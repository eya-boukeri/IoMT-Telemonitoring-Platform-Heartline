"""
model.py
Wrapper around the pre-trained XGBoost model (.joblib).
The model is loaded once at startup; inference is stateless.
"""

import os
import logging
import numpy as np
import joblib
from typing import Tuple, List

from app.config import MODEL_PATH

logger = logging.getLogger(__name__)


class PPGModel:
    """
    Loads and wraps the pre-trained XGBoost PPG anomaly detector.

    predict() returns:
        predicted_class (int)  : 0 = normal, 1 = anomaly
        confidence      (float): probability of the predicted class [0, 1]
    """

    def __init__(self):
        self._model      = None
        self.model_path  = MODEL_PATH
        # Extract version string from filename, e.g. "ppg_model_v2.1.joblib" → "v2.1"
        self.model_version = self._parse_version(MODEL_PATH)
        self._load()

    # ── Loading ────────────────────────────────────────────────────────────────

    def _load(self):
        if not os.path.exists(self.model_path):
            raise FileNotFoundError(
                f"Model file not found: {self.model_path}. "
                "Run create_dummy_model.py to generate a test model."
            )
        logger.info(f"Loading model from {self.model_path} (version: {self.model_version})")
        self._model = joblib.load(self.model_path)
        logger.info("Model loaded successfully.")

    @staticmethod
    def _parse_version(path: str) -> str:
        """Extract version tag from filename, fallback to 'unknown'."""
        basename = os.path.basename(path)           # ppg_model_v2.1.joblib
        name     = os.path.splitext(basename)[0]    # ppg_model_v2.1
        parts    = name.split("_")
        for part in reversed(parts):
            if part.startswith("v") and len(part) > 1:
                return part
        return "unknown"

    # ── Inference ──────────────────────────────────────────────────────────────

    def predict(self, features: List[float]) -> Tuple[int, float]:
        """
        Run inference on a single feature vector.

        Args:
            features: list of 14 floats from feature_extractor.extract_features()

        Returns:
            (predicted_class, confidence)
            predicted_class: 0 = normal, 1 = anomaly
            confidence: max class probability from predict_proba()
        """
        if self._model is None:
            raise RuntimeError("Model not loaded.")

        X = np.array(features, dtype=float).reshape(1, -1)

        predicted_class = int(self._model.predict(X)[0])

        # Use predict_proba when available, else assign hard confidence
        if hasattr(self._model, "predict_proba"):
            proba      = self._model.predict_proba(X)[0]
            confidence = float(np.max(proba))
        else:
            confidence = 1.0 if predicted_class == 1 else 0.0

        return predicted_class, confidence