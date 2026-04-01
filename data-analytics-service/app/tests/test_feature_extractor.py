"""
tests/test_feature_extractor.py
Unit tests for the feature extraction module.
Run with:  pytest tests/ -v
"""

import math
import numpy as np
import pytest

from app.feature_extractor import extract_features, SAMPLING_RATE, MIN_SIGNAL_LEN

N_FEATURES = 14


# ── helpers ────────────────────────────────────────────────────────────────────

def _sine_wave(freq_hz: float, duration_s: float = 5.0) -> list:
    t = np.linspace(0, duration_s, int(duration_s * SAMPLING_RATE), endpoint=False)
    return list(np.sin(2 * np.pi * freq_hz * t))


def _ppg_like(n: int = 200) -> list:
    """Simple synthetic PPG: sine at 1.2 Hz (72 bpm)."""
    return _sine_wave(1.2, duration_s=n / SAMPLING_RATE)


# ── tests ──────────────────────────────────────────────────────────────────────

class TestFeatureCount:
    def test_returns_14_features(self):
        features = extract_features(_ppg_like())
        assert len(features) == N_FEATURES, f"Expected {N_FEATURES}, got {len(features)}"

    def test_all_features_are_float(self):
        features = extract_features(_ppg_like())
        for i, f in enumerate(features):
            assert isinstance(f, float), f"Feature {i} is {type(f)}, expected float"


class TestShortSignal:
    def test_too_short_returns_zeros(self):
        features = extract_features([0.1] * (MIN_SIGNAL_LEN - 1))
        assert features == [0.0] * N_FEATURES

    def test_empty_returns_zeros(self):
        features = extract_features([])
        assert features == [0.0] * N_FEATURES

    def test_exact_min_length_does_not_crash(self):
        features = extract_features([0.5] * MIN_SIGNAL_LEN)
        assert len(features) == N_FEATURES


class TestConstantSignal:
    def test_constant_signal_std_is_zero(self):
        features = extract_features([1.0] * 200)
        std = features[1]
        assert math.isclose(std, 0.0, abs_tol=1e-9), f"Expected std=0, got {std}"

    def test_constant_signal_mean_matches(self):
        value    = 3.7
        features = extract_features([value] * 200)
        assert math.isclose(features[0], value, rel_tol=1e-6), \
            f"Expected mean={value}, got {features[0]}"


class TestDominantFrequency:
    def test_dominant_freq_detected(self):
        """A pure sine at 1.2 Hz should yield dominant_freq ≈ 1.2 Hz."""
        signal   = _sine_wave(1.2, duration_s=10.0)
        features = extract_features(signal)
        dom_freq = features[5]   # index 5 = dominant_frequency
        assert abs(dom_freq - 1.2) < 0.3, \
            f"Expected dominant_freq ≈ 1.2 Hz, got {dom_freq:.3f}"


class TestPeakDetection:
    def test_peaks_detected_on_sine(self):
        """A 1 Hz sine over 5 s should yield ~5 peaks."""
        signal   = _sine_wave(1.0, duration_s=5.0)
        features = extract_features(signal)
        num_peaks = int(features[12])   # index 12 = num_peaks
        assert 3 <= num_peaks <= 7, f"Expected ~5 peaks, got {num_peaks}"

    def test_flat_signal_no_peaks(self):
        features  = extract_features([0.5] * 200)
        num_peaks = int(features[12])
        assert num_peaks == 0, f"Expected 0 peaks on flat signal, got {num_peaks}"


class TestBandPowers:
    def test_lf_hf_ratio_nonnegative(self):
        features    = extract_features(_ppg_like())
        lf_hf_ratio = features[8]   # index 8
        assert lf_hf_ratio >= 0.0, f"lf_hf_ratio must be >= 0, got {lf_hf_ratio}"

    def test_lf_hf_powers_nonnegative(self):
        features = extract_features(_ppg_like())
        assert features[6] >= 0.0, "lf_power must be >= 0"
        assert features[7] >= 0.0, "hf_power must be >= 0"


class TestHigherOrderStats:
    def test_gaussian_noise_skew_near_zero(self):
        rng      = np.random.default_rng(0)
        noise    = list(rng.standard_normal(500))
        features = extract_features(noise)
        sk       = features[3]   # skewness
        assert abs(sk) < 0.5, f"Gaussian noise skewness should be ~0, got {sk:.3f}"

    def test_gaussian_noise_kurtosis_near_zero(self):
        """scipy.stats.kurtosis returns *excess* kurtosis; Gaussian ≈ 0."""
        rng      = np.random.default_rng(1)
        noise    = list(rng.standard_normal(1000))
        features = extract_features(noise)
        kurt     = features[4]   # kurtosis
        assert abs(kurt) < 1.0, f"Gaussian noise excess kurtosis should be ~0, got {kurt:.3f}"