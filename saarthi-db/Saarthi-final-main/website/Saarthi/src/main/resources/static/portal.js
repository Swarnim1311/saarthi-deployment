/**
 * SAARTHI Decision Platform Logic (live-forecast integration).
 * Vanilla JS VIEW over Spring Boot REST APIs backed by the LIVE
 * Open-Meteo/ECMWF-IFS feed (6 Sangrur blocks, D+1..D+16), with the
 * 17-30 day climatological outlook kept strictly separate.
 * Observed rainfall (CHIRPS) is OPTIONAL display context only: when
 * unavailable the UI reports "Observed rainfall context unavailable" and
 * the live forecast keeps working.
 * No forecasting is computed here. No synthetic fallback: API failure shows
 * an explicit error message.
 */

let activeRoute = location.pathname.slice(1) || 'farmer';
if (!['farmer', 'map', 'timeline'].includes(activeRoute)) {
  activeRoute = 'farmer';
}

let currentFarmerData = null;
let leafletMapInstance = null;
let mapGeoLayer = null;
let blockForecastCache = {};   // block_name -> /api/weather/forecast payload
let blocksMetaCache = [];      // [{block_id, block_name, ...}]
let latestCache = null;

// Farmer advisory is block-level: no panchayat selection, no fabricated
// sub-block resolution. (The backend keeps a panchayat display field for
// legacy Sangrur responses; the portal no longer sends or shows one.)

const CATEGORY_COLORS = { LOW: '#e2be62', NORMAL: '#9ed683', HIGH: '#f27256' };

// P0 freshness: same rule as src/utils/forecast_freshness.py and the backend
// (stale = expired OR age_days > 2). Stale forecasts must NEVER render
// identically to fresh ones.
const STALE_AFTER_DAYS = 2;

function freshnessOf(issueDate, validTo) {
  try {
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    const issue = new Date(`${issueDate}T00:00:00`);
    const validEnd = new Date(`${validTo}T00:00:00`);
    if (Number.isNaN(issue.getTime()) || Number.isNaN(validEnd.getTime())) return { unknown: true };
    const ageDays = Math.round((today - issue) / 86400000);
    const expired = today > validEnd;
    return { ageDays, expired, stale: expired || ageDays > STALE_AFTER_DAYS };
  } catch (err) {
    return { unknown: true };
  }
}

function freshnessLabel(issueDate, validFrom, validTo) {
  const f = freshnessOf(issueDate, validTo);
  const base = `Issued ${issueDate} · Valid ${validFrom} – ${validTo}`;
  if (f.unknown) return `${base} · freshness unknown — verify before acting`;
  if (f.expired) return `${base} · EXPIRED (${f.ageDays}d old) — forecast data are outdated`;
  if (f.stale) return `${base} · STALE (${f.ageDays}d old) — may be outdated`;
  return `${base} · fresh (${f.ageDays}d old)`;
}

// Paints a static (portal.html placeholder) freshness banner by element id.
function paintStaticBanner(id, issueDate, validFrom, validTo) {
  const el = document.getElementById(id);
  if (!el) return;
  const f = freshnessOf(issueDate, validTo);
  el.textContent = freshnessLabel(issueDate, validFrom, validTo);
  el.style.background = f.stale ? '#fbe3dc' : '#e4efe0';
  el.style.color = f.stale ? '#a33' : '#3c6e47';
  el.style.border = f.stale ? '1px solid #e0a08e' : '1px solid #b9d2bd';
}

// Freshness banner for the LIVE contract (/api/weather/forecast): the server
// decides staleness (cache age vs TTL); the UI only renders it honestly.
function paintLiveBanner(id, liveEnvelope) {
  const el = document.getElementById(id);
  if (!el) return;
  const stale = !!(liveEnvelope && liveEnvelope.stale);
  const label = `${liveEnvelope.provider || 'Open-Meteo'} / ${liveEnvelope.model || 'ECMWF IFS'} · issued ${liveEnvelope.issue_date} · retrieved ${liveEnvelope.retrieved_at}`
    + (stale ? ' · STALE — may be outdated' : ' · fresh');
  el.textContent = label;
  el.style.background = stale ? '#fbe3dc' : '#e4efe0';
  el.style.color = stale ? '#a33' : '#3c6e47';
  el.style.border = stale ? '1px solid #e0a08e' : '1px solid #b9d2bd';
}

// Live rain category for map colouring: from the 7-day cumulative window of
// the live feed (NOT the frozen training-threshold categories).
function liveCategoryOf(blockName) {
  const f = blockForecastCache[blockName];
  if (!f || !f.rain_7d && f.rain_7d !== 0) return 'nodata';
  if (f.rain_7d < 10) return 'LOW';
  if (f.rain_7d > 35) return 'HIGH';
  return 'NORMAL';
}

// Injects (or updates) a freshness banner as the first child of container.
function renderFreshnessBanner(container, issueDate, validFrom, validTo) {
  if (!container) return;
  const f = freshnessOf(issueDate, validTo);
  let banner = container.querySelector('[data-freshness-banner]');
  if (!banner) {
    banner = document.createElement('p');
    banner.setAttribute('data-freshness-banner', 'true');
    banner.style.cssText = 'margin:0 0 12px;font-size:12.5px;font-weight:700;padding:8px 12px;border-radius:8px;';
    container.prepend(banner);
  }
  banner.textContent = freshnessLabel(issueDate, validFrom, validTo);
  banner.style.background = f.stale ? '#fbe3dc' : '#e4efe0';
  banner.style.color = f.stale ? '#a33' : '#3c6e47';
  banner.style.border = f.stale ? '1px solid #e0a08e' : '1px solid #b9d2bd';
}

// Page Titles and Subtitles (English + Hindi only)
function uiLang() {
  const stored = localStorage.getItem('saarthi_lang');
  return stored === 'hi' ? 'hi' : 'en';
}

// Display-only numeric formatting: API values are never rounded or modified,
// these helpers format for display only so the UI never shows float artefacts
// like 0.20248958333333333 m³/m³. Used as fallback when SaarthiGeo.fmt is
// unavailable; otherwise SaarthiGeo.fmt (same conventions) is preferred.
function fmtNumOrNull(x, digits) {
  if (x === null || x === undefined || !Number.isFinite(Number(x))) return null;
  return Number(Number(x).toFixed(digits));
}
function fmtRainFallback(x) {
  const n = fmtNumOrNull(x, 1);
  return n === null ? 'No data' : `${n} mm`;
}
function fmtSoilFallback(x) {
  if (x === null || x === undefined || !Number.isFinite(Number(x))) return 'No data';
  return `${Number(x).toFixed(2)} m³/m³`;
}
function fmtPct0(x) {
  const n = Number(x);
  return (x === null || x === undefined || !Number.isFinite(n)) ? '—' : `${Math.round(n)}%`;
}
function fmtAnom2(x) {
  if (x === null || x === undefined || !Number.isFinite(Number(x))) return '—';
  return Number(x).toFixed(2);
}
const PAGE_METADATA = {
  farmer: {
    title: { en: 'Farmer <em>advisory.</em>', hi: 'किसान <em>परामर्श पोर्टल।</em>' },
    intro: {
      en: 'Live 16-day ECMWF IFS block outlook plus crop guidance.',
      hi: 'लाइव 16-दिवसीय ECMWF IFS ब्लॉक आउटलुक और फसल मार्गदर्शन।'
    }
  },
  map: {
    title: { en: 'Live <em>risk map.</em>', hi: 'लाइव <em>जोखिम मानचित्र।</em>' },
    intro: {
      en: 'Select a state, district and block to view the current ECMWF IFS outlook and block-level risk.',
      hi: 'वर्तमान ECMWF IFS आउटलुक और ब्लॉक-स्तरीय जोखिम देखने हेतु राज्य, जिला और ब्लॉक चुनें।'
    }
  },
  timeline: {
    title: { en: 'Forecast <em>outlook.</em>', hi: '7 दिन <em>पूर्वानुमान।</em>' },
    intro: {
      en: 'Live 16-day rainfall, field-work risk, climatological weeks 3–4 and climate context per block.',
      hi: 'प्रति ब्लॉक लाइव 16-दिवसीय वर्षा, खेत-कार्य जोखिम, जलवायु सप्ताह 3–4 और जलवायु पृष्ठभूमि।'
    }
  }
};

// Show Toast Notification Helper
function showToast(message) {
  const existing = document.querySelector('.toast-notification');
  if (existing) existing.remove();

  const toast = document.createElement('div');
  toast.className = 'toast-notification';
  toast.innerHTML = `<span>✓</span> <span>${message}</span>`;
  document.body.appendChild(toast);

  setTimeout(() => {
    toast.style.opacity = '0';
    toast.style.transform = 'translateY(20px)';
    toast.style.transition = 'all 0.3s ease';
    setTimeout(() => toast.remove(), 300);
  }, 3500);
}

function apiError(target, message) {
  if (typeof target === 'string') target = document.querySelector(target);
  if (target) target.innerHTML = `<p style="color:#a33;">${message}</p>`;
  showToast(message);
}

// Update Active Navigation Tab and Page Header
function updateRouteUI() {
  document.querySelectorAll('.nav-tabs a').forEach((link) => {
    link.classList.toggle('active', link.getAttribute('href') === `/${activeRoute}`);
  });

  document.querySelectorAll('.portal-page').forEach((section) => {
    section.style.display = section.id === activeRoute ? 'block' : 'none';
  });

  const lang = uiLang();
  const meta = PAGE_METADATA[activeRoute] || PAGE_METADATA.farmer;
  document.querySelector('#page-title').innerHTML = meta.title[lang] || meta.title.en;
  document.querySelector('#page-intro').textContent = meta.intro[lang] || meta.intro.en;
}

