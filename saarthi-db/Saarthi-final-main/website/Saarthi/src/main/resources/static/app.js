const ids = (name) => document.querySelector(name);
let modelNoteBase = '';
let geoApi = null;

// Display-only numeric formatting (API values are never rounded or modified).
// Used as fallback when SaarthiGeo.fmt is unavailable.
function fmtRain1(x) {
  if (x === null || x === undefined || !Number.isFinite(Number(x))) return 'No data';
  return `${Number(Number(x).toFixed(1))} mm`;
}
function fmtSoil2(x) {
  if (x === null || x === undefined || !Number.isFinite(Number(x))) return 'No data';
  return `${Number(x).toFixed(2)} m³/m³`;
}
function fmtPct0(x) {
  const n = Number(x);
  return (x === null || x === undefined || !Number.isFinite(n)) ? '—' : `${Math.round(n)}%`;
}
function fmtNum1(x) {
  if (x === null || x === undefined || !Number.isFinite(Number(x))) return '—';
  return String(Number(Number(x).toFixed(1)));
}

function soilText(envelope, block) {
  const direct = envelope && envelope.soil;
  if (direct && direct.available && direct.line) return direct.line;
  const sm = block && block.soil_moisture_0_to_7cm_pct;
  if (sm && sm.available && sm.value != null) {
    const f = (typeof SaarthiGeo !== 'undefined' && SaarthiGeo.fmt) ? SaarthiGeo.fmt.soil(sm.value) : fmtSoil2(sm.value);
    return `Forecast surface soil moisture ${f} (ECMWF IFS, 0–7 cm — not a measurement)`;
  }
  return 'Soil data unavailable for this block';
}

function methodText(block) {
  const m = (block && block.spatial_method) || '';
  if (m.includes('single_point_centroid')) {
    return 'Spatial method: single-point centroid (registry coordinates — not polygon-averaged)';
  }
  if (m.includes('multipoint')) {
    return `Spatial method: polygon multipoint mean (${m.split(' ')[0]})`;
  }
  return m ? `Spatial method: ${m}` : '';
}

function renderBlockChips(blocks) {
  let panel = document.querySelector('#rainfall-map');
  if (!panel) {
    panel = document.createElement('section');
    panel.id = 'rainfall-map';
    panel.className = 'rainfall-map';
    document.querySelector('.analysis-panel')?.append(panel);
  }
  if (!blocks || !blocks.length) {
    panel.innerHTML = `<div class="map-heading"><div><p class="card-kicker">Live ECMWF IFS outlook</p><h4>Sangrur polygon blocks</h4></div></div><p>Live forecast data is currently unavailable. Please try again.</p>`;
    return;
  }
  const F = (typeof SaarthiGeo !== 'undefined' && SaarthiGeo.fmt) || null;
  const chips = blocks.map((b) => {
    const raw = b.cum_7d_mm && b.cum_7d_mm.available ? b.cum_7d_mm.rainfall_mm : null;
    const cum = raw != null ? `${F ? F.rain(raw) : fmtRain1(raw)} / 7d` : '7d total unavailable';
    return `<li data-block="${b.block_name}" style="cursor:pointer;"><span><b>${b.block_name}</b><small>${cum}</small></span><strong>●</strong></li>`;
  }).join('');
  panel.innerHTML = `<div class="map-heading"><div><p class="card-kicker">Live ECMWF IFS outlook</p><h4>Sangrur polygon blocks (Bhuvan boundaries)</h4></div><span>Next 16 days</span></div><ul class="village-list">${chips}</ul><div class="map-legend"><span>Open-Meteo · ECMWF IFS (ecmwf_ifs)</span></div>`;
  panel.querySelectorAll('li[data-block]').forEach((li) => {
    li.addEventListener('click', async () => {
      // Resolve the chip name through the registry so the cascade,
      // location line and forecast all follow the click.
      try {
        const found = await SaarthiGeo.searchBlocks(li.dataset.block);
        if (found.length && geoApi) {
          const hit = found[0];
          await geoApi.selectByCodes(hit.state_code, hit.district_code, hit.block_code);
          return;
        }
      } catch (e) {
        console.warn('Chip registry resolve failed, loading legacy directly:', e);
      }
      loadLegacyDirect(li.dataset.block);
    });
  });
}

