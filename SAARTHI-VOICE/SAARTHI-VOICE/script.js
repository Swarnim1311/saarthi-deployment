// ============================================================================
// SAARTHI VOICE ASSISTANT - FRONTEND CONTROLLER
// ============================================================================
// Features:
// 1. Multilingual Support: English (en-IN), Hindi (hi-IN), Telugu (te-IN), Punjabi (pa-IN)
// 2. Punjabi Bhashini Integration: 16kHz WAV Audio recording & Indic-TTS playback
// 3. Farmer Profile Database Management: Session-based context memory for crop advice
// 4. Clean Core UI: Voice input, Hear Again, Language selection, Profile manager
// ============================================================================


// ==========================================
// 1. GET HTML ELEMENTS
// ==========================================

const startButton = document.querySelector("#startButton");
const status = document.querySelector("#status");
const result = document.querySelector("#result");
const languageSelect = document.querySelector("#language");
const userQuestion = document.querySelector("#userQuestion");
const replayButton = document.querySelector("#replayButton");

const askTitle = document.querySelector("#askTitle");
const languageLabel = document.querySelector("#languageLabel");
const exampleTitle = document.querySelector("#exampleTitle");
const exampleQuestion = document.querySelector("#exampleQuestion");
const youSaidTitle = document.querySelector("#youSaidTitle");
const answerTitle = document.querySelector("#answerTitle");

const voiceCircle = document.querySelector("#voiceCircle");
const recordingTimer = document.querySelector("#recordingTimer");

// Farmer Profile UI Elements (Task 3 & 4)
const profileButton = document.querySelector("#profileButton");
const farmerModal = document.querySelector("#farmerModal");
const closeModalBtn = document.querySelector("#closeModalBtn");

const loadFarmerIdInput = document.querySelector("#loadFarmerIdInput");
const loadFarmerBtn = document.querySelector("#loadFarmerBtn");
const loadMsg = document.querySelector("#loadMsg");
const chipF001 = document.querySelector("#chipF001");
const chipF002 = document.querySelector("#chipF002");

const loadedProfileCard = document.querySelector("#loadedProfileCard");
const cardFarmerId = document.querySelector("#cardFarmerId");
const cardFarmerName = document.querySelector("#cardFarmerName");
const cardCrop = document.querySelector("#cardCrop");
const cardLocation = document.querySelector("#cardLocation");
const cardDistrictState = document.querySelector("#cardDistrictState");
const cardLandSize = document.querySelector("#cardLandSize");
const cardSowingDate = document.querySelector("#cardSowingDate");
const cardLandSizeItem = document.querySelector("#cardLandSizeItem");
const cardSowingDateItem = document.querySelector("#cardSowingDateItem");

const farmerForm = document.querySelector("#farmerForm");
const formFarmerId = document.querySelector("#formFarmerId");
const formFarmerName = document.querySelector("#formFarmerName");
const formCrop = document.querySelector("#formCrop");
const formCropLocation = document.querySelector("#formCropLocation");
const formDistrict = document.querySelector("#formDistrict");
const formState = document.querySelector("#formState");
const formLandSize = document.querySelector("#formLandSize");
const formSowingDate = document.querySelector("#formSowingDate");
const saveMsg = document.querySelector("#saveMsg");

const activeFarmerBanner = document.querySelector("#activeFarmerBanner");
const bannerFarmerName = document.querySelector("#bannerFarmerName");
const bannerFarmerCrop = document.querySelector("#bannerFarmerCrop");
const bannerFarmerLocation = document.querySelector("#bannerFarmerLocation");
const clearFarmerSessionBtn = document.querySelector("#clearFarmerSessionBtn");


// ==========================================
// 2. STATE VARIABLES
// ==========================================

let lastAnswer = "";
let speechRate = 0.9;
let isVoiceOutputEnabled = true;
let recordingTimerInterval = null;
let recordingSeconds = 0;
let currentVoiceState = "idle"; // "idle" | "listening" | "processing" | "speaking" | "error"
let currentPlayingAudio = null;
let lastAudioBase64 = null;
let errorAutoResetTimer = null;
let isManualStop = false;

// Farmer Context Memory for Current Session (Task 4)
let activeFarmerId = null;
let activeFarmerProfile = null;

// ==========================================
// 3. MULTILINGUAL TRANSLATIONS & LABELS
// ==========================================