// -------------------------------------------------------------
// 1. FARMER ADVISORY PORTAL LOGIC (API-driven)
// -------------------------------------------------------------

// Currently selected registry block on the farmer route (null until chosen).
let farmerSelection = null;

function updateFarmerLocationNote() {
  const note = document.querySelector('#form-location-note');
  if (note) {
    note.textContent = farmerSelection
      ? `${SaarthiGeo.locationLabel(farmerSelection)} · advisory served at block level`
      : '';
  }
}

async function computeFarmerAdvisory() {
  const sel = farmerSelection;
  const crop = document.querySelector('#form-crop')?.value || 'Paddy (PR-126)';
  const soil = document.querySelector('#form-soil')?.value || 'Clay Loam';
  const sowingDate = document.querySelector('#form-date')?.value || '';
  const irrigation = document.querySelector('#form-irrigation')?.value || 'Canals';

  if (!sel) {
    apiError('#decision-explanation', 'Select State → District → Block to compute the advisory.');
    return;
  }
  // Block-level request: empty panchayat so the backend never labels a
  // generic block with another block's village name.
  const payload = {
    block: sel.block_name, panchayat: '', crop, soil,
    sowing_date: sowingDate, irrigation,
  };

  try {
    const res = await fetch('/api/farmer-analysis', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });
    if (res.ok) {
      const data = await res.json();
      currentFarmerData = data;
      renderFarmerAdvisoryView(data);
      return;
    }
    // Non-legacy blocks 404 here (advisory engine covers Sangrur polygons):
    // fall through to the generic block-level view, never another block.
    if (res.status !== 404 && res.status !== 503) {
      const err = await res.json().catch(() => ({}));
      throw new Error(err.message || `HTTP ${res.status}`);
    }
  } catch (err) {
    console.error('Farmer analysis failed:', err);
    apiError('#decision-explanation', 'Live advisory data is currently unavailable. Please try again.');
    return;
  }
  // Generic path: live centroid weather + honest subset, no invented agronomy.
  try {
    const { envelope } = await SaarthiGeo.fetchBlockForecast(sel);
    currentFarmerData = null;
    renderGenericAdvisoryView(sel, envelope, { crop, irrigation, sowingDate });
  } catch (err) {
    console.error('Generic advisory failed:', err);
    apiError('#decision-explanation', 'Live advisory data is currently unavailable. Please try again.');
  }
}

/**
 * Block-level advisory for non-Sangrur registry blocks: location, live
 * weather and soil shown factually; crop-stage/risk sections are marked as
 * Sangrur-polygon-only instead of being invented.
 */
function renderGenericAdvisoryView(sel, env, inputs) {
  const lang = uiLang();
  const noData = '<span style="color:#a33;">No data</span>';
  const b = (env && env.block) || {};
  const label = SaarthiGeo.locationLabel(sel);
  const days = b.days || [];
  const rain7 = b.cum_7d_mm && b.cum_7d_mm.available ? b.cum_7d_mm.rainfall_mm : null;
  const et0 = b.et0_7d_mm && b.et0_7d_mm.available ? b.et0_7d_mm.rainfall_mm : null;
  const wet = days.filter((d) => (d.rainfall_mm || 0) >= 1).length;
  const heavy = days.reduce((a, d) => ((d.rainfall_mm || 0) > (a.rainfall_mm || 0) ? d : a), days[0] || {});
  const sm = b.soil_moisture_0_to_7cm_pct;
  const smv = sm && sm.available ? sm.value : null;
  const soilLine = env.soil && env.soil.available && env.soil.line
    ? env.soil.line
    : 'Soil data unavailable for this block';

  const set = (id, text) => { const el = document.querySelector(id); if (el) el.textContent = text; };
  const setHtml = (id, html) => { const el = document.querySelector(id); if (el) el.innerHTML = html; };
  const F = (typeof SaarthiGeo !== 'undefined' && SaarthiGeo.fmt) || null;
  const rainTxt = (x) => (F ? F.rain(x) : fmtRainFallback(x));
  const soilTxt = (x) => (F ? F.soil(x) : fmtSoilFallback(x));
  set('#decision-headline', `${inputs.crop} — ${label}`);
  set('#decision-confidence', `${env.provider} / ${env.model} · issued ${env.issue_date} · block-level forecast`);
  set('#decision-location', label);
  set('#decision-explanation',
    `Block-level outlook for ${label}. ` +
    `7-day rainfall ${rainTxt(rain7)}, ` +
    `${wet} wet days (≥1 mm) in the 16-day horizon. ` +
    `Crop-stage and dry-spell risk sections are computed for supported polygon blocks; ` +
    `the live weather and soil below cover ${sel.block_name}.`);
  const tagEl = document.querySelector('#decision-tag');
  if (tagEl) { tagEl.textContent = 'Block-level outlook'; tagEl.className = 'decision-tag review'; }
  set('#decision-window', 'Stage-specific window: supported polygon blocks');
  set('#decision-prob', '');
  setHtml('#decision-prob',
    `7-day rain: <b>${rainTxt(rain7)}</b>` +
    ` · ET0 7-day: <b>${rainTxt(et0)}</b>`);
  set('#gauge-status', smv == null ? 'Forecast surface soil moisture: No data' : `Forecast surface soil moisture: ${soilTxt(smv)} (ECMWF IFS, 0–7 cm — not a measurement)`);
  const gaugePct = document.querySelector('#gauge-percent');
  if (gaugePct) gaugePct.textContent = smv == null ? '—' : soilTxt(smv);
  set('#pillar-weather-val', rain7 == null ? 'No data' : `${rainTxt(rain7)} / 7d`);
  set('#pillar-soil-val', smv == null ? 'No data' : 'Forecast (see gauge)');
  set('#pillar-crop-val', 'Advisory: supported blocks');
  set('#pillar-dry-val', `${wet} wet days / 16d`);
  setHtml('#advisory-actions',
    `<ul><li>Heaviest forecast day: ${heavy && heavy.date ? `${rainTxt(heavy.rainfall_mm)} (${heavy.date})` : 'No data'}.</li>` +
    `<li>${soilLine}.</li>` +
    `<li>Irrigation input on file: ${inputs.irrigation}; sowing date on file: ${inputs.sowingDate || 'not set'}. Stage-specific guidance is computed for supported polygon blocks.</li></ul>`);
  setHtml('#advisory-sources',
    `<ul><li>Open-Meteo API delivering ECMWF IFS — GET /api/weather/forecast/by-coords (single-point centroid, not polygon-averaged)</li>` +
    `<li>${soilLine}</li></ul>`);
  const shareBtn = document.querySelector('#share-whatsapp-btn');
  if (shareBtn) {
    const lines = [
      `🌾 *SAARTHI KISAN ADVISORY*`,
      `📍 ${label}`,
      `🌱 ${inputs.crop}`,
      `🌧 7-day rain: ${rainTxt(rain7)}`,
      `💧 ${soilLine}`,
      `— SAARTHI (ECMWF IFS live forecast, block level)`,
    ];
    shareBtn.onclick = async () => {
      try {
        await navigator.clipboard.writeText(lines.join('\n'));
        showToast(getTranslation('btn_copied', lang));
      } catch (e) {
        showToast('Advisory text copied!');
      }
    };
  }
}

