"""Experiment: which early-stopping criterion is stable under sparse, delayed labels?

Selection uses only validation *observed* labels (what is known at training time), never the test
window and never ground truth.
"""
import lightgbm as lgb
from sklearn.metrics import average_precision_score, log_loss

from fraudlab.dataset import build_splits
from fraudlab.features import BASE_FEATURES as F

s = build_splits("aldermoor-bank")
tr, va = s.part("train"), s.part("valid")
y, yv = tr["label_observed"].astype(int), va["label_observed"].astype(int)
for name, metric, lr in [("ap_stop", "average_precision", 0.05), ("logloss_stop", "binary_logloss", 0.05),
                         ("logloss_stop_lr02", "binary_logloss", 0.02)]:
    m = lgb.LGBMClassifier(n_estimators=2000, learning_rate=lr, num_leaves=31, min_child_samples=50, subsample=0.8,
                           subsample_freq=1, colsample_bytree=0.8, reg_lambda=1.0, random_state=42, n_jobs=4,
                           deterministic=True, force_row_wise=True, verbose=-1)
    m.fit(tr[F], y, eval_X=(va[F],), eval_y=(yv,), eval_metric=metric,
          callbacks=[lgb.early_stopping(100, first_metric_only=True, verbose=False)])
    p = m.predict_proba(va[F])[:, 1]
    print(f"{name:20s} iters={m.best_iteration_:4d} valid_obs_ap={average_precision_score(yv, p):.3f} "
          f"valid_obs_logloss={log_loss(yv, p):.5f} mean_p={p.mean():.5f} obs_rate={yv.mean():.5f}", flush=True)
