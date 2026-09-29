"""Generate notebooks/09_phase3_climate_outlook_validation.ipynb (Phase 3A Step 8).

The notebook documents and verifies the frozen artifacts; it does NOT retrain
anything and does NOT re-download. Heavy computation lives in
src/climatology/*.py (already run); this notebook loads artifacts, checks them,
plots the key figures, and states the gate/backtest conclusions.
Run: python src/climatology/make_notebook09.py  (writes the .ipynb)
"""
from pathlib import Path

import nbformat as nbf

REPO = Path(__file__).resolve().parents[2]
NB = REPO / "notebooks" / "09_phase3_climate_outlook_validation.ipynb"


def md(src: str):
    return nbf.v4.new_markdown_cell(src)


def code(src: str):
    return nbf.v4.new_code_cell(src)


cells = [
    md("# 09 — Phase 3A Climate Outlook Validation\n"
       "Block rainfall climatology + MJO phase-composite gate + W3/W4 backtest.\n\n"
       "**Rule:** no new ML, MJO CNN-LSTM frozen, no future leakage. "
       "Fit period 2010–2019; test period 2020–2025. "
       "Run order: `build_normals.py` → `mjo_composites.py` → `backtest_w3w4.py` → this notebook."),
    md("## 1. Data sources\n"
       "- `data/raw/rainfall/Sangrur_Block_Daily_Rainfall_2010_2025.csv` — 35,064 rows, "
       "2010-01-01..2025-12-31, 6 blocks × 5844 d, 0 NaN, 0 dupes, units mm (verified Step 1).\n"
       "- `data/raw/mjo/MJO_RMM_cleaned_core.csv` — 18,802 rows, 1974-06-01..2026-09-07, "
       "phases 1–8, one 291-d gap (1978), **provenance NOT verified**.\n"
       "- `data/processed/climatology/block_doy_normals_train_2010_2019.csv` (train-frozen) + "
       "`block_doy_normals_full.csv` (**deployment only**)."),
    md("## 2. Preprocessing\n"
       "Zero NaNs in source; every 0.0 is an observed no-rain day (nothing filled). "
       "Feb-29 pooled into DOY 60. Per block × issue-DOY pooling over years × circular ±7 d window; "
       "no post-smoothing. Wet day: ≥ 1.0 mm (project convention, not IMD)."),
    md("## 3. Leakage rules\n"
       "Climatology built from dates strictly before the cutoff (`--end-date`). "
       "All composite/backtest conditioning tables fit on 2010–2019 only; test issues are "
       "weekly JJAS 2020–2025 (last issue 2025-12-01, need D+30 observed). "
       "The `full` artifact is labelled `deployment` and never used in backtests."),
    code("%matplotlib inline\nimport json\nimport pandas as pd\nimport numpy as np\nimport matplotlib.pyplot as plt\n"
         "from pathlib import Path\n"
         "ROOT = Path('.').resolve()\n"
         "CLIM = (ROOT/'data/processed/climatology' if (ROOT/'data').exists()\n"
         "        else ROOT.parent/'data/processed/climatology')  # kernel cwd: repo root or notebooks/\n"
         "train = pd.read_csv(CLIM/'block_doy_normals_train_2010_2019.csv')\n"
         "full = pd.read_csv(CLIM/'block_doy_normals_full.csv')\n"
         "method = json.load(open(CLIM/'method_train_2010_2019.json'))\n"
         "print(method['kind'], method['span_used'], '|', method['leakage_statement'][:80], '...')\n"
         "print(train.shape, full.shape)"),
    md("## 4. Climatology — seasonal cycle per block (train-frozen)"),
    code("fig, ax = plt.subplots(figsize=(9, 4))\n"
         "for b, g in train.groupby('block'):\n"
         "    ax.plot(g['doy'], g['daily_mean_mm'], label=b, lw=1.2)\n"
         "ax.set(xlabel='Day of year (issue DOY)', ylabel='Daily mean rainfall (mm)',\n"
         "       title='Block x DOY climatology, train 2010-2019 (pooled +-7d)')\n"
         "ax.legend(ncol=3, fontsize=8); fig.tight_layout(); plt.show()"),
    md("## 5. MJO composites — method\n"
       "Active MJO: amplitude ≥ 1.0 (Wheeler–Hendon). MJO state at issue D "
       "(operations must lag ~3 d; backtest repeats with D-3). Lead scan offsets 3/10/17/24 d "
       "(product needs 17/24 = W3/W4). Anomaly vs train climatological expected window sum. "
       "Gate (pre-registered, train JJAS/active/offset-17): GO iff ≥2 phases with |mean pct| ≥ 15%, "
       "95% CI excludes 0, same sign in ≥5/6 blocks, same sign in 2010-2014 vs 2015-2019."),
    code("gate = json.load(open(CLIM/'mjo_gate.json'))\n"
         "print('GATE:', gate['decision'], '| qualifying:', gate['qualifying_phases'])\n"
         "comp = pd.read_csv(CLIM/'mjo_composites.csv')\n"
         "j = comp[(comp.jjas==1) & (comp.active) & (comp.offset==17)]\n"
         "print(j.groupby('phase').agg(n=('n','sum'), mean_pct=('mean_pct','mean')).round(1).to_string())"),
    md("## 6. MJO composites — lead decay of gate phases (train JJAS, active)"),
    code("fig, axes = plt.subplots(1, 3, figsize=(12, 3.5), sharey=True)\n"
         "for ax, p in zip(axes, [2, 6, 7]):\n"
         "    g = comp[(comp.phase==p) & (comp.active) & (comp.jjas==1)]\n"
         "    m = g.groupby('offset')['mean_pct'].mean()\n"
         "    ax.bar(m.index.astype(str), m.values)\n"
         "    ax.set_title(f'Phase {p}: mean % anomaly by lead'); ax.axhline(0, color='k', lw=0.8)\n"
         "axes[0].set_ylabel('% anomaly vs climatology'); fig.tight_layout(); plt.show()"),
    md("## 7. Recent-rainfall baseline\n"
       "Trailing-14d sums (D-14..D-1, strictly before D) terciled per (block, DOY) from train; "
       "P(W-tercile | trail-tercile) contingency from train (Laplace-smoothed). No ML."),
    md("## 8. W3/W4 targets\n"
       "W3 = sum(D+17..D+23), W4 = sum(D+24..D+30) per block; labels below/near/above vs "
       "train W3/W4 terciles for the issue DOY (NOT the frozen 7-day 10.94/34.66 mm thresholds). "
       "Boundary rule: below if x ≤ t33, above if x > t66, else near."),
    md("## 9. Backtest — Brier / RPS / accuracy (test JJAS weekly 2020–2025, n=630/arm/window)"),
    code("met = json.load(open(CLIM/'backtest_metrics.json'))\n"
         "for k, v in met['arms'].items():\n"
         "    if 'JJAS' in k and 'mjolag0' in k:\n"
         "        print(k, v)"),
    md("## 10. Reliability of P(above-normal) + confusion (JJAS W3)"),
    code("for k, v in met['reliability_P_above'].items():\n"
         "    if k.startswith('W3'):\n"
         "        print(k, v)\n"
         "print()\n"
         "for k, v in met['confusion'].items():\n"
         "    if k.startswith('W3'):\n"
         "        print(k, v)"),
    md("## 11. MJO go/no-go (final)\n"
       "In-sample gate: GO (phases 2, 6, 7 — physically coherent with the canonical "
       "MJO–monsoon teleconnection, 6/6 block agreement). **Out-of-sample backtest: both "
       "CLIM+RECENT and CLIM+RECENT+MJO score negative Brier skill vs flat climatology "
       "(W3 −1.1%/−3.4%, W4 −1.5%/−2.5%; JJAS-only-fit sensitivity also negative).** "
       "Accuracy rises slightly but probabilities are miscalibrated (overconfident). "
       "**Final decision: NO-GO for MJO mathematics — MJO remains explanatory-only.** "
       "Recent-anomaly conditioning also fails → trailing rainfall stays display-only."),
    md("## 12. Conclusions for Phase 3B\n"
       "Ship: frozen deployment climatology (normals + W3/W4 terciles + wet-day probs) and "
       "observed trailing totals/anomalies as DISPLAY. Do not ship: MJO/recent probability modifiers, "
       "any new ML, OutlookService (needs its own build step). Revisit MJO math only with calibrated "
       "probabilities on more data — not by tuning this result."),
]

nb = nbf.v4.new_notebook()
nb.metadata.update({"kernelspec": {"display_name": "Python 3", "language": "python", "name": "python3"},
                    "language_info": {"name": "python"}})
nb.cells = cells
NB.write_text(nbf.writes(nb), encoding="utf-8")
print(f"wrote {NB} ({len(cells)} cells)")