function renderFarmerAdvisoryView(data) {
  if (!data || !data.location) return;
  const lang = uiLang();
  const noData = '<span style="color:#a33;">No data</span>';
  const v = (x) => (x === null || x === undefined ? noData : x);
  const F = (typeof SaarthiGeo !== 'undefined' && SaarthiGeo.fmt) || null;
  const rainTxt = (x) => (F ? F.rain(x) : fmtRainFallback(x));
  const soilTxt = (x) => (F ? F.soil(x) : fmtSoilFallback(x));

  // Header: crop + block + provenance (section 1)
  const cropName = (data.inputs && data.inputs.crop) || '';
  const placeLabel = (data.location.panchayat_label || '').trim()
    || `${data.location.block} block`;
  const hlEl = document.querySelector('#decision-headline');
  if (hlEl) hlEl.textContent = `${cropName} — ${placeLabel}`;

  const confEl = document.querySelector('#decision-confidence');
  if (confEl) confEl.textContent =
    `${data.location.provider} / ${data.location.model} · issued ${data.location.forecast_issue_date} · block-level forecast`;

  const targetEl = document.querySelector('#decision-location');
  if (targetEl) targetEl.textContent = placeLabel;

  const expEl = document.querySelector('#decision-explanation');
  if (expEl) expEl.textContent =
    (data.location.resolution_note || '') + ' ' +
    ((data.advisory && data.advisory.explanation) || '');

  // Stage (section 3)
  const st = data.stage || {};
  const tagEl = document.querySelector('#decision-tag');
  if (tagEl) {
    tagEl.textContent = st.available
      ? `Stage: ${String(st.stage).replace(/_/g, ' ')} (day ${st.days_since_sowing})`
      : (st.days_since_sowing != null
        ? `Day ${st.days_since_sowing} — stage not available`
        : 'Set sowing date for stage guidance');
    tagEl.className = `decision-tag ${st.available ? 'sow' : 'review'}`;
  }

  const winEl = document.querySelector('#decision-window');
  if (winEl) {
    const w = data.crop && data.crop.sowing_window;
    winEl.textContent = (data.crop && data.crop.known && w && w.start)
      ? `Cited sowing window: ${w.start} → ${w.end || '-'}`
      : 'Sowing window not available for this crop';
  }

  // Weather snapshot (section 4)
  const wx = data.weather || {};
  const probEl = document.querySelector('#decision-prob');
  if (probEl) {
    probEl.innerHTML =
      `7-day rain: <b>${v(wx.rain_7d_mm != null ? rainTxt(wx.rain_7d_mm) : null)}</b>` +
      ` · ET0 7-day: <b>${v(wx.et0_7d_mm != null ? rainTxt(wx.et0_7d_mm) : null)}</b>`;
  }

  // Soil (section 5) — forecast surface moisture, honest units
  const soil = data.soil || {};
  const gaugePct = document.querySelector('#gauge-percent');
  const gaugeDial = document.querySelector('#root-gauge-dial');
  const gStatus = document.querySelector('#gauge-status');
  const smv = soil.forecast_surface_soil_moisture_vwc;
  if (gStatus) {
    gStatus.textContent = smv == null
      ? 'Forecast surface soil moisture: No data'
      : `Forecast surface soil moisture: ${soilTxt(smv)} (ECMWF IFS, 0–7 cm — not a measurement)`;
  }
  if (gaugePct) gaugePct.textContent = smv == null ? '—' : soilTxt(smv);
  if (gaugeDial) {
    // VWC 0–0.5 mapped to 0–100% dial position for display only.
    const pct = smv == null ? 0 : Math.max(0, Math.min(100, (smv / 0.5) * 100));
    gaugeDial.style.background = smv == null
      ? 'conic-gradient(#c9d2c4 0% 100%)'
      : `conic-gradient(#abc87d 0% ${pct}%, #e6ece0 ${pct}% 100%)`;
  }

  // Risk pillars (section 7) — worded watches, never fake percentages
  const risks = data.risks || {};
  const flags = risks.flags || [];
  const pWeather = document.querySelector('#pillar-weather-val');
  if (pWeather) {
    const heavy = flags.includes('heavy_rain_watch');
    const dry = flags.includes('dry_spell_watch');
    pWeather.textContent = heavy ? 'Heavy rain watch' : dry ? 'Dry-spell watch' : 'No watch flags';
    pWeather.style.color = heavy || dry ? 'var(--gold)' : 'var(--moss)';
  }
  const pSoil = document.querySelector('#pillar-soil-val');
  if (pSoil) {
    pSoil.textContent = smv == null ? 'No data'
      : smv < 0.12 ? 'Dry surface (forecast)' : 'Adequate (forecast)';
    pSoil.style.color = smv != null && smv < 0.12 ? 'var(--coral)' : 'var(--moss)';
  }
  const pCrop = document.querySelector('#pillar-crop-val');
  if (pCrop) {
    pCrop.textContent = (data.crop && data.crop.water_need_class)
      ? `Water need: ${String(data.crop.water_need_class).replace(/_/g, ' ')}`
      : 'Water need: not available';
    pCrop.style.color = 'var(--moss)';
  }
  const pDry = document.querySelector('#pillar-dry-val');
  if (pDry) {
    pDry.textContent = flags.includes('dry_spell_watch') ? 'Watch active' : 'No dry-spell signal';
    pDry.style.color = flags.includes('dry_spell_watch') ? 'var(--coral)' : 'var(--moss)';
  }

  // Advisory actions (section 8)
  const advEl = document.querySelector('#advisory-actions');
  if (advEl) {
    const actions = (data.advisory && data.advisory.actions) || [];
    advEl.innerHTML = actions.length
      ? `<ul>${actions.map((a) => `<li>${a}</li>`).join('')}</ul>`
      : 'No advisory actions available.';
  }

  // Sources (section 9)
  const srcEl = document.querySelector('#advisory-sources');
  if (srcEl && data.sources) {
    const items = [].concat(data.sources.weather || [], data.sources.agronomy || [],
      data.sources.soil ? [data.sources.soil] : []);
    srcEl.innerHTML = `<ul>${items.map((s) => `<li>${s}</li>`).join('')}</ul>`;
  }

  // WhatsApp share (plain-text digest of the new sections)
  const shareBtn = document.querySelector('#share-whatsapp-btn');
  if (shareBtn) {
    const lines = [
      `🌾 *SAARTHI KISAN ADVISORY*`,
      `📍 ${placeLabel} · ${data.location.block} block`,
      `🌱 ${cropName}${st.available ? ` · ${String(st.stage).replace(/_/g, ' ')}` : ''}`,
      `🌧 7-day rain: ${rainTxt(wx.rain_7d_mm)} · ET0: ${rainTxt(wx.et0_7d_mm)}`,
      `💧 ${smv != null ? `Forecast surface moisture ${soilTxt(smv)}` : 'Surface moisture: No data'}`,
      flags.length ? `⚠️ ${flags.map((f) => f.replace(/_/g, ' ')).join(', ')}` : '',
      ``,
      `💡 ${(data.advisory && data.advisory.actions || []).map((a) => '• ' + a).join('\n')}`,
      `— SAARTHI (ECMWF IFS live forecast + PAU/ICAR agronomy)`,
    ].filter((l) => l !== '');
    shareBtn.onclick = async () => {
      try {
        await navigator.clipboard.writeText(lines.join('\n'));
        showToast(getTranslation('btn_copied', lang));
      } catch (e) {
        showToast('Advisory text copied!');
      }
    };
  }
}

// Single-fetch timeline refresh: one live request serves the chart,
// the 16-day table, the risk card and the W3/W4 panel for a selection.
let timelineSelection = null;

// -------------------------------------------------------------
// 2b. PLAIN-LANGUAGE "WHAT THIS MEANS" EXPLANATIONS
// -------------------------------------------------------------
// Display-only interpretability layer: every builder reads already-loaded
// API data and returns short, hedged, farmer-friendly text. No thresholds,
// models, or risk logic are changed here. Builders take (input, t) where t
// is a translate function, so language switching re-renders from cache.
let lastMeanings = { liveDays: null, liveFailed: false, risk: null, w34: null, w34State: 'loading', climate: null };

function meaningT(key, params) {
  let s = (typeof getTranslation === 'function') ? getTranslation(key) : key;
  if (params) {
    for (const k of Object.keys(params)) s = s.split('{' + k + '}').join(params[k]);
  }
  return s;
}

function paintMeaning(boxId, textId, text) {
  const box = document.querySelector('#' + boxId);
  const txt = document.querySelector('#' + textId);
  if (!box || !txt) return;
  if (!text) {
    box.hidden = true;
    txt.textContent = '';
    return;
  }
  txt.textContent = text;
  box.hidden = false;
}

function riskMeaningText(overall, wetDays, t) {
  if (overall === 'HIGH') {
    const n = wetDays != null ? wetDays : 2; // HIGH rule implies >=2 wet days
    return t('meaning_risk_high', { n: String(n) });
  }
  if (overall === 'MODERATE') return t('meaning_risk_moderate');
  if (overall === 'LOW') return t('meaning_risk_low');
  return t('meaning_risk_unavailable');
}

function liveMeaningText(days, t) {
  const vals = (days || []).filter((d) => d && d.rainfall_mm != null);
  if (!vals.length) return t('meaning_live_unavailable');
  const total = vals.reduce((a, d) => a + d.rainfall_mm, 0);
  let base;
  if (total < 5) base = t('meaning_live_dry');
  else if (total <= 40) base = t('meaning_live_moderate');
  else base = t('meaning_live_wet');
  const sorted = [...vals].sort((a, b) => b.rainfall_mm - a.rainfall_mm);
  const top = sorted.slice(0, 3);
  const topSum = top.reduce((a, d) => a + d.rainfall_mm, 0);
  if (total >= 5 && top[0].rainfall_mm >= 3 && topSum >= 0.7 * total) {
    return base + ' ' + t('meaning_live_concentrated', { dates: top.map((d) => d.date).join(', ') });
  }
  return base;
}

function w34WindowMeaning(w, t) {
  if (!w || w.status !== 'available') return null;
  const below = Number(w.below_probability);
  const near = Number(w.near_probability);
  const above = Number(w.above_probability);
  if (![below, near, above].every(Number.isFinite)) return t('meaning_w34_near');
  if (Math.max(below, near, above) - Math.min(below, near, above) < 0.10) {
    return t('meaning_w34_near'); // flat climatological prior: no predicted direction
  }
  if (below >= near && below >= above) return t('meaning_w34_below');
  if (above >= near && above >= below) return t('meaning_w34_above');
  return t('meaning_w34_near');
}

function w34MeaningText(data, t) {
  if (!data) return t('meaning_w34_unavailable');
  const parts = [t('meaning_w34_about')];
  const w3 = w34WindowMeaning(data.w3, t);
  const w4 = w34WindowMeaning(data.w4, t);
  if (!w3 && !w4) return t('meaning_w34_unavailable');
  if (w3) parts.push(`W3 (Days 17–23) — ${w3}`);
  if (w4) parts.push(`W4 (Days 24–30) — ${w4}`);
  return parts.join(' ');
}

function mjoMeaningText(mjo, t) {
  if (!mjo || mjo.available === false) return null;
  const phase = (mjo.forecast_H7 && mjo.forecast_H7.phase) != null
    ? mjo.forecast_H7.phase
    : (mjo.observed && mjo.observed.phase);
  return phase != null ? t('meaning_mjo_phase', { phase: String(phase) }) : t('meaning_mjo');
}

function ensoMeaningText(enso, t) {
  if (!enso || enso.available === false) return null;
  const s = String(enso.status || '').toLowerCase().replace(/[^a-z]/g, '');
  if (s.includes('elnino')) return t('meaning_enso_elnino');
  if (s.includes('lanina')) return t('meaning_enso_lanina');
  if (s.includes('neutral')) return t('meaning_enso_neutral');
  return t('meaning_enso');
}

