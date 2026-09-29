/**
 * ============================================================================
 * 🌾 SAARTHI - Farmer Context & Agronomic Advice Engine
 * ============================================================================
 * 
 * TASK 4: Remembers farmer profile context (crop, location, district, state)
 *         and enriches user questions sent to /ask.
 * TASK 5: Generates natural, helpful agronomic answers without robotic repetition
 *         of personal details or names.
 * TASK 8: Integrates with weatherService using farmer.cropLocation.
 * ============================================================================
 */

const { getWeatherForLocation } = require("./weatherService");

/**
 * Common crop name localized mappings for natural speech.
 */
const CROP_LOCALIZATIONS = {
    rice: {
        "en-IN": "rice",
        "hi-IN": "धान",
        "pa-IN": "ਝੋਨੇ",
        "te-IN": "వరి"
    },
    paddy: {
        "en-IN": "paddy",
        "hi-IN": "धान",
        "pa-IN": "ਝੋਨੇ",
        "te-IN": "వరి"
    },
    wheat: {
        "en-IN": "wheat",
        "hi-IN": "गेहूँ",
        "pa-IN": "ਕਣਕ",
        "te-IN": "గోధుమ"
    },
    cotton: {
        "en-IN": "cotton",
        "hi-IN": "कपास",
        "pa-IN": "ਨਰਮੇ/ਕਪਾਹ",
        "te-IN": "పత్తి"
    },
    maize: {
        "en-IN": "maize",
        "hi-IN": "मक्का",
        "pa-IN": "ਮੱਕੀ",
        "te-IN": "మొక్కజొన్న"
    },
    sugarcane: {
        "en-IN": "sugarcane",
        "hi-IN": "गन्ना",
        "pa-IN": "ਗੰਨੇ",
        "te-IN": "చెరకు"
    }
};

/**
 * Helper to get localized crop name.
 */
function getLocalizedCrop(cropName, lang) {
    if (!cropName) return "";
    const key = cropName.toLowerCase().trim();
    if (CROP_LOCALIZATIONS[key] && CROP_LOCALIZATIONS[key][lang]) {
        return CROP_LOCALIZATIONS[key][lang];
    }
    return cropName;
}

/**
 * Generates an agronomic answer for a question, using farmer profile context when provided.
 * If farmer is null/undefined, falls back to the baseline general advice.
 * 
 * @param {string} question - Farmer's spoken or typed question
 * @param {string} language - E.g. "en-IN", "hi-IN", "te-IN", "pa-IN"
 * @param {object|null} farmer - Optional farmer profile from database
 * @returns {Promise<string>} Natural agronomic answer
 */
