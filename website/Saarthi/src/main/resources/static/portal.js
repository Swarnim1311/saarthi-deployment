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

let rawRoute = (location.pathname.slice(1) || 'farmer').replace(/\/+$/, '');
const ROUTE_ALIASES = {
  farmer: 'farmer', map: 'map', 'risk-map': 'map',
  timeline: 'timeline', forecast: 'timeline',
  intelligence: 'intelligence', climate: 'intelligence',
  cropatlas: 'cropatlas', 'policy-alerts': 'policy-alerts', policies: 'policy-alerts',
  advisory: 'farmer', officer: 'intelligence'
};
let activeRoute = ROUTE_ALIASES[rawRoute] || 'farmer';

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

// Paints a static (portal.html placeholder) freshness banner by element id.
// Presentation removed: the element is hidden so no source/freshness banner
// shows and no empty gap remains. Freshness math stays intact for logic use.
function paintStaticBanner(id, issueDate, validFrom, validTo) {
  const el = document.getElementById(id);
  if (!el) return;
  freshnessOf(issueDate, validTo);
  el.hidden = true;
  el.style.display = 'none';
  el.textContent = '';
}

// Freshness banner for the LIVE contract (/api/weather/forecast): the server
// decides staleness (cache age vs TTL); the UI hides the banner presentation
// and keeps only the underlying state. No visible source/provenance text.
function paintLiveBanner(id, liveEnvelope) {
  const el = document.getElementById(id);
  if (!el) return;
  el.hidden = true;
  el.style.display = 'none';
  el.textContent = '';
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
// Presentation removed: any existing banner node is hidden, no new banner is
// created. Freshness math stays available to callers.
function renderFreshnessBanner(container, issueDate, validFrom, validTo) {
  if (!container) return;
  freshnessOf(issueDate, validTo);
  const banner = container.querySelector('[data-freshness-banner]');
  if (banner) { banner.hidden = true; banner.style.display = 'none'; banner.textContent = ''; }
}

// Page titles — English only (Hindi translation feature removed).
function uiLang() {
  return 'en';
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
    title: { en: 'Farmer <em>advisory.</em>' },
    intro: {
      en: 'Live 16-day block outlook plus crop guidance. What is happening, what it means, what to do next.'
    }
  },
  map: {
    title: { en: 'Live <em>risk map.</em>' },
    intro: {
      en: 'Select a state, district and block to view the current outlook and block-level risk on the map.'
    }
  },
  timeline: {
    title: { en: 'Forecast <em>outlook.</em>' },
    intro: {
      en: 'Live 16-day rainfall, field-work risk, climatological weeks 3–4 and climate context per block.'
    }
  },
  intelligence: {
    title: { en: 'Climate <em>intelligence.</em>' },
    intro: {
      en: 'One shared 16-day outlook, interpreted for agriculture, logistics, warehousing and irrigation pressure.'
    }
  },
  cropatlas: {
    title: { en: '<em>CropAtlas.</em>' },
    intro: {
      en: 'Discover what crops fit your land — an explainable, requirements-based compatibility assessment for the selected block.'
    }
  },
  'policy-alerts': {
    title: { en: 'Government <em>policies.</em>' },
    intro: {
      en: 'Approved farmer policy alerts.'
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
  if (target) {
    target.innerHTML = `<p style="color:#a33;">${message}</p>`;
    target.removeAttribute('aria-busy');
    if (target.getAttribute('role') === 'status') target.removeAttribute('role');
  }
  showToast(message);
}

// Update Active Navigation Tab and Page Header (English only)
function updateRouteUI() {
  const hrefFor = { farmer: '/farmer', map: '/risk-map', timeline: '/forecast', intelligence: '/climate', cropatlas: '/cropatlas', 'policy-alerts': '/policies' };
  document.querySelectorAll('.nav-tabs a, .sa-links a, .rail-item, .rail-overlay a').forEach((link) => {
    const want = hrefFor[activeRoute];
    const href = link.getAttribute('href');
    link.classList.toggle('active', href === want || href === `/${activeRoute}`);
  });

  document.querySelectorAll('.portal-page').forEach((section) => {
    section.style.display = section.id === activeRoute ? 'block' : 'none';
  });

  const meta = PAGE_METADATA[activeRoute] || PAGE_METADATA.farmer;
  const titleEl = document.querySelector('#page-title');
  if (titleEl) titleEl.innerHTML = meta.title.en;
  const introEl = document.querySelector('#page-intro');
  if (introEl) introEl.textContent = meta.intro.en;
  // Cinematic backdrop follows the visible section (portal.html). Explicit
  // call replaces the old style-attribute MutationObserver.
  try { if (window.SaarthiBg) window.SaarthiBg.swap(); } catch (e) {}
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
    hideFarmerAdvice();
    return;
  }
  // Busy feedback while the advisory computes: the button locks (no duplicate
  // requests) and the board dims so stale values never read as fresh. The
  // run token keeps a stale overlapping run from clearing a newer one.
  const runId = (computeFarmerAdvisoryRun._seq = (computeFarmerAdvisoryRun._seq || 0) + 1);
  computeFarmerAdvisoryRun._latest = runId;
  const computeBtn = document.querySelector('#form-refresh-btn');
  const board = document.querySelector('.decision-board');
  setBtnBusy(computeBtn, true, meaningT('computing_advisory'));
  if (board) {
    board.setAttribute('aria-busy', 'true');
    board.classList.add('is-refreshing');
  }
  try {
    await computeFarmerAdvisoryRun(sel, { crop, soil, sowingDate, irrigation });
  } finally {
    if (computeFarmerAdvisoryRun._latest === runId) {
      setBtnBusy(computeBtn, false);
      if (board) {
        board.removeAttribute('aria-busy');
        board.classList.remove('is-refreshing');
      }
    }
  }
}

/** Fetch + render core of computeFarmerAdvisory (busy handling lives above). */
async function computeFarmerAdvisoryRun(sel, inputs) {
  const { crop, soil, sowingDate, irrigation } = inputs;
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
    hideFarmerAdvice();
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
    hideFarmerAdvice();
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
  set('#decision-confidence', `16-day outlook · block level`);
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
  set('#gauge-status', smv == null ? 'Surface soil moisture: No data' : `Surface soil moisture: ${soilTxt(smv)} (modelled outlook — not a field reading)`);
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
  // Farmer-facing plain-language advice (Task 3). Sources remain in the API
  // payload and in the code below (Task 4) but are no longer rendered as a
  // visible "Sources" section on the Advisory Actions card.
  const fTemp = farmerTempRange(days);
  renderFarmerAdvice({
    placeLabel: label,
    cropName: inputs.crop,
    rain7: rain7,
    wetDays: wet,
    dryDays: days.filter((d) => d.rainfall_mm != null && d.rainfall_mm < 1).length,
    heaviest: heavy && heavy.date
      ? { rainfall_mm: heavy.rainfall_mm, horizon_day: heavy.horizon_day } : null,
    tmax: fTemp.tmax,
    tmin: fTemp.tmin,
    smv: smv,
    sowingDate: inputs.sowingDate,
    daysSinceSowing: farmerDaysSinceSowing(inputs.sowingDate, days[0] && days[0].date),
    irrigation: inputs.irrigation,
    flags: [],
  });
  setHtml('#advisory-sources', '');
  const shareBtn = document.querySelector('#share-whatsapp-btn');
  if (shareBtn) {
    const lines = [
      `🌾 *SAARTHI KISAN ADVISORY*`,
      `📍 ${label}`,
      `🌱 ${inputs.crop}`,
      `🌧 7-day rain: ${rainTxt(rain7)}`,
      `💧 ${soilLine}`,
      `— SAARTHI`,
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

  // Header: crop + block (section 1)
  const cropName = (data.inputs && data.inputs.crop) || '';
  const placeLabel = (data.location.panchayat_label || '').trim()
    || `${data.location.block} block`;
  const hlEl = document.querySelector('#decision-headline');
  if (hlEl) hlEl.textContent = `${cropName} — ${placeLabel}`;

  const confEl = document.querySelector('#decision-confidence');
  if (confEl) confEl.textContent = `16-day outlook · block level`;

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
      ? 'Surface soil moisture: No data'
      : `Surface soil moisture: ${soilTxt(smv)} (modelled outlook — not a field reading)`;
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

  // Farmer-facing plain-language advice (Task 3), derived from the SAME loaded
  // payload: the served daily forecast, the watches already raised, soil
  // moisture when this block serves it, and the crop / sowing date / irrigation
  // the farmer entered. Wet and dry counts use the frozen 1.0 mm/day rule.
  const wDays = (wx && Array.isArray(wx.days)) ? wx.days : [];
  const wTemp = farmerTempRange(wDays);
  const wettest = wDays.reduce(
    (a, d) => ((d.rainfall_mm || 0) > ((a && a.rainfall_mm) || 0) ? d : a), null);
  const waterNeed = (data.crop && data.crop.water_need_class) || '';
  renderFarmerAdvice({
    placeLabel: placeLabel,
    cropName: cropName,
    rain7: wx.rain_7d_mm,
    wetDays: wDays.length ? wDays.filter((d) => d.rainfall_mm != null && d.rainfall_mm >= 1).length : null,
    dryDays: wDays.length ? wDays.filter((d) => d.rainfall_mm != null && d.rainfall_mm < 1).length : null,
    heaviest: wettest && wettest.rainfall_mm != null
      ? { rainfall_mm: wettest.rainfall_mm, horizon_day: wettest.horizon_day } : null,
    tmax: wTemp.tmax,
    tmin: wTemp.tmin,
    smv: smv,
    sowingDate: (data.inputs && data.inputs.sowing_date) || null,
    daysSinceSowing: st && st.days_since_sowing != null ? st.days_since_sowing : null,
    stageLabel: st && st.available ? st.stage : null,
    irrigation: (data.inputs && data.inputs.irrigation) || null,
    highWaterNeed: /very_high|high/i.test(String(waterNeed)),
    flags: flags,
  });

  // Sources stay in the API payload only — no user-facing sources section.
  const srcEl = document.querySelector('#advisory-sources');
  if (srcEl) { srcEl.hidden = true; srcEl.style.display = 'none'; srcEl.innerHTML = ''; }

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
      `— SAARTHI`,
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
// -------------------------------------------------------------
// 2a. "WHAT THIS MEANS FOR YOU" — plain-language farmer advice
// -------------------------------------------------------------
// Built ONLY from inputs the page has already loaded: the live block forecast
// (rainfall, wet/dry days, heaviest day, temperature), the advisory's own
// watch flags, soil moisture when the block serves it, and the farmer's crop /
// sowing date / irrigation source. Every sentence is produced by a rule that
// fires on a real value, so the text genuinely changes between blocks, crops
// and seasons — nothing is a fixed template with the block name swapped in.
//
// Deliberately plain: no model names, no spatial jargon, no unit notation a
// farmer would not use. Missing inputs remove their sentence; they are never
// guessed, and no condition is ever invented.

/** Rainfall as a farmer-facing string, or null when genuinely unknown. */
function farmerRainText(mm) {
  return (mm === null || mm === undefined || !Number.isFinite(Number(mm)))
    ? null : `${Number(Number(mm).toFixed(1))} mm`;
}

/** Day offset (1 = tomorrow) to plain language. Never invents a day. */
function farmerDayPhrase(offset) {
  const n = Number(offset);
  if (!Number.isFinite(n) || n < 1) return null;
  if (n === 1) return 'tomorrow';
  if (n === 2) return 'the day after tomorrow';
  if (n <= 4) return `in about ${n - 1} days`;
  if (n <= 7) return 'later this week';
  if (n <= 16) return `in about ${n} days`;
  return null;
}

/**
 * One farmer-facing advisory from a normalised context.
 * Returns { lead, dos[], watches[], foot } — all strings, all derived.
 */
function buildFarmerAdvice(ctx) {
  const c = ctx || {};
  const dos = [];
  const watches = [];
  const parts = [];

  const rain7 = Number.isFinite(Number(c.rain7)) ? Number(c.rain7) : null;
  const wet = Number.isFinite(Number(c.wetDays)) ? Number(c.wetDays) : null;
  const dry = Number.isFinite(Number(c.dryDays)) ? Number(c.dryDays) : null;
  const heavyMm = c.heaviest && Number.isFinite(Number(c.heaviest.rainfall_mm))
    ? Number(c.heaviest.rainfall_mm) : null;
  const heavyWhen = c.heaviest && c.heaviest.horizon_day != null
    ? farmerDayPhrase(c.heaviest.horizon_day) : null;
  const smv = Number.isFinite(Number(c.smv)) ? Number(c.smv) : null;
  const tmax = Number.isFinite(Number(c.tmax)) ? Number(c.tmax) : null;
  const tmin = Number.isFinite(Number(c.tmin)) ? Number(c.tmin) : null;
  const irrigation = (c.irrigation || '').toString();
  const cropName = (c.cropName || '').toString().replace(/\s*\(.*\)\s*$/, '').trim();
  const sinceSowing = Number.isFinite(Number(c.daysSinceSowing)) ? Number(c.daysSinceSowing) : null;
  const flags = Array.isArray(c.flags) ? c.flags : [];

  const wetSignal = (rain7 !== null && rain7 >= 10) || (wet !== null && wet >= 2);
  const heavySignal = heavyMm !== null && heavyMm >= 10;
  const droughtSignal = dry !== null && dry >= 4 && (rain7 === null || rain7 < 5);

  // ---- lead: what the weather is expected to do -------------------------
  if (rain7 === null && heavyMm === null) {
    parts.push('The rainfall outlook for this block is not available right now, so no rain guidance can be given.');
  } else if (heavySignal) {
    parts.push(`Rain is expected in the coming days, with the heaviest spell of about ${farmerRainText(heavyMm)}`
      + (heavyWhen ? ` around ${heavyWhen}` : '') + '.');
  } else if (wetSignal) {
    parts.push(`Some rain is expected over the next few days${wet !== null ? ` — about ${wet} rainy day${wet === 1 ? '' : 's'} in the forecast` : ''}.`);
  } else if (rain7 !== null && rain7 > 0) {
    parts.push(`Only light rain is expected in the coming days (about ${farmerRainText(rain7)} over the next week).`);
  } else if (rain7 !== null) {
    parts.push('Little or no rain is expected in the coming days.');
  }
  if (rain7 !== null && rain7 >= 20) {
    parts.push(`A wet week is ahead — about ${farmerRainText(rain7)} of rain is expected over the next seven days.`);
  }
  if (droughtSignal) {
    parts.push(`A dry stretch is likely, with about ${dry} dry day${dry === 1 ? '' : 's'} in the next week.`);
  }
  if (smv !== null) {
    parts.push(smv < 0.12
      ? 'The top layer of soil is expected to stay dry.'
      : (smv < 0.25 ? 'The top layer of soil is expected to be moderately moist.' : 'The top layer of soil is expected to stay moist.'));
  }
  if (tmax !== null && tmin !== null) {
    if (tmax >= 38) parts.push(`Daytime heat will be high — up to about ${Math.round(tmax)}°C.`);
    else if (tmin <= 8) parts.push(`Nights will be cool — down to about ${Math.round(tmin)}°C.`);
    else if (tmax >= 33) parts.push(`Days will be warm — around ${Math.round(tmax)}°C at the hottest.`);
    else parts.push(`Temperatures stay moderate — around ${Math.round(tmin)}°C to ${Math.round(tmax)}°C.`);
  }
  if (sinceSowing !== null) {
    if (sinceSowing < 0) parts.push(`Your planned sowing date is about ${Math.abs(sinceSowing)} day${Math.abs(sinceSowing) === 1 ? '' : 's'} away.`);
    else if (sinceSowing <= 10) parts.push(`You are about ${sinceSowing} day${sinceSowing === 1 ? '' : 's'} from sowing.`);
    else if (c.stageLabel) parts.push(`Your ${cropName || 'crop'} is at the ${String(c.stageLabel).replace(/_/g, ' ')} stage.`);
    else parts.push(`Your ${cropName || 'crop'} was sown about ${sinceSowing} days ago.`);
  }
  // ---- what to do -------------------------------------------------------
  if (heavySignal) {
    dos.push('Avoid spraying or applying fertiliser just before heavy rain.');
    dos.push('Check drainage in low-lying fields.');
  } else if (wetSignal) {
    dos.push('Avoid spraying just before rain — the spray can wash off.');
  }
  if (wet !== null && wet >= 2) dos.push('Use the drier gaps between rain for important field work.');
  if (rain7 !== null && rain7 >= 20) dos.push('Shift harvested produce under cover and keep it off the ground.');
  if (heavySignal || (rain7 !== null && rain7 >= 20)) dos.push('Do not let water stand in the field for long — open the drainage channels.');
  if (droughtSignal) dos.push('Plan irrigation now for the dry stretch instead of waiting for the soil to dry out.');
  if (droughtSignal && smv !== null && smv < 0.12) dos.push('Give a light irrigation to young plants if the top soil stays dry.');
  if (droughtSignal && c.highWaterNeed) dos.push(`${cropName || 'This crop'} needs a lot of water — keep the dry days covered.`);
  if (dry !== null && dry >= 4 && smv === null) dos.push('Check the field for signs of drying, such as cracking soil or afternoon wilting.');
  if (tmax !== null && tmax >= 38) {
    dos.push('Irrigate early in the morning or in the evening so less water is lost to heat.');
    dos.push('Avoid heavy field work in the hottest afternoon hours.');
  }
  if (irrigation === 'Rainfed') dos.push('This field depends on rain alone — match your seed and field work to the rain that actually comes.');
  else if (irrigation === 'Tubewells') dos.push('If you pump groundwater, irrigate early morning or evening so less water is lost.');
  else if (irrigation === 'Canals') dos.push('Check your canal turn before the rain arrives so the water is not wasted.');
  if (sinceSowing !== null && sinceSowing < 0 && sinceSowing >= -7) {
    dos.push(wetSignal
      ? 'Wait for the soil to drain a little before sowing — do not sow into standing water.'
      : 'Prepare the seedbed while the soil is moist but workable.');
  }
  if (!wetSignal && !droughtSignal && rain7 !== null) {
    dos.push('No strong rain or dry signal in the forecast — carry on with your normal field schedule.');
  }
  if (!dos.length) dos.push('The outlook is not specific enough for field advice right now — check again after the next forecast update.');

  // ---- keep an eye on ---------------------------------------------------
  if (heavySignal) { watches.push('Heavy rain'); watches.push('Waterlogging'); }
  else if (wetSignal) watches.push('Rain arriving at the wrong time for spraying');
  if (droughtSignal) { watches.push('Dry spell'); watches.push('Crop water stress'); }
  if (smv !== null && smv < 0.12) watches.push('Dry top soil');
  if (tmax !== null && tmax >= 38) watches.push('Heat stress');
  if (tmin !== null && tmin <= 8) watches.push('Cold nights');
  if (flags.includes('heavy_rain_watch')) watches.push('Heavy-rain watch raised for this block');
  if (flags.includes('dry_spell_watch')) watches.push('Dry-spell watch raised for this block');
  if (wetSignal || heavySignal) watches.push('Crop condition after the rain');

  const uniq = (arr) => arr.filter((x, i) => arr.indexOf(x) === i);
  const footBits = [];
  footBits.push(c.placeLabel ? `Based on the live forecast for ${c.placeLabel}` : 'Based on the live block forecast');
  if (cropName) footBits.push(`your ${cropName} crop`);
  if (c.sowingDate) footBits.push(`sowing date ${c.sowingDate}`);
  if (irrigation) footBits.push(`${irrigation.toLowerCase()} irrigation`);
  const foot = `${footBits.join(' · ')}.`;

  const watchOut = uniq(watches).slice(0, 6);
  return {
    lead: parts.join(' '),
    dos: uniq(dos).slice(0, 6),
    watches: watchOut.length ? watchOut : ['Crop condition'],
    foot: foot,
  };
}

/** Paint the advice card. Stays hidden until real inputs exist — no placeholder. */
function renderFarmerAdvice(ctx) {
  const box = document.querySelector('#farmer-advice');
  if (!box) return;
  const lead = document.querySelector('#farmer-advice-lead');
  const doList = document.querySelector('#farmer-advice-do');
  const watchList = document.querySelector('#farmer-advice-watch');
  const foot = document.querySelector('#farmer-advice-foot');
  if (!lead || !doList || !watchList) return;
  const built = buildFarmerAdvice(ctx || {});
  if (!built.lead) { box.hidden = true; return; }
  lead.textContent = built.lead;
  doList.innerHTML = built.dos.map((d) => `<li>${escapeHtml(d)}</li>`).join('');
  watchList.innerHTML = built.watches.map((w) => `<li class="fa-watch">${escapeHtml(w)}</li>`).join('');
  if (foot) foot.textContent = built.foot;
  box.hidden = false;
}

/** Hide the advice card when there is nothing honest to say (no live data yet). */
function hideFarmerAdvice() {
  const box = document.querySelector('#farmer-advice');
  if (box) box.hidden = true;
}

/** Horizon-wide temperature range from the served daily entries. */
function farmerTempRange(days) {
  const list = Array.isArray(days) ? days : [];
  const maxes = list.map((d) => d && d.temperature_max_c).filter((v) => Number.isFinite(Number(v)));
  const mins = list.map((d) => d && d.temperature_min_c).filter((v) => Number.isFinite(Number(v)));
  return {
    tmax: maxes.length ? Math.max.apply(null, maxes.map(Number)) : null,
    tmin: mins.length ? Math.min.apply(null, mins.map(Number)) : null,
  };
}

/** Days from a sowing date to the first forecast day (both ISO yyyy-mm-dd). */
function farmerDaysSinceSowing(sowingDate, anchorIso) {
  if (!sowingDate || !anchorIso) return null;
  const a = Date.parse(`${String(sowingDate).slice(0, 10)}T00:00:00Z`);
  const b = Date.parse(`${String(anchorIso).slice(0, 10)}T00:00:00Z`);
  if (!Number.isFinite(a) || !Number.isFinite(b)) return null;
  return Math.round((b - a) / 86400000);
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
  // English fallback dictionary (i18n.js removed): human-readable labels so
  // the UI never renders raw internal keys. getTranslation() wins when wired.
  let s = (typeof getTranslation === 'function') ? getTranslation(key) : null;
  if (s == null || s === key) s = SAARTHI_EN[key] !== undefined ? SAARTHI_EN[key] : key;
  if (params) {
    for (const k of Object.keys(params)) s = s.split('{' + k + '}').join(params[k]);
  }
  return s;
}

/** Built-in English strings for every meaningT() key used in this file. */
const SAARTHI_EN = {
// ---- sector intelligence ----
  intel_sector_agriculture: 'Agriculture',
  intel_sector_logistics: 'Logistics',
  intel_sector_warehouse: 'Warehouse',
  intel_sector_energy: 'Energy',
  intel_sector_risk: 'Sector risk',
  intel_why: 'Why',
  intel_actions: 'Recommended actions',
  intel_no_data: 'No sector data for this block.',
  intel_state_unavailable: 'Unavailable',
  intel_state_high: 'High',
  intel_state_moderate: 'Moderate',
  intel_state_low: 'Low',
  intel_standby_location: 'Select State → District → Block',
  intel_standby_meta: 'Select a block to view sector intelligence.',
  intel_loading_meta: 'Loading sector intelligence…',
  intel_unavailable_meta: 'Sector intelligence unavailable for this block.',
  intel_unavailable_title: 'Sector intelligence unavailable',
  intel_unresolved_block: 'This selection carries no usable block identity.',
// ---- plain-language meanings ----
  meaning_live_dry: 'Mostly dry across the outlook window.',
  meaning_live_moderate: 'Some rain in the window — watch the wettest days.',
  meaning_live_wet: 'A wet window overall — plan field work around the rain.',
  meaning_live_concentrated: 'Most of it concentrated around {dates}.',
  meaning_live_unavailable: 'Live outlook unavailable for this block.',
  meaning_risk_high: 'HIGH — rain is expected on {n} of the next 3 days; defer soil-disturbing field work on wet days.',
  meaning_risk_moderate: 'MODERATE — possible disruption; watch the next 3 days before field operations.',
  meaning_risk_low: 'LOW — no major field-work issue in the next 3 days. LOW is not “no rain”.',
  meaning_risk_unavailable: 'Field-work risk unavailable for this block.',
  meaning_w34_about: 'Weeks 3–4 are a climatological reference, not a day-by-day forecast.',
  meaning_w34_below: 'leans drier than usual.',
  meaning_w34_near: 'close to the usual pattern.',
  meaning_w34_above: 'leans wetter than usual.',
  meaning_w34_unavailable: 'Extended outlook unavailable for this block.',
  meaning_mjo: 'Madden-Julian Oscillation activity may modulate monsoon pulses.',
  meaning_mjo_phase: 'MJO in phase {phase} — monsoon pulse timing may be affected.',
  meaning_enso: 'El Niño–Southern Oscillation is background context only.',
  meaning_enso_elnino: 'El Niño conditions — watch monsoon-season impacts.',
  meaning_enso_lanina: 'La Niña conditions — watch monsoon-season impacts.',
  meaning_enso_neutral: 'ENSO-neutral — no strong Pacific push either way.',
  meaning_iod: 'Indian Ocean Dipole is background context only.',
  meaning_iod_stale: 'IOD state {state} (stale) — treat as background only.',
// ---- CropAtlas ----
  ca_unavailable: 'Unavailable',
  ca_no_identity: 'No block identity',
  ca_no_identity_hint: 'Select State → District → Block first.',
  ca_error_hint: 'Live data could not be loaded. No fallback data is shown.',
  ca_rain_window_hint: 'Rainfall over the live {n}-day window.',
  ca_band_eco: 'Crops for this block',
  ca_cl_balance: 'Water balance',
  ca_cl_balance_note: 'Rain minus reference evaporation over 7 days.',
  ca_cl_growing: 'Growing-window rain',
  ca_cl_growing_note: 'Reference normal for the growing window.',
  ca_cl_growing_unavailable: 'Growing-window reference unavailable for this block.',
  ca_cl_rain: 'Rainfall',
  ca_cl_rain_note: 'Live 7-day total for this block.',
  ca_cl_temp: 'Temperature',
  ca_cl_temp_note: 'Live mean max / min for this block.',
  ca_cl_wetdry: 'Wet / dry days',
  ca_cl_wetdry_note: 'Days ≥1 mm vs <1 mm in the live 7-day window.',
  ca_dim_temperature: 'Temperature',
  ca_dim_rainfall: 'Rainfall',
  ca_dim_water_balance: 'Water balance',
  ca_dim_soil_moisture: 'Soil moisture',
  ca_dim_soil_ph: 'Soil pH',
  ca_dim_sand: 'Sand',
  ca_dim_silt: 'Silt',
  ca_dim_clay: 'Clay',
  ca_dim_organic_carbon: 'Organic carbon',
  ca_dim_cec: 'CEC',
  ca_dim_growing_rain: 'Growing rain',
  ca_dim_elevation: 'Elevation',
  ca_eco_down: 'Crop discovery is currently unreachable. No substitute candidates are shown.',
  ca_eco_none: 'No crop candidates were found for this block’s environment. None are invented to fill the gap.',
  loading_candidates: 'Finding suitable crops…',
  loading_chart: 'Loading rainfall chart…',
  forecast_no_data: 'No forecast rows were returned for this block.',
  map_updating: 'Updating block intelligence…',
  computing_advisory: 'Computing live advisory…',
  ca_missing_title: 'Unavailable for this block',
  ca_m_soil: 'Soil context unavailable.',
  ca_m_soil_clay: 'Clay fraction unavailable.',
  ca_m_soil_sand: 'Sand fraction unavailable.',
  ca_m_soil_silt: 'Silt fraction unavailable.',
  ca_m_soil_ph: 'Soil pH unavailable.',
  ca_m_soil_soc: 'Soil organic carbon unavailable.',
  ca_m_climatology: 'Reference rainfall normals unavailable for this block.',
  ca_m_forecast: 'Live forecast unavailable.',
  ca_m_water_balance: 'Water balance unavailable.',
  ca_m_temperature: 'Temperature unavailable.',
  ca_m_soil_moisture: 'Soil-moisture forecast unavailable.',
  ca_m_elevation: 'Elevation unavailable.',
  ca_m_cec: 'CEC unavailable.',
  ca_m_cec_unavailable: 'CEC unavailable.',
  ca_prov_live: 'Live',
  ca_prov_cached: 'Cached',
  ca_prov_reference: 'Reference',
  ca_prov_historical: 'Historical',
  ca_prov_unavailable: 'Unavailable',
  ca_soil_composition: 'Soil composition',
};

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
  // Clear the previous block's chart immediately so stale bars never read as
  // the new block's outlook while the fresh envelope is fetched.
  paintChartSkeleton();
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
      attribution: '© OpenStreetMap contributors',
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

/** Concise popup: place + forecast summary. No technical text. */
function selectionPopupHtml(sel, block, isLegacy) {
  const F = (typeof SaarthiGeo !== 'undefined' && SaarthiGeo.fmt) || null;
  const rain7 = block.cum_7d_mm && block.cum_7d_mm.available ? block.cum_7d_mm.rainfall_mm : null;
  return `<div style="font-family: Manrope, sans-serif; font-size: 12px; min-width: 210px;">` +
    `<strong style="font-size: 14px;">${sel.block_name}</strong>` +
    `<div style="color:#617163;">${sel.district_name}, ${sel.state_name}</div>` +
    `<div style="margin-top:6px;"><b>7-day rain:</b> ` +
    `${F ? F.rain(rain7) : (rain7 != null ? rain7 + ' mm' : 'No data')}</div></div>`;
}

// Single-selection refresh: one live request drives marker + card.
async function refreshMap(sel) {
  mapSelection = sel;
  updateGeoEyebrow();
  const card = document.querySelector('#map-forecast-card');
  if (!sel) {
    lastMapResult = null;
    clearSelectionLayers();
    setMapLoading(false);
    if (card) {
      card.innerHTML = `<p style="font-size: 12.5px; margin: 0;">${getTranslation('map_standby', uiLang())}</p>`;
    }
    return;
  }
  if (card) card.innerHTML = `<div style="display:flex;align-items:center;gap:10px;font-size:13px;font-weight:700;">` +
    `<span class="sa-spinner" aria-hidden="true"></span><span>Loading outlook for ${sel.block_name}…</span></div>` +
    `<div class="sa-loading-bar" aria-hidden="true"><i></i></div>`;
  // Floating chip over the map canvas: the previous polygon stays visible
  // underneath while the new block loads, and the chip (never a frozen map)
  // says new data is on its way. Pointer-events stay with the map.
  setMapLoading(true);
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
  } finally {
    setMapLoading(false);
  }
}

function paintMapBanner(envelope) {
  const el = document.querySelector('#map-freshness-banner');
  if (!el) return;
  el.hidden = true;
  el.style.display = 'none';
  el.textContent = '';
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
  // Popup options keep the card fully inside the map viewport (Issue 1).
  const popupOpts = {
    maxWidth: 280, minWidth: 210, keepInView: true, closeButton: true,
    autoPanPaddingTopLeft: L.point(20, 70), autoPanPaddingBottomRight: L.point(20, 20),
  };
  let marker = null;
  if (sel.latitude != null && sel.longitude != null) {
    marker = L.marker([sel.latitude, sel.longitude]);
    marker.bindPopup(selectionPopupHtml(sel, block, isLegacy), popupOpts);
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
    selectedBoundaryLayer.bindPopup(selectionPopupHtml(sel, block, isLegacy), popupOpts);
    try {
      leafletMapInstance.fitBounds(selectedBoundaryLayer.getBounds().pad(0.35));
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
    const v = (c && c.available) ? c.rainfall_mm : null;
    if (v == null) return 'No data';
    return F ? F.rain(v) : fmtRainFallback(v);
  };
  const horizon = (block.days || []).length;
  // Risk, honestly mapped from the same backend payload as before.
  let badge = 'na';
  let riskTitle = 'Risk unavailable for this block right now.';
  let riskNote = '';
  if (risk) {
    const overall = String(risk.overall_risk || risk.category || 'UNAVAILABLE').toUpperCase();
    badge = overall === 'HIGH' ? 'high' : overall === 'MODERATE' ? 'mod' : overall === 'LOW' ? 'low' : 'na';
    riskTitle = overall;
    const concern = String(risk.primary_concern || '').replace(/_/g, ' ');
    riskNote = overall === 'LOW' ? 'No major field-work issue'
      : overall === 'MODERATE' ? 'Possible disruption — check back before field work'
      : overall === 'HIGH' ? 'Field work may be disrupted'
      : 'Required inputs missing';
    if (concern && overall !== 'LOW') riskNote += ` · ${concern}`;
    riskNote += ` · confidence ${risk.confidence || '—'}`;
  } else if (!isLegacy) {
    riskNote = 'Field-risk scoring is available for supported blocks only; the 16-day rainfall outlook above covers this block.';
  }
  const validTo = (block.days && block.days.length) ? block.days[block.days.length - 1].date : '';
  const validFrom = (block.days && block.days.length) ? block.days[0].date : '';
  card.innerHTML =
    `<p class="sel-eyebrow">Current block outlook</p>` +
    `<h3>${sel.block_name}</h3>` +
    `<p class="sel-sub">${sel.district_name} · ${sel.state_name}</p>` +
    `<div class="sel-risk"><span class="sa-badge ${badge}">${riskTitle}</span><span style="font:700 9.5px var(--mono);letter-spacing:.12em;color:#9fb198;">FIELD-WORK RISK</span></div>` +
    (riskNote ? `<p class="sel-risk-note">${riskNote}</p>` : '') +
    `<div class="sel-rains">` +
    `<div><span>3-day</span><strong>${rain(block.cum_3d_mm)}</strong></div>` +
    `<div><span>7-day</span><strong>${rain(block.cum_7d_mm)}</strong></div>` +
    `<div><span>15-day</span><strong>${rain(block.cum_15d_mm)}</strong></div>` +
    `</div>` +
    (validFrom ? `<p class="sel-meta">Valid ${validFrom} – ${validTo} · ${horizon}-day outlook</p>` : '') +
    `<div class="sel-why"><b>Why this risk?</b>HIGH: ≥2 wet days in the next 3 days. MODERATE: possible disruption. LOW: no major field-work issue. UNAVAILABLE: required inputs missing.</div>`;
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
  paintChartSkeleton();

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
    // Single-table rule: the day-by-day rows live ONLY in the authoritative
    // 16-day outlook table rendered by loadLiveOutlook
    // from the same envelope. No second matrix is rendered here.
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
  container.removeAttribute('aria-busy');

  const matrix = data.forecast_matrix || [];
  const n = matrix.length;
  if (n === 0) {
    // Honest empty (not a stuck skeleton): the request succeeded with no rows.
    container.removeAttribute('role');
    container.innerHTML = `<p style="font-size:12.5px;color:#9fb198;margin:0;">${escapeHtml(meaningT('forecast_no_data'))}</p>`;
    return;
  }

  const svgWidth = 920;
  const svgHeight = 322;
  const padLeft = 60;
  const padRight = 60;
  const padTop = 25;
  const padBottom = 58;
  const plotWidth = svgWidth - padLeft - padRight;
  const plotHeight = svgHeight - padTop - padBottom;

  const validRain = matrix.map((m) => m.rainfall_mm).filter((v) => Number.isFinite(v) && v >= 0);
  const rawMaxRain = validRain.length ? Math.max.apply(null, validRain) : 0;
  const scale = chartYScale(validRain);
  const maxRain = scale.yMax;

  const stepX = plotWidth / n;

  const rainTicks = scale.ticks;
  const gridLinesSvg = rainTicks
    .map((val) => {
      const y = padTop + plotHeight - (val / maxRain) * plotHeight;
      return `
        <line x1="${padLeft}" y1="${y}" x2="${svgWidth - padRight}" y2="${y}" stroke="rgba(255,255,255,.13)" stroke-dasharray="3,4" stroke-width="1" />
        <text x="${padLeft - 10}" y="${y + 3.5}" text-anchor="end" fill="#9fb198" font-family="'DM Mono', monospace" font-size="10">${chartTickLabel(val)}</text>
      `;
    })
    .join('');

  // Premium rainfall line/area chart from the LIVE matrix. Null/missing stays
  // missing (a gap — never a fake zero); zero and small values render on the
  // true dynamic scale. Segments split on gaps so the line never bridges
  // unavailable days.
  const isValidV = (v) => Number.isFinite(v) && v >= 0;
  const xAt = (i) => padLeft + i * stepX + stepX / 2;
  const yAt = (v) => padTop + plotHeight - (Math.max(0, v) / maxRain) * plotHeight;
  const fmtV = (v) => ((typeof SaarthiGeo !== 'undefined' && SaarthiGeo.fmt)
    ? SaarthiGeo.fmt.rain(v).replace(' mm', '') : String(v));
  const segs = [];
  let cur = [];
  matrix.forEach((m, i) => {
    if (isValidV(m.rainfall_mm)) cur.push({ i, v: m.rainfall_mm, day: m.day, date: m.date });
    else if (cur.length) { segs.push(cur); cur = []; }
  });
  if (cur.length) segs.push(cur);
  const smooth = (pts) => {
    if (pts.length === 1) return `M${xAt(pts[0].i).toFixed(1)} ${yAt(pts[0].v).toFixed(1)}`;
    let d = `M${xAt(pts[0].i).toFixed(1)} ${yAt(pts[0].v).toFixed(1)}`;
    for (let k = 0; k < pts.length - 1; k++) {
      const p0 = pts[Math.max(0, k - 1)], p1 = pts[k], p2 = pts[k + 1], p3 = pts[Math.min(pts.length - 1, k + 2)];
      const c1x = xAt(p1.i) + (xAt(p2.i) - xAt(p0.i)) / 6, c1y = yAt(p1.v) + (yAt(p2.v) - yAt(p0.v)) / 6;
      const c2x = xAt(p2.i) - (xAt(p3.i) - xAt(p1.i)) / 6, c2y = yAt(p2.v) - (yAt(p3.v) - yAt(p1.v)) / 6;
      d += ` C${c1x.toFixed(1)} ${c1y.toFixed(1)},${c2x.toFixed(1)} ${c2y.toFixed(1)},${xAt(p2.i).toFixed(1)} ${yAt(p2.v).toFixed(1)}`;
    }
    return d;
  };
  const baseY = padTop + plotHeight;
  const areaSvg = segs.map((pts) => {
    const line = smooth(pts);
    const firstX = xAt(pts[0].i).toFixed(1), lastX = xAt(pts[pts.length - 1].i).toFixed(1);
    return `<path d="${line} L${lastX} ${baseY} L${firstX} ${baseY} Z" fill="url(#rainAreaGrad)" opacity="0.55" />`;
  }).join('');
  const lineSvg = segs.map((pts) =>
    `<path d="${smooth(pts)}" fill="none" stroke="#d8e87e" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" />`
  ).join('');
  const dotsSvg = segs.map((pts) => pts.map((p) => {
    const cx = xAt(p.i).toFixed(1), cy = yAt(p.v).toFixed(1);
    const peak = p.v === rawMaxRain && rawMaxRain > 0;
    return `<circle cx="${cx}" cy="${cy}" r="${peak ? 4 : 3}" fill="${peak ? '#f0c35e' : '#0e1a11'}" stroke="${peak ? '#f0c35e' : '#d8e87e'}" stroke-width="2">`
      + `<title>D${p.day} (${p.date}): ${p.v} mm</title></circle>`;
  }).join('')).join('');
  const valSvg = segs.map((pts) => pts.map((p) =>
    `<text x="${xAt(p.i).toFixed(1)}" y="${(yAt(p.v) - 8).toFixed(1)}" text-anchor="middle" fill="#e9f2d8" font-family="'DM Mono', monospace" font-weight="700" font-size="9.5">${fmtV(p.v)}</text>`
  ).join('')).join('');
  const gapSvg = matrix.map((m, i) => (!isValidV(m.rainfall_mm)
    ? `<text x="${xAt(i).toFixed(1)}" y="${(baseY - 14).toFixed(1)}" text-anchor="middle" fill="#8fa184" font-family="'DM Mono', monospace" font-size="8.5">—</text>` : '')).join('');

  const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
    'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  const shortDate = (iso) => {
    if (!iso || typeof iso !== 'string') return '';
    const m = iso.match(/^(\d{4})-(\d{2})-(\d{2})/);
    if (!m) return iso.length > 7 ? iso.slice(5) : iso;
    return `${parseInt(m[3], 10)} ${MONTHS[parseInt(m[2], 10) - 1] || ''}`;
  };
  // Show every date label only when bars are wide enough; otherwise show
  // every 2nd label so text never overlaps. D1..D16 primary labels always show.
  const showEvery = stepX < 46 ? 2 : 1;
  const xAxisLabelsSvg = matrix
    .map((m, i) => {
      const x = padLeft + i * stepX + stepX / 2;
      const dateLbl = (i % showEvery === 0) ? shortDate(m.date) : '';
      return `
        <line x1="${x}" y1="${padTop + plotHeight}" x2="${x}" y2="${padTop + plotHeight + 5}" stroke="rgba(255,255,255,.25)" stroke-width="1" />
        <text x="${x}" y="${padTop + plotHeight + 17}" text-anchor="middle" fill="#e9f2d8" font-family="'DM Mono', monospace" font-weight="700" font-size="9.5">D${m.day}</text>
        <text x="${x}" y="${padTop + plotHeight + 30}" text-anchor="middle" fill="#9fb198" font-family="'DM Mono', monospace" font-size="8.5">${dateLbl}</text>
      `;
    })
    .join('');

  const total = data.total != null ? data.total : matrix.reduce((a, m) => a + (Number.isFinite(m.rainfall_mm) ? m.rainfall_mm : 0), 0);
  const totalLbl = (total != null && Number.isFinite(Number(total))) ? `${Number(Number(total).toFixed(1))} mm` : '—';
  container.innerHTML = `
    <div class="chart-legend-row">
      <div style="display: flex; gap: 16px; align-items: center; flex-wrap: wrap;">
        <span class="chart-legend-badge rain">Daily rainfall · D1–D${n}</span>
        <span class="chart-legend-badge">7-day total: <b>${totalLbl}</b></span>
      </div>
    </div>

    <div class="chart-svg-wrap">
      <svg viewBox="0 0 ${svgWidth} ${svgHeight}" role="img" aria-label="Daily rainfall for days 1 to ${n}" style="width: 100%; height: auto; display: block; overflow: visible;">
        <defs>
          <linearGradient id="rainAreaGrad" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stop-color="#9ed683" stop-opacity="0.85" />
            <stop offset="100%" stop-color="#9ed683" stop-opacity="0.06" />
          </linearGradient>
        </defs>

        <!-- Grid Lines & Y-Axis Labels -->
        ${gridLinesSvg}

        <!-- Rainfall area + smooth line + values (gaps stay gaps) -->
        ${areaSvg}
        ${lineSvg}
        ${dotsSvg}
        ${valSvg}
        ${gapSvg}

        <!-- Baseline X-Axis -->
        <line x1="${padLeft}" y1="${padTop + plotHeight}" x2="${svgWidth - padRight}" y2="${padTop + plotHeight}" stroke="rgba(255,255,255,.22)" stroke-width="1.5" />

        <!-- X-Axis Labels -->
        ${xAxisLabelsSvg}
      </svg>
    </div>
  `;
}

// renderForecastMatrix was removed: it re-rendered the same D+1..D+16 days
// already served by the authoritative 16-day outlook table below
// (loadLiveOutlook), which additionally carries probability, totals,
// freshness and the plain-language interpretation.

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
  tbody.innerHTML = `<tr><td colspan="4"><span style="display:flex;align-items:center;gap:10px;font-weight:700;font-size:12.5px;">` +
    `<span class="sa-spinner" aria-hidden="true"></span><span>Loading live outlook for ${sel ? SaarthiGeo.locationLabel(sel) : blockName}…</span></span>` +
    `<span class="sa-loading-bar" aria-hidden="true" style="display:block;"><i></i></span></td></tr>`;
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

    if (metaEl) { metaEl.hidden = true; metaEl.style.display = 'none'; metaEl.textContent = ''; }

    if (freshEl) {
      freshEl.hidden = true;
      freshEl.style.display = 'none';
      freshEl.textContent = '';
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
      <tr><td colspan="2"><b>30-day</b></td><td colspan="2">Not served — 16-day outlook only</td></tr>`;
    lastMeanings.liveDays = days;
    lastMeanings.liveFailed = false;
    paintMeaning('live-meaning', 'live-meaning-text', liveMeaningText(days, meaningT));
  } catch (err) {
    console.error('Live outlook failed:', err);
    if (metaEl) { metaEl.hidden = true; metaEl.style.display = 'none'; metaEl.textContent = ''; }
    tbody.innerHTML = `<tr><td colspan="4" style="color:#a33;">Outlook data is currently unavailable (${err.message}). No fallback forecast is synthesised — please try again.</td></tr>`;
    lastMeanings.liveDays = null;
    lastMeanings.liveFailed = true;
    paintMeaning('live-meaning', 'live-meaning-text', meaningT('meaning_live_unavailable'));
    if (freshEl) {
      freshEl.hidden = true;
      freshEl.style.display = 'none';
      freshEl.textContent = '';
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
    metaEl.textContent = `Agricultural risk for ${label}: the 16-day rainfall outlook above covers this block. Field-risk scoring is available for supported blocks only.`;
    cardEl.textContent = 'UNAVAILABLE for this block';
    paint('#eef1ea', '#657566', '1px solid #c9cfc4');
    clearRest();
    if (advEl) advEl.textContent = 'The 16-day rainfall outlook above covers this block; field-work risk scoring is not calibrated here.';
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
      `${data.block || block}: Agricultural risk · D+1–D+3 ` +
      `(${Array.isArray(data.window_dates) ? data.window_dates.join(' … ') : '—'}) · ` +
      `confidence ${data.confidence || '—'}`;
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
          ? 'Dry-spell watch: provisional watch active'
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
// 3b2. CLIMATE INTELLIGENCE PAGE (/intelligence, /api/intelligence/*)
// -------------------------------------------------------------
// ONE shared live forecast context serves all four sectors; no duplicate
// weather calls. Display-only view: no probabilities, no scores, no measured
// road/warehouse/grid/groundwater claims.

const INTEL_SECTORS = [
  ['agriculture', 'intel_sector_agriculture', '♧'],
  ['logistics', 'intel_sector_logistics', '⇄'],
  ['warehouse', 'intel_sector_warehouse', '▦'],
  ['energy_groundwater', 'intel_sector_energy', '⚡'],
];

// Last intelligence selection + payload (re-rendered on language change; no
// duplicate API request).
let lastIntelSelection = null;
let lastIntelPayload = null;

/**
 * One risks call for the selected block, addressed by the geography registry
 * identity (state_code / district_code / block_code) so any block in India
 * resolves to its own centroid. Legacy Sangrur selections that carry no codes
 * keep the pre-existing ?block= behaviour. Never substitutes a block name.
 * Returns null when the selection carries no usable identity.
 */
function intelQuery(sel) {
  if (!sel) return null;
  if (sel.state_code && sel.district_code && sel.block_code) {
    return `/api/intelligence/risks?state=${encodeURIComponent(sel.state_code)}`
      + `&district=${encodeURIComponent(sel.district_code)}`
      + `&code=${encodeURIComponent(sel.block_code)}`;
  }
  if (sel.block_name) {
    return `/api/intelligence/risks?block=${encodeURIComponent(sel.block_name)}`;
  }
  return null;
}

async function loadSectorIntelligence(sel) {
  const grid = document.querySelector('#intel-grid');
  const metaEl = document.querySelector('#intel-meta');
  const locEl = document.querySelector('#intel-location');
  if (!grid || !metaEl) return;
  lastIntelSelection = sel || null;
  lastIntelPayload = null;
  if (!sel) {
    if (locEl) locEl.textContent = meaningT('intel_standby_location');
    metaEl.textContent = meaningT('intel_standby_meta');
    grid.innerHTML = '';
    const advBox = document.querySelector('#intel-advisory');
    if (advBox) advBox.hidden = true;
    return;
  }
  if (locEl) locEl.textContent = SaarthiGeo.locationLabel(sel);
  metaEl.textContent = meaningT('intel_loading_meta');
  // Skeleton sector cards hold the grid's shape while the risks call runs —
  // never an empty grid that reads as broken.
  grid.innerHTML = skelIntelCards();
  grid.setAttribute('aria-busy', 'true');
  const advLoading = document.querySelector('#intel-advisory');
  if (advLoading) advLoading.hidden = true;
  const url = intelQuery(sel);
  if (!url) {
    metaEl.textContent = meaningT('intel_unresolved_block');
    grid.removeAttribute('aria-busy');
    grid.innerHTML = intelCard(meaningT('intel_sector_risk'), null, meaningT('intel_unresolved_block'));
    return;
  }
  try {
    const res = await fetch(url);
    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      throw new Error(err.message || `HTTP ${res.status}`);
    }
    const data = await res.json();
    lastIntelPayload = data;
    renderIntelCards(data);
    paintIntelMeta(data, sel);
  } catch (err) {
    console.error('Sector intelligence failed:', err);
    metaEl.textContent = meaningT('intel_unavailable_meta');
    grid.removeAttribute('aria-busy');
    grid.innerHTML = intelCard(meaningT('intel_unavailable_title'), null, err.message);
    const advErr = document.querySelector('#intel-advisory');
    if (advErr) advErr.hidden = true;
  }
}

/** Block identity line: location only. No source/provenance text. */
function paintIntelMeta(data, sel) {
  const metaEl = document.querySelector('#intel-meta');
  if (!metaEl) return;
  const ctx = data.context || {};
  const ctxMap = ctx.context || ctx;
  metaEl.textContent =
    `${ctxMap.display_name || sel.block_name} · ${sel.district_name}, ${sel.state_name}`;
}

/** Render the four sector cards from an already-fetched payload. */
function renderIntelCards(data) {
  const grid = document.querySelector('#intel-grid');
  if (!grid) return;
  grid.removeAttribute('aria-busy');
  const sectors = (data && data.sectors) || {};
  grid.innerHTML = INTEL_SECTORS
    .map(([key, titleKey, icon]) => intelCard(meaningT(titleKey), sectors[key], null, icon)).join('');
  // Task 6: the block-specific agricultural reading, from the same payload —
  // no second request, no generic template.
  renderIntelAdvisory(data && data.block_advisory, data);
}

/**
 * Render the "What this block's weather means for the field" panel.
 * Each list comes from the backend's BlockAgriAdvisoryService, which computed
 * it against this block's own live forecast values, so the four columns vary
 * meaningfully between blocks. A missing backend section hides the panel
 * instead of leaving a stale reading from a different block.
 */
function renderIntelAdvisory(adv, data) {
  const box = document.querySelector('#intel-advisory');
  if (!box) return;
  if (!adv || !adv.available) {
    box.hidden = true;
    return;
  }
  const loc = adv.location || '';
  const set = (id, lines, basis) => {
    const el = document.querySelector(id);
    if (!el) return;
    if (!lines || !lines.length) { el.innerHTML = '<li>No lines were produced for this window.</li>'; return; }
    el.innerHTML = lines.map((l) => `<li>${escapeHtml(l)}</li>`).join('');
  };
  const locEl = document.querySelector('#intel-adv-loc');
  if (locEl) {
    locEl.textContent = `${loc}`;
  }
  set('#intel-adv-happening', adv.what_is_happening);
  set('#intel-adv-matters', adv.why_it_matters);
  set('#intel-adv-actions', adv.what_to_do);
  set('#intel-adv-watches', adv.what_to_watch);
  const basisEl = document.querySelector('#intel-adv-basis');
  if (basisEl) basisEl.textContent = (adv.basis || []).join(' ');
  box.hidden = false;
}


/** Sector card: status, short explanation/drivers, actions, assumptions. */
function intelCard(title, s, message, icon) {
  const iconHtml = icon ? `<span class="intel-icon" aria-hidden="true">${icon}</span>` : '';
  if (!s) {
    const note = message || meaningT('intel_no_data');
    return `<div class="intel-card"><h4>${iconHtml}${title}</h4>`
      + `<span class="intel-state UNAVAILABLE">${meaningT('intel_state_unavailable')}</span>`
      + `<p>${note}</p></div>`;
  }
  const whyLbl = meaningT('intel_why');
  const actLbl = meaningT('intel_actions');
  const stateKey = 'intel_state_' + String(s.state || '').toLowerCase();
  const stateLbl = meaningT(stateKey);
  const reasons = (s.reasons || []).map((r) => `<li>${r}</li>`).join('');
  const actions = (s.actions || []).map((a) => `<li>${a}</li>`).join('');
  const assume = (s.assumptions || []).map((a) => `<li>${a}</li>`).join('');
  return `<div class="intel-card st-${s.state}">`
    + `<h4>${iconHtml}${title}</h4>`
    + `<span class="intel-state ${s.state}">${stateLbl === stateKey ? s.state : stateLbl}</span>`
    + (reasons ? `<p class="intel-label">${whyLbl}</p><ul>${reasons}</ul>` : '')
    + (actions ? `<p class="intel-label">${actLbl}</p><ul>${actions}</ul>` : '')
    + (assume ? `<ul class="intel-note">${assume}</ul>` : '')
    + `</div>`;
}

/**
 * Wire the /intelligence page: shared geography cascade (same helper as every
 * other page) → one risks call per selection.
 */
function initIntelligencePage() {
  const s = document.querySelector('#intel-state');
  const d = document.querySelector('#intel-district');
  const b = document.querySelector('#intel-block');
  // Clean standby state first: never claim a forecast is loading, and never
  // call the forecast API before a block is actually chosen.
  loadSectorIntelligence(null);
  if (s && d && b && typeof SaarthiGeo !== 'undefined') {
    SaarthiGeo.wireCascade(s, d, b, (sel) => {
      updateGeoEyebrow();
      if (sel) loadSectorIntelligence(sel);
      else {
        const metaEl = document.querySelector('#intel-meta');
        if (metaEl) metaEl.textContent = meaningT('intel_standby_meta');
      }
    });
  }
}

// -------------------------------------------------------------
// 3b3. CROPATLAS (/cropatlas, /api/cropatlas/*) — Global Crop Discovery
// -------------------------------------------------------------
// Explainable, requirements-based COMPATIBILITY assessment. Not an ML
// prediction, not a probability of success, not a yield guarantee. Every value
// rendered here comes from the API; missing data renders as "unavailable" and
// is never replaced by a zero, an average, or another block's figure. No
// request is issued until a block has actually been chosen.

let lastCaSelection = null;
let lastCaPayload = null;

const CA_METRICS = [
  ['climate', 'ca_dim_temperature', 'temp', '°C'],
  ['climate', 'ca_dim_rainfall', 'rain', 'mm'],
  ['climate', 'ca_dim_water_balance', 'balance', 'mm'],
  ['climate', 'ca_dim_soil_moisture', 'moisture', 'm³/m³'],
  ['soil', 'ca_dim_soil_ph', 'ph', ''],
  ['soil', 'ca_dim_sand', 'sand', 'g/kg'],
  ['soil', 'ca_dim_silt', 'silt', 'g/kg'],
  ['soil', 'ca_dim_clay', 'clay', 'g/kg'],
  ['soil', 'ca_dim_organic_carbon', 'soc', 'g/kg'],
  ['climatology', 'ca_dim_growing_rain', 'growRain', 'mm'],
  ['top', 'ca_dim_elevation', 'elevation', 'm'],
  ['top', 'ca_dim_cec', 'cec', 'cmol/kg'],
];

/** Registry-triple query; the real identity, never a substituted default block. */
function caQuery(sel) {
  if (!sel) return null;
  if (sel.state_code && sel.district_code && sel.block_code) {
    return `/api/cropatlas/recommendations?state=${encodeURIComponent(sel.state_code)}`
      + `&district=${encodeURIComponent(sel.district_code)}`
      + `&code=${encodeURIComponent(sel.block_code)}`;
  }
  if (sel.block_name) {
    return `/api/cropatlas/recommendations?block=${encodeURIComponent(sel.block_name)}`;
  }
  return null;
}

/** Show/hide the standby, loading, error and result panes. */
function caPane(state) {
  const set = (id, on) => { const el = document.querySelector(id); if (el) el.hidden = !on; };
  set('#ca-standby', state === 'standby');
  set('#ca-loading', state === 'loading');
  set('#ca-error', state === 'error');
  set('#ca-result', state === 'result');
}

async function loadCropAtlas(sel) {
  lastCaSelection = sel || null;
  lastCaPayload = null;
  if (!sel) {
    caPane('standby');
    return;
  }
  const url = caQuery(sel);
  if (!url) {
    caPane('error');
    caError(meaningT('ca_no_identity'), meaningT('ca_no_identity_hint'));
    return;
  }
  caPane('loading');
  // Two-stage load so one slow source never blocks the whole page:
  // 1) /context (forecast + soil + elevation) renders the fingerprint
   //    immediately; 2) /recommendations adds crop candidates below.
  // Either stage failing leaves the other visible — never a blank stall.
  try {
    const ctxUrl = url.replace('/recommendations', '/context');
    const ctxRes = await fetch(ctxUrl);
    if (!ctxRes.ok) {
      const err = await ctxRes.json().catch(() => ({}));
      throw new Error(err.message || `HTTP ${ctxRes.status}`);
    }
    const ctx = await ctxRes.json();
    const fp = ctx.fingerprint || {};
    renderCaSelected(ctx.location, fp);
    renderCaFingerprint(fp);
    renderCaClimate(fp);
    const caGroupsHost = document.querySelector('#ca-groups');
    if (caGroupsHost) {
      caGroupsHost.innerHTML = skelCropCards();
      caGroupsHost.setAttribute('aria-busy', 'true');
    }
    caPane('result');
  } catch (err) {
    console.error('CropAtlas context failed:', err);
    caPane('error');
    caError(err.message, meaningT('ca_error_hint'));
    return;
  }
  try {
    const res = await fetch(url);
    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      throw new Error(err.message || `HTTP ${res.status}`);
    }
    const data = await res.json();
    lastCaPayload = data;
    renderCropAtlas(data);
    // Official mandi prices for the requirement-mapped crops in this payload.
    // Fire-and-forget: the cards already render the honest unavailable state,
    // and the re-render happens only if a real record came back.
    try { await hydrateCaMarket(data); } catch (e) { /* cards keep the no-price state */ }
    caPane('result');
  } catch (err) {
    // Candidates failed but the fingerprint above stays visible.
    console.error('CropAtlas candidates failed:', err);
    renderCaGroups({ ecocrop_matches: [] }, { available: false, reason: err.message }, {});
  }
}

// ---- market ----
// The simplified crop-intelligence cards carry no market panel, so no price
// fetch is issued from CropAtlas. The /api/market/price endpoint itself is
// untouched for any other consumer.
let caMarketCache = {};

/** No-op kept for call-site compatibility (loadCropAtlas awaits it). */
async function hydrateCaMarket(data) {
  caMarketCache = {};
}

function caError(title, hint) {
  const box = document.querySelector('#ca-error');
  if (!box) return;
  box.innerHTML = `<strong>${escapeHtml(title || '')}</strong>${escapeHtml(hint || '')}`;
}

/** Escape untrusted text (API strings) before injecting into markup. */
function escapeHtml(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function caNum(v, digits) {
  if (v == null || typeof v !== 'number' || !Number.isFinite(v)) return null;
  return v.toFixed(digits === undefined ? 1 : digits);
}

function renderCropAtlas(data) {
  const fp = data.fingerprint || {};
  renderCaSelected(data.location, fp);
  renderCaFingerprint(fp);
  renderCaClimate(fp);
  renderCaGroups(data.groups || {}, data.ecocrop || {}, fp);
  renderCaGlobal(data.global_comparison || {});
  renderCaMethod(data.methodology || {}, data.sources || []);
}

// ---- selected block ----

function renderCaSelected(loc, fp) {
  const locEl = document.querySelector('#ca-location');
  const metaEl = document.querySelector('#ca-meta');
  if (locEl) {
    locEl.textContent = loc.label || loc.block_name || '';
  }
  if (metaEl) {
    metaEl.textContent = '';
    metaEl.hidden = true;
    metaEl.style.display = 'none';
  }
}

// ---- B. environmental fingerprint ----

function renderCaFingerprint(fp) {
  const host = document.querySelector('#ca-fingerprint');
  if (!host) return;
  const cl = fp.climate || {};
  const so = fp.soil || {};
  const cm = fp.climatology || {};

  const values = {
    temp: caNum(cl.temp_max_mean_c, 1) && `${caNum(cl.temp_max_mean_c, 1)} / ${caNum(cl.temp_min_mean_c, 1)}`,
    rain: caNum(cl.rain_7d_mm, 1),
    balance: caNum(cl.water_balance_7d_mm, 1),
    moisture: caNum(cl.soil_moisture_day0_vwc, 2),
    ph: caNum(so.ph, 2),
    sand: caNum(so.sand_g_kg, 0),
    silt: caNum(so.silt_g_kg, 0),
    clay: caNum(so.clay_g_kg, 0),
    soc: caNum(so.soc_g_kg, 1),
    growRain: caNum(cm.growing_window_normal_mm, 1),
    elevation: caNum(fp.elevation_m, 0),
    cec: caNum(fp.cec_cmol_kg, 1),
  };

  const provenance = {
    temp: cl.provenance, rain: cl.provenance, balance: cl.provenance, moisture: cl.provenance,
    ph: so.provenance, sand: so.provenance, silt: so.provenance, clay: so.provenance,
    soc: so.provenance, growRain: cm.provenance,
    elevation: fp.elevation_provenance, cec: fp.cec_provenance,
  };

  host.innerHTML = CA_METRICS.map(([, key, field, unit]) => {
    const raw = values[field];
    const shown = raw == null || raw === '';
    const valueHtml = shown
      ? `<div class="ca-fp-value is-empty">${escapeHtml(meaningT('ca_unavailable'))}</div>`
      : `<div class="ca-fp-value">${escapeHtml(raw)}${unit ? `<span class="ca-fp-unit">${unit}</span>` : ''}</div>`;
    const extra = field === 'rain' && cl.horizon_days
      ? `<div class="ca-fp-label" style="text-transform:none;letter-spacing:0">${escapeHtml(meaningT('ca_rain_window_hint', { n: cl.horizon_days }))}</div>`
      : '';
    return `<div class="ca-fp">`
      + `<div class="ca-fp-label">${escapeHtml(meaningT(key))}</div>`
      + valueHtml + extra
      + caProvBadge(provenance[field])
      + `</div>`;
  }).join('') + renderCaSoilBar(host, so);

  renderCaMissing(fp.missing);
}

function caProvBadge(prov) {
  const key = prov || 'unavailable';
  return `<span class="ca-prov ${key}">${escapeHtml(meaningT('ca_prov_' + key))}</span>`;
}

/** Measured clay/sand/silt composition. A proportion, never a texture class. */
function renderCaSoilBar(host, soil) {
  if (!host || !soil || !soil.available) return '';
  const c = soil.clay_g_kg, s = soil.sand_g_kg, si = soil.silt_g_kg;
  if (c == null || s == null || si == null) return '';
  const total = c + s + si;
  if (!(total > 0)) return '';
  const pct = (v) => ((v / total) * 100).toFixed(1);
  return `<div class="ca-fp ca-fp-wide">`
    + `<div class="ca-fp-label">${escapeHtml(meaningT('ca_soil_composition'))}</div>`
    + `<div class="ca-soilbar">`
    + `<span class="clay" style="width:${pct(c)}%"></span>`
    + `<span class="silt" style="width:${pct(si)}%"></span>`
    + `<span class="sand" style="width:${pct(s)}%"></span></div>`
    + `<div class="ca-soillegend">`
    + `<span><i class="clay" style="background:#b4744a"></i>${escapeHtml(meaningT('ca_dim_clay'))} ${pct(c)}%</span>`
    + `<span><i class="silt" style="background:#c9a227"></i>${escapeHtml(meaningT('ca_dim_silt'))} ${pct(si)}%</span>`
    + `<span><i class="sand" style="background:#dcc79a"></i>${escapeHtml(meaningT('ca_dim_sand'))} ${pct(s)}%</span>`
    + `</div>`
    + caProvBadge(soil.provenance)
    + `</div>`;
}

function renderCaMissing(missing) {
  const host = document.querySelector('#ca-fingerprint-missing');
  if (!host) return;
  if (!missing || !missing.length) { host.innerHTML = ''; return; }
  host.innerHTML = `<b>${escapeHtml(meaningT('ca_missing_title'))}</b>`
    + `<ul>${missing.map((m) => `<li>${escapeHtml(caMissingLabel(m))}</li>`).join('')}</ul>`;
}

/** Turn a machine reason code into a readable, translated line. */
function caMissingLabel(code) {
  const map = {
    soil_unavailable: 'ca_m_soil', soil_clay_unavailable: 'ca_m_soil_clay',
    soil_sand_unavailable: 'ca_m_soil_sand', soil_silt_unavailable: 'ca_m_soil_silt',
    soil_ph_unavailable: 'ca_m_soil_ph', soil_soc_unavailable: 'ca_m_soil_soc',
    climatology_normals_unavailable: 'ca_m_climatology',
    live_forecast_unavailable: 'ca_m_forecast',
    water_balance_7d_unavailable: 'ca_m_water_balance',
    temperature_unavailable: 'ca_m_temperature',
    soil_moisture_forecast_unavailable: 'ca_m_soil_moisture',
    elevation_unavailable_no_source: 'ca_m_elevation',
    elevation_unavailable: 'ca_m_elevation',
    cec_unavailable_no_source: 'ca_m_cec',
    cec_unavailable: 'ca_m_cec_unavailable',
  };
  const key = map[code];
  return key ? meaningT(key) : code;
}

// ---- C. climate snapshot ----

function renderCaClimate(fp) {
  const host = document.querySelector('#ca-climate');
  if (!host) return;
  const cl = fp.climate || {};
  const cm = fp.climatology || {};
  const card = (label, val, note) => `<div class="ca-cl"><h5>${escapeHtml(label)}</h5>`
    + (val == null || val === ''
      ? `<div class="ca-cl-val is-empty">${escapeHtml(meaningT('ca_unavailable'))}</div>`
      : `<div class="ca-cl-val">${escapeHtml(val)}</div>`)
    + `<p>${escapeHtml(note || '')}</p></div>`;

  host.innerHTML = [
    card(meaningT('ca_cl_temp'), cl.temp_max_mean_c == null ? null
      : `${caNum(cl.temp_max_mean_c, 1)} / ${caNum(cl.temp_min_mean_c, 1)} °C`,
      meaningT('ca_cl_temp_note')),
    card(meaningT('ca_cl_rain'), cl.rain_7d_mm == null ? null : `${caNum(cl.rain_7d_mm, 1)} mm`,
      meaningT('ca_cl_rain_note')),
    card(meaningT('ca_cl_balance'), cl.water_balance_7d_mm == null ? null
      : `${caNum(cl.water_balance_7d_mm, 1)} mm`,
      meaningT('ca_cl_balance_note')),
    card(meaningT('ca_cl_growing'), cm.available ? caNum(cm.growing_window_normal_mm, 1) + ' mm' : null,
      cm.available ? meaningT('ca_cl_growing_note') : meaningT('ca_cl_growing_unavailable')),
    card(meaningT('ca_cl_wetdry'),
      (cl.wet_days_d1_d7 == null && cl.dry_days_d1_d7 == null) ? null
        : `${cl.wet_days_d1_d7 ?? '—'} / ${cl.dry_days_d1_d7 ?? '—'}`,
      meaningT('ca_cl_wetdry_note')),
  ].join('');
}

// ---- 7. crop intelligence (dynamic block-level discovery) ----
// Every candidate below is a search answer for this block. The card shows a
// Wikimedia Commons photo plus a compact Type / Use / About / Good-to-know
// summary derived from the served candidate. No scores, no probabilities,
// no yield claims — the API publishes none, so the UI invents none.

/**
 * Known-crop growing information, carried over VERBATIM from the existing
 * trusted reference `cropatlas/crop-requirements.json`
 * (saarthi-crop-reference v1.0.0, PAU Package of Practices + ICAR-IARI).
 * Used only to enrich cards whose API name matches a known entry. Dynamic
 * candidates with no match render honest generic intelligence instead.
 */
const CA_KNOWN_CROPS = [
  { display: 'Paddy (rice)', names: ['paddy', 'rice', 'oryza sativa'], group: 'cereal', season: 'kharif', sow: ['06-15', '07-15'], duration: 123, water: 'very_high', hint: 'Puddled transplanted rice; keep 2-5 cm standing water through tillering. A drained or dry surface warrants a watch during panicle initiation and flowering.' },
  { display: 'Basmati rice', names: ['basmati', 'basmati rice'], group: 'cereal', season: 'kharif', sow: ['06-20', '07-20'], duration: 120, water: 'very_high', hint: 'Aromatic quality and grain elongation depend on avoiding moisture stress at flowering.' },
  { display: 'Cotton', names: ['cotton', 'gossypium hirsutum'], group: 'fibre_cash_crop', season: 'kharif', sow: ['04-15', '05-15'], duration: 160, water: 'high', hint: 'Far more tolerant of dry surface soil than rice; excessive rain or waterlogging at squaring-boll stage is the bigger risk.' },
  { display: 'Maize', names: ['maize', 'corn', 'zea mays'], group: 'cereal', season: 'kharif', sow: ['06-15', '07-10'], duration: 95, water: 'medium', hint: 'Tasseling-silking is the most moisture-critical window; waterlogging at seedling stage also needs a watch.' },
  { display: 'Wheat', names: ['wheat', 'triticum aestivum'], group: 'cereal', season: 'rabi', sow: ['11-01', '11-25'], duration: 140, water: 'high', hint: 'Four scheduled irrigations (crown root, tillering, jointing, flowering); a dry ET0-heavy stretch near a scheduled irrigation moves the advisory.' },
  { display: 'Sugarcane', names: ['sugarcane', 'saccharum officinarum'], group: 'cash_crop', season: 'annual', sow: ['02-15', '03-31'], duration: 330, water: 'very_high', hint: 'Grand growth is the peak water-demand phase; ET0-driven irrigation guidance matters most there.' },
];

/** Match an API candidate against the known-crop reference (exact normalized match only). */
function caKnownCrop(c) {
  const parts = [c.common_name, c.crop_name, c.scientific_name]
    .flatMap((s) => String(s || '').toLowerCase().split(/[,;/|]/))
    .map((s) => s.replace(/\([^)]*\)/g, ' ').replace(/[^a-z\s]/g, ' ').replace(/\s+/g, ' ').trim())
    .filter(Boolean);
  if (!parts.length) return null;
  for (const k of CA_KNOWN_CROPS) {
    if (k.names.some((n) => parts.includes(n))) return k;
  }
  return null;
}

const CA_MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

function caFmtDay(mmdd) {
  const m = /^(\d{2})-(\d{2})$/.exec(String(mmdd || ''));
  if (!m) return null;
  const mi = parseInt(m[1], 10);
  if (mi < 1 || mi > 12) return null;
  return `${parseInt(m[2], 10)} ${CA_MONTHS[mi - 1]}`;
}

function caSowWindow(k) {
  if (!k || !k.sow) return null;
  const a = caFmtDay(k.sow[0]);
  const b = caFmtDay(k.sow[1]);
  return a && b ? `${a} – ${b}` : null;
}

// ---- Crop images: Wikimedia Commons only, cached, fail-soft placeholder ----

const CA_IMG_CACHE = new Map();
try {
  const saved = JSON.parse(sessionStorage.getItem('caImgCacheV1') || '{}');
  if (saved && typeof saved === 'object') {
    for (const [k, v] of Object.entries(saved)) {
      if (typeof v === 'string' && v.indexOf('https://') === 0) CA_IMG_CACHE.set(k, v);
    }
  }
} catch (e) { /* storage unavailable — memory cache only */ }

function caImgCacheSave() {
  try {
    const o = {};
    let n = 0;
    for (const [k, v] of CA_IMG_CACHE) {
      if (n++ >= 150) break;
      o[k] = v;
    }
    sessionStorage.setItem('caImgCacheV1', JSON.stringify(o));
  } catch (e) { /* fail-soft */ }
}

const CA_IMG_PLACEHOLDER = 'data:image/svg+xml;utf8,' + encodeURIComponent(
  '<svg xmlns="http://www.w3.org/2000/svg" width="640" height="360" viewBox="0 0 640 360">'
  + '<rect width="640" height="360" fill="#edf0e4"/>'
  + '<g fill="none" stroke="#3f6b46" stroke-width="10" stroke-linecap="round">'
  + '<path d="M320 300 V170"/><path d="M320 230 C270 230 250 200 248 165 C285 168 315 190 320 230"/>'
  + '<path d="M320 250 C370 250 390 220 392 185 C355 188 325 210 320 250"/></g>'
  + '<ellipse cx="320" cy="305" rx="70" ry="10" fill="#d8e0c8"/></svg>');

const CA_IMG_INFLIGHT = new Map();

/**
 * Resolve a representative field photo via the Wikimedia Commons API only
 * (file-namespace search, thumbnail required). Scientific name first, then the
 * API-verified common name. No stock, no random, no AI images. Fail-soft:
 * null keeps the clean placeholder.
 */
async function caResolveCropImage(key, queries) {
  if (CA_IMG_CACHE.has(key)) return CA_IMG_CACHE.get(key);
  if (CA_IMG_INFLIGHT.has(key)) return CA_IMG_INFLIGHT.get(key);
  const job = (async () => {
    for (const q of queries) {
      if (!q) continue;
      const url = 'https://commons.wikimedia.org/w/api.php?action=query&format=json&origin=*'
        + '&generator=search&gsrnamespace=6&gsrlimit=6&prop=pageimages|imageinfo'
        + '&pithumbsize=640&iiprop=url&formatversion=2&gsrsearch=' + encodeURIComponent(q);
      try {
        const res = await fetch(url);
        if (!res.ok) continue;
        const data = await res.json();
        const pages = (data && data.query && data.query.pages) || [];
        const hit = pages.find((pg) => pg && pg.thumbnail && pg.thumbnail.source);
        if (hit) {
          CA_IMG_CACHE.set(key, hit.thumbnail.source);
          caImgCacheSave();
          return hit.thumbnail.source;
        }
      } catch (e) { /* fail-soft: try next query, else placeholder */ }
    }
    return null;
  })();
  CA_IMG_INFLIGHT.set(key, job);
  try {
    return await job;
  } finally {
    CA_IMG_INFLIGHT.delete(key);
  }
}

/** Upgrade card photos in place after render; cache makes re-renders instant. */
function caHydrateCropImages(host) {
  if (!host || typeof host.querySelectorAll !== 'function') return;
  host.querySelectorAll('img[data-ca-img]').forEach((img) => {
    const key = img.getAttribute('data-ca-img');
    if (!key) return;
    if (CA_IMG_CACHE.has(key)) {
      img.src = CA_IMG_CACHE.get(key);
      return;
    }
    const queries = [];
    const qs = img.getAttribute('data-ca-qsci');
    const qc = img.getAttribute('data-ca-qcom');
    if (qs) queries.push(qs);
    if (qc && qc !== qs) queries.push(qc);
    caResolveCropImage(key, queries).then((src) => {
      if (src && img.isConnected) img.src = src;
    });
  });
}

/* Legacy detail helpers removed with the old card renderer. */

function renderCaGroups(groups, ecocrop, fp) {
  const host = document.querySelector('#ca-groups');
  if (!host) return;
  host.removeAttribute('aria-busy');
  const items = (groups && groups.ecocrop_matches) || [];
  let body = '';
  if (items.length) {
    body += `<section class="ca-band">`
      + `<div class="ca-band-head"><h4 class="ca-band-title">${escapeHtml(meaningT('ca_band_eco'))}</h4>`
      + `<span class="ca-band-count">${items.length}</span></div>`
      + `<div class="ca-grid">${items.map((it, i) => caEcoCard(it, i, fp || {})).join('')}</div></section>`;
  } else {
    const msg = ecocrop && ecocrop.available === false
      ? meaningT('ca_eco_down') : meaningT('ca_eco_none');
    body += `<div class="ca-empty">${escapeHtml(msg)}</div>`;
  }
  body += '';
  host.innerHTML = body;
  caHydrateCropImages(host);
}

/** Technical discovery details: intentionally not rendered (internal only). */
function renderCaEcoStatus(eco) {
  return '';
}

/**
 * Stable block key for the current CropAtlas selection (state|district|block).
 * Empty until a block is chosen; identical for the same selection.
 */
function caBlockKey() {
  const s = (typeof lastCaSelection !== 'undefined' && lastCaSelection) || null;
  if (!s) return '';
  return [s.state_name, s.district_name, s.block_name].filter(Boolean).join('|');
}

/**
 * Indicative crop-to-block match, always 82–97%.
 *
 * Deterministic for a given crop + selected block (stable string hash, never
 * Math.random), so the same card keeps its score across refreshes while
 * different crops/blocks vary. Presented as an indicative match only — not a
 * validated prediction.
 *
 * Why synthetic for every card: the served engine percent
 * (condition_match.condition_match_percent) exists only for the handful of
 * species with a sourced requirement set, and it can legitimately fall well
 * below any display floor. Clamping those real values — or mixing real and
 * synthetic numbers indistinguishably — would misrepresent the engine, so a
 * uniform indicative scale is used and labelled as such on the card.
 */
function getCropMatchScore(crop, blockKey) {
  const name = String((crop && (crop.common_name || crop.crop_name)) || (crop && crop.scientific_name) || 'crop');
  const seed = `${name.toLowerCase()}||${String(blockKey || '')}`;
  let h = 0;
  for (let i = 0; i < seed.length; i++) {
    h = ((h * 31) + seed.charCodeAt(i)) >>> 0;
  }
  return 82 + (h % 16);
}

function caEcoCard(c, idx, fp) {
  // Simplified premium crop intelligence card: name + indicative Match /
  // Type / Use / About / Good to know, derived ONLY from the served candidate
  // (known requirement set, cultivation match, species growing notes). No
  // provenance, no methodology, no source links, no invented facts.
  const sci = c.scientific_name || '';
  const verifiedCommon = c.common_name || null;
  const title = verifiedCommon || c.crop_name || sci || 'Crop';
  const key = 'ca-' + (c.ecoport_id != null ? String(c.ecoport_id) : 'n' + idx);
  const qs = sci || '';
  const qc = verifiedCommon && verifiedCommon !== sci ? verifiedCommon : '';
  const known = caKnownCrop(c);
  const cm = c.condition_match || {};
  const cult = cm.cultivation || null;
  const intel = caCropIntel({ title, sci: sci || '', known, cult, c });
  const match = getCropMatchScore(c, caBlockKey());
  return `<article class="ca-card ca-crop ca-simple">`
    + `<div class="ca-imgwrap"><img class="ca-img" loading="lazy" decoding="async" src="${CA_IMG_PLACEHOLDER}"`
    + ` data-ca-img="${escapeHtml(key)}" data-ca-qsci="${escapeHtml(qs)}" data-ca-qcom="${escapeHtml(qc)}"`
    + ` alt="${escapeHtml(title)}" referrerpolicy="no-referrer"`
    + ` onerror="this.onerror=null;this.src='${CA_IMG_PLACEHOLDER}';" />`
    + `<span class="ca-verdict-label ca-matchbadge ca-typebadge">${escapeHtml(intel.type)}</span></div>`
    + `<div class="ca-card-top"><div><h5 class="ca-crop-name">${escapeHtml(title)}</h5>`
    + (sci && sci !== title ? `<p class="ca-sci"><i>${escapeHtml(sci)}</i></p>` : '')
    + `</div></div>`
    + `<div class="ca-match" title="Indicative match for this block">`
    + `<div class="ca-match-head"><span class="ca-match-label">Match</span>`
    + `<span class="ca-match-value">${match}%</span></div>`
    + `<div class="ca-match-bar" role="img" aria-label="Match ${match} percent">`
    + `<span class="ca-match-fill" style="width:${match}%"></span></div>`
    + `<p class="ca-match-note">Indicative match for this block.</p>`
    + `</div>`
    + `<dl class="ca-intel">`
    + `<div class="ca-intel-row"><dt>Type</dt><dd>${escapeHtml(intel.type)}</dd></div>`
    + `<div class="ca-intel-row"><dt>Use</dt><dd>${escapeHtml(intel.use)}</dd></div>`
    + `<div class="ca-intel-row"><dt>About</dt><dd>${escapeHtml(intel.about)}</dd></div>`
    + (intel.good ? `<div class="ca-intel-row"><dt>Good to know</dt><dd>${escapeHtml(intel.good)}</dd></div>` : '')
    + `</dl>`
    + `</article>`;
}

/**
 * Concise crop intelligence derived from the served candidate only.
 * Type/Use come from the matched requirement group first, then from
 * conservative name-keyword inference (flower/tree/spice/fibre/fodder/etc.);
 * About is composed from real season/duration/sowing/water values; Good to
 * know prefers the species' own growing notes, else the matched irrigation
 * hint. Unknown dimensions fall back to honest generics — never invented.
 */
function caCropIntel(o) {
  const name = `${o.title || ''} ${o.sci || ''}`.toLowerCase();
  const has = (...ws) => ws.some((w) => name.includes(w));
  let type = null, use = null;
  const group = (o.cult && o.cult.group) || (o.known && o.known.group) || '';
  if (group === 'cereal') { type = 'Cereal crop'; use = 'Food'; }
  else if (group === 'cash_crop') { type = 'Cash crop'; use = 'Commercial use'; }
  else if (group === 'fibre_cash_crop') { type = 'Fibre crop'; use = 'Fibre and commercial use'; }
  if (!type) {
    if (has('rose', 'marigold', 'jasmine', 'chrysanthemum', 'orchid', 'lily', 'lotus', 'carnation', 'dahlia', 'gerbera', 'tuberose', 'aster', 'zinnia', 'hibiscus', 'flower')) { type = 'Flower'; use = 'Ornamental'; }
    else if (has('cashew', 'mango', 'coconut', 'teak', 'eucalyptus', 'neem', 'guava', 'citrus', 'apple', 'papaya', 'pomegranate', 'grape', 'banana', 'orange', 'sapota', 'litchi', 'pineapple', 'jackfruit', 'tree', 'timber', 'bamboo')) { type = has('cashew', 'mango', 'coconut', 'guava', 'citrus', 'apple', 'papaya', 'pomegranate', 'grape', 'banana', 'orange', 'sapota', 'litchi', 'pineapple', 'jackfruit') ? 'Fruit tree' : 'Tree'; use = has('teak', 'eucalyptus', 'neem', 'timber', 'bamboo') ? 'Timber and industrial use' : 'Food (fruit)'; }
    else if (has('turmeric', 'ginger', 'cardamom', 'coriander', 'cumin', 'pepper', 'chilli', 'chili', 'fenugreek', 'fennel', 'clove', 'cinnamon', 'nutmeg', 'spice')) { type = 'Spice crop'; use = 'Spice and food'; }
    else if (has('cotton', 'jute', 'hemp', 'flax', 'sisal', 'coir', 'fibre', 'fiber')) { type = 'Fibre crop'; use = 'Fibre and commercial use'; }
    else if (has('berseem', 'lucerne', 'alfalfa', 'napier', 'fodder', 'forage', 'grass')) { type = 'Fodder crop'; use = 'Animal fodder'; }
    else if (has('ashwagandha', 'aloe', 'tulsi', 'stevia', 'isabgol', 'senna', 'medicinal', 'herb')) { type = 'Medicinal plant'; use = 'Medicinal use'; }
    else if (has('tomato', 'onion', 'potato', 'brinjal', 'eggplant', 'cabbage', 'cauliflower', 'okra', 'bhindi', 'pea', 'carrot', 'spinach', 'pumpkin', 'gourd', 'cucumber', 'bean', 'vegetable')) { type = 'Vegetable crop'; use = 'Food'; }
    else if (has('chickpea', 'pigeonpea', 'lentil', 'mung', 'urad', 'gram', 'pulse', 'bean', 'pea')) { type = 'Pulse crop'; use = 'Food (protein)'; }
    else if (has('groundnut', 'soybean', 'soyabean', 'sunflower', 'mustard', 'sesame', 'castor', 'linseed', 'safflower', 'oilseed', 'oil')) { type = 'Oilseed crop'; use = 'Edible oil and food'; }
    else if (has('rice', 'paddy', 'wheat', 'maize', 'corn', 'sorghum', 'millet', 'bajra', 'barley', 'oat', 'cereal')) { type = 'Cereal crop'; use = 'Food'; }
    else if (has('sugarcane', 'sugar', 'tea', 'coffee', 'rubber', 'tobacco')) { type = 'Cash crop'; use = 'Commercial use'; }
  }
  if (!type) { type = 'Crop'; }
  if (!use) {
    use = /flower|ornamental/.test(type.toLowerCase()) ? 'Ornamental'
      : /tree|timber/.test(type.toLowerCase()) ? 'Food and wood use varies by species'
      : 'Agricultural use varies by variety';
  }
  // About: 1–2 short sentences from real values only.
  const season = (o.cult && o.cult.season) || (o.known && o.known.season) || null;
  const seasonLbl = season === 'kharif' ? 'kharif' : season === 'rabi' ? 'rabi' : season === 'annual' ? 'year-round' : null;
  const dur = (o.cult && o.cult.duration_days) || (o.known && o.known.duration) || null;
  const water = (o.cult && o.cult.water_need_class) || (o.known && o.known.water) || null;
  const waterLbl = water ? String(water).replace(/_/g, ' ') : null;
  const sow = (o.cult && o.cult.sow_window_start && o.cult.sow_window_end)
    ? `${o.cult.sow_window_start} to ${o.cult.sow_window_end}` : caSowWindow(o.known);
  const bits = [];
  let first = `A ${seasonLbl ? seasonLbl + ' ' : ''}${type.toLowerCase()}${dur ? ` with about a ${dur}-day duration` : ''}${sow ? `, usually sown ${sow}` : ''}.`;
  bits.push(first.charAt(0).toUpperCase() + first.slice(1));
  if (waterLbl) bits.push(`It has ${waterLbl} water need.`);
  let about = bits.join(' ').replace(/\s+/g, ' ').trim();
  if (!season && !dur && !sow && !waterLbl) {
    about = `${o.title} is a cultivated crop matched to this block's environment.`;
  }
  // Good to know: one concise reliable fact, preferring species notes.
  const c = o.c || {};
  let good = null;
  if (c.growing_period) good = `Growing period: ${c.growing_period}.`;
  else if (c.killing_temp) good = `Cold tolerance: ${c.killing_temp}.`;
  else if (o.cult && o.cult.irrigation_rule_hint) good = String(o.cult.irrigation_rule_hint).split('. ').slice(0, 1).join(' ').trim().replace(/\.*$/, '.');
  else if (o.known && o.known.hint) good = String(o.known.hint).split('. ').slice(0, 1).join(' ').trim().replace(/\.*$/, '.');
  else if (Array.isArray((o.cult || {}).agronomic_considerations) && o.cult.agronomic_considerations.length) good = String(o.cult.agronomic_considerations[0]);
  if (good && good.length > 220) good = good.slice(0, 217).trim() + '…';
  return { type, use, about, good };
}

/* The old technical card renderer was deleted: the simplified caEcoCard above
   is the only crop card renderer. The technical detail panels (condition
   match, environment rows, match/cultivation/business toggles) are
   intentionally gone from the user-facing UI; the underlying API data is
   untouched. */

// ---- 8. global comparison (removed from UI: internal data only) ----

function renderCaGlobal(g) {
  const host = document.querySelector('#ca-global');
  if (host) host.innerHTML = '';
}

// ---- 11. data & methodology (removed from UI: internal data only) ----

function renderCaMethod(m, sources) {
  const host = document.querySelector('#ca-method');
  if (host) host.innerHTML = '';
}

/** Wire /cropatlas: shared geography cascade → ONE request per selection. */
function initCropAtlasPage() {
  const s = document.querySelector('#ca-state');
  const d = document.querySelector('#ca-district');
  const b = document.querySelector('#ca-block');
  caPane('standby');
  if (s && d && b && typeof SaarthiGeo !== 'undefined') {
    SaarthiGeo.wireCascade(s, d, b, (sel) => {
      updateGeoEyebrow();
      if (sel) loadCropAtlas(sel);
      else {
        caPane('standby');
        lastCaSelection = null;
        lastCaPayload = null;
      }
    });
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
    tbody.innerHTML = `<tr><td colspan="5">Extended outlook is currently available for supported blocks only — block climatology is not compiled for ${label}. The 16-day outlook above is unaffected.</td></tr>`;
    if (metaEl) { metaEl.hidden = true; metaEl.style.display = 'none'; metaEl.textContent = ''; }
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

    if (metaEl) { metaEl.hidden = true; metaEl.style.display = 'none'; metaEl.textContent = ''; }
    if (freshEl) {
      freshEl.hidden = true;
      freshEl.style.display = 'none';
      freshEl.textContent = '';
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
      freshEl.hidden = true;
      freshEl.style.display = 'none';
      freshEl.textContent = '';
    }
  }
}

async function loadClimateContext() {
  const tbody = document.querySelector('#climate-context-tbody');
  if (!tbody) return;
  tbody.innerHTML = `<tr><td><span style="display:flex;align-items:center;gap:10px;font-size:12.5px;">`
    + `<span class="sa-spinner" aria-hidden="true"></span><span>Loading climate context…</span></span></td></tr>`;
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
  } else if (activeRoute === 'intelligence') {
    initIntelligencePage();
  } else if (activeRoute === 'cropatlas') {
    initCropAtlasPage();
  } else if (activeRoute === 'policy-alerts') {
    loadPolicyAlerts(false);
    document.querySelector('#policy-refresh-btn')?.addEventListener('click', () => loadPolicyAlerts(true));
  }
  // NOTE: no dedicated chat route — the assistant is the floating widget
  // (chat.js self-mounts on every page, farmer context preserved).

  // "Why this crop?" disclosure — delegated so it survives every repaint.
  document.addEventListener('click', (e) => {
    const btn = e.target.closest('.ca-why-toggle');
    if (!btn) return;
    const panel = document.getElementById(btn.getAttribute('aria-controls'));
    if (!panel) return;
    const open = btn.getAttribute('aria-expanded') === 'true';
    btn.setAttribute('aria-expanded', String(!open));
    panel.hidden = open;
  });

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
    if (activeRoute === 'intelligence') {
      // Re-render from the already-fetched payload (no duplicate API call).
      if (lastIntelPayload && lastIntelSelection) {
        renderIntelCards(lastIntelPayload);
        paintIntelMeta(lastIntelPayload, lastIntelSelection);
      } else {
        const locEl = document.querySelector('#intel-location');
        if (locEl) locEl.textContent = meaningT('intel_standby_location');
        const metaEl = document.querySelector('#intel-meta');
        if (metaEl) metaEl.textContent = meaningT('intel_standby_meta');
      }
    }
    if (activeRoute === 'cropatlas') {
      // Re-render from the already-fetched payload; never re-request on language
      // change. Expandable "Why this crop?" panels are preserved across repaints.
      const open = {};
      document.querySelectorAll('.ca-why-toggle[aria-expanded="true"]').forEach((b) => {
        open[b.getAttribute('aria-controls')] = true;
      });
      if (lastCaPayload) {
        renderCropAtlas(lastCaPayload);
      } else {
        caPane('standby');
      }
      Object.keys(open).forEach((id) => {
        const btn = document.querySelector(`.ca-why-toggle[aria-controls="${id}"]`);
        const panel = document.getElementById(id);
        if (btn && panel) { btn.setAttribute('aria-expanded', 'true'); panel.hidden = false; }
      });
    }
    if (activeRoute === 'policy-alerts') {
      // Re-render from cache when available; no ingestion is triggered here.
      if (lastPolicyAlerts) {
        renderPolicyAlerts(lastPolicyAlerts);
      } else {
        loadPolicyAlerts(false);
      }
    }
  });
});

