import numpy as np
import pytest

from app.feature_extractor import PPGFeatureExtractor


def test_extract_raises_on_short_signal():
    extractor = PPGFeatureExtractor(sampling_rate=100)
    short_signal = [0.1] * 150  # < 2 * sampling_rate

    with pytest.raises(ValueError):
        extractor.extract(short_signal)


def test_extract_returns_expected_shape_and_finite_values():
    extractor = PPGFeatureExtractor(sampling_rate=100)
    t = np.linspace(0, 10, 1000, endpoint=False)
    signal = np.sin(2 * np.pi * 1.2 * t) + 0.05 * np.random.RandomState(42).randn(len(t))

    features = extractor.extract(signal.tolist())

    assert features.shape == (len(PPGFeatureExtractor.FEATURE_NAMES),)
    assert np.all(np.isfinite(features))


def test_morphological_features_detect_regular_peaks():
    extractor = PPGFeatureExtractor(sampling_rate=100)
    sig = np.zeros(1000)

    # Pic toutes les 100 samples -> ~60 BPM
    peak_positions = np.arange(100, 1000, 100)
    sig[peak_positions] = 1.0

    morph = extractor._morphological_features(sig)

    assert morph["num_peaks"] >= 8
    assert 55 <= morph["hr_estimate"] <= 65
