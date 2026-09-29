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
    return `Surface soil moisture outlook ${f} (modelled — not a field reading)`;
  }
  return 'Soil data unavailable for this block';
}

function methodText(block) {
  // Internal spatial detail is kept in the payload; nothing technical is shown.
  return '';
}

function renderBlockChips(blocks, isLegacy) {
  let panel = document.querySelector('#rainfall-map');
  // Sangrur polygon chips are Sangrur-only: for generic (non-Sangrur)
  // selections the whole section stays hidden — never another block's data.
  if (!isLegacy) {
    if (panel) panel.hidden = true;
    return;
  }
  if (!panel) {
    panel = document.createElement('section');
    panel.id = 'rainfall-map';
    panel.className = 'rainfall-map';
    document.querySelector('.analysis-panel')?.append(panel);
  }
  panel.hidden = false;
  if (!blocks || !blocks.length) {
    panel.innerHTML = `<div class="map-heading"><div><p class="card-kicker">Block outlook</p><h4>Nearby blocks</h4></div></div><p>Forecast data is currently unavailable. Please try again.</p>`;
    return;
  }
  const F = (typeof SaarthiGeo !== 'undefined' && SaarthiGeo.fmt) || null;
  const chips = blocks.map((b) => {
    const raw = b.cum_7d_mm && b.cum_7d_mm.available ? b.cum_7d_mm.rainfall_mm : null;
    const cum = raw != null ? `${F ? F.rain(raw) : fmtRain1(raw)} / 7d` : '7d total unavailable';
    return `<li data-block="${b.block_name}" style="cursor:pointer;"><span><b>${b.block_name}</b><small>${cum}</small></span><strong>●</strong></li>`;
  }).join('');
  panel.innerHTML = `<div class="map-heading"><div><p class="card-kicker">Block outlook</p><h4>Nearby blocks</h4></div><span>Next 16 days</span></div><ul class="village-list">${chips}</ul>`;
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
    // Overlap fix (Task 1): the D1-D16 rainfall chart belongs to the forecast
    // card's own clear space, in normal flow, immediately after the 7-day curve
    // and BEFORE the "17-30 Day Climatological Outlook" card. It used to be
    // appended to .outlook-heading (the whole main column), which is the same
    // subtree that holds the climatology card, so the D-range bars and the
    // outlook card could occupy one visual band. The position is now inserted
    // explicitly, so no later element can land on top of the bars.
    const host = document.querySelector('.wx-forecast') || document.querySelector('.outlook-heading');
    const climatology = document.querySelector('#crop-advice');
    if (host && climatology && climatology.parentNode === host) {
      host.insertBefore(panel, climatology);
    } else if (host) {
      host.append(panel);
    }
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
  panel.innerHTML = `<div class="timeline-heading"><p class="card-kicker">Rainfall · next 16 days</p><span>16 days</span></div><div class="rain-bars horizon-7">${bars}</div><p class="timeline-note">Rainfall in millimetres · probability where available</p>`;
}

function renderValidityMeta(fc, block) {
  // Validity detail stays in the API payload; no source/provenance panel is shown.
  const panel = document.querySelector('#seasonal-signals');
  if (panel) { panel.hidden = true; panel.style.display = 'none'; panel.innerHTML = ''; }
}