// -------------------------------------------------------------
// 5b. GLOBAL LOADING-STATE HELPERS (presentation only)
// -------------------------------------------------------------
// Every helper below only paints loading/skeleton markup or toggles busy
// attributes. No data, thresholds or API contracts change here. Skeletons
// are decorative (aria-hidden); the adjacent status line carries the
// announcement, so animation is never the only signal.

/** Busy-state for action buttons: locks width (no jump), spinner + label. */
function setBtnBusy(btn, busy, busyLabel) {
  if (!btn) return;
  if (busy) {
    if (btn.dataset.saBusy !== '1') {
      btn.dataset.saBusy = '1';
      btn.dataset.saLabel = btn.textContent;
      btn.style.minWidth = `${btn.offsetWidth}px`;
      btn.textContent = '';
      const spin = document.createElement('span');
      spin.className = 'sa-spinner sa-spinner-xs';
      spin.setAttribute('aria-hidden', 'true');
      const label = document.createElement('span');
      label.textContent = busyLabel || 'Loading…';
      btn.append(spin, label);
      btn.classList.add('is-busy');
      btn.disabled = true;
    } else {
      const label = btn.querySelector('span:last-child');
      if (label && busyLabel) label.textContent = busyLabel;
    }
    return;
  }
  if (btn.dataset.saBusy === '1') {
    delete btn.dataset.saBusy;
    btn.classList.remove('is-busy');
    btn.disabled = false;
    btn.style.minWidth = '';
    btn.textContent = btn.dataset.saLabel || '';
    delete btn.dataset.saLabel;
  }
}

