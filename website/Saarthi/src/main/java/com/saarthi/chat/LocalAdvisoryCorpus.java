package com.saarthi.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic local answer source, used whenever Gemini is unavailable.
 *
 * <p><b>Provenance rule.</b> Crop facts come from the repository's existing
 * source-cited reference, {@code agronomy/crop_reference.json} (PAU Package of
 * Practices for Kharif/Rabi Crops of Punjab, and ICAR-IARI guidelines), with its
 * {@code source_ids} carried through. Nothing here invents a citation, a
 * threshold, or a number. General guidance is written as cautious, check-with-your-
 * officer advice and carries no numeric claim that the reference does not state.
 *
 * <p><b>Determinism.</b> The same question always produces the same answer: the
 * topic is selected by ordered keyword rules, and the crop selected by explicit
 * name. There is no sampling and no generation.
 *
 * <p><b>Honest refusal.</b> When no topic matches and no crop is named, the
 * assistant says it does not have enough verified information rather than
 * improvising. A plausible-sounding wrong answer is worse than an admission.
 */
@Component
public class LocalAdvisoryCorpus {

    private static final Logger log = LoggerFactory.getLogger(LocalAdvisoryCorpus.class);

    static final String RESOURCE = "agronomy/crop_reference.json";

    /** One reference crop row, flattened for matching. */
    record CropRow(String id, List<String> aliases, String season, Integer durationDays,
                   String waterNeed, String waterloggingTolerance, String droughtSensitivity,
                   String irrigationHint, String stages, String sowWindow,
                   List<String> sourceIds) {}

    private final List<CropRow> crops = new ArrayList<>();
    private final String referenceVersion;
    private final List<String> sourceNames = new ArrayList<>();

    public LocalAdvisoryCorpus() {
        this(RESOURCE);
    }

