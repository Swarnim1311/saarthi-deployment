package com.saarthi.policy;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure unit tests: RSS date parsing, HTML stripping, keyword filter. */
class PibPolicySourceTest {

    private final PibPolicySource source =
            new PibPolicySource("https://example.invalid/rss", 1);

    @Test
    void parsesRfc1123PubDate() {
        assertEquals(LocalDate.of(2026, 9, 20),
                PibPolicySource.parsePubDate("Sun, 20 Sep 2026 10:15:00 GMT"));
        assertEquals(LocalDate.of(2026, 9, 20),
                PibPolicySource.parsePubDate("20 Sep 2026 10:15:00 GMT"));
    }

    @Test
    void survivesInconsistentWeekdayInFeed() {
        // 20 Sep 2026 is a Sunday; a feed that says "Sat" must still be dated
        // correctly rather than silently falling back to the fetch date.
        assertEquals(LocalDate.of(2026, 9, 20),
                PibPolicySource.parsePubDate("Sat, 20 Sep 2026 10:15:00 GMT"));
    }

    @Test
    void unknownDateStaysNullInsteadOfToday() {
        // A missing/unparseable feed date is unknown: it must be stored null
        // and shown as "Unavailable", never replaced with the fetch date.
        assertNull(PibPolicySource.parsePubDate("not-a-date"));
        assertNull(PibPolicySource.parsePubDate(""));
        assertNull(PibPolicySource.parsePubDate(null));
    }

    @Test
    void stripsHtmlFromDescription() {
        assertEquals("PM KISAN support for farmers & fields",
                PibPolicySource.cleanHtml(
                        "<p>PM KISAN <b>support</b> for farmers &amp; fields</p>"));
        assertEquals("", PibPolicySource.cleanHtml(null));
    }

    @Test
    void filtersAgricultureItems() {
        assertTrue(source.isAgricultureRelated("PM KISAN support", "farmer instalment"));
        assertFalse(source.isAgricultureRelated("Cricket World Cup", "match schedule"));
    }