function iodMeaningText(iod, t) {
  if (!iod || iod.available === false) return null;
  if (iod.stale) return t('meaning_iod_stale', { state: String(iod.phase || 'Neutral') });
  return t('meaning_iod');
}

/**
 * ONE compact climate summary (Bug-3 hierarchy): short labeled lines for
 * MJO / ENSO / IOD instead of a repeated kicker under every table row.
 * Background information only — never a local rainfall claim.
 */
function climateMeaningText(ctx, t) {
  if (!ctx) return null;
  const parts = [];
  const m = mjoMeaningText(ctx.mjo, t);
  if (m) parts.push(`MJO · ${m}`);
  const e = ensoMeaningText(ctx.enso, t);
  if (e) parts.push(`ENSO · ${e}`);
  const i = iodMeaningText(ctx.iod, t);
  if (i) parts.push(`IOD · ${i}`);
  return parts.length ? parts.join('\n\n') : null;
}

function repaintTimelineMeanings() {
  if (typeof activeRoute !== 'undefined' && activeRoute !== 'timeline') return;
  const t = meaningT;
  paintMeaning('live-meaning', 'live-meaning-text',
    lastMeanings.liveFailed ? t('meaning_live_unavailable')
      : lastMeanings.liveDays ? liveMeaningText(lastMeanings.liveDays, t) : null);
  const r = lastMeanings.risk;
  paintMeaning('risk-meaning', 'risk-meaning-text',
    r ? riskMeaningText(r.overall, r.wetDays, t) : null);
  paintMeaning('w34-meaning', 'w34-meaning-text',
    lastMeanings.w34State === 'unavailable' ? t('meaning_w34_unavailable')
      : lastMeanings.w34State === 'error' || lastMeanings.w34State === 'loading' ? null
        : w34MeaningText(lastMeanings.w34, t));
  const c = lastMeanings.climate;
  paintMeaning('climate-meaning', 'climate-meaning-text',
    c ? climateMeaningText(c, t) : null);
}

async function refreshTimeline(sel) {
  timelineSelection = sel;
  if (!sel) {
    loadTimelineForecast(null);
    loadLiveOutlook(null);
    loadFieldRisk(null, true, 'Sangrur');
    loadWeeks34Outlook(null, true, 'Sangrur');
    return;
  }
  try {
    const pre = await SaarthiGeo.fetchBlockForecast(sel);
    const canonical = (pre.block && pre.block.block_name) || sel.block_name;
    await loadTimelineForecast(sel, pre);
    await loadLiveOutlook(sel, pre);
    await loadFieldRisk(sel, pre.isLegacy, canonical);
    await loadWeeks34Outlook(sel, pre.isLegacy, canonical);
  } catch (err) {
    console.error('Timeline refresh failed:', err);
    apiError('#recharts-forecast-canvas', `Live forecast unavailable for ${sel.block_name} (${err.message}). No fallback data is shown.`);
  }
}

// -------------------------------------------------------------
// 2. LIVE RISK MAP (LEAFLET): selection-driven, not polygon-assumed
// -------------------------------------------------------------
// One geography source of truth (SaarthiGeo cascade). The map renders the
// SELECTED block's own polygon: legacy Sangrur blocks use their Bhuvan
// reference polygon, every other registry block uses its compiled LGD
// boundary from /api/geography/block-boundary (ONE block per request —
// the national file never reaches the browser). Blocks without compiled
// geometry fall back to an honest centroid marker. Forecast always comes
// from the live ECMWF IFS APIs — nothing is manufactured in JavaScript.
let selectionMarker = null;
let selectedBoundaryLayer = null;
let mapSelection = null;
let lastMapResult = null; // { selection, envelope, block, isLegacy, risk }
let sangrurGeo = null;
let mapRequestId = 0;

function categoryOf(blockName) {
  return liveCategoryOf(blockName);
}

async function initRiskMap() {
  const mapContainer = document.querySelector('#leaflet-map-canvas');
  if (!mapContainer) return;

  if (!leafletMapInstance) {
    const persisted = (typeof SaarthiGeo !== 'undefined' && SaarthiGeo.loadSelection())
      ? SaarthiGeo.loadSelection() : null;
    leafletMapInstance = L.map('leaflet-map-canvas', {
      center: persisted && persisted.latitude != null
        ? [persisted.latitude, persisted.longitude] : [30.2458, 75.8421],
      zoom: persisted && persisted.latitude != null ? 10 : 7,
      zoomControl: true,
    });

    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      attribution: '© OpenStreetMap contributors | SAARTHI live ECMWF IFS outlook',
      maxZoom: 18,
    }).addTo(leafletMapInstance);

    mapGeoLayer = L.layerGroup().addTo(leafletMapInstance);
  }

  // Geography cascade (single source of truth — same helper as other pages).
  const mState = document.querySelector('#map-state');
  const mDist = document.querySelector('#map-district');
  const mBlock = document.querySelector('#map-block');
  if (mState && mDist && mBlock && typeof SaarthiGeo !== 'undefined') {
    SaarthiGeo.wireCascade(mState, mDist, mBlock, (sel) => {
      updateGeoEyebrow();
      refreshMap(sel);
    });
  }
  document.querySelector('#map-show-boundary')?.addEventListener('change', () => {
    if (lastMapResult) {
      const r = lastMapResult;
      renderSelectionOnMap(r.selection, r.envelope, r.block, r.isLegacy);
    }
  });
}

/** Remove the previous selection's marker + boundary from the map. */
function clearSelectionLayers() {
  if (selectionMarker) {
    leafletMapInstance.removeLayer(selectionMarker);
    selectionMarker = null;
  }
  if (selectedBoundaryLayer) {
    leafletMapInstance.removeLayer(selectedBoundaryLayer);
    selectedBoundaryLayer = null;
  }
}

/** Fill colour for the selected polygon from its live 7-day rain total. */
function selectedFillColor(block) {
  const rain7 = block && block.cum_7d_mm && block.cum_7d_mm.available
    ? block.cum_7d_mm.rainfall_mm : null;
  if (rain7 == null) return '#9ed683';
  if (rain7 < 10) return '#e2be62';
  if (rain7 > 35) return '#f27256';
  return '#9ed683';
}

/** Concise popup: place + forecast summary + sampling method. No debug text. */
function selectionPopupHtml(sel, block, isLegacy) {
  const F = (typeof SaarthiGeo !== 'undefined' && SaarthiGeo.fmt) || null;
  const rain7 = block.cum_7d_mm && block.cum_7d_mm.available ? block.cum_7d_mm.rainfall_mm : null;
  const method = isLegacy ? 'polygon multipoint mean' : 'single-point centroid';
  return `<div style="font-family: Manrope, sans-serif; font-size: 12px; min-width: 210px;">` +
    `<strong style="font-size: 14px;">${sel.block_name}</strong>` +
    `<div style="color:#617163;">${sel.district_name}, ${sel.state_name}</div>` +
    `<div style="margin-top:6px;"><b>7-day rain (live ECMWF IFS):</b> ` +
    `${F ? F.rain(rain7) : (rain7 != null ? rain7 + ' mm' : 'No data')}</div>` +
    `<div><b>Sampling:</b> ${method}</div></div>`;
}

// Single-selection refresh: one live request drives marker + card.
async function refreshMap(sel) {
  mapSelection = sel;
  updateGeoEyebrow();
  const card = document.querySelector('#map-forecast-card');
  if (!sel) {
    lastMapResult = null;
    clearSelectionLayers();
    if (card) {
      card.innerHTML = `<p style="font-size: 12.5px; margin: 0;">${getTranslation('map_standby', uiLang())}</p>`;
    }
    return;
  }
  if (card) card.innerHTML = `<p style="font-size: 12.5px; margin: 0;">Loading live ECMWF IFS outlook for ${sel.block_name}…</p>`;
  try {
    const { envelope, block, isLegacy } = await SaarthiGeo.fetchBlockForecast(sel);
    const canonical = (block && block.block_name) || sel.block_name;
    let risk = null;
    if (isLegacy) {
      try {
        const rr = await fetch(`/api/risks/${encodeURIComponent(canonical)}?window=3d`);
        if (rr.ok) risk = await rr.json();
      } catch (e) {
        console.warn('Map risk unavailable:', e);
      }
    }
    lastMapResult = { selection: sel, envelope, block, isLegacy, risk };
    paintMapBanner(envelope);
    renderMapForecastCard(sel, envelope, block, isLegacy, risk);
    renderSelectionOnMap(sel, envelope, block, isLegacy);
  } catch (err) {
    console.error('Map refresh failed:', err);
    lastMapResult = null;
    if (card) {
      card.innerHTML = `<p style="font-size: 12.5px; margin: 0; color:#a33;">Live forecast unavailable for ${sel.block_name} (${err.message}). No fallback data is shown.</p>`;
    }
  }
}

function paintMapBanner(envelope) {
  const el = document.querySelector('#map-freshness-banner');
  if (!el || !envelope) return;
  const stale = !!envelope.stale;
  el.textContent = `${envelope.provider || 'Open-Meteo'} / ${envelope.model || 'ECMWF IFS'} · issued ${envelope.issue_date} · retrieved ${envelope.retrieved_at}`
    + (stale ? ' · STALE — may be outdated' : ' · fresh');
  el.style.background = stale ? '#fbe3dc' : '#e4efe0';
  el.style.color = stale ? '#a33' : '#3c6e47';
  el.style.border = stale ? '1px solid #e0a08e' : '1px solid #b9d2bd';
}

/**
 * Draw the selection: the block's own polygon (primary) + forecast marker
 * (secondary). Legacy Sangrur blocks highlight their Bhuvan reference
 * polygon; all other blocks load their compiled LGD boundary for this
 * triple only. No other block's geometry is ever shown as a substitute.
 */
