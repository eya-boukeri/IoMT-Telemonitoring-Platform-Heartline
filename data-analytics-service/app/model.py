"""
model.py — Chargement du modèle XGBoost et inférence.

Ce module encapsule toute la logique ML dans une seule classe PPGModel.
Le reste du code n'a pas à savoir que c'est XGBoost — ça facilite
le remplacement futur par un autre modèle (PyTorch, sklearn, etc.).
"""

import logging
from pathlib import Path

import joblib
import numpy as np

logger = logging.getLogger(__name__)


class PPGModel:
    """
    Wrapper autour du modèle XGBoost sérialisé.

    Le modèle est chargé une seule fois au démarrage du service,
    puis réutilisé pour chaque inférence (économie mémoire/CPU).

    Args:
        model_path (str): Chemin vers le fichier .joblib du modèle.
    """

    def __init__(self, model_path: str):
        path = Path(model_path)
        if not path.exists():
            raise FileNotFoundError(
                f"Modèle introuvable : {model_path}\n"
                "Vérifiez que le fichier .joblib est bien monté dans le conteneur."
            )

        self.model = joblib.load(path)
        # Version déduite du nom de fichier (ex: "ppg_model_v2" → version = "ppg_model_v2")
        # Incluse dans chaque alerte pour la traçabilité des prédictions.
        self.version = path.stem
        logger.info("Modèle chargé : %s", self.version)

    def predict(self, features: np.ndarray) -> tuple[int, float]:
        """
        Prédit la classe et la confiance à partir d'un vecteur de features.

        Args:
            features: np.ndarray 1D de shape (n_features,).

        Returns:
            Tuple (pred_class, confidence) où :
              - pred_class  : int  → 0 = normal, 1 = anomalie
              - confidence  : float → probabilité max ∈ [0.0, 1.0]
        """
        # Le modèle attend une matrice 2D (1 ligne = 1 exemple)
        x = features.reshape(1, -1)

        pred_class = int(self.model.predict(x)[0])

        # predict_proba renvoie [[p_classe_0, p_classe_1, ...]]
        # On prend la probabilité de la classe prédite (la plus élevée)
        proba_vector = self.model.predict_proba(x)[0]
        confidence = float(proba_vector.max())

        logger.debug("Prédiction : classe=%d, confiance=%.3f", pred_class, confidence)
        return pred_class, confidence

    def __repr__(self) -> str:
        return f"PPGModel(version={self.version!r})"