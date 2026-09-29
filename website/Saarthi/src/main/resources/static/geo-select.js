/**
 * SaarthiGeo — shared State → District → Block cascade + selection state.
 * Used by the homepage (app.js) and the portal (portal.js).
 *
 * Geography lists are NEVER hardcoded here: states/districts/blocks come
 * from /api/geography/*. The persisted selection is explicit (no silent
 * Sangrur default drives weather — the UI always shows what is selected).
 *
 * Selection shape:
 *   { state_code, state_name, district_code, district_name,
 *     block_code, block_name, latitude, longitude }
 */

const SaarthiGeo = (() => {
  const STORE_KEY = 'saarthi_geo_selection';

  async function getJSON(url) {
    let res;
    try {
      res = await fetch(url);
    } catch (err) {
      throw new Error('Network unreachable — check connection and retry');
    }
    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      throw new Error(err.message || `HTTP ${res.status}`);
    }
    return res.json();
  }

  const loadStates = () => getJSON('/api/geography/states').then((d) => d.states || []);
  const loadDistricts = (stateCode) =>
    getJSON(`/api/geography/districts?state=${encodeURIComponent(stateCode)}`)
      .then((d) => d.districts || []);
  const loadBlocks = (districtCode) =>
    getJSON(`/api/geography/blocks?district=${encodeURIComponent(districtCode)}`)
      .then((d) => d.blocks || []);

  function setOptions(select, items, valueKey, labelFn, placeholder) {
    select.innerHTML = '';
    const ph = document.createElement('option');
    ph.value = '';
    ph.textContent = placeholder;
    select.appendChild(ph);
    for (const item of items) {
      const opt = document.createElement('option');
      opt.value = item[valueKey];
      opt.textContent = labelFn(item);
      // Keep full row for blocks so coordinates travel with the selection.
      if (item.latitude !== undefined) opt._row = item;
      select.appendChild(opt);
    }
    select.disabled = false;
  }

  function setStandby(select, text) {
    select.innerHTML = '';
    const opt = document.createElement('option');
    opt.value = '';
    opt.textContent = text;
    select.appendChild(opt);
    select.disabled = true;
  }

  function saveSelection(sel) {
    try {
      if (sel) localStorage.setItem(STORE_KEY, JSON.stringify(sel));
      else localStorage.removeItem(STORE_KEY);
    } catch (e) { /* private mode: selection simply won't persist */ }
  }

  function loadSelection() {
    try {
      const raw = localStorage.getItem(STORE_KEY);
      return raw ? JSON.parse(raw) : null;
    } catch (e) {
      return null;
    }
  }

  function locationLabel(sel) {
    if (!sel) return '';
    const districtState = [sel.district_name, sel.state_name].filter(Boolean).join(', ');
    return districtState ? `${sel.block_name} · ${districtState}` : sel.block_name;
  }

  /**
   * Wire three <select> elements into a cascade. Every level handles
   * loading / empty / API-error states; onChange receives a full
   * selection object or null while the selection is incomplete.
   * Returns { refresh } for external triggers (e.g. map-chip clicks).
   */
  function wireCascade(stateSel, districtSel, blockSel, onChange, labels = {}) {
    const L = Object.assign({
      loadingStates: 'Loading states…', statesUnavailable: 'States unavailable',
      chooseState: 'Select state', loadingDistricts: 'Loading districts…',
      chooseDistrict: 'Select district', noDistricts: 'No districts',
      districtsUnavailable: 'Districts unavailable', selectStateFirst: 'Select state first',
      loadingBlocks: 'Loading blocks…', chooseBlock: 'Select block',
      noBlocks: 'No blocks', blocksUnavailable: 'Blocks unavailable',
      selectDistrictFirst: 'Select district first',
    }, labels);

    let current = null;
    const emit = () => { saveSelection(current); onChange(current); };

    stateSel.addEventListener('change', async () => {
      const code = stateSel.value;
      current = null;
      if (!code) {
        setStandby(districtSel, L.selectStateFirst);
        setStandby(blockSel, L.selectDistrictFirst);
        emit();
        return;
      }
      setStandby(districtSel, L.loadingDistricts);
      setStandby(blockSel, L.selectDistrictFirst);
      emit();
      try {
        const districts = await loadDistricts(code);
        if (!districts.length) { setStandby(districtSel, L.noDistricts); return; }
        const stateName = stateSel.options[stateSel.selectedIndex].textContent;
        districtSel._stateName = stateName;
        setOptions(districtSel, districts, 'district_code',
          (d) => d.district_name, L.chooseDistrict);
      } catch (err) {
        console.error('Districts failed:', err);
        setStandby(districtSel, `${L.districtsUnavailable} (${err.message})`);
      }
    });

    districtSel.addEventListener('change', async () => {
      const code = districtSel.value;
      current = null;
      if (!code) { setStandby(blockSel, L.selectDistrictFirst); emit(); return; }
      setStandby(blockSel, L.loadingBlocks);
      emit();
      try {
        const blocks = await loadBlocks(code);
        if (!blocks.length) { setStandby(blockSel, L.noBlocks); return; }
        setOptions(blockSel, blocks, 'block_code',
          (b) => b.block_name, L.chooseBlock);
      } catch (err) {
        console.error('Blocks failed:', err);
        setStandby(blockSel, `${L.blocksUnavailable} (${err.message})`);
      }
    });

    blockSel.addEventListener('change', () => {
      const opt = blockSel.options[blockSel.selectedIndex];
      const row = opt && opt._row;
      current = null;
      if (!row) { emit(); return; }
      const stateOpt = stateSel.options[stateSel.selectedIndex];
      const distOpt = districtSel.options[districtSel.selectedIndex];
      current = {
        state_code: stateSel.value,
        state_name: (stateOpt && stateOpt.textContent) || '',
        district_code: districtSel.value,
        district_name: (distOpt && distOpt.textContent) || '',
        block_code: row.block_code,
        block_name: row.block_name,
        latitude: row.latitude,
        longitude: row.longitude,
      };
      emit();
    });

    async function init() {
      setStandby(stateSel, L.loadingStates);
      setStandby(districtSel, L.selectStateFirst);
      setStandby(blockSel, L.selectDistrictFirst);
      try {
        const states = await loadStates();
        if (!states.length) { setStandby(stateSel, L.statesUnavailable); emit(); return; }
        setOptions(stateSel, states, 'state_code', (s) => s.state_name, L.chooseState);
      } catch (err) {
        console.error('States failed:', err);
        setStandby(stateSel, `${L.statesUnavailable} (${err.message})`);
        emit();
        return;
      }
      // Restore persisted selection (verified against the live registry).
      const saved = loadSelection();
      if (saved && saved.state_code && saved.district_code && saved.block_code) {
        try {
          if ([...stateSel.options].some((o) => o.value === saved.state_code)) {
            stateSel.value = saved.state_code;
            stateSel.dispatchEvent(new Event('change'));
            await waitFor(() => [...districtSel.options].some((o) => o.value === saved.district_code) && !districtSel.disabled, 8000);
            if ([...districtSel.options].some((o) => o.value === saved.district_code)) {
              districtSel.value = saved.district_code;
              districtSel.dispatchEvent(new Event('change'));
              await waitFor(() => [...blockSel.options].some((o) => o.value === saved.block_code) && !blockSel.disabled, 8000);
              if ([...blockSel.options].some((o) => o.value === saved.block_code)) {
                blockSel.value = saved.block_code;
                blockSel.dispatchEvent(new Event('change'));
                return;
              }
            }
          }
        } catch (err) {
          console.warn('Selection restore failed, falling back:', err);
        }
      }
      // Explicit visible default: Sunam (Sangrur) — shown in the UI, never silent.
      try {
        await selectByCodes('3', '43', '350');
      } catch (err) {
        console.warn('Default selection unavailable:', err);
      }
    }

    function waitFor(cond, timeoutMs) {
      return new Promise((resolve, reject) => {
        const start = Date.now();
        const tick = () => {
          let ok = false;
          try { ok = cond(); } catch (e) { ok = false; }
          if (ok) return resolve();
          if (Date.now() - start > timeoutMs) return reject(new Error('restore timeout'));
          setTimeout(tick, 120);
        };
        tick();
      });
    }

    /** Programmatic selection (e.g. map-chip clicks resolve via /search first). */
    async function selectByCodes(stateCode, districtCode, blockCode) {
      if (![...stateSel.options].some((o) => o.value === stateCode)) {
        throw new Error('State not in registry');
      }
      stateSel.value = stateCode;
      stateSel.dispatchEvent(new Event('change'));
      await waitFor(() => [...districtSel.options].some((o) => o.value === districtCode) && !districtSel.disabled, 8000);
      districtSel.value = districtCode;
      districtSel.dispatchEvent(new Event('change'));
      await waitFor(() => [...blockSel.options].some((o) => o.value === blockCode) && !blockSel.disabled, 8000);
      blockSel.value = blockCode;
      blockSel.dispatchEvent(new Event('change'));
    }

    init();
    return { selectByCodes, getSelection: () => current };
  }

  /**
   * Live forecast for a selection. Tries the legacy Sangrur polygon
   * endpoint first (multipoint path preserved); any failure falls back to
   * the generic centroid endpoint. Returns
   * { envelope, block, isLegacy, selection }.
   */
  async function fetchBlockForecast(sel) {
    if (!sel) throw new Error('No block selected');
    try {
      const res = await fetch(`/api/weather/forecast/${encodeURIComponent(sel.block_name)}`);
      if (res.ok) {
        const env = await res.json();
        if (env && env.block) return { envelope: env, block: env.block, isLegacy: true, selection: sel };
      }
    } catch (err) {
      console.warn('Legacy forecast unavailable, using centroid path:', err);
    }
    const url = `/api/weather/forecast/by-coords?lat=${sel.latitude}&lon=${sel.longitude}`
      + `&name=${encodeURIComponent(sel.block_name)}`;
    let res;
    try {
      res = await fetch(url);
    } catch (err) {
      throw new Error('Network unreachable — check connection and retry');
    }
    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      throw new Error(err.message || `HTTP ${res.status}`);
    }
    const env = await res.json();
    return { envelope: env, block: env.block, isLegacy: false, selection: sel };
  }

  /** Registry name search (lets legacy UI names resolve to coordinates). */
  async function searchBlocks(name) {
    const d = await getJSON(`/api/geography/search?name=${encodeURIComponent(name)}`);
    return d.blocks || [];
  }

  /**
   * Display-only number formatting (Bug-4 cleanup). API values are never
   * rounded before calculation — these helpers format for display only so
   * the UI never shows floating-point artefacts like 57.49999999999999 mm.
   */
  function num1(v) {
    if (v === null || v === undefined || !Number.isFinite(Number(v))) return null;
    const r = Math.round(Number(v) * 10) / 10;
    return Number.isInteger(r) ? String(r) : r.toFixed(1);
  }
  const fmt = {
    /** Rainfall in mm, 1 decimal (integers shown without decimals). */
    rain(v) {
      const s = num1(v);
      return s === null ? 'No data' : `${s} mm`;
    },
    /** Soil moisture (volumetric fraction), 2 decimals. */
    soil(v) {
      if (v === null || v === undefined || !Number.isFinite(Number(v))) return 'No data';
      return `${Number(v).toFixed(2)} m³/m³`;
    },
    /** Percentages, 1 decimal. */
    pct(v) {
      const s = num1(v);
      return s === null ? '—' : `${s}%`;
    },
    /** Temperatures, 1 decimal. */
    temp(v) {
      const s = num1(v);
      return s === null ? '—' : `${s}°C`;
    },
  };

  return {
    wireCascade, fetchBlockForecast, searchBlocks,
    loadSelection, saveSelection, locationLabel, getJSON, fmt,
  };
})();
