# 🌾 SAARTHI Voice Module Documentation

This repository contains the standalone, production-ready **Multilingual Voice Module** for the **SAARTHI** Voice-First Agricultural Assistant. It provides voice input (Speech-to-Text / ASR) and voice output (Text-to-Speech / TTS) with official **Bhashini** integration for Punjabi (`pa-IN`) alongside browser-native Web Speech support for English (`en-IN`), Hindi (`hi-IN`), and Telugu (`te-IN`).

It includes a lightweight, modular **Farmer Profile Database** with session-based context memory, allowing SAARTHI to deliver personalized agronomic recommendations (crop-specific and location-aware) without requiring farmers to repeat their background details in every voice query.

---

## 1. Module Capabilities

* **Punjabi Voice Input (Bhashini ASR):** Uses the browser Web Audio API to capture 16kHz mono PCM audio, packages standard WAV format, and transcribes via Bhashini's Conformer ASR pipeline (`POST /transcribe`).
* **Punjabi Voice Output (Bhashini TTS):** Generates natural Punjabi voice audio using Bhashini's Indic-TTS Coqui model (`POST /speak`) and plays it back via HTML5 Audio.
* **English, Hindi & Telugu Support:** Seamlessly integrates standard browser SpeechRecognition and SpeechSynthesis.
* **Clean, Focused Core UI:**
  1. Language Selection (English, Hindi, Telugu, Punjabi)
  2. Microphone / Voice Input button (`Start Speaking` / `⏹ Stop Speaking`)
  3. Hear Again / Speaker button (`🔊 Hear Again`)
  4. Farmer Profile Button & Modal (`👨‍🌾 Farmer Profile`)
  5. Chat / Response Area (`👨‍🌾 You said` & `🌾 SAARTHI says`)
* **Session Farmer Context Memory:** When a farmer profile is loaded (e.g. Harpreet Singh, Rice, Amritsar), SAARTHI remembers the context and tailors irrigation, weather, fertilizer, and pest advice accordingly.
* **Natural Voice Responses:** Complies with privacy and natural conversation standards — avoids robotic repetition of the farmer's personal name, delivering practical answers directly.
* **Weather Context Hook:** Modular hook structured around `farmer.cropLocation` ready for teammates to connect OpenWeatherMap or IMD APIs.
* **Privacy by Design:** Strict endpoint controls — only returns the individual farmer profile requested by `farmerId`; zero API keys or environment variables exposed in frontend scripts.

---

## 2. File Structure

```
SAARTHI-VOICE/
├── index.html                  # Clean core UI (Language selector, mic button, farmer modal, chat area)
├── script.js                   # Frontend controller (Audio recorder, state manager, Bhashini connector)
├── style.css                   # Supplemental stylesheet
├── README.md                   # Complete module & integration documentation
├── .gitignore                  # Git ignore rules protecting credentials and node_modules
└── backend/
    ├── .env                    # Real credentials (DO NOT COMMIT OR SHARE)
    ├── .env.example            # Template credentials file for teammates
    ├── package.json            # Node.js configuration
    ├── server.js               # Express server routing /farmers, /ask, /speak, /transcribe
    ├── farmers.js              # Farmer database access layer (read, write, getById, save)
    ├── farmerContext.js        # Agronomic reasoning engine with multilingual crop/location context
    ├── weatherService.js       # Weather hook accepting farmer.cropLocation
    ├── bhashini.js             # Modular Bhashini ASR & TTS pipelines with in-memory caching
    └── data/
        └── farmers.json        # Lightweight JSON database with seed records (F001, F002)
```

---

## 3. Environment Variables & Credentials

All Bhashini credentials reside strictly in `backend/.env`. **Never expose API keys in frontend JavaScript.**

### `backend/.env.example` Template:
```env
# Bhashini Credentials for SAARTHI
BHASHINI_API_KEY=your_bhashini_api_key_here
BHASHINI_USER_ID=your_bhashini_user_id_here
BHASHINI_PIPELINE_ID=64392f96daac500b55c543cd
```

---

## 4. How to Start the Backend Server

### Prerequisites
* Node.js v18 or newer.

### Installation & Run
```bash
# 1. Navigate to backend directory
cd backend

# 2. Install dependencies (if not already installed)
npm install

# 3. Create .env from template and add your credentials
copy .env.example .env

# 4. Start the server
node server.js
```

