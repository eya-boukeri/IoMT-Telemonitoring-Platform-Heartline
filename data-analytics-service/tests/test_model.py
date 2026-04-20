from pathlib import Path

import joblib
import numpy as np
import pytest

from app.model import PPGModel


class DummyModel:
    def predict(self, x):
        return np.array([1])

    def predict_proba(self, x):
        return np.array([[0.1, 0.9]])


def test_model_init_raises_if_file_missing(tmp_path: Path):
    missing = tmp_path / "missing.joblib"

    with pytest.raises(FileNotFoundError):
        PPGModel(str(missing))


def test_model_predict_and_repr(tmp_path: Path):
    model_path = tmp_path / "ppg_model_test.joblib"
    joblib.dump(DummyModel(), model_path)

    model = PPGModel(str(model_path))
    pred_class, confidence = model.predict(np.ones(14))

    assert pred_class == 1
    assert confidence == 0.9
    assert "ppg_model_test" in repr(model)