    /** Test seam: load from an alternate classpath resource. */
    LocalAdvisoryCorpus(String resource) {
        String version = "unknown";
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            JsonNode root = new ObjectMapper().readTree(in);
            version = root.path("version").asText("unknown");
            for (JsonNode s : root.path("sources")) {
                String n = s.path("name").asText(s.path("id").asText("")).trim();
                if (!n.isEmpty()) sourceNames.add(n);
            }
            for (JsonNode c : root.path("crops")) {
                String id = c.path("id").asText("").trim();
                if (id.isEmpty()) continue;
                List<String> alias = new ArrayList<>();
                for (JsonNode a : c.path("aliases")) {
                    String v = a.asText("").trim();
                    if (!v.isEmpty()) alias.add(v);
                }
                List<String> src = new ArrayList<>();
                for (JsonNode s : c.path("source_ids")) {
                    String v = s.asText("").trim();
                    if (!v.isEmpty()) src.add(v);
                }
                StringBuilder stages = new StringBuilder();
                for (JsonNode st : c.path("stages")) {
                    if (stages.length() > 0) stages.append(" -> ");
                    stages.append(st.path("name").asText("").replace('_', ' '));
                }
                JsonNode sw = c.path("sowing_window");
                String window = null;
                String start = firstText(sw, "sow_start", "transplant_start", "plant_start");
                String end = firstText(sw, "sow_end", "transplant_end", "plant_end");
                if (start != null && end != null) window = start + " to " + end;

                crops.add(new CropRow(id, alias, c.path("season").asText("unspecified"),
                        c.hasNonNull("duration_days") ? c.path("duration_days").asInt() : null,
                        c.path("water_need_class").asText("unspecified"),
                        c.path("sensitivities").path("waterlogging_tolerance").asText("unspecified"),
                        c.path("sensitivities").path("drought_sensitivity").asText("unspecified"),
                        c.path("irrigation_rule_hint").asText(null),
                        stages.length() == 0 ? null : stages.toString(),
                        window, List.copyOf(src)));
            }
        } catch (Exception e) {
            log.warn("Local advisory corpus unavailable ({}); the assistant will answer with "
                    + "general guidance only", e.getMessage());
        }
        this.referenceVersion = version;
    }

    // ------------------------------------------------------------------
    // Crop knowledge
    // ------------------------------------------------------------------

    /**
     * Reference facts for a named crop, matched on the crop id or any alias.
     * Returns an empty map when the reference has no such entry, which the caller
     * reports as unavailable rather than substituting a guess.
     */
    public Map<String, Object> cropKnowledge(String crop) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (crop == null || crop.isBlank()) return out;
        CropRow row = find(crop);
        if (row == null) return out;
        out.put("referenceId", row.id());
        out.put("season", row.season());
        if (row.durationDays() != null) out.put("referenceDurationDays", row.durationDays());
        if (row.sowWindow() != null) out.put("referenceSowingWindow", row.sowWindow());
        out.put("waterNeedClass", row.waterNeed());
        out.put("waterloggingTolerance", row.waterloggingTolerance());
        out.put("droughtSensitivity", row.droughtSensitivity());
        if (row.stages() != null) out.put("growthStages", row.stages());
        if (row.irrigationHint() != null) out.put("irrigationGuidance", row.irrigationHint());
        out.put("referenceVersion", referenceVersion);
        if (!row.sourceIds().isEmpty()) out.put("referenceSourceIds", row.sourceIds());
        return out;
    }

    private CropRow find(String crop) {
        String q = crop.trim().toLowerCase(Locale.ROOT);
        for (CropRow r : crops) {
            if (r.id().toLowerCase(Locale.ROOT).equals(q)) return r;
        }
        for (CropRow r : crops) {
            for (String a : r.aliases()) {
                if (a.toLowerCase(Locale.ROOT).equals(q)) return r;
            }
        }
        // Last resort: a cultivar or variety token, e.g. "HD-2967".
        for (CropRow r : crops) {
            for (String a : r.aliases()) {
                String al = a.toLowerCase(Locale.ROOT);
                if (!al.isEmpty() && (q.contains(al) || al.contains(q))) return r;
            }
            if (q.contains(r.id().toLowerCase(Locale.ROOT))) return r;
        }
        return null;
    }

    /** Crop names the reference knows, for the "what can you help with" line. */
    public List<String> knownCropNames() {
        List<String> out = new ArrayList<>();
        for (CropRow r : crops) {
            String base = r.id().contains("(")
                    ? r.id().substring(0, r.id().indexOf('(')).trim() : r.id();
            if (!out.contains(base)) out.add(base);
        }
        return out;
    }

    public String referenceVersion() {
        return referenceVersion;
    }

    public List<String> sourceNames() {
        return List.copyOf(sourceNames);
    }

    // ------------------------------------------------------------------
    // Deterministic topic selection
    // ------------------------------------------------------------------

    /** An ordered topic rule. First match wins, so order encodes precedence. */
    record Topic(List<String> anyKeyword, String en, String hi) {}

    private static final List<Topic> TOPICS = List.of(
        new Topic(List.of("heavy rain", "heavy rainfall", "storm", "downpour", "flood",
                        "बारिश", "तूफान", "बाढ़"),
                "If heavy rain is forecast, the useful first step is to check drainage and "
                        + "field conditions rather than to act on the forecast alone. Do not "
                        + "apply fertiliser or pesticide immediately before heavy rain, because "
                        + "it can run off and waste money. Delay harvesting of a mature crop "
                        + "if rain is expected within a day. After the rain, check for water "
                        + "logging in low spots and look at the crop a day or two later, because "
                        + "damage is usually easier to assess once the water has drained. "
                        + "Forecasts change, so re-check before you act.",
                "यदि तेज़ बारिश की भविष्यवाणी है, तो पहला कदम बारिश पर अमल करने से पहले जल निकासी "
                        + "और खेत की स्थिति देखना है। तेज़ बारिश से ठीक पहले उर्वरक या कीटनाशक न "
                        + "डालें, क्योंकि वह बह जा सकता है और पैसा बर्बाद होगा। फसल पकने के "
                        + "पास हो तो एक दिन के भीतर बारिश की संभावना होने पर कटाई टालें। बारिश "
                        + "के बाद निचले स्थानों में जलभराव देखें, और दो-तीन दिन बाद फसल की "
                        + "स्थिति देखें। भविष्यवाणी बदल सकती है, इसलिए काम करने से पहले दोबारा "
                        + "जाँचें।"),

        new Topic(List.of("field work", "fieldwork", "spray", "spraying", "tilling",
                        "sowing operation", "farm operation", "field activity",
                        "खेत का काम", "छिड़काव", "जुताई"),
                "Field work is worth doing when the soil is workable and the weather window "
                        + "is reasonably stable. As a rule of thumb for the coming day: avoid "
                        + "spraying if rain is likely within a few hours, since spray washes "
                        + "off; avoid working wet soil, because it compacts and damages "
                        + "structure; and avoid tillage when heavy rain is expected, since you "
                        + "can lose soil to erosion. Use the SAARTHI field-work risk on the "
                        + "Forecast page as one input, not as a guarantee. Where a crop is "
                        + "already at a sensitive stage, ask your local agricultural officer.",
                "खेत का काम तब करें जब मिट्टी काम करने योग्य हो और मौसम की स्थिति स्थिर हो। "
                        + "आने वाले दिन के लिए सामान्य सलाह: यदि कुछ घंटों में बारिश की संभावना "
                        + "हो तो छिड़काव न करें, क्योंकि दवा बह जाती है; गीली मिट्टी में काम न "
                        + "करें, क्योंकि मिट्टी दब जाती है; और तेज़ बारिश से पहले जुताई न करें, "
                        + "क्योंकि मिट्टी बह सकती है। Forecast पेज का field-work risk एक "
                        + "संकेत है, गारंटी नहीं। फसल किसी संवेदनशील अवस्था में हो तो अपने "
                        + "स्थानीय कृषि अधिकारी से पूछें।"),

        new Topic(List.of("explain", "forecast", "outlook", "simple", "understand",
                        "meaning", "समझा", "भविष्यवाणी"),
                "In short: SAARTHI shows a 16-day rainfall forecast for your selected block, "
                        + "from the ECMWF IFS model via Open-Meteo. The 7-day number is the "
                        + "rain expected over the next week; the dry-day count tells you how "
                        + "many of the first 7 days expect under 1 mm. A forecast is a "
                        + "probability-weighted expectation, not a promise, and it is a "
                        + "regional model output rather than a reading from your field. Read "
                        + "the trend across days, not a single number, and re-check if you are "
                        + "making a costly decision.",
                "संक्षेप में: SAARTHI आपके चुने हुए ब्लॉक के लिए 16 दिन का वर्षा पूर्वानुमान "
                        + "दिखाता है, जो Open-Meteo के माध्यम से ECMWF IFS मॉडल से आता है। "
                        + "7-दिन का आंकड़ा अगले सप्ताह की अपेक्षित वर्षा है; सूखे दिनों की गिनती "
                        + "बताती है कि पहले 7 दिनों में कितने दिन 1 मिमी से कम वर्षा की "
                        + "संभावना है। पूर्वानुमान संभावना-आधारित अपेक्षा है, वादा नहीं, और यह "
                        + "क्षेत्रीय मॉडल का परिणाम है, आपके खेत का माप नहीं। एक अंक के बजाय "
                        + "कई दिनों का रुझान देखें, और महँगा निर्णय लेने से पहले दोबारा जाँचें।"),

        new Topic(List.of("sowing", "sow the", "planting", "transplant", "when to plant",
                        "बुवाई", "रोपाई", "बोआई"),
                "Sowing inside the reference window matters more than any single recommendation. "
                        + "Sowing too early exposes the crop to a terminal heat or cold spell; "
                        + "sowing too late compresses the season and shifts every later growth "
                        + "stage. Choose a variety whose duration matches the time left in your "
                        + "season. Check the SAARTHI CropAtlas page for how the selected block's "
                        + "measured conditions compare with each crop's documented "
                        + "requirements, and confirm the final date with your local KVK or "
                        + "agricultural officer, since they know the local variety and soil "
                        + "situation.",
                "सिंचाई/बुवाई के लिए संदर्भ विंडो के भीतर बुवाई करना किसी भी एक सुझाव से "
                        + "ज़्यादा महत्वपूर्ण है। बहुत जल्दी बुवाई करने पर फसल को अंतिम गर्मी या "
                        + "ठंडे मौसम का सामना करना पड़ सकता है; देर से बुवाई करने पर मौसम संकुचित "
                        + "हो जाता है और बाद की सभी अवस्थाएँ प्रभावित होती हैं। ऐसी किस्म चुनें "
                        + "जिसकी अवधि आपके मौसम के बचे समय से मेल खाती हो। चुने हुए ब्लॉक की "
                        + "मापी गई स्थिति की तुलना फसलों के दर्ज किए गए आवश्यकताओं से देखने के "
                        + "लिए SAARTHI CropAtlas पेज देखें, और अंतिम तिथि की पुष्टि अपने स्थानीय "
                        + "KVK या कृषि अधिकारी से करें।"),

        new Topic(List.of("irrigat", "water", "dry spell", "drought", "water requirement",
                        "सिंचाई", "सूखा", "पानी"),
                "Irrigation timing is a judgement call between the crop's water requirement and "
                        + "what the soil is actually holding. SAARTHI reports a 7-day rainfall "
                        + "minus evapotranspiration balance, which is a useful signal, plus a "
                        + "run of dry days and a model soil-moisture forecast. Treat a drying "
                        + "balance as a prompt to check the field, not as a fixed schedule. "
                        + "Crops differ sharply: a crop rated very high on water need will suffer "
                        + "in a drying period that a medium-demand crop tolerates. Look up the "
                        + "water-need class for your crop on the CropAtlas page.",
                "सिंचाई का समय फसल की जल आवश्यकता और मिट्टी में उपलब्ध पानी के बीच एक संतुलन "
                        + "है। SAARTHI 7-दिन की वर्षा घटा वाष्पोत्सर्जन का संतुलन बताता है, "
                        + "जो उपयोगी संकेत है, साथ ही सूखे दिनों की संख्या और मॉडल द्वारा अनुमानित "
                        + "मिट्टी नमी। सूखता संतुलन को निश्चित समय-सारणी न मानें, बल्कि खेत देखने "
                        + "का संकेत मानें। फसलों की जल आवश्यकता बहुत भिन्न है: बहुत अधिक जल "
                        + "आवश्यकता वाली फसल उस सूखे में कष्ट झेलेगी जिसे मध्यम आवश्यकता वाली "
                        + "फसल सहन कर सकती है। अपनी फसल की जल आवश्यकता CropAtlas पेज देखें।"),

        new Topic(List.of("pest", "disease", "insect", "pests", "infestation", "fungus",
                        "कीट", "रोग", "कीड़ा", "कीटक"),
                "SAARTHI does not measure pests or disease, so I cannot tell you what is in your "
                        + "field. What the platform can support is timing: many treatments are "
                        + "worthless if rain arrives soon after spraying, and a stressed or "
                        + "waterlogged crop is more vulnerable. Walk the field and look before "
                        + "deciding, prefer a locally recommended product and dose, and confirm "
                        + "with your KVK, agriculture department or a certified adviser before "
                        + "applying anything. Do not treat on the basis of a forecast alone.",
                "SAARTHI कीट या रोग मापता नहीं है, इसलिए मैं नहीं बता सकता कि आपके खेत में क्या "
                        + "है। मंच समय के बारे में सहायता कर सकता है: बारिश के तुरंत बाद छिड़काव "
                        + "अक्सर निष्फल हो जाता है, और तनावग्रस्त या जलभराव वाली फसल अधिक "
                        + "संवेदनशील होती है। निर्णय से पहले खेत देखें, स्थानीय रूप से अनुशंसित "
                        + "उत्पाद और मात्रा चुनें, और कुछ भी डालने से पहले अपने KVK, कृषि विभाग "
                        + "या प्रमाणित सलाहकार से पुष्टि करें। केवल पूर्वानुमान के आधार पर उपचार "
                        + "न करें।"),

        new Topic(List.of("soil", "texture", "ph", "clay", "loam", "मिट्टी"),
                "SAARTHI uses a modelled 0-5 cm soil surface value from SoilGrids, plus bundled "
                        + "block means where available. That is useful background, but it is not "
                        + "a measurement of your field: local texture, organic matter and "
                        + "compaction vary within a single block. Treat it as an indication for "
                        + "screening, and get a local soil test before making a decision that "
                        + "depends on soil chemistry, such as a lime or nutrient dose.",
                "SAARTHI SoilGrids से मॉडल की गई 0-5 सेमी मिट्टी की सतही जानकारी और जहाँ उपलब्ध "
                        + "हो वहाँ बंडल किए गए ब्लॉक औसत का उपयोग करता है। यह उपयोगी पृष्ठभूमि है, "
                        + "पर आपके खेत का माप नहीं: स्थानीय बनावट, कार्बनिक पदार्थ और संकुचन "
                        + "एक ही ब्लॉक में भी बदलते हैं। इसे स्क्रीनिंग के संकेत के रूप में लें, "
                        + "और मिट्टी की रसायन-स्थिति पर निर्भर निर्णय — जैसे चूना या पोषण "
                        + "की मात्रा — से पहले स्थानीय मिट्टी जाँच कराएँ।"),

        new Topic(List.of("stage", "growth stage", "tillering", "flowering", "grain",
                        "अवस्था", "बढ़वार"),
                "Crop stages come from the reference and each one has its own sensitivities. "
                        + "Early stages after sowing or transplanting are the most vulnerable to "
                        + "both waterlogging and moisture stress. Flowering and grain filling are "
                        + "the stages where heat, moisture stress and sudden dry spells cause the "
                        + "most damage, because the plant has already committed resources to "
                        + "reproductive growth. The exact stage is only visible in the field. "
                        + "CropAtlas lists the documented stage sequence for the reference "
                        + "varieties.",
                "फसल की अवस्थाएँ संदर्भ से ली जाती हैं और हर अवस्था की अपनी संवेदनशीलता होती है। "
                        + "बुवाई या रोपाई के तुरंत बाद की प्रारंभिक अवस्थाएँ जलभराव और नमी की "
                        + "कमी दोनों के प्रति सबसे अधिक संवेदनशील होती हैं। फूल और दानी भरने की "
                        + "अवस्था में गर्मी, नमी की कमी और अचानक सूखा सबसे अधिक नुकसान पहुँचाते हैं, "
                        + "क्योंकि पौधा उस समय तक संसाधन फूल विकास में लगा चुका होता है। वास्तविक "
                        + "अवस्था केवल खेत में देखकर पता चलती है। संदर्भ किस्मों की दर्ज अवस्था "
                        + "क्रम CropAtlas बताता है।"),

        new Topic(List.of("hello", "hi", "namaste", "नमस्ते", "help", "what can you do"),
                "Namaste! I am Saarthi Agri-Advisor. I can explain your SAARTHI forecast, talk "
                        + "through field-work and sowing decisions, and describe what the "
                        + "platform measures. I cannot see prices, cannot estimate yield, and I "
                        + "am not a substitute for your local agricultural officer.",
                "नमस्ते! मैं सारथी कृषि सलाहकार हूँ। मैं आपका SAARTHI पूर्वानुमान समझा सकता हूँ, "
                        + "खेत के काम और बुवाई के निर्णयों पर चर्चा कर सकता हूँ, और बता सकता "
                        + "हूँ कि यह मंच क्या मापता है। मैं कीमतें नहीं देख सकता, उपज का अनुमान "
                        + "नहीं लगा सकता, और आपके स्थानीय कृषि अधिकारी का विकल्प नहीं हूँ।")
    );

    /**
     * The topic whose keywords match, or {@code null}. First match wins.
     *
     * <p>Keywords are matched on <b>word boundaries</b>, not as raw substrings.
     * A plain substring search gives false positives that are actively harmful
     * here: {@code "hi"} occurs inside "Bathinda", so a question about market
     * prices in Bathinda would be answered with the greeting topic and the
     * farmer's actual question silently ignored.
     */
    Topic matchTopic(String message) {
        if (message == null || message.isBlank()) return null;
        String m = message.toLowerCase(Locale.ROOT);
        for (Topic t : TOPICS) {
            for (String k : t.anyKeyword()) {
                if (containsWord(m, k.toLowerCase(Locale.ROOT))) return t;
            }
        }
        return null;
    }

    /**
     * Word-boundary containment.
     *
     * <p>A plain substring search gives false positives that are actively harmful
     * here: {@code "hi"} occurs inside "Bathinda", so a question about market prices
     * in Bathinda would be answered with the greeting topic.
     *
     * <p>The rule is deliberately asymmetric, because the keyword list mixes two
     * kinds of term:
     * <ul>
     *   <li><b>Whole words</b> (short, ambiguous ones especially) must be bounded on
     *       <em>both</em> sides, so {@code "hi"} does not match "high".</li>
     *   <li><b>Stems</b> (e.g. {@code "irrigat"}, {@code "stage"}, {@code "pest"},
     *       {@code "agricultur"}) must be bounded only on the <em>left</em>, so they
     *       still match "irrigate", "stages" and "agricultural".</li>
     * </ul>
     * A term of three characters or fewer is treated as a whole word, since that is
     * where the false-positive risk actually lives.
     */
    private static boolean containsWord(String haystack, String needle) {
        if (needle == null || needle.isEmpty()) return false;
        boolean needRight = needle.length() <= 3;
        int from = 0;
        while (true) {
            int i = haystack.indexOf(needle, from);
            if (i < 0) return false;
            boolean leftOk = i == 0 || !isWordChar(haystack.charAt(i - 1));
            int end = i + needle.length();
            boolean rightOk = !needRight
                    || end >= haystack.length() || !isWordChar(haystack.charAt(end));
            if (leftOk && rightOk) return true;
            from = i + 1;
        }
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c);
    }

    boolean isHindi(String message) {
        if (message == null) return false;
        for (int i = 0; i < message.length(); i++) {
            char c = message.charAt(i);
            if (c >= 0x0900 && c <= 0x097F) return true;
        }
        return false;
    }

    int topicCount() {
        return TOPICS.size();
    }

    int cropCount() {
        return crops.size();
    }

    private static String firstText(JsonNode parent, String... fields) {
        if (parent == null) return null;
        for (String f : fields) {
            JsonNode n = parent.path(f);
            if (n.isTextual()) {
                String v = n.asText().trim();
                if (!v.isEmpty()) return v;
            }
        }
        return null;
    }
}