const translations = {

    "en-IN": {
        askTitle: "Ask SAARTHI",
        languageLabel: "Choose Language:",
        start: "Start Speaking",
        tapToSpeak: "Tap to Speak",
        listening: "Listening...",
        listeningStop: "Listening... (Click to Stop)",
        processing: "Processing...",
        speaking: "Speaking...",
        ready: "Ready",
        stop: "Stop Speaking",
        youSaid: "You said:",
        answer: "SAARTHI says:",
        hearAgain: "Hear Again",
        profileBtn: "Farmer Profile",
        recording: "Recording",

        example: "Try asking:",
        exampleQuestion: "\"Should I irrigate my paddy today?\"",

        noSupport: "Speech recognition is not supported in this browser.",
        connection: "Please make sure the SAARTHI backend is running.",
        error: "Something went wrong.",

        errMicDenied: "Microphone access was denied. Please allow microphone in browser settings.",
        errNoSpeech: "No speech was detected. Tap the microphone and speak clearly.",
        errRecording: "Audio recording failed. Please check your microphone.",
        errAsr: "Speech recognition could not understand. Please try speaking again.",
        errTts: "Voice answer is not available. You can read the answer below.",
        errBackend: "Could not connect to SAARTHI backend. Please check that the server is running.",
        errNetwork: "Network problem occurred. Please try again."
    },

    "hi-IN": {
        askTitle: "सारथी से पूछें",
        languageLabel: "भाषा चुनें:",
        start: "बोलना शुरू करें",
        tapToSpeak: "बोलने के लिए दबाएं",
        listening: "सुन रहा हूँ...",
        listeningStop: "सुन रहा हूँ... (रोकने के लिए दबाएं)",
        processing: "सोच रहा हूँ...",
        speaking: "बोल रहा हूँ...",
        ready: "तैयार",
        stop: "रोकें",
        youSaid: "आपने कहा:",
        answer: "सारथी कहता है:",
        hearAgain: "फिर से सुनें",
        profileBtn: "किसान प्रोफ़ाइल",
        recording: "रिकॉर्डिंग",

        example: "पूछकर देखें:",
        exampleQuestion: "\"क्या मुझे आज अपने धान में पानी देना चाहिए?\"",

        noSupport: "इस ब्राउज़र में आवाज़ पहचानने की सुविधा उपलब्ध नहीं है।",
        connection: "कृपया सुनिश्चित करें कि SAARTHI का बैकएंड चल रहा है।",
        error: "कुछ गलत हो गया।",

        errMicDenied: "माइक्रोफ़ोन की अनुमति नहीं मिली। कृपया ब्राउज़र सेटिंग में अनुमति दें।",
        errNoSpeech: "कोई आवाज़ नहीं सुनी गई। कृपया माइक्रोफ़ोन दबाकर दोबारा बोलें।",
        errRecording: "ऑडियो रिकॉर्डिंग विफल रही। कृपया माइक्रोफ़ोन जांचें।",
        errAsr: "आवाज़ पहचानी नहीं जा सकी। कृपया साफ़ आवाज़ में दोबारा बोलें।",
        errTts: "आवाज़ में उत्तर उपलब्ध नहीं है। आप नीचे लिखा उत्तर पढ़ सकते हैं।",
        errBackend: "SAARTHI बैकएंड से संपर्क नहीं हो सका। कृपया जांचें कि सर्वर चल रहा है।",
        errNetwork: "नेटवर्क में समस्या आई है। कृपया दोबारा प्रयास करें।"
    },

    "te-IN": {
        askTitle: "సార్థిని అడగండి",
        languageLabel: "భాషను ఎంచుకోండి:",
        start: "మాట్లాడటం ప్రారంభించండి",
        tapToSpeak: "మాట్లాడటానికి నొక్కండి",
        listening: "వింటున్నాను...",
        listeningStop: "వింటున్నాను... (ఆపడానికి నొక్కండి)",
        processing: "ఆలోచిస్తోంది...",
        speaking: "మాట్లాడుతోంది...",
        ready: "సిద్ధంగా ఉంది",
        stop: "ఆపండి",
        youSaid: "మీరు చెప్పారు:",
        answer: "సార్థి చెబుతోంది:",
        hearAgain: "మళ్లీ వినండి",
        profileBtn: "రైతు ప్రొఫైల్",
        recording: "రికార్డింగ్",

        example: "ఇలా అడగండి:",
        exampleQuestion: "\"నేను ఈ రోజు నా వరి పంటకు నీరు పెట్టాలా?\"",

        noSupport: "ఈ బ్రౌజర్‌లో స్పీచ్ రికగ్నిషన్ అందుబాటులో లేదు.",
        connection: "దయచేసి SAARTHI బ్యాక్‌ఎండ్ నడుస్తుందో లేదో చూసుకోండి.",
        error: "ఏదో తప్పు జరిగింది.",

        errMicDenied: "మైక్రోఫోన్ అనుమతి నిరాకరించబడింది. దయచేసి బ్రౌజర్ సెట్టింగ్‌లలో అనుమతించండి.",
        errNoSpeech: "వాయిస్ వినబడలేదు. దయచేసి మళ్లీ మాట్లాడండి.",
        errRecording: "ఆడియో రికార్డింగ్ విఫలమైంది. మైక్రోఫోన్‌ను తనిఖీ చేయండి.",
        errAsr: "వాయిస్ గుర్తించలేకపోయాము. దయచేసి స్పష్టంగా మాట్లాడండి.",
        errTts: "వాయిస్ అందుబాటులో లేదు. మీరు కింద సమాధానం చదువుకోవచ్చు.",
        errBackend: "SAARTHI బ్యాక్‌ఎండ్ అందుబాటులో లేదు. దయచేసి సర్వర్ నడుస్తోందో లేదో చూడండి.",
        errNetwork: "నెట్‌వర్క్ సమస్య. దయచేసి మళ్లీ ప్రయత్నించండి."
    },

    "pa-IN": {
        askTitle: "ਸਾਰਥੀ ਨੂੰ ਪੁੱਛੋ",
        languageLabel: "ਭਾਸ਼ਾ ਚੁਣੋ:",
        start: "ਬੋਲਣਾ ਸ਼ੁਰੂ ਕਰੋ",
        tapToSpeak: "ਬੋਲਣ ਲਈ ਦਬਾਓ",
        listening: "ਸੁਣ ਰਿਹਾ ਹਾਂ...",
        listeningStop: "ਸੁਣ ਰਿਹਾ ਹਾਂ... (ਰੋਕਣ ਲਈ ਦਬਾਓ)",
        processing: "ਸੋਚ ਰਿਹਾ ਹਾਂ...",
        speaking: "ਬੋਲ ਰਿਹਾ ਹਾਂ...",
        ready: "ਤਿਆਰ ਹੈ",
        stop: "ਰੋਕੋ (Stop)",
        youSaid: "ਤੁਸੀਂ ਕਿਹਾ:",
        answer: "ਸਾਰਥੀ ਕਹਿੰਦਾ ਹੈ:",
        hearAgain: "ਦੁਬਾਰਾ ਸੁਣੋ",
        profileBtn: "ਕਿਸਾਨ ਪ੍ਰੋਫਾਈਲ",
        recording: "ਰਿਕਾਰਡਿੰਗ",

        example: "ਇਹ ਪੁੱਛੋ:",
        exampleQuestion: "\"ਕੀ ਮੈਨੂੰ ਅੱਜ ਆਪਣੀ ਝੋਨੇ ਦੀ ਫ਼ਸਲ ਨੂੰ ਪਾਣੀ ਦੇਣਾ ਚਾਹੀਦਾ ਹੈ?\"",

        noSupport: "ਇਸ ਬ੍ਰਾਊਜ਼ਰ ਵਿੱਚ ਸਪੀਚ ਰਿਕਗਨਿਸ਼ਨ ਉਪਲਬਧ ਨਹੀਂ ਹੈ।",
        connection: "ਕਿਰਪਾ ਕਰਕੇ ਯਕੀਨੀ ਬਣਾਓ ਕਿ SAARTHI ਬੈਕਐਂਡ ਚੱਲ ਰਿਹਾ ਹੈ।",
        error: "ਕੁਝ ਗਲਤ ਹੋ ਗਿਆ।",

        errMicDenied: "ਮਾਈਕ੍ਰੋਫੋਨ ਦੀ ਇਜਾਜ਼ਤ ਨਹੀਂ ਮਿਲੀ। ਕਿਰਪਾ ਕਰਕੇ ਬ੍ਰਾਊਜ਼ਰ ਸੈਟਿੰਗਾਂ ਵਿੱਚ ਇਜਾਜ਼ਤ ਦਿਓ।",
        errNoSpeech: "ਕੋਈ ਆਵਾਜ਼ ਨਹੀਂ ਸੁਣੀ ਗਈ। ਕਿਰਪਾ ਕਰਕੇ ਦੁਬਾਰਾ ਸਾਫ਼ ਆਵਾਜ਼ ਵਿੱਚ ਬੋਲੋ।",
        errRecording: "ਆਡੀਓ ਰਿਕਾਰਡਿੰਗ ਅਸਫਲ ਰਹੀ। ਕਿਰਪਾ ਕਰਕੇ ਮਾਈਕ੍ਰੋਫੋਨ ਦੀ ਜਾਂਚ ਕਰੋ।",
        errAsr: "ਆਵਾਜ਼ ਦੀ ਪਛਾਣ ਨਹੀਂ ਹੋ ਸਕੀ। ਕਿਰਪਾ ਕਰਕੇ ਸਾਫ਼ ਆਵਾਜ਼ ਵਿੱਚ ਦੁਬਾਰਾ ਬੋਲੋ।",
        errTts: "ਆਵਾਜ਼ ਵਿੱਚ ਉੱਤਰ ਉਪਲਬਧ ਨਹੀਂ ਹੈ। ਤੁਸੀਂ ਹੇਠਾਂ ਉੱਤਰ ਪੜ੍ਹ ਸਕਦੇ ਹੋ।",
        errBackend: "SAARTHI ਬੈਕਐਂਡ ਨਾਲ ਸੰਪਰਕ ਨਹੀਂ ਹੋ ਸਕਿਆ। ਕਿਰਪਾ ਕਰਕੇ ਜਾਂਚ ਕਰੋ ਕਿ ਸਰਵਰ ਚੱਲ ਰਿਹਾ ਹੈ।",
        errNetwork: "ਨੈੱਟਵਰਕ ਸਮੱਸਿਆ ਆਈ ਹੈ। ਕਿਰਪਾ ਕਰਕੇ ਦੁਬਾਰਾ ਕੋਸ਼ਿਸ਼ ਕਰੋ।"
    }

};