async function renderSelectionOnMap(sel, envelope, block, isLegacy) {
  if (!leafletMapInstance) return;
  const myRequest = ++mapRequestId;
  clearSelectionLayers();
  const showBoundary = document.querySelector('#map-show-boundary')?.checked !== false;
  const canonical = (block && block.block_name) || sel.block_name;

  // Secondary marker: built first so the polygon popup takes precedence.
  let marker = null;
  if (sel.latitude != null && sel.longitude != null) {
    marker = L.marker([sel.latitude, sel.longitude]);
    marker.bindPopup(selectionPopupHtml(sel, block, isLegacy));
  }

  let feature = null;
  if (showBoundary) {
    if (isLegacy) {
      feature = await legacyPolygonFeature(canonical);
    } else {
      feature = await compiledBoundaryFeature(sel);
    }
  }
  if (myRequest !== mapRequestId) return; // a newer selection won the race
  if (feature) {
    selectedBoundaryLayer = L.geoJSON(feature, {
      style: () => ({
        fillColor: selectedFillColor(block),
        color: '#244235',
        weight: 3,
        opacity: 1,
        fillOpacity: 0.35,
      }),
    }).addTo(leafletMapInstance);
    selectedBoundaryLayer.bindPopup(selectionPopupHtml(sel, block, isLegacy));
    try {
      leafletMapInstance.fitBounds(selectedBoundaryLayer.getBounds().pad(0.2));
    } catch (e) { /* keep current view */ }
    selectedBoundaryLayer.openPopup();
  }
  if (marker) {
    selectionMarker = marker.addTo(leafletMapInstance);
    if (!feature && sel.latitude != null && sel.longitude != null) {
      leafletMapInstance.setView([sel.latitude, sel.longitude], 10);
      selectionMarker.openPopup();
    }
  }
}

/** Bhuvan reference polygon for one legacy Sangrur block (lazy, cached). */
async function legacyPolygonFeature(canonical) {
  try {
    if (!sangrurGeo) {
      const res = await fetch('/api/blocks/geojson');
      if (!res.ok) return null;
      sangrurGeo = await res.json();
    }
    const feats = (sangrurGeo && sangrurGeo.features) || [];
    return feats.find((f) => f.properties && f.properties.block_name === canonical) || null;
  } catch (err) {
    console.warn('Legacy polygon unavailable:', err);
    return null;
  }
}

/** Compiled LGD boundary for one selected triple. Null when not compiled. */
async function compiledBoundaryFeature(sel) {
  try {
    const url = `/api/geography/block-boundary?state=${encodeURIComponent(sel.state_code)}`
      + `&district=${encodeURIComponent(sel.district_code)}`
      + `&block=${encodeURIComponent(sel.block_code)}`;
    const res = await fetch(url);
    if (!res.ok) return null; // 404 boundary_unavailable: honest marker-only view
    return await res.json();
  } catch (err) {
    console.warn('Block boundary unavailable:', err);
    return null;
  }
}

function renderMapForecastCard(sel, envelope, block, isLegacy, risk) {
  const card = document.querySelector('#map-forecast-card');
  if (!card) return;
  const F = (typeof SaarthiGeo !== 'undefined' && SaarthiGeo.fmt) || null;
  const rain = (c) => {
    const txt = F ? F.rain(c && c.available ? c.rainfall_mm : null)
      : ((c && c.available ? fmtRainFallback(c.rainfall_mm) : 'No data'));
    return txt;
  };
  const method = isLegacy
    ? 'multipoint polygon mean'
    : 'single-point centroid (registry coordinates — not polygon-averaged)';
  const riskLine = risk
    ? `${risk.overall_risk || risk.category || '—'} — ${String(risk.primary_concern || '').replace(/_/g, ' ')} (confidence ${risk.confidence || '—'})`
    : isLegacy
      ? 'Risk unavailable for this block right now.'
      : 'Advanced field-risk scoring is currently available for supported blocks only; the live forecast above covers this block.';
  const stale = envelope.stale ? ` · STALE${envelope.stale_warning ? ` — ${envelope.stale_warning}` : ''}` : ' · fresh';
  card.innerHTML =
    `<p class="card-kicker" style="margin:0 0 6px;">Current block outlook · live ECMWF IFS</p>` +
    `<div style="font-size:13px;line-height:1.7;">` +
    `<div><b>Block:</b> ${sel.block_name} · <b>District:</b> ${sel.district_name} · <b>State:</b> ${sel.state_name}</div>` +
    `<div><b>Provider:</b> ${envelope.provider || 'Open-Meteo'} · <b>Model:</b> ${envelope.model || 'ECMWF IFS'} · <b>Horizon:</b> ${(block.days || []).length} days</div>` +
    `<div><b>Issued:</b> ${envelope.issue_date} · <b>Retrieved:</b> ${envelope.retrieved_at}${stale}</div>` +
    `<div><b>Rainfall:</b> 3-day ${rain(block.cum_3d_mm)} · 7-day ${rain(block.cum_7d_mm)} · 15-day ${rain(block.cum_15d_mm)}</div>` +
    `<div><b>Risk:</b> ${riskLine}</div>` +
    `<div><b>Sampling:</b> ${method}</div>` +
    `</div>`;
}

// -------------------------------------------------------------
// 3. 7-DAY FORECAST OUTLOOK & MATRIX (TIMELINE)
// -------------------------------------------------------------

async function loadTimelineForecast(sel, pre) {
  const titleEl = document.querySelector('#timeline-block-title');
  const blockName = (sel && sel.block_name) || 'Sangrur';
  if (titleEl) titleEl.textContent = sel ? SaarthiGeo.locationLabel(sel) : `${blockName} Block`;
  const placeEl = document.querySelector('#timeline-place');
  if (placeEl) placeEl.textContent = sel ? `${sel.district_name}, ${sel.state_name}` : 'Select a block';

  try {
    // LIVE contract: single source for the timeline chart + matrix (D+1..D+16).
    // Legacy Sangrur names resolve to the polygon path; all other registry
    // blocks resolve to their centroid — same render, honest method label.
    let data = pre && pre.envelope;
    if (!data) {
      if (sel) {
        ({ envelope: data } = await SaarthiGeo.fetchBlockForecast(sel));
      } else {
        const res = await fetch(`/api/weather/forecast/${encodeURIComponent(blockName)}`);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        data = await res.json();
      }
    }
    paintLiveBanner('timeline-freshness-banner', data);
    const b = data.block || {};
    blockForecastCache[b.block_name] = { ...b, rain_7d: b.cum_7d_mm && b.cum_7d_mm.available ? b.cum_7d_mm.rainfall_mm : null };
    const matrix = (b.days || []).map((d) => ({
      day: d.horizon_day, date: d.date, rainfall_mm: d.rainfall_mm,
    }));
    const total7 = b.cum_7d_mm && b.cum_7d_mm.available ? b.cum_7d_mm.rainfall_mm : null;
    renderForecastChart({ block: b.block_name, forecast_matrix: matrix, total: total7, category: null });
    renderForecastMatrix(matrix);
  } catch (err) {
    console.error('Timeline forecast failed:', err);
    apiError('#recharts-forecast-canvas', 'Live forecast data is currently unavailable. Please try again.');
  }
}

// -------------------------------------------------------------
// CHART Y-AXIS SCALING (display-only: input values are never modified)
// -------------------------------------------------------------
// Dynamic "nice" scale for the rainfall chart: ~15% headroom above the
// largest valid bar, human-friendly ticks, never clips real data.
// Ignores null/undefined/NaN/negative values for the scale calculation.
function chartYScale(values) {
  const valid = (Array.isArray(values) ? values : []).filter((v) => Number.isFinite(v) && v >= 0);
  const dataMax = valid.length ? Math.max.apply(null, valid) : 0;
  if (!(dataMax > 0)) return { yMax: 2, ticks: [0, 0.5, 1, 1.5, 2] };
  const target = dataMax * 1.15;
  const mag = Math.pow(10, Math.floor(Math.log10(target / 4)));
  const mults = [1, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10];
  let step = 10 * mag;
  for (const m of mults) {
    if (m * mag * 4 >= target) { step = m * mag; break; }
  }
  let yMax = step * 4;
  if (yMax < 2) return { yMax: 2, ticks: [0, 0.5, 1, 1.5, 2] };
  const clean = (v) => parseFloat(v.toFixed(6));
  return { yMax: clean(yMax), ticks: [0, step, 2 * step, 3 * step, 4 * step].map(clean) };
}

function chartTickLabel(val) {
  return Number.isInteger(val) ? String(val) : String(parseFloat(val.toFixed(1)));
}

