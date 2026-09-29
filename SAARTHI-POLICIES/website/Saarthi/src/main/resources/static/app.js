const blockSelect = document.querySelector('#block-select');
const ids = (name) => document.querySelector(name);
let latestCache = null;
let blocksCache = [];
let latestOutlookData = null;

// Six validated legacy Bhuvan blocks (select fallback only; names come from API).
const FALLBACK_BLOCKS = ['Dhuri', 'Lehra', 'Malerkotla', 'Moonak', 'Sangrur', 'Sunam'];

function mapColor(category) {
  if (category === 'HIGH') return '#f27256';
  if (category === 'LOW') return '#e2be62';
  return '#9ed683';
}

function toneFor(category) {
  if (category === 'LOW') return 'review';
  return 'sow';
}

async function populateBlockSelect() {
  if (!blockSelect) return;
  try {
    const res = await fetch('/api/forecast/latest');
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    latestCache = await res.json();
    blocksCache = latestCache.forecast.blocks;
    const names = blocksCache.map((b) => b.block_name);
    const current = blockSelect.value;
    blockSelect.innerHTML = names.map((n) => `<option value="${n}">${n}</option>`).join('');
    if (names.includes(current)) blockSelect.value = current;
  } catch (err) {
    console.error('Block list failed:', err);
    blockSelect.innerHTML = FALLBACK_BLOCKS.map((n) => `<option value="${n}">${n}</option>`).join('');
  }
}

function renderBlockChips() {
  let panel = document.querySelector('#rainfall-map');
  if (!panel) {
    panel = document.createElement('section');
    panel.id = 'rainfall-map';
    panel.className = 'rainfall-map';
    document.querySelector('.analysis-panel')?.append(panel);
  }
  if (!blocksCache.length) {
    panel.innerHTML = `<div class="map-heading"><div><p class="card-kicker">Validated 7-day block outlook</p><h4>Six Sangrur blocks</h4></div></div><p>Live forecast data is currently unavailable. Please try again.</p>`;
    return;
  }
  const chips = blocksCache.map((b) => `
    <li data-block="${b.block_name}" style="cursor:pointer;">
      <span><b>${b.block_name}</b><small>${b.category} · ${b.forecast_7d_total_rainfall_mm} mm / 7d</small></span>
      <strong style="color:${mapColor(b.category)}">●</strong>
    </li>`).join('');
  panel.innerHTML = `<div class="map-heading"><div><p class="card-kicker">Validated 7-day block outlook</p><h4>Six Sangrur blocks (Bhuvan boundaries)</h4></div><span>Next 7 days</span></div><ul class="village-list">${chips}</ul><div class="map-legend"><span><i class="dry"></i>LOW (&lt;10.94 mm)</span><span><i class="moderate"></i>NORMAL</span><span><i class="wet"></i>HIGH (&gt;34.66 mm)</span></div>`;
  panel.querySelectorAll('li[data-block]').forEach((li) => {
    li.addEventListener('click', () => {
      if (blockSelect) { blockSelect.value = li.dataset.block; loadOutlook(); }
    });
  });
}

function renderLanguageAdvisory(decision) {
  if (!decision) return;
  let panel = document.querySelector('#language-advisory');
  if (!panel) {
    panel = document.createElement('div');
    panel.id = 'language-advisory';
    panel.className = 'language-advisory';
    document.querySelector('#sowing-card')?.append(panel);
  }
  const currentLang = localStorage.getItem('saarthi_lang') || 'en';
  const messages = { en: decision.message || '', pa: decision.punjabi || decision.message || '', hi: decision.hindi || decision.message || '' };
  panel.innerHTML = `<div class="language-tabs"><span>Language</span><button class="${currentLang === 'en' ? 'active' : ''}" data-lang="en">EN</button><button class="${currentLang === 'pa' ? 'active' : ''}" data-lang="pa">ਪੰਜਾਬੀ</button><button class="${currentLang === 'hi' ? 'active' : ''}" data-lang="hi">हिंदी</button></div><p id="local-advice">${messages[currentLang] || messages.en}</p>`;
  panel.querySelectorAll('button').forEach((button) => button.addEventListener('click', () => {
    panel.querySelectorAll('button').forEach((item) => item.classList.toggle('active', item === button));
    const lang = button.dataset.lang;
    panel.querySelector('#local-advice').textContent = messages[lang] || messages.en;
    if (typeof setLanguage === 'function') setLanguage(lang);
  }));
}