// ==========================================
// 4. CLEAR VOICE UI STATE MANAGER
// ==========================================

function setVoiceState(state, customMessage = "") {

    currentVoiceState = state;
    const lang = languageSelect ? languageSelect.value : "en-IN";
    const t = translations[lang] || translations["en-IN"];

    if (errorAutoResetTimer) {
        clearTimeout(errorAutoResetTimer);
        errorAutoResetTimer = null;
    }

    if (voiceCircle) {
        voiceCircle.classList.remove("listening", "processing", "speaking", "error");
    }

    if (startButton) {
        startButton.classList.remove("listening", "processing", "speaking", "error");
        startButton.disabled = false;
    }

    switch (state) {

        case "listening":
            if (voiceCircle) {
                voiceCircle.classList.add("listening");
            }
            if (startButton) {
                startButton.innerText = t.stop || "Stop Speaking";
                startButton.classList.add("listening");
            }
            if (status) {
                status.innerText = t.listening || "Listening...";
                status.style.color = "#d32f2f";
            }
            startRecordingTimer();
            break;

        case "processing":
            if (voiceCircle) {
                voiceCircle.classList.add("processing");
            }
            if (startButton) {
                startButton.innerText = t.processing || "Processing...";
                startButton.classList.add("processing");
                startButton.disabled = true;
            }
            if (status) {
                status.innerText = t.processing || "Processing...";
                status.style.color = "#f57c00";
            }
            stopRecordingTimer();
            break;

        case "speaking":
            if (voiceCircle) {
                voiceCircle.classList.add("speaking");
            }
            if (startButton) {
                startButton.innerText = t.stop || "Stop Speaking";
                startButton.classList.add("speaking");
            }
            if (status) {
                status.innerText = t.speaking || "Speaking...";
                status.style.color = "#1976d2";
            }
            stopRecordingTimer();
            break;

        case "error":
            if (voiceCircle) {
                voiceCircle.classList.add("error");
            }
            if (startButton) {
                startButton.innerText = t.tapToSpeak || t.start || "Tap to Speak";
            }
            if (status) {
                status.innerText = customMessage || t.error || "Something went wrong.";
                status.style.color = "#c62828";
            }
            stopRecordingTimer();

            // Auto-recover back to idle after 4 seconds
            errorAutoResetTimer = setTimeout(() => {
                if (currentVoiceState === "error") {
                    setVoiceState("idle");
                }
            }, 4000);
            break;

        case "idle":
        default:
            currentVoiceState = "idle";
            if (startButton) {
                startButton.innerText = t.tapToSpeak || t.start || "Tap to Speak";
                startButton.disabled = false;
            }
            if (status) {
                status.innerText = customMessage || t.ready || "Ready";
                status.style.color = "#1f2d1f";
            }
            stopRecordingTimer();
            break;

    }

}

