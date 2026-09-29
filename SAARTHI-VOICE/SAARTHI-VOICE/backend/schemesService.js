/**
 * ============================================================================
 * 🌾 SAARTHI - Government Schemes Service (Punjab & Central Agricultural Schemes)
 * ============================================================================
 * 
 * Implements the interactive two-step government schemes workflow:
 * 1. LISTING: Returns ONLY a clean numbered list of scheme names.
 * 2. DETAILS: Returns structured details ONLY for the selected scheme:
 *    • Eligibility/criteria
 *    • Required documents
 *    • Benefits
 *    • Application procedure
 *    • Timeline/deadline
 *    • Official source
 *    • Helpline
 * 3. FOLLOW-UP: Answers specific follow-up questions for the selected scheme.
 * ============================================================================
 */

const PUNJAB_SCHEMES = [
    {
        id: "pm_kisan",
        index: 1,
        letter: "A",
        aliases: ["pm kisan", "kisan samman", "kisan nidhi", "scheme 1", "scheme a", "1", "a", "ਪੀ ਐਮ ਕਿਸਾਨ", "पीएम किसान", "పీఎం కిసాన్"],
        names: {
            "en-IN": "Pradhan Mantri Kisan Samman Nidhi (PM-KISAN)",
            "pa-IN": "ਪ੍ਰਧਾਨ ਮੰਤਰੀ ਕਿਸਾਨ ਸੰਮਾਨ ਨਿਧੀ (PM-KISAN)",
            "hi-IN": "प्रधानमंत्री किसान सम्मान निधि (PM-KISAN)",
            "te-IN": "ప్రధాన మంత్రి కిసాన్ సమ్మాన్ నిధి (PM-KISAN)"
        },
        bullets: {
            "en-IN": {
                eligibility: "All small, marginal, and landholding farmer families in Punjab with cultivable land registered in their name. Excludes institutional landholders, government employees, and income tax payers.",
                documents: "Aadhaar Card, Land ownership papers (Fard / Jamabandi), Bank account linked with Aadhaar, Active mobile number.",
                benefits: "₹6,000 per year provided in 3 equal installments of ₹2,000 every four months, credited directly via DBT to the farmer's bank account.",
                applicationProcedure: "Apply online at the official PM-KISAN portal (pmkisan.gov.in) under 'Farmer Corner' > 'New Farmer Registration', or visit your nearest Common Service Centre (CSC) or Block Agriculture Office.",
                timeline: "Registration is open all year round. Bi-annual e-KYC updates are required before each disbursement.",
                officialSource: "https://pmkisan.gov.in (Ministry of Agriculture & Farmers Welfare)",
                helpline: "PM-KISAN Toll-Free: 155261 / 1800-115-526 | Direct Helpline: 011-24300606"
            },
            "pa-IN": {
                eligibility: "ਪੰਜਾਬ ਦੇ ਸਾਰੇ ਛੋਟੇ, ਸੀਮਾਂਤ ਅਤੇ ਜ਼ਮੀਨ ਮਾਲਕ ਕਿਸਾਨ ਪਰਿਵਾਰ ਜਿਨ੍ਹਾਂ ਦੇ ਨਾਮ ਖੇਤੀਯੋਗ ਜ਼ਮੀਨ ਦਰਜ ਹੈ। ਸਰਕਾਰੀ ਮੁਲਾਜ਼ਮ ਅਤੇ ਇਨਕਮ ਟੈਕਸ ਦੇਣ ਵਾਲੇ ਇਸ ਦੇ ਯੋਗ ਨਹੀਂ ਹਨ।",
                documents: "ਆਧਾਰ ਕਾਰਡ, ਜ਼ਮੀਨ ਦੀ ਫ਼ਰਦ/ਜਮ੍ਹਾਂਬੰਦੀ, ਆਧਾਰ ਲਿੰਕਡ ਬੈਂਕ ਖਾਤਾ, ਚਾਲੂ ਮੋਬਾਈਲ ਨੰਬਰ।",
                benefits: "ਸਾਲਾਨਾ ₹6,000 ਦੀ ਸਹਾਇਤਾ, ਜੋ 4-4 ਮਹੀਨਿਆਂ ਬਾਅਦ ₹2,000 ਦੀਆਂ 3 ਬਰਾਬਰ ਕਿਸ਼ਤਾਂ ਵਿੱਚ ਸਿੱਧੀ ਬੈਂਕ ਖਾਤੇ ਵਿੱਚ ਭੇਜੀ ਜਾਂਦੀ ਹੈ।",
                applicationProcedure: "pmkisan.gov.in ਪੋਰਟਲ 'ਤੇ 'New Farmer Registration' ਰਾਹੀਂ ਆਨਲਾਈਨ ਅਪਲਾਈ ਕਰੋ ਜਾਂ ਨਜ਼ਦੀਕੀ ਸੇਵਾ ਕੇਂਦਰ (CSC) ਜਾਂ ਬਲਾਕ ਖੇਤੀਬਾੜੀ ਦਫ਼ਤਰ ਜਾਓ।",
                timeline: "ਰਜਿਸਟ੍ਰੇਸ਼ਨ ਪੂਰਾ ਸਾਲ ਖੁੱਲ੍ਹੀ ਰਹਿੰਦੀ ਹੈ। ਹਰੇਕ ਕਿਸ਼ਤ ਤੋਂ ਪਹਿਲਾਂ ਈ-ਕੇਵਾਈਸੀ (e-KYC) ਲਾਜ਼ਮੀ ਹੈ।",
                officialSource: "https://pmkisan.gov.in (ਖੇਤੀਬਾੜੀ ਮੰਤਰਾਲਾ, ਭਾਰਤ ਸਰਕਾਰ)",
                helpline: "ਟੋਲ-ਫ੍ਰੀ: 155261 / 1800-115-526 | ਹੈਲਪਲਾਈਨ: 011-24300606"
            },
            "hi-IN": {
                eligibility: "पंजाब के सभी किसान परिवार जिनके नाम कृषि योग्य भूमि दर्ज है। संस्थागत भूमिधारक, सरकारी कर्मचारी और आयकर दाता अपात्र हैं।",
                documents: "आधार कार्ड, जमीन की नकल/जमाबंदी, आधार से जुड़ा बैंक खाता, मोबाइल नंबर।",
                benefits: "प्रति वर्ष ₹6,000 की वित्तीय सहायता, जो ₹2,000 की 3 समान किस्तों में सीधे बैंक खाते (DBT) में भेजी जाती है।",
                applicationProcedure: "आधिकारिक पोर्टल pmkisan.gov.in पर 'Farmer Corner' में जाकर पंजीकरण करें या नजदीकी सीएससी (CSC) या कृषि कार्यालय जाएं।",
                timeline: "पंजीकरण पूरे वर्ष खुला रहता है। प्रत्येक किस्त से पहले e-KYC अनिवार्य है।",
                officialSource: "https://pmkisan.gov.in (कृषि एवं किसान कल्याण मंत्रालय)",
                helpline: "टोल फ्री नंबर: 155261 / 1800-115-526 | फोन: 011-24300606"
            },
            "te-IN": {
                eligibility: "సాగు భూమి కలిగిన పంజాబ్ రైతు కుటుంబాలందరూ అర్హులు. సంస్థాగత భూ యజమానులు, ప్రభుత్వ ఉద్యోగులు మరియు ఆదాయపు పన్ను చెల్లింపుదారులు మినహాయించబడ్డారు.",
                documents: "ఆధార్ కార్డు, భూమి పట్టాదారు పాస్‌బుక్ / జమాబందీ, ఆధార్ లింక్డ్ బ్యాంక్ ఖాతా, మొబైల్ నంబర్.",
                benefits: "సంవత్సరానికి ₹6,000 ఆర్థిక సాయం, 4 నెలలకు ఒకసారి ₹2,000 చొప్పున 3 విడతల్లో నేరుగా బ్యాంక్ ఖాతాలో జమ చేయబడుతుంది.",
                applicationProcedure: "pmkisan.gov.in వెబ్‌సైట్‌లో 'New Farmer Registration' ద్వారా లేదా సమీప CSC కేంద్రం ద్వారా దరఖాస్తు చేసుకోవచ్చు.",
                timeline: "సంవత్సరం పొడవునా దరఖాస్తు చేసుకోవచ్చు. ప్రతి విడతకు ముందు e-KYC పూర్తి చేయాలి.",
                officialSource: "https://pmkisan.gov.in (వ్యవసాయ మంత్రిత్వ శాఖ)",
                helpline: "టోల్ ఫ్రీ: 155261 / 1800-115-526 | హెల్ప్‌లైన్: 011-24300606"
            }
        },
        followUps: {
            "en-IN": {
                apply: "To apply for PM-KISAN in Punjab:\n1. Visit pmkisan.gov.in\n2. Click on 'New Farmer Registration'\n3. Enter your Aadhaar number and select state as 'Punjab'\n4. Fill in your land record details (Khata / Khasra number from Jamabandi)\n5. Submit and keep the registration number for tracking.",
                documents: "Required documents for PM-KISAN:\n• Aadhaar Card of the applicant\n• Recent Punjab Jamabandi / Fard (Land Record Copy)\n• Bank Passbook (linked with Aadhaar & NPCI direct benefit transfer)\n• Active Mobile Number linked to Aadhaar for OTP verification.",
                helpline: "PM-KISAN Helplines:\n• Toll-Free: 155261 or 1800-115-526\n• National PM-KISAN Helpline: 011-24300606 / 011-23381092\n• Punjab Agriculture Call Centre: 1800-180-1551",
                timeline: "PM-KISAN registration has no closing deadline—it is an ongoing year-round scheme. However, complete your e-KYC immediately via OTP on the portal or biometric at any CSC to receive upcoming installments without delay.",
                eligibility: "Eligibility criteria:\n• Must be a resident farmer of Punjab owning agricultural land.\n• Land records must be updated in your name.\n• Family definition includes husband, wife, and minor children.\n• Exclusions: Institutional landholders, former/present ministers, government employees, doctors, engineers, chartered accountants, and income-tax payees."
            },
            "pa-IN": {
                apply: "ਪੀ ਐਮ ਕਿਸਾਨ ਲਈ ਅਪਲਾਈ ਕਰਨ ਦਾ ਤਰੀਕਾ:\n1. pmkisan.gov.in 'ਤੇ ਜਾਓ\n2. 'New Farmer Registration' 'ਤੇ ਕਲਿੱਕ ਕਰੋ\n3. ਆਪਣਾ ਆਧਾਰ ਨੰਬਰ ਅਤੇ ਰਾਜ 'ਪੰਜਾਬ' ਚੁਣੋ\n4. ਆਪਣੀ ਜ਼ਮੀਨ ਦੀ ਜਮ੍ਹਾਂਬੰਦੀ / ਖਸਰਾ ਨੰਬਰ ਭਰੋ\n5. ਫਾਰਮ ਸਬਮਿਟ ਕਰਕੇ ਰਸੀਦ ਆਪਣੇ ਕੋਲ ਰੱਖੋ।",
                documents: "ਜ਼ਰੂਰੀ ਦਸਤਾਵੇਜ਼:\n• ਆਧਾਰ ਕਾਰਡ\n• ਪੰਜਾਬ ਜ਼ਮੀਨ ਦੀ ਤਾਜ਼ਾ ਜਮ੍ਹਾਂਬੰਦੀ / ਫ਼ਰਦ\n• ਆਧਾਰ ਨਾਲ ਲਿੰਕ ਬੈਂਕ ਖਾਤਾ\n• ਓਟੀਪੀ ਲਈ ਚਾਲੂ ਮੋਬਾਈਲ ਨੰਬਰ।",
                helpline: "ਹੈਲਪਲਾਈਨ ਨੰਬਰ:\n• ਟੋਲ-ਫ੍ਰੀ: 155261 ਜਾਂ 1800-115-526\n• ਸਿੱਧਾ ਨੰਬਰ: 011-24300606\n• ਪੰਜਾਬ ਕਿਸਾਨ ਕਾਲ ਸੈਂਟਰ: 1800-180-1551",
                timeline: "ਪੀ ਐਮ ਕਿਸਾਨ ਦੀ ਕੋਈ ਆਖਰੀ ਮਿਤੀ ਨਹੀਂ ਹੈ, ਇਹ ਸਾਰਾ ਸਾਲ ਚੱਲਦੀ ਹੈ। ਅਗਲੀ ਕਿਸ਼ਤ ਲੈਣ ਲਈ ਪੋਰਟਲ 'ਤੇ ਆਪਣੀ ਈ-ਕੇਵਾਈਸੀ ਤੁਰੰਤ ਮੁਕੰਮਲ ਕਰੋ।",
                eligibility: "ਪਾਤਰਤਾ:\n• ਪੰਜਾਬ ਵਿੱਚ ਆਪਣੇ ਨਾਮ ਖੇਤੀਬਾੜੀ ਜ਼ਮੀਨ ਹੋਣੀ ਚਾਹੀਦੀ ਹੈ।\n• ਸਰਕਾਰੀ ਨੌਕਰੀ ਵਾਲੇ ਅਤੇ ਇਨਕਮ ਟੈਕਸ ਦੇਣ ਵਾਲੇ ਕਿਸਾਨ ਇਸ ਦੇ ਯੋਗ ਨਹੀਂ ਹਨ।"
            },
            "hi-IN": {
                apply: "पीएम किसान आवेदन प्रक्रिया:\n1. pmkisan.gov.in पर जाएं।\n2. 'New Farmer Registration' चुनें।\n3. आधार नंबर दर्ज कर राज्य 'Punjab' चुनें।\n4. जमीन की जमाबंदी/खसरा विवरण भरें और सबमिट करें।",
                documents: "आवश्यक दस्तावेज:\n• आधार कार्ड\n• जमीन की नकल/जमाबंदी (फर्द)\n• आधार लिंक्ड बैंक पासबुक\n• आधार से जुड़ा मोबाइल नंबर।",
                helpline: "पीएम किसान हेल्पलाइन:\n• टोल फ्री: 155261 / 1800-115-526\n• फोन: 011-24300606\n• किसान कॉल सेंटर: 1800-180-1551",
                timeline: "पीएम किसान में आवेदन की कोई अंतिम तिथि नहीं है। यह योजना पूरे वर्ष खुली है। बस पोर्टल पर e-KYC अवश्य पूरी रखें।",
                eligibility: "पात्रता:\n• पंजाब में कृषि योग्य भूमि का स्वामी होना आवश्यक है।\n• सरकारी सेवा में कार्यरत या आयकर भरने वाले किसान अपात्र हैं।"
            },
            "te-IN": {
                apply: "పీఎం కిసాన్ దరఖాస్తు విధానం:\n1. pmkisan.gov.in లోకి వెళ్లండి.\n2. 'New Farmer Registration' పై క్లిక్ చేయండి.\n3. ఆధార్ నంబర్, రాష్ట్రం 'Punjab' ఎంచుకోండి.\n4. భూమి వివరాలు నమోదు చేసి సమర్పించండి.",
                documents: "అవసరమైన పత్రాలు: ఆధార్ కార్డు, జమాబందీ భూమి పత్రాలు, బ్యాంక్ పాస్‌బుక్, ఆధార్ లింక్డ్ మొబైల్ నంబర్.",
                helpline: "హెల్ప్‌లైన్ నంబర్లు: 155261 / 1800-115-526 | కిసాన్ కాల్ సెంటర్: 1800-180-1551",
                timeline: "ఈ పథకానికి ఎప్పుడైనా దరఖాస్తు చేసుకోవచ్చు. ప్రతి విడత సాయం అందడానికి e-KYC పూర్తి చేసుకోవాలి.",
                eligibility: "అర్హత: పంజాబ్‌లో సాగు భూమి ఉన్న రైతు కుటుంబాలందరూ అర్హులు. ఆదాయపు పన్ను చెల్లించేవారు అనర్హులు."
            }
        }
    },
    {
        id: "punjab_crm",
        index: 2,
        letter: "B",
        aliases: ["crop residue", "crm", "happy seeder", "super seeder", "stubble", "parali", "scheme 2", "scheme b", "2", "b", "ਸੁਪਰ ਸੀਡਰ", "ਪਰਾਲੀ", "सीआरएम", "सुपर सीडर", "పంట వ్యర్థాలు"],
        names: {
            "en-IN": "Punjab Crop Residue Management Scheme (CRM - Machinery Subsidy)",
            "pa-IN": "ਪੰਜਾਬ ਫ਼ਸਲੀ ਰਹਿੰਦ-ਖੂੰਹਦ ਪ੍ਰਬੰਧਨ ਸਕੀਮ (CRM - ਮਸ਼ੀਨਰੀ ਸਬਸਿਡੀ)",
            "hi-IN": "पंजाब फसल अवशेष प्रबंधन योजना (CRM - मशीनरी सब्सिडी)",
            "te-IN": "పంజాబ్ పంట వ్యర్థాల నిర్వహణ పథకం (CRM సబ్సిడీ)"
        },
        bullets: {
            "en-IN": {
                eligibility: "Individual farmers possessing agricultural land in Punjab, registered Farmer Producer Organizations (FPOs), Cooperative Societies, and Gram Panchayats. Priority is given to small and marginal farmers.",
                documents: "Aadhaar Card, Punjab Land Record (Jamabandi / Fard), Bank account details, Tractor Registration Certificate (RC) in farmer's name, Self-undertaking not to burn crop residue.",
                benefits: "50% direct subsidy for individual farmers and 80% subsidy for Custom Hiring Centres (CHCs), Panchayats, and FPOs on equipment such as Super Seeder, Happy Seeder, Smart Seeder, Paddy Straw Chopper, Zero Till Drill, and Balers.",
                applicationProcedure: "Apply online at the official Punjab Agriculture Machinery portal (agrimachinerypb.com). Select the desired machinery, upload required documents, and print the application receipt for verification by the Agriculture Development Officer (ADO).",
                timeline: "Annual applications typically open between July and September prior to the Kharif paddy harvesting season.",
                officialSource: "https://agrimachinerypb.com / https://agripb.gov.in (Department of Agriculture & Farmers Welfare, Punjab)",
                helpline: "Punjab Agriculture Department: 0172-2970605 / 0172-2970606 | Kisan Call Centre: 1800-180-1551"
            },
            "pa-IN": {
                eligibility: "ਪੰਜਾਬ ਦੇ ਜ਼ਮੀਨ ਮਾਲਕ ਕਿਸਾਨ, ਕਿਸਾਨ ਉਤਪਾਦਕ ਸੰਗਠਨ (FPO), ਸਹਿਕਾਰੀ ਸਭਾਵਾਂ ਅਤੇ ਗ੍ਰਾਮ ਪੰਚਾਇਤਾਂ। ਛੋਟੇ ਅਤੇ ਸੀਮਾਂਤ ਕਿਸਾਨਾਂ ਨੂੰ ਪਹਿਲ ਦਿੱਤੀ ਜਾਂਦੀ ਹੈ।",
                documents: "ਆਧਾਰ ਕਾਰਡ, ਪੰਜਾਬ ਜ਼ਮੀਨ ਦੀ ਜਮ੍ਹਾਂਬੰਦੀ/ਫ਼ਰਦ, ਬੈਂਕ ਖਾਤਾ, ਕਿਸਾਨ ਦੇ ਨਾਮ ਟਰੈਕਟਰ ਦੀ ਆਰ.ਸੀ. (RC), ਪਰਾਲੀ ਨਾ ਸਾੜਨ ਦਾ ਸਵੈ-ਘੋਸ਼ਣਾ ਪੱਤਰ।",
                benefits: "ਵਿਅਕਤੀਗਤ ਕਿਸਾਨਾਂ ਲਈ 50% ਸਬਸਿਡੀ ਅਤੇ ਕਸਟਮ ਹਾਇਰਿੰਗ ਸੈਂਟਰਾਂ (CHCs)/ਸਮੂਹਾਂ/ਪੰਚਾਇਤਾਂ ਲਈ 80% ਸਬਸਿਡੀ ਸੁਪਰ ਸੀਡਰ, ਹੈਪੀ ਸੀਡਰ, ਸਮਾਰਟ ਸੀਡਰ, ਸਟ੍ਰਾਅ ਚੌਪਰ ਅਤੇ ਬੇਲਰਾਂ 'ਤੇ।",
                applicationProcedure: "ਵਿਭਾਗ ਦੇ ਪੋਰਟਲ agrimachinerypb.com 'ਤੇ ਆਨਲਾਈਨ ਅਪਲਾਈ ਕਰੋ, ਮਸ਼ੀਨ ਚੁਣੋ, ਦਸਤਾਵੇਜ਼ ਅਪਲੋਡ ਕਰੋ ਅਤੇ ਬਲਾਕ ਖੇਤੀਬਾੜੀ ਅਫ਼ਸਰ (ADO) ਤੋਂ ਤਸਦੀਕ ਕਰਵਾਓ।",
                timeline: "ਦਰਖਾਸਤਾਂ ਆਮ ਤੌਰ 'ਤੇ ਸਾਉਣੀ ਦੀ ਫ਼ਸਲ ਕਟਾਈ ਤੋਂ ਪਹਿਲਾਂ ਜੁਲਾਈ ਤੋਂ ਸਤੰਬਰ ਦਰਮਿਆਨ ਮੰਗੀਆਂ ਜਾਂਦੀਆਂ ਹਨ।",
                officialSource: "https://agrimachinerypb.com (ਖੇਤੀਬਾੜੀ ਅਤੇ ਕਿਸਾਨ ਭਲਾਈ ਵਿਭਾਗ, ਪੰਜਾਬ)",
                helpline: "ਪੰਜਾਬ ਖੇਤੀਬਾੜੀ ਵਿਭਾਗ: 0172-2970605 | ਕਿਸਾਨ ਕਾਲ ਸੈਂਟਰ: 1800-180-1551"
            },
            "hi-IN": {
                eligibility: "पंजाब के व्यक्तिगत किसान, किसान उत्पादक संगठन (FPO), सहकारी समितियां और ग्राम पंचायतें। छोटे एवं सीमांत किसानों को वरीयता।",
                documents: "आधार कार्ड, पंजाब भूमि जमाबंदी, बैंक खाता, किसान के नाम ट्रैक्टर की आरसी, पराली न जलाने का शपथ पत्र।",
                benefits: "व्यक्तिगत किसानों को 50% तथा कस्टम हायरिंग सेंटरों (CHC)/पंचायतों को 80% तक सब्सिडी सुपर सीडर, हैप्पी सीडर, स्ट्रा चॉपर और बेलर मशीनों पर।",
                applicationProcedure: "पोर्टल agrimachinerypb.com पर ऑनलाइन आवेदन करें, मशीन चुनें, दस्तावेज अपलोड करें और एडीओ से सत्यापन कराएं।",
                timeline: "आवेदन आमतौर पर धान कटाई से पहले जुलाई से सितंबर के दौरान खोले जाते हैं।",
                officialSource: "https://agrimachinerypb.com (कृषि एवं किसान कल्याण विभाग, पंजाब)",
                helpline: "कृषि विभाग पंजाब: 0172-2970605 | किसान कॉल सेंटर: 1800-180-1551"
            },
            "te-IN": {
                eligibility: "పంజాబ్‌లో సాగు భూమి ఉన్న రైతులు, రైతు ఉత్పత్తిదారుల సంఘాలు (FPOs), గ్రామ పంచాయతీలు అర్హులు. చిన్న, సన్నకారు రైతులకు ప్రాధాన్యత.",
                documents: "ఆధార్ కార్డు, భూమి జమాబందీ రికార్డు, బ్యాంక్ వివరాలు, ట్రాక్టర్ ఆర్సీ (RC), పంట వ్యర్థాలు తగులబెట్టబోమని స్వీయ ధృవీకరణ.",
                benefits: "వ్యక్తిగత రైతులకు 50% సబ్సిడీ, కస్టమ్ హైరింగ్ కేంద్రాలు/సంఘాలకు 80% సబ్సిడీ సూపర్ సీడర్, హ్యాపీ సీడర్, స్ట్రా ఛాపర్ మరియు బేలర్లపై లభిస్తుంది.",
                applicationProcedure: "agrimachinerypb.com పోర్టల్ ద్వారా ఆన్‌లైన్‌లో దరఖాస్తు చేసుకోవాలి మరియు వ్యవసాయ విస్తరణ అధికారి ద్వారా ధృవీకరించాలి.",
                timeline: "వరి కోతకు ముందు జూలై నుండి సెప్టెంబర్ మధ్య దరఖాస్తులు స్వీకరిస్తారు.",
                officialSource: "https://agrimachinerypb.com (పంజాబ్ వ్యవసాయ శాఖ)",
                helpline: "పంజాబ్ వ్యవసాయ విభాగం: 0172-2970605 | కిసాన్ కాల్ సెంటర్: 1800-180-1551"
            }
        },
        followUps: {
            "en-IN": {
                apply: "To apply for Punjab Crop Residue Management (CRM) Machinery Subsidy:\n1. Open agrimachinerypb.com\n2. Click on 'Apply for CRM Machinery Subsidy'\n3. Enter your Aadhaar, bank details, and Tractor RC details\n4. Select your preferred machine (e.g. Super Seeder / Happy Seeder)\n5. Submit online and deliver a hardcopy of the application to your Block Agriculture Development Officer (ADO). Once sanctioned, purchase from an empanelled vendor.",
                documents: "Documents Checklist for CRM Subsidy:\n• Aadhaar Card of farmer\n• Jamabandi / Fard showing Punjab agricultural land\n• Valid Tractor Registration Certificate (RC) in farmer's name\n• Bank passbook / cancelled cheque for DBT subsidy\n• Signed affidavit promising not to burn paddy straw.",
                helpline: "CRM Scheme Helplines:\n• Punjab Agriculture Head Office (Chandigarh): 0172-2970605, 0172-2970606\n• National Kisan Call Centre: 1800-180-1551 (Toll-Free, 6 AM to 10 PM)\n• Local District Agriculture Officer: Contact at your District Kheti Bhawan.",
                timeline: "The CRM application window opens annually before the paddy harvest season, usually from mid-July through August/September. Sanction letters are distributed by early October before harvest begins.",
                eligibility: "Eligibility criteria:\n• Must be a farmer with agricultural land in Punjab.\n• Must possess an operational tractor with valid RC.\n• Custom Hiring Centres (CHCs) and Panchayats can apply for up to 80% subsidy.\n• Individual farmers receive 50% subsidy on approved machinery models."
            },
            "pa-IN": {
                apply: "ਫ਼ਸਲੀ ਰਹਿੰਦ-ਖੂੰਹਦ ਪ੍ਰਬੰਧਨ (CRM) ਲਈ ਅਪਲਾਈ ਕਿਵੇਂ ਕਰੀਏ:\n1. agrimachinerypb.com 'ਤੇ ਜਾਓ\n2. ਆਪਣਾ ਆਧਾਰ, ਬੈਂਕ ਖਾਤਾ ਅਤੇ ਟਰੈਕਟਰ ਦੀ ਆਰਸੀ ਨੰਬਰ ਦਰਜ ਕਰੋ\n3. ਆਪਣੀ ਲੋੜੀਂਦੀ ਮਸ਼ੀਨ (ਜਿਵੇਂ ਸੁਪਰ ਸੀਡਰ ਜਾਂ ਹੈਪੀ ਸੀਡਰ) ਚੁਣੋ\n4. ਆਨਲਾਈਨ ਫਾਰਮ ਜਮ੍ਹਾਂ ਕਰਕੇ ਰਸੀਦ ਬਲਾਕ ਖੇਤੀਬਾੜੀ ਅਫ਼ਸਰ ਕੋਲ ਜਮ੍ਹਾਂ ਕਰਵਾਓ। ਮਨਜ਼ੂਰੀ ਮਿਲਣ 'ਤੇ ਮਨਜ਼ੂਰਸ਼ੁਦਾ ਡੀਲਰ ਤੋਂ ਮਸ਼ੀਨ ਖਰੀਦੋ।",
                documents: "ਜ਼ਰੂਰੀ ਦਸਤਾਵੇਜ਼:\n• ਆਧਾਰ ਕਾਰਡ\n• ਜ਼ਮੀਨ ਦੀ ਤਾਜ਼ਾ ਜਮ੍ਹਾਂਬੰਦੀ / ਫ਼ਰਦ\n• ਟਰੈਕਟਰ ਦੀ ਵੈਧ ਆਰ.ਸੀ. (ਕਿਸਾਨ ਦੇ ਨਾਮ 'ਤੇ)\n• ਬੈਂਕ ਪਾਸਬੁੱਕ ਦੀ ਕਾਪੀ\n• ਪਰਾਲੀ ਨਾ ਸਾੜਨ ਦਾ ਹਲਫ਼ਨਾਮਾ।",
                helpline: "ਹੈਲਪਲਾਈਨ ਨੰਬਰ:\n• ਪੰਜਾਬ ਖੇਤੀਬਾੜੀ ਵਿਭਾਗ: 0172-2970605 / 0172-2970606\n• ਕਿਸਾਨ ਕਾਲ ਸੈਂਟਰ: 1800-180-1551\n• ਆਪਣੇ ਜ਼ਿਲ੍ਹੇ ਦੇ ਮੁੱਖ ਖੇਤੀਬਾੜੀ ਅਫ਼ਸਰ ਨਾਲ ਖੇਤੀ ਭਵਨ ਵਿਖੇ ਸੰਪਰਕ ਕਰੋ।",
                timeline: "ਹਰ ਸਾਲ ਸਾਉਣੀ ਦੀ ਕਟਾਈ ਤੋਂ ਪਹਿਲਾਂ ਜੁਲਾਈ ਤੋਂ ਸਤੰਬਰ ਦਰਮਿਆਨ ਪੋਰਟਲ ਖੋਲ੍ਹਿਆ ਜਾਂਦਾ ਹੈ। ਅਕਤੂਬਰ ਵਿੱਚ ਮਸ਼ੀਨਾਂ ਦੀ ਵੰਡ ਸ਼ੁਰੂ ਹੁੰਦੀ ਹੈ।",
                eligibility: "ਪਾਤਰਤਾ:\n• ਪੰਜਾਬ ਵਿੱਚ ਖੇਤੀਬਾੜੀ ਜ਼ਮੀਨ ਹੋਣੀ ਚਾਹੀਦੀ ਹੈ।\n• ਕਿਸਾਨ ਦੇ ਨਾਮ ਟਰੈਕਟਰ ਹੋਣਾ ਜ਼ਰੂਰੀ ਹੈ।\n• ਨਿੱਜੀ ਕਿਸਾਨ ਨੂੰ 50% ਅਤੇ ਸਮੂਹਾਂ/ਪੰਚਾਇਤਾਂ ਨੂੰ 80% ਸਬਸਿਡੀ ਮਿਲਦੀ ਹੈ।"
            },
            "hi-IN": {
                apply: "आवेदन प्रक्रिया:\n1. agrimachinerypb.com पर जाएं।\n2. आधार, बैंक और ट्रैक्टर आरसी दर्ज करें।\n3. सुपर सीडर या हैप्पी सीडर चुनें और फॉर्म सबमिट करें।\n4. स्वीकृति मिलने पर अधिकृत निर्माता से मशीन खरीदें।",
                documents: "दस्तावेज:\n• आधार कार्ड\n• जमाबंदी/फर्द\n• ट्रैक्टर आरसी\n• बैंक पासबुक\n• पराली न जलाने का शपथ पत्र।",
                helpline: "हेल्पलाइन:\n• पंजाब कृषि विभाग: 0172-2970605\n• किसान कॉल सेंटर: 1800-180-1551",
                timeline: "यह योजना जुलाई से सितंबर के दौरान सक्रिय होती है ताकि धान की कटाई से पहले मशीनें उपलब्ध हो सकें।",
                eligibility: "पात्रता: पंजाब के भूमिधारक किसान जिनके पास ट्रैक्टर आरसी हो। व्यक्तिगत किसान को 50% और समूह को 80% सब्सिडी।"
            },
            "te-IN": {
                apply: "దరఖాస్తు విధానం: agrimachinerypb.com లో వివరాలు నమోదు చేసి, సూపర్ సీడర్ ఎంపిక చేసుకుని దరఖాస్తు చేసుకోవాలి.",
                documents: "పత్రాలు: ఆధార్, జమాబందీ పత్రాలు, ట్రాక్టర్ ఆర్సీ, బ్యాంక్ పాస్‌బుక్, స్వీయ ధృవీకరణ.",
                helpline: "ఫోన్: 0172-2970605 / టోల్ ఫ్రీ: 1800-180-1551",
                timeline: "ప్రతి సంవత్సరం జూలై-సెప్టెంబర్ మధ్య దరఖాస్తులు తెరుస్తారు.",
                eligibility: "పంజాబ్ రైతులు, ట్రాక్టర్ కలిగి ఉన్నవారు అర్హులు. 50% నుండి 80% వరకు సబ్సిడీ."
            }
        }
    },
    {
        id: "pmksy_punjab",
        index: 3,
        letter: "C",
        aliases: ["drip", "sprinkler", "micro irrigation", "per drop more crop", "pmksy", "sinchayee", "irrigation subsidy", "scheme 3", "scheme c", "3", "c", "ਤੁਪਕਾ ਸਿੰਚਾਈ", "ਸਿੰਜਾਈ", "ड्रिप", "फुहारा", "सूक्ष्म सिंचाई"],
        names: {
            "en-IN": "Per Drop More Crop - PMKSY (Micro-Irrigation & Drip Subsidy)",
            "pa-IN": "ਪ੍ਰਤੀ ਬੂੰਦ ਵੱਧ ਫ਼ਸਲ - ਪ੍ਰਧਾਨ ਮੰਤਰੀ ਕ੍ਰਿਸ਼ੀ ਸਿੰਚਾਈ ਯੋਜਨਾ (ਤੁਪਕਾ ਤੇ ਫੁਹਾਰਾ ਸਿੰਚਾਈ)",
            "hi-IN": "प्रति बूंद अधिक फसल - पीएम कृषि सिंचाई योजना (ड्रिप एवं स्प्रिंकलर)",
            "te-IN": "ప్రధాన మంత్రి కృషి సించాయి పథకం (సూక్ష్మ సేద్యం - డ్రిప్ & స్ప్రింక్లర్)"
        },
        bullets: {
            "en-IN": {
                eligibility: "Farmers of all categories owning cultivable land in Punjab with an assured irrigation water source (tubewell or canal outlet). Special priority given to water-stressed/dark zone blocks and small/marginal/women farmers.",
                documents: "Aadhaar Card, Punjab Land Revenue Record (Jamabandi/Khasra), Tubewell electricity connection bill or water source undertaking, Bank account passbook, Soil/water test report (optional).",
                benefits: "Up to 80% total financial subsidy (55% Central share + up to 25% Punjab State top-up for small/marginal/female farmers; 45-55% for other farmers) on Drip and Sprinkler irrigation installations to conserve underground water.",
                applicationProcedure: "Apply online through the Punjab Soil and Water Conservation portal (dswcpunjab.gov.in) or visit your District Soil Conservation Officer (DSCO) or Divisional Soil Conservation Office.",
                timeline: "Open for application year-round, subject to annual district target allocations and budget availability.",
                officialSource: "https://dswcpunjab.gov.in (Department of Soil & Water Conservation, Punjab)",
                helpline: "Soil & Water Conservation Punjab: 0172-2704548 | Kisan Call Centre: 1800-180-1551"
            },
            "pa-IN": {
                eligibility: "ਪੰਜਾਬ ਦੇ ਸਾਰੇ ਕਿਸਾਨ ਜਿਨ੍ਹਾਂ ਕੋਲ ਪਾਣੀ ਦਾ ਪੱਕਾ ਸਰੋਤ (ਟਿਊਬਵੈੱਲ ਜਾਂ ਨਹਿਰੀ ਪਾਣੀ) ਹੈ। ਧਰਤੀ ਹੇਠਲੇ ਪਾਣੀ ਦੀ ਘਾਟ ਵਾਲੇ ਡਾਰਕ ਜ਼ੋਨਾਂ ਅਤੇ ਛੋਟੇ/ਸੀਮਾਂਤ/ਮਹਿਲਾ ਕਿਸਾਨਾਂ ਨੂੰ ਪਹਿਲ।",
                documents: "ਆਧਾਰ ਕਾਰਡ, ਜ਼ਮੀਨ ਦੀ ਜਮ੍ਹਾਂਬੰਦੀ/ਫ਼ਰਦ, ਟਿਊਬਵੈੱਲ ਬਿਜਲੀ ਬਿੱਲ ਜਾਂ ਪਾਣੀ ਦੇ ਸਰੋਤ ਦਾ ਸਬੂਤ, ਬੈਂਕ ਪਾਸਬੁੱਕ।",
                benefits: "ਤੁਪਕਾ (Drip) ਅਤੇ ਫੁਹਾਰਾ (Sprinkler) ਸਿੰਚਾਈ ਪ੍ਰਣਾਲੀ 'ਤੇ 80% ਤੱਕ ਸਬਸਿਡੀ (ਛੋਟੇ, ਸੀਮਾਂਤ ਅਤੇ ਮਹਿਲਾ ਕਿਸਾਨਾਂ ਲਈ ਵਿਸ਼ੇਸ਼ ਸਟੇਟ ਟਾਪ-ਅੱਪ)।",
                applicationProcedure: "ਭੂਮੀ ਅਤੇ ਜਲ ਸੰਭਾਲ ਵਿਭਾਗ ਦੇ ਪੋਰਟਲ dswcpunjab.gov.in 'ਤੇ ਆਨਲਾਈਨ ਅਪਲਾਈ ਕਰੋ ਜਾਂ ਜ਼ਿਲ੍ਹਾ ਭੂਮੀ ਸੰਭਾਲ ਅਫ਼ਸਰ (DSCO) ਦੇ ਦਫ਼ਤਰ ਜਾਓ।",
                timeline: "ਸਾਰਾ ਸਾਲ ਖੁੱਲ੍ਹੀ ਰਹਿੰਦੀ ਹੈ, ਜ਼ਿਲ੍ਹੇ ਦੇ ਟੀਚਿਆਂ ਅਨੁਸਾਰ ਪਹਿਲ ਦੇ ਆਧਾਰ 'ਤੇ ਮਨਜ਼ੂਰੀ ਦਿੱਤੀ ਜਾਂਦੀ ਹੈ।",
                officialSource: "https://dswcpunjab.gov.in (ਭੂਮੀ ਅਤੇ ਜਲ ਸੰਭਾਲ ਵਿਭਾਗ, ਪੰਜਾਬ)",
                helpline: "ਭੂਮੀ ਅਤੇ ਜਲ ਸੰਭਾਲ ਵਿਭਾਗ: 0172-2704548 | ਕਿਸਾਨ ਕਾਲ ਸੈਂਟਰ: 1800-180-1551"
            },
            "hi-IN": {
                eligibility: "पंजाब के वे सभी किसान जिनके पास सुनिश्चित जल स्रोत (नलकूप/नहरी) है। डार्क ज़ोन और छोटे/सीमांत किसानों को प्राथमिकता।",
                documents: "आधार कार्ड, जमाबंदी नकल, बिजली बिल या जल स्रोत प्रमाण, बैंक पासबुक।",
                benefits: "ड्रिप और स्प्रिंकलर सिंचाई सिस्टम लगाने पर 80% तक सब्सिडी ताकि भूजल बचाया जा सके।",
                applicationProcedure: "dswcpunjab.gov.in पर ऑनलाइन आवेदन करें या जिला भूमि संरक्षण अधिकारी (DSCO) से संपर्क करें।",
                timeline: "पूरे वर्ष उपलब्ध, जिलेवार लक्ष्यों के अनुसार आबंटन।",
                officialSource: "https://dswcpunjab.gov.in (भूमि एवं जल संरक्षण विभाग, पंजाब)",
                helpline: "फोन: 0172-2704548 | किसान हेल्पलाइन: 1800-180-1551"
            },
            "te-IN": {
                eligibility: "పంజాబ్‌లో నీటి వనరు ఉన్న రైతులందరూ అర్హులు. చిన్న, సన్నకారు మరియు మహిళా రైతులకు ప్రాధాన్యత.",
                documents: "ఆధార్ కార్డు, జమాబందీ, కరెంట్ బిల్లు/బోరు వివరాలు, బ్యాంక్ పాస్‌బుక్.",
                benefits: "డ్రిప్ మరియు స్ప్రింక్లర్ పద్ధతులపై 80% వరకు సబ్సిడీ లభిస్తుంది.",
                applicationProcedure: "dswcpunjab.gov.in ద్వారా లేదా జిల్లా భూగర్భ జల సంరక్షణ అధికారి వద్ద దరఖాస్తు చేసుకోవాలి.",
                timeline: "సంవత్సరం పొడవునా తెరిచి ఉంటుంది.",
                officialSource: "https://dswcpunjab.gov.in (పంజాబ్ భూ మరియు నీటి సంరక్షణ విభాగం)",
                helpline: "0172-2704548 / 1800-180-1551"
            }
        },
        followUps: {
            "en-IN": {
                apply: "How to apply for Micro-Irrigation (Drip/Sprinkler) in Punjab:\n1. Visit dswcpunjab.gov.in\n2. Fill out the Farmer Registration form for Micro Irrigation\n3. Attach land record, water source certificate, and Aadhaar\n4. Department engineers will conduct a field survey and prepare system design\n5. Choose an approved company vendor to install the system with direct subsidy.",
                documents: "Required Documents:\n• Aadhaar Card\n• Latest Jamabandi / Fard\n• Tubewell electric connection proof or water availability certificate\n• Bank Passbook (IFSC & Account number)\n• Passport size photograph.",
                helpline: "Helpline Contact:\n• Department of Soil & Water Conservation Punjab: 0172-2704548\n• Kisan Call Centre: 1800-180-1551 (All days 6 AM - 10 PM)",
                timeline: "Applications are processed throughout the year. Priority is given before sowing seasons (April-June for Kharif, October-November for Rabi).",
                eligibility: "Small, marginal, and female farmers receive up to 80% subsidy. Large farmers receive up to 50% subsidy. A functional water source (tubewell/canal) is mandatory."
            },
            "pa-IN": {
                apply: "ਤੁਪਕਾ ਸਿੰਚਾਈ ਲਈ ਅਪਲਾਈ ਕਿਵੇਂ ਕਰੀਏ:\n1. dswcpunjab.gov.in 'ਤੇ ਜਾਓ\n2. ਫਾਰਮ ਭਰੋ ਅਤੇ ਜ਼ਮੀਨ ਦੀ ਫ਼ਰਦ ਅਤੇ ਟਿਊਬਵੈੱਲ ਬਿੱਲ ਅਪਲੋਡ ਕਰੋ\n3. ਵਿਭਾਗੀ ਟੀਮ ਖੇਤ ਦਾ ਦੌਰਾ ਕਰਕੇ ਸਿਸਟਮ ਦਾ ਨਕਸ਼ਾ ਬਣਾਵੇਗੀ\n4. ਮਨਜ਼ੂਰ ਕੰਪਨੀ ਰਾਹੀਂ ਸਿਸਟਮ ਲਗਵਾਓ ਅਤੇ ਸਬਸਿਡੀ ਸਿੱਧੀ ਖਾਤੇ ਵਿੱਚ ਆਵੇਗੀ।",
                documents: "ਜ਼ਰੂਰੀ ਦਸਤਾਵੇਜ਼: ਆਧਾਰ ਕਾਰਡ, ਜਮ੍ਹਾਂਬੰਦੀ/ਫ਼ਰਦ, ਟਿਊਬਵੈੱਲ ਬਿਜਲੀ ਬਿੱਲ, ਬੈਂਕ ਪਾਸਬੁੱਕ, ਪਾਸਪੋਰਟ ਫੋਟੋ।",
                helpline: "ਫ਼ੋਨ ਨੰਬਰ: 0172-2704548 | ਕਿਸਾਨ ਕਾਲ ਸੈਂਟਰ: 1800-180-1551",
                timeline: "ਇਹ ਸਕੀਮ ਸਾਰਾ ਸਾਲ ਖੁੱਲ੍ਹੀ ਰਹਿੰਦੀ ਹੈ। ਹਾੜ੍ਹੀ ਅਤੇ ਸਾਉਣੀ ਤੋਂ ਪਹਿਲਾਂ ਲਗਵਾਉਣਾ ਸਭ ਤੋਂ ਵਧੀਆ ਹੈ।",
                eligibility: "ਛੋਟੇ, ਸੀਮਾਂਤ ਅਤੇ ਬੀਬੀਆਂ ਕਿਸਾਨਾਂ ਨੂੰ 80% ਤੱਕ ਅਤੇ ਵੱਡੇ ਕਿਸਾਨਾਂ ਨੂੰ 50% ਤੱਕ ਸਬਸਿਡੀ ਮਿਲਦੀ ਹੈ।"
            },
            "hi-IN": {
                apply: "आवेदन प्रक्रिया: dswcpunjab.gov.in पर जाएं, फॉर्म भरें और जमाबंदी व बिजली बिल संलग्न करें।",
                documents: "दस्तावेज: आधार, जमाबंदी, बिजली बिल, बैंक पासबुक।",
                helpline: "फोन: 0172-2704548 | 1800-180-1551",
                timeline: "पूरे वर्ष खुली है।",
                eligibility: "छोटे और महिला किसानों को 80% तक, अन्य को 50% तक सब्सिडी।"
            },
            "te-IN": {
                apply: "దరఖాస్తు: dswcpunjab.gov.in పోర్టల్ ద్వారా దరఖాస్తు చేసుకోవాలి.",
                documents: "పత్రాలు: ఆధార్, జమాబందీ, కరెంట్ బిల్లు, బ్యాంక్ పాస్‌బుక్.",
                helpline: "0172-2704548 / 1800-180-1551",
                timeline: "సంవత్సరం పొడవునా అందుబాటులో ఉంది.",
                eligibility: "నీటి వనరు ఉన్న రైతులకు 80% వరకు సబ్సిడీ."
            }
        }
    },
    {
        id: "seed_subsidy_punjab",
        index: 4,
        letter: "D",
        aliases: ["certified seeds", "seed subsidy", "rkvy", "wheat seed", "paddy seed", "seeds", "scheme 4", "scheme d", "4", "d", "ਬੀਜ ਸਬਸਿਡੀ", "ਕਣਕ ਦਾ ਬੀਜ", "बीज सब्सिडी", "విత్తన సబ్సిడీ"],
        names: {
            "en-IN": "Certified Seed & Input Subsidy Scheme (RKVY / NFSM Punjab)",
            "pa-IN": "ਤਸਦੀਕਸ਼ੁਦਾ ਬੀਜ ਅਤੇ ਖਾਦ ਸਬਸਿਡੀ ਸਕੀਮ (RKVY / NFSM ਪੰਜਾਬ)",
            "hi-IN": "प्रमाणित बीज एवं आदान सब्सिडी योजना (RKVY / NFSM पंजाब)",
            "te-IN": "ధృవీకరించబడిన విత్తన సబ్సిడీ పథకం (RKVY / NFSM)"
        },
        bullets: {
            "en-IN": {
                eligibility: "All registered farmers in Punjab cultivating major seasonal crops (Wheat, Paddy/Basmati, Moong/Pulses, Maize, and Oilseeds). Priority to small farmers owning up to 5 acres.",
                documents: "Aadhaar Card, Punjab Land Record / Jamabandi, Bank Passbook details, Farmer Registration number on agripb.gov.in.",
                benefits: "Direct price subsidy of up to ₹1,000 per quintal (or 50% of seed cost) on high-yielding certified seeds through PUNSEED, PAU Ludhiana, and National Seeds Corporation.",
                applicationProcedure: "Apply online at agripb.gov.in prior to the sowing season. Download the subsidy coupon and redeem it directly at PUNSEED outlets, primary cooperative societies, or authorized seed dealers.",
                timeline: "Seasonal application windows: October-November for Rabi Wheat, and April-June for Kharif Paddy/Maize/Moong.",
                officialSource: "https://agripb.gov.in (Department of Agriculture & Farmers Welfare, Punjab / PUNSEED)",
                helpline: "PUNSEED Helpline: 0172-2225642 | Kisan Call Centre: 1800-180-1551"
            },
            "pa-IN": {
                eligibility: "ਪੰਜਾਬ ਦੇ ਸਾਰੇ ਰਜਿਸਟਰਡ ਕਿਸਾਨ ਜੋ ਕਣਕ, ਬਾਸਮਤੀ, ਮੱਕੀ, ਮੂੰਗੀ ਅਤੇ ਦਾਲਾਂ ਦੀ ਕਾਸ਼ਤ ਕਰਦੇ ਹਨ। 5 ਏਕੜ ਤੱਕ ਦੇ ਛੋਟੇ ਕਿਸਾਨਾਂ ਨੂੰ ਪਹਿਲ।",
                documents: "ਆਧਾਰ ਕਾਰਡ, ਜ਼ਮੀਨ ਦੀ ਜਮ੍ਹਾਂਬੰਦੀ/ਫ਼ਰਦ, ਬੈਂਕ ਖਾਤੇ ਦਾ ਵੇਰਵਾ, agripb.gov.in 'ਤੇ ਕਿਸਾਨ ਆਈਡੀ।",
                benefits: "ਪਨਸੀਡ (PUNSEED) ਅਤੇ ਪੀ.ਏ.ਯੂ. ਲੁਧਿਆਣਾ ਦੇ ਪ੍ਰਮਾਣਿਤ ਬੀਜਾਂ 'ਤੇ ₹1,000 ਪ੍ਰਤੀ ਕੁਇੰਟਲ ਜਾਂ 50% ਤੱਕ ਦੀ ਸਿੱਧੀ ਸਬਸਿਡੀ।",
                applicationProcedure: "ਬਿਜਾਈ ਤੋਂ ਪਹਿਲਾਂ agripb.gov.in 'ਤੇ ਆਨਲਾਈਨ ਅਪਲਾਈ ਕਰੋ, ਸਬਸਿਡੀ ਪਰਚੀ ਡਾਊਨਲੋਡ ਕਰੋ ਅਤੇ ਸਹਿਕਾਰੀ ਸਭਾਵਾਂ ਜਾਂ ਪਨਸੀਡ ਸਟੋਰਾਂ ਤੋਂ ਬੀਜ ਲਵੋ।",
                timeline: "ਹਾੜ੍ਹੀ (ਕਣਕ) ਲਈ ਅਕਤੂਬਰ-ਨਵੰਬਰ ਅਤੇ ਸਾਉਣੀ (ਮੱਕੀ/ਮੂੰਗੀ) ਲਈ ਅਪ੍ਰੈਲ-ਜੂਨ।",
                officialSource: "https://agripb.gov.in (ਪੰਜਾਬ ਰਾਜ ਬੀਜ ਨਿਗਮ - PUNSEED)",
                helpline: "ਪਨਸੀਡ ਮੁੱਖ ਦਫ਼ਤਰ: 0172-2225642 | ਕਿਸਾਨ ਕਾਲ ਸੈਂਟਰ: 1800-180-1551"
            },
            "hi-IN": {
                eligibility: "पंजाब के सभी किसान जो गेहूं, मक्का, दालें उगाते हैं। 5 एकड़ तक के किसानों को प्राथमिकता।",
                documents: "आधार कार्ड, जमीन की जमाबंदी, बैंक पासबुक।",
                benefits: "प्रमाणित बीजों पर ₹1,000 प्रति क्विंटल या 50% तक सीधी छूट।",
                applicationProcedure: "agripb.gov.in पर आवेदन करें और कूपन लेकर सहकारी सभा या पनसीड केंद्र से बीज प्राप्त करें।",
                timeline: "रबी हेतु अक्टूबर-नवंबर, खरीफ हेतु अप्रैल-जून।",
                officialSource: "https://agripb.gov.in (कृषि विभाग पंजाब / PUNSEED)",
                helpline: "फोन: 0172-2225642 | किसान कॉल सेंटर: 1800-180-1551"
            },
            "te-IN": {
                eligibility: "పంజాబ్‌లో పంటలు సాగుచేసే నమోదిత రైతులందరూ అర్హులు. 5 ఎకరాలలోపు రైతులకు ప్రాధాన్యత.",
                documents: "ఆధార్ కార్డు, భూమి పత్రాలు, బ్యాంక్ పాస్‌బుక్.",
                benefits: "విత్తనాలపై క్వింటాలుకు ₹1,000 వరకు లేదా 50% వరకు సబ్సిడీ లభిస్తుంది.",
                applicationProcedure: "agripb.gov.in పోర్టల్ ద్వారా దరఖాస్తు చేసుకుని కూపన్ పొందాలి.",
                timeline: "విత్తే కాలానికి ముందు (అక్టోబర్-నవంబర్ మరియు ఏప్రిల్-జూన్).",
                officialSource: "https://agripb.gov.in (PUNSEED)",
                helpline: "0172-2225642 / 1800-180-1551"
            }
        },
        followUps: {
            "en-IN": {
                apply: "To claim certified seed subsidy in Punjab:\n1. Log on to agripb.gov.in\n2. Select 'Seed Subsidy Registration'\n3. Enter your Farmer ID / Aadhaar and choose your crop variety\n4. Download the generated subsidy token/slip\n5. Present this slip at your nearest PUNSEED store or primary agricultural cooperative society (PACS) to get the discounted rate.",
                documents: "Required Documents: Aadhaar card, Jamabandi/land fard copy, and bank passbook.",
                helpline: "PUNSEED Office: 0172-2225642 | Kisan Call Centre: 1800-180-1551",
                timeline: "Wheat seed subsidy opens every year during October and runs through mid-November.",
                eligibility: "Available for all farmers; preference and higher allocation given to farmers holding up to 5 acres."
            },
            "pa-IN": {
                apply: "ਬੀਜ ਸਬਸਿਡੀ ਲੈਣ ਲਈ: agripb.gov.in 'ਤੇ ਰਜਿਸਟ੍ਰੇਸ਼ਨ ਕਰੋ, ਸਬਸਿਡੀ ਪਰਚੀ ਕਢਵਾਓ ਅਤੇ ਆਪਣੀ ਪਿੰਡ ਦੀ ਸੁਸਾਇਟੀ ਜਾਂ ਪਨਸੀਡ ਸੈਂਟਰ ਤੋਂ ਰਿਆਇਤੀ ਰੇਟ 'ਤੇ ਬੀਜ ਪ੍ਰਾਪਤ ਕਰੋ।",
                documents: "ਦਸਤਾਵੇਜ਼: ਆਧਾਰ ਕਾਰਡ, ਜਮ੍ਹਾਂਬੰਦੀ ਦੀ ਕਾਪੀ, ਬੈਂਕ ਪਾਸਬੁੱਕ।",
                helpline: "ਪਨਸੀਡ: 0172-2225642 | ਕਿਸਾਨ ਕਾਲ ਸੈਂਟਰ: 1800-180-1551",
                timeline: "ਕਣਕ ਦੇ ਬੀਜ ਲਈ ਅਕਤੂਬਰ ਤੋਂ ਨਵੰਬਰ ਵਿਚਕਾਰ ਅਪਲਾਈ ਕਰੋ।",
                eligibility: "ਸਾਰੇ ਕਿਸਾਨਾਂ ਲਈ ਉਪਲਬਧ ਹੈ, 5 ਏਕੜ ਤੱਕ ਦੇ ਕਿਸਾਨਾਂ ਨੂੰ ਪਹਿਲ ਮਿਲਦੀ ਹੈ।"
            },
            "hi-IN": {
                apply: "agripb.gov.in पर पंजीकरण कर टोकन प्राप्त करें और बीज केंद्र से सब्सिडी दर पर बीज खरीदें।",
                documents: "आधार कार्ड, जमाबंदी, बैंक पासबुक।",
                helpline: "0172-2225642 / 1800-180-1551",
                timeline: "रबी सीजन में अक्टूबर-नवंबर।",
                eligibility: "5 एकड़ तक भूमि वाले किसानों को प्राथमिकता।"
            },
            "te-IN": {
                apply: "agripb.gov.in ద్వారా దరఖాస్తు చేసుకోవాలి.",
                documents: "ఆధార్, జమాబందీ, బ్యాంక్ వివరాలు.",
                helpline: "0172-2225642 / 1800-180-1551",
                timeline: "అక్టోబర్-నవంబర్.",
                eligibility: "నమోదిత రైతులందరూ అర్హులు."
            }
        }
    },
    {
        id: "pashu_kcc_punjab",
        index: 5,
        letter: "E",
        aliases: ["dairy", "pashu kcc", "animal husbandry", "cattle", "milch", "dairy loan", "scheme 5", "scheme e", "5", "e", "ਪਸ਼ੂ ਕਿਸਾਨ ਕ੍ਰੈਡਿਟ ਕਾਰਡ", "ਡੇਅਰੀ", "पशु किसान क्रेडिट कार्ड", "డెయిరీ పథకం"],
        names: {
            "en-IN": "Pashu Kisan Credit Card (PKCC) & Dairy Development Scheme",
            "pa-IN": "ਪਸ਼ੂ ਕਿਸਾਨ ਕ੍ਰੈਡਿਟ ਕਾਰਡ (PKCC) ਅਤੇ ਡੇਅਰੀ ਵਿਕਾਸ ਸਕੀਮ",
            "hi-IN": "पशु किसान क्रेडिट कार्ड (PKCC) एवं डेयरी विकास योजना",
            "te-IN": "పశు కిసాన్ క్రెడిట్ కార్డు (PKCC) & డెయిరీ అభివృద్ధి పథకం"
        },
        bullets: {
            "en-IN": {
                eligibility: "Farmers and rural youth in Punjab engaged in dairy farming or rearing cows, buffaloes, sheep, goats, or pigs. Land ownership is not mandatory; tenant farmers and landless livestock keepers are also eligible.",
                documents: "Aadhaar Card, PAN Card or Form 60, Animal Health & Tagging Certificate from Veterinary Officer, Bank Account details, 2 passport photographs.",
                benefits: "Collateral-free working capital loan up to ₹1.6 lakh (and up to ₹3 lakh with collateral) at an effective 4% interest rate (after prompt repayment subvention), plus 25% to 33% capital subsidy for modern dairy sheds and milch animals through Punjab Dairy Development Board.",
                applicationProcedure: "Obtain the PKCC application form from your nearest Veterinary Hospital or Commercial/Cooperative Bank branch. Get the animal health verification done by the Senior Veterinary Officer (SVO) and submit to the bank.",
                timeline: "Open for application continuously throughout the year with no deadline cutoff.",
                officialSource: "https://dairy.punjab.gov.in (Punjab Dairy Development Board & Animal Husbandry Department)",
                helpline: "Punjab Dairy Development Board: 0172-2700228 / 0172-5028448 | Kisan Call Centre: 1800-180-1551"
            },
            "pa-IN": {
                eligibility: "ਪੰਜਾਬ ਦੇ ਕਿਸਾਨ ਅਤੇ ਪੇਂਡੂ ਨੌਜਵਾਨ ਜੋ ਦੁਧਾਰੂ ਪਸ਼ੂ (ਗਾਵਾਂ, ਮੱਝਾਂ) ਜਾਂ ਬੱਕਰੀਆਂ/ਸੂਰ ਪਾਲਦੇ ਹਨ। ਜ਼ਮੀਨ ਹੋਣੀ ਲਾਜ਼ਮੀ ਨਹੀਂ ਹੈ, ਬੇਜ਼ਮੀਨੇ ਪਸ਼ੂ ਪਾਲਕ ਵੀ ਯੋਗ ਹਨ।",
                documents: "ਆਧਾਰ ਕਾਰਡ, ਪੈਨ ਕਾਰਡ, ਸਰਕਾਰੀ ਵੈਟਰਨਰੀ ਅਫ਼ਸਰ ਵੱਲੋਂ ਪਸ਼ੂਆਂ ਦਾ ਟੈਗਿੰਗ ਸਰਟੀਫਿਕੇਟ, ਬੈਂਕ ਖਾਤਾ, ਫੋਟੋਆਂ।",
                benefits: "ਬਿਨਾਂ ਗਾਰੰਟੀ ₹1.6 ਲੱਖ ਤੱਕ (ਅਤੇ ਜ਼ਮਾਨਤ ਨਾਲ ₹3 ਲੱਖ ਤੱਕ) ਦਾ ਕਰਜ਼ਾ ਸਿਰਫ਼ 4% ਸਾਲਾਨਾ ਵਿਆਜ 'ਤੇ। ਨਾਲ ਹੀ ਡੇਅਰੀ ਸ਼ੈੱਡਾਂ ਅਤੇ ਪਸ਼ੂਆਂ 'ਤੇ 25% ਤੋਂ 33% ਸਬਸਿਡੀ।",
                applicationProcedure: "ਨੇੜਲੇ ਪਸ਼ੂ ਹਸਪਤਾਲ ਜਾਂ ਬੈਂਕ ਤੋਂ ਫਾਰਮ ਲਵੋ, ਵੈਟਰਨਰੀ ਡਾਕਟਰ ਤੋਂ ਪਸ਼ੂਆਂ ਦੀ ਤਸਦੀਕ ਕਰਵਾ ਕੇ ਬੈਂਕ ਵਿੱਚ ਜਮ੍ਹਾਂ ਕਰਵਾਓ।",
                timeline: "ਇਹ ਸਕੀਮ ਸਾਰਾ ਸਾਲ ਖੁੱਲ੍ਹੀ ਰਹਿੰਦੀ ਹੈ, ਕਦੇ ਵੀ ਅਪਲਾਈ ਕੀਤਾ ਜਾ ਸਕਦਾ ਹੈ।",
                officialSource: "https://dairy.punjab.gov.in (ਪੰਜਾਬ ਡੇਅਰੀ ਵਿਕਾਸ ਬੋਰਡ)",
                helpline: "ਡੇਅਰੀ ਵਿਕਾਸ ਬੋਰਡ: 0172-2700228 | ਕਿਸਾਨ ਕਾਲ ਸੈਂਟਰ: 1800-180-1551"
            },
            "hi-IN": {
                eligibility: "पंजाब के पशुपालक व किसान जो गाय/भैंस पालते हैं। भूमिहीन पशुपालक भी पात्र हैं।",
                documents: "आधार कार्ड, पैन कार्ड, पशु स्वास्थ्य/टैग प्रमाण पत्र, बैंक खाता, 2 फोटो।",
                benefits: "बिना गारंटी ₹1.6 लाख तक का ऋण मात्र 4% प्रभावी ब्याज दर पर, साथ ही आधुनिक डेयरी शेड पर 25-33% सब्सिडी।",
                applicationProcedure: "नजदीकी पशु चिकित्सालय या बैंक शाखा से फॉर्म भरकर पशु डॉक्टर से सत्यापन करवाएं।",
                timeline: "पूरे वर्ष आवेदन खुला रहता है।",
                officialSource: "https://dairy.punjab.gov.in (पंजाब डेयरी विकास बोर्ड)",
                helpline: "फोन: 0172-2700228 | किसान हेल्पलाइन: 1800-180-1551"
            },
            "te-IN": {
                eligibility: "పాడి గేదెలు, ఆవులు పెంచే పంజాబ్ రైతులు అర్హులు. భూమి లేని పశుపోషకులు కూడా అర్హులే.",
                documents: "ఆధార్ కార్డు, పాన్ కార్డు, పశువుల ట్యాగ్ వివరాలు, బ్యాంక్ ఖాతా.",
                benefits: "₹1.6 లక్షల వరకు ఎలాంటి పూచీకత్తు లేకుండా 4% వడ్డీతో రుణం మరియు షెడ్లపై 25-33% సబ్సిడీ.",
                applicationProcedure: "సమీప పశువైద్యశాల లేదా బ్యాంక్ ద్వారా దరఖాస్తు చేసుకోవాలి.",
                timeline: "సంవత్సరం పొడవునా తెరిచి ఉంటుంది.",
                officialSource: "https://dairy.punjab.gov.in (పంజాబ్ డెయిరీ బోర్డు)",
                helpline: "0172-2700228 / 1800-180-1551"
            }
        },
        followUps: {
            "en-IN": {
                apply: "To apply for Pashu Kisan Credit Card (PKCC):\n1. Visit your local Veterinary Hospital (Civil Animal Hospital) or nearest Commercial/Cooperative Bank\n2. Fill out the simple 2-page PKCC application form\n3. The Veterinary Officer will tag your animals and issue a health verification certificate\n4. Submit the verified form to the bank branch. Loan sanctioned within 14 days without collateral up to ₹1.6 Lakh.",
                documents: "Required Documents:\n• Aadhaar Card & PAN Card\n• Veterinary Tagging Certificate\n• Bank Account details & 2 passport photos\n• Land or cattle shed ownership/lease proof.",
                helpline: "PKCC Helplines:\n• Punjab Dairy Development Board: 0172-2700228\n• Animal Husbandry Directorate Punjab: 0172-2217084\n• Kisan Call Centre: 1800-180-1551",
                timeline: "Continuous open enrollment throughout the year.",
                eligibility: "Any livestock farmer in Punjab. Loan amount: ₹40,783 per cow and ₹60,249 per buffalo for working capital."
            },
            "pa-IN": {
                apply: "ਅਪਲਾਈ ਕਰਨ ਦਾ ਤਰੀਕਾ: ਨੇੜਲੇ ਪਸ਼ੂ ਹਸਪਤਾਲ ਜਾਓ, ਫਾਰਮ ਭਰੋ, ਵੈਟਰਨਰੀ ਡਾਕਟਰ ਤੋਂ ਪਸ਼ੂਆਂ ਦੀ ਟੈਗਿੰਗ ਕਰਵਾਓ ਅਤੇ ਬੈਂਕ ਸ਼ਾਖਾ ਵਿੱਚ ਜਮ੍ਹਾਂ ਕਰੋ।",
                documents: "ਆਧਾਰ, ਪੈਨ, ਪਸ਼ੂ ਟੈਗ ਸਰਟੀਫਿਕੇਟ, ਬੈਂਕ ਖਾਤਾ, ਫੋਟੋਆਂ।",
                helpline: "ਫ਼ੋਨ: 0172-2700228 | 1800-180-1551",
                timeline: "ਸਾਰਾ ਸਾਲ ਖੁੱਲ੍ਹੀ ਹੈ।",
                eligibility: "ਪੰਜਾਬ ਦੇ ਸਾਰੇ ਪਸ਼ੂ ਪਾਲਕ। ਪ੍ਰਤੀ ਗਾਂ ਲਗਭਗ ₹40,000 ਅਤੇ ਪ੍ਰਤੀ ਮੱਝ ₹60,000 ਦੀ ਲਿਮਿਟ ਬਣਦੀ ਹੈ।"
            },
            "hi-IN": {
                apply: "पशु चिकित्सालय या बैंक से फॉर्म लें, पशु टैग करवाएं और बैंक में जमा करें।",
                documents: "आधार, पैन, टैग प्रमाणपत्र, बैंक पासबुक।",
                helpline: "0172-2700228 / 1800-180-1551",
                timeline: "पूरे वर्ष खुला।",
                eligibility: "प्रति गाय ₹40,000 व प्रति भैंस ₹60,000 तक कार्यशील पूंजी ऋण।"
            },
            "te-IN": {
                apply: "పశువైద్యశాల లేదా బ్యాంక్ ద్వారా దరఖాస్తు చేసుకోవాలి.",
                documents: "ఆధార్, పాన్, పశువుల ట్యాగ్ సర్టిఫికేట్, బ్యాంక్ ఖాతా.",
                helpline: "0172-2700228 / 1800-180-1551",
                timeline: "సంవత్సరం పొడవునా ఉంటుంది.",
                eligibility: "పాడి రైతులందరూ అర్హులు."
            }
        }
    },
    {
        id: "crop_diversification_punjab",
        index: 6,
        letter: "F",
        aliases: ["diversification", "alternative crops", "bhavantar", "cotton subsidy", "maize subsidy", "basmati", "scheme 6", "scheme f", "6", "f", "ਫ਼ਸਲੀ ਵਿਭਿੰਨਤਾ", "ਨਰਮਾ", "ਮੱਕੀ", "फसल विविधीकरण", "పంట వైవిధ్యీకరణ"],
        names: {
            "en-IN": "Crop Diversification Incentive Scheme (Promotion of Alternative Crops)",
            "pa-IN": "ਫ਼ਸਲੀ ਵਿਭਿੰਨਤਾ ਪ੍ਰੋਤਸਾਹਨ ਸਕੀਮ (ਬਦਲਵੀਆਂ ਫ਼ਸਲਾਂ ਨੂੰ ਉਤਸ਼ਾਹ - ਪੰਜਾਬ)",
            "hi-IN": "फसल विविधीकरण प्रोत्साहन योजना (वैकल्पिक फसलों को प्रोत्साहन)",
            "te-IN": "పంట వైవిధ్యీకరణ ప్రోత్సాహక పథకం (ప్రత్యామ్నాయ పంటల సాగు)"
        },
        bullets: {
            "en-IN": {
                eligibility: "Farmers in Punjab who voluntarily shift from water-guzzling summer paddy (paddy monoculture) to alternative crops such as Cotton, Maize, Basmati, Pulses, or Oilseeds. Special focus on groundwater-stressed blocks.",
                documents: "Aadhaar Card, Punjab Land Record (Jamabandi), Bank account details, Sowing verification certificate issued by Agriculture Development Officer (ADO).",
                benefits: "Direct financial incentive of ₹7,000 to ₹10,000 per acre for sowing alternative crops, plus 33% subsidy on cotton seeds and crop insurance premium support.",
                applicationProcedure: "Register online on the Punjab Agriculture Portal (agripb.gov.in) before sowing. Field verification is conducted via satellite and physical inspection by the local Agriculture Department.",
                timeline: "Registration window opens in May and remains active through June/July during the Kharif sowing cycle.",
                officialSource: "https://agripb.gov.in (Department of Agriculture & Farmers Welfare, Punjab)",
                helpline: "Directorate of Agriculture Punjab: 0172-2970602 | Kisan Call Centre: 1800-180-1551"
            },
            "pa-IN": {
                eligibility: "ਪੰਜਾਬ ਦੇ ਉਹ ਕਿਸਾਨ ਜੋ ਝੋਨੇ ਦੀ ਥਾਂ ਬਦਲਵੀਆਂ ਫ਼ਸਲਾਂ ਜਿਵੇਂ ਨਰਮਾ, ਮੱਕੀ, ਬਾਸਮਤੀ, ਦਾਲਾਂ ਜਾਂ ਤੇਲ ਬੀਜਾਂ ਦੀ ਕਾਸ਼ਤ ਕਰਦੇ ਹਨ।",
                documents: "ਆਧਾਰ ਕਾਰਡ, ਜ਼ਮੀਨ ਦੀ ਜਮ੍ਹਾਂਬੰਦੀ/ਫ਼ਰਦ, ਬੈਂਕ ਖਾਤਾ, ਖੇਤੀਬਾੜੀ ਅਫ਼ਸਰ (ADO) ਵੱਲੋਂ ਬਿਜਾਈ ਦੀ ਤਸਦੀਕ।",
                benefits: "ਝੋਨਾ ਛੱਡ ਕੇ ਬਦਲਵੀਂ ਫ਼ਸਲ ਬੀਜਣ 'ਤੇ ₹7,000 ਤੋਂ ₹10,000 ਪ੍ਰਤੀ ਏਕੜ ਸਿੱਧੀ ਵਿੱਤੀ ਸਹਾਇਤਾ ਅਤੇ ਨਰਮੇ ਦੇ ਬੀਜਾਂ 'ਤੇ 33% ਸਬਸਿਡੀ।",
                applicationProcedure: "ਬਿਜਾਈ ਤੋਂ ਪਹਿਲਾਂ agripb.gov.in 'ਤੇ ਆਨਲਾਈਨ ਰਜਿਸਟ੍ਰੇਸ਼ਨ ਕਰੋ। ਖੇਤੀਬਾੜੀ ਵਿਭਾਗ ਵੱਲੋਂ ਮੌਕੇ 'ਤੇ ਜਾਂਚ ਉਪਰੰਤ ਪੈਸੇ ਸਿੱਧੇ ਖਾਤੇ ਵਿੱਚ ਭੇਜੇ ਜਾਣਗੇ।",
                timeline: "ਮਈ ਤੋਂ ਜੁਲਾਈ ਦਰਮਿਆਨ ਸਾਉਣੀ ਬਿਜਾਈ ਸਮੇਂ ਰਜਿਸਟ੍ਰੇਸ਼ਨ ਖੁੱਲ੍ਹਦੀ ਹੈ।",
                officialSource: "https://agripb.gov.in (ਖੇਤੀਬਾੜੀ ਵਿਭਾਗ, ਪੰਜਾਬ)",
                helpline: "ਖੇਤੀਬਾੜੀ ਡਾਇਰੈਕਟਰ ਦਫ਼ਤਰ: 0172-2970602 | ਕਿਸਾਨ ਕਾਲ ਸੈਂਟਰ: 1800-180-1551"
            },
            "hi-IN": {
                eligibility: "पंजाब के वे किसान जो धान की जगह मक्का, कपास (नरमा), बासमती या दलहन फसलें बोते हैं।",
                documents: "आधार कार्ड, जमाबंदी, बैंक खाता, एडीओ सत्यापन प्रमाणपत्र।",
                benefits: "धान की जगह वैकल्पिक फसल लगाने पर ₹7,000 से ₹10,000 प्रति एकड़ प्रोत्साहन राशि और कपास बीज पर 33% सब्सिडी।",
                applicationProcedure: "agripb.gov.in पर बुवाई से पहले ऑनलाइन पंजीकरण कराएं।",
                timeline: "मई से जुलाई खरीफ बुवाई के दौरान।",
                officialSource: "https://agripb.gov.in (कृषि विभाग पंजाब)",
                helpline: "फोन: 0172-2970602 | 1800-180-1551"
            },
            "te-IN": {
                eligibility: "వరి స్థానంలో పత్తి, మొక్కజొన్న, పప్పుధాన్యాలు సాగుచేసే పంజాబ్ రైతులు అర్హులు.",
                documents: "ఆధార్ కార్డు, జమాబందీ, బ్యాంక్ వివరాలు, సాగు ధృవీకరణ పత్రం.",
                benefits: "ఎకరాకు ₹7,000 నుండి ₹10,000 వరకు ప్రోత్సాహక సాయం.",
                applicationProcedure: "agripb.gov.in పోర్టల్ ద్వారా నమోదు చేసుకోవాలి.",
                timeline: "మే నుండి జూలై వరకు.",
                officialSource: "https://agripb.gov.in",
                helpline: "0172-2970602 / 1800-180-1551"
            }
        },
        followUps: {
            "en-IN": {
                apply: "To claim the Crop Diversification Incentive:\n1. Visit agripb.gov.in\n2. Click on 'Alternative Crop / Diversification Incentive'\n3. Register your farm parcel number and declare the crop sown (Cotton, Maize, Basmati)\n4. The Block Agriculture Development Officer will inspect your field and verify the crop\n5. The incentive of ₹7,000-₹10,000/acre is transferred directly via DBT to your bank account.",
                documents: "Required Documents: Aadhaar Card, Land record (Fard), Bank passbook, and ADO verification slip.",
                helpline: "Punjab Agriculture Department: 0172-2970602 | Kisan Call Centre: 1800-180-1551",
                timeline: "Registration opens every year from May 1 to July 15 during Kharif sowing.",
                eligibility: "Applicable to any farmer shifting land from normal summer paddy to maize, cotton, pulses, or basmati in Punjab."
            },
            "pa-IN": {
                apply: "ਅਪਲਾਈ ਕਰਨ ਦਾ ਤਰੀਕਾ: agripb.gov.in 'ਤੇ ਰਜਿਸਟ੍ਰੇਸ਼ਨ ਕਰੋ, ਬੀਜੀ ਫ਼ਸਲ ਦਾ ਵੇਰਵਾ ਦਰਜ ਕਰੋ, ਏ.ਡੀ.ਓ. ਖੇਤ ਦੀ ਜਾਂਚ ਕਰੇਗਾ ਅਤੇ ਸਹਾਇਤਾ ਰਾਸ਼ੀ ਖਾਤੇ ਵਿੱਚ ਆ ਜਾਵੇਗੀ।",
                documents: "ਦਸਤਾਵੇਜ਼: ਆਧਾਰ ਕਾਰਡ, ਫ਼ਰਦ, ਬੈਂਕ ਖਾਤਾ, ਖੇਤੀਬਾੜੀ ਅਫ਼ਸਰ ਦੀ ਰਿਪੋਰਟ।",
                helpline: "ਫ਼ੋਨ: 0172-2970602 | ਕਿਸਾਨ ਕਾਲ ਸੈਂਟਰ: 1800-180-1551",
                timeline: "ਹਰ ਸਾਲ 1 ਮਈ ਤੋਂ 15 ਜੁਲਾਈ ਦਰਮਿਆਨ ਰਜਿਸਟ੍ਰੇਸ਼ਨ ਹੁੰਦੀ ਹੈ।",
                eligibility: "ਝੋਨਾ ਛੱਡ ਕੇ ਨਰਮਾ, ਮੱਕੀ ਜਾਂ ਬਾਸਮਤੀ ਬੀਜਣ ਵਾਲੇ ਸਾਰੇ ਕਿਸਾਨ ਯੋਗ ਹਨ।"
            },
            "hi-IN": {
                apply: "agripb.gov.in पर पंजीकरण करें, एडीओ द्वारा भौतिक सत्यापन के बाद राशि खाते में आएगी।",
                documents: "आधार, जमाबंदी, बैंक खाता।",
                helpline: "0172-2970602 / 1800-180-1551",
                timeline: "1 मई से 15 जुलाई के मध्य।",
                eligibility: "धान छोड़कर मक्का, कपास या बासमती बोने वाले किसान।"
            },
            "te-IN": {
                apply: "agripb.gov.in ద్వారా నమోదు చేసుకోవాలి.",
                documents: "ఆధార్, జమాబందీ, బ్యాంక్ వివరాలు.",
                helpline: "0172-2970602 / 1800-180-1551",
                timeline: "మే నుండి జూలై.",
                eligibility: "వరి స్థానంలో ప్రత్యామ్నాయ పంటలు సాగుచేసే రైతులు."
            }
        }
    }
];

