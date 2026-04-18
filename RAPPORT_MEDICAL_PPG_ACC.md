# Rapport Medical - Donnees PPG et ACC

Date: 2026-04-04
Projet: platformeIOT
Objet: Synthese complete des informations medicales liees aux signaux PPG et ACC pour documentation et rapport.

## 1. Resume Executif

Le systeme exploite deux sources capteurs principales:
- PPG (Photoplethysmographie): mesure optique des variations de volume sanguin peripherique.
- ACC (Accelerometrie 3 axes): mesure des mouvements corporels (x, y, z), utile pour l activite physique et la detection d artefacts.

Ces donnees permettent d estimer:
- Frequence cardiaque (HR, bpm)
- Variabilite de frequence cardiaque (HRV, variabilite des intervalles)
- Qualite du signal
- Niveau de mouvement et artefacts de motion
- Indices derives dans le module ingestion (respiration estimee, stress index, perfusion, indice vasculaire)

## 2. Donnees Captures

### 2.1 Donnees PPG

Le projet supporte deux formats de PPG:
- Format enrichi legacy: echantillons avec canaux green et red
- Format Android dynamique: timestamp -> valeur PPG

Variables PPG exploitees:
- ppgGreenMin, ppgGreenMax, ppgGreenAverage
- ppgRedMin, ppgRedMax, ppgRedAverage
- ppgDataPoints
- ppgMean, ppgMin, ppgMax, ppgStdDev, ppgVariance (niveau agregation ingestion)

### 2.2 Donnees ACC

Le projet supporte:
- accelerometerPoint: x, y, z
- format timestamp -> {x, y, z}

Variables ACC exploitees:
- accelerometerMagnitudeAverage
- accelerometerMagnitudeMax
- accelerometerVariance
- accelerometerDataPoints
- activityMean, activityMax, activityVariance (niveau agregation ingestion)

## 3. Signification Medicale des Variables

### 3.1 PPG et biomarqueurs cardio-vasculaires

- ppgGreen/ppgRed: amplitude optique brute des canaux, sensible a perfusion, position capteur, pression de contact, mouvement.
- ppgMean: niveau moyen du signal.
- ppgStdDev / ppgVariance: variabilite du signal, indicateur de stabilite hemodynamique et de bruit.
- ppgMin / ppgMax: plage du signal; utile pour detecter signal faible ou saturation.

Applications medicales indirectes:
- Estimation HR
- Qualite de perfusion (avec prudence)
- Detection d anomalies de signal/artefacts

### 3.2 ACC et contexte physiologique

- Magnitude ACC = sqrt(x^2 + y^2 + z^2)
- accelerometerMagnitudeAverage: niveau moyen d activite
- accelerometerMagnitudeMax: pics de mouvement
- accelerometerVariance: variabilite motrice

Applications medicales indirectes:
- Distinguer repos vs mouvement
- Evaluer la fiabilite de HR/HRV pendant l activite
- Nettoyer le signal PPG en presence d artefacts de motion

## 4. Calculs et Methodes Implementes

### 4.1 Traitement PPG (vitals-management)

Le module vitals-management:
- Extrait les valeurs PPG (green/red ou timestamp -> valeur)
- Calcule min, max, moyenne par canal
- Estime HR via detection de pics a seuil adaptatif
- Calcule variabilite cardiaque, bornes min/max de HR

Seuil adaptatif utilise:
- threshold = mean + 0.5 * stdDev

Qualite signal PPG:
- Penalite si nombre de points PPG insuffisant
- Penalite si plage PPG trop faible
- Penalite si HR hors bornes physiologiques de plausibilite

### 4.2 Traitement ACC (vitals-management)

Le module calcule:
- Magnitude par echantillon
- Moyenne, maximum, variance de la magnitude
- Nombre de points accelerometre

Role medical:
- Contexte d effort
- Aide a interpreter la fiabilite des parametres cardiaques

### 4.3 Nettoyage PPG guide par ACC (ingestion-service)

