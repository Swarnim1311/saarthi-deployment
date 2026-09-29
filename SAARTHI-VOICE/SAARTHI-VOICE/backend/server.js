const path = require("path");
// Ensure .env is loaded regardless of current working directory
require("dotenv").config({ path: path.join(__dirname, ".env") });
require("dotenv").config({ path: path.join(__dirname, "..", ".env") });

const express = require("express");
const cors = require("cors");

const { getFarmerById, saveFarmer } = require("./farmers");
const { generateAnswer } = require("./farmerContext");
const { synthesizeSpeech, synthesizePunjabiAudio, transcribeAudio } = require("./bhashini");

const app = express();

// Path to frontend directory containing index.html, script.js, style.css
const FRONTEND_DIR = path.join(__dirname, "..");


// ==========================================
// MIDDLEWARE
// ==========================================

app.use(cors());

app.use(express.json({ limit: "50mb" }));
app.use(express.urlencoded({ limit: "50mb", extended: true }));

// Serve static frontend files (script.js, style.css, index.html, etc.)
app.use(express.static(FRONTEND_DIR));


// ==========================================
// FRONTEND ROUTE & HEALTH CHECK
// ==========================================

app.get("/", (req, res) => {
    res.sendFile(path.join(FRONTEND_DIR, "index.html"));
});

// Dedicated backend health check route
app.get("/api/health", (req, res) => {
    res.send("🌾 SAARTHI Backend is running!");
});


// ==========================================
// FARMER DATABASE ENDPOINTS (TASK 2, 7, 9)
// ==========================================

/**
 * GET /farmers/:farmerId
 * Retrieves a single farmer's profile.
 * PRIVACY: Never exposes the full database list to the frontend.
 */
app.get("/farmers/:farmerId", (req, res) => {
    try {
        const { farmerId } = req.params;
        const farmer = getFarmerById(farmerId);

        if (!farmer) {
            return res.status(404).json({
                success: false,
                error: `Farmer profile not found with ID: ${farmerId}`
            });
        }

        res.json({
            success: true,
            farmer: farmer
        });
    } catch (error) {
        console.error("❌ Error fetching farmer:", error);
        res.status(500).json({
            success: false,
            error: "Internal server error while fetching farmer profile."
        });
    }
});

/**
 * POST /farmers
 * Saves or updates a farmer profile in the local database.
 */
app.post("/farmers", (req, res) => {
    try {
        const result = saveFarmer(req.body);

        if (!result.success) {
            return res.status(400).json({
                success: false,
                error: result.error
            });
        }

        res.status(201).json({
            success: true,
            message: "Farmer profile saved successfully.",
            farmer: result.farmer
        });
    } catch (error) {
        console.error("❌ Error saving farmer:", error);
        res.status(500).json({
            success: false,
            error: "Internal server error while saving farmer profile."
        });
    }
});


// ==========================================
// ASK SAARTHI (TASK 4, 5, 7)
// ==========================================

/**
 * POST /ask
 * Handles agronomic queries.
 * Optionally receives `farmerId` to enrich answer with stored crop/location context.
 * If farmerId is omitted, continues working normally with general advice.
 */
