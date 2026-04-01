"""
feature_extractor.py
Transforms a raw PPG signal window (list of floats) into a 14-feature vector.
These features must match exactly those used during XGBoost training.
"""

import warnings

import numpy as np
from scipy import signal as scipy_signal
from scipy.stats import skew, kurtosis
from typing import List

# np.trapezoid replaces np.trapz in NumPy >= 2.0
_trapz = getattr(np, "trapezoid", getattr(np, "trapz", None))


# ── Constants ──────────────────────────────────────────────────────────────────
SAMPLING_RATE   = 25    # Hz  (typical PPG sensor sampling rate)
LF_LOW, LF_HIGH = 0.04, 0.15   # Low-Frequency band (Hz)
HF_LOW, HF_HIGH = 0.15, 0.40   # High-Frequency band (Hz)
MIN_SIGNAL_LEN  = 10            # minimum samples to compute features


def extract_features(ppg_window: List[float]) -> List[float]:
    """
    Extract 14 features from a PPG signal window.

    Args:
        ppg_window: list of raw PPG float values

    Returns:
        List of 14 floats (feature vector), or list of 14 zeros if signal is invalid.

    Features (in order):
        0  mean
        1  std
        2  rms
        3  skewness
        4  kurtosis
        5  dominant_frequency
        6  lf_power
        7  hf_power
        8  lf_hf_ratio
        9  mean_rr_interval
        10 std_rr_interval
        11 mean_peak_amplitude
        12 num_peaks
        13 valid_peaks_ratio
    """
    if len(ppg_window) < MIN_SIGNAL_LEN:
        return [0.0] * 14

    x = np.array(ppg_window, dtype=float)

    # ── 1. Time-domain statistics ───────────────────────────────────────────────
    feat_mean     = float(np.mean(x))
    feat_std      = float(np.std(x))
    feat_rms      = float(np.sqrt(np.mean(x ** 2)))
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", RuntimeWarning)
        feat_skew     = float(skew(x))
        feat_kurtosis = float(kurtosis(x))

    # ── 2. Frequency-domain features ────────────────────────────────────────────
    freqs, psd = scipy_signal.welch(
        x,
        fs=SAMPLING_RATE,
        nperseg=min(len(x), 64),
        scaling="density",
    )

    # Dominant frequency
    feat_dom_freq = float(freqs[np.argmax(psd)])

    # Band powers
    lf_mask   = (freqs >= LF_LOW) & (freqs <= LF_HIGH)
    hf_mask   = (freqs >= HF_LOW) & (freqs <= HF_HIGH)
    lf_power  = float(_trapz(psd[lf_mask], freqs[lf_mask])) if lf_mask.any() else 0.0
    hf_power  = float(_trapz(psd[hf_mask], freqs[hf_mask])) if hf_mask.any() else 0.0
    lf_hf_ratio = lf_power / hf_power if hf_power > 1e-10 else 0.0

    # ── 3. Morphological features (peak detection) ───────────────────────────────
    # Normalize for peak detection
    x_norm = (x - np.min(x)) / (np.ptp(x) + 1e-10)

    min_distance = max(1, int(SAMPLING_RATE * 0.4))   # ~0.4s between heartbeats
    peaks, props = scipy_signal.find_peaks(
        x_norm,
        height=0.3,
        distance=min_distance,
        prominence=0.1,
    )

    num_peaks         = len(peaks)
    total_samples     = len(x)
    valid_peaks_ratio = num_peaks / max(1, total_samples / SAMPLING_RATE)

    if num_peaks >= 2:
        rr_intervals    = np.diff(peaks) / SAMPLING_RATE   # in seconds
        mean_rr         = float(np.mean(rr_intervals))
        std_rr          = float(np.std(rr_intervals))
        mean_peak_amp   = float(np.mean(x[peaks]))
    elif num_peaks == 1:
        mean_rr       = 0.0
        std_rr        = 0.0
        mean_peak_amp = float(x[peaks[0]])
    else:
        mean_rr       = 0.0
        std_rr        = 0.0
        mean_peak_amp = 0.0

    return [
        feat_mean,          # 0
        feat_std,           # 1
        feat_rms,           # 2
        feat_skew,          # 3
        feat_kurtosis,      # 4
        feat_dom_freq,      # 5
        lf_power,           # 6
        hf_power,           # 7
        lf_hf_ratio,        # 8
        mean_rr,            # 9
        std_rr,             # 10
        mean_peak_amp,      # 11
        float(num_peaks),   # 12
        valid_peaks_ratio,  # 13
    ]