// ==========================================
// 5. RECORDING TIMER HELPERS
// ==========================================

function startRecordingTimer() {

    clearInterval(recordingTimerInterval);
    recordingSeconds = 0;

    const lang = languageSelect ? languageSelect.value : "en-IN";
    const t = translations[lang] || translations["en-IN"];

    if (recordingTimer) {
        recordingTimer.innerText = `${t.recording || "Recording"} 00:00`;
        recordingTimer.style.display = "block";

        recordingTimerInterval = setInterval(() => {
            recordingSeconds++;
            const mins = String(Math.floor(recordingSeconds / 60)).padStart(2, "0");
            const secs = String(recordingSeconds % 60).padStart(2, "0");
            recordingTimer.innerText = `${t.recording || "Recording"} ${mins}:${secs}`;
        }, 1000);
    }

}

function stopRecordingTimer() {

    clearInterval(recordingTimerInterval);

    if (recordingTimer) {
        recordingTimer.style.display = "none";
    }

}


// ==========================================
// 6. UPDATE UI LANGUAGE
// ==========================================

function updateLanguage() {

    const lang = languageSelect.value;
    const t = translations[lang] || translations["en-IN"];

    if (askTitle) askTitle.innerText = t.askTitle;
    if (languageLabel) languageLabel.innerText = t.languageLabel;
    if (exampleTitle) exampleTitle.innerText = t.example;
    if (exampleQuestion) exampleQuestion.innerText = t.exampleQuestion;
    if (youSaidTitle) youSaidTitle.innerText = t.youSaid;
    if (answerTitle) answerTitle.innerText = t.answer;
    if (replayButton) replayButton.innerText = t.hearAgain;
    if (profileButton) profileButton.innerText = t.profileBtn || "Farmer Profile";

    setVoiceState(currentVoiceState);

}


// ==========================================
// 7. LANGUAGE CHANGE LISTENER
// ==========================================

if (languageSelect) {

    languageSelect.addEventListener("change", () => {

        if (isPunjabiRecording) {
            stopPunjabiRecording();
        }

        if (recognition && currentVoiceState === "listening") {
            isManualStop = true;
            try { recognition.stop(); } catch (e) { }
        }

        if (currentPlayingAudio) {
            currentPlayingAudio.pause();
            currentPlayingAudio = null;
        }

        if (window.speechSynthesis) {
            window.speechSynthesis.cancel();
        }

        setVoiceState("idle");
        updateLanguage();

        console.log("Language changed to:", languageSelect.value);

    });

}


// ==========================================
// 8. FARMER PROFILE DATABASE UI LOGIC (TASK 3 & 4)
// ==========================================

// Open & Close Modal
if (profileButton) {
    profileButton.addEventListener("click", () => {
        if (farmerModal) farmerModal.style.display = "flex";
        if (loadMsg) loadMsg.innerText = "";
        if (saveMsg) saveMsg.innerText = "";
    });
}

if (closeModalBtn) {
    closeModalBtn.addEventListener("click", () => {
        if (farmerModal) farmerModal.style.display = "none";
    });
}

// Close when clicking outside modal card
if (farmerModal) {
    farmerModal.addEventListener("click", (e) => {
        if (e.target === farmerModal) {
            farmerModal.style.display = "none";
        }
    });
}

// Quick-test chip buttons
if (chipF001) {
    chipF001.addEventListener("click", () => {
        if (loadFarmerIdInput) loadFarmerIdInput.value = "F001";
        loadFarmerProfile("F001");
    });
}

if (chipF002) {
    chipF002.addEventListener("click", () => {
        if (loadFarmerIdInput) loadFarmerIdInput.value = "F002";
        loadFarmerProfile("F002");
    });
}

// Load Farmer by ID
if (loadFarmerBtn) {
    loadFarmerBtn.addEventListener("click", () => {
        const id = loadFarmerIdInput ? loadFarmerIdInput.value.trim() : "";
        if (!id) {
            showFeedback(loadMsg, "Please enter a Farmer ID.", "error");
            return;
        }
        loadFarmerProfile(id);
    });
}

/**
 * Loads farmer profile from backend by Farmer ID.
 * Stores in active session for context memory during /ask.
 */
async function loadFarmerProfile(farmerId) {
    if (!farmerId) return;

    showFeedback(loadMsg, "Fetching profile...", "");

    try {
        const response = await fetch(`http://localhost:3000/farmers/${encodeURIComponent(farmerId)}`);
        const data = await response.json();

        if (!response.ok || !data.success || !data.farmer) {
            showFeedback(loadMsg, data.error || `Farmer '${farmerId}' not found.`, "error");
            return;
        }

        const farmer = data.farmer;
        setActiveFarmer(farmer);
        renderFarmerCard(farmer);
        showFeedback(loadMsg, `Profile '${farmer.farmerName}' loaded for current session!`, "success");

        console.log("Farmer session loaded:", farmer);
    } catch (error) {
        console.error("Error loading farmer profile:", error);
        showFeedback(loadMsg, "Could not connect to backend to load farmer.", "error");
    }
}

