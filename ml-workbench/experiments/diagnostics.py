"""Diagnostics used while interpreting evaluation results (validation window unless stated)."""
import json
from fraudlab.dataset import build_splits

for c in ("aldermoor-bank", "quillon-pay"):
    s = build_splits(c)
    te = s.part("test")
    emg = te[te["scenario_id"].fillna("").str.startswith("EMG")]
    print(c, "emerging ATO rows:", len(emg),
          "mean graph_beneficiary_risk:", round(float(emg["graph_beneficiary_risk"].mean()), 3),
          "share with beneficiary risk>0:", round(float((emg["graph_beneficiary_risk"] > 0).mean()), 3))
    r = json.load(open(f"reports/{c}/evaluation-{c}-lgbm-1.0.0.json"))
    for v in ("ml_only", "hybrid_full", "strategy_as_configured"):
        m = r["variants"][v]["test"]
        print("  ", v, "decline_precision", m["decline_precision"], "false_declines", m["false_declines"],
              "legit_flagged_per_1000", m["legit_flagged_per_1000_legit"])
