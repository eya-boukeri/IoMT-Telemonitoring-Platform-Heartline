# Test - Affichage des types d'alertes
## Guide rapide pour valider le badge de sévérité

---

## 🚀 Lancer le test

### Step 1: Redémarrer le dashboard (si nécessaire)

```bash
# Si en Docker:
docker-compose -f pfa-infrastructure/docker-compose.yml restart medical-dashboard-pfa

# Attendre ~5 secondes
```

### Step 2: Ouvrir le dashboard

Aller sur: **http://localhost:5173**

### Step 3: Sélectionner un patient

Cliquer sur "patient-001" dans la liste

---

## 📤 Publier des alertes de test

Ouvrir un terminal PowerShell et exécuter:

### Test 1: Alerte CRITICAL (Rouge)

```powershell
@"
{
  "alertId": "test-critical-001",
  "patientId": "patient-001",
  "alertType": "arrhythmia",
  "severity": "CRITICAL",
  "message": "CRITICAL: Irregular heartbeat detected!",
  "timestamp": "$(Get-Date -Format 'yyyy-MM-ddTHH:mm:ssZ')",
  "detectionScore": 0.95
}
"@ | docker exec -i kafka-pfa-v2 rpk topic produce medical-alerts
```

**Résultat attendu au dashboard:**
- Badge **CRITICAL** en rouge vif 🔴
- Message d'arythmie
- Apparaît immédiatement (< 1 sec)

---

### Test 2: Alerte WARNING (Orange)

```powershell
@"
{
  "alertId": "test-warning-001",
  "patientId": "patient-001",
  "alertType": "tachycardia",
  "severity": "WARNING",
  "message": "WARNING: Elevated heart rate detected",
  "timestamp": "$(Get-Date -Format 'yyyy-MM-ddTHH:mm:ssZ')",
  "detectionScore": 0.78
}
"@ | docker exec -i kafka-pfa-v2 rpk topic produce medical-alerts
```

**Résultat attendu au dashboard:**
- Badge **WARNING** en orange 🟠
- Message de tachycardie
- S'ajoute à la liste (max 8 alertes)

---

### Test 3: Alerte INFO (Bleu)

```powershell
@"
{
  "alertId": "test-info-001",
  "patientId": "patient-001",
  "alertType": "notification",
  "severity": "INFO",
  "message": "INFO: Patient data sync completed",
  "timestamp": "$(Get-Date -Format 'yyyy-MM-ddTHH:mm:ssZ')",
  "detectionScore": null
}
"@ | docker exec -i kafka-pfa-v2 rpk topic produce medical-alerts
```

**Résultat attendu au dashboard:**
- Badge **INFO** en bleu 🔵
- Message informatif
- Visible dans la liste

---

## 🎯 Points de vérification

Après chaque test, vérifier:

### ✅ Affichage du badge

- [ ] Badge visible et centré
- [ ] Texte UPPERCASE lisible
- [ ] Pas de débordement
- [ ] Aligné correctement avec l'alerte

### ✅ Couleurs exactes

| Sévérité | Couleur attendue | Vous voyez |
|----------|-----------------|-----------|
| CRITICAL | Rouge vif | 🔴 _____ |
| WARNING | Orange | 🟠 _____ |
| INFO | Bleu | 🔵 _____ |

### ✅ Positionnement

```
┌────────────────────────────────────────────┐
│ Heure  ●  [BADGE]  Type alerte             │
│             ↑ Doit être ici                │
│               Message détail               │
│               Patient: ...                 │
└────────────────────────────────────────────┘
```

### ✅ Responsive

- [ ] Sur desktop (1920px): badges bien espacés
- [ ] Sur tablette (768px): badges adaptatifs
- [ ] Sur mobile (320px): pas de débordement

---

## 🔍 Troubleshooting

### ❌ Badge ne s'affiche pas

**Solution:**
1. Vérifier que le badge est dans le HTML:
   - Ouvrir DevTools (F12)
   - Chercher: `.severity-badge`
   - Doit voir l'élément dans l'arborescence

2. Vérifier le CSS:
   - Dans DevTools → Styles
   - `.severity-badge` doit avoir `display: flex`