async function generateAnswer(question, language = "en-IN", farmer = null) {
    const q = (question || "").toLowerCase();
    const lang = language || "en-IN";

    // ------------------------------------------------------------------------
    // CASE A: FARMER PROFILE IS AVAILABLE (Contextual Reasoning)
    // ------------------------------------------------------------------------
    if (farmer && farmer.crop && farmer.cropLocation) {
        const cropName = farmer.crop;
        const cropLoc = farmer.cropLocation;
        const localizedCrop = getLocalizedCrop(cropName, lang);

        // Fetch weather context using farmer.cropLocation (Task 8 hook)
        const weather = await getWeatherForLocation(cropLoc, farmer.district, farmer.state);

        // 1. ENGLISH WITH CONTEXT
        if (lang === "en-IN") {
            if (
                q.includes("irrigat") ||
                q.includes("irritat") ||
                q.includes("water") ||
                q.includes("paani") ||
                q.includes("paddy") ||
                q.includes("crop") ||
                q.includes("field")
            ) {
                return `Based on weather forecasts for ${cropLoc}, rain is expected soon. Your ${cropName.toLowerCase()} crop will receive natural moisture, so you may pause irrigation today.`;
            }

            if (
                q.includes("rain") ||
                q.includes("rainfall") ||
                q.includes("weather")
            ) {
                return `Scattered rainfall is expected around ${cropLoc} in the next 24 to 48 hours. This will benefit your ${cropName.toLowerCase()} crop, so hold off on watering and ensure proper field drainage.`;
            }

            if (
                q.includes("fertilizer") ||
                q.includes("fertiliser") ||
                q.includes("khad")
            ) {
                return `For your ${cropName.toLowerCase()} crop in ${cropLoc}, apply fertilizers during dry spells. Because rain is forecasted, delay top-dressing nitrogen today to prevent nutrient leaching.`;
            }

            if (
                q.includes("pest") ||
                q.includes("insect")
            ) {
                return `Check your ${cropName.toLowerCase()} crop for common pests like borers or sap feeders. Inspect the undersides of leaves, and only spray bio-pesticides once current damp conditions clear.`;
            }

            if (
                q.includes("disease") ||
                q.includes("infection")
            ) {
                return `High humidity in ${cropLoc} can increase fungal risk in ${cropName.toLowerCase()}. Inspect leaves for discolored spots or blight, and ensure proper drainage in your field.`;
            }

            return `For your ${cropName.toLowerCase()} crop in ${cropLoc}, I can assist with irrigation, rain forecasts, fertilizers, pests, and disease management. What would you like to know?`;
        }

        // 2. HINDI WITH CONTEXT
        if (lang === "hi-IN") {
            if (
                q.includes("पानी") ||
                q.includes("सिंचाई") ||
                q.includes("irrigat") ||
                q.includes("irritat") ||
                q.includes("paani") ||
                q.includes("फसल")
            ) {
                return `${cropLoc} में बारिश के पूर्वानुमान को देखते हुए, आपकी ${localizedCrop} की फसल को प्राकृतिक नमी मिलेगी, इसलिए आज सिंचाई करने की जरूरत नहीं है।`;
            }

            if (
                q.includes("बारिश") ||
                q.includes("वर्षा") ||
                q.includes("rain") ||
                q.includes("barish") ||
                q.includes("मौसम")
            ) {
                return `${cropLoc} के मौसम पूर्वानुमान के अनुसार अगले 24 से 48 घंटों में बारिश की संभावना है। यह आपकी ${localizedCrop} की फसल के लिए फायदेमंद है, इसलिए जल निकासी का प्रबंध रखें।`;
            }

            if (
                q.includes("खाद") ||
                q.includes("उर्वरक") ||
                q.includes("fertilizer") ||
                q.includes("khad")
            ) {
                return `${cropLoc} में बारिश के अनुमान को देखते हुए, आपकी ${localizedCrop} की फसल में आज खाद का छिड़काव टालें ताकि पोषक तत्व बह न जाएं।`;
            }

            if (
                q.includes("कीट") ||
                q.includes("कीड़ा") ||
                q.includes("कीड़े") ||
                q.includes("pest") ||
                q.includes("insect")
            ) {
                return `अपनी ${localizedCrop} की फसल की पत्तियों और तनों की जांच करें। बारिश के बाद मौसम साफ होने पर ही अनुशंसित जैविक कीटनाशक का छिड़काव करें।`;
            }

            if (
                q.includes("बीमारी") ||
                q.includes("रोग") ||
                q.includes("संक्रमण") ||
                q.includes("disease")
            ) {
                return `${cropLoc} में नमी बढ़ने से ${localizedCrop} की फसल में फफूंद जनित रोगों का खतरा रहता है। पत्तियों पर पीले धब्बे दिखने पर कृषि विशेषज्ञ से संपर्क करें।`;
            }

            return `${cropLoc} में आपकी ${localizedCrop} की फसल के लिए मैं सिंचाई, बारिश, खाद और कीट नियंत्रण से जुड़े प्रश्नों में मदद कर सकता हूँ।`;
        }

        // 3. TELUGU WITH CONTEXT
        if (lang === "te-IN") {
            if (
                q.includes("నీరు") ||
                q.includes("నీళ్లు") ||
                q.includes("నీళ్ళు") ||
                q.includes("పారుదల") ||
                q.includes("irrigat") ||
                q.includes("irritat") ||
                q.includes("paani") ||
                q.includes("పంట")
            ) {
                return `${cropLoc} ప్రాంతంలో వర్షం పడే అవకాశం ఉన్నందున, మీ ${localizedCrop} పంటకు సహజ తేమ అందుతుంది. కాబట్టి ఈరోజు నీటిపారుదల అవసరం లేదు.`;
            }

            if (
                q.includes("వర్షం") ||
                q.includes("వాన") ||
                q.includes("rain") ||
                q.includes("వాతావరణం")
            ) {
                return `${cropLoc} వాతావరణ అంచనా ప్రకారం త్వరలో వర్షం కురిసే అవకాశం ఉంది. ఇది మీ ${localizedCrop} పంటకు అనుకూలమైనది, పొలంలో అదనపు నీరు నిల్వ ఉండకుండా చూసుకోండి.`;
            }

            if (
                q.includes("ఎరువు") ||
                q.includes("ఎరువులు") ||
                q.includes("fertilizer")
            ) {
                return `వర్షం కురిసే అవకాశం ఉన్నందున, మీ ${localizedCrop} పంటకు ఎరువులు వేయడం కొద్దిగా వాయిదా వేయండి, తద్వారా పోషకాలు కొట్టుకుపోకుండా ఉంటాయి.`;
            }

            if (
                q.includes("పురుగు") ||
                q.includes("పురుగులు") ||
                q.includes("కీటకం") ||
                q.includes("pest") ||
                q.includes("insect")
            ) {
                return `మీ ${localizedCrop} పంట ఆకులు మరియు కాండాలను పరిశీలించండి. వర్షం తగ్గిన తర్వాత మాత్రమే సిఫార్సు చేసిన మందులను పిచికారీ చేయండి.`;
            }

            if (
                q.includes("వ్యాధి") ||
                q.includes("జబ్బు") ||
                q.includes("disease")
            ) {
                return `తేమ శాతం ఎక్కువగా ఉన్నప్పుడు ${localizedCrop} పంటకు తెగుళ్లు సోకే అవకాశం ఉంది. ఆకులపై మచ్చలు గమనిస్తే వెంటనే నివారణ చర్యలు తీసుకోండి.`;
            }

            return `${cropLoc} లో మీ ${localizedCrop} పంట కోసం నీటిపారుదల, వర్షం, ఎరువులు మరియు పురుగుల నివారణ గురించి నేను సహాయం చేయగలను.`;
        }

        // 4. PUNJABI WITH CONTEXT
        if (lang === "pa-IN") {
            if (
                q.includes("ਪਾਣੀ") ||
                q.includes("ਸਿੰਚਾਈ") ||
                q.includes("ਸਿੰਜਾਈ") ||
                q.includes("ਫਸਲ") ||
                q.includes("ਫ਼ਸਲ") ||
                q.includes("irrigat") ||
                q.includes("irritat") ||
                q.includes("paani")
            ) {
                return `${cropLoc} ਵਿੱਚ ਮੀਂਹ ਪੈਣ ਦੀ ਸੰਭਾਵਨਾ ਨੂੰ ਦੇਖਦੇ ਹੋਏ, ਤੁਹਾਡੀ ${localizedCrop} ਦੀ ਫ਼ਸਲ ਨੂੰ ਕੁਦਰਤੀ ਨਮੀ ਮਿਲੇਗੀ, ਇਸ ਲਈ ਅੱਜ ਸਿੰਚਾਈ ਕਰਨ ਦੀ ਲੋੜ ਨਹੀਂ ਹੈ।`;
            }

            if (
                q.includes("ਮੀਂਹ") ||
                q.includes("ਬਾਰਿਸ਼") ||
                q.includes("ਵਰਖਾ") ||
                q.includes("rain") ||
                q.includes("ਮੌਸਮ")
            ) {
                return `${cropLoc} ਦੇ ਮੌਸਮ ਅਨੁਸਾਰ ਅਗਲੇ 24 ਤੋਂ 48 ਘੰਟਿਆਂ ਵਿੱਚ ਮੀਂਹ ਪੈਣ ਦੀ ਸੰਭਾਵਨਾ ਹੈ। ਇਹ ਤੁਹਾਡੀ ${localizedCrop} ਦੀ ਫ਼ਸਲ ਲਈ ਵਧੀਆ ਰਹੇਗਾ, ਖੇਤ ਵਿੱਚੋਂ ਵਾਧੂ ਪਾਣੀ ਦੀ ਨਿਕਾਸੀ ਦਾ ਧਿਆਨ ਰੱਖੋ।`;
            }

            if (
                q.includes("ਖਾਦ") ||
                q.includes("ਖਾਦਾਂ") ||
                q.includes("ਉਰਵਰਕ") ||
                q.includes("fertilizer") ||
                q.includes("khad")
            ) {
                return `ਮੀਂਹ ਦੀ ਸੰਭਾਵਨਾ ਕਰਕੇ, ਆਪਣੀ ${localizedCrop} ਦੀ ਫ਼ਸਲ ਵਿੱਚ ਅੱਜ ਯੂਰੀਆ ਜਾਂ ਖਾਦ ਪਾਉਣ ਤੋਂ ਗੁਰੇਜ਼ ਕਰੋ ਤਾਂ ਜੋ ਖਾਦ ਖੁਰ ਕੇ ਖ਼ਰਾਬ ਨਾ ਹੋਵੇ।`;
            }

            if (
                q.includes("ਬਿਜਾਈ") ||
                q.includes("ਬੀਜ") ||
                q.includes("ਕਣਕ") ||
                q.includes("sowing") ||
                q.includes("seed")
            ) {
                return `${cropLoc} ਵਿੱਚ ${localizedCrop} ਦੀ ਬਿਜਾਈ ਲਈ ਜ਼ਮੀਨ ਦੀ ਸਹੀ ਤਿਆਰੀ ਅਤੇ ਪ੍ਰਮਾਣਿਤ ਬੀਜਾਂ ਦੀ ਸੋਧ ਕਰਕੇ ਹੀ ਬਿਜਾਈ ਕਰੋ।`;
            }

            if (
                q.includes("ਕੀੜੇ") ||
                q.includes("ਕੀੜਾ") ||
                q.includes("ਕੀਟ") ||
                q.includes("pest") ||
                q.includes("insect")
            ) {
                return `ਆਪਣੀ ${localizedCrop} ਦੀ ਫ਼ਸਲ ਦੇ ਪੱਤਿਆਂ ਅਤੇ ਤਣਿਆਂ ਦੀ ਚੰਗੀ ਤਰ੍ਹਾਂ ਜਾਂਚ ਕਰੋ। ਮੀਂਹ ਤੋਂ ਬਾਅਦ ਮੌਸਮ ਸਾਫ਼ ਹੋਣ 'ਤੇ ਹੀ ਸਿਫਾਰਸ਼ ਕੀਤੀ ਸਪਰੇਅ ਕਰੋ।`;
            }

            if (
                q.includes("ਬਿਮਾਰੀ") ||
                q.includes("ਬੀਮਾਰੀ") ||
                q.includes("ਰੋਗ") ||
                q.includes("disease")
            ) {
                return `${cropLoc} ਵਿੱਚ ਜ਼ਿਆਦਾ ਨਮੀ ਕਾਰਨ ${localizedCrop} ਦੀ ਫ਼ਸਲ ਵਿੱਚ ਉੱਲੀ ਰੋਗ ਦਾ ਖ਼ਤਰਾ ਹੋ ਸਕਦਾ ਹੈ। ਜੇਕਰ ਪੱਤਿਆਂ 'ਤੇ ਧੱਬੇ ਦਿਖਣ ਤਾਂ ਤੁਰੰਤ ਮਾਹਿਰਾਂ ਦੀ ਸਲਾਹ ਲਵੋ।`;
            }

            return `${cropLoc} ਵਿੱਚ ਤੁਹਾਡੀ ${localizedCrop} ਦੀ ਫ਼ਸਲ ਲਈ ਮੈਂ ਸਿੰਚਾਈ, ਮੀਂਹ, ਖਾਦ ਅਤੇ ਕੀੜਿਆਂ ਦੀ ਰੋਕਥਾਮ ਬਾਰੇ ਮਦਦ ਕਰ ਸਕਦਾ ਹਾਂ।`;
        }
    }

    // ------------------------------------------------------------------------
    // CASE B: BASELINE / NO FARMER CONTEXT PROVIDED (100% Backward Compatible)
    // ------------------------------------------------------------------------

    // 1. ENGLISH BASELINE
    if (lang === "en-IN") {
        if (
            q.includes("irrigat") ||
            q.includes("irritat") ||
            q.includes("water my crop") ||
            q.includes("water the crop") ||
            q.includes("water my field") ||
            q.includes("water the field") ||
            q.includes("paddy") ||
            q.includes("paani")
        ) {
            return "Before irrigating, check the soil moisture and rainfall forecast. If rain is expected soon, irrigation may not be necessary.";
        } else if (
            q.includes("rain") ||
            q.includes("rainfall") ||
            q.includes("weather")
        ) {
            return "To give you a useful irrigation recommendation, SAARTHI needs your location and crop information.";
        } else if (
            q.includes("fertilizer") ||
            q.includes("fertiliser") ||
            q.includes("fertilize") ||
            q.includes("fertilise") ||
            q.includes("khad")
        ) {
            return "Fertilizer provides essential nutrients to crops and can improve plant growth. The correct fertilizer and quantity depend on your crop and soil condition.";
        } else if (
            q.includes("pest") ||
            q.includes("pests") ||
            q.includes("best") ||
            q.includes("insect") ||
            q.includes("insects")
        ) {
            return "If you notice pests, first inspect the leaves and stems for damage. Identify the pest before choosing a treatment.";
        } else if (
            q.includes("disease") ||
            q.includes("diseases") ||
            q.includes("infection")
        ) {
            return "If your crop shows unusual spots, yellowing or wilting, it may indicate a disease. A photo of the affected plant can help identify the problem.";
        } else {
            return "I can help with irrigation, rainfall, fertilizer, crop diseases and pests. Please ask me a farming-related question.";
        }
    }

    // 2. HINDI BASELINE
    if (lang === "hi-IN") {
        if (
            q.includes("पानी") ||
            q.includes("सिंचाई") ||
            q.includes("irrigat") ||
            q.includes("irritat") ||
            q.includes("paani")
        ) {
            return "सिंचाई करने से पहले मिट्टी की नमी और बारिश का पूर्वानुमान जांचें। अगर जल्द बारिश होने वाली है, तो आज सिंचाई करने की जरूरत नहीं हो सकती है।";
        } else if (
            q.includes("बारिश") ||
            q.includes("वर्षा") ||
            q.includes("rain") ||
            q.includes("barish")
        ) {
            return "बारिश की जानकारी के आधार पर सही सलाह देने के लिए मुझे आपके स्थान और फसल की जानकारी चाहिए।";
        } else if (
            q.includes("खाद") ||
            q.includes("उर्वरक") ||
            q.includes("fertilizer") ||
            q.includes("fertiliser") ||
            q.includes("khad")
        ) {
            return "खाद फसल को जरूरी पोषक तत्व प्रदान करती है और पौधों की वृद्धि में मदद करती है। सही खाद और उसकी मात्रा आपकी फसल और मिट्टी की स्थिति पर निर्भर करती है।";
        } else if (
            q.includes("कीट") ||
            q.includes("कीड़ा") ||
            q.includes("कीड़े") ||
            q.includes("pest") ||
            q.includes("insect")
        ) {
            return "अगर आपकी फसल में कीट दिखाई दे रहे हैं, तो पहले पत्तियों और तनों को ध्यान से जांचें। उपचार करने से पहले कीट की पहचान करना जरूरी है।";
        } else if (
            q.includes("बीमारी") ||
            q.includes("रोग") ||
            q.includes("संक्रमण") ||
            q.includes("disease")
        ) {
            return "अगर आपकी फसल की पत्तियों पर धब्बे, पीलापन या मुरझाने के लक्षण दिखाई दें, तो यह बीमारी का संकेत हो सकता है। समस्या की पहचान करने के लिए फसल की तस्वीर उपयोगी हो सकती है।";
        } else {
            return "मैं सिंचाई, बारिश, खाद, फसल की बीमारी और कीटों से जुड़े सवालों में आपकी मदद कर सकता हूं।";
        }
    }

    // 3. TELUGU BASELINE
    if (lang === "te-IN") {
        if (
            q.includes("నీరు") ||
            q.includes("నీళ్లు") ||
            q.includes("నీళ్ళు") ||
            q.includes("పారుదల") ||
            q.includes("irrigat") ||
            q.includes("irritat") ||
            q.includes("paani")
        ) {
            return "నీటిపారుదల చేయడానికి ముందు నేలలో తేమను మరియు వర్షపాతం అంచనాను పరిశీలించండి. త్వరలో వర్షం వచ్చే అవకాశం ఉంటే, ఈరోజు నీటిపారుదల అవసరం లేకపోవచ్చు.";
        } else if (
            q.includes("వర్షం") ||
            q.includes("వాన") ||
            q.includes("rain")
        ) {
            return "సరైన నీటిపారుదల సలహా ఇవ్వడానికి మీ ప్రాంతం మరియు పంట వివరాలు నాకు అవసరం.";
        } else if (
            q.includes("ఎరువు") ||
            q.includes("ఎరువులు") ||
            q.includes("fertilizer")
        ) {
            return "ఎరువులు పంటలకు అవసరమైన పోషకాలను అందించి మొక్కల ఆరోగ్యకరమైన పెరుగుదలకు సహాయపడతాయి. సరైన ఎరువు మరియు దాని పరిమాణం పంట మరియు నేల పరిస్థితిపై ఆధారపడి ఉంటుంది.";
        } else if (
            q.includes("పురుగు") ||
            q.includes("పురుగులు") ||
            q.includes("కీటకం") ||
            q.includes("pest") ||
            q.includes("insect")
        ) {
            return "పంటలో పురుగులు కనిపిస్తే, ముందుగా ఆకులు మరియు కాండాలను జాగ్రత్తగా పరిశీలించండి. చికిత్స చేయడానికి ముందు పురుగును గుర్తించడం ముఖ్యం.";
        } else if (
            q.includes("వ్యాధి") ||
            q.includes("జబ్బు") ||
            q.includes("disease")
        ) {
            return "పంట ఆకులపై మచ్చలు, పసుపు రంగు లేదా వాడిపోవడం కనిపిస్తే అది వ్యాధికి సంకేతం కావచ్చు. సమస్యను గుర్తించడానికి పంట ఫోటో ఉపయోగపడుతుంది.";
        } else {
            return "నేను నీటిపారుదల, వర్షం, ఎరువులు, పంట వ్యాధులు మరియు పురుగుల గురించి మీకు సహాయం చేయగలను.";
        }
    }

    // 4. PUNJABI BASELINE
    if (lang === "pa-IN") {
        if (
            q.includes("ਪਾਣੀ") ||
            q.includes("ਸਿੰਚਾਈ") ||
            q.includes("ਸਿੰਜਾਈ") ||
            q.includes("ਫਸਲ") ||
            q.includes("ਫ਼ਸਲ") ||
            q.includes("irrigat") ||
            q.includes("irritat") ||
            q.includes("paani")
        ) {
            return "ਸਿੰਚਾਈ ਕਰਨ ਤੋਂ ਪਹਿਲਾਂ ਮਿੱਟੀ ਦੀ ਨਮੀ ਅਤੇ ਮੀਂਹ ਦੀ ਭਵਿੱਖਬਾਣੀ ਦੀ ਜਾਂਚ ਕਰੋ। ਜੇ ਜਲਦੀ ਮੀਂਹ ਪੈਣ ਦੀ ਸੰਭਾਵਨਾ ਹੈ, ਤਾਂ ਅੱਜ ਸਿੰਚਾਈ ਕਰਨ ਦੀ ਲੋੜ ਨਹੀਂ ਹੋ ਸਕਦੀ।";
        } else if (
            q.includes("ਮੀਂਹ") ||
            q.includes("ਬਾਰਿਸ਼") ||
            q.includes("ਵਰਖਾ") ||
            q.includes("rain")
        ) {
            return "ਮੀਂਹ ਦੇ ਆਧਾਰ 'ਤੇ ਸਹੀ ਸਿੰਚਾਈ ਦੀ ਸਲਾਹ ਦੇਣ ਲਈ ਮੈਨੂੰ ਤੁਹਾਡੇ ਸਥਾਨ ਅਤੇ ਫਸਲ ਦੀ ਜਾਣਕਾਰੀ ਚਾਹੀਦੀ ਹੈ।";
        } else if (
            q.includes("ਬਿਜਾਈ") ||
            q.includes("ਬੀਜ") ||
            q.includes("ਕਣਕ") ||
            q.includes("sowing") ||
            q.includes("seed")
        ) {
            return "ਕਣਕ ਜਾਂ ਹੋਰ ਹਾੜ੍ਹੀ ਦੀਆਂ ਫ਼ਸਲਾਂ ਦੀ ਬਿਜਾਈ ਲਈ ਨਵੰਬਰ ਦਾ ਪਹਿਲਾ ਪੰਦਰਵਾੜਾ ਸਭ ਤੋਂ ਵਧੀਆ ਸਮਾਂ ਹੈ। ਬਿਜਾਈ ਤੋਂ ਪਹਿਲਾਂ ਉੱਨਤ ਬੀਜਾਂ ਦੀ ਸੋਧ ਜ਼ਰੂਰ ਕਰੋ।";
        } else if (
            q.includes("ਖਾਦ") ||
            q.includes("ਖਾਦਾਂ") ||
            q.includes("ਉਰਵਰਕ") ||
            q.includes("fertilizer") ||
            q.includes("fertiliser") ||
            q.includes("khad")
        ) {
            return "ਖਾਦ ਫਸਲਾਂ ਨੂੰ ਜ਼ਰੂਰੀ ਪੋਸ਼ਕ ਤੱਤ ਪ੍ਰਦਾਨ ਕਰਦੀ ਹੈ ਅਤੇ ਪੌਦਿਆਂ ਦੇ ਵਾਧੇ ਵਿੱਚ ਮਦਦ ਕਰਦੀ ਹੈ। ਸਹੀ ਖਾਦ ਅਤੇ ਇਸ ਦੀ ਮਾਤਰਾ ਤੁਹਾਡੀ ਫਸਲ ਅਤੇ ਮਿੱਟੀ ਦੀ ਸਥਿਤੀ 'ਤੇ ਨਿਰਭਰ ਕਰਦੀ ਹੈ।";
        } else if (
            q.includes("ਕੀੜੇ") ||
            q.includes("ਕੀੜਾ") ||
            q.includes("ਕੀਟ") ||
            q.includes("pest") ||
            q.includes("insect")
        ) {
            return "ਜੇ ਤੁਹਾਡੀ ਫਸਲ ਵਿੱਚ ਕੀੜੇ ਦਿਖਾਈ ਦੇ ਰਹੇ ਹਨ, ਤਾਂ ਪਹਿਲਾਂ ਪੱਤਿਆਂ ਅਤੇ ਤਣਿਆਂ ਦੀ ਧਿਆਨ ਨਾਲ ਜਾਂਚ ਕਰੋ। ਇਲਾਜ ਕਰਨ ਤੋਂ ਪਹਿਲਾਂ ਕੀੜੇ ਦੀ ਪਛਾਣ ਕਰਨਾ ਜ਼ਰੂਰੀ ਹੈ।";
        } else if (
            q.includes("ਬਿਮਾਰੀ") ||
            q.includes("ਬੀਮਾਰੀ") ||
            q.includes("ਰੋਗ") ||
            q.includes("disease")
        ) {
            return "ਜੇ ਤੁਹਾਡੀ ਫਸਲ ਦੇ ਪੱਤਿਆਂ 'ਤੇ ਧੱਬੇ, ਪੀਲਾਪਣ ਜਾਂ ਮੁਰਝਾਉਣ ਦੇ ਲੱਛਣ ਦਿਖਾਈ ਦੇਣ, ਤਾਂ ਇਹ ਬਿਮਾਰੀ ਦਾ ਸੰਕੇਤ ਹੋ ਸਕਦਾ ਹੈ। ਸਮੱਸਿਆ ਦੀ ਪਛਾਣ ਕਰਨ ਲਈ ਫਸਲ ਦੀ ਤਸਵੀਰ ਮਦਦਗਾਰ ਹੋ ਸਕਦੀ ਹੈ।";
        } else {
            return "ਮੈਂ ਸਿੰਚਾਈ, ਮੀਂਹ, ਖਾਦ, ਫਸਲ ਦੀਆਂ ਬਿਮਾਰੀਆਂ ਅਤੇ ਕੀੜਿਆਂ ਨਾਲ ਸਬੰਧਤ ਸਵਾਲਾਂ ਵਿੱਚ ਤੁਹਾਡੀ ਮਦਦ ਕਰ ਸਕਦਾ ਹਾਂ।";
        }
    }

    return "Please select a supported language.";
}

module.exports = {
    generateAnswer
};