function renderTimeline(dailyForecast) {
  if (!dailyForecast) return;
  let panel = document.querySelector('#rainfall-timeline');
  if (!panel) {
    panel = document.createElement('section');
    panel.id = 'rainfall-timeline';
    panel.className = 'rainfall-timeline';
    document.querySelector('.outlook-heading')?.append(panel);
  }
  const rains = dailyForecast.map((d) => d.rainfall_mm);
  const max = Math.max(...rains, 1);
  const peak = Math.max(...rains);
  const bars = dailyForecast.map((d) => `<div class="rain-day ${d.rainfall_mm === peak ? 'peak' : ''}"><span>${d.rainfall_mm} mm</span><i style="height:${Math.max(12, Math.round(d.rainfall_mm / max * 72))}px"></i><small>D${d.day}<br>${String(d.date).slice(5)}</small></div>`).join('');
  panel.innerHTML = `<div class="timeline-heading"><p class="card-kicker">Rainfall outlook</p><span>7 days</span></div><div class="rain-bars horizon-7">${bars}</div><p class="timeline-note">Validated 7-day outlook · rainfall in millimetres</p>`;
}

function renderValidityMeta(forecast) {
  let panel = document.querySelector('#seasonal-signals');
  if (!panel) {
    panel = document.createElement('section');
    panel.id = 'seasonal-signals';
    panel.className = 'seasonal-signals';
    document.querySelector('.analysis-grid')?.after(panel);
  }
  panel.innerHTML = `<p class="card-kicker">Forecast validity</p><div><article><span>Issued</span><strong>${forecast.issue_date}</strong><small>forecast issue date</small></article><article><span>Valid period</span><strong>${forecast.valid_from} – ${forecast.valid_to}</strong><small>7-day horizon</small></article></div>`;
}

function renderCropAdvice(category) {
  const plans = {
    LOW: 'Below-normal 7-day rainfall expected. Moisture conservation and irrigation planning may be relevant.',
    NORMAL: 'Near-normal 7-day rainfall expected. Conditions generally favorable; continue routine monitoring.',
    HIGH: 'Above-normal 7-day rainfall expected. Monitor waterlogging/runoff risk and drainage.',
  };
  let panel = document.querySelector('#crop-advice');
  if (!panel) {
    panel = document.createElement('section');
    panel.id = 'crop-advice';
    panel.className = 'crop-advice';
    document.querySelector('#sowing-card')?.append(panel);
  }
  panel.innerHTML = `<div class="crop-heading"><span>Block advisory (prototype)</span></div><p>${plans[category] || plans.NORMAL}</p><div class="crop-options"><span><small>Method</small><b>Raw CHIRPS-GEFS</b></span><span><small>Guidance</small><b>Prototype decision-support</b></span></div>`;
}

