# 📊 MONITORING COMPLET - PLATEFORME PFA

## ✅ Fichiers créés pour vous

Tous ces fichiers sont dans `pfa-infrastructure/`:

### 1. **DEMARRER_MONITORING.ps1**
   - Affiche les 7 commandes PowerShell à copier-coller
   - Lance ce fichier d'abord pour voir les instructions
   - ```powershell
     .\DEMARRER_MONITORING.ps1
     ```

### 2. **COMMANDES_RAPIDES.md**
   - Recueil de toutes les commandes
   - Permet de copier-coller directement dans chaque terminal
   - Inclut aussi les payloads JSON pour les tests

### 3. **GUIDE_TESTS_COMPLETS.md**
   - Guide détaillé avec timeline
   - Explications pour chaque scénario
   - Dépannage si quelque chose ne fonctionne pas

### 4. **test-scenarios.ps1**
   - Script automatisé qui publie les données
   - Fait le Scénario 1 (patient normal) et Scénario 2 (patient critique)
   - Lance avec: ```powershell
     .\test-scenarios.ps1
     ```

### 5. **quick-diagnostic.ps1**
   - Diagnostic rapide de l'état de la plateforme
   - Vérifie tous les containers Docker

---

## 🚀 DÉMARRAGE RAPIDE (3 étapes)

### ✅ Étape 1: Afficher les commandes
```powershell
cd pfa-infrastructure
.\DEMARRER_MONITORING.ps1
```

### ✅ Étape 2: Ouvrir 7 terminaux PowerShell
- Ctrl+` pour ouvrir le premier
- Cliquer le bouton "+" pour ajouter les autres (ou Ctrl+Shift+`)
- Total: 7 terminaux dans VS Code

### ✅ Étape 3: Copier-coller les 7 commandes
Chaque commande dans son terminal correspondant (voir **COMMANDES_RAPIDES.md**)

### ✅ Étape 4 (Automatique): Lancer les tests
```powershell
# Dans un 8ème terminal, après les 30 secondes d'attente
.\test-scenarios.ps1
```

---

## 📊 Structure des 7 Terminaux

```
T1: MQTT Messages    │  T2: Ingestion Logs  │  T3: Kafka Raw
   (bruts)           │   (parsing JSON)     │   (données)
─────────────────────┼──────────────────────┼────────────────
T4: Kafka Alerts     │  T5: Data Analytics  │  T6: Notifs
   (CRITICAL, etc)   │   (anomalies)        │   (emails)
─────────────────────┴──────────────────────┴────────────────
                T7: InfluxDB Latest Data
                (Dernières données stockées)
```

---

## 🟢 Scénario 1 - Patient Normal

**Données:**
- FC: 72 → 75 → 70 bpm (normal)
- SpO2: 99 → 98 → 99 % (normal)
- Temp: 36.6 → 36.5 → 36.7°C (normal)

**Résultat attendu:**
- ✅ T1-T3: Données visibles
- ❌ T4: Aucune alerte
- ❌ T5-T6: Silence (pas d'anomalie)

---

## 🔴 Scénario 2 - Patient Critique

**Données:**
- FC: 80 → 55 → **155** → 152 bpm (tachycardie)
- SpO2: 97 → 94 → **82** → 80 % (hypoxie)
- Temp: 36.8 → 37 → **39.2** → 39.5°C (fièvre)

**Résultat attendu:**
- ✅ T1-T3: Données visibles
- 🔴 **T4: Alerte JSON CRITICAL (10-15 secondes après)**
- 🔴 **T5: "Anomaly detected"**
- 🔴 **T6: "Email sent to farah.attia21@gmail.com"**
- ✅ T7: Données InfluxDB avec valeurs critiques
- 📧 Email reçu dans Gmail avec sujet `[CRITICAL]`

---

## 📧 Vérification Email

1. Ouvrir: https://mail.google.com
2. Compte: farah.attia21@gmail.com
3. Chercher email avec sujet: `[CRITICAL] Alerte: ANOMALY` ou `[CRITICAL] Alerte Médicale`
4. Vérifier que contient: patient-urgent, FC, SpO2, température

---

## 🎨 Vérification Dashboard

1. Ouvrir: http://localhost:5173
2. Login: dr-farah / farah1234
3. Sélectionner: patient-urgent
4. Voir:
   - ✅ Graphique FC avec pic à 155 bpm
   - ✅ Graphique SpO2 avec creux à 82%
   - ✅ Alerte CRITICAL en rouge
   - ✅ Timestamp correct

---

## 🔍 Dépannage Rapide

**Si pas de messages MQTT (T1)?**
```powershell
docker logs mosquitto-pfa-v2 --tail 20
docker restart mosquitto-pfa-v2
```

**Si pas d'alerte (T4)?**
- Vérifier que T5 montre "Anomaly detected"
- Attendre 15-20 secondes (transmission peut être lente)
- Vérifier logs: `docker logs data-analytics-service-pfa --tail 50`

**Si pas d'email (T6)?**
```powershell
docker logs notification-service-pfa --tail 100
# Chercher erreurs de SMTP/Brevo
```

**Si pas de données InfluxDB (T7)?**
```powershell
# Vérifier InfluxDB est actif
curl http://localhost:8086/health
```

---

## 💾 Fichiers importants

```
pfa-infrastructure/
├── DEMARRER_MONITORING.ps1  ← Lance d'abord
├── COMMANDES_RAPIDES.md     ← Copier-coller les 7 commandes
├── GUIDE_TESTS_COMPLETS.md  ← Guide détaillé + dépannage
├── test-scenarios.ps1        ← Exécute les 2 scénarios
├── quick-diagnostic.ps1      ← Vérifie l'état
└── docker-compose.yml        ← Stack Docker
```

---

## ⏱️ Timeline complète pour Scénario 2

```
T0s    : Publier première donnée
T1-2s  : MQTT reçoit (T1) ✓
T2-5s  : Ingestion traite (T2) ✓
T5-8s  : Apparition dans Kafka vitals-raw (T3) ✓
T8-10s : Publier les 2 autres mesures critiques
T12-15s: Data Analytics détecte anomalie (T5) 🔴
T13-18s: Alerte CRITICAL dans Kafka medical-alerts (T4) 🔴
T15-25s: Email envoyé (T6) 🔴
T20s+  : Données dans InfluxDB (T7) ✓
```

---

## ✅ Checklist Finale

Avant la démo/soutenance:

- ☐ Docker Compose actif (`docker ps` montre 8+ containers)
- ☐ 7 terminaux ouverts et affichent les logs
- ☐ Scénario 1 exécuté: aucune alerte ✅
- ☐ Scénario 2 exécuté: alerte CRITICAL + email ✅
- ☐ Dashboard affiche les données
- ☐ Email reçu dans Gmail

---

## 🎯 Objectif Atteint

Vous avez maintenant une **plateforme complète opérationnelle** avec:

✅ **Ingestion MQTT** → Kafka → InfluxDB  
✅ **Analyse d'anomalies** en temps réel  
✅ **Alertes critiques** automatiques  
✅ **Notifications email** aux médecins  
✅ **Dashboard** avec visualisation  

**Prêt pour la soutenance! 🚀**

---

**Créé: 22 Avril 2026**  
**Projet: PFA - Plateforme de Télésurveillance Médicale**  
**Version: 1.0 - Tests Complets**