    @Test
    void parsesLiveShapedFeed() throws Exception {
        // Mirrors the real PIB feed shape observed live: title + link only,
        // no description, no pubDate, optional category.
        String rss = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<rss version=\"2.0\"><channel>"
                + "<title>Press Information Bureau</title>"
                + "<item><title>किसान सम्मान निधि की नई किस्त जारी</title>"
                + "<link>https://pib.gov.in/PressReleaseIframePage.aspx?PRID=2315001</link>"
                + "<category>Scheme</category></item>"
                + "<item><title>Cricket World Cup schedule announced</title>"
                + "<link>https://pib.gov.in/PressReleaseIframePage.aspx?PRID=999</link></item>"
                + "<item><title></title>"
                + "<link>https://pib.gov.in/PressReleaseIframePage.aspx?PRID=1000</link></item>"
                + "<item><title>Wheat procurement rises for farmers</title>"
                + "<link>https://pib.gov.in/PressReleaseIframePage.aspx?PRID=2315002</link>"
                + "<pubDate>Sun, 20 Sep 2026 10:15:00 GMT</pubDate></item>"
                + "</channel></rss>";
        java.util.List<PolicySourceItem> items = source.parse(
                new java.io.ByteArrayInputStream(
                        rss.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(2, items.size(), "only agriculture items with title+link survive");
        assertEquals("https://pib.gov.in/PressReleaseIframePage.aspx?PRID=2315001",
                items.get(0).getSourceUrl(), "official URL preserved verbatim");
        assertEquals("Scheme", items.get(0).getCategory());
        assertNull(items.get(0).getPublishedDate(), "missing feed date stays null");
        assertEquals(LocalDate.of(2026, 9, 20), items.get(1).getPublishedDate());
    }

    @Test
    void emptyFeedParsesToEmpty() throws Exception {
        String rss = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<rss version=\"2.0\"><channel><title>PIB</title></channel></rss>";
        assertTrue(source.parse(new java.io.ByteArrayInputStream(
                rss.getBytes(java.nio.charset.StandardCharsets.UTF_8))).isEmpty());
    }

    @Test
    void malformedFeedThrows() {
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> source.parse(
                new java.io.ByteArrayInputStream(
                        "<rss><channel><item>broken".getBytes(
                                java.nio.charset.StandardCharsets.UTF_8))));
    }

    @Test
    void unreachableFeedIsNotReachable() {
        PibPolicySource.FetchOutcome outcome = source.fetchDetailed();
        assertFalse(outcome.reachable(), "example.invalid must not resolve");
        assertTrue(outcome.items().isEmpty());
    }

    @Test
    void listingBaseFollowsRssHost() {
        assertEquals("https://www.pib.gov.in",
                PibPolicySource.deriveBase("https://www.pib.gov.in/RssMain.aspx?ModId=6&Lang=1&Regid=3"));
        assertEquals("https://example.invalid",
                PibPolicySource.deriveBase("https://example.invalid/rss"));
    }

    @Test
    void unionDedupesSamePridAcrossRssAndListingUrls() {
        PolicySourceItem rss = new PolicySourceItem("Rabi procurement",
                "", "PIB", "https://pib.gov.in/PressReleaseIframePage.aspx?PRID=2316100",
                null);
        PolicySourceItem listing = new PolicySourceItem(
                "Union Minister Calls for Timely Procurement for Rabi Season 2026",
                "", "PIB", "https://www.pib.gov.in/PressReleaseDetail.aspx?PRID=2316100",
                java.time.LocalDate.of(2026, 9, 28));
        java.util.List<PolicySourceItem> union =
                PibPolicySource.union(java.util.List.of(rss), java.util.List.of(listing));
        assertEquals(1, union.size(), "same PRID from RSS + listing must collapse to one");
    }

    @Test
    void parsesListingWithMinistryAttributionAndDate() {
        String html = "<html><body><div class=\"content-area\">"
                + "<ul><li><h3 class='font104'>Ministry of Agriculture &amp; Farmers Welfare</h3>"
                + "<ul class='num'><li><a title='Rabi Campaign 2026 to Begin Tomorrow' "
                + "href='/PressReleaseDetail.aspx?PRID=2315470' target=\"_blank\">"
                + "Rabi Campaign 2026 to Begin Tomorrow </a></li></ul></li></ul>"
                + "<ul><li><h3>Prime Minister's Office</h3><ul><li>"
                + "<a title='Prime Minister congratulates medal winners at Asian Games' "
                + "href=\"/PressReleaseDetail.aspx?PRID=2316259\">congratulates</a>"
                + "</li></ul></li></ul>"
                + "</div></body></html>";
        java.util.List<PolicySourceItem> items = source.parseListing(
                html, java.time.LocalDate.of(2026, 9, 27));
        assertEquals(1, items.size(), "only the agriculture row survives; sports excluded");
        assertEquals("Rabi Campaign 2026 to Begin Tomorrow", items.get(0).getTitle());
        assertEquals("https://example.invalid/PressReleaseDetail.aspx?PRID=2315470",
                items.get(0).getSourceUrl(),
                "official path preserved verbatim against the configured host");
        assertEquals("Ministry of Agriculture & Farmers Welfare", items.get(0).getCategory(),
                "ministry heading stored as category");
        assertEquals(java.time.LocalDate.of(2026, 9, 27), items.get(0).getPublishedDate(),
                "listing day is PIB's own date attribution");
    }

    @Test
    void listingKeepsRealAgriTitlesAndRejectsCampaignNotices() {
        assertTrue(source.isAgricultureRelated(
                "Union Minister Calls for Data-Driven Planning, Timely Procurement for Rabi Season 2026", ""));
        assertTrue(source.isAgricultureRelated(
                "Honble Minister launched Live Stock insurance portal", ""));
        assertTrue(source.isAgricultureRelated(
                "Insuring the Blue Revolution: PM-MKSSY Risk Management for Aquaculture Farmers", ""));
        assertTrue(source.isAgricultureRelated(
                "Government Cuts Import Duty on Major Edible Oils", ""));
        assertTrue(source.isAgricultureRelated(
                "Kanda Express carrying onions from Government buffer stock to reach Kolkata", ""));
        assertTrue(source.isAgricultureRelated(
                "National Rainfed Area Authority meeting for Watershed Development", ""));
        assertTrue(source.isAgricultureRelated(
                "Agrinnovate India Facilitates ICAR Partnerships for CSR Support in Agriculture", ""));
        assertTrue(source.isAgricultureRelated(
                "Interest-Free Loan per Barn for FCV Tobacco Growers", ""));
        assertTrue(source.isAgricultureRelated(
                "APEDA Facilitates Export of GI-Tagged Gulbarga Tur Dal", ""));
        assertTrue(source.isAgricultureRelated(
                "Digitisation and Modernisation of Land Records", ""));
        assertTrue(source.isAgricultureRelated(
                "National Cooperative Training Framework 2026", ""));
        assertTrue(source.isAgricultureRelated(
                "किसान सम्मान निधि की नई किस्त जारी, रबी बुवाई से पहले खरीद केंद्र", ""));
    }

    @Test
    void wordBoundariesStopSubstringTraps() {
        // धान (paddy) hides inside प्रधानमंत्री (prime minister) and
        // अनुसंधान (research); oil inside soil; rice inside price.
        assertFalse(source.isAgricultureRelated(
                "प्रधानमंत्री ने एशियाई खेलों में पदक जीतने पर एथलीट को बधाई दी", ""));
        assertFalse(source.isAgricultureRelated(
                "Union Education Minister holds bilateral meeting on research and space cooperation", ""));
        assertFalse(source.isAgricultureRelated(
                "Oil India Limited announces quarterly results", ""));
        assertFalse(source.isAgricultureRelated(
                "Steel price rise worries industry", ""));
        assertFalse(source.isAgricultureRelated(
                "Committee finds scapegoat in fund misuse row", ""),
                "goat inside scapegoat must never qualify");
        // ...yet genuine items using those words still pass.
        assertTrue(source.isAgricultureRelated(
                "प्रधानमंत्री किसान सम्मान निधि की नई किस्त जारी", ""));
        assertTrue(source.isAgricultureRelated(
                "धान की खरीद के लिए मंडी में किसान", ""));
        assertTrue(source.isAgricultureRelated(
                "Government cuts import duty on edible oils", ""));
        assertTrue(source.isAgricultureRelated(
                "MSP for jute and copra announced for farmers", ""));
        assertTrue(source.isAgricultureRelated(
                "Soil Health Card scheme extended to more farmers", ""));
        assertTrue(source.isAgricultureRelated(
                "RARI Patna organises mini expo on traditional foods and millets", ""));
        assertTrue(source.isAgricultureRelated(
                "Wheat prices rise, bringing relief to farmers", ""));
    }

    @Test
    void listingRejectsSportsAndHygieneNotices() {
        assertFalse(source.isAgricultureRelated(
                "Prime Minister congratulates Vithya Ramraj on winning Bronze in Womens 400m hurdles at Asian Games", ""));
        assertFalse(source.isAgricultureRelated(
                "Prime Minister congratulates Mens Kabaddi Team on retaining Asian Games Gold", ""));
        assertFalse(source.isAgricultureRelated(
                "Department of Fertilizers to participate in Special Campaign 6", ""),
                "cleanliness-campaign notice is not a farmer policy");
        assertFalse(source.isAgricultureRelated(
                "ICAR Institutes Promote Cleanliness and Sustainable Waste Management", ""));
        assertFalse(source.isAgricultureRelated(
                "Department of Food and Public Distribution Continues Swachhata Hi Seva-2026 Campaign", ""));
        assertFalse(source.isAgricultureRelated(
                "प्रधानमंत्री ने एशियाई खेलों में महिलाओं की दौड़ में कांस्य पदक जीतने पर एथलीट को बधाई दी", ""));
        assertFalse(source.isAgricultureRelated(
                "India-France Space Cooperation Enters New Phase", ""),
                "'cooperation' alone must not qualify a space item");
        assertFalse(source.isAgricultureRelated(
                "Major boost: MoD signs contract for anti-airfield weapons", ""));
    }

    @Test
    void strongSchemeSignalRequalifiesMixedItem() {
        assertTrue(source.isAgricultureRelated(
                "Documentary film on PM-KISAN success screened for farmers", ""),
                "genuine scheme content survives an entertainment overlap");
    }

    @Test
    void formFieldsAndOptionValuesParse() {
        String html = "<input name=\"__VIEWSTATE\" value=\"abc\" />"
                + "<select name=\"ctl00$ContentPlaceHolder1$ddlday\">"
                + "<option value=\"1\">1</option>"
                + "<option selected=\"selected\" value=\"29\">29</option></select>";
        java.util.Map<String, String> fields = PibPolicySource.formFields(html);
        assertEquals("abc", fields.get("__VIEWSTATE"));
        assertEquals("29", fields.get("ctl00$ContentPlaceHolder1$ddlday"));
        assertEquals("9", PibPolicySource.optionValue(
                "<select name=\"m\"><option value=\"9\">September</option></select>", "m", "September"));
    }
}