async function loadLongRange(blockName, isLegacy) {
  let panel = document.querySelector('#crop-advice');
  if (!panel) {
    panel = document.createElement('section');
    panel.id = 'crop-advice';
    panel.className = 'crop-advice';
    // Own clear area below the forecast strip — never inside the 3-column
    // intelligence grid (it stretched the card cell and collided with charts).
    document.querySelector('.wx-forecast')?.append(panel);
  }
  if (!isLegacy) {
    panel.innerHTML = `<div class="crop-heading"><span>17–30 Day Climatological Outlook</span></div><p>Extended climatological outlook is currently served for supported blocks only. The live 16-day IFS forecast above is unaffected.</p>`;
    return;
  }
  panel.setAttribute('aria-busy', 'true');
  try {
    const res = await fetch(`/api/outlook/17-30/${encodeURIComponent(blockName)}`);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const o = await res.json();
    panel.removeAttribute('aria-busy');
    const pct = (v) => (v === null || v === undefined) ? '—' : `${Math.round(v * 100)}%`;
    const w = (n) => `<p style="margin:0 0 4px;font-size:12.5px;"><b>${n.horizon_label}</b> · ${n.period_start} → ${n.period_end} · climatological normal ${fmtNum1(n.climatological_normal_mm)} mm · below/near/above ${pct(n.below_probability)}/${pct(n.near_probability)}/${pct(n.above_probability)}</p>`;
    panel.innerHTML = `<div class="crop-heading"><span>17–30 Day Climatological Outlook (not an IFS forecast)</span></div>${w(o.w3)}${w(o.w4)}<p style="margin:0;font-size:12px;opacity:.8;">Confidence ${o.confidence}${o.confidence_reason ? ' — ' + o.confidence_reason : ''}</p><div class="crop-options"><span><small>Method</small><b>Climatology</b></span><span><small>Probabilities</small><b>tercile prior (1/3 each, not calibrated)</b></span></div>`;
  } catch (err) {
    panel.removeAttribute('aria-busy');
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
  if (ids('#risk-label')) ids('#risk-label').textContent = '16-day outlook';
  if (ids('#risk-probability')) ids('#risk-probability').textContent = `${days.length}-day outlook`;
  if (ids('#risk-meter')) ids('#risk-meter').style.width = '20%';
  const noteEl = ids('#model-note');
  if (noteEl) { noteEl.hidden = true; noteEl.style.display = 'none'; noteEl.textContent = ''; }
  renderTimeline(block);
  renderValidityMeta(fc, block);
  // Homepage enhancer (index.html): one-shot mirror + strip sync. Called
  // explicitly instead of MutationObservers — no observation loop.
  try { if (window.SaarthiHome) window.SaarthiHome.refresh(); } catch (e) {}
  const upd = document.querySelector('.updated');
  if (upd) {
    upd.textContent = 'Live';
    upd.style.fontWeight = '700';
  }
}

async function loadRisk(selection, isLegacy, legacyName) {
  const name = selection ? selection.block_name : legacyName;
  try {
    if (!isLegacy) {
      if (ids('#decision-status')) ids('#decision-status').textContent = 'Block-level outlook';
      if (ids('#decision-title')) ids('#decision-title').textContent = `Outlook for ${name}.`;
      if (ids('#decision-message')) ids('#decision-message').textContent = `Weather outlook is available for ${name}. Field-risk scoring is available for supported blocks only — the 16-day rainfall outlook above covers this block.`;
      if (ids('#wait-badge')) ids('#wait-badge').innerHTML = '<strong>—</strong>';
      return;
    }
    const rr = await fetch(`/api/risks/${encodeURIComponent(name)}?window=3d`);
    if (rr.ok) {
      const risk = await rr.json();
      if (ids('#decision-status')) ids('#decision-status').textContent = `${risk.overall_risk} — ${risk.primary_concern}`;
      if (ids('#decision-title')) ids('#decision-title').textContent = (risk.advisories && risk.advisories[0]) || `Field outlook for ${name}.`;
      if (ids('#decision-message')) ids('#decision-message').textContent = `Risk for ${name}: ${risk.overall_risk} (${risk.primary_concern}), confidence ${risk.confidence}.`;
      if (ids('#wait-badge')) ids('#wait-badge').innerHTML = `<strong>${risk.overall_risk}</strong>`;
      const riskNote = ids('#model-note');
      if (riskNote) { riskNote.hidden = true; riskNote.style.display = 'none'; riskNote.textContent = ''; }
      try { if (window.SaarthiHome) window.SaarthiHome.refresh(); } catch (e) {}
    }
  } catch (e) { console.error('Risk load failed:', e); }
}

async function loadOutlook(selection) {
  const panel = document.querySelector('.analysis-panel');
  // Honest indeterminate loading treatment (Issue 2): spinner + sweeping bar
  // in Saarthi glass language. No fake percentage — progress is unknown until
  // the live fetch settles, then this note is removed (or becomes an error).
  const showLoading = (text) => {
    if (!panel) return null;
    let note = panel.querySelector('.api-loading-note');
    if (!note) {
      note = document.createElement('div');
      note.className = 'api-loading-note';
      panel.prepend(note);
    }
    note.className = 'api-loading-note';
    note.setAttribute('role', 'status');
    note.setAttribute('aria-live', 'polite');
    note.innerHTML = '';
    const row = document.createElement('div');
    row.className = 'api-loading-header';
    const spin = document.createElement('span');
    spin.className = 'sa-spinner';
    spin.setAttribute('aria-hidden', 'true');
    const label = document.createElement('span');
    label.className = 'api-loading-text';
    label.textContent = text;
    row.append(spin, label);
    const bar = document.createElement('div');
    bar.className = 'sa-loading-bar';
    bar.setAttribute('aria-hidden', 'true');
    const fill = document.createElement('i');
    fill.className = 'sa-loading-fill';
    bar.append(fill);
    note.append(row, bar);
    return note;
  };
  const showStandby = (text) => {
    if (!panel) return null;
    let note = panel.querySelector('.api-loading-note');
    if (!note) {
      note = document.createElement('div');
      panel.prepend(note);
    }
    note.className = 'api-loading-note api-standby-note';
    note.removeAttribute('role');
    note.innerHTML = `<div class="api-loading-header"><span style="opacity:0.85;font-size:14px;color:#d8e87e;" aria-hidden="true">ℹ</span><span class="api-loading-text">${text}</span></div>`;
    return note;
  };
  document.querySelectorAll('.api-error-note').forEach((n) => n.remove());
  if (!selection) {
    if (ids('#analysis-block')) ids('#analysis-block').textContent = 'Select a block';
    const chips = document.querySelector('#rainfall-map');
    if (chips) chips.hidden = true;
    showStandby('Select State → District → Block to load the live forecast.');
    return;
  }
  if (ids('#analysis-block')) ids('#analysis-block').textContent = SaarthiGeo.locationLabel(selection);
  showLoading(`Loading outlook for ${selection.block_name}…`);
  if (panel) panel.setAttribute('aria-busy', 'true');
  try {
    const { envelope, block, isLegacy } = await SaarthiGeo.fetchBlockForecast(selection);
    const loading = document.querySelector('.api-loading-note');
    if (loading) loading.remove();
    if (panel) panel.removeAttribute('aria-busy');
    renderOutlookView(envelope, block, selection);
    try {
      // Sangrur polygon chips only make sense on the legacy Sangrur path;
      // renderBlockChips hides the section for generic blocks.
      if (isLegacy) {
        const all = await fetch('/api/weather/forecast');
        if (all.ok) renderBlockChips((await all.json()).blocks || [], true);
      } else {
        renderBlockChips([], false);
      }
    } catch (e) { console.error('Block chips failed:', e); }
    loadLongRange(selection.block_name, isLegacy);
    loadRisk(selection, isLegacy);
  } catch (err) {
    console.error('Outlook load failed:', err);
    if (panel) panel.removeAttribute('aria-busy');
    const loading = document.querySelector('.api-loading-note');
    if (loading) {
      // Transition the loading treatment into an honest error state.
      loading.className = 'api-loading-note api-error-note';
      loading.removeAttribute('role');
      loading.innerHTML = '';
      loading.style.color = '#ff9b86';
      loading.textContent = 'Live forecast unavailable.';
    }
    if (panel && !panel.querySelector('.api-error-note')) {
      const note = document.createElement('p');
      note.className = 'api-error-note';
      note.style.color = '#ff9b86';
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