/**
 * Returns formatted numbered list of schemes (ONLY the scheme names).
 * Example:
 * 1. Pradhan Mantri Kisan Samman Nidhi (PM-KISAN)
 * 2. Punjab Crop Residue Management Scheme (CRM)
 * 3. ...
 * 
 * @param {string} state - E.g. "Punjab"
 * @param {string} language - E.g. "en-IN", "pa-IN", "hi-IN", "te-IN"
 * @returns {{ text: string, speechText: string, schemes: Array<{ id: string, index: number, letter: string, name: string }> }}
 */
function getSchemesList(state = "Punjab", language = "en-IN") {
    const lang = PUNJAB_SCHEMES[0].names[language] ? language : "en-IN";
    const schemesData = PUNJAB_SCHEMES.map(s => ({
        id: s.id,
        index: s.index,
        letter: s.letter,
        name: s.names[lang] || s.names["en-IN"]
    }));

    // Generate strict numbered list showing ONLY the scheme names
    const lines = schemesData.map(s => `${s.index}. ${s.name}`);
    const text = lines.join("\n");

    // Natural speech format for voice synthesis
    let speechIntro = "";
    let speechOutro = "";
    if (lang === "pa-IN") {
        speechIntro = "ਪੰਜਾਬ ਵਿੱਚ ਇਸ ਵੇਲੇ ਚੱਲ ਰਹੀਆਂ ਸਕੀਮਾਂ:";
        speechOutro = "ਤੁਸੀਂ ਕਿਸ ਸਕੀਮ ਬਾਰੇ ਜਾਣਕਾਰੀ ਚਾਹੁੰਦੇ ਹੋ?";
    } else if (lang === "hi-IN") {
        speechIntro = "पंजाब में वर्तमान में चल रही योजनाएं:";
        speechOutro = "आप किस योजना के बारे में विस्तार से जानना चाहते हैं?";
    } else if (lang === "te-IN") {
        speechIntro = "పంజాబ్‌లో ప్రస్తుతం నడుస్తున్న పథకాలు:";
        speechOutro = "మీరు ఏ పథకం గురించి తెలుసుకోవాలనుకుంటున్నారు?";
    } else {
        speechIntro = "Here are the schemes currently running in Punjab:";
        speechOutro = "Which scheme would you like to know more about? For example, say 'I want Scheme 2' or 'Tell me about Scheme B'.";
    }

    const speechText = `${speechIntro}\n${schemesData.map(s => `${s.index}. ${s.name}`).join(".\n")}.\n${speechOutro}`;

    return {
        text,
        speechText,
        schemes: schemesData
    };
}