3. Vérifier la classe CSS:
   - Pour CRITICAL: `.severity-critical`
   - Pour WARNING: `.severity-warning`
   - Pour INFO: `.severity-info`

### ❌ Badge s'affiche mais pas de couleur

**Solution:**
1. Vérifier que les variables CSS sont définies:
   ```css
   --severity-critical: #ff4444;
   --severity-warning: #ffaa33;
   --severity-info: #4488ff;
   ```

2. Vider le cache du navigateur: `Ctrl+Shift+Delete`

3. Redémarrer le dashboard

### ❌ Alerte n'apparaît pas du tout

**Solution:**
1. Vérifier que notification-service reçoit:
   ```bash
   docker logs notification-service-pfa | grep "Alert received"
   ```

2. Vérifier que le topic est correct:
   ```bash
   docker exec kafka-pfa-v2 rpk topic list | grep medical-alerts
   ```

3. Vérifier la connexion SSE:
   - DevTools → Network
   - Chercher `/api/notifications/stream/patient-001`
   - Status doit être 200 (EventStream)

---

## 📊 Batch test (10 alertes)

Pour tester la performance:

```powershell
$severities = @("CRITICAL", "WARNING", "INFO", "WARNING", "CRITICAL", "INFO", "WARNING", "CRITICAL", "WARNING", "INFO")
$alerts = @("arrhythmia", "tachycardia", "notification", "bradycardia", "ectopic", "normal", "hypoxia", "syncope", "flutter", "pause")

for ($i = 0; $i -lt 10; $i++) {
  $alert = @{
    alertId = "batch-$i"
    patientId = "patient-001"
    alertType = $alerts[$i]
    severity = $severities[$i]
    message = "$($severities[$i]): $($alerts[$i])"
    timestamp = (Get-Date -Format 'yyyy-MM-ddTHH:mm:ssZ')
    detectionScore = Get-Random -Minimum 60 -Maximum 100 | ForEach-Object { $_ / 100 }
  } | ConvertTo-Json
  
  $alert | docker exec -i kafka-pfa-v2 rpk topic produce medical-alerts
  Start-Sleep -Milliseconds 500
}
```

**Résultat attendu:**
- Toutes les 10 alertes reçues
- Couleurs correctes pour chaque type
- Pas de crash ou lag
- Max 8 affichées (les 8 plus récentes)

---

## ✨ Test visuel final

### 📸 Screenshot à prendre:

1. Vue avec alertes CRITICAL (rouge)
2. Vue avec alertes WARNING (orange)
3. Vue avec mix de sévérités
4. Vue sur mobile (si possible)

### Partager:
- Montrer les badges colorés
- Démontrer la réactivité (< 1s)
- Montrer que c'est readable et accessible

---

## 🎓 Comprendre le code

### Flux de rendu:

```javascript
// 1. Réception de l'alerte JSON
const rawAlert = {
  severity: "CRITICAL",
  ...
}

// 2. Normalisation
const severity = String(rawAlert.severity || 'WARNING').toUpperCase();
// severity = "CRITICAL"

// 3. Calcul de la classe CSS
const severityLabel = alert.severity || 'INFO';
// severityLabel = "CRITICAL"
const severityBadgeClass = `severity-badge severity-${severityLabel.toLowerCase()}`;
// severityBadgeClass = "severity-badge severity-critical"

// 4. Rendu JSX
<div className={severityBadgeClass}>{severityLabel}</div>
// ↓
// <div class="severity-badge severity-critical">CRITICAL</div>

// 5. CSS appliqué
.severity-critical {
  background: rgba(255, 68, 68, 0.2);
  color: #ff6b6b;
  ...
}
```

---

## 🏁 Résumé du test

✅ **Succès** si:
- Badge visible pour toutes les alertes
- Couleurs correctes (CRITICAL=rouge, WARNING=orange, INFO=bleu)
- Pas de lag ou erreur console
- Responsive sur tous les écrans
- Affiche la sévérité en UPPERCASE

❌ **Échoue** si:
- Badge invisible
- Couleurs incorrectes
- Console errors
- Responsive cassé
- Texte mal visible

---

**Bon test! 🎉**