function renderForecastChart(data) {

  const container = document.querySelector('#recharts-forecast-canvas');
  if (!container) return;

  const matrix = data.forecast_matrix || [];
  const n = matrix.length;
  if (n === 0) return;

  const svgWidth = 920;
  const svgHeight = 310;
  const padLeft = 60;
  const padRight = 60;
  const padTop = 25;
  const padBottom = 45;
  const plotWidth = svgWidth - padLeft - padRight;
  const plotHeight = svgHeight - padTop - padBottom;

  const validRain = matrix.map((m) => m.rainfall_mm).filter((v) => Number.isFinite(v) && v >= 0);
  const rawMaxRain = validRain.length ? Math.max.apply(null, validRain) : 0;
  const scale = chartYScale(validRain);
  const maxRain = scale.yMax;

  const stepX = plotWidth / n;
  const barWidth = Math.min(42, Math.max(14, stepX * 0.58));

  const rainTicks = scale.ticks;
  const gridLinesSvg = rainTicks
    .map((val) => {
      const y = padTop + plotHeight - (val / maxRain) * plotHeight;
      return `
        <line x1="${padLeft}" y1="${y}" x2="${svgWidth - padRight}" y2="${y}" stroke="#e8ece4" stroke-dasharray="3,3" stroke-width="1" />
        <text x="${padLeft - 10}" y="${y + 3.5}" text-anchor="end" fill="#587482" font-family="'DM Mono', monospace" font-size="10">${chartTickLabel(val)} mm</text>
      `;
    })
    .join('');

  const barsSvg = matrix
    .map((m, i) => {
      const centerX = padLeft + i * stepX + stepX / 2;
      const barX = centerX - barWidth / 2;
      const barH = Math.max(3, (m.rainfall_mm / maxRain) * plotHeight);
      const barY = padTop + plotHeight - barH;
      const isPeak = m.rainfall_mm === rawMaxRain && rawMaxRain > 3;
      const fill = isPeak ? 'url(#rainBarPeakGrad)' : 'url(#rainBarGrad)';
      const barLbl = (typeof SaarthiGeo !== 'undefined' && SaarthiGeo.fmt)
        ? SaarthiGeo.fmt.rain(m.rainfall_mm).replace(' mm', '')
        : m.rainfall_mm;

      const textLabel = (barH > 18 || (isPeak && barH > 14))
        ? `<text x="${centerX}" y="${barY - 4}" text-anchor="middle" fill="#2d6b8b" font-family="'DM Mono', monospace" font-weight="700" font-size="9.5">${barLbl}</text>`
        : '';

      return `
        <g class="chart-bar" style="cursor: pointer;">
          <rect x="${barX}" y="${barY}" width="${barWidth}" height="${barH}" rx="4" fill="${fill}" />
          ${textLabel}
          <title>Day ${m.day} (${m.date}): Rain ${m.rainfall_mm}mm</title>
        </g>
      `;
    })
    .join('');

  const xAxisLabelsSvg = matrix
    .map((m, i) => {
      const x = padLeft + i * stepX + stepX / 2;
      return `
        <line x1="${x}" y1="${padTop + plotHeight}" x2="${x}" y2="${padTop + plotHeight + 5}" stroke="#b8c4b4" stroke-width="1" />
        <text x="${x}" y="${padTop + plotHeight + 18}" text-anchor="middle" fill="#3c4e3f" font-family="'DM Mono', monospace" font-weight="700" font-size="10">D${m.day}</text>
        <text x="${x}" y="${padTop + plotHeight + 30}" text-anchor="middle" fill="#788a7b" font-family="'DM Mono', monospace" font-size="9">${m.date}</text>
      `;
    })
    .join('');

  const total = data.total != null ? data.total : matrix.reduce((a, m) => a + m.rainfall_mm, 0);
  container.innerHTML = `
    <div class="chart-legend-row">
      <div style="display: flex; gap: 16px; align-items: center;">
        <span class="chart-legend-badge rain">■ Validated Daily Rain (mm)</span>
        <span class="chart-legend-badge">7-day total: <b>${total.toFixed ? total.toFixed(1) : total} mm</b>${data.category ? ` · <b>${data.category}</b>` : ''}</span>
      </div>
    </div>

    <div class="chart-svg-wrap">
      <svg viewBox="0 0 ${svgWidth} ${svgHeight}" style="width: 100%; height: auto; display: block; overflow: visible;">
        <defs>
          <linearGradient id="rainBarGrad" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stop-color="#427c9c" />
            <stop offset="100%" stop-color="#82b7c7" />
          </linearGradient>
          <linearGradient id="rainBarPeakGrad" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stop-color="#2a5d77" />
            <stop offset="100%" stop-color="#5599b8" />
          </linearGradient>
        </defs>

        <!-- Grid Lines & Y-Axis Labels -->
        ${gridLinesSvg}

        <!-- Rainfall Bars -->
        ${barsSvg}

        <!-- Baseline X-Axis -->
        <line x1="${padLeft}" y1="${padTop + plotHeight}" x2="${svgWidth - padRight}" y2="${padTop + plotHeight}" stroke="#adbdb0" stroke-width="1.5" />

        <!-- X-Axis Labels -->
        ${xAxisLabelsSvg}
      </svg>
    </div>
  `;
}

function renderForecastMatrix(matrix) {
  const tbody = document.querySelector('#forecast-matrix-tbody');
  if (!tbody) return;
  const F = (typeof SaarthiGeo !== 'undefined' && SaarthiGeo.fmt) || null;
  const rainTxt = (x) => (F ? F.rain(x) : fmtRainFallback(x));

  tbody.innerHTML = matrix
    .map((row) => {
      const wet = row.rainfall_mm >= 1.0;
      return `
        <tr>
          <td><b>Day ${row.day}</b></td>
          <td>${row.date}</td>
          <td><strong style="color: #427c9c;">${rainTxt(row.rainfall_mm)}</strong></td>
          <td>${wet ? 'Wet (≥1 mm)' : 'Dry (<1 mm)'}</td>
        </tr>
      `;
    })
    .join('');
}

// -------------------------------------------------------------
// 3b. LIVE OPERATIONAL OUTLOOK (Phase 1+2: /api/weather/*)
// -------------------------------------------------------------
// Block-level live rainfall from Open-Meteo delivery + ECMWF IFS NWP.
// Display-only view: no forecasting here, no zero-filling, horizon served
// honestly (16 days; 16–30 explicitly unavailable).

async function loadLiveOutlook(sel, pre) {
  const tbody = document.querySelector('#live-matrix-tbody');
  const metaEl = document.querySelector('#live-meta');
  const freshEl = document.querySelector('#live-freshness');
  if (!tbody || !metaEl) return;

  const blockName = (sel && sel.block_name) || 'Sangrur';
  tbody.innerHTML = `<tr><td colspan="4">Loading live outlook for ${sel ? SaarthiGeo.locationLabel(sel) : blockName}…</td></tr>`;
  metaEl.textContent = 'Live outlook loading…';
  lastMeanings.liveDays = null;
  lastMeanings.liveFailed = false;
  paintMeaning('live-meaning', 'live-meaning-text', null);

  try {
    let data;
    let isLegacy = false;
    if (pre && pre.envelope) {
      data = pre.envelope;
      isLegacy = !!pre.isLegacy;
    } else if (sel) {
      const r = await SaarthiGeo.fetchBlockForecast(sel);
      data = r.envelope;
      isLegacy = r.isLegacy;
    } else {
      const res = await fetch(`/api/weather/forecast/${encodeURIComponent(blockName)}`);
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error(err.message || `HTTP ${res.status}`);
      }
      data = await res.json();
      isLegacy = true;
    }
    const b = data.block || {};
    const days = b.days || [];

    const staleTag = data.stale
      ? ` · STALE${data.stale_warning ? ` — ${data.stale_warning}` : ' — cached forecast served'}` : ' · fresh';
    const method = b.spatial_method || '';
    const methodTag = method.includes('single_point_centroid')
      ? ' · single-point centroid (not polygon-averaged)'
      : method.includes('multipoint') ? ' · polygon multipoint mean' : '';
    metaEl.textContent =
      `${b.block_name || blockName}: ${data.provider || 'Open-Meteo'} / ${data.model || 'ECMWF IFS'} · ` +
      `issued ${data.issue_date} · ${days.length}-day horizon${staleTag}${methodTag}`;
    if (sel && !isLegacy && data.soil) {
      metaEl.textContent += data.soil.available && data.soil.line
        ? ` · Soil: ${data.soil.line}` : ' · Soil data unavailable for this block';
    }

    if (freshEl) {
      freshEl.textContent = data.stale
        ? `Live forecast STALE (retrieved ${data.retrieved_at}) — may be outdated`
        : `Live forecast fresh (retrieved ${data.retrieved_at})`;
      freshEl.style.background = data.stale ? '#fbe3dc' : '#e4efe0';
      freshEl.style.color = data.stale ? '#a33' : '#3c6e47';
      freshEl.style.border = data.stale ? '1px solid #e0a08e' : '1px solid #b9d2bd';
    }

    const cum = (c) => {
      if (!c || !c.available) return 'unavailable';
      const F = (typeof SaarthiGeo !== 'undefined' && SaarthiGeo.fmt) || null;
      return F ? F.rain(c.rainfall_mm) : fmtRainFallback(c.rainfall_mm);
    };
    tbody.innerHTML = days.map((d) => {
      const F = (typeof SaarthiGeo !== 'undefined' && SaarthiGeo.fmt) || null;
      const rain = d.rainfall_mm == null
        ? '<span style="color:#a33;">No data (not zero)</span>'
        : `<strong style="color: #427c9c;">${F ? F.rain(d.rainfall_mm) : fmtRainFallback(d.rainfall_mm)}</strong>`;
      const prob = fmtPct0(d.rain_probability_pct);
      return `<tr><td><b>Day ${d.horizon_day}</b></td><td>${d.date}</td><td>${rain}</td><td>${prob}</td></tr>`;
    }).join('') + `
      <tr><td colspan="2"><b>3-day total</b></td><td colspan="2">${cum(b.cum_3d_mm)}</td></tr>
      <tr><td colspan="2"><b>7-day total</b></td><td colspan="2">${cum(b.cum_7d_mm)}</td></tr>
      <tr><td colspan="2"><b>15-day total</b></td><td colspan="2">${cum(b.cum_15d_mm)}</td></tr>
      <tr><td colspan="2"><b>30-day</b></td><td colspan="2">Not served — 16-day deterministic feed only</td></tr>`;
    lastMeanings.liveDays = days;
    lastMeanings.liveFailed = false;
    paintMeaning('live-meaning', 'live-meaning-text', liveMeaningText(days, meaningT));
  } catch (err) {
    console.error('Live outlook failed:', err);
    metaEl.textContent = 'Live outlook unavailable.';
    tbody.innerHTML = `<tr><td colspan="4" style="color:#a33;">Live outlook data is currently unavailable (${err.message}). No fallback forecast is synthesised — please try again.</td></tr>`;
    lastMeanings.liveDays = null;
    lastMeanings.liveFailed = true;
    paintMeaning('live-meaning', 'live-meaning-text', meaningT('meaning_live_unavailable'));
    if (freshEl) {
      freshEl.textContent = 'Live forecast freshness unknown — provider unreachable.';
      freshEl.style.background = '#fbe3dc';
      freshEl.style.color = '#a33';
      freshEl.style.border = '1px solid #e0a08e';
    }
  }
}