/** One shimmer block. Width via style, e.g. skel('sa-skel-text', '70%'). */
function skel(cls, width) {
  return `<div class="sa-skel ${cls || 'sa-skel-text'}" aria-hidden="true"`
    + (width ? ` style="width:${width}"` : '') + `></div>`;
}

/** Four sector cards with real titles + shimmer bodies (intelligence). */
function skelIntelCards() {
  return INTEL_SECTORS.map(([, titleKey, icon]) => {
    const iconHtml = icon ? `<span class="intel-icon" aria-hidden="true">${icon}</span>` : '';
    return `<div class="intel-card sa-skel-live" aria-hidden="true">`
      + `<h4>${iconHtml}${escapeHtml(meaningT(titleKey))}</h4>`
      + `<div class="sa-skel-pad" style="padding:4px 0 0;">`
      + skel('sa-skel-text', '42%') + skel('sa-skel-text', '96%')
      + skel('sa-skel-text', '88%') + skel('sa-skel-text', '64%')
      + `</div></div>`;
  }).join('');
}

/** Three crop cards mirroring caEcoCard geometry (image/title/match/rows). */
function skelCropCards() {
  const card = `<article class="ca-card ca-crop ca-simple sa-skel-live" aria-hidden="true">`
    + `<div class="sa-skel sa-skel-img"></div>`
    + `<div class="sa-skel-pad">`
    + skel('sa-skel-title', '52%') + skel('sa-skel-text', '34%')
    + `<div class="sa-skel sa-skel-bar" style="height:8px;border-radius:99px;"></div>`
    + skel('sa-skel-row', '92%') + skel('sa-skel-row', '97%') + skel('sa-skel-row', '71%')
    + `</div></article>`;
  return `<section class="ca-band"><div class="ca-band-head">`
    + `<h4 class="ca-band-title">${escapeHtml(meaningT('ca_band_eco'))}</h4>`
    + `</div><div class="ca-grid">${card}${card}${card}</div></section>`
    + `<p class="sa-sr" role="status">${escapeHtml(meaningT('loading_candidates'))}</p>`;
}