app.post("/ask", async (req, res) => {
    try {
        const question = req.body.question || "";
        const language = req.body.language || "en-IN";
        const farmerId = req.body.farmerId || null;
        const sessionState = req.body.sessionState || null;

        console.log("--------------------------------");
        console.log("🌾 Question:", question);
        console.log("🌐 Language:", language);
        if (farmerId) {
            console.log("👨‍🌾 Farmer ID context:", farmerId);
        }
        if (sessionState && sessionState.activeScheme) {
            console.log("🏛️ Active scheme session context:", sessionState.activeScheme);
        }
        console.log("--------------------------------");

        // Retrieve farmer context if ID is provided
        let farmer = null;
        if (farmerId) {
            farmer = getFarmerById(farmerId);
            if (farmer) {
                console.log(`✅ Loaded context: ${farmer.farmerName} (${farmer.crop}, ${farmer.cropLocation})`);
            } else {
                console.log(`⚠️ Farmer ID ${farmerId} provided but not found in database.`);
            }
        }

        const result = await generateAnswer(question, language, farmer, sessionState);

        // Normalize string or object response for backward compatibility
        const answerText = typeof result === "object" ? result.answer : result;
        const speechText = (typeof result === "object" && result.speechText) ? result.speechText : answerText;
        const updatedSessionState = (typeof result === "object" && result.sessionState) ? result.sessionState : sessionState;

        res.json({
            answer: answerText,
            speechText: speechText,
            sessionState: updatedSessionState,
            farmerContextUsed: Boolean(farmer)
        });
    } catch (error) {
        console.error("❌ Error in /ask:", error);
        res.status(500).json({
            answer: "An error occurred while generating the advice. Please try again."
        });
    }
});


// ==========================================
// BHASHINI TEXT TO SPEECH (TTS) (TASK 6)
// ==========================================

app.post("/speak", async (req, res) => {
    try {
        const text = req.body.text || "";
        const language = req.body.language || "en-IN";

        if (!text) {
            return res.status(400).json({
                error: "Text is required"
            });
        }

        console.log("--------------------------------");
        console.log("🔊 BHASHINI speech request");
        console.log("Text:", text);
        console.log("Language:", language);
        console.log("--------------------------------");

        const langCode = language.split("-")[0].toLowerCase();

        // Indian languages (pa, hi, te): Use Bhashini Indic-TTS for crystal-clear spoken voice!
        if (langCode === "pa" || langCode === "hi" || langCode === "te") {
            try {
                const audioBase64 = await synthesizeSpeech(text, langCode);
                console.log(`🔊 Bhashini ${langCode.toUpperCase()} TTS audio successfully generated!`);
                return res.json({
                    audio: audioBase64,
                    text: text,
                    language: language
                });
            } catch (ttsErr) {
                console.error(`❌ Bhashini ${langCode.toUpperCase()} TTS compute error:`, ttsErr.message);
                if (langCode === "pa") {
                    return res.status(500).json({
                        error: "Bhashini TTS compute failed: " + ttsErr.message
                    });
                }
            }
        }

        // For other languages (English or fallback), return status for browser TTS fallback
        res.json({
            message: "Using browser voice fallback",
            text: text,
            language: language
        });
    } catch (error) {
        console.error("❌ Speech error:", error);
        res.status(500).json({
            error: "Unable to generate speech: " + error.message
        });
    }
});


// ==========================================
// BHASHINI SPEECH TO TEXT (ASR) (TASK 6)
// ==========================================

app.post("/transcribe", async (req, res) => {
    try {
        const audio = req.body.audio || "";
        const language = req.body.language || "pa-IN";

        if (!audio) {
            return res.status(400).json({
                error: "Audio data (base64) is required"
            });
        }

        const langCode = language.split("-")[0].toLowerCase();

        console.log("--------------------------------");
        console.log("🎤 BHASHINI ASR transcribe request");
        console.log("Language:", language, `(code: ${langCode})`);
        console.log("Audio payload size:", audio.length, "bytes base64");
        console.log("--------------------------------");

        const recognizedText = await transcribeAudio(audio, langCode);
        console.log("🌾 Bhashini ASR recognized transcript:", recognizedText);

        res.json({
            text: recognizedText,
            language: language
        });
    } catch (error) {
        console.error("❌ Transcribe error:", error);
        res.status(500).json({
            error: "Unable to transcribe audio: " + error.message
        });
    }
});


// ==========================================
// START SERVER
// ==========================================

const PORT = process.env.PORT || 3000;

app.listen(PORT, () => {
    console.log(`🌾 SAARTHI Backend running on http://localhost:${PORT}`);
});