// -------------------------------------------------------------
// 3b2. AGRICULTURAL RISK — COMPOSITE (Phase 4.3: composite_v1, /api/risks/*)
// -------------------------------------------------------------
// Rule-based priority served from the same live IFS forecast (no second
// request): FIELD_HIGH -> HIGH; provisional dry-spell watch -> MODERATE
// (never HIGH); stale -> MODERATE; incomplete -> UNAVAILABLE (never LOW).
// Generic wording only — no crops, no agronomic prescriptions.

async function loadFieldRisk(sel, isLegacy, canonical) {
  const metaEl = document.querySelector('#risk-meta');
  const cardEl = document.querySelector('#risk-card');
  const primEl = document.querySelector('#risk-primary');
  const evEl = document.querySelector('#risk-evidence');
  const othEl = document.querySelector('#risk-others');
  const advEl = document.querySelector('#risk-advisory');
  if (!metaEl || !cardEl) return;

  const label = sel ? SaarthiGeo.locationLabel(sel) : (canonical || 'Sangrur');
  const paint = (bg, fg, border) => {
    cardEl.style.background = bg;
    cardEl.style.color = fg;
    cardEl.style.border = border;
  };
  const clearRest = () => {
    if (primEl) primEl.textContent = '';
    if (evEl) evEl.textContent = '';
    if (othEl) othEl.textContent = '';
    if (advEl) advEl.textContent = '';
  };
  if (!isLegacy) {
    // Field-risk scoring is calibrated for supported blocks only. Never
    // imply a generic block uses another block's data.
    metaEl.textContent = `Agricultural risk for ${label}: live ECMWF IFS weather is available for this block. Advanced field-risk scoring is currently available for supported blocks only — the live 16-day rainfall outlook above covers this block.`;
    cardEl.textContent = 'UNAVAILABLE for this block';
    paint('#eef1ea', '#657566', '1px solid #c9cfc4');
    clearRest();
    if (advEl) advEl.textContent = 'The live 16-day rainfall outlook above covers this block; field-work risk scoring is not calibrated here.';
    lastMeanings.risk = { overall: 'UNAVAILABLE', wetDays: null };
    paintMeaning('risk-meaning', 'risk-meaning-text', meaningT('meaning_risk_unavailable'));
    return;
  }
  const block = canonical || 'Sangrur';

  metaEl.textContent = `Agricultural risk loading for ${block}…`;
  cardEl.textContent = 'Loading…';
  clearRest();
  lastMeanings.risk = null;
  paintMeaning('risk-meaning', 'risk-meaning-text', null);

  const prettyConcern = (c) => (c || '—').replace(/_/g, ' ');
  try {
    const res = await fetch(`/api/risks/${encodeURIComponent(block)}?window=3d`);
    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      throw new Error(err.message || `HTTP ${res.status}`);
    }
    const data = await res.json();
    // Backward compatible: older responses carry category without overall_risk.
    const overall = data.overall_risk || data.category || 'UNAVAILABLE';
    const ev = data.evidence || {};
    const F = (typeof SaarthiGeo !== 'undefined' && SaarthiGeo.fmt) || null;
    const wetTxt = ev.wet_days == null ? 'unknown' : `${ev.wet_days} of 3 forecast days are wet`;
    const maxTxt = ev.max_precipitation_mm == null ? 'unknown'
      : (F ? F.rain(ev.max_precipitation_mm) : fmtRainFallback(ev.max_precipitation_mm));
    lastMeanings.risk = { overall, wetDays: ev.wet_days };
    paintMeaning('risk-meaning', 'risk-meaning-text', riskMeaningText(overall, ev.wet_days, meaningT));
    metaEl.textContent =
      `${data.block || block}: AGRICULTURAL RISK · D+1–D+3 ` +
      `(${Array.isArray(data.window_dates) ? data.window_dates.join(' … ') : '—'}) · ` +
      `confidence ${data.confidence || '—'}` +
      (data.stale ? ' · STALE forecast — capped confidence' : ' · fresh');
    if (overall === 'HIGH') {
      cardEl.textContent = 'Overall: HIGH — field work may be disrupted';
      paint('#fbe3dc', '#a33', '1px solid #e0a08e');
    } else if (overall === 'MODERATE') {
      cardEl.textContent = 'Overall: MODERATE — stay aware, check back';
      paint('#fdf3e0', '#8a6d1b', '1px solid #e0c98e');
    } else if (overall === 'LOW') {
      cardEl.textContent = 'Overall: LOW — no strong risk signal';
      paint('#e4efe0', '#3c6e47', '1px solid #b9d2bd');
    } else {
      cardEl.textContent = 'UNAVAILABLE — incomplete forecast data';
      paint('#eef1ea', '#657566', '1px solid #c9cfc4');
    }
    if (primEl) {
      primEl.textContent = `Primary concern: ${prettyConcern(data.primary_concern)}. ` +
        `Why: ${overall === 'HIGH' ? `${wetTxt} (≥1 mm/day).` : (data.reasons || []).join(', ') || '—'}`;
    }
    if (evEl) {
      evEl.textContent = `Evidence: ${wetTxt}; max forecast rainfall ${maxTxt}.`;
    }
    if (othEl) {
      const risks = Array.isArray(data.risks) ? data.risks : [];
      const byName = {};
      risks.forEach((r) => { byName[r.name] = r; });
      const watch = byName.DRY_SPELL_WATCH;
      const heavy = byName.HEAVY_RAIN_EVIDENCE;
      const ctx = data.context || {};
      const parts = [];
      if (watch) {
        parts.push(watch.state === 'ACTIVE'
          ? 'Dry-spell watch: PROVISIONAL watch active (IFS validation pending)'
          : `Dry-spell watch: ${String(watch.state || 'unknown').toLowerCase()}`);
      }
      if (heavy && heavy.state === 'PRESENT') {
        parts.push('Heavy-rain evidence present (display-only, not a validated risk)');
      }
      if (ctx.recent_rainfall && ctx.recent_rainfall.available) {
        const d14 = ctx.recent_rainfall.d14_mm;
        parts.push(`Recent rainfall: 14-day total ${F && d14 != null ? F.rain(d14) : fmtRainFallback(d14)} ` +
          `(observed through ${ctx.recent_rainfall.through})`);
      }
      if (ctx.soil && ctx.soil.line) parts.push(`Soil: ${ctx.soil.line}`);
      othEl.textContent = parts.length ? `Other signals: ${parts.join(' · ')}` : '';
    }
    if (advEl) {
      const adv = Array.isArray(data.advisories) ? data.advisories : [data.advisory];
      advEl.textContent = adv.filter(Boolean).join(' ');
    }
  } catch (err) {
    console.error('Agricultural risk failed:', err);
    metaEl.textContent = 'Agricultural risk unavailable.';
    cardEl.textContent = `UNAVAILABLE — ${err.message}`;
    paint('#eef1ea', '#657566', '1px solid #c9cfc4');
    lastMeanings.risk = { overall: 'UNAVAILABLE', wetDays: null };
    paintMeaning('risk-meaning', 'risk-meaning-text', meaningT('meaning_risk_unavailable'));
    if (primEl) primEl.textContent = '';
    if (evEl) evEl.textContent = '';
    if (othEl) othEl.textContent = '';
    if (advEl) advEl.textContent = 'Agricultural risk is unavailable because the required forecast data is incomplete.';
  }
}

// -------------------------------------------------------------
// 3c. WEEKS 3-4 EXTENDED CLIMATE OUTLOOK (Phase 3B: /api/outlook/*)
// -------------------------------------------------------------
// Display-only, climatology-based outlook: W3 = D+17..D+23, W4 = D+24..D+30.
// Probabilities are the tercile prior (1/3 each); amounts are climatological
// normals for reference, never deterministic forecasts. MJO/IOD/ENSO are
// context only. All honesty states (loading/unavailable/stale/error) explicit.