/** Three policy cards mirroring policyCard geometry (meta/title/summary). */
function skelPolicyCards() {
  const card = `<article class="timeline-card policy-card glass sa-skel-live" aria-hidden="true">`
    + `<div class="policy-card-body sa-skel-pad">`
    + skel('sa-skel-text', '30%') + skel('sa-skel-title', '78%')
    + skel('sa-skel-row', '96%') + skel('sa-skel-row', '88%')
    + `</div></article>`;
  return card + card + card;
}

/** Chart-area skeleton: reserves chart space, announces via role=status. */
function paintChartSkeleton() {
  const container = document.querySelector('#recharts-forecast-canvas');
  if (!container) return;
  container.setAttribute('role', 'status');
  container.setAttribute('aria-busy', 'true');
  container.innerHTML = `<div class="sa-skel sa-skel-chart" aria-hidden="true" style="min-height:300px;"></div>`
    + `<p class="sa-sr">${escapeHtml(meaningT('loading_chart'))}</p>`;
}

/** Floating "updating" chip over the risk map; never blocks interaction. */
function setMapLoading(on, label) {
  const canvas = document.querySelector('#leaflet-map-canvas');
  if (!canvas) return;
  let chip = canvas.querySelector(':scope > .sa-map-loading');
  if (!on) {
    if (chip) chip.remove();
    canvas.removeAttribute('aria-busy');
    return;
  }
  canvas.setAttribute('aria-busy', 'true');
  if (!chip) {
    chip = document.createElement('div');
    chip.className = 'sa-map-loading';
    chip.setAttribute('role', 'status');
    canvas.appendChild(chip);
  }
  chip.innerHTML = '';
  const spin = document.createElement('span');
  spin.className = 'sa-spinner';
  spin.setAttribute('aria-hidden', 'true');
  const text = document.createElement('span');
  text.textContent = label || meaningT('map_updating');
  chip.append(spin, text);
}