// Register / Save Farmer Form
if (farmerForm) {
    farmerForm.addEventListener("submit", async (e) => {
        e.preventDefault();

        const payload = {
            farmerId: formFarmerId ? formFarmerId.value.trim() : "",
            farmerName: formFarmerName ? formFarmerName.value.trim() : "",
            crop: formCrop ? formCrop.value.trim() : "",
            cropLocation: formCropLocation ? formCropLocation.value.trim() : "",
            district: formDistrict ? formDistrict.value.trim() : "",
            state: formState ? formState.value.trim() : "",
            landSize: formLandSize ? formLandSize.value.trim() : "",
            sowingDate: formSowingDate ? formSowingDate.value.trim() : ""
        };

        if (!payload.farmerId || !payload.farmerName || !payload.crop || !payload.cropLocation || !payload.district || !payload.state) {
            showFeedback(saveMsg, "Please fill in all required fields (*).", "error");
            return;
        }

        showFeedback(saveMsg, "Saving farmer profile...", "");

        try {
            const response = await fetch("http://localhost:3000/farmers", {
                method: "POST",
                headers: {
                    "Content-Type": "application/json"
                },
                body: JSON.stringify(payload)
            });

            const data = await response.json();

            if (!response.ok || !data.success) {
                showFeedback(saveMsg, data.error || "Failed to save farmer profile.", "error");
                return;
            }

            const savedFarmer = data.farmer;
            setActiveFarmer(savedFarmer);
            renderFarmerCard(savedFarmer);
            showFeedback(saveMsg, "Farmer saved and active in session!", "success");

            // Populate load input
            if (loadFarmerIdInput) loadFarmerIdInput.value = savedFarmer.farmerId;

            console.log("Farmer saved and activated:", savedFarmer);
        } catch (error) {
            console.error("Error saving farmer profile:", error);
            showFeedback(saveMsg, "Could not connect to backend to save farmer.", "error");
        }
    });
}

// Clear Farmer Session
if (clearFarmerSessionBtn) {
    clearFarmerSessionBtn.addEventListener("click", () => {
        activeFarmerId = null;
        activeFarmerProfile = null;

        if (activeFarmerBanner) activeFarmerBanner.style.display = "none";
        if (loadedProfileCard) loadedProfileCard.style.display = "none";
        if (loadFarmerIdInput) loadFarmerIdInput.value = "";
        showFeedback(loadMsg, "Active farmer profile cleared from session.", "");
        console.log("Cleared active farmer session context.");
    });
}

function setActiveFarmer(farmer) {
    activeFarmerId = farmer.farmerId;
    activeFarmerProfile = farmer;

    if (activeFarmerBanner) {
        if (bannerFarmerName) bannerFarmerName.innerText = farmer.farmerName;
        if (bannerFarmerCrop) bannerFarmerCrop.innerText = farmer.crop;
        if (bannerFarmerLocation) bannerFarmerLocation.innerText = farmer.cropLocation;
        activeFarmerBanner.style.display = "flex";
    }
}

function renderFarmerCard(farmer) {
    if (!loadedProfileCard) return;

    if (cardFarmerId) cardFarmerId.innerText = farmer.farmerId;
    if (cardFarmerName) cardFarmerName.innerText = farmer.farmerName;
    if (cardCrop) cardCrop.innerText = farmer.crop;
    if (cardLocation) cardLocation.innerText = farmer.cropLocation;
    if (cardDistrictState) cardDistrictState.innerText = `${farmer.district}, ${farmer.state}`;

    if (farmer.landSize && cardLandSize) {
        cardLandSize.innerText = farmer.landSize;
        if (cardLandSizeItem) cardLandSizeItem.style.display = "block";
    } else if (cardLandSizeItem) {
        cardLandSizeItem.style.display = "none";
    }

    if (farmer.sowingDate && cardSowingDate) {
        cardSowingDate.innerText = farmer.sowingDate;
        if (cardSowingDateItem) cardSowingDateItem.style.display = "block";
    } else if (cardSowingDateItem) {
        cardSowingDateItem.style.display = "none";
    }

    loadedProfileCard.style.display = "block";
}

function showFeedback(element, message, type) {
    if (!element) return;
    element.innerText = message;
    element.className = "feedback-msg" + (type ? " " + type : "");
}


// ==========================================
// 9. SEND QUESTION TO BACKEND AND SPEAK (TASK 4, 5, 7)
// ==========================================

async function handleFarmerQuestion(text, selectedLanguage) {

    console.log("Farmer question:", text, `(${selectedLanguage})`);
    if (activeFarmerId) {
        console.log("Attaching farmer context for session:", activeFarmerId);
    }

    const t = translations[selectedLanguage] || translations["en-IN"];

    // Display what the farmer said
    if (youSaidTitle) youSaidTitle.innerText = t.youSaid;
    if (userQuestion) userQuestion.innerText = text;

    setVoiceState("processing");

    try {

        // Build payload including optional farmerId for contextual advice (Task 4 & 7)
        const payload = {
            question: text,
            language: selectedLanguage
        };

        if (activeFarmerId) {
            payload.farmerId = activeFarmerId;
        }

        const response = await fetch("http://localhost:3000/ask", {
            method: "POST",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify(payload)
        });

        if (!response.ok) {
            throw new Error("Backend error: " + response.status);
        }

        const data = await response.json();

        console.log("SAARTHI answer:", data.answer);
        if (data.farmerContextUsed) {
            console.log("Agronomic answer generated using farmer crop/location context!");
        }

        lastAnswer = data.answer;
        if (answerTitle) answerTitle.innerText = t.answer;
        if (result) result.innerText = data.answer;

        // Speak the answer
        speakAnswer(data.answer);

    }

    catch (error) {

        console.error("Connection error:", error);

        if (!navigator.onLine) {
            setVoiceState("error", t.errNetwork);
            if (result) result.innerText = t.errNetwork;
        } else {
            setVoiceState("error", t.errBackend);
            if (result) result.innerText = t.errBackend;
        }

    }

}


// ==========================================
// 10. PUNJABI BHASHINI AUDIO RECORDER (TASK 6)
// ==========================================

let isPunjabiRecording = false;