/**
 * Returns structured details ONLY about the selected scheme.
 * Bullet structure strictly matches:
 * • Eligibility/criteria
 * • Required documents
 * • Benefits
 * • Application procedure
 * • Timeline/deadline
 * • Official source
 * • Helpline
 * 
 * @param {string} schemeId - E.g. "punjab_crm" or "pm_kisan"
 * @param {string} language - E.g. "en-IN", "pa-IN"
 * @returns {{ text: string, speechText: string, scheme: object } | null}
 */
function getSchemeDetails(schemeId, language = "en-IN") {
    const scheme = PUNJAB_SCHEMES.find(s => s.id === schemeId);
    if (!scheme) return null;

    const lang = scheme.bullets[language] ? language : "en-IN";
    const b = scheme.bullets[lang] || scheme.bullets["en-IN"];
    const name = scheme.names[lang] || scheme.names["en-IN"];

    // Label localization
    const labels = {
        "en-IN": {
            eligibility: "Eligibility/criteria",
            documents: "Required documents",
            benefits: "Benefits",
            applicationProcedure: "Application procedure",
            timeline: "Timeline/deadline",
            officialSource: "Official source",
            helpline: "Helpline"
        },
        "pa-IN": {
            eligibility: "ਯੋਗਤਾ / ਸ਼ਰਤਾਂ (Eligibility/criteria)",
            documents: "ਜ਼ਰੂਰੀ ਦਸਤਾਵੇਜ਼ (Required documents)",
            benefits: "ਲਾਭ / ਸਬਸਿਡੀ (Benefits)",
            applicationProcedure: "ਅਪਲਾਈ ਕਰਨ ਦਾ ਤਰੀਕਾ (Application procedure)",
            timeline: "ਸਮਾਂ-ਸੀਮਾ / ਆਖਰੀ ਮਿਤੀ (Timeline/deadline)",
            officialSource: "ਸਰਕਾਰੀ ਸਰੋਤ (Official source)",
            helpline: "ਹੈਲਪਲਾਈਨ (Helpline)"
        },
        "hi-IN": {
            eligibility: "पात्रता / मापदंड (Eligibility/criteria)",
            documents: "आवश्यक दस्तावेज (Required documents)",
            benefits: "लाभ / सब्सिडी (Benefits)",
            applicationProcedure: "आवेदन प्रक्रिया (Application procedure)",
            timeline: "समय-सीमा / अंतिम तिथि (Timeline/deadline)",
            officialSource: "आधिकारिक स्रोत (Official source)",
            helpline: "हेल्पलाइन (Helpline)"
        },
        "te-IN": {
            eligibility: "అర్హత / ప్రమాణాలు (Eligibility/criteria)",
            documents: "అవసరమైన పత్రాలు (Required documents)",
            benefits: "ప్రయోజనాలు / సబ్సిడీ (Benefits)",
            applicationProcedure: "దరఖాస్తు విధానం (Application procedure)",
            timeline: "గడువు / సమయపాలన (Timeline/deadline)",
            officialSource: "అధికారిక మూలం (Official source)",
            helpline: "హెల్ప్‌లైన్ (Helpline)"
        }
    };

    const l = labels[lang] || labels["en-IN"];

    const text = [
        `🌾 **${name}**\n`,
        `• **${l.eligibility}**: ${b.eligibility}`,
        `• **${l.documents}**: ${b.documents}`,
        `• **${l.benefits}**: ${b.benefits}`,
        `• **${l.applicationProcedure}**: ${b.applicationProcedure}`,
        `• **${l.timeline}**: ${b.timeline}`,
        `• **${l.officialSource}**: ${b.officialSource}`,
        `• **${l.helpline}**: ${b.helpline}`
    ].join("\n");

    // Clean, natural speech version for TTS
    const speechText = `${name}. ` +
        `Benefits: ${b.benefits}. ` +
        `Eligibility: ${b.eligibility}. ` +
        `Required documents: ${b.documents}. ` +
        `Application procedure: ${b.applicationProcedure}. ` +
        `Timeline: ${b.timeline}. ` +
        `Helpline: ${b.helpline}.`;

    return {
        text,
        speechText,
        scheme: {
            id: scheme.id,
            index: scheme.index,
            letter: scheme.letter,
            name
        }
    };
}