Le module ingestion-service:
- Aligne PPG et ACC
- Evalue la motion (magnitude)
- Supprime ou corrige des segments fortement corrompus
- Applique filtrage et suppression d outliers

Impact medical:
- Reduction des faux positifs d anomalies PPG
- Meilleure robustesse des mesures derivees

## 5. Metriques Cliniques Derivees dans le Projet

Le pipeline d aggregation calcule en plus:
- estimatedHeartRate
- heartRateConfidence
- respiratoryRate (estimee)
- rrMeanMs et rrIntervalsMs
- hrvSdnn, hrvRmssd, hrvLfHf
- perfusionIndex
- vascularIndex
- stressIndex et stressLevel
- signalQualityScore et signalQualityLabel

Interpretation generale:
- HR: charge cardiaque instantanee
- HRV (SDNN, RMSSD): regulation autonome cardiaque
- LF/HF: indicateur d equilibre sympathique/parasympathique (a interpreter avec prudence)
- stressIndex: indicateur compose dependant de HR, HRV, activite, qualite signal

## 6. Regles de Qualite et Seuils de Plausibilite

Dans la logique de scoring signal:
- Score initial a 100
- Penalites appliquees si:
  - peu de points PPG
  - amplitude PPG faible
  - HR < 40 bpm ou HR > 200 bpm
  - variabilite excessive

Classification qualite:
- excellent
- good
- fair
- poor

Remarque:
Ces classes mesurent avant tout la qualite technique du signal, pas un diagnostic clinique direct.

## 7. Stockage et Tracabilite des Donnees

### 7.1 InfluxDB

Les mesures vitales enrichies sont stockees dans la mesure vitals, incluant champs PPG et ACC.
Le module ingestion pousse aussi ingestion_metrics avec statistiques PPG/activite et indicateurs derives.

### 7.2 PostgreSQL

La table ingestion_metrics conserve:
- ppg_mean, ppg_min, ppg_max, ppg_std_dev, ppg_variance
- activity_mean, activity_max, activity_variance
- indicateurs cardiorespiratoires et de stress

## 8. Usage Medical Recommande dans le Rapport

### 8.1 Ce que ces donnees permettent

- Surveillance continue non invasive
- Detection precoce de degradation de qualite physiologique potentielle
- Correlation etat cardiaque / mouvement
- Filtrage des artefacts pour fiabiliser l interpretation

### 8.2 Ce que ces donnees ne permettent pas seules

- Diagnostic medical definitif
- Remplacement d ECG clinique ou de monitorage hospitalier de reference
- Interpretation sans contexte patient (age, traitements, comorbidites, condition d acquisition)

## 9. Limites et Precautions d Interpretation

Principales limites:
- Artefacts de mouvement, pression du capteur, perfusion peripherique faible
- Sensibilite au contexte (effort, stress, temperature, posture)
- HRV fiable seulement avec signal propre et fenetre suffisante

Precautions:
- Toujours interpreter avec signalQuality
- Utiliser ACC pour qualifier la fiabilite des mesures cardiaques
- Croiser avec signes cliniques et autres capteurs si disponibles

## 10. Glossaire Rapide

- PPG: Photoplethysmographie
- ACC: Accelerometrie
- HR: Heart Rate (bpm)
- HRV: Heart Rate Variability
- SDNN: ecart-type des intervalles RR
- RMSSD: racine de la moyenne des differences successives RR
- LF/HF: ratio puissance basse/haute frequence

## 11. Conclusion

L architecture actuelle fournit une chaine complete orientee medical:
- Acquisition PPG + ACC
- Nettoyage guide par motion
- Extraction de biomarqueurs cardiaques et d activite
- Scoring de qualite
- Historisation et diffusion temps reel

Pour un rapport, il est pertinent de presenter PPG et ACC comme un couple complementaire:
- PPG pour les marqueurs hemodynamiques/cardiaques
- ACC pour le contexte biomecanique et la robustesse des infer ences

En pratique, la valeur medicale depend fortement de la qualite du signal et du contexte clinique.