// Safe accessor function if referenced as isPunjabiRecording()
function getIsPunjabiRecording() {
    return isPunjabiRecording;
}
let punjabiMediaStream = null;
let punjabiAudioContext = null;
let punjabiRecorderNode = null;
let punjabiAudioChunks = [];
let punjabiAutoStopTimer = null;
let punjabiRecordedSampleRate = 16000;

function writeWavString(view, offset, string) {
    for (let i = 0; i < string.length; i++) {
        view.setUint8(offset + i, string.charCodeAt(i));
    }
}

/**
 * Downsamples single-channel PCM audio buffer from inputSampleRate down to 16000 Hz.
 * Required for Bhashini ASR to prevent audio speed/pitch distortion.
 */
function downsampleBuffer(buffer, inputSampleRate, outputSampleRate = 16000) {
    if (!buffer || buffer.length === 0 || inputSampleRate === outputSampleRate) {
        return buffer;
    }
    if (inputSampleRate < outputSampleRate) {
        return buffer;
    }
    const sampleRateRatio = inputSampleRate / outputSampleRate;
    const newLength = Math.round(buffer.length / sampleRateRatio);
    const result = new Float32Array(newLength);
    let offsetResult = 0;
    let offsetBuffer = 0;
    while (offsetResult < result.length) {
        const nextOffsetBuffer = Math.round((offsetResult + 1) * sampleRateRatio);
        let accum = 0;
        let count = 0;
        for (let i = offsetBuffer; i < nextOffsetBuffer && i < buffer.length; i++) {
            accum += buffer[i];
            count++;
        }
        result[offsetResult] = count > 0 ? accum / count : 0;
        offsetResult++;
        offsetBuffer = nextOffsetBuffer;
    }
    return result;
}

/**
 * Encodes Float32 PCM samples into a 16-bit Mono WAV format and converts to base64.
 * Uses chunked String conversion to prevent call-stack or UI thread freezing.
 */
function encodeWAVBase64(samples, sampleRate = 16000) {

    const buffer = new ArrayBuffer(44 + samples.length * 2);
    const view = new DataView(buffer);

    writeWavString(view, 0, "RIFF");
    view.setUint32(4, 36 + samples.length * 2, true);
    writeWavString(view, 8, "WAVE");
    writeWavString(view, 12, "fmt ");
    view.setUint32(16, 16, true);
    view.setUint16(20, 1, true); // PCM format
    view.setUint16(22, 1, true); // Mono channel
    view.setUint32(24, sampleRate, true);
    view.setUint32(28, sampleRate * 2, true); // Byte rate (16000 * 2)
    view.setUint16(32, 2, true); // Block align (1 * 2)
    view.setUint16(34, 16, true); // 16 bits per sample
    writeWavString(view, 36, "data");
    view.setUint32(40, samples.length * 2, true);

    let offset = 44;
    for (let i = 0; i < samples.length; i++, offset += 2) {
        let s = Math.max(-1, Math.min(1, samples[i]));
        view.setInt16(offset, s < 0 ? s * 0x8000 : s * 0x7FFF, true);
    }

    let binary = "";
    const bytes = new Uint8Array(buffer);
    const chunkSize = 8192;
    for (let i = 0; i < bytes.length; i += chunkSize) {
        binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunkSize));
    }

    return btoa(binary);

}

/**
 * Plays base64 WAV audio safely using a memory Blob URL.
 */
function playWavBase64(base64Audio, onEnded, onError) {
    try {
        if (currentPlayingAudio) {
            currentPlayingAudio.pause();
            currentPlayingAudio = null;
        }

        const byteCharacters = atob(base64Audio);
        const byteNumbers = new Uint8Array(byteCharacters.length);
        for (let i = 0; i < byteCharacters.length; i++) {
            byteNumbers[i] = byteCharacters.charCodeAt(i);
        }
        const blob = new Blob([byteNumbers], { type: "audio/wav" });
        const url = URL.createObjectURL(blob);
        const audio = new Audio(url);
        currentPlayingAudio = audio;

        audio.onended = () => {
            URL.revokeObjectURL(url);
            currentPlayingAudio = null;
            if (onEnded) onEnded();
        };

        audio.onerror = (err) => {
            console.error("Audio playback error:", err);
            URL.revokeObjectURL(url);
            currentPlayingAudio = null;
            if (onError) onError(err);
        };

        audio.play().catch((err) => {
            console.error("Audio play promise error:", err);
            URL.revokeObjectURL(url);
            currentPlayingAudio = null;
            if (onError) onError(err);
        });
    } catch (err) {
        console.error("playWavBase64 exception:", err);
        if (onError) onError(err);
    }
}

async function startPunjabiRecording() {

    const t = translations["pa-IN"];

    try {

        if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
            setVoiceState("error", t.errRecording);
            return;
        }

        punjabiMediaStream = await navigator.mediaDevices.getUserMedia({ audio: true });

        const AudioCtx = window.AudioContext || window.webkitAudioContext;
        try {
            punjabiAudioContext = new AudioCtx({ sampleRate: 16000 });
        } catch (e) {
            punjabiAudioContext = new AudioCtx();
        }
        punjabiRecordedSampleRate = (punjabiAudioContext && punjabiAudioContext.sampleRate) ? punjabiAudioContext.sampleRate : 16000;
        console.log("Punjabi AudioContext initialized with sampleRate:", punjabiRecordedSampleRate);

        const source = punjabiAudioContext.createMediaStreamSource(punjabiMediaStream);
        punjabiRecorderNode = punjabiAudioContext.createScriptProcessor(4096, 1, 1);
        punjabiAudioChunks = [];

        punjabiRecorderNode.onaudioprocess = function (e) {
            if (!isPunjabiRecording) return;
            const channelData = e.inputBuffer.getChannelData(0);
            punjabiAudioChunks.push(new Float32Array(channelData));
        };

        source.connect(punjabiRecorderNode);
        punjabiRecorderNode.connect(punjabiAudioContext.destination);

        isPunjabiRecording = true;
        setVoiceState("listening");

        if (result) result.innerText = "";
        if (userQuestion) userQuestion.innerText = "";

        console.log("Punjabi Bhashini voice recording started...");

        // Safety timeout (9 seconds)
        clearTimeout(punjabiAutoStopTimer);
        punjabiAutoStopTimer = setTimeout(() => {
            if (isPunjabiRecording) {
                console.log("Auto-stopping Punjabi recording after 9s safety timeout...");
                stopPunjabiRecording();
            }
        }, 9000);

    }

    catch (error) {

        console.error("Microphone access error:", error);
        isPunjabiRecording = false;

        if (error.name === "NotAllowedError" || error.name === "PermissionDeniedError") {
            setVoiceState("error", t.errMicDenied);
        } else {
            setVoiceState("error", t.errRecording);
        }

    }

}

