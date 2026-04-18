"""
feature_extractor.py — Transforme un signal PPG brut en vecteur de features.

Un signal PPG (PhotoPlethysmoGraphie) est une série temporelle capturant les
variations de volume sanguin dans les vaisseaux. On en extrait 3 familles de
caractéristiques pour alimenter le modèle XGBoost.

Features extraites (13 au total) :
  Temporelles  : mean, std, rms, skewness, kurtosis
  Fréquentielles: dominant_freq, lf_power, hf_power, lf_hf_ratio
  Morphologiques: rr_mean, rr_std, hr_estimate, peak_amplitude_mean, num_peaks
"""

import numpy as np
from scipy.signal import find_peaks
from scipy.fft import rfft, rfftfreq


class PPGFeatureExtractor:
    """
    Extrait un vecteur de features à partir d'une fenêtre de signal PPG.

    Args:
        sampling_rate (int): Fréquence d'échantillonnage du capteur en Hz.
                             Doit correspondre exactement au capteur réel,
                             sinon les calculs de fréquence seront faux.
    """

    # Noms des features dans l'ordre exact du vecteur renvoyé.
    # Utile pour la création d'un DataFrame pandas si besoin.
    FEATURE_NAMES = [
        "mean", "std", "rms", "skewness", "kurtosis",
        "dominant_freq", "lf_power", "hf_power", "lf_hf_ratio",
        "rr_mean", "rr_std", "hr_estimate", "peak_amplitude_mean", "num_peaks",
    ]

    def __init__(self, sampling_rate: int = 100):
        self.sampling_rate = sampling_rate

    # ── API publique ──────────────────────────────────────────────────────

    def extract(self, signal: list) -> np.ndarray:
        """
        Extrait toutes les features d'une fenêtre PPG.

        Args:
            signal: Liste ou tableau de valeurs float (ex: 30 secondes × 100 Hz = 3000 points).

        Returns:
            np.ndarray de shape (14,) contenant les features normalisées.

        Raises:
            ValueError: Si le signal est trop court pour une analyse fiable.
        """
        sig = np.asarray(signal, dtype=float)

        # Garde-fou : il faut au minimum 2 secondes de signal pour calculer
        # les fréquences LF/HF et détecter des pics cardiaques.
        if len(sig) < 2 * self.sampling_rate:
            raise ValueError(
                f"Signal trop court : {len(sig)} points reçus, "
                f"minimum {2 * self.sampling_rate} requis."
            )

        features = {}
        features.update(self._temporal_features(sig))
        features.update(self._frequency_features(sig))
        features.update(self._morphological_features(sig))

        # On construit le vecteur dans l'ordre défini par FEATURE_NAMES
        # pour garantir la cohérence avec le modèle entraîné.
        return np.array([features[name] for name in self.FEATURE_NAMES])

    # ── Features temporelles ──────────────────────────────────────────────

    def _temporal_features(self, sig: np.ndarray) -> dict:
        """
        Statistiques de base dans le domaine temporel.
        Ces features capturent l'amplitude et la distribution du signal.
        """
        return {
            "mean": float(np.mean(sig)),
            "std": float(np.std(sig)),
            # RMS (Root Mean Square) : énergie moyenne du signal
            "rms": float(np.sqrt(np.mean(np.square(sig)))),
            # Skewness : asymétrie de la distribution (0 = symétrique)
            "skewness": float(self._skewness(sig)),
            # Kurtosis excédentaire : aplatissement (0 = gaussienne)
            "kurtosis": float(self._kurtosis(sig)),
        }

    # ── Features fréquentielles ───────────────────────────────────────────

    def _frequency_features(self, sig: np.ndarray) -> dict:
        """
        Analyse fréquentielle via FFT.

        Les bandes LF (Low Frequency) et HF (High Frequency) sont les
        standards de la variabilité de la fréquence cardiaque (HRV) :
          - LF  0.04–0.15 Hz : activité sympathique + parasympathique
          - HF  0.15–0.40 Hz : activité parasympathique (respiration)
          - LF/HF ratio      : équilibre sympatho-vagal (marqueur de stress)
        """
        fft_vals = rfft(sig)                                # Transformée de Fourier réelle
        freqs = rfftfreq(len(sig), 1.0 / self.sampling_rate)  # Axe des fréquences en Hz

        # Fréquence dominante dans la plage cardiaque [0.5, 5] Hz
        # (évite les artefacts de basse fréquence < 0.5 Hz)
        cardiac_mask = (freqs >= 0.5) & (freqs <= 5.0)
        if np.any(cardiac_mask):
            dominant_freq = freqs[cardiac_mask][
                np.argmax(np.abs(fft_vals[cardiac_mask]))
            ]
        else:
            dominant_freq = 0.0

        # Puissance spectrale dans chaque bande HRV
        lf_mask = (freqs >= 0.04) & (freqs <= 0.15)
        hf_mask = (freqs >= 0.15) & (freqs <= 0.40)
        lf_power = float(np.sum(np.abs(fft_vals[lf_mask]) ** 2))
        hf_power = float(np.sum(np.abs(fft_vals[hf_mask]) ** 2))

        # Epsilon au dénominateur pour éviter la division par zéro
        lf_hf_ratio = lf_power / (hf_power + 1e-9)

        return {
            "dominant_freq": float(dominant_freq),
            "lf_power": lf_power,
            "hf_power": hf_power,
            "lf_hf_ratio": lf_hf_ratio,
        }

    # ── Features morphologiques ───────────────────────────────────────────

    def _morphological_features(self, sig: np.ndarray) -> dict:
        """
        Détection des pics systoliques (battements cardiaques).

        Les intervalles R-R (temps entre deux pics consécutifs) sont la
        base de l'analyse HRV. On en dérive la fréquence cardiaque estimée.

        distance=0.4s garantit un minimum de 40 BPM (physiologiquement réaliste).
        """
        min_distance_samples = int(0.4 * self.sampling_rate)  # 40 BPM minimum
        peaks, _ = find_peaks(sig, distance=min_distance_samples)

        if len(peaks) >= 2:
            # Intervalles R-R en millisecondes
            rr_intervals = np.diff(peaks) / self.sampling_rate * 1000.0
            return {
                "rr_mean": float(np.mean(rr_intervals)),
                "rr_std": float(np.std(rr_intervals)),
                # Formule : 60 000 ms / RR_mean_ms = BPM
                "hr_estimate": float(60_000.0 / (np.mean(rr_intervals) + 1e-9)),
                "peak_amplitude_mean": float(np.mean(sig[peaks])),
                "num_peaks": float(len(peaks)),
            }
        else:
            # Pas assez de pics → signal probablement bruité ou trop court
            return {
                "rr_mean": 0.0,
                "rr_std": 0.0,
                "hr_estimate": 0.0,
                "peak_amplitude_mean": 0.0,
                "num_peaks": float(len(peaks)),
            }

    # ── Helpers statistiques ──────────────────────────────────────────────

    @staticmethod
    def _skewness(x: np.ndarray) -> float:
        """Asymétrie (moment d'ordre 3 normalisé)."""
        mu = np.mean(x)
        sigma = np.std(x)
        return float(np.mean((x - mu) ** 3) / (sigma ** 3 + 1e-9))

    @staticmethod
    def _kurtosis(x: np.ndarray) -> float:
        """Kurtosis excédentaire (moment d'ordre 4, -3 pour centrer sur la gaussienne)."""
        mu = np.mean(x)
        sigma = np.std(x)
        return float(np.mean((x - mu) ** 4) / (sigma ** 4 + 1e-9) - 3.0)