/**
 * Resolves a scheme query (e.g. "I want Scheme B", "Scheme 2", "2", "CRM", "Happy Seeder") to a specific scheme.
 * 
 * @param {string} query 
 * @returns {object|null} Matched scheme or null
 */
function matchScheme(query) {
    if (!query) return null;
    const q = query.toLowerCase().trim();

    // 1. Check letter matching: "Scheme A", "Scheme B", "Scheme C", "I want Scheme B", "b", "B"
    const letterMatch = q.match(/\bscheme\s*([a-f])\b/i) || q.match(/\b([a-f])\b/i);
    if (letterMatch && letterMatch[1]) {
        const char = letterMatch[1].toUpperCase();
        const foundByLetter = PUNJAB_SCHEMES.find(s => s.letter === char);
        if (foundByLetter) return foundByLetter;
    }

    // 2. Check number matching: "Scheme 1", "Scheme 2", "1", "2", "number 2", "second scheme", "ਦੂਜੀ ਸਕੀਮ"
    const numberMatch = q.match(/\bscheme\s*([1-6])\b/i) || q.match(/\b([1-6])\b/i) || q.match(/number\s*([1-6])/i);
    if (numberMatch && numberMatch[1]) {
        const num = parseInt(numberMatch[1], 10);
        const foundByNum = PUNJAB_SCHEMES.find(s => s.index === num);
        if (foundByNum) return foundByNum;
    }

    // Ordinal matching in English, Hindi, Punjabi
    if (q.includes("first") || q.includes("first scheme") || q.includes("ਪਹਿਲੀ") || q.includes("पहला") || q.includes("पहला")) {
        return PUNJAB_SCHEMES[0];
    }
    if (q.includes("second") || q.includes("second scheme") || q.includes("ਦੂਜੀ") || q.includes("दूसरा") || q.includes("दूसरी")) {
        return PUNJAB_SCHEMES[1];
    }
    if (q.includes("third") || q.includes("third scheme") || q.includes("ਤੀਜੀ") || q.includes("तीसरा") || q.includes("तीसरी")) {
        return PUNJAB_SCHEMES[2];
    }
    if (q.includes("fourth") || q.includes("fourth scheme") || q.includes("ਚੌਥੀ") || q.includes("चौथा") || q.includes("चौथी")) {
        return PUNJAB_SCHEMES[3];
    }
    if (q.includes("fifth") || q.includes("fifth scheme") || q.includes("ਪੰਜਵੀਂ") || q.includes("पांचवां") || q.includes("पांचवीं")) {
        return PUNJAB_SCHEMES[4];
    }
    if (q.includes("sixth") || q.includes("sixth scheme") || q.includes("ਛੇਵੀਂ") || q.includes("छठा") || q.includes("छठी")) {
        return PUNJAB_SCHEMES[5];
    }

    // 3. Check alias and keyword matches
    for (const scheme of PUNJAB_SCHEMES) {
        if (scheme.aliases.some(alias => q.includes(alias))) {
            return scheme;
        }
    }

    return null;
}

