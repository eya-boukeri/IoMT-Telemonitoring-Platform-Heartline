# Nouvelle Fonctionnalité : Visualisation Patient Normal

## 📊 Fonction ajoutée au Dashboard

Une nouvelle section "Patient Normal - Courbes de Référence" a été ajoutée au dashboard médical pour visualiser les signes vitaux d'un patient en bonne santé.

## 🎯 Utilisation

1. **Accès à la fonctionnalité** :
   - Cliquez sur le bouton 📊 dans la barre de navigation latérale
   - Le bouton devient actif (fond coloré) quand la visualisation est affichée

2. **Contenu de la visualisation** :
   - **Signal PPG** : Graphique du signal photopléthysmographique avec une fréquence de 100 Hz
   - **Rythme Cardiaque** : Graphique du rythme cardiaque en bpm (60-85 bpm normal)

3. **Caractéristiques** :
   - Données générées algorithmiquement pour simuler un patient normal
   - Rythme cardiaque moyen : 72 bpm
   - Signal PPG réaliste avec harmoniques et bruit léger
   - Lignes de référence pour les valeurs moyennes

## 🔧 Fonctionnalités

- **Régénération** : Bouton "Régénérer les données" pour créer de nouvelles courbes
- **Comparaison** : Utilisez ces courbes comme référence pour comparer avec les patients surveillés
- **Temps réel** : Visualisation statique mais peut être étendue pour du streaming

## 📈 Données techniques

- **Durée** : 60 secondes de données
- **Échantillonnage PPG** : 100 Hz (6000 points)
- **Échantillonnage Rythme Cardiaque** : 1 Hz (60 points)
- **Format** : Compatible avec les données MQTT du système

Cette fonctionnalité aide les médecins et le personnel soignant à :
- Comprendre l'apparence normale des signaux PPG
- Comparer rapidement les données des patients avec une référence saine
- Éduquer sur les caractéristiques des signes vitaux normaux