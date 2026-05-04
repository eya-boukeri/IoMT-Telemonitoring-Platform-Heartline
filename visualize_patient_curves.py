#!/usr/bin/env python3
"""
Script de visualisation des courbes de signes vitaux d'un patient normal
Visualise les données PPG (Photopléthysmographie) et rythme cardiaque
"""

import matplotlib.pyplot as plt
import numpy as np
import json
from datetime import datetime, timedelta

def generate_normal_ppg_data(duration_seconds=60, sampling_rate=100):
    """
    Génère des données PPG normales pour un patient
    """
    # Nombre total d'échantillons
    n_samples = duration_seconds * sampling_rate

    # Temps en secondes
    time = np.linspace(0, duration_seconds, n_samples)

    # Fréquence cardiaque normale (60-80 bpm)
    heart_rate = 72  # bpm
    heart_rate_freq = heart_rate / 60  # Hz

    # Générer un signal PPG réaliste
    # Signal principal avec la fréquence cardiaque
    ppg_signal = np.sin(2 * np.pi * heart_rate_freq * time)

    # Ajouter des harmoniques pour plus de réalisme
    ppg_signal += 0.3 * np.sin(2 * np.pi * 2 * heart_rate_freq * time)  # 2ème harmonique
    ppg_signal += 0.1 * np.sin(2 * np.pi * 3 * heart_rate_freq * time)  # 3ème harmonique

    # Ajouter du bruit réaliste
    noise = np.random.normal(0, 0.05, n_samples)
    ppg_signal += noise

    # Normaliser entre 0 et 1
    ppg_signal = (ppg_signal - np.min(ppg_signal)) / (np.max(ppg_signal) - np.min(ppg_signal))

    # Échelonner vers une plage réaliste (ex: 100-150 unités arbitraires)
    ppg_signal = 100 + ppg_signal * 50

    return time, ppg_signal

def generate_normal_heart_rate(duration_seconds=60, sampling_rate=1):
    """
    Génère des données de rythme cardiaque normales
    """
    n_samples = duration_seconds * sampling_rate
    time = np.linspace(0, duration_seconds, n_samples)

    # Rythme cardiaque de base
    base_hr = 72

    # Ajouter de légères variations naturelles
    variation = np.sin(2 * np.pi * 0.01 * time) * 2  # Variation lente
    noise = np.random.normal(0, 1, n_samples)

    heart_rate = base_hr + variation + noise

    # S'assurer que c'est dans une plage normale
    heart_rate = np.clip(heart_rate, 60, 85)

    return time, heart_rate

def plot_patient_curves():
    """
    Crée et affiche les courbes d'un patient normal
    """
    # Générer les données
    ppg_time, ppg_data = generate_normal_ppg_data(duration_seconds=60)
    hr_time, hr_data = generate_normal_heart_rate(duration_seconds=60)

    # Créer la figure avec deux sous-graphiques
    fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(12, 8))
    fig.suptitle('Courbes de Signes Vitaux - Patient Normal', fontsize=16, fontweight='bold')

    # Premier graphique : PPG
    ax1.plot(ppg_time, ppg_data, 'b-', linewidth=1.5, alpha=0.8)
    ax1.set_title('Signal PPG (Photopléthysmographie)', fontsize=14)
    ax1.set_xlabel('Temps (secondes)')
    ax1.set_ylabel('Amplitude PPG')
    ax1.grid(True, alpha=0.3)
    ax1.set_xlim(0, 60)

    # Ajouter des annotations pour le rythme cardiaque
    ax1.text(5, np.max(ppg_data) * 0.9, f'Rythme cardiaque: ~72 bpm',
             bbox=dict(boxstyle="round,pad=0.3", facecolor="lightblue", alpha=0.8))

    # Deuxième graphique : Rythme cardiaque
    ax2.plot(hr_time, hr_data, 'r-', linewidth=2, marker='o', markersize=3, alpha=0.7)
    ax2.set_title('Rythme Cardiaque', fontsize=14)
    ax2.set_xlabel('Temps (secondes)')
    ax2.set_ylabel('Rythme Cardiaque (bpm)')
    ax2.grid(True, alpha=0.3)
    ax2.set_xlim(0, 60)
    ax2.set_ylim(55, 90)

    # Ajouter une ligne pour la moyenne
    mean_hr = np.mean(hr_data)
    ax2.axhline(y=mean_hr, color='orange', linestyle='--', alpha=0.7,
                label='.1f')
    ax2.legend()

    # Ajuster l'espacement
    plt.tight_layout()

    # Sauvegarder le graphique
    plt.savefig('patient_normal_curves.png', dpi=300, bbox_inches='tight')
    print("Graphique sauvegardé dans 'patient_normal_curves.png'")

    # Ne pas afficher en mode headless
    # plt.show()

def save_sample_data():
    """
    Sauvegarde un échantillon de données au format JSON
    """
    # Générer 10 secondes de données
    ppg_time, ppg_data = generate_normal_ppg_data(duration_seconds=10)
    hr_time, hr_data = generate_normal_heart_rate(duration_seconds=10)

    # Créer un objet de données similaire au format MQTT
    sample_data = {
        "patientId": "patient-normal-demo",
        "deviceId": "simulator",
        "timestamp": int(datetime.now().timestamp() * 1000),
        "ppgData": ppg_data.tolist()[:100],  # 100 premiers échantillons
        "heartRate": hr_data.tolist(),
        "accelerometerData": [
            {"x": 0.12, "y": 9.81, "z": 0.05} for _ in range(10)
        ]
    }

    # Sauvegarder en JSON
    with open('sample_patient_data.json', 'w') as f:
        json.dump(sample_data, f, indent=2)

    print("Échantillon de données sauvegardé dans 'sample_patient_data.json'")
    print(f"Données PPG: {len(sample_data['ppgData'])} échantillons")
    print(f"Données Heart Rate: {len(sample_data['heartRate'])} échantillons")

if __name__ == "__main__":
    print("Génération des courbes d'un patient normal...")

    # Sauvegarder un échantillon de données
    save_sample_data()

    # Créer et afficher les graphiques
    plot_patient_curves()

    print("\nVisualisation terminée!")
    print("- Graphique sauvegardé: patient_normal_curves.png")
    print("- Données d'exemple: sample_patient_data.json")