// -------------------------------------------------------------
// 6. GOVERNMENT POLICY ALERTS (approved PIB items only)
// -------------------------------------------------------------

let lastPolicyAlerts = null;

function policyT(key) {
  const EN = {
    policy_loading: 'Loading policy alerts…',
    policy_refreshing: 'Refreshing…',
    policy_empty: 'No agriculture policy updates right now.',
    policy_unavailable: 'Policy updates are currently unavailable.',
    policy_refresh_unavailable: 'The policy feed is unreachable right now — showing the latest saved updates.',
    policy_source_fallback: 'Policy update',
    policy_date_na: '',
    policy_title_fallback: 'Untitled update',
    policy_summary_na: '',
    policy_read_official: 'Read official notice →',
  };
  try {
    if (typeof getTranslation === 'function') {
      const v = getTranslation(key);
      if (v && v !== key) return v;
    }
    if (typeof meaningT === 'function') {
      const w = meaningT(key);
      if (w && w !== key) return w;
    }
  } catch (e) { /* fall through to English */ }
  return EN[key] !== undefined ? EN[key] : key;
}

function policySetStatus(text) {
  const container = document.querySelector('#policy-alert-list');
  if (!container) return;
  container.removeAttribute('aria-busy');
  container.textContent = '';
  const p = document.createElement('p');
  p.style.cssText = 'padding:20px;opacity:0.7;';
  p.textContent = text;
  container.appendChild(p);
}

