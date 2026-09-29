/**
 * ============================================================================
 * 🌾 SAARTHI - Bhashini Pipeline Module (TTS & ASR)
 * ============================================================================
 * 
 * Modular wrapper for Government of India / MeitY Bhashini AI Services:
 * - ASR (Automatic Speech Recognition / Speech-to-Text) for Punjabi (pa)
 * - TTS (Text-to-Speech / Speech Synthesis) for Punjabi (pa), Hindi (hi), Telugu (te)
 * 
 * Pipeline endpoints and inference keys are cached in-memory to minimize latency.
 * ============================================================================
 */

const BHASHINI_API_KEY = process.env.BHASHINI_API_KEY;
const BHASHINI_USER_ID = process.env.BHASHINI_USER_ID;
const BHASHINI_PIPELINE_ID = process.env.BHASHINI_PIPELINE_ID || "64392f96daac500b55c543cd";

const pipelineCache = {};

/**
 * Retrieves and caches the Bhashini pipeline inference endpoint and serviceId.
 * 
 * @param {"tts" | "asr"} taskType 
 * @param {string} sourceLang E.g. "pa", "hi", "te"
 * @returns {Promise<{callbackUrl: string, authHeaderName: string, authHeaderValue: string, serviceId: string}>}
 */
async function getBhashiniPipeline(taskType, sourceLang) {
    const cleanLang = (sourceLang || "pa").split("-")[0].toLowerCase();
    const cacheKey = `${taskType}_${cleanLang}`;

    if (pipelineCache[cacheKey]) {
        return pipelineCache[cacheKey];
    }

    console.log(`📡 Fetching Bhashini pipeline config for task: ${taskType}, lang: ${cleanLang}...`);

    const response = await fetch(
        "https://meity-auth.ulcacontrib.org/ulca/apis/v0/model/getModelsPipeline",
        {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                "ulcaApiKey": BHASHINI_API_KEY,
                "userID": BHASHINI_USER_ID
            },
            body: JSON.stringify({
                pipelineTasks: [
                    {
                        taskType: taskType,
                        config: {
                            language: {
                                sourceLanguage: cleanLang
                            }
                        }
                    }
                ],
                pipelineRequestConfig: {
                    pipelineId: BHASHINI_PIPELINE_ID
                }
            })
        }
    );

    if (!response.ok) {
        const errorText = await response.text();
        throw new Error(`Bhashini getModelsPipeline failed (${response.status}): ${errorText}`);
    }

    const data = await response.json();
    const endpoint = data.pipelineInferenceAPIEndPoint;
    const taskConfig = data.pipelineResponseConfig?.[0]?.config?.[0];

    if (!endpoint || !taskConfig) {
        throw new Error(`Invalid pipeline response structure: ${JSON.stringify(data)}`);
    }

    const config = {
        callbackUrl: endpoint.callbackUrl,
        authHeaderName: endpoint.inferenceApiKey?.name || "Authorization",
        authHeaderValue: endpoint.inferenceApiKey?.value,
        serviceId: taskConfig.serviceId
    };

    pipelineCache[cacheKey] = config;
    console.log(`✅ Cached Bhashini pipeline config for ${cacheKey} (Service: ${config.serviceId})`);

    return config;
}

/**
 * Generates natural Indian language audio from text using Bhashini Indic-TTS.
 * Supports Punjabi (pa), Hindi (hi), and Telugu (te).
 * 
 * @param {string} text - Spoken text
 * @param {string} langCode - Language code e.g. "pa", "hi", "te"
 * @returns {Promise<string>} Base64-encoded WAV audio
 */
async function synthesizeSpeech(text, langCode = "pa") {
    if (!BHASHINI_API_KEY || !BHASHINI_USER_ID) {
        throw new Error("BHASHINI credentials are not fully configured in .env");
    }

    const cleanLang = (langCode || "pa").split("-")[0].toLowerCase();
    const pipeline = await getBhashiniPipeline("tts", cleanLang);

    const computeRes = await fetch(pipeline.callbackUrl, {
        method: "POST",
        headers: {
            "Content-Type": "application/json",
            [pipeline.authHeaderName]: pipeline.authHeaderValue
        },
        body: JSON.stringify({
            pipelineTasks: [
                {
                    taskType: "tts",
                    config: {
                        language: {
                            sourceLanguage: cleanLang
                        },
                        serviceId: pipeline.serviceId,
                        gender: "female"
                    }
                }
            ],
            inputData: {
                input: [
                    {
                        source: text
                    }
                ]
            }
        })
    });

    if (!computeRes.ok) {
        const errText = await computeRes.text();
        throw new Error(`Bhashini TTS compute failed for ${cleanLang}: ${errText}`);
    }

    const computeData = await computeRes.json();
    const audioBase64 = computeData.pipelineResponse?.[0]?.audio?.[0]?.audioContent;

    if (!audioBase64) {
        throw new Error(`No audio returned in Bhashini TTS response for ${cleanLang}`);
    }

    return audioBase64;
}

/**
 * Backward-compatible alias for Punjabi synthesis.
 */
async function synthesizePunjabiAudio(text) {
    return synthesizeSpeech(text, "pa");
}

/**
 * Transcribes audio into text using Bhashini ASR.
 * 
 * @param {string} audioBase64 - 16kHz WAV base64 audio
 * @param {string} langCode - Language code e.g. "pa"
 * @returns {Promise<string>} Recognized transcript text
 */
async function transcribeAudio(audioBase64, langCode = "pa") {
    if (!BHASHINI_API_KEY || !BHASHINI_USER_ID) {
        throw new Error("BHASHINI credentials are not fully configured in .env");
    }

    const cleanLang = (langCode || "pa").split("-")[0].toLowerCase();
    const pipeline = await getBhashiniPipeline("asr", cleanLang);

    const computeRes = await fetch(pipeline.callbackUrl, {
        method: "POST",
        headers: {
            "Content-Type": "application/json",
            [pipeline.authHeaderName]: pipeline.authHeaderValue
        },
        body: JSON.stringify({
            pipelineTasks: [
                {
                    taskType: "asr",
                    config: {
                        language: {
                            sourceLanguage: cleanLang
                        },
                        serviceId: pipeline.serviceId,
                        audioFormat: "wav"
                    }
                }
            ],
            inputData: {
                audio: [
                    {
                        audioContent: audioBase64
                    }
                ]
            }
        })
    });

    if (!computeRes.ok) {
        const errText = await computeRes.text();
        throw new Error(`Bhashini ASR compute failed: ${errText}`);
    }

    const computeData = await computeRes.json();
    const recognizedText = computeData.pipelineResponse?.[0]?.output?.[0]?.source || "";

    return recognizedText;
}

module.exports = {
    getBhashiniPipeline,
    synthesizeSpeech,
    synthesizePunjabiAudio,
    transcribeAudio
};