async function loadLegacyDirect(block) {
  if (ids('#analysis-block')) ids('#analysis-block').textContent = block;
  try {
    const response = await fetch(`/api/weather/forecast/${encodeURIComponent(block)}`);
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const fc = await response.json();
    renderOutlookView(fc, fc.block, null);
    loadLongRange(block, true);
    loadRisk(null, true, block);
  } catch (err) {
    console.error('Outlook load failed:', err);
  }
}

function renderTimeline(block) {
  const days = (block && block.days) || [];
  if (!days.length) return;
  let panel = document.querySelector('#rainfall-timeline');
  if (!panel) {
    panel = document.createElement('section');
    panel.id = 'rainfall-timeline';
    panel.className = 'rainfall-timeline';
    document.querySelector('.outlook-heading')?.append(panel);
  }
  const rains = days.map((d) => d.rainfall_mm).filter((v) => v !== null && v !== undefined);
  const F = (typeof SaarthiGeo !== 'undefined' && SaarthiGeo.fmt) || null;
  const max = Math.max(...rains, 1);
  const peak = Math.max(...rains, 0);
  const bars = days.map((d) => {
    const v = d.rainfall_mm;
    const h = (v === null || v === undefined) ? 12 : Math.max(12, Math.round(v / max * 72));
    const lbl = (v === null || v === undefined) ? 'no data' : (F ? F.rain(v) : fmtRain1(v));
    const prob = (d.rain_probability_pct === null || d.rain_probability_pct === undefined) ? '' : `<br>${fmtPct0(d.rain_probability_pct)}`;
    const isPeak = v !== null && v !== undefined && v === peak;
    return `<div class="rain-day ${isPeak ? 'peak' : ''}"><span>${lbl}</span><i style="height:${h}px"></i><small>D${d.horizon_day}<br>${String(d.date).slice(5)}${prob}</small></div>`;
  }).join('');
  panel.innerHTML = `<div class="timeline-heading"><p class="card-kicker">Live ECMWF IFS rainfall</p><span>16 days</span></div><div class="rain-bars horizon-7">${bars}</div><p class="timeline-note">Live ECMWF IFS outlook · rainfall in millimetres · probability where available</p>`;
}

function renderValidityMeta(fc, block) {
  let panel = document.querySelector('#seasonal-signals');
  if (!panel) {
    panel = document.createElement('section');
    panel.id = 'seasonal-signals';
    panel.className = 'seasonal-signals';
    document.querySelector('.analysis-grid')?.after(panel);
  }
  const days = (block && block.days) || [];
  const validTo = days.length ? days[days.length - 1].date : fc.issue_date;
  const stale = !!fc.stale;
  panel.innerHTML = `<p class="card-kicker">Forecast validity</p><div><article><span>Issued</span><strong>${fc.issue_date}</strong><small>live forecast issue date (from API)</small></article><article><span>Valid period</span><strong>${days.length ? days[0].date : ''} – ${validTo}</strong><small>IFS horizon as served</small></article><article><span>Provider / model</span><strong>${fc.provider} · ${fc.model}</strong><small>Open-Meteo delivery · ECMWF IFS NWP</small></article></div><p class="validity-freshness" style="margin:8px 0 0;font-size:12px;font-weight:700;color:${stale ? '#a33' : '#3c6e47'}">${stale ? `STALE — ${fc.stale_warning || 'cached forecast may be outdated'}` : 'fresh — live forecast current'}</p>`;
}

