/**
 * SAARTHI Bilingual Translation Engine
 * Instant Language Switching for English (EN) and Hindi (हिन्दी).
 * Served across India.
 */

const I18N_DICTIONARY = {
  en: {
    // Navigation
    nav_home: "Overview",
    nav_farmer: "Farmer Portal",
    nav_map: "Risk Map",
    nav_timeline: "Forecast",
    nav_check_block: "Check your block",
    live_monitoring: "Live Monitoring",
    district_name: "Select State → District → Block · Live ECMWF IFS Outlook",
    geo_state: "State",
    geo_district: "District",

    // Hero / Landing
    hero_eyebrow: "Hyperlocal Climate Intelligence",
    hero_title_1: "Know the",
    hero_title_em: "monsoon",
    hero_title_2: "before it arrives.",
    hero_desc: "Live 16-day ECMWF IFS rainfall outlook for your selected block, with a 17–30 day climatological outlook.",
    hero_cta: "Open Farmer Portal",
    hero_secondary_cta: "View Risk Map",
    hero_badge: "Designed for every field, every season",
    hero_card_kicker: "Saarthi · Live Block Outlook",
    hero_card_signal: "This week's monsoon signal",
    hero_card_days: "days",
    hero_card_sub: "early warning for dry spells",
    risk_low: "LOW (<10.94 mm)",
    risk_mod: "NORMAL",
    risk_high: "HIGH (>34.66 mm)",

    // Stats
    stat_1_val: "16-Day",
    stat_1_lbl: "Live ECMWF IFS Outlook",
    stat_2_val: "D+1–D+16",
    stat_2_lbl: "Live horizon per selected block",
    stat_3_val: "7,179 Blocks",
    stat_3_lbl: "Live national block registry",
    stat_4_val: "19.54 mm",
    stat_4_lbl: "Historical Test MAE (NB04)",

    // Feature Cards
    portal_features_title: "Explore the SAARTHI Platform",
    portal_features_sub: "Decision support portals for farmers and agriculture communities.",
    card_farmer_title: "Farmer Advisory Portal",
    card_farmer_desc: "Personalized sowing decisions, 4-pillar risk breakdown, and root-zone moisture dial.",
    card_map_title: "Block-Scale Outlook Map",
    card_map_desc: "Interactive map with live rainfall categories for Sangrur polygons and any selected block.",
    card_timeline_title: "7-Day Forecast Outlook",
    card_timeline_desc: "Live daily rainfall, probabilities and day-by-day matrix for the selected block.",

    // Landing Outlook Section
    outlook_heading: "Your Block Outlook",
    outlook_title_1: "One selection.",
    outlook_title_em: "A clear decision.",
    outlook_desc: "Select State → District → Block. Saarthi shows the live ECMWF IFS rainfall outlook with 3/7/15-day totals.",
    choose_block: "Choose your block",
    latest_block_analysis: "Latest Block Analysis",
    rain_7d: "7-day total rainfall",
    dry_14d: "Dry / wet days",
    expected_rain: "Heaviest day",
    rain_trend: "Rainfall Trend",
    dry_spell_prob_label: "Dry Spell Probability",
    dry_spell_caption: "LOW / NORMAL / HIGH 7-day rainfall category with calibrated probabilities (training thresholds).",
    sowing_rec: "Sowing Recommendation",

    // Farmer Portal
    farmer_title: "Farmer Advisory",
    farmer_subtitle: "Hyperlocal sowing decisions for your selected block.",
    farm_profile: "Farm Profile",
    lbl_block: "Block",
    lbl_panchayat: "Panchayat",
    lbl_crop: "Crop Variety",
    lbl_soil: "Soil Type",
    lbl_sowing_date: "Planned Sowing Date",
    lbl_irrigation: "Irrigation Source",
    btn_update_advisory: "Compute Advisory",
    lbl_sowing_decision: "Sowing Decision",
    lbl_recommended_window: "Cited Sowing Window",
    lbl_model_confidence: "Model Source",
    pillar_weather: "Weather Watch",
    pillar_soil: "Surface Soil Moisture",
    pillar_crop: "Crop Water Need",
    pillar_dry_spell: "Dry-Spell Watch",
    lbl_root_moisture: "Forecast Surface Soil Moisture",
    lbl_root_moisture_sub: "Volumetric water content in active root zone (0–30 cm).",
    btn_copy_whatsapp: "Share WhatsApp Advisory",
    btn_copied: "Advisory Copied to Clipboard ✓",

    // Map
    map_title: "Block-Scale Outlook Map",
    map_subtitle: "Interactive block map with live rainfall categories.",
    map_filter_block: "Filter by Block",
    map_filter_risk: "Filter by Category",
    map_all_blocks: "All blocks",
    map_all_risks: "All Categories",
    map_panchayats_active: "Panchayats Monitored",
    map_standby: "Select a state, district and block to view the live outlook.",
    map_show_boundary: "Show block boundary",
    map_legend_poly: "Selected block",
    map_legend_marker: "Live forecast marker",

    // Timeline / Forecast
    timeline_title: "7-Day Forecast Outlook",
    timeline_subtitle: "Live daily rainfall and day-by-day matrix for the selected block.",
    timeline_horizon: "Forecast Interval",
    timeline_chart_label: "Probabilistic Cumulative Rainfall vs Soil Moisture Depletion Curve",
    th_day: "Day",
    th_date: "Date",
    th_rain: "Predicted Rain",
    th_temp: "Temperature",
    th_moisture: "Soil Moisture",
    th_risk: "Risk Status",
    th_wetdry: "Wet / Dry (≥1 mm)",

    // Plain-language explanations ("What this means")
    meaning_kicker: "WHAT THIS MEANS",
    plain_kicker: "In simple terms",
    climate_plain_title: "Climate context in simple terms",
    w34_guide_note: "This is a longer-range guide based on the block's usual rainfall pattern — not a day-by-day rainfall forecast.",
    climate_guide_note: "Large-scale background only — the local 16-day forecast above comes from ECMWF IFS.",
    meaning_risk_high: "Rain is expected on {n} of the next 3 days. Field work may be difficult, so consider avoiding activities that need dry soil.",
    meaning_risk_moderate: "Some rain-related field disruption is possible over the next 3 days. Check the daily rain forecast before planning field work.",
    meaning_risk_low: "No major rain-related field-work problem is currently indicated for the next 3 days. This does not mean there will be no rain — keep using the daily forecast when planning.",
    meaning_risk_unavailable: "Rain is forecast for this block, but field-risk scoring is not available here yet. Use the 16-day rain forecast above when planning field work.",
    meaning_live_dry: "The next several days are expected to stay mostly dry. If your crop needs water, monitor soil moisture and plan irrigation according to crop stage.",
    meaning_live_moderate: "Some rain is expected during this period. Rain may reduce irrigation needs, but check the daily forecast before spraying or field work.",
    meaning_live_wet: "Significant rain is expected during this period. Prepare for wet field conditions and possible delays to field operations.",
    meaning_live_concentrated: "Most of the expected rain is concentrated around {dates}. These days may be less suitable for spraying, sowing, harvesting, or other field work.",
    meaning_live_unavailable: "The rainfall forecast for this block is currently unavailable. Please try again later — no estimated values are shown.",
    meaning_w34_about: "This is a longer-range guide based on the block's usual rainfall pattern. It can help with broad planning for weeks 3–4, but it should not be treated as a day-by-day rain forecast.",
    meaning_w34_below: "Rainfall during this period is expected to be below the block's usual level.",
    meaning_w34_near: "Rainfall during this period is expected to be close to the block's usual level.",
    meaning_w34_above: "Rainfall during this period is expected to be above the block's usual level.",
    meaning_w34_unavailable: "This longer-range outlook is not available for this block yet. The 16-day ECMWF IFS forecast above is still available.",
    meaning_mjo: "MJO describes a large-scale movement of tropical rainfall and thunderstorms around the world. Its current state can influence monsoon activity, but this is background climate information — the local rainfall forecast above remains the main guide.",
    meaning_mjo_phase: "MJO is currently in Phase {phase}. This is useful background for understanding broader monsoon conditions, but the local 16-day rainfall forecast still comes from ECMWF IFS.",
    meaning_enso: "ENSO describes unusually warm or cool conditions in the tropical Pacific Ocean. It can influence monsoon patterns across large regions, but it does not directly determine the rainfall forecast for this block.",
    meaning_enso_elnino: "The Pacific is currently in an El Niño state. El Niño can influence India's monsoon pattern, but the local rainfall forecast above should be used for near-term planning.",
    meaning_enso_neutral: "Pacific Ocean conditions are currently near neutral. ENSO is shown as background climate information; the local forecast above remains the main guide for the next 16 days.",
    meaning_enso_lanina: "The Pacific is currently in a La Niña state. La Niña can influence India's monsoon pattern, but the local rainfall forecast above should be used for near-term planning.",
    meaning_iod: "IOD describes the difference in sea-surface temperatures between the western and eastern tropical Indian Ocean. It can influence monsoon rainfall patterns, but it is background climate information and does not replace the local ECMWF IFS forecast.",
    meaning_iod_stale: "IOD is currently shown as {state}, but the latest available IOD data is older than the current forecast. Treat this as background information rather than a current local forecast signal.",

    // Footer
    footer_text: "Climate clarity for stronger rural futures.",
    footer_copy: "© 2026 · SIH26086 Hyperlocal Monsoon Intelligence"
  },

  hi: {
    // Navigation
    nav_home: "मुख्य पृष्ठ",
    nav_farmer: "किसान पोर्टल",
    nav_map: "जोखिम मानचित्र",
    nav_timeline: "पूर्वानुमान",
    nav_check_block: "अपना ब्लॉक देखें",
    live_monitoring: "लाइव मॉनिटरिंग",
    district_name: "राज्य → जिला → ब्लॉक चुनें · लाइव ECMWF IFS आउटलुक",
    geo_state: "राज्य",
    geo_district: "जिला",

    // Hero / Landing
    hero_eyebrow: "अति-स्थानीय जलवायु बुद्धिमत्ता",
    hero_title_1: "मानसून को",
    hero_title_em: "आगमन से पहले",
    hero_title_2: "सटीकता से जानें।",
    hero_desc: "आपके चुने गए ब्लॉक के लिए लाइव 16-दिवसीय ECMWF IFS वर्षा पूर्वानुमान, 17–30 दिवसीय जलवायु आउटलुक सहित।",
    hero_cta: "किसान पोर्टल खोलें",
    hero_secondary_cta: "जोखिम नक्शा देखें",
    hero_badge: "हर खेत, हर मौसम के लिए तैयार",
    hero_card_kicker: "सारथी · लाइव ब्लॉक आउटलुक",
    hero_card_signal: "इस सप्ताह का मानसूनी संकेत",
    hero_card_days: "दिन",
    hero_card_sub: "सूखे की पूर्व चेतावनी",
    risk_low: "LOW (<10.94 mm)",
    risk_mod: "NORMAL",
    risk_high: "HIGH (>34.66 mm)",

    // Stats
    stat_1_val: "16-दिवसीय",
    stat_1_lbl: "लाइव ECMWF IFS आउटलुक",
    stat_2_val: "D+1–D+16",
    stat_2_lbl: "चुने गए ब्लॉक हेतु लाइव अवधि",
    stat_3_val: "7,179 ब्लॉक",
    stat_3_lbl: "लाइव राष्ट्रीय ब्लॉक रजिस्ट्री",
    stat_4_val: "19.54 mm",
    stat_4_lbl: "ऐतिहासिक टेस्ट MAE",

    // Feature Cards
    portal_features_title: "सारथी प्लेटफॉर्म का अन्वेषण करें",
    portal_features_sub: "किसानों और कृषि समुदायों के लिए निर्णय सहायता पोर्टल।",
    card_farmer_title: "किसान परामर्श पोर्टल",
    card_farmer_desc: "व्यक्तिगत बुवाई निर्णय, 4-स्तंभ जोखिम विश्लेषण और मिट्टी की नमी का मीटर।",
    card_map_title: "ब्लॉक-स्तरीय दृष्टिकोण मानचित्र",
    card_map_desc: "संगरूर बहुभुजों और चुने गए ब्लॉक के लिए लाइव वर्षा श्रेणियों वाला इंटरएक्टिव मानचित्र।",
    card_timeline_title: "7-दिवसीय पूर्वानुमान",
    card_timeline_desc: "चुने गए ब्लॉक के लिए लाइव दैनिक वर्षा, संभावनाएँ और तालिका।",

    // Landing Outlook Section
    outlook_heading: "आपके ब्लॉक का पूर्वानुमान",
    outlook_title_1: "एक चयन।",
    outlook_title_em: "स्पष्ट निर्णय।",
    outlook_desc: "राज्य → जिला → ब्लॉक चुनें। सारथी 3/7/15-दिवसीय योग सहित लाइव ECMWF IFS वर्षा पूर्वानुमान दिखाता है।",
    choose_block: "अपना ब्लॉक चुनें",
    latest_block_analysis: "नवीनतम ब्लॉक विश्लेषण",
    rain_7d: "7-दिवसीय कुल वर्षा",
    dry_14d: "सूखे / गीले दिन",
    expected_rain: "सर्वाधिक वर्षा दिवस",
    rain_trend: "वर्षा का रुझान",
    dry_spell_prob_label: "सूखे की संभावना",
    dry_spell_caption: "कैलिब्रेटेड संभावनाओं सहित LOW / NORMAL / HIGH 7-दिवसीय वर्षा श्रेणी।",
    sowing_rec: "बुवाई की सिफारिश",

    // Farmer Portal
    farmer_title: "किसान परामर्श पोर्टल",
    farmer_subtitle: "आपके चुने गए ब्लॉक के लिए अति-स्थानीय बुवाई निर्णय।",
    farm_profile: "खेत की प्रोफाइल",
    lbl_block: "ब्लॉक",
    lbl_panchayat: "पंचायत",
    lbl_crop: "फसल किस्म",
    lbl_soil: "मिट्टी का प्रकार",
    lbl_sowing_date: "योजनाबद्ध बुवाई तिथि",
    lbl_irrigation: "सिंचाई का साधन",
    btn_update_advisory: "सलाह अपडेट करें",
    lbl_sowing_decision: "बुवाई का निर्णय",
    lbl_recommended_window: "अनुशंसित बुवाई अवधि",
    lbl_model_confidence: "मॉडल स्रोत",
    pillar_weather: "मौसम जोखिम",
    pillar_soil: "मिट्टी नमी जोखिम",
    pillar_crop: "फसल संवेदनशीलता",
    pillar_dry_spell: "सूखे का अनुमानित जोखिम",
    lbl_root_moisture: "जड़-क्षेत्र मिट्टी की नमी",
    lbl_root_moisture_sub: "सक्रिय जड़ क्षेत्र (0–30 सेमी) में पानी की मात्रा।",
    btn_copy_whatsapp: "व्हाट्सएप सलाह कॉपी करें",
    btn_copied: "सलाह क्लिपबोर्ड पर कॉपी हो गई ✓",

    // Map
    map_title: "ब्लॉक-स्तरीय दृष्टिकोण मानचित्र",
    map_subtitle: "लाइव वर्षा श्रेणियों वाला इंटरएक्टिव ब्लॉक मानचित्र।",
    map_filter_block: "ब्लॉक के अनुसार फ़िल्टर करें",
    map_filter_risk: "श्रेणी अनुसार फ़िल्टर करें",
    map_all_blocks: "सभी ब्लॉक",
    map_all_risks: "सभी श्रेणियाँ",
    map_panchayats_active: "निगरानी में पंचायतें",
    map_standby: "लाइव आउटलुक देखने हेतु राज्य, जिला और ब्लॉक चुनें।",
    map_show_boundary: "ब्लॉक सीमा दिखाएँ",
    map_legend_poly: "चुना गया ब्लॉक",
    map_legend_marker: "लाइव पूर्वानुमान मार्कर",

    // Timeline / Forecast
    timeline_title: "7-दिवसीय मौसम पूर्वानुमान",
    timeline_subtitle: "चुने गए ब्लॉक के लिए लाइव दैनिक वर्षा और तालिका।",
    timeline_horizon: "पूर्वानुमान अवधि",
    timeline_chart_label: "संभाव्य संचयी वर्षा बनाम मिट्टी की नमी ह्रास वक्र",
    th_day: "दिन",
    th_date: "दिनांक",
    th_rain: "अनुमानित वर्षा",
    th_temp: "तापमान",
    th_moisture: "मिट्टी की नमी",
    th_risk: "जोखिम स्थिति",
    th_wetdry: "गीला / सूखा (≥1 मिमी)",

    // सरल भाषा में व्याख्या ("इसका मतलब")
    meaning_kicker: "इसका मतलब",
    plain_kicker: "सरल शब्दों में",
    climate_plain_title: "सरल शब्दों में जलवायु पृष्ठभूमि",
    w34_guide_note: "यह ब्लॉक के सामान्य वर्षा पैटर्न पर आधारित लंबी अवधि का मार्गदर्शन है — दिन-प्रतिदिन का वर्षा पूर्वानुमान नहीं।",
    climate_guide_note: "केवल बड़े पैमाने की पृष्ठभूमि जानकारी — ऊपर दिया स्थानीय 16-दिवसीय पूर्वानुमान ECMWF IFS से आता है।",
    meaning_risk_high: "अगले 3 दिनों में से {n} दिन वर्षा की उम्मीद है। खेत का काम मुश्किल हो सकता है, इसलिए सूखी मिट्टी वाले काम टालने पर विचार करें।",
    meaning_risk_moderate: "अगले 3 दिनों में वर्षा से खेत के काम में कुछ बाधा संभव है। काम की योजना बनाने से पहले दैनिक वर्षा पूर्वानुमान ज़रूर देखें।",
    meaning_risk_low: "अगले 3 दिनों में वर्षा से जुड़े खेत-कार्य में किसी बड़ी बाधा का संकेत नहीं है। इसका मतलब यह नहीं कि वर्षा बिल्कुल नहीं होगी — योजना के लिए दैनिक पूर्वानुमान देखते रहें।",
    meaning_risk_unavailable: "इस ब्लॉक के लिए वर्षा का पूर्वानुमान उपलब्ध है, लेकिन खेत-जोखिम गणना अभी यहाँ उपलब्ध नहीं है। योजना के लिए ऊपर दिया 16-दिवसीय वर्षा पूर्वानुमान देखें।",
    meaning_live_dry: "आने वाले कई दिन ज़्यादातर सूखे रहने की उम्मीद है। फसल की अवस्था के अनुसार मिट्टी की नमी पर नज़र रखें और सिंचाई की योजना बनाएँ।",
    meaning_live_moderate: "इस अवधि में कुछ वर्षा की उम्मीद है। वर्षा से सिंचाई की ज़रूरत कम हो सकती है, लेकिन छिड़काव या खेत के काम से पहले दैनिक पूर्वानुमान ज़रूर देखें।",
    meaning_live_wet: "इस अवधि में अच्छी वर्षा की उम्मीद है। खेत गीले रह सकते हैं और काम में देरी संभव है — पहले से तैयारी रखें।",
    meaning_live_concentrated: "अधिकांश वर्षा {dates} के आसपास केंद्रित है। इन दिनों छिड़काव, बुवाई, कटाई या अन्य खेत कार्य कम उपयुक्त हो सकते हैं।",
    meaning_live_unavailable: "इस ब्लॉक का वर्षा पूर्वानुमान अभी उपलब्ध नहीं है। कृपया बाद में पुनः प्रयास करें — कोई अनुमानित आँकड़ा नहीं दिखाया जा रहा है।",
    meaning_w34_about: "यह लंबी अवधि का मार्गदर्शन इस ब्लॉक के सामान्य वर्षा पैटर्न पर आधारित है। सप्ताह 3–4 की मोटी योजना में सहायक है, लेकिन इसे दिन-प्रतिदिन का वर्षा पूर्वानुमान न मानें।",
    meaning_w34_below: "इस अवधि में वर्षा ब्लॉक के सामान्य स्तर से कम रहने की उम्मीद है।",
    meaning_w34_near: "इस अवधि में वर्षा ब्लॉक के सामान्य स्तर के आसपास रहने की उम्मीद है।",
    meaning_w34_above: "इस अवधि में वर्षा ब्लॉक के सामान्य स्तर से अधिक रहने की उम्मीद है।",
    meaning_w34_unavailable: "इस ब्लॉक के लिए यह लंबी अवधि का आउटलुक अभी उपलब्ध नहीं है। ऊपर दिया 16-दिवसीय ECMWF IFS पूर्वानुमान तब भी उपलब्ध है।",
    meaning_mjo: "MJO दुनिया भर में उष्णकटिबंधीय वर्षा और तूफ़ानों की बड़े पैमाने की हलचल है। इसकी वर्तमान स्थिति मानसून को प्रभावित कर सकती है, लेकिन यह केवल पृष्ठभूमि जानकारी है — ऊपर दिया स्थानीय वर्षा पूर्वानुमान ही मुख्य मार्गदर्शक है।",
    meaning_mjo_phase: "MJO वर्तमान में फ़ेज़ {phase} में है। यह व्यापक मानसूनी स्थितियों को समझने में सहायक पृष्ठभूमि है, लेकिन स्थानीय 16-दिवसीय वर्षा पूर्वानुमान ECMWF IFS से ही आता है।",
    meaning_enso: "ENSO प्रशांत महासागर के असामान्य गर्म या ठंडे हालात को दर्शाता है। यह बड़े क्षेत्रों के मानसून को प्रभावित कर सकता है, लेकिन इस ब्लॉक का वर्षा पूर्वानुमान सीधे इससे तय नहीं होता।",
    meaning_enso_elnino: "प्रशांत महासागर वर्तमान में अल नीनो स्थिति में है। अल नीनो भारत के मानसून को प्रभावित कर सकता है, लेकिन निकट अवधि की योजना के लिए ऊपर दिया स्थानीय वर्षा पूर्वानुमान ही देखें।",
    meaning_enso_neutral: "प्रशांत महासागर की स्थिति वर्तमान में लगभग सामान्य है। ENSO यहाँ पृष्ठभूमि जानकारी के रूप में दिखाया गया है; अगले 16 दिनों के लिए ऊपर दिया स्थानीय पूर्वानुमान ही मुख्य मार्गदर्शक है।",
    meaning_enso_lanina: "प्रशांत महासागर वर्तमान में ला नीना स्थिति में है। ला नीना भारत के मानसून को प्रभावित कर सकता है, लेकिन निकट अवधि की योजना के लिए ऊपर दिया स्थानीय वर्षा पूर्वानुमान ही देखें।",
    meaning_iod: "IOD पश्चिमी और पूर्वी हिंद महासागर के समुद्री तापमान के अंतर को दर्शाता है। यह मानसूनी वर्षा को प्रभावित कर सकता है, लेकिन यह पृष्ठभूमि जानकारी है — स्थानीय ECMWF IFS पूर्वानुमान का विकल्प नहीं।",
    meaning_iod_stale: "IOD वर्तमान में {state} दिख रहा है, लेकिन उपलब्ध IOD आँकड़ा वर्तमान पूर्वानुमान से पुराना है। इसे पृष्ठभूमि जानकारी मानें, न कि वर्तमान स्थानीय पूर्वानुमान संकेत।",

    // Footer
    footer_text: "मजबूत ग्रामीण भविष्य के लिए सटीक मौसम बुद्धिमत्ता।",
    footer_copy: "© 2026 · SIH26086 अति-स्थानीय मानसूनी बुद्धिमत्ता प्रणाली"
  },

};

