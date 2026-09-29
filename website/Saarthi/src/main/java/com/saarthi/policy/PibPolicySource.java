package com.saarthi.policy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class PibPolicySource implements PolicySource {

    private static final Logger log = LoggerFactory.getLogger(PibPolicySource.class);

    static final String SOURCE_NAME = "PIB";

    /** RFC-1123, the format RSS feeds publish dates in. */
    private static final DateTimeFormatter RFC_1123 = DateTimeFormatter.RFC_1123_DATE_TIME;

    /** PIB release pages are timestamped in IST; the listing date walk uses it too. */
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Cap on listing day-walk requests per refresh: one GET + warm-up + ≤7 POSTs. */
    static final int LISTING_MAX_DAYS = 7;
    /** Stop the day-walk early once this many agriculture items are collected. */
    static final int LISTING_EARLY_STOP = 25;

    private final String rssUrl;
    private final int timeoutMillis;
    private final String listingBaseUrl;

    public PibPolicySource(
            @Value("${saarthi.policy.pib-rss-url:https://www.pib.gov.in/RssMain.aspx?ModId=6&Lang=1&Regid=3}") String rssUrl,
            @Value("${saarthi.policy.fetch-timeout-seconds:15}") int timeoutSeconds) {
        this(rssUrl, timeoutSeconds, "");
    }

    @Autowired
    public PibPolicySource(
            @Value("${saarthi.policy.pib-rss-url:https://www.pib.gov.in/RssMain.aspx?ModId=6&Lang=1&Regid=3}") String rssUrl,
            @Value("${saarthi.policy.fetch-timeout-seconds:15}") int timeoutSeconds,
            @Value("${saarthi.policy.pib-listing-url:}") String listingUrl) {
        this.rssUrl = (rssUrl == null || rssUrl.isBlank())
                ? "https://www.pib.gov.in/RssMain.aspx?ModId=6&Lang=1&Regid=3"
                : rssUrl.trim();
        this.timeoutMillis = (timeoutSeconds <= 0 ? 15 : timeoutSeconds) * 1000;
        String explicit = listingUrl == null ? "" : listingUrl.trim();
        this.listingBaseUrl = explicit.isEmpty() ? deriveBase(this.rssUrl) : explicit;
    }

    /** Host root (scheme + authority) backing the official listing pages. */
    static String deriveBase(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme() == null ? "https" : uri.getScheme();
            String host = uri.getHost() == null ? "www.pib.gov.in" : uri.getHost();
            int port = uri.getPort();
            return scheme + "://" + host + (port < 0 ? "" : ":" + port);
        } catch (Exception e) {
            return "https://www.pib.gov.in";
        }
    }

    @Override
    public String getSourceName() {
        return SOURCE_NAME;
    }

    /** Outcome of one retrieval attempt: items plus honest reachability. */
    public record FetchOutcome(List<PolicySourceItem> items, boolean reachable, String error) {
    }

    @Override
    public List<PolicySourceItem> fetch() {
        return fetchDetailed().items();
    }

    /**
     * Retrieve official PIB agriculture items from every configured official
     * mechanism, distinguishing "source unreachable" from "source reachable but
     * empty". Never throws: transport/parse failure yields
     * {@code reachable=false} so the refresh path can report an honest
     * "official source currently unavailable" state instead of silent zero.
     *
     * <p><b>Why two mechanisms.</b> PIB publishes no agriculture-specific RSS:
     * {@code RssMain.aspx?ModId=6} is the generic all-releases feed capped at
     * ~20 current items with title+link only. When that window carries no
     * agriculture releases (e.g. a sports-news cycle), the union below falls
     * back to the official ministry listing ({@code allRel.aspx}, same
     * pib.gov.in host) walked back up to a week, which carries ministry
     * attribution and multi-day history. Both are official PIB data.
     */
    public FetchOutcome fetchDetailed() {
        List<PolicySourceItem> rssItems = List.of();
        boolean rssReachable = false;
        String rssError = null;
        try {
            rssItems = fetchRss();
            rssReachable = true;
        } catch (Exception e) {
            rssError = e.getMessage();
            log.warn("Failed to fetch PIB RSS ({}): {}", e.getClass().getSimpleName(), e.getMessage());
        }

        List<PolicySourceItem> listingItems = List.of();
        boolean listingReachable = false;
        String listingError = null;
        try {
            listingItems = fetchListing();
            listingReachable = true;
        } catch (Exception e) {
            listingError = e.getMessage();
            log.warn("Failed to fetch PIB listing ({}): {}", e.getClass().getSimpleName(), e.getMessage());
        }

        List<PolicySourceItem> union = union(rssItems, listingItems);
        boolean reachable = rssReachable || listingReachable;
        String error = rssError != null ? rssError : listingError;
        log.info("PIB fetch: rss reachable={} agri={} | listing reachable={} agri={} | union={}",
                rssReachable, rssItems.size(), listingReachable, listingItems.size(), union.size());
        return new FetchOutcome(union, reachable, error);
    }

    /** Merge RSS + listing items, deduping on the official PRID when present. */
    static List<PolicySourceItem> union(List<PolicySourceItem> rss, List<PolicySourceItem> listing) {
        Map<String, PolicySourceItem> merged = new LinkedHashMap<>();
        if (rss != null) {
            for (PolicySourceItem item : rss) {
                merged.putIfAbsent(dedupKey(item), item);
            }
        }
        if (listing != null) {
            for (PolicySourceItem item : listing) {
                merged.putIfAbsent(dedupKey(item), item);
            }
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * Canonical identity for one official release: the PIB PRID when the URL
     * carries one (RSS {@code PressReleaseIframePage.aspx} and listing
     * {@code PressReleaseDetail.aspx} links share the same PRID), otherwise
     * the trimmed URL.
     */
    static String dedupKey(PolicySourceItem item) {
        String url = item == null || item.getSourceUrl() == null ? "" : item.getSourceUrl().trim();
        Matcher m = PRID_PATTERN.matcher(url);
        if (m.find()) {
            return "PRID:" + m.group(1);
        }
        return "URL:" + url;
    }

    private static final Pattern PRID_PATTERN =
            Pattern.compile("[?&]PRID=(\\d+)", Pattern.CASE_INSENSITIVE);

    private List<PolicySourceItem> fetchRss() throws Exception {
        URL url = URI.create(rssUrl).toURL();
        URLConnection connection = url.openConnection();
        connection.setConnectTimeout(timeoutMillis);
        connection.setReadTimeout(timeoutMillis);
        connection.setRequestProperty("User-Agent", "SAARTHI-PolicyMonitor/1.0");
        try (InputStream inputStream = connection.getInputStream()) {
            List<PolicySourceItem> items = parse(inputStream);
            log.debug("PIB RSS agriculture items matched: {}", items.size());
            return items;
        }
    }

    // ------------------------------------------------------------------
    // Official PIB listing (allRel.aspx): ministry-filtered, multi-day.
    // ------------------------------------------------------------------

    static final String LISTING_PATH = "/allRel.aspx";
    static final String MINISTRY_FIELD = "ctl00$ContentPlaceHolder1$ddlMinistry";
    static final String DAY_FIELD = "ctl00$ContentPlaceHolder1$ddlday";
    static final String MONTH_FIELD = "ctl00$ContentPlaceHolder1$ddlMonth";
    static final String YEAR_FIELD = "ctl00$ContentPlaceHolder1$ddlYear";

    private static final Pattern INPUT_PATTERN =
            Pattern.compile("<input[^>]*name=\"([^\"]+)\"[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern VALUE_PATTERN =
            Pattern.compile("value=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern SELECT_PATTERN =
            Pattern.compile("<select[^>]*name=\"([^\"]+)\"(.*?)</select>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern OPTION_PATTERN =
            Pattern.compile("<option([^>]*)>(.*?)</option>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern OPTION_VALUE_PATTERN =
            Pattern.compile("value=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);

    /**
     * Walk the official PIB release listing back up to a week, collecting
     * agriculture items with ministry attribution. Throws when the listing
     * host cannot be reached at all; a reachable-but-empty listing yields an
     * empty list (honestly "no items", not "unreachable").
     */
    List<PolicySourceItem> fetchListing() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofMillis(Math.min(timeoutMillis, 10_000)))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        // Warm the session the way browsers do so the listing honours English.
        get(client, listingBaseUrl + "/index.aspx?lang=1&reg=3");
        String listHtml = get(client, listingBaseUrl + LISTING_PATH + "?lang=1&reg=3");
        Map<String, String> fields = formFields(listHtml);

        List<PolicySourceItem> out = new ArrayList<>();
        LocalDate today = LocalDate.now(IST);
        int daysQueried = 0;
        int raw = 0;
        for (int offset = 0; offset < LISTING_MAX_DAYS && out.size() < LISTING_EARLY_STOP; offset++) {
            LocalDate day = today.minusDays(offset);
            Map<String, String> post = new LinkedHashMap<>(fields);
            post.put("__EVENTTARGET", MINISTRY_FIELD);
            post.put("__EVENTARGUMENT", "");
            post.put(MINISTRY_FIELD, "0");
            post.put(DAY_FIELD, optionValue(listHtml, DAY_FIELD, String.valueOf(day.getDayOfMonth())));
            post.put(MONTH_FIELD, optionValue(listHtml, MONTH_FIELD, monthName(day)));
            post.put(YEAR_FIELD, optionValue(listHtml, YEAR_FIELD, String.valueOf(day.getYear())));
            String response = postForm(client, listingBaseUrl + LISTING_PATH + "?lang=1&reg=3", post);
            daysQueried++;
            fields = formFields(response);
            listHtml = response;
            List<PolicySourceItem> parsed = parseListing(response, day);
            raw += countListingRows(response);
            out.addAll(parsed);
        }
        log.info("PIB listing: days queried={} raw rows={} agriculture retained={}",
                daysQueried, raw, out.size());
        return out;
    }

    private String get(HttpClient client, String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(Math.min(timeoutMillis, 10_000)))
                .header("User-Agent", "SAARTHI-PolicyMonitor/1.0")
                .header("Accept-Language", "en-IN,en;q=0.9")
                .GET()
                .build();
        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new java.io.IOException("PIB listing GET " + url + " -> HTTP " + response.statusCode());
        }
        return response.body();
    }

    private String postForm(HttpClient client, String url, Map<String, String> fields) throws Exception {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (body.length() > 0) {
                body.append('&');
            }
            body.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            body.append('=');
            body.append(URLEncoder.encode(entry.getValue() == null ? "" : entry.getValue(),
                    StandardCharsets.UTF_8));
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(Math.min(timeoutMillis, 10_000)))
                .header("User-Agent", "SAARTHI-PolicyMonitor/1.0")
                .header("Accept-Language", "en-IN,en;q=0.9")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new java.io.IOException("PIB listing POST " + url + " -> HTTP " + response.statusCode());
        }
        return response.body();
    }

    private static String monthName(LocalDate day) {
        String full = Month.of(day.getMonthValue()).name().toLowerCase(Locale.ROOT);
        return full.substring(0, 1).toUpperCase(Locale.ROOT) + full.substring(1);
    }

    /** All hidden inputs plus current select values from one listing page. */
    static Map<String, String> formFields(String html) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (html == null) {
            return fields;
        }
        Matcher inputs = INPUT_PATTERN.matcher(html);
        while (inputs.find()) {
            String tag = inputs.group(0);
            Matcher value = VALUE_PATTERN.matcher(tag);
            fields.put(inputs.group(1), value.find() ? value.group(1) : "");
        }
        Matcher selects = SELECT_PATTERN.matcher(html);
        while (selects.find()) {
            fields.put(selects.group(1), selectedOption(selects.group(2)));
        }
        return fields;
    }

    private static String selectedOption(String selectBody) {
        String first = "";
        Matcher options = OPTION_PATTERN.matcher(selectBody);
        while (options.find()) {
            Matcher value = OPTION_VALUE_PATTERN.matcher(options.group(1));
            String v = value.find() ? value.group(1) : options.group(2).trim();
            if (first.isEmpty()) {
                first = v;
            }
            if (options.group(1).toLowerCase(Locale.ROOT).contains("selected")) {
                return v;
            }
        }
        return first;
    }

    /**
     * Resolve the option value whose visible text equals {@code text}
     * (case-insensitive); fall back to {@code text} itself so the postback
     * still carries a usable value when the markup differs.
     */
    static String optionValue(String html, String selectName, String text) {
        if (html != null) {
            Matcher selects = SELECT_PATTERN.matcher(html);
            while (selects.find()) {
                if (!selects.group(1).equals(selectName)) {
                    continue;
                }
                Matcher options = OPTION_PATTERN.matcher(selects.group(2));
                while (options.find()) {
                    String label = options.group(2).replaceAll("\\s+", " ").trim();
                    if (label.equalsIgnoreCase(text)
                            || label.equalsIgnoreCase(text.replaceFirst("^0+(\\d)", "$1"))) {
                        Matcher value = OPTION_VALUE_PATTERN.matcher(options.group(1));
                        if (value.find()) {
                            return value.group(1);
                        }
                    }
                }
            }
        }
        return text;
    }

    // -- listing rows: <h3>ministry</h3> headings followed by PRID anchors. --

    private static final Pattern ROW_PATTERN = Pattern.compile(
            "<h3[^>]*>(.*?)</h3>|<a\\b[^>]*?PRID=(\\d+)[^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TITLE_ATTR_PATTERN = Pattern.compile(
            "title\\s*=\\s*'([^']*)'|title\\s*=\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern HREF_ATTR_PATTERN = Pattern.compile(
            "href\\s*=\\s*'([^']*)'|href\\s*=\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);

    /**
     * Parse one day's listing response into agriculture items. The queried
     * {@code day} is PIB's own date attribution for every row shown, so it is
     * stored as the published date; the ministry heading is stored as the
     * category. Rows that fail the agriculture filter are dropped — never
     * kept merely to populate the page.
     */
    List<PolicySourceItem> parseListing(String html, LocalDate day) {
        List<PolicySourceItem> items = new ArrayList<>();
        if (html == null || html.isEmpty()) {
            return items;
        }
        int start = html.indexOf("content-area");
        String segment = start >= 0 ? html.substring(start) : html;
        String ministry = "";
        Matcher rows = ROW_PATTERN.matcher(segment);
        while (rows.find()) {
            if (rows.group(1) != null) {
                String heading = cleanText(rows.group(1));
                if (!heading.isEmpty() && heading.length() < 200) {
                    ministry = heading;
                }
                continue;
            }
            String prid = rows.group(2);
            String tag = rows.group(0);
            String title = attrValue(TITLE_ATTR_PATTERN, tag);
            if (title.isEmpty()) {
                title = cleanText(rows.group(3));
            }
            String href = attrValue(HREF_ATTR_PATTERN, tag);
            if (title.isEmpty() || href.isEmpty()) {
                continue;
            }
            if (!isAgricultureRelated(title, "")) {
                continue;
            }
            String url = absoluteUrl(href);
            PolicySourceItem item = new PolicySourceItem(title, "", SOURCE_NAME, url, day);
            item.setCategory(ministry.isEmpty() ? null : ministry);
            items.add(item);
        }
        return items;
    }

    /** Raw release-row count (before filtering) for honest fetch diagnostics. */
    static int countListingRows(String html) {
        if (html == null || html.isEmpty()) {
            return 0;
        }
        int start = html.indexOf("content-area");
        String segment = start >= 0 ? html.substring(start) : html;
        Matcher rows = ROW_PATTERN.matcher(segment);
        int n = 0;
        while (rows.find()) {
            if (rows.group(2) != null) {
                n++;
            }
        }
        return n;
    }

    private static String attrValue(Pattern pattern, String tag) {
        Matcher m = pattern.matcher(tag);
        if (!m.find()) {
            return "";
        }
        String single = m.group(1);
        if (single != null) {
            return single.replaceAll("\\s+", " ").trim();
        }
        String dbl = m.group(2);
        return dbl == null ? "" : dbl.replaceAll("\\s+", " ").trim();
    }

    private static String cleanText(String raw) {
        if (raw == null) {
            return "";
        }
        String noTags = raw.replaceAll("(?s)<[^>]*>", " ");
        return noTags.replace("&amp;", "&").replace("&nbsp;", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private String absoluteUrl(String href) {
        String clean = href.replace("&amp;", "&").trim();
        if (clean.toLowerCase(Locale.ROOT).startsWith("http")) {
            return clean;
        }
        if (clean.startsWith("/")) {
            return listingBaseUrl + clean;
        }
        return listingBaseUrl + "/" + clean;
    }

    /**
     * Parse one RSS document stream into agriculture items. Throws on
     * malformed XML so callers can tell "broken feed" apart from "no items".
     */
    List<PolicySourceItem> parse(InputStream inputStream) throws Exception {
        List<PolicySourceItem> items = new ArrayList<>();
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setNamespaceAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document document = builder.parse(inputStream);
        document.getDocumentElement().normalize();
        NodeList rssItems = document.getElementsByTagName("item");
        log.debug("PIB RSS items found: {}", rssItems.getLength());
        for (int i = 0; i < rssItems.getLength(); i++) {
            Node node = rssItems.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element element = (Element) node;
            String title = getElementText(element, "title");
            String summary = cleanHtml(getElementText(element, "description"));
            String link = getElementText(element, "link");
            String pubDate = getElementText(element, "pubDate");
            String category = getElementText(element, "category");
            if (title.isBlank() || link.isBlank()) {
                continue;
            }
            if (!isAgricultureRelated(title, summary)) {
                continue;
            }
            PolicySourceItem item =
                    new PolicySourceItem(title, summary, SOURCE_NAME, link, parsePubDate(pubDate));
            item.setCategory(category.isBlank() ? null : category);
            items.add(item);
        }
        return items;
    }

    String getElementText(Element element, String tagName) {
        NodeList nodes = element.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            return "";
        }
        Node node = nodes.item(0);
        if (node == null) {
            return "";
        }
        String text = node.getTextContent();
        return text == null ? "" : text.trim();
    }

    static String cleanHtml(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String noTags = raw.replaceAll("(?s)<[^>]*>", " ");
        String decoded = noTags
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'");
        return decoded.replaceAll("\\s+", " ").trim();
    }

    /**
     * Parse an RSS date, or {@code null} when the feed carries none or an
     * unparseable one. A missing date is unknown — it is stored null and shown
     * as "Unavailable", never replaced with the fetch date.
     */
    static LocalDate parsePubDate(String pubDate) {
        if (pubDate != null && !pubDate.isBlank()) {
            String text = pubDate.trim();
            try {
                return ZonedDateTime.parse(text, RFC_1123).toLocalDate();
            } catch (DateTimeParseException e) {
                // Retry below without the optional leading weekday.
            }
            String noDayOfWeek = text.replaceFirst("^[A-Za-z]{3}\\s*,\\s*", "");
            if (!noDayOfWeek.equals(text)) {
                try {
                    return ZonedDateTime.parse(noDayOfWeek, RFC_1123).toLocalDate();
                } catch (DateTimeParseException e) {
                    // Fall through to null: unknown, not today.
                }
            }
        }
        return null;
    }

    /**
     * Letter-boundary keyword matching. A bare substring test is dangerously
     * wrong here: Devanagari "धान" (paddy) hides inside "प्रधानमंत्री" (prime
     * minister) and "अनुसंधान" (research), Latin "oil" inside "soil", "rice"
     * inside "price", "goat" inside "scapegoat". The boundary below treats
     * combining marks (anusvara, matras: {@code \p{M}}) as word characters so
     * inflected forms ("किसानों", "खेलों") do NOT match their stems — those
     * forms are listed explicitly instead.
     */
    static Pattern wordPattern(String keyword) {
        return Pattern.compile("(?<=^|[^\\p{L}\\p{M}])" + Pattern.quote(keyword)
                + "(?=[^\\p{L}\\p{M}]|$)");
    }

    private static List<Pattern> wordPatterns(List<String> keywords) {
        List<Pattern> out = new ArrayList<>(keywords.size());
        for (String keyword : keywords) {
            out.add(wordPattern(keyword));
        }
        return out;
    }

    private static final List<String> KEYWORDS = List.of(
            "agriculture", "agricultural", "farmer", "farmers", "farming",
            "crop", "crops", "cropping", "kisan", "pmkisan", "cultivation", "irrigation",
            "fertilizer", "fertilizers", "fertiliser", "fertilisers",
            "pesticide", "pesticides", "horticulture",
            "agri", "animal husbandry", "dairy", "fisheries", "fishery", "fishing",
            "aquaculture", "blue revolution", "mkssy",
            "livestock", "live stock", "poultry", "cattle", "goat", "sheep", "piggery",
            "food grain", "food grains",
            "rice", "wheat", "maize", "onion", "onions", "tomato", "tomatoes",
            "potato", "potatoes",
            "soybean", "soyabean", "mustard", "groundnut", "sugarcane",
            "cotton", "paddy", "basmati", "millet", "millets", "pulses",
            "tur", "tur dal", "toor dal", "chana", "moong", "urad", "masur", "arhar",
            "bajra", "jowar", "ragi", "barley",
            "sunflower", "safflower", "sesamum", "sesame",
            "jute", "copra", "tobacco",
            "oilseed", "oils", "edible oil", "palm oil",
            "soil", "soil health",
            "rabi", "kharif", "procurement", "buffer stock", "buffer stocks",
            "watershed", "rainfed", "land record", "land records", "grower", "growers",
            "cooperative", "cooperatives", "grameen", "gramin", "krishi",
            "pm-kisan", "pm kisan", "pmfby", "fasal bima", "crop insurance",
            "pm-aasha", "aasha", "annadata",
            // No bare mandi: it collides with Mandi town in Himachal Pradesh.
            // Genuine market items carry msp, procurement or e-nam as well.
            "e-nam", "enam", "msp", "minimum support price",
            "subsidy", "subsidies", "agricultural credit", "kisan credit",
            "kcc", "farmer welfare", "crop production", "rural development",
            "panchayat", "panchayati", "allied sector",
            "कृषि", "किसान", "किसानों", "कृषक", "खेती", "फसल", "फसलों",
            "सिंचाई", "उर्वरक", "खाद", "खाद्य", "कीटनाशक", "बागवानी",
            "पशुपालन", "डेयरी", "मत्स्य", "मछली", "मछलियों", "पशुधन",
            "अनाज", "धान", "गेहूं", "मक्का", "सोयाबीन",
            "पंचायत", "पंचायती",
            "रबी", "खरीफ", "खरीफ़", "खरीद", "एमएसपी", "मंडी",
            "प्याज", "टमाटर", "आलू");

    private static final List<String> EXCLUSIONS = List.of(
            "cricket", "football", "hockey", "kabaddi", "badminton", "tennis",
            "olympic", "paralympic", "athlete", "athletes", "athletics", "wrestling",
            "boxing", "chess", "tournament", "tournaments", "world cup", "match schedule",
            "asian games", "khelo", "stadium", "stadiums", "medal", "medals",
            "sports", "sportsperson", "coach",
            "film", "films", "cinema", "actor", "actors", "actress", "music concert",
            "swachhata", "swachh", "special campaign", "cleanliness",
            "hindi pakhwada", "pakhwada", "pakhwade", "defence", "defense", "federalism",
            "खेल", "खेलों", "एथलीट", "एथलीटों", "पदक", "पदकों",
            "क्रिकेट", "ओलंपिक", "एशियाई खेल",
            "स्वच्छता", "स्वच्छ", "विशेष अभियान",
            "हिंदी पखवाड़ा", "पखवाड़ा", "पखवाड़े", "सलाहकार समिति");

    private static final List<String> STRONG = List.of(
            "pm-kisan", "pm kisan", "pmkisan", "pmfby", "fasal bima",
            "crop insurance", "e-nam", "enam", "kcc", "minimum support price",
            "kisan credit", "farmer welfare", "crop production",
            "pm-aasha", "aasha", "annadata", "mkssy", "blue revolution",
            "agriculture", "agricultural", "farmer", "farmers", "farming",
            "kisan", "irrigation", "horticulture", "animal husbandry",
            "fisheries", "fishery", "fishing", "aquaculture",
            "livestock", "live stock", "poultry", "cattle", "goat", "sheep",
            "subsidy", "subsidies", "procurement",
            "rabi", "kharif", "watershed", "rainfed", "krishi",
            "tur", "tur dal", "toor dal", "chana", "moong", "urad", "masur", "arhar",
            "bajra", "jowar", "ragi", "barley", "millet", "millets",
            "sunflower", "safflower", "sesamum", "sesame",
            "jute", "copra", "tobacco", "grower", "growers",
            "onion", "onions", "tomato", "tomatoes", "potato", "potatoes",
            "buffer stock", "buffer stocks", "land record", "land records",
            "soil", "soil health", "cropping", "food grains",
            "rice", "wheat", "maize", "cotton", "paddy", "basmati", "pulses",
            "soybean", "soyabean", "mustard", "groundnut", "sugarcane", "oilseed",
            "oils", "edible oil", "palm oil",
            "कृषि", "किसान", "किसानों", "खेती", "फसल", "फसलों",
            "सिंचाई", "पशुपालन", "मत्स्य",
            "रबी", "खरीफ", "खरीफ़", "खरीद", "एमएसपी", "मंडी",
            "प्याज", "टमाटर", "आलू");

    private static final List<Pattern> KEYWORD_PATTERNS = wordPatterns(KEYWORDS);
    private static final List<Pattern> EXCLUSION_PATTERNS = wordPatterns(EXCLUSIONS);
    private static final List<Pattern> STRONG_PATTERNS = wordPatterns(STRONG);

    static boolean anyWordMatch(List<Pattern> patterns, String text) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    boolean isAgricultureRelated(String title, String summary) {
        String text = (title + " " + summary).toLowerCase(Locale.ROOT);
        // Exclusions first: sport/athlete/entertainment/cleanliness-drive and
        // unrelated-domain items must never pass on a generic word overlap. A
        // strong agriculture scheme/production signal below can still
        // re-qualify a genuinely mixed item.
        boolean excluded = anyWordMatch(EXCLUSION_PATTERNS, text);
        if (!anyWordMatch(KEYWORD_PATTERNS, text)) {
            return false;
        }
        if (!excluded) {
            return true;
        }
        // Excluded topic present: keep only items carrying a strong,
        // unambiguous agriculture scheme/production signal anywhere in the
        // text (not a bare generic word).
        return anyWordMatch(STRONG_PATTERNS, text);
    }
}