async function loadWeeks34Outlook(sel, isLegacy, canonical) {
  const tbody = document.querySelector('#w34-matrix-tbody');
  const metaEl = document.querySelector('#w34-meta');
  const freshEl = document.querySelector('#w34-freshness');
  const narrEl = document.querySelector('#w34-narrative');
  if (!tbody || !metaEl) return;

  const label = sel ? SaarthiGeo.locationLabel(sel) : (canonical || 'Sangrur');
  if (!isLegacy) {
    // Phase 3B climatology normals exist for supported blocks only. Never
    // imply a generic block is served by another block's feed.
    tbody.innerHTML = `<tr><td colspan="5">Extended outlook is currently available for supported blocks only — block climatology is not compiled for ${label}. The live 16-day IFS outlook above is unaffected.</td></tr>`;
    metaEl.textContent = 'Extended outlook unavailable for this block.';
    if (narrEl) narrEl.textContent = '';
    lastMeanings.w34 = null;
    lastMeanings.w34State = 'unavailable';
    paintMeaning('w34-meaning', 'w34-meaning-text', meaningT('meaning_w34_unavailable'));
    return;
  }
  const block = canonical || 'Sangrur';

    tbody.innerHTML = `<tr><td colspan="5">Loading extended outlook for ${block}…</td></tr>`;
    metaEl.textContent = 'Extended outlook loading…';
    if (narrEl) narrEl.textContent = '';
    lastMeanings.w34 = null;
    lastMeanings.w34State = 'loading';
    paintMeaning('w34-meaning', 'w34-meaning-text', null);

  try {
    const res = await fetch(`/api/outlook/17-30/${encodeURIComponent(block)}`);
    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      throw new Error(err.message || `HTTP ${res.status}`);
    }
    const data = await res.json();
    const fmtProb = (p) => `${Math.round(p * 100)}%`;
    const row = (label, w) => {
      if (!w || w.status !== 'available') {
        return `<tr><td><b>${label}</b></td><td colspan="4" style="color:#a33;">Unavailable — ${w ? (w.reason || 'no data') : 'no data'} (never zero-filled)</td></tr>`;
      }
      return `<tr><td><b>${label}</b></td><td>${w.period_start}…${w.period_end}</td>` +
        `<td>${fmtProb(w.below_probability)} / ${fmtProb(w.near_probability)} / ${fmtProb(w.above_probability)}</td>` +
        `<td>${fmtNumOrNull(w.climatological_normal_mm, 1) ?? '—'} mm <span style="opacity:.65">(normal, not forecast)</span></td>` +
        `<td>${data.confidence || '—'}</td></tr>`;
    };
    tbody.innerHTML = row('W3 (Days 17–23)', data.w3) + row('W4 (Days 24–30)', data.w4);

    const stale = data.climate_context && data.climate_context.any_stale;
    metaEl.textContent =
      `${data.block_name || block}: issued ${data.issue_date} · confidence ${data.confidence || '—'}` +
      (stale ? ' · climate context STALE — confidence capped' : ' · climate context fresh') +
      ` (${data.confidence_reason || ''})`;
    if (freshEl) {
      freshEl.textContent = stale
        ? 'Extended outlook based on STALE climate context — climatology baseline, reduced confidence'
        : `Extended outlook fresh (issued ${data.issue_date}) — climatology baseline`;
      freshEl.style.background = stale ? '#fbe3dc' : '#e4efe0';
      freshEl.style.color = stale ? '#a33' : '#3c6e47';
      freshEl.style.border = stale ? '1px solid #e0a08e' : '1px solid #b9d2bd';
    }
    if (narrEl) narrEl.textContent = data.narrative || '';
    lastMeanings.w34 = data;
    lastMeanings.w34State = 'ready';
    paintMeaning('w34-meaning', 'w34-meaning-text', w34MeaningText(data, meaningT));
    const rec = data.recent_observed || {};
    if (rec.observed_14d_mm == null && rec.observed_30d_mm == null && narrEl) {
      narrEl.textContent += ' Recent observed rainfall unavailable (not zero).';
    }
  } catch (err) {
    console.error('Weeks 3-4 outlook failed:', err);
    metaEl.textContent = 'Extended outlook unavailable.';
    tbody.innerHTML = `<tr><td colspan="5" style="color:#a33;">Extended outlook data is currently unavailable (${err.message}). No fallback outlook is synthesised — please try again.</td></tr>`;
    lastMeanings.w34 = null;
    lastMeanings.w34State = 'error';
    paintMeaning('w34-meaning', 'w34-meaning-text', null);
    if (freshEl) {
      freshEl.textContent = 'Extended outlook freshness unknown.';
      freshEl.style.background = '#fbe3dc';
      freshEl.style.color = '#a33';
      freshEl.style.border = '1px solid #e0a08e';
    }
  }
}

async function loadClimateContext() {
  const tbody = document.querySelector('#climate-context-tbody');
  if (!tbody) return;
  try {
    const res = await fetch('/api/climate-context');
    if (!res.ok) throw new Error(`climate-context ${res.status}`);
    const ctx = await res.json();
    if (ctx.available === false) {
      tbody.innerHTML = `<tr><td>Climate context unavailable — ${ctx.reason || 'no data'}. Rainfall forecast above is unaffected.</td></tr>`;
      lastMeanings.climate = null;
      paintMeaning('climate-meaning', 'climate-meaning-text', null);
      return;
    }
    const rows = [];
    const mjo = ctx.mjo || {};
    if (mjo.available === false) {
      rows.push(['MJO', `Unavailable — ${mjo.reason || 'no RMM data'}`]);
    } else {
      const f = mjo.forecast_H7 || {};
      const o = mjo.observed || {};
      rows.push(['MJO Phase', `Observed ${o.phase} (amp ${fmtAnom2(o.amplitude)}) → forecast phase ${f.phase} (amp ${fmtAnom2(f.amplitude)}) for ${f.forecast_date}${mjo.stale ? ' — from latest available observations' : ''}`]);
      rows.push(['MJO RMM', `RMM1 ${fmtAnom2(f.RMM1)}, RMM2 ${fmtAnom2(f.RMM2)}`]);
    }
    const enso = ctx.enso || {};
    rows.push(['ENSO', enso.available === false ? `Unavailable — ${enso.reason || ''}` : `${enso.status} (Niño-3.4 anomaly ${fmtAnom2(enso.nino34_anom)}°C, ${enso.latest_month})`]);
    const iod = ctx.iod || {};
    let iodTxt;
    if (iod.available === false) {
      iodTxt = `Data unavailable · Module: integration-ready<br><span style="opacity:.75">Local OISST data unavailable — live IOD enables automatically once OISST ingest lands. No value fabricated.</span>`;
    } else {
      const stale = iod.stale ? `<br><span style="opacity:.75">Latest available IOD data is stale (data ${iod.data_month || ''}).</span>` : '';
      const dmiNum = Number(iod.dmi);
      const dmi = !Number.isFinite(dmiNum) ? '—' : `${dmiNum >= 0 ? '+' : ''}${dmiNum.toFixed(2)}°C`;
      iodTxt = `${iod.phase} IOD<br>DMI ${dmi} · Forecast: ${iod.forecast_month || ''} · Model: ${iod.model || ''}${stale}`;
    }
    rows.push(['IOD', iodTxt]);
    tbody.innerHTML = rows.map(([k, v]) => `<tr><td><b>${k}</b></td><td>${v}</td></tr>`).join('');
    lastMeanings.climate = ctx;
    paintMeaning('climate-meaning', 'climate-meaning-text', climateMeaningText(ctx, meaningT));
  } catch (err) {
    console.error('Climate context failed:', err);
    tbody.innerHTML = `<tr><td>Climate context unavailable — live data could not be loaded. Rainfall forecast above is unaffected.</td></tr>`;
    lastMeanings.climate = null;
    paintMeaning('climate-meaning', 'climate-meaning-text', null);
  }
}

// -------------------------------------------------------------
// INITIALIZATION
// -------------------------------------------------------------

document.addEventListener('DOMContentLoaded', () => {
  updateRouteUI();

  if (activeRoute === 'farmer') {
    const fState = document.querySelector('#form-state');
    const fDist = document.querySelector('#form-district');
    const fBlock = document.querySelector('#form-block');
    if (fState && fDist && fBlock && typeof SaarthiGeo !== 'undefined') {
      SaarthiGeo.wireCascade(fState, fDist, fBlock, (sel) => {
        farmerSelection = sel;
        updateGeoEyebrow();
        updateFarmerLocationNote();
        if (!sel) return; // mid-cascade: wait for a complete selection
        computeFarmerAdvisory();
      });
      document.querySelector('#form-refresh-btn')?.addEventListener('click', computeFarmerAdvisory);
      ['form-crop', 'form-soil', 'form-date', 'form-irrigation'].forEach((id) => {
        document.querySelector(`#${id}`)?.addEventListener('change', computeFarmerAdvisory);
      });
    } else {
      computeFarmerAdvisory();
    }
  } else if (activeRoute === 'map') {
    initRiskMap();
  } else if (activeRoute === 'timeline') {
    loadClimateContext();
    const tState = document.querySelector('#tl-state');
    const tDist = document.querySelector('#tl-district');
    const tBlock = document.querySelector('#live-block-select');
    if (tState && tDist && tBlock && typeof SaarthiGeo !== 'undefined') {
      SaarthiGeo.wireCascade(tState, tDist, tBlock, (sel) => {
        updateGeoEyebrow();
        if (sel) refreshTimeline(sel);
      });
    } else {
      refreshTimeline(null);
    }
  }

  window.addEventListener('languageChanged', () => {
    updateRouteUI();
    updateGeoEyebrow();
    if (activeRoute === 'farmer' && currentFarmerData) {
      renderFarmerAdvisoryView(currentFarmerData);
    }
    if (activeRoute === 'map' && lastMapResult) {
      const r = lastMapResult;
      renderMapForecastCard(r.selection, r.envelope, r.block, r.isLegacy, r.risk);
    }
    if (activeRoute === 'timeline') {
      repaintTimelineMeanings();
    }
  });
});

// Portal header eyebrow follows the persisted selection (block · district,
// state); falls back to the translated static label when nothing is chosen.
function updateGeoEyebrow() {
  const el = document.querySelector('.portal-header .eyebrow span[data-i18n="district_name"]');
  if (!el) return;
  const sel = farmerSelection || timelineSelection || mapSelection || SaarthiGeo.loadSelection();
  if (sel) el.textContent = `${sel.block_name} · ${sel.district_name}, ${sel.state_name} · Live ECMWF IFS Outlook`;
}