async function stopPunjabiRecording() {

    if (!isPunjabiRecording) return;
    isPunjabiRecording = false;
    clearTimeout(punjabiAutoStopTimer);

    const t = translations["pa-IN"];
    setVoiceState("processing");

    if (punjabiMediaStream) {
        punjabiMediaStream.getTracks().forEach(track => track.stop());
    }
    if (punjabiRecorderNode) {
        punjabiRecorderNode.disconnect();
    }
    const sampleRate = punjabiRecordedSampleRate || (punjabiAudioContext ? punjabiAudioContext.sampleRate : 16000);
    if (punjabiAudioContext && punjabiAudioContext.state !== "closed") {
        punjabiAudioContext.close();
    }

    let totalSamples = 0;
    for (const chunk of punjabiAudioChunks) {
        totalSamples += chunk.length;
    }

    // If recorded audio is less than ~0.5s, treat as no speech
    if (totalSamples < sampleRate * 0.5) {
        console.log("Audio too short (< 0.5s)");
        setVoiceState("error", t.errNoSpeech);
        return;
    }

    const mergedSamples = new Float32Array(totalSamples);
    let offset = 0;
    for (const chunk of punjabiAudioChunks) {
        mergedSamples.set(chunk, offset);
        offset += chunk.length;
    }

    // Downsample from hardware recording rate to clean 16000 Hz for Bhashini ASR
    const samples16k = downsampleBuffer(mergedSamples, sampleRate, 16000);
    const wavBase64 = encodeWAVBase64(samples16k, 16000);

    try {

        console.log(`Sending recorded audio (${samples16k.length} samples at 16kHz) to Bhashini ASR endpoint...`);

        const response = await fetch("http://localhost:3000/transcribe", {
            method: "POST",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify({
                audio: wavBase64,
                language: "pa-IN"
            })
        });

        if (!response.ok) {
            throw new Error("Transcribe backend error: " + response.status);
        }

        const data = await response.json();
        const recognizedText = data.text ? data.text.trim() : "";

        console.log("Bhashini transcribed Punjabi text:", recognizedText);

        if (!recognizedText) {
            setVoiceState("error", t.errNoSpeech);
            return;
        }

        await handleFarmerQuestion(recognizedText, "pa-IN");

    }

    catch (error) {

        console.error("Punjabi ASR error:", error);

        if (!navigator.onLine) {
            setVoiceState("error", t.errNetwork);
        } else if (error.message && error.message.includes("Failed to fetch")) {
            setVoiceState("error", t.errBackend);
        } else {
            setVoiceState("error", t.errAsr);
        }

    }

}


// ==========================================
// 11. SPEECH RECOGNITION (EN / HI / TE)
// ==========================================

const SpeechRecognition =
    window.SpeechRecognition ||
    window.webkitSpeechRecognition;

let recognition = null;

if (SpeechRecognition) {

    recognition = new SpeechRecognition();
    recognition.continuous = false;
    recognition.interimResults = false;

    // Speech Start
    recognition.onstart = function () {
        console.log("Browser speech recognition started.");
        setVoiceState("listening");
    };

    // Speech Result
    recognition.onresult = async function (event) {

        const text = event.results[0][0].transcript;
        const selectedLanguage = languageSelect.value;

        console.log("Farmer said (via browser recognition):", text);

        setVoiceState("processing");
        await handleFarmerQuestion(text, selectedLanguage);

    };

    // Speech Error
    recognition.onerror = function (event) {

        console.error("Speech error:", event.error);
        const t = translations[languageSelect.value] || translations["en-IN"];

        // If stopped manually or aborted intentionally, return cleanly to idle without error
        if (event.error === "aborted") {
            console.log("Recognition aborted/stopped by user.");
            if (isManualStop || currentVoiceState === "listening") {
                setVoiceState("idle");
            }
            return;
        }

        if (event.error === "not-allowed") {
            setVoiceState("error", t.errMicDenied);
            return;
        }

        if (event.error === "no-speech") {
            setVoiceState("error", t.errNoSpeech);
            return;
        }

        if (event.error === "audio-capture") {
            setVoiceState("error", t.errRecording || t.errMicDenied);
            return;
        }

        if (event.error === "network") {
            setVoiceState("error", t.errNetwork);
            return;
        }

        setVoiceState("error", t.errAsr);

    };

    // Recognition End
    recognition.onend = function () {
        console.log("Speech recognition ended.");
        isManualStop = false;
        if (currentVoiceState === "listening") {
            setVoiceState("idle");
        }
    };

}


// ==========================================
// 12. START / STOP LISTENING (ALL LANGUAGES)
// ==========================================

