/* SAARTHI Agri-Advisor — floating assistant widget.
 *
 * One reusable component, loaded once per page by `chat.js`. It self-mounts, so
 * there is no per-page HTML to duplicate across /, /farmer, /timeline, /map,
 * /intelligence and /cropatlas.
 *
 * It reads the farmer's current selection from SaarthiGeo (the same persisted
 * selection the other pages use), so the assistant always knows which block
 * it is answering for without a second selection UI. English only.
 */

const CHAT_API = '/api/chat';
const VOICE_TRANSCRIBE_API = '/api/voice/transcribe';
const VOICE_SPEAK_API = '/api/voice/speak';
const FARMER_STORAGE_KEY = 'saarthi_farmer_id';

const CHAT_I18N = {
  en: {
    open: 'Open Saarthi Agri-Advisor',
    title: 'Saarthi Agri-Advisor',
    greeting: "Namaste! I'm Saarthi Agri-Advisor. Ask me about your crop, weather, "
      + 'field conditions, or farming decisions.',
    placeholder: 'Ask about your crop, weather or field work…',
    send: 'Send message',
    close: 'Close chat',
    starter: 'Try asking',
    you: 'You',
    advisor: 'Saarthi Agri-Advisor',
    modeGemini: 'Answered by Saarthi Agri-Advisor (Gemini)',
    modeLocal: 'Answered from the local SAARTHI reference (offline answer)',
    thinking: 'Thinking…',
    error: 'Sorry, the advisor could not be reached. Please try again.',
    tooLong: 'Your question is too long. Please shorten it.',
    empty: 'Please type a question first.',
    micStart: 'Start voice input',
    micStop: 'Stop voice input',
    micListening: 'Listening... speak now',
    micUnsupported: 'Voice input is not supported in this browser. Please type your question.',
    micDenied: 'Microphone access was blocked. Please allow the microphone or type your question.',
    micError: 'Voice input failed. Please type your question.',
    speakReply: 'Speak reply aloud',
    stopSpeaking: 'Stop speaking',
    speechUnsupported: 'Voice output is not supported in this browser.',
    hearAgain: 'Hear again',
    hearAgainNone: 'There is no reply to replay yet. Ask a question first.',
    voiceServerDown: 'Server voice is unavailable; using browser voice instead.',
    transcribing: 'Transcribing voice…',
    micRecording: 'Recording... tap again to stop and transcribe',
    micStart: 'Start voice input',
    micStop: 'Stop voice input',
    micListening: 'Listening... speak now',
    micUnsupported: 'Voice input is not supported in this browser. Please type your question.',
    micDenied: 'Microphone access was blocked. Please allow the microphone or type your question.',
    micError: 'Voice input failed. Please type your question.',
    speakReply: 'Speak reply aloud',
    stopSpeaking: 'Stop speaking',
    speechUnsupported: 'Voice output is not supported in this browser.',
  },
};

const STARTERS_EN = [
  'What should I do if heavy rain is forecast?',
  'Is this a good time for field work?',
  'Explain my forecast simply',
  'What should I consider before sowing?',
];

const chatState = { open: false, busy: false, turns: 0, lastMode: null };

/* ------------------------------------------------------------------ */
/* Helpers                                                             */
/* ------------------------------------------------------------------ */

/* UI is English only (translation feature removed). Voice stays en-IN via Bhashini/browser. */
function chatLang() {
  return 'en';
}

function chatT(key) {
  const table = CHAT_I18N[chatLang()] || CHAT_I18N.en;
  return table[key] || CHAT_I18N.en[key] || key;
}

/** BCP-47 tag for server voice + browser speech (English only). */
function chatLangTag() {
  return 'en-IN';
}

/**
 * Optional farmer profile id, persisted across pages. Set it once (e.g. from
 * the farmer portal) and every chat request carries it so the stored
 * crop/location fills any blank context. Blank = anonymous, as before.
 */
function chatFarmerId() {
  try {
    if (typeof window.SaarthiFarmerId === 'string' && window.SaarthiFarmerId.trim()) {
      return window.SaarthiFarmerId.trim().slice(0, 64);
    }
    const stored = localStorage.getItem(FARMER_STORAGE_KEY);
    if (stored && stored.trim()) return stored.trim().slice(0, 64);
  } catch (e) { /* storage unavailable */ }
  return null;
}