function renderOutlookView(outlook) {
  if (!outlook) return;
  latestOutlookData = outlook;
  const probs = `LOW ${(outlook.prob_low * 100).toFixed(0)}% · NORMAL ${(outlook.prob_normal * 100).toFixed(0)}% · HIGH ${(outlook.prob_high * 100).toFixed(0)}%`;
  const ind = outlook.indicators || {};
  if (ids('#rain-7')) ids('#rain-7').textContent = `${outlook.forecast_7d_total_rainfall_mm} mm`;
  if (ids('#dry-14')) ids('#dry-14').textContent = `${ind.dry_days ?? '—'} / ${ind.wet_days ?? '—'}`;
  if (ids('#expected-rain')) ids('#expected-rain').textContent = `${ind.max_daily_rainfall_mm ?? '—'} mm (${ind.max_daily_rainfall_date ?? '—'})`;
  if (ids('#rain-trend')) ids('#rain-trend').textContent = outlook.category;
  if (ids('#risk-label')) ids('#risk-label').textContent = `${outlook.category} rainfall outlook`;
  if (ids('#risk-probability')) ids('#risk-probability').textContent = probs;
  if (ids('#risk-meter')) ids('#risk-meter').style.width = `${Math.round(outlook.prob_low * 100)}%`;
  if (ids('#model-note')) ids('#model-note').textContent = `Analysis source: ${outlook.model_source || 'Raw CHIRPS-GEFS'} · thresholds LOW<10.94 / HIGH>34.66 mm (training)`;
  if (outlook.daily_forecast) renderTimeline(outlook.daily_forecast);
  renderCropAdvice(outlook.category);
  renderValidityMeta(outlook);
  const card = ids('#sowing-card');
  if (card) {
    const tone = toneFor(outlook.category);
    card.dataset.tone = tone;
    const msgs = {
      LOW: { status: 'Low rainfall expected', title: 'Plan irrigation.', message: `Below-normal 7-day rainfall (${outlook.forecast_7d_total_rainfall_mm} mm) in ${outlook.block_name}. Consider moisture conservation.`, badge: `P(LOW) <strong>${Math.round(outlook.prob_low * 100)}%</strong>` },
      NORMAL: { status: 'Favourable outlook', title: 'Routine monitoring.', message: `Near-normal 7-day rainfall (${outlook.forecast_7d_total_rainfall_mm} mm) in ${outlook.block_name}.`, badge: '<strong>Normal conditions</strong>' },
      HIGH: { status: 'Heavy rainfall expected', title: 'Watch drainage.', message: `Above-normal 7-day rainfall (${outlook.forecast_7d_total_rainfall_mm} mm) in ${outlook.block_name}. Monitor waterlogging.`, badge: `P(HIGH) <strong>${Math.round(outlook.prob_high * 100)}%</strong>` },
    };
    const m = msgs[outlook.category] || msgs.NORMAL;
    if (ids('#decision-icon')) ids('#decision-icon').textContent = tone === 'sow' ? '✓' : '↗';
    if (ids('#decision-status')) ids('#decision-status').textContent = m.status;
    if (ids('#decision-title')) ids('#decision-title').textContent = m.title;
    if (ids('#decision-message')) ids('#decision-message').textContent = m.message;
    if (ids('#wait-badge')) ids('#wait-badge').innerHTML = m.badge;
    renderLanguageAdvisory({ message: m.message, punjabi: m.message, hindi: m.message });
  }
  const upd = document.querySelector('.updated');
  if (upd) upd.textContent = `Issued ${outlook.issue_date} · Valid ${outlook.valid_from} – ${outlook.valid_to}`;
}

async function loadOutlook() {
  if (!blockSelect) return;
  const block = blockSelect.value;
  if (ids('#analysis-block')) ids('#analysis-block').textContent = block;
  renderBlockChips();

  try {
    const response = await fetch(`/api/forecast/${encodeURIComponent(block)}`);
    if (!response.ok) {
      const err = await response.json().catch(() => ({}));
      throw new Error(err.message || `HTTP ${response.status}`);
    }
    const outlook = await response.json();
    renderOutlookView(outlook);
  } catch (err) {
    console.error('Outlook load failed:', err);
    const panel = document.querySelector('.analysis-panel');
    if (panel && !panel.querySelector('.api-error-note')) {
      const note = document.createElement('p');
      note.className = 'api-error-note';
      note.style.color = '#a33';
      note.textContent = 'Live forecast data is currently unavailable. Please try again.';
      panel.prepend(note);
    }
  }
}

if (blockSelect) {
  populateBlockSelect().then(() => loadOutlook());
  blockSelect.addEventListener('change', loadOutlook);
}

window.addEventListener('languageChanged', () => {
  if (latestOutlookData) renderOutlookView(latestOutlookData);
});