function handleVoiceToggle() {

    // 1. If currently processing, ignore clicks to prevent duplicate / concurrent requests
    if (currentVoiceState === "processing") {
        console.log("Voice query is currently processing. Please wait.");
        return;
    }

    // 2. If currently speaking (TTS audio playback), clicking stops the speech cleanly
    if (currentVoiceState === "speaking") {
        console.log("User interrupted speech playback.");
        if (currentPlayingAudio) {
            currentPlayingAudio.pause();
            currentPlayingAudio = null;
        }
        if (window.speechSynthesis) {
            window.speechSynthesis.cancel();
        }
        setVoiceState("idle");
        return;
    }

    // 3. If currently listening, clicking toggles STOP
    if (currentVoiceState === "listening") {
        console.log("User clicked Stop Speaking.");
        if (isPunjabiRecording) {
            stopPunjabiRecording();
        } else if (recognition) {
            isManualStop = true;
            try {
                recognition.stop();
            } catch (e) {
                console.log("Recognition stop error:", e);
                setVoiceState("idle");
            }
        }
        return;
    }

    // 4. If idle or error: start listening
    startListening();

}

if (startButton) {
    startButton.addEventListener("click", handleVoiceToggle);
}

if (voiceCircle) {
    voiceCircle.addEventListener("click", handleVoiceToggle);
}

function startListening() {

    isManualStop = false;
    const selectedLanguage = languageSelect.value;
    const t = translations[selectedLanguage] || translations["en-IN"];

    if (currentPlayingAudio) {
        currentPlayingAudio.pause();
        currentPlayingAudio = null;
    }

    if (window.speechSynthesis) {
        window.speechSynthesis.cancel();
    }

    // PUNJABI: Uses Bhashini Voice Recording
    if (selectedLanguage === "pa-IN") {
        if (isPunjabiRecording) {
            stopPunjabiRecording();
        } else {
            startPunjabiRecording();
        }
        return;
    }

    // OTHER LANGUAGES: Use browser SpeechRecognition
    if (!recognition) {
        setVoiceState("error", t.noSupport);
        return;
    }

    recognition.lang = selectedLanguage;
    setVoiceState("listening");

    if (result) result.innerText = "";
    if (userQuestion) userQuestion.innerText = "";

    console.log("Recognition language:", selectedLanguage);

    try {
        recognition.start();
    } catch (error) {
        console.log("Recognition already running.");
    }

}


// ==========================================
// 13. TEXT TO SPEECH (VOICE OUTPUT)
// ==========================================

async function speakAnswer(text, forcePlay = false) {

    if (!text) {
        return;
    }

    const selectedLanguage = languageSelect.value;
    const t = translations[selectedLanguage] || translations["en-IN"];

    console.log("speakAnswer requested for:", selectedLanguage);
    lastAudioBase64 = null;

    try {

        const response = await fetch("http://localhost:3000/speak", {
            method: "POST",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify({
                text: text,
                language: selectedLanguage,
                rate: speechRate
            })
        });

        if (!response.ok) {
            throw new Error("Speech backend error: " + response.status);
        }

        const data = await response.json();

        // ------------------------------------------
        // AUDIO RETURNED FROM BACKEND (BHASHINI)
        // ------------------------------------------
        if (data.audio) {

            lastAudioBase64 = data.audio;
            setVoiceState("speaking");

            playWavBase64(
                data.audio,
                () => {
                    console.log("Bhashini audio playback ended.");
                    setVoiceState("idle", t.answer);
                },
                (err) => {
                    console.error("Audio playback error:", err);
                    setVoiceState("idle", t.errTts);
                }
            );

        }

        // ------------------------------------------
        // BROWSER VOICE FALLBACK FOR EN/HI/TE
        // ------------------------------------------
        else {

            if (selectedLanguage !== "pa-IN") {
                speakUsingBrowser(text, selectedLanguage);
            } else {
                setVoiceState("idle", t.answer);
            }

        }

    }

    catch (error) {

        console.error("Voice error:", error);
        setVoiceState("idle", t.errTts);

        if (selectedLanguage !== "pa-IN") {
            speakUsingBrowser(text, selectedLanguage);
        }

    }

}


// ==========================================
// 14. BROWSER VOICE FALLBACK
// ==========================================

function speakUsingBrowser(text, selectedLanguage) {

    if (!text) {
        return;
    }

    window.speechSynthesis.cancel();

    const speech = new SpeechSynthesisUtterance(text);
    speech.lang = selectedLanguage;
    speech.rate = speechRate;
    speech.pitch = 1;

    speech.onstart = () => {
        setVoiceState("speaking");
    };

    speech.onend = () => {
        setVoiceState("idle");
    };

    speech.onerror = () => {
        setVoiceState("idle");
    };

    const voices = window.speechSynthesis.getVoices();

    let matchingVoice = voices.find(
        voice => voice.lang.toLowerCase() === selectedLanguage.toLowerCase()
    );

    if (!matchingVoice) {
        const languageCode = selectedLanguage.split("-")[0].toLowerCase();
        matchingVoice = voices.find(
            voice => voice.lang.toLowerCase().startsWith(languageCode)
        );
    }

    if (matchingVoice) {
        speech.voice = matchingVoice;
        console.log("Using browser voice:", matchingVoice.name, matchingVoice.lang);
    }

    window.speechSynthesis.speak(speech);

}


// ==========================================
// 15. HEAR AGAIN
// ==========================================

if (replayButton) {

    replayButton.addEventListener("click", function () {

        if (!lastAnswer) {
            return;
        }

        console.log("Hear Again clicked");

        if (lastAudioBase64) {
            setVoiceState("speaking");

            playWavBase64(
                lastAudioBase64,
                () => {
                    setVoiceState("idle");
                },
                (err) => {
                    console.error("Audio replay error:", err);
                    setVoiceState("idle");
                }
            );

            return;
        }

        speakAnswer(lastAnswer, true);

    });

}


// ==========================================
// 16. LOAD BROWSER VOICES
// ==========================================

if (window.speechSynthesis) {
    window.speechSynthesis.onvoiceschanged = function () {
        const voices = window.speechSynthesis.getVoices();
        console.log("Browser voices loaded:", voices.length);
    };
}


// ==========================================
// 17. INITIAL UI
// ==========================================

updateLanguage();
setVoiceState("idle");