"""
create_dummy_model.py
Generates a minimal XGBoost model (trained on synthetic data) and saves it
as models/ppg_model_v1.0.joblib so the service can start without a real model.

Run once:
    python create_dummy_model.py
"""

import os
import numpy as np
import joblib
from xgboost import XGBClassifier

MODELS_DIR  = os.path.join(os.path.dirname(__file__), "models")
MODEL_FILE  = os.path.join(MODELS_DIR, "ppg_model_v1.0.joblib")
N_FEATURES  = 14
N_SAMPLES   = 500  # synthetic samples

os.makedirs(MODELS_DIR, exist_ok=True)

rng = np.random.default_rng(42)

# ── Synthetic dataset: 14 features, binary label ──────────────────────────────
X = rng.standard_normal((N_SAMPLES, N_FEATURES)).astype(np.float32)
# Simple rule: anomaly if mean of first 3 features > 0.5
y = ((X[:, 0] + X[:, 1] + X[:, 2]) > 0.5).astype(int)

model = XGBClassifier(
    n_estimators   = 50,
    max_depth      = 3,
    use_label_encoder = False,
    eval_metric    = "logloss",
    random_state   = 42,
)
model.fit(X, y)

joblib.dump(model, MODEL_FILE)
print(f"Dummy model saved → {MODEL_FILE}")
print(f"  Classes : {model.classes_}")
print(f"  Features: {N_FEATURES}")