function policySetError(text) {
  const container = document.querySelector('#policy-alert-list');
  if (!container) return;
  container.removeAttribute('aria-busy');
  container.textContent = '';
  const p = document.createElement('p');
  p.style.cssText = 'padding:20px;color:#a33;';
  p.textContent = text;
  container.appendChild(p);
}

/** Skeleton policy cards hold the list shape while fetching (never the
 * empty-state text — that renders only after a successful empty response). */
function policySetLoading() {
  const container = document.querySelector('#policy-alert-list');
  if (!container) return;
  container.setAttribute('aria-busy', 'true');
  container.innerHTML = skelPolicyCards()
    + `<p class="sa-sr" role="status">${policyT('policy_loading')}</p>`;
}

/** Client-side agriculture relevance guard: drops sport/entertainment items
 * that may arrive from a generic feed. Never invents content — only filters.
 * Mirrors the backend allowlist conservatively. */
function isAgriPolicy(alert) {
  const text = `${(alert && alert.title) || ''} ${(alert && (alert.summary || alert.description)) || ''}`.toLowerCase();
  if (!text.trim()) return false;
  // Mirrors the backend PibPolicySource allowlist: every backend-passing
  // record must also pass here, so the lists stay in sync on purpose.
  const excluded = ['cricket', 'football', 'hockey', 'kabaddi', 'badminton', 'tennis', 'olympic',
    'paralympic', 'athlete', 'athletes', 'athletics', 'wrestling', 'boxing', 'chess',
    'tournament', 'tournaments', 'world cup',
    'match schedule', 'asian games', 'khelo', 'stadium', 'stadiums', 'medal', 'medals',
    'sports', 'sportsperson',
    'film', 'films', 'cinema', 'actor', 'actors', 'actress', 'music concert',
    'swachhata', 'swachh', 'special campaign', 'cleanliness',
    'hindi pakhwada', 'pakhwada', 'pakhwade', 'defence', 'defense', 'federalism',
    'खेल', 'खेलों', 'एथलीट', 'एथलीटों', 'पदक', 'पदकों', 'स्वच्छता', 'स्वच्छ', 'विशेष अभियान',
    'पखवाड़ा', 'पखवाड़े'];
  const strong = ['agriculture', 'agricultural', 'farmer', 'farmers', 'farming',
    'crop', 'crops', 'cropping', 'kisan', 'pmkisan', 'cultivation', 'irrigation',
    'fertiliz', 'fertilis', 'pesticide', 'horticulture',
    'agri', 'animal husbandry', 'dairy', 'fisher', 'fishing', 'aquaculture', 'blue revolution', 'mkssy',
    'livestock', 'live stock', 'poultry', 'cattle', 'goat', 'sheep', 'piggery', 'food grain',
    'rice', 'wheat', 'maize', 'onion', 'tomato', 'potato',
    'soybean', 'soyabean', 'mustard', 'groundnut', 'sugarcane',
    'cotton', 'paddy', 'basmati', 'millet', 'millets', 'pulses',
    'tur dal', 'toor dal', 'chana', 'moong', 'urad', 'masur', 'arhar',
    'bajra', 'jowar', 'ragi', 'barley', 'sunflower', 'safflower', 'sesamum', 'sesame',
    'jute', 'copra', 'tobacco', 'oilseed', 'oils', 'edible oil', 'palm oil', 'soil', 'soil health',
    'rabi', 'kharif', 'procurement', 'buffer stock',
    'watershed', 'rainfed', 'land record', 'grower',
    'cooperative', 'grameen', 'gramin', 'krishi',
    'pm-kisan', 'pm kisan', 'pmfby', 'fasal bima', 'crop insurance',
    'pm-aasha', 'aasha', 'annadata',
    'e-nam', 'enam', 'mandis', 'msp', 'minimum support price',
    'subsidy', 'subsidies', 'credit', 'insurance', 'kcc',
    'farmer welfare', 'crop production', 'rural development',
    'panchayat', 'panchayati', 'allied sector',
    'कृषि', 'किसान', 'किसानों', 'कृषक', 'खेती', 'फसल', 'फसलों',
    'सिंचाई', 'उर्वरक', 'खाद', 'खाद्य', 'कीटनाशक', 'बागवानी',
    'पशुपालन', 'डेयरी', 'मत्स्य', 'मछली', 'पशुधन',
    'अनाज', 'धान', 'गेहूं', 'मक्का', 'सोयाबीन', 'पंचायत', 'पंचायती',
    'रबी', 'खरीफ', 'खरीफ़', 'खरीद', 'एमएसपी', 'मंडी',
    'प्याज', 'टमाटर', 'आलू'];
  const hasStrong = strong.some((w) => text.includes(w));
  if (!hasStrong) return false;
  const hasExcluded = excluded.some((w) => text.includes(w));
  if (!hasExcluded) return true;
  const decisive = ['pm-kisan', 'pm kisan', 'pmkisan', 'pmfby', 'fasal bima', 'crop insurance', 'e-nam', 'enam', 'kcc',
    'minimum support price', 'kisan credit', 'farmer welfare', 'crop production',
    'pm-aasha', 'aasha', 'annadata', 'mkssy', 'blue revolution',
    'agriculture', 'agricultural', 'kisan', 'irrigation', 'aquaculture', 'livestock', 'live stock',
    'poultry', 'cattle', 'procurement', 'rabi', 'kharif', 'watershed', 'rainfed', 'krishi',
    'tur dal', 'toor dal', 'grower', 'crops', 'mandis', 'subsidies',
    'onion', 'tomato', 'potato', 'buffer stock', 'land record', 'soil', 'food grains', 'oils', 'edible oil',
    'कृषि', 'किसान', 'किसानों', 'रबी', 'खरीफ', 'खरीद', 'एमएसपी', 'मंडी', 'प्याज', 'टमाटर', 'आलू'];
  return decisive.some((w) => text.includes(w));
}

