import numpy as np
import xgboost as xgb
import joblib

# 14 features (correspondent à celles de feature_extractor)
X = np.random.randn(500, 14)
y = np.random.randint(0, 2, 500)

model = xgb.XGBClassifier(n_estimators=10, max_depth=2)
model.fit(X, y)

joblib.dump(model, 'models/ppg_model.joblib')
print("Modèle factice créé : models/ppg_model.joblib")