async function loadLongRange(blockName, isLegacy) {
  let panel = document.querySelector('#crop-advice');
  if (!panel) {
    panel = document.createElement('section');
    panel.id = 'crop-advice';
    panel.className = 'crop-advice';
    document.querySelector('#sowing-card')?.append(panel);
  }
  if (!isLegacy) {
    panel.innerHTML = `<div class="crop-heading"><span>17–30 Day Climatological Outlook</span></div><p>Extended climatological outlook is currently served for supported blocks only. The live 16-day IFS forecast above is unaffected.</p>`;
    return;
  }
  try {
    const res = await fetch(`/api/outlook/17-30/${encodeURIComponent(blockName)}`);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const o = await res.json();
    const pct = (v) => (v === null || v === undefined) ? '—' : `${Math.round(v * 100)}%`;
    const w = (n) => `<p style="margin:0 0 4px;font-size:12.5px;"><b>${n.horizon_label}</b> · ${n.period_start} → ${n.period_end} · climatological normal ${fmtNum1(n.climatological_normal_mm)} mm · below/near/above ${pct(n.below_probability)}/${pct(n.near_probability)}/${pct(n.above_probability)}</p>`;
    panel.innerHTML = `<div class="crop-heading"><span>17–30 Day Climatological Outlook (not an IFS forecast)</span></div>${w(o.w3)}${w(o.w4)}<p style="margin:0;font-size:12px;opacity:.8;">Confidence ${o.confidence}${o.confidence_reason ? ' — ' + o.confidence_reason : ''}</p><div class="crop-options"><span><small>Method</small><b>Climatology</b></span><span><small>Probabilities</small><b>tercile prior (1/3 each, not calibrated)</b></span></div>`;
  } catch (err) {
    panel.innerHTML = `<div class="crop-heading"><span>17–30 Day Climatological Outlook (not an IFS forecast)</span></div><p>Extended outlook currently unavailable. Live 16-day IFS forecast above is unaffected.</p>`;
  }
}

function renderOutlookView(fc, block, selection) {
  const days = block.days || [];
  const total = block.cum_7d_mm && block.cum_7d_mm.available ? block.cum_7d_mm.rainfall_mm : null;
  const wet = days.filter((d) => (d.rainfall_mm || 0) >= 1).length;
  const dry = days.filter((d) => (d.rainfall_mm || 0) < 1 && d.rainfall_mm !== null).length;
  const heaviest = days.reduce((a, b) => ((b.rainfall_mm || 0) > (a.rainfall_mm || 0) ? b : a), days[0] || {});
  const label = selection ? SaarthiGeo.locationLabel(selection) : block.block_name;
  if (ids('#analysis-block')) ids('#analysis-block').textContent = label;
  const locLine = ids('#geo-location-line');
  if (locLine) locLine.textContent = label;
  const methodLine = ids('#geo-method-line');
  if (methodLine) methodLine.textContent = methodText(block);
  const RF = (typeof SaarthiGeo !== 'undefined' && SaarthiGeo.fmt) || null;
  const rainLbl = (x) => (RF ? RF.rain(x) : (x !== null ? fmtRain1(x) : 'unavailable'));
  if (ids('#rain-7')) ids('#rain-7').textContent = total !== null ? rainLbl(total) : 'unavailable';
  if (ids('#dry-14')) ids('#dry-14').textContent = `${dry} / ${wet}`;
  if (ids('#expected-rain')) ids('#expected-rain').textContent = heaviest && heaviest.date ? `${rainLbl(heaviest.rainfall_mm)} (${heaviest.date})` : '—';
  if (ids('#rain-trend')) ids('#rain-trend').textContent = block.cum_3d_mm && block.cum_3d_mm.available ? `${rainLbl(block.cum_3d_mm.rainfall_mm)} / 3d` : 'unavailable';
  if (ids('#risk-label')) ids('#risk-label').textContent = 'Live ECMWF IFS outlook';
  if (ids('#risk-probability')) ids('#risk-probability').textContent = `${fc.provider} · ${fc.model}`;
  if (ids('#risk-meter')) ids('#risk-meter').style.width = '20%';
  if (ids('#model-note')) {
    modelNoteBase = `Live source: ${fc.provider} · ${fc.model} · issued ${fc.issue_date} · ${methodText(block)} · ${soilText(fc, block)}`;
    ids('#model-note').textContent = modelNoteBase;
  }
  renderTimeline(block);
  renderValidityMeta(fc, block);
  const upd = document.querySelector('.updated');
  if (upd) {
    upd.textContent = fc.stale ? `Live ECMWF IFS Outlook · Issued ${fc.issue_date} · STALE` : `Live ECMWF IFS Outlook · Issued ${fc.issue_date} · fresh`;
    upd.style.color = fc.stale ? '#a33' : '#3c6e47';
    upd.style.fontWeight = '700';
  }
}