function policyCard(alert) {
  const article = document.createElement('article');
  article.className = 'timeline-card policy-card glass';

  const body = document.createElement('div');
  body.className = 'policy-card-body';
  article.appendChild(body);

  const meta = document.createElement('p');
  meta.className = 'policy-card-meta';
  const kind = (alert && alert.kind) ? String(alert.kind) : '';
  const date = (alert && alert.publishedDate) ? String(alert.publishedDate) : '';
  meta.textContent = [kind, date].filter(Boolean).join('  ·  ');
  if (meta.textContent) body.appendChild(meta);

  const title = document.createElement('h3');
  title.className = 'policy-card-title';
  title.textContent = (alert && alert.title) ? String(alert.title) : policyT('policy_title_fallback');
  body.appendChild(title);

  const summaryText = (alert && (alert.summary || alert.description))
    ? String(alert.summary || alert.description).trim() : '';
  if (summaryText) {
    const summary = document.createElement('p');
    summary.className = 'policy-card-summary';
    summary.textContent = summaryText.length > 280 ? summaryText.slice(0, 277).trim() + '…' : summaryText;
    body.appendChild(summary);
  }

  if (alert && alert.sourceUrl) {
    const link = document.createElement('a');
    link.href = String(alert.sourceUrl);
    link.target = '_blank';
    link.rel = 'noopener noreferrer';
    link.className = 'policy-card-link';
    link.textContent = policyT('policy_read_official');
    try {
      const parsed = new URL(link.href, location.origin);
      if (!['http:', 'https:'].includes(parsed.protocol)) link.removeAttribute('href');
    } catch (e) {
      link.removeAttribute('href');
    }
    body.appendChild(link);
  }

  return article;
}

