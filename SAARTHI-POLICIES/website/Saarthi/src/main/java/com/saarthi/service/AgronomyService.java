package com.saarthi.service;

import com.saarthi.model.FarmerAnalysisRequest;
import com.saarthi.model.FarmerAnalysisResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class AgronomyService {

    @Autowired
    private RealForecastService realForecastService;

    private String classifyRisk(double probability) {
        if (probability >= 0.60) return "High";
        if (probability >= 0.30) return "Moderate";
        return "Low";
    }

    public Map<String, Map<String, String>> getAllCropAdvice(double probability) {
        Map<String, Map<String, String>> map = new LinkedHashMap<>();

        if (probability >= 0.60) {
            map.put("Paddy (PR-126)", createCropItem(
                    "High risk of early dry break. Delay transplanting by 7 days. If seedlings are over 30 days, maintain minimum root-zone puddle and mulch field boundaries.",
                    "Direct seeded rice (DSR) or short-duration PR-126",
                    "Delay transplanting; maintain 2 cm puddle depth only using tubewell water.",
                    "Withhold top-dressing nitrogen until the next active monsoon spell."
            ));
            map.put("Paddy (Pusa-44)", createCropItem(
                    "CRITICAL: Long duration (160 days) variety highly vulnerable to rainfall deficits. Delay transplanting or pivot to shorter duration PR-126.",
                    "Switch to PR-126 or PR-121",
                    "Do not transplant without guaranteed continuous canal/tubewell power supply.",
                    "Apply zinc sulphate and basal DAP; avoid excess nitrogen."
            ));
            map.put("Basmati", createCropItem(
                    "Delay field transplanting. Maintain nursery seedlings with light alternate wetting. Nursery beds should not crack.",
                    "Basmati PB-1847 or PB-1509",
                    "Laser level fields and transplant on raised beds to economize water.",
                    "Incorporate well-decomposed farmyard manure (FYM) to enhance water holding capacity."
            ));
            map.put("Cotton", createCropItem(
                    "High dry break risk. Postpone square initiation stage irrigation until moisture stresses ease. Inspect for whitefly.",
                    "Short duration cotton hybrid",
                    "Alternate furrow irrigation to conserve 40% water.",
                    "Foliar spray of 2% potassium nitrate (13:0:45) to mitigate drought stress."
            ));
            map.put("Maize", createCropItem(
                    "Postpone sowing until a soaking rain of at least 25mm is received. Seedlings at 2-leaf stage are highly vulnerable.",
                    "Cluster bean (Guar) or Bajra fodder",
                    "Broad-bed furrow (BBF) planting to prevent drought stress.",
                    "Band placement of basal NPK (12:32:16) at 5 cm below seed depth."
            ));
            map.put("Wheat", createCropItem(
                    "Conserve existing kharif residual moisture. Laser-level fields and apply residue mulching.",
                    "Gram (PBG-7) or Mustard",
                    "Happy Seeder / Smart Seeder zero-tillage into paddy stubble.",
                    "Plan basal DAP application with seed-cum-fertilizer drill."
            ));
            map.put("Sugarcane", createCropItem(
                    "Do not plant new setts now. Mulch existing cane fields with trash mulch (10-12 cm) and arrange life-saving furrow irrigation.",
                    "Fodder sorghum (SL-44)",
                    "Paired-row trench planting with drip fertigation.",
                    "Spray 2% urea + 2.5% MOP solution to reduce transpiration loss during dry heat."
            ));
        } else if (probability >= 0.30) {
            map.put("Paddy (PR-126)", createCropItem(
                    "Moderate risk. Keep nursery ready, but proceed with field transplanting only if tubewell/canal irrigation is secured. Monitor 3-day rainfall outlook.",
                    "Direct seeded rice (DSR) with seed drill",
                    "Transplant 25-30 day old seedlings at 20x15 cm spacing using alternate wetting & drying.",
                    "Apply 1/3rd nitrogen as basal and incorporate into soil before transplanting."
            ));
            map.put("Paddy (Pusa-44)", createCropItem(
                    "Transplant only in plots with assured tubewell water. If relying on monsoon showers, wait 3 days for cloud cover confirmation.",
                    "PR-126 or PR-121",
                    "Laser levelling + puddling with tractor-mounted puddler.",
                    "Full basal dose of P and K; split N into three equal applications."
            ));
            map.put("Basmati", createCropItem(
                    "Good window for nursery preparation and field puddling in irrigated areas. Maintain light standing water to prevent soil crack formation.",
                    "Basmati PB-1847",
                    "Transplant on raised beds or well-puddled leveled fields.",
                    "Apply organic manure/FYM at 4-5 tonnes/acre to enhance moisture retention capacity."
            ));
            map.put("Cotton", createCropItem(
                    "Sow only where pre-sowing irrigation (Rauni) has been completed; otherwise review the forecast in 3 days.",
                    "Short-duration cotton hybrid",
                    "Ridge-and-furrow planting to conserve moisture and facilitate easy draining.",
                    "Apply half nitrogen and full phosphorus at sowing."
            ));
            map.put("Maize", createCropItem(
                    "Prepare the seedbed and sow in moisture-retaining alluvial/clay loam plots after checking 3-day radar updates.",
                    "Short-duration maize (PMH-1)",
                    "Ridge sowing with seed drill on broad beds.",
                    "Apply 50 kg DAP and 25 kg MOP per acre as basal dose."
            ));
            map.put("Wheat", createCropItem(
                    "Assess field preparation and check soil moisture profile across root zone (0-30 cm).",
                    "Wheat HD-3086 or PBW-725",
                    "Direct drilling with Happy Seeder into anchored stubble.",
                    "Ensure phosphorus availability in root-zone soil."
            ));
            map.put("Sugarcane", createCropItem(
                    "Plant only in well-prepared irrigated plots. Keep furrows ready to manage brief moisture dips.",
                    "Sugarcane Co-118",
                    "Trench planting with trash mulching.",
                    "Apply basal NPK and incorporate biofertilizers."
            ));
        } else {
            map.put("Paddy (PR-126)", createCropItem(
                    "OPTIMAL CONDITIONS: Low dry-break probability (<30%). Favourable moisture profile. Proceed with planned transplanting or direct seeding across all Sangrur blocks.",
                    "Direct-seeded paddy (PR-126)",
                    "Transplant in laser-levelled fields; practice Alternate Wetting and Drying (AWD) to save 25% water.",
                    "Standard recommended schedule: Apply 1/3rd Urea + full DAP + MOP at transplanting."
            ));
            map.put("Paddy (Pusa-44)", createCropItem(
                    "Suitable conditions for puddled transplanting where power supply is available.",
                    "PR-126 or Basmati-1509",
                    "Transplant 30-35 day seedlings in well-puddled clay loam fields.",
                    "Apply recommended basal DAP (55 kg/acre) + MOP (20 kg/acre)."
            ));
            map.put("Basmati", createCropItem(
                    "Ideal transplanting conditions for Basmati varieties (PB-1121, PB-1509, PB-1847).",
                    "Basmati PB-1847",
                    "Transplant 25-day seedlings at 20x15 cm spacing.",
                    "Green manuring with Sesbania (Dhaincha) incorporation before transplanting."
            ));
            map.put("Cotton", createCropItem(
                    "Favourable conditions. Complete direct planting on ridges.",
                    "Bt Cotton hybrid",
                    "Ridge sowing with tractor-driven planter.",
                    "Apply half dose Nitrogen and full dose Phosphorus at planting."
            ));
            map.put("Maize", createCropItem(
                    "High moisture profile is suitable for quick maize germination.",
                    "Maize PMH-1 or PMH-13",
                    "Flat bed or ridge sowing with seed drill.",
                    "Apply starter DAP dose at sowing time."
            ));
            map.put("Wheat", createCropItem(
                    "Prepare seedbed and preserve post-monsoon moisture profile.",
                    "Wheat HD-2967 or PBW-824",
                    "Zero tillage / Smart Seeder.",
                    "Incorporate basal Phosphorus in root zone."
            ));
            map.put("Sugarcane", createCropItem(
                    "Optimal moisture conditions for autumn/spring planting.",
                    "Co-0238 or Co-118",
                    "Deep trench planting with organic mulching.",
                    "Apply balanced NPK as per PAU recommendations."
            ));
        }

        return map;
    }

    private Map<String, String> createCropItem(String advice, String alternative, String method, String fertilizer) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("advice", advice);
        m.put("alternative", alternative);
        m.put("method", method);
        m.put("fertilizer", fertilizer);
        return m;
    }

    public FarmerAnalysisResponse computeFarmerAnalysis(FarmerAnalysisRequest req) {
        String block = req.getBlock();
        String panchayat = req.getPanchayat();
        String crop = req.getCrop() != null ? req.getCrop() : "Paddy (PR-126)";
        String soil = req.getSoil() != null ? req.getSoil() : "Clay Loam";
        String irrigation = req.getIrrigation() != null ? req.getIrrigation() : "Canals";

        // REAL forecast context: P(LOW 7-day rainfall) is the dry probability.
        // Throws BlockNotFoundException (HTTP 404) for unknown blocks — never a silent fallback.
        Map<String, Object> forecast = realForecastService.findBlock(block)
                .orElseThrow(() -> new RealForecastService.BlockNotFoundException(block));
        @SuppressWarnings("unchecked")
        Map<String, Object> probability = (Map<String, Object>) forecast.get("probability");
        double prob = ((Number) probability.get("low")).doubleValue();
        double forecastTotal = ((Number) forecast.get("forecast_7d_total_rainfall_mm")).doubleValue();
        String rainfallCategory = Objects.toString(forecast.get("category"), "NORMAL");

        String riskLevel = classifyRisk(prob);
        double probPct = Math.round(prob * 1000.0) / 10.0;

        double baseMoisture = 42.0 - (prob * 26.0);
        if (soil.equalsIgnoreCase("Clay Loam")) baseMoisture += 6.0;
        else if (soil.equalsIgnoreCase("Sandy Loam")) baseMoisture -= 7.0;
        else if (soil.equalsIgnoreCase("Silt Loam")) baseMoisture += 2.0;

        if (irrigation.equalsIgnoreCase("Canals")) baseMoisture += 4.0;
        else if (irrigation.equalsIgnoreCase("Tubewells")) baseMoisture += 3.0;
        else if (irrigation.equalsIgnoreCase("Rainfed")) baseMoisture -= 6.0;

        double soilMoisturePct = Math.max(14.0, Math.min(62.0, baseMoisture));

        String decisionTag;
        String decisionTone;
        String decisionColor;
        if (prob >= 0.60 || (soilMoisturePct < 25.0 && irrigation.equalsIgnoreCase("Rainfed"))) {
            decisionTag = "WAIT / DELAY SOWING";
            decisionTone = "wait";
            decisionColor = "#f27256";
        } else if (prob >= 0.30 || soilMoisturePct < 32.0) {
            decisionTag = "EXERCISE CAUTION";
            decisionTone = "review";
            decisionColor = "#f0c35e";
        } else {
            decisionTag = "SAFE TO SOW";
            decisionTone = "sow";
            decisionColor = "#9ed683";
        }

        LocalDate baseDate = LocalDate.now();
        if (req.getSowingDate() != null && !req.getSowingDate().isEmpty()) {
            try {
                baseDate = LocalDate.parse(req.getSowingDate(), DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (Exception ignored) {}
        }

        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("MMM dd");
        String recWindow;
        if (decisionTone.equals("sow")) {
            recWindow = baseDate.format(dtf) + " – " + baseDate.plusDays(6).format(dtf);
        } else if (decisionTone.equals("review")) {
            recWindow = baseDate.plusDays(3).format(dtf) + " – " + baseDate.plusDays(9).format(dtf);
        } else {
            recWindow = baseDate.plusDays(7).format(dtf) + " – " + baseDate.plusDays(14).format(dtf);
        }

        Map<String, String> fourPillars = new LinkedHashMap<>();
        // Weather pillar reflects the REAL forecast category (LOW rain = high dry risk).
        fourPillars.put("weather_risk", rainfallCategory.equals("LOW") ? "High"
                : rainfallCategory.equals("HIGH") ? "Low" : "Moderate");
        fourPillars.put("soil_moisture_risk", soilMoisturePct >= 38 ? "Low" : soilMoisturePct >= 28 ? "Moderate" : "High");
        fourPillars.put("crop_vulnerability_risk", crop.contains("PR-126") ? "Low" : crop.contains("Pusa-44") ? "High" : "Moderate");
        fourPillars.put("dry_break_risk", riskLevel);

        Map<String, Map<String, String>> allCrops = getAllCropAdvice(prob);
        Map<String, String> selectedCrop = allCrops.getOrDefault(crop, allCrops.get("Paddy (PR-126)"));

        Map<String, String> explanation = new LinkedHashMap<>();
        String outlookLine = "7-day outlook " + forecastTotal + " mm (" + rainfallCategory + "). ";
        explanation.put("en", "For " + crop + " in " + panchayat + " (" + block + " Block), " + outlookLine +
                (decisionTone.equals("sow") ? "soil moisture (" + Math.round(soilMoisturePct) + "%) and monsoon probability indicate favorable sowing conditions." :
                 decisionTone.equals("review") ? "moderate dry break risk (" + probPct + "%). Ensure supplemental irrigation before nursery transplanting." :
                 "high dry spell risk (" + probPct + "%). Delay sowing by 7 days to avoid seedling desiccation."));

        explanation.put("hi", panchayat + " (" + block + " ब्लॉक) में " + crop + " के लिए, " +
                (decisionTone.equals("sow") ? "मिट्टी की नमी (" + Math.round(soilMoisturePct) + "%) और मौसम बुवाई के लिए पूर्णतः अनुकूल हैं।" :
                 decisionTone.equals("review") ? "मध्यम सूखा जोखिम (" + probPct + "%) है। बुवाई से पूर्व सिंचाई की व्यवस्था सुनिश्चित करें।" :
                 "सूखे का बड़ा खतरा (" + probPct + "%) है। बीज व पौध को नुकसान से बचाने हेतु बुवाई 7 दिन टालें।"));

        explanation.put("pa", panchayat + " (" + block + " ਬਲਾਕ) ਵਿੱਚ " + crop + " ਲਈ, " +
                (decisionTone.equals("sow") ? "ਜ਼ਮੀਨੀ ਨਮੀ (" + Math.round(soilMoisturePct) + "%) ਅਤੇ ਮੌਸਮ ਬੀਜਾਈ ਲਈ ਬਹੁਤ ਵਧੀਆ ਹਨ।" :
                 decisionTone.equals("review") ? "ਦਰਮਿਆਨਾ ਸੋਕਾ ਜੋਖਮ (" + probPct + "%) ਹੈ। ਪਨੀਰੀ ਲਾਉਣ ਤੋਂ ਪਹਿਲਾਂ ਨਹਿਰੀ/ਟਿਊਬਵੈੱਲ ਪਾਣੀ ਯਕੀਨੀ ਬਣਾਓ।" :
                 "ਸੋਕੇ ਦਾ ਵੱਡਾ ਖ਼ਤਰਾ (" + probPct + "%) ਹੈ। ਪਨੀਰੀ ਨੂੰ ਸੁੱਕਣ ਤੋਂ ਬਚਾਉਣ ਲਈ ਬੀਜਾਈ 7 ਦਿਨ ਅੱਗੇ ਪਾਓ।"));

        Map<String, String> whatsappShare = new LinkedHashMap<>();
        whatsappShare.put("en", "🌾 *SAARTHI KISAN ADVISORY — SANGRUR*\n" +
                "📍 *Location:* " + panchayat + ", " + block + "\n" +
                "🌱 *Crop:* " + crop + " | *Soil:* " + soil + "\n" +
                "📊 *Decision:* *" + decisionTag + "*\n" +
                "📅 *Recommended Window:* " + recWindow + "\n" +
                "💧 *Root-Zone Moisture:* " + Math.round(soilMoisturePct) + "% | *Dry Break Risk:* " + probPct + "%\n\n" +
                "💡 *Advisory:* " + explanation.get("en") + "\n" +
                "— Powered by SAARTHI AI (SIH26086)");

        whatsappShare.put("hi", "🌾 *सारथी किसान सलाह — संगरूर जिला*\n" +
                "📍 *स्थान:* " + panchayat + ", " + block + "\n" +
                "🌱 *फसल:* " + crop + " | *मिट्टी:* " + soil + "\n" +
                "📊 *निर्णय:* *" + decisionTag + "*\n" +
                "📅 *उचित बुवाई समय:* " + recWindow + "\n" +
                "💧 *मिट्टी नमी:* " + Math.round(soilMoisturePct) + "% | *सूखा जोखिम:* " + probPct + "%\n\n" +
                "💡 *कृषि सलाह:* " + explanation.get("hi") + "\n" +
                "— सारथी मानसूनी बुद्धिमत्ता (SIH26086)");

        whatsappShare.put("pa", "🌾 *ਸਾਰਥੀ ਕਿਸਾਨ ਸਲਾਹ — ਸੰਗਰੂਰ*\n" +
                "📍 *ਥਾਂ:* " + panchayat + ", " + block + "\n" +
                "🌱 *ਫ਼ਸਲ:* " + crop + " | *ਮਿੱਟੀ:* " + soil + "\n" +
                "📊 *ਫ਼ੈਸਲਾ:* *" + decisionTag + "*\n" +
                "📅 *ਬੀਜਾਈ ਦਾ ਸਹੀ ਸਮਾਂ:* " + recWindow + "\n" +
                "💧 *ਜ਼ਮੀਨੀ ਨਮੀ:* " + Math.round(soilMoisturePct) + "% | *ਸੋਕਾ ਜੋਖਮ:* " + probPct + "%\n\n" +
                "💡 *ਸਲਾਹ:* " + explanation.get("pa") + "\n" +
                "— ਸਾਰਥੀ AI (SIH26086)");

        FarmerAnalysisResponse.Outputs outputs = new FarmerAnalysisResponse.Outputs();
        outputs.setDrySpellProbability(probPct);
        outputs.setRiskLevel(riskLevel);
        outputs.setModelSource("Raw CHIRPS-GEFS 7-day outlook + prototype agronomy guidance");
        outputs.setForecastTotalMm(forecastTotal);
        outputs.setRainfallCategory(rainfallCategory);
        outputs.setProbLow(((Number) probability.get("low")).doubleValue());
        outputs.setProbNormal(((Number) probability.get("normal")).doubleValue());
        outputs.setProbHigh(((Number) probability.get("high")).doubleValue());
        outputs.setDecisionTag(decisionTag);
        outputs.setDecisionTone(decisionTone);
        outputs.setDecisionColor(decisionColor);
        outputs.setRecommendedWindow(recWindow);
        outputs.setExplanation(explanation);
        outputs.setFourPillars(fourPillars);
        outputs.setRootZoneSoilMoisturePct(Math.round(soilMoisturePct * 10.0) / 10.0);
        outputs.setCropGuidance(selectedCrop);
        outputs.setWhatsappShare(whatsappShare);

        Map<String, Object> inputEcho = new LinkedHashMap<>();
        inputEcho.put("block", block);
        inputEcho.put("panchayat", panchayat);
        inputEcho.put("crop", crop);
        inputEcho.put("soil", soil);
        inputEcho.put("irrigation", irrigation);
        inputEcho.put("sowing_date", baseDate.toString());

        FarmerAnalysisResponse response = new FarmerAnalysisResponse();
        response.setInputs(inputEcho);
        response.setOutputs(outputs);
        return response;
    }
}