async function loadRisk(selection, isLegacy, legacyName) {
  const name = selection ? selection.block_name : legacyName;
  try {
    if (!isLegacy) {
      if (ids('#decision-status')) ids('#decision-status').textContent = 'Block-level outlook';
      if (ids('#decision-title')) ids('#decision-title').textContent = `Live IFS outlook for ${name}.`;
      if (ids('#decision-message')) ids('#decision-message').textContent = `Live ECMWF IFS weather is available for ${name}. Advanced field-risk scoring is currently available for supported blocks only — the live 16-day rainfall outlook above covers this block.`;
      if (ids('#wait-badge')) ids('#wait-badge').innerHTML = '<strong>—</strong>';
      return;
    }
    const rr = await fetch(`/api/risks/${encodeURIComponent(name)}?window=3d`);
    if (rr.ok) {
      const risk = await rr.json();
      if (ids('#decision-status')) ids('#decision-status').textContent = `${risk.overall_risk} — ${risk.primary_concern}`;
      if (ids('#decision-title')) ids('#decision-title').textContent = (risk.advisories && risk.advisories[0]) || `Field outlook for ${name}.`;
      if (ids('#decision-message')) ids('#decision-message').textContent = `Live IFS-based risk for ${name}: ${risk.overall_risk} (${risk.primary_concern}), confidence ${risk.confidence}.`;
      if (ids('#wait-badge')) ids('#wait-badge').innerHTML = `<strong>${risk.overall_risk}</strong>`;
      if (ids('#model-note')) ids('#model-note').textContent = `${modelNoteBase} · risk ${risk.overall_risk}/${risk.primary_concern} (${risk.confidence})`;
    }
  } catch (e) { console.error('Risk load failed:', e); }
}

async function loadOutlook(selection) {
  const panel = document.querySelector('.analysis-panel');
  const showLoading = (text) => {
    if (!panel) return null;
    let note = panel.querySelector('.api-loading-note');
    if (!note) {
      note = document.createElement('p');
      note.className = 'api-loading-note';
      panel.prepend(note);
    }
    note.textContent = text;
    return note;
  };
  document.querySelectorAll('.api-error-note').forEach((n) => n.remove());
  if (!selection) {
    if (ids('#analysis-block')) ids('#analysis-block').textContent = 'Select a block';
    const note = showLoading('Select State → District → Block to load the live forecast.');
    return;
  }
  if (ids('#analysis-block')) ids('#analysis-block').textContent = SaarthiGeo.locationLabel(selection);
  showLoading(`Loading live ECMWF IFS forecast for ${selection.block_name}…`);
  try {
    const { envelope, block, isLegacy } = await SaarthiGeo.fetchBlockForecast(selection);
    const loading = document.querySelector('.api-loading-note');
    if (loading) loading.remove();
    renderOutlookView(envelope, block, selection);
    try {
      const all = await fetch('/api/weather/forecast');
      if (all.ok) renderBlockChips((await all.json()).blocks || []);
    } catch (e) { console.error('Block chips failed:', e); }
    loadLongRange(selection.block_name, isLegacy);
    loadRisk(selection, isLegacy);
  } catch (err) {
    console.error('Outlook load failed:', err);
    const loading = document.querySelector('.api-loading-note');
    if (loading) loading.textContent = 'Live forecast unavailable.';
    if (panel && !panel.querySelector('.api-error-note')) {
      const note = document.createElement('p');
      note.className = 'api-error-note';
      note.style.color = '#a33';
      note.textContent = `Live forecast unavailable for ${selection.block_name} (${err.message}). No fallback data is shown.`;
      panel.prepend(note);
    }
  }
}

(function initHomepage() {
  const stateSel = document.querySelector('#state-select');
  const districtSel = document.querySelector('#district-select');
  const blockSel = document.querySelector('#block-select');
  if (!stateSel || !districtSel || !blockSel || typeof SaarthiGeo === 'undefined') return;
  geoApi = SaarthiGeo.wireCascade(stateSel, districtSel, blockSel, loadOutlook);
})();

window.addEventListener('languageChanged', () => {
  if (geoApi && typeof geoApi.getSelection === 'function') loadOutlook(geoApi.getSelection());
});