const SUPPORTED_LANGUAGES = ["en", "hi"];

function normalizeLanguage(lang) {
  return SUPPORTED_LANGUAGES.includes(lang) ? lang : "en";
}

let currentLanguage = normalizeLanguage(localStorage.getItem("saarthi_lang"));
if (localStorage.getItem("saarthi_lang") !== currentLanguage) {
  // Migrate obsolete stored values (e.g. legacy "pa") to English.
  localStorage.setItem("saarthi_lang", currentLanguage);
}

function getTranslation(key, lang = currentLanguage) {
  if (I18N_DICTIONARY[lang] && I18N_DICTIONARY[lang][key]) {
    return I18N_DICTIONARY[lang][key];
  }
  if (I18N_DICTIONARY.en[key]) {
    return I18N_DICTIONARY.en[key];
  }
  return key;
}

function setLanguage(lang) {
  currentLanguage = normalizeLanguage(lang);
  localStorage.setItem("saarthi_lang", currentLanguage);
  applyTranslations();
  
  // Trigger custom event for reactive components
  window.dispatchEvent(new CustomEvent("languageChanged", { detail: { language: currentLanguage } }));
}

function applyTranslations() {
  const elements = document.querySelectorAll("[data-i18n]");
  elements.forEach((el) => {
    const key = el.getAttribute("data-i18n");
    const trans = getTranslation(key);
    if (trans) {
      if (el.tagName === "INPUT" && el.getAttribute("type") === "button") {
        el.value = trans;
      } else if (el.hasAttribute("data-i18n-placeholder")) {
        el.placeholder = trans;
      } else {
        el.innerHTML = trans;
      }
    }
  });

  // Update active states on bilingual buttons
  document.querySelectorAll(".lang-btn, .lang-switcher button").forEach((btn) => {
    btn.classList.toggle("active", btn.getAttribute("data-lang") === currentLanguage);
  });

  // Update HTML lang attribute
  document.documentElement.lang = currentLanguage;
}

// Initialize on DOM load
document.addEventListener("DOMContentLoaded", () => {
  applyTranslations();
  
  // Bind click handlers to language buttons
  document.querySelectorAll(".lang-btn, .lang-switcher button").forEach((btn) => {
    btn.addEventListener("click", (e) => {
      e.preventDefault();
      const chosen = btn.getAttribute("data-lang");
      if (chosen) setLanguage(chosen);
    });
  });
});