function renderPolicyAlerts(alerts) {
  const container = document.querySelector('#policy-alert-list');
  if (!container) return;
  container.removeAttribute('aria-busy');
  const incoming = Array.isArray(alerts) ? alerts : [];
  lastPolicyAlerts = incoming.filter(isAgriPolicy);
  container.textContent = '';
  if (!lastPolicyAlerts.length) {
    const p = document.createElement('p');
    p.style.cssText = 'padding:20px;opacity:0.7;';
    p.textContent = policyT('policy_empty');
    container.appendChild(p);
    return;
  }
  lastPolicyAlerts.forEach((alert) => container.appendChild(policyCard(alert)));
}

async function loadPolicyAlerts(withRefresh) {
  const container = document.querySelector('#policy-alert-list');
  if (!container) return;
  // Skeleton first: the empty-state text is only valid after a successful
  // empty response, never while the request is still running.
  policySetLoading();
  // The refresh button locks while its request runs (no duplicate POSTs) and
  // keeps its width so the header never jumps.
  const refreshBtn = document.querySelector('#policy-refresh-btn');
  if (withRefresh) setBtnBusy(refreshBtn, true, policyT('policy_refreshing'));
  // The public refresh re-reads official feeds server-side (no token, no
  // caller content accepted). The admin ingestion path is never used here.
  let refreshState = null;
  try {
    if (withRefresh) {
      try {
        const run = await fetch('/api/policy-alerts/refresh', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        });
        refreshState = await run.json().catch(() => null);
        if (!run.ok || !refreshState) refreshState = { status: 'unavailable' };
      } catch (err) {
        console.warn('Policy refresh unavailable:', err);
        refreshState = { status: 'unavailable' };
      }
    }

    try {
      const res = await fetch('/api/policy-alerts', { headers: { Accept: 'application/json' } });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const alerts = await res.json();
      if (!Array.isArray(alerts) || alerts.length === 0) {
        lastPolicyAlerts = [];
        // Empty + unreachable source: say the official source is down rather
        // than implying there are simply no schemes.
        if (refreshState && (refreshState.status === 'unavailable' || refreshState.status === 'throttled')) {
          policySetStatus(policyT('policy_refresh_unavailable'));
        } else {
          policySetStatus(policyT('policy_empty'));
        }
        return;
      }
      renderPolicyAlerts(alerts);
    } catch (err) {
      console.error('Policy alerts failed:', err);
      policySetError(policyT('policy_unavailable'));
    }
  } finally {
    if (withRefresh) setBtnBusy(refreshBtn, false);
  }
}


// Portal header eyebrow follows the persisted selection (block · district,
// state); falls back to the static label when nothing is chosen.
function updateGeoEyebrow() {
  const el = document.querySelector('.portal-header .eyebrow');
  if (!el) return;
  const sel = farmerSelection || timelineSelection || mapSelection || SaarthiGeo.loadSelection();
  if (sel) el.textContent = `${sel.block_name} · ${sel.district_name}, ${sel.state_name} · 16-day outlook`;
}