/**
 * Answers follow-up questions for an active scheme.
 * 
 * @param {string} schemeId 
 * @param {string} question 
 * @param {string} language 
 * @param {object|null} farmerProfile 
 * @returns {string|null}
 */
function handleFollowUp(schemeId, question, language = "en-IN", farmerProfile = null) {
    const scheme = PUNJAB_SCHEMES.find(s => s.id === schemeId);
    if (!scheme) return null;

    const q = (question || "").toLowerCase();
    const lang = scheme.followUps[language] ? language : "en-IN";
    const followUps = scheme.followUps[lang] || scheme.followUps["en-IN"];

    // 1. Application Procedure Follow-up
    if (
        q.includes("apply") ||
        q.includes("procedure") ||
        q.includes("how to") ||
        q.includes("process") ||
        q.includes("register") ||
        q.includes("ਅਪਲਾਈ") ||
        q.includes("ਕਿਵੇਂ") ||
        q.includes("आवेदन") ||
        q.includes("దరఖాస్తు")
    ) {
        return followUps.apply;
    }

    // 2. Documents Follow-up
    if (
        q.includes("document") ||
        q.includes("paper") ||
        q.includes("fard") ||
        q.includes("jamabandi") ||
        q.includes("aadhaar") ||
        q.includes("ਦਸਤਾਵੇਜ਼") ||
        q.includes("ਕਾਗਜ਼") ||
        q.includes("दस्तावेज") ||
        q.includes("పత్రాలు")
    ) {
        return followUps.documents;
    }

    // 3. Helpline / Contact Follow-up
    if (
        q.includes("helpline") ||
        q.includes("phone") ||
        q.includes("contact") ||
        q.includes("number") ||
        q.includes("call") ||
        q.includes("toll free") ||
        q.includes("ਹੈਲਪਲਾਈਨ") ||
        q.includes("ਫੋਨ") ||
        q.includes("ਨੰਬਰ") ||
        q.includes("हेल्पलाइन") ||
        q.includes("फोन") ||
        q.includes("హెల్ప్‌లైన్")
    ) {
        return followUps.helpline;
    }

    // 4. Timeline / Deadline Follow-up
    if (
        q.includes("deadline") ||
        q.includes("last date") ||
        q.includes("timeline") ||
        q.includes("when") ||
        q.includes("date") ||
        q.includes("ਮਿਤੀ") ||
        q.includes("ਆਖਰੀ") ||
        q.includes("तिथि") ||
        q.includes("समय") ||
        q.includes("గడువు")
    ) {
        return followUps.timeline;
    }

    // 5. Eligibility Follow-up (with personalization if farmerProfile exists)
    if (
        q.includes("eligible") ||
        q.includes("eligibility") ||
        q.includes("qualify") ||
        q.includes("can i") ||
        q.includes("am i") ||
        q.includes("ਯੋਗ") ||
        q.includes("ਸ਼ਰਤਾਂ") ||
        q.includes("पात्र") ||
        q.includes("అర్హత")
    ) {
        let personalizedPrefix = "";
        if (farmerProfile && farmerProfile.crop && farmerProfile.cropLocation) {
            const loc = farmerProfile.cropLocation;
            const crop = farmerProfile.crop;
            const land = farmerProfile.landSize || "your farm";

            if (lang === "pa-IN") {
                personalizedPrefix = `ਤੁਹਾਡੇ ਪ੍ਰੋਫਾਈਲ ਅਨੁਸਾਰ (${loc} ਵਿੱਚ ${crop} ਦੀ ਫ਼ਸਲ, ${land}): `;
            } else if (lang === "hi-IN") {
                personalizedPrefix = `आपकी प्रोफ़ाइल के अनुसार (${loc} में ${crop}, ${land}): `;
            } else if (lang === "te-IN") {
                personalizedPrefix = `మీ ప్రొఫైల్ వివరాల ప్రకారం (${loc} లో ${crop}, ${land}): `;
            } else {
                personalizedPrefix = `Based on your profile (${crop} crop in ${loc}, ${land}): `;
            }
        }
        return personalizedPrefix + followUps.eligibility;
    }

    // 6. Benefits Follow-up
    if (
        q.includes("benefit") ||
        q.includes("subsidy") ||
        q.includes("amount") ||
        q.includes("money") ||
        q.includes("ਲਾਭ") ||
        q.includes("ਸਬਸਿਡੀ") ||
        q.includes("ਰੁਪਏ") ||
        q.includes("लाभ") ||
        q.includes("सब्सिडी") ||
        q.includes("ప్రయోజనాలు")
    ) {
        const bulletData = scheme.bullets[lang] || scheme.bullets["en-IN"];
        return bulletData.benefits;
    }

    return null;
}

/**
 * Checks if a query is asking for government schemes.
 * 
 * @param {string} question 
 * @returns {boolean}
 */
function isSchemeListQuery(question) {
    const q = (question || "").toLowerCase();
    return (
        (q.includes("scheme") || q.includes("yojana") || q.includes("ਸਕੀਮ") || q.includes("ਸਕੀਮਾਂ") || q.includes("योजना") || q.includes("योजनाएं") || q.includes("పథకం") || q.includes("పథకాలు")) &&
        (q.includes("punjab") || q.includes("running") || q.includes("all") || q.includes("current") || q.includes("available") || q.includes("list") || q.includes("tell me") || q.includes("show") || q.includes("state") || q.includes("ਪੰਜਾਬ") || q.includes("ਸਾਰੀਆਂ") || q.includes("पंजाब") || q.includes("सभी") || q.includes("పంజాబ్") || q.includes("అన్నీ"))
    );
}

module.exports = {
    PUNJAB_SCHEMES,
    getSchemesList,
    getSchemeDetails,
    matchScheme,
    handleFollowUp,
    isSchemeListQuery
};