The server starts on **`http://localhost:3000`** and serves both the frontend web application and API endpoints.

---

## 5. Backend API Endpoints

### 1. `GET /farmers/:farmerId`
Retrieves a single farmer's profile.
* **URL Param:** `farmerId` (e.g. `F001`)
* **Privacy:** Returns ONLY the requested farmer record (or 404 if not found).
* **Response (200 OK):**
  ```json
  {
    "success": true,
    "farmer": {
      "farmerId": "F001",
      "farmerName": "Harpreet Singh",
      "crop": "Rice",
      "cropLocation": "Amritsar",
      "district": "Amritsar",
      "state": "Punjab",
      "landSize": "5 acres",
      "sowingDate": "2024-06-15"
    }
  }
  ```

### 2. `POST /farmers`
Registers or updates a farmer profile in `backend/data/farmers.json`.
* **Headers:** `Content-Type: application/json`
* **Request Body:**
  ```json
  {
    "farmerId": "F003",
    "farmerName": "Sukhwinder Kaur",
    "crop": "Cotton",
    "cropLocation": "Bathinda",
    "district": "Bathinda",
    "state": "Punjab",
    "landSize": "4 acres",
    "sowingDate": "2024-05-10"
  }
  ```
* **Response (201 Created):**
  ```json
  {
    "success": true,
    "message": "Farmer profile saved successfully.",
    "farmer": { ... }
  }
  ```

### 3. `POST /ask`
Generates multilingual agronomic advice. Accepts optional `farmerId` to enrich answers with stored crop and location context.
* **Headers:** `Content-Type: application/json`
* **Request Body (with context):**
  ```json
  {
    "question": "Should I water my crop today?",
    "language": "en-IN",
    "farmerId": "F001"
  }
  ```
* **Response Body (200 OK):**
  ```json
  {
    "answer": "Based on weather forecasts for Amritsar, rain is expected soon. Your rice crop will receive natural moisture, so you may pause irrigation today.",
    "farmerContextUsed": true
  }
  ```
* *Note: If `farmerId` is omitted, `/ask` continues working normally with general agricultural advice.*

### 4. `POST /transcribe` (Speech-to-Text / ASR)
Transcribes 16kHz mono WAV base64 audio via Bhashini ASR.
* **Headers:** `Content-Type: application/json`
* **Request Body:**
  ```json
  {
    "audio": "<base64_wav_audio>",
    "language": "pa-IN"
  }
  ```
* **Response Body (200 OK):**
  ```json
  {
    "text": "ਕੀ ਮੈਨੂੰ ਅੱਜ ਆਪਣੀ ਫ਼ਸਲ ਨੂੰ ਪਾਣੀ ਦੇਣਾ ਚਾਹੀਦਾ ਹੈ?",
    "language": "pa-IN"
  }
  ```

### 5. `POST /speak` (Text-to-Speech / TTS)
Generates audio speech using Bhashini Indic-TTS for Punjabi (`pa`).
* **Headers:** `Content-Type: application/json`
* **Request Body:**
  ```json
  {
    "text": "ਅੰਮ੍ਰਿਤਸਰ ਵਿੱਚ ਮੀਂਹ ਪੈਣ ਦੀ ਸੰਭਾਵਨਾ ਨੂੰ ਦੇਖਦੇ ਹੋਏ...",
    "language": "pa-IN"
  }
  ```
* **Response Body (200 OK):**
  ```json
  {
    "audio": "<base64_wav_audio>",
    "text": "...",
    "language": "pa-IN"
  }
  ```

---

## 6. How to Test the Farmer Database

Run the following automated test in PowerShell or terminal:

```bash
# 1. Test Farmer Lookup (F001)
curl http://localhost:3000/farmers/F001

# 2. Test Nonexistent Farmer (Privacy 404 test)
curl http://localhost:3000/farmers/F999

# 3. Test Save New Farmer (F004)
curl -X POST http://localhost:3000/farmers -H "Content-Type: application/json" -d "{\"farmerId\":\"F004\",\"farmerName\":\"Gurpreet Singh\",\"crop\":\"Wheat\",\"cropLocation\":\"Ludhiana\",\"district\":\"Ludhiana\",\"state\":\"Punjab\"}"

# 4. Test Asking with Farmer Context (F001 - Rice in Amritsar)
curl -X POST http://localhost:3000/ask -H "Content-Type: application/json" -d "{\"question\":\"Should I water my crop today?\",\"language\":\"en-IN\",\"farmerId\":\"F001\"}"

# 5. Test Asking in Punjabi with Farmer Context
curl -X POST http://localhost:3000/ask -H "Content-Type: application/json" -d "{\"question\":\"ਕੀ ਮੈਨੂੰ ਅੱਜ ਆਪਣੀ ਫ਼ਸਲ ਨੂੰ ਪਾਣੀ ਦੇਣਾ ਚਾਹੀਦਾ ਹੈ?\",\"language\":\"pa-IN\",\"farmerId\":\"F001\"}"
```

In the Web UI (`http://localhost:3000`):
1. Click **"👨‍🌾 Farmer Profile"**.
2. Click the quick chip **"F001 (Amritsar - Rice)"** or type `F001` and click **"Load Farmer"**.
3. Notice the **Active Profile Card** and top banner displaying Harpreet Singh's crop and location.
4. Speak or ask: *"Should I water my crop today?"*
5. Notice that SAARTHI automatically provides guidance tailored to rice in Amritsar without asking for your location.

---

## 7. How Teammates Can Integrate This Module into the Main SAARTHI Project

This module was intentionally designed to be 100% modular so teammates can drop it into the main repository:

### Step 1: Copy Files
Copy the following files into your main SAARTHI repository:
* `backend/farmers.js` -> Data access layer for farmer records
* `backend/farmerContext.js` -> Context-aware agronomic reasoning
* `backend/weatherService.js` -> Weather context hook
* `backend/bhashini.js` -> Bhashini TTS & ASR pipelines
* `backend/data/farmers.json` -> Local farmer database

### Step 2: Mount the Routes in Your Main Express App
In your main `app.js` or `server.js`:
```javascript
const { getFarmerById, saveFarmer } = require("./farmers");
const { generateAnswer } = require("./farmerContext");
const { synthesizePunjabiAudio, transcribeAudio } = require("./bhashini");

// 1. Mount farmer profile endpoints:
app.get("/farmers/:farmerId", (req, res) => {
    const farmer = getFarmerById(req.params.farmerId);
    if (!farmer) return res.status(404).json({ success: false, error: "Farmer not found" });
    res.json({ success: true, farmer });
});

app.post("/farmers", (req, res) => {
    const result = saveFarmer(req.body);
    if (!result.success) return res.status(400).json(result);
    res.status(201).json(result);
});

// 2. Pass farmerId to your agronomy engine:
app.post("/ask", async (req, res) => {
    const { question, language, farmerId } = req.body;
    const farmer = farmerId ? getFarmerById(farmerId) : null;
    const answer = await generateAnswer(question, language, farmer);
    res.json({ answer, farmerContextUsed: Boolean(farmer) });
});

// 3. Mount voice endpoints (/speak and /transcribe):
// See backend/server.js for exact handlers.
```

### Step 3: Connect Your Real Weather API (Task 8 Hook)
Open `backend/weatherService.js`. In `getWeatherForLocation(cropLocation)`:
```javascript
async function getWeatherForLocation(cropLocation, district = "", state = "") {
    const apiKey = process.env.WEATHER_API_KEY;
    const response = await fetch(`https://api.weatherapi.com/v1/forecast.json?key=${apiKey}&q=${encodeURIComponent(cropLocation)}&days=3`);
    const data = await response.json();
    return {
        location: cropLocation,
        forecast: data.current.condition.text,
        rainLikely: data.forecast.forecastday[0].day.daily_chance_of_rain > 50,
        temperature: `${data.current.temp_c}°C`
    };
}
```

### Step 4: Hook into Your Main Database (If MongoDB / PostgreSQL is used)
If your main project uses MongoDB / Mongoose or PostgreSQL instead of JSON files:
Replace `readFarmersData()` and `writeFarmersData()` in `backend/farmers.js` with your Mongoose model (`Farmer.findOne({ farmerId })` and `Farmer.findOneAndUpdate(...)`). The rest of the voice and context codebase remains identical!