function chatEscape(s) {
  return String(s === null || s === undefined ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

/** The farmer's current selection, if the shared geography module knows one. */
function chatSelection() {
  try {
    if (typeof SaarthiGeo !== 'undefined' && typeof SaarthiGeo.loadSelection === 'function') {
      const s = SaarthiGeo.loadSelection();
      if (s && (s.block_name || s.block_code)) {
        return {
          state: s.state_code || null,
          district: s.district_code || null,
          block: s.block_code || s.block_name || null,
        };
      }
    }
  } catch (e) { /* no persisted selection */ }
  return { state: null, district: null, block: null };
}

/* ------------------------------------------------------------------ */
/* Mounting                                                            */
/* ------------------------------------------------------------------ */

function chatMount() {
  if (document.getElementById('saarthi-chat-root')) return;

  const root = document.createElement('div');
  root.id = 'saarthi-chat-root';
  root.innerHTML = ''
    + '<button id="saarthi-chat-fab" class="sa-chat-fab" type="button" aria-expanded="false"'
    + '   aria-controls="saarthi-chat-panel" aria-label="' + chatEscape(chatT('open')) + '">'
    + '  <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">'
    + '    <path d="M12 3c-4.97 0-9 3.36-9 7.5 0 2.3 1.25 4.35 3.2 5.73L5.5 20.5'
    + '      l3.9-1.9c.86.2 1.76.3 2.7.3 4.97 0 9-3.36 9-7.5S16.97 3 12 3z"/>'
    + '  </svg>'
    + '</button>'

    + '<section id="saarthi-chat-panel" class="sa-chat-panel" role="dialog"'
    + '   aria-modal="false" aria-labelledby="saarthi-chat-title" hidden>'
    + '  <header class="sa-chat-head">'
    + '    <h2 id="saarthi-chat-title" class="sa-chat-title">Saarthi Agri-Advisor</h2>'
    + '    <button id="saarthi-chat-close" class="sa-chat-close" type="button" aria-label="'
    + '      ' + chatEscape(chatT('close')) + '">&times;</button>'
    + '  </header>'
    + '  <div id="saarthi-chat-greeting" class="sa-chat-greeting" aria-hidden="true"></div>'
    + '  <div id="saarthi-chat-log" class="sa-chat-log" role="log" aria-live="polite"></div>'
    + '  <div id="saarthi-chat-starters" class="sa-chat-starters"></div>'
    + '  <form id="saarthi-chat-form" class="sa-chat-form">'
    + '    <button id="saarthi-chat-mic" class="sa-chat-mic" type="button"'
    + '      aria-label="' + chatEscape(chatT('micStart')) + '">' 
    + '      <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">'
    + '        <path d="M12 15a3.5 3.5 0 0 0 3.5-3.5v-5a3.5 3.5 0 1 0-7 0v5A3.5 3.5 0 0 0 12 15zm6-3.5a6 6 0 0 1-12 0H4a8 8 0 0 0 7 7.94V22h2v-2.06A8 8 0 0 0 20 11.5h-2z"/></svg>'
    + '    </button>'
    + '    <button id="saarthi-chat-hear" class="sa-chat-hear" type="button"'
    + '      aria-label="' + chatEscape(chatT('hearAgain')) + '"'
    + '      title="' + chatEscape(chatT('hearAgain')) + '">'
    + '      <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">'
    + '        <path d="M12 5V1L6 6l6 5V7a6 6 0 1 1-6 6H4a8 8 0 1 0 8-8z"/></svg>'
    + '    </button>'
    + '    <input id="saarthi-chat-input" class="sa-chat-input" type="text" autocomplete="off"'
    + '      maxlength="2000" aria-label="' + chatEscape(chatT('placeholder')) + '">'
    + '    <button id="saarthi-chat-send" class="sa-chat-send" type="submit"'
    + '      aria-label="' + chatEscape(chatT('send')) + '">'
    + '      <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">'
    + '        <path d="M3 20.5l19-8.5-19-8.5 4 8.5-4 8.5z"/></svg>'
    + '    </button>'
    + '  </form>'
    + '</section>';
  document.body.appendChild(root);

  const fab = document.getElementById('saarthi-chat-fab');
  const panel = document.getElementById('saarthi-chat-panel');
  const close = document.getElementById('saarthi-chat-close');
  const form = document.getElementById('saarthi-chat-form');
  const input = document.getElementById('saarthi-chat-input');
  const send = document.getElementById('saarthi-chat-send');
  const mic = document.getElementById('saarthi-chat-mic');
  const log = document.getElementById('saarthi-chat-log');
  const starters = document.getElementById('saarthi-chat-starters');

  fab.addEventListener('click', () => chatToggle(true));
  close.addEventListener('click', () => chatToggle(false));
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && chatState.open) chatToggle(false);
  });
  form.addEventListener('submit', (e) => {
    e.preventDefault();
    chatSend();
  });

  // Keep the FAB label and panel strings in step with the language.
  window.addEventListener('languageChanged', () => {
    fab.setAttribute('aria-label', chatT('open'));
    close.setAttribute('aria-label', chatT('close'));
    input.setAttribute('aria-label', chatT('placeholder'));
    send.setAttribute('aria-label', chatT('send'));
    if (mic) mic.setAttribute('aria-label', chatT('micStart'));
    chatRenderGreeting();
    chatRenderStarters();
    if (chatState.lastMode) chatRenderMode(chatState.lastMode);
  });

  /** Re-renders the greeting and the mode label; never rewrites past replies. */
  function chatRenderGreeting() {
    const g = document.getElementById('saarthi-chat-greeting');
    if (g) g.textContent = chatT('greeting');
  }

  function chatRenderStarters() {
    const list = STARTERS_EN;
    starters.innerHTML = '<p class="sa-chat-starters-label">' + chatEscape(chatT('starter'))
      + '</p><div class="sa-chat-starter-row">'
      + list.map((q) => '<button type="button" class="sa-chat-starter" data-q="'
        + chatEscape(q) + '">' + chatEscape(q) + '</button>').join('')
      + '</div>';
    starters.querySelectorAll('.sa-chat-starter').forEach((b) => {
      // Starter prompts fill the input; they never auto-send.
      b.addEventListener('click', () => {
        input.value = b.getAttribute('data-q') || '';
        input.focus();
      });
    });
  }

  function chatRenderMode(mode) {
    let el = document.getElementById('saarthi-chat-mode');
    if (!el) {
      el = document.createElement('p');
      el.id = 'saarthi-chat-mode';
      el.className = 'sa-chat-mode';
      log.parentNode.insertBefore(el, starters);
    }
    el.className = 'sa-chat-mode ' + (mode === 'gemini' ? 'is-gemini' : 'is-local');
    el.textContent = mode === 'gemini' ? chatT('modeGemini') : chatT('modeLocal');
  }

  function chatAppend(who, text) {
    const wrap = document.createElement('div');
    wrap.className = 'sa-chat-msg sa-chat-msg-' + who;
    const bubble = document.createElement('div');
    bubble.className = 'sa-chat-bubble';
    // The assistant's text is rendered as text, never as HTML.
    bubble.textContent = text;
    wrap.appendChild(bubble);
    if (who === 'bot') {
      chatState.lastReply = text;
      const speak = document.createElement('button');
      speak.type = 'button';
      speak.className = 'sa-chat-speak';
      speak.setAttribute('aria-label', chatT('speakReply'));
      speak.textContent = '\uD83D\uDD0A';
      speak.addEventListener('click', () => chatSpeakToggle(text, speak));
      wrap.appendChild(speak);
    }
    log.appendChild(wrap);
    log.scrollTop = log.scrollHeight;
  }

  /* ---- voice (browser Web Speech first, Bhashini server voice as fallback) ----
   *
   * Text chat never depends on any of this. English/Hindi browser speech is
   * kept as the primary path; the server Bhashini endpoints add transcription
   * where Web Speech is missing and Indic TTS playback with a "Hear Again"
   * replay. Telugu/Punjabi exist in the server voice layer only — the visible
   * UI language switcher stays English + Hindi.
   */

  const ChatSpeech = ('SpeechRecognition' in window) ? window.SpeechRecognition
    : (('webkitSpeechRecognition' in window) ? window.webkitSpeechRecognition : null);
  let chatRecognition = null;
  let chatListening = false;
  let chatSpeaking = false;
  let chatAudioEl = null;
  let chatRecorder = null;
  let chatRecordChunks = [];

  function chatRecogLang() {
    return chatLangTag();
  }

  function chatMicLabel() {
    if (!mic) return;
    mic.setAttribute('aria-label', chatListening ? chatT('micStop') : chatT('micStart'));
    mic.classList.toggle('is-listening', chatListening);
  }

  /** Transcribe busy state: mic pulses, input shows what is happening.
   * WAV conversion + the ASR round-trip take seconds with no other signal. */
  function chatTranscribing(on) {
    if (mic) {
      if (on) mic.setAttribute('aria-busy', 'true');
      else mic.removeAttribute('aria-busy');
    }
    if (on) {
      if (input && input.dataset.saPh === undefined) {
        input.dataset.saPh = input.placeholder || '';
      }
      if (input) {
        input.placeholder = chatT('transcribing');
        input.disabled = true;
      }
    } else if (input) {
      input.disabled = false;
      if (input.dataset.saPh !== undefined) {
        input.placeholder = input.dataset.saPh;
        delete input.dataset.saPh;
      }
    }
  }

  function chatMicToggle() {
    if (!ChatSpeech) {
      // No Web Speech here: record and let the server transcribe (Bhashini).
      chatBhashiniRecordToggle();
      return;
    }
    if (chatListening && chatRecognition) {
      try { chatRecognition.stop(); } catch (e) { /* already stopped */ }
      return;
    }
    try {
      chatRecognition = new ChatSpeech();
    } catch (e) {
      chatAppend('bot', chatT('micError'));
      return;
    }
    chatRecognition.lang = chatRecogLang();
    chatRecognition.continuous = false;
    chatRecognition.interimResults = false;
    chatRecognition.onresult = (event) => {
      let transcript = '';
      try {
        const res = event.results && event.results[0] && event.results[0][0];
        transcript = (res && res.transcript) ? res.transcript : '';
      } catch (e) { transcript = ''; }
      transcript = (transcript || '').trim();
      if (transcript) {
        input.value = transcript;
        chatSend();
      }
    };
    chatRecognition.onerror = (event) => {
      const code = event && event.error;
      if (code === 'not-allowed' || code === 'service-not-allowed') {
        chatAppend('bot', chatT('micDenied'));
      } else if (code === 'abort' || code === 'aborted') {
        /* user stopped; stay silent */
      } else {
        chatAppend('bot', chatT('micError'));
      }
    };
    chatRecognition.onend = () => {
      chatListening = false;
      chatMicLabel();
    };
    try {
      chatRecognition.start();
      chatListening = true;
      chatMicLabel();
    } catch (e) {
      chatListening = false;
      chatMicLabel();
      chatAppend('bot', chatT('micError'));
    }
  }

  function chatStopSpeak() {
    try {
      if (chatAudioEl) {
        chatAudioEl.pause();
        chatAudioEl = null;
      }
    } catch (e) { /* no audio element */ }
    try {
      if ('speechSynthesis' in window) window.speechSynthesis.cancel();
    } catch (e) { /* no speech engine */ }
    chatSpeaking = false;
    document.querySelectorAll('.sa-chat-speak.is-speaking').forEach((b) => {
      b.classList.remove('is-speaking');
      b.setAttribute('aria-label', chatT('speakReply'));
    });
  }

  /** Server TTS via Bhashini; true when audio playback started. */
  async function chatPlayBhashini(text, btn) {
    let data = null;
    try {
      const res = await fetch(VOICE_SPEAK_API, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify({ text: text, language: chatLangTag() }),
      });
      data = await res.json().catch(() => null);
    } catch (e) {
      data = null;
    }
    if (!data || !data.available || !data.audio) return false;
    try {
      chatStopSpeak();
      const el = new Audio('data:audio/wav;base64,' + data.audio);
      chatAudioEl = el;
      chatSpeaking = true;
      if (btn) {
        btn.classList.add('is-speaking');
        btn.setAttribute('aria-label', chatT('stopSpeaking'));
      }
      el.onended = () => chatStopSpeak();
      el.onerror = () => chatStopSpeak();
      await el.play();
      return true;
    } catch (e) {
      chatStopSpeak();
      return false;
    }
  }

  /** Browser speech synthesis fallback (English/Hindi). */
  function chatPlayBrowser(text, btn) {
    if (!('speechSynthesis' in window) || typeof SpeechSynthesisUtterance === 'undefined') {
      chatAppend('bot', chatT('speechUnsupported'));
      return;
    }
    try {
      window.speechSynthesis.cancel();
      const utter = new SpeechSynthesisUtterance(text);
      utter.lang = chatRecogLang();
      utter.onstart = () => {
        chatSpeaking = true;
        if (btn) {
          btn.classList.add('is-speaking');
          btn.setAttribute('aria-label', chatT('stopSpeaking'));
        }
      };
      const done = () => chatStopSpeak();
      utter.onend = done;
      utter.onerror = done;
      window.speechSynthesis.speak(utter);
    } catch (e) {
      chatAppend('bot', chatT('speechUnsupported'));
    }
  }

  function chatSpeakToggle(text, btn) {
    if (chatSpeaking) {
      chatStopSpeak();
      return;
    }
    // Prefer server Indic voice; fall back to browser speech silently.
    chatPlayBhashini(text, btn).then((played) => {
      if (!played && !chatSpeaking) chatPlayBrowser(text, btn);
    });
  }

  /** Replay the latest assistant reply ("Hear Again"). */
  function chatHearAgain() {
    if (chatSpeaking) {
      chatStopSpeak();
      return;
    }
    const last = chatState.lastReply;
    if (!last) {
      chatAppend('bot', chatT('hearAgainNone'));
      return;
    }
    chatSpeakToggle(last, null);
  }

  /* ---- server transcription fallback (mic → 16 kHz mono WAV → Bhashini ASR) ----
   *
   * Bhashini ASR expects WAV/PCM audio, but MediaRecorder produces WebM.
   * The recorded blob is therefore decoded with the browser-native
   * AudioContext, resampled to 16 kHz mono via OfflineAudioContext, and
   * encoded as 16-bit PCM WAV — no audio library, no new dependency. Any
   * failure degrades to a typed message; text chat is never affected.
   */

  /* WAV-ENCODER-START (pure function: also verified in Node, see report) */
  function chatEncodeWavPCM16(samples, sampleRate) {
    const n = samples ? samples.length : 0;
    const rate = sampleRate > 0 ? Math.floor(sampleRate) : 16000;
    const bytes = new Uint8Array(44 + n * 2);
    const v = new DataView(bytes.buffer);
    const wstr = (o, s) => {
      for (let i = 0; i < s.length; i++) v.setUint8(o + i, s.charCodeAt(i));
    };
    wstr(0, 'RIFF');
    v.setUint32(4, 36 + n * 2, true);
    wstr(8, 'WAVE');
    wstr(12, 'fmt ');
    v.setUint32(16, 16, true);
    v.setUint16(20, 1, true); // PCM
    v.setUint16(22, 1, true); // mono
    v.setUint32(24, rate, true);
    v.setUint32(28, rate * 2, true); // byte rate: sampleRate * channels * bytes
    v.setUint16(32, 2, true); // block align
    v.setUint16(34, 16, true); // bits per sample
    wstr(36, 'data');
    v.setUint32(40, n * 2, true);
    for (let i = 0; i < n; i++) {
      let s = samples[i];
      if (s > 1) s = 1;
      else if (s < -1) s = -1;
      v.setInt16(44 + i * 2, s < 0 ? Math.round(s * 0x8000) : Math.round(s * 0x7fff), true);
    }
    return bytes;
  }
  /* WAV-ENCODER-END */

  function chatBase64OfBytes(bytes) {
    let bin = '';
    const CHUNK = 0x8000;
    for (let i = 0; i < bytes.length; i += CHUNK) {
      bin += String.fromCharCode.apply(null, bytes.subarray(i, i + CHUNK));
    }
    return btoa(bin);
  }

  /**
   * Convert a recorded WebM blob to base64 16-bit PCM WAV (16 kHz mono).
   * Rejects when the browser cannot decode/convert — the caller shows a
   * graceful message instead of sending incompatible audio.
   */
  async function chatBlobToWavBase64(blob) {
    const buf = await blob.arrayBuffer();
    if (!buf || !buf.byteLength) throw new Error('empty-audio');
    const AC = window.AudioContext || window.webkitAudioContext;
    if (!AC || typeof OfflineAudioContext === 'undefined') throw new Error('no-audio-context');
    const actx = new AC();
    let decoded;
    try {
      decoded = await actx.decodeAudioData(buf);
    } finally {
      try { await actx.close(); } catch (e) { /* already closed */ }
    }
    if (!decoded || !decoded.duration || !isFinite(decoded.duration) || decoded.duration > 120) {
      throw new Error('bad-audio');
    }
    const TARGET_RATE = 16000;
    const frames = Math.max(1, Math.ceil(decoded.duration * TARGET_RATE));
    const OfflineAC = window.OfflineAudioContext || window.webkitOfflineAudioContext;
    const off = new OfflineAC(1, frames, TARGET_RATE);
    const src = off.createBufferSource();
    src.buffer = decoded;
    // Stereo/multi-channel input is down-mixed to the mono destination.
    src.connect(off.destination);
    src.start(0);
    const rendered = await off.startRendering();
    if (!rendered || rendered.length < TARGET_RATE / 4) throw new Error('too-short');
    const pcm = chatEncodeWavPCM16(rendered.getChannelData(0), TARGET_RATE);
    return chatBase64OfBytes(pcm);
  }

  function chatBhashiniRecordToggle() {
    if (chatRecorder) {
      try { chatRecorder.stop(); } catch (e) { chatRecorder = null; }
      return;
    }
    if (!navigator.mediaDevices || typeof MediaRecorder === 'undefined') {
      chatAppend('bot', chatT('micUnsupported'));
      return;
    }
    navigator.mediaDevices.getUserMedia({ audio: true }).then((stream) => {
      try {
        chatRecordChunks = [];
        chatRecorder = new MediaRecorder(stream);
      } catch (e) {
        chatRecorder = null;
        chatAppend('bot', chatT('micError'));
        return;
      }
      chatListening = true;
      chatMicLabel();
      chatRecorder.ondataavailable = (e) => {
        if (e.data && e.data.size) chatRecordChunks.push(e.data);
      };
      chatRecorder.onstop = () => {
        chatListening = false;
        chatMicLabel();
        stream.getTracks().forEach((t) => { try { t.stop(); } catch (e) {} });
        chatRecorder = null;
        const blob = new Blob(chatRecordChunks, { type: 'audio/webm' });
        chatRecordChunks = [];
        if (!blob.size) {
          chatAppend('bot', chatT('micError'));
          return;
        }
        // WebM → 16 kHz mono PCM WAV first: the transcribe contract is
        // unchanged ({audio base64, language}) but the payload is now real WAV.
        chatTranscribing(true);
        chatBlobToWavBase64(blob).then((base64) => {
          if (!base64) {
            chatTranscribing(false);
            chatAppend('bot', chatT('micError'));
            return;
          }
          fetch(VOICE_TRANSCRIBE_API, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
            body: JSON.stringify({ audio: base64, language: chatLangTag(), audioFormat: 'wav' }),
          }).then((res) => res.json().catch(() => ({}))).then((data) => {
            const transcript = data && data.text ? String(data.text).trim() : '';
            chatTranscribing(false);
            if (transcript) {
              input.value = transcript;
              chatSend();
            } else {
              chatAppend('bot', chatT('micError'));
            }
          }).catch(() => {
            chatTranscribing(false);
            chatAppend('bot', chatT('micError'));
          });
        }).catch(() => {
          chatTranscribing(false);
          chatAppend('bot', chatT('micError'));
        });
      };
      chatRecorder.start();
    }).catch(() => chatAppend('bot', chatT('micDenied')));
  }

  window.addEventListener('languageChanged', () => {
    document.querySelectorAll('.sa-chat-speak').forEach((b) => {
      b.setAttribute('aria-label', chatT('speakReply'));
    });
    const hear = document.getElementById('saarthi-chat-hear');
    if (hear) {
      hear.setAttribute('aria-label', chatT('hearAgain'));
      hear.setAttribute('title', chatT('hearAgain'));
    }
  });
  if (mic) {
    mic.addEventListener('click', chatMicToggle);
    chatMicLabel();
  }
  const hearBtn = document.getElementById('saarthi-chat-hear');
  if (hearBtn) hearBtn.addEventListener('click', chatHearAgain);


  async function chatSend() {
    if (chatState.busy) return;
    chatStopSpeak();
    const text = (input.value || '').trim();
    if (!text) {
      chatAppend('bot', chatT('empty'));
      return;
    }
    if (text.length > 2000) {
      chatAppend('bot', chatT('tooLong'));
      return;
    }
    chatState.busy = true;
    chatState.turns += 1;
    // Visual lock matches the logic guard above: no double-submit while the
    // assistant is thinking. The input stays editable for follow-ups.
    send.disabled = true;
    input.value = '';
    starters.innerHTML = '';
    chatAppend('user', text);

    const busy = document.createElement('div');
    busy.id = 'saarthi-chat-busy';
    busy.className = 'sa-chat-msg sa-chat-msg-bot';
    busy.innerHTML = '<div class="sa-chat-bubble sa-chat-thinking">'
      + '<span class="sa-chat-dot"></span><span class="sa-chat-dot"></span>'
      + '<span class="sa-chat-dot"></span>'
      + '<span class="sa-chat-sr">' + chatEscape(chatT('thinking')) + '</span></div>';
    log.appendChild(busy);
    log.scrollTop = log.scrollHeight;

    const sel = chatSelection();
    const farmerId = chatFarmerId();
    const payload = {
      message: text,
      context: {
        state: sel.state,
        district: sel.district,
        block: sel.block,
        crop: chatCrop(),
        language: chatLang(),
      },
    };
    // Stored profile fills any blank context field server-side; absent = anonymous.
    if (farmerId) payload.farmerId = farmerId;

    try {
      const res = await fetch(CHAT_API, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify(payload),
      });
      const data = await res.json().catch(() => ({}));
      if (busy.parentNode) busy.parentNode.removeChild(busy);
      if (!res.ok) {
        chatAppend('bot', (data && data.message) ? data.message : chatT('error'));
        return;
      }
      chatState.lastMode = data.mode || 'local_fallback';
      chatAppend('bot', data.reply || chatT('error'));
      chatRenderMode(chatState.lastMode);
    } catch (e) {
      if (busy.parentNode) busy.parentNode.removeChild(busy);
      chatAppend('bot', chatT('error'));
    } finally {
      chatState.busy = false;
      send.disabled = false;
    }
  }

  function chatToggle(open) {
    chatState.open = open;
    panel.hidden = !open;
    fab.setAttribute('aria-expanded', String(open));
    fab.classList.toggle('is-open', open);
    if (open) {
      chatRenderGreeting();
      chatRenderStarters();
      // Delay focus so the panel is visible and laid out first, which keeps
      // mobile browsers from jumping the viewport.
      setTimeout(() => input.focus(), 60);
    }
  }

  chatRenderGreeting();
  chatRenderStarters();
  chatToggle(false);
}

/** The crop currently in focus, when a page has one. */
function chatCrop() {
  try {
    if (typeof lastCaSelection !== 'undefined' && lastCaSelection
        && lastCaSelection.payload) {
      const g = lastCaSelection.payload.groups;
      if (g && g.potential_matches && g.potential_matches.length) {
        const c = g.potential_matches[0].crop;
        if (c && c.crop_name) return c.crop_name;
      }
    }
  } catch (e) { /* no crop context on this page */ }
  return null;
}

/* Public hooks: let the farmer portal attach a stored profile id. */
try {
  window.SaarthiChat = window.SaarthiChat || {};
  window.SaarthiChat.setFarmerId = function (id) {
    try {
      if (id && String(id).trim()) {
        localStorage.setItem(FARMER_STORAGE_KEY, String(id).trim().slice(0, 64));
      } else {
        localStorage.removeItem(FARMER_STORAGE_KEY);
      }
    } catch (e) { /* storage unavailable */ }
  };
  window.SaarthiChat.getFarmerId = chatFarmerId;
} catch (e) { /* non-browser context */ }

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', chatMount);
} else {
  chatMount();
}
