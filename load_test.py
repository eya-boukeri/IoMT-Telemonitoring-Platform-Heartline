#!/usr/bin/env python3
"""
Test de montée en charge pour la plateforme IoT médicale
Teste la latence de traitement avec différents nombres de patients simultanés
"""

import subprocess
import threading
import time
import json
import requests
import statistics
from datetime import datetime
import sys
import os
from concurrent.futures import ThreadPoolExecutor, as_completed

class LoadTest:
    def __init__(self, base_url="http://localhost:8081"):
        self.base_url = base_url
        self.results = {}

    def simulate_patient(self, patient_id, duration=30):
        """Simule un patient en envoyant des données via MQTT"""
        try:
            # Utilise le script PowerShell existant
            cmd = [
                "powershell.exe",
                "-ExecutionPolicy", "Bypass",
                "-File", "simulate-patient.ps1",
                "-PatientId", patient_id,
                "-DurationSeconds", str(duration),
                "-IntervalMs", "500"  # Plus rapide pour les tests
            ]

            result = subprocess.run(
                cmd,
                cwd=r"C:\Users\Admin\Desktop\platformeIOT\pfa-infrastructure",
                capture_output=True,
                text=True,
                timeout=duration + 10
            )

            return {
                "patient_id": patient_id,
                "success": result.returncode == 0,
                "duration": duration,
                "output": result.stdout[-500:] if result.stdout else "",  # Derniers 500 chars
                "error": result.stderr[-500:] if result.stderr else ""
            }

        except subprocess.TimeoutExpired:
            return {
                "patient_id": patient_id,
                "success": False,
                "duration": duration,
                "error": "Timeout"
            }
        except Exception as e:
            return {
                "patient_id": patient_id,
                "success": False,
                "duration": duration,
                "error": str(e)
            }

    def get_anomaly_snapshots_count(self):
        """Récupère le nombre d'anomalies créées pendant le test"""
        try:
            # Via l'API REST si disponible, sinon via la DB
            response = requests.get(f"{self.base_url}/api/anomalies/count", timeout=5)
            if response.status_code == 200:
                return response.json().get("count", 0)
        except:
            pass

        # Fallback: requête directe à la DB
        try:
            result = subprocess.run([
                "docker", "exec", "postgres-pfa",
                "psql", "-U", "postgres", "-d", "ingestion_db", "-t", "-c",
                "SELECT COUNT(*) FROM anomaly_snapshots WHERE created_at > NOW() - INTERVAL '5 minutes';"
            ], capture_output=True, text=True, timeout=10)

            if result.returncode == 0:
                count = result.stdout.strip()
                return int(count) if count.isdigit() else 0
        except:
            pass

        return 0

    def get_system_metrics(self):
        """Récupère les métriques système (CPU, mémoire)"""
        try:
            # Métriques Docker
            result = subprocess.run([
                "docker", "stats", "--no-stream", "--format", "json"
            ], capture_output=True, text=True, timeout=10)

            metrics = {}
            if result.returncode == 0:
                lines = result.stdout.strip().split('\n')
                for line in lines:
                    if line.strip():
                        try:
                            data = json.loads(line)
                            container_name = data.get('Name', '').replace('pfa-infrastructure-', '')
                            metrics[container_name] = {
                                'cpu': data.get('CPUPerc', '0%'),
                                'memory': data.get('MemUsage', '0B'),
                                'net_io': data.get('NetIO', '0B'),
                                'block_io': data.get('BlockIO', '0B')
                            }
                        except:
                            continue

            return metrics
        except:
            return {}

    def run_load_test(self, patient_counts, test_duration=30):
        """Exécute les tests de montée en charge"""
        print("🚀 Démarrage des tests de montée en charge...")
        print(f"📊 Test de latence et montée en charge")
        print(f"⏱️  Durée par test: {test_duration}s")
        print(f"👥 Nombre de patients testés: {patient_counts}")
        print("=" * 60)

        for num_patients in patient_counts:
            print(f"\n🔥 Test avec {num_patients} patients simultanés")
            print("-" * 40)

            start_time = time.time()
            initial_snapshots = self.get_anomaly_snapshots_count()

            # Lance les simulations de patients en parallèle
            with ThreadPoolExecutor(max_workers=num_patients) as executor:
                futures = []
                for i in range(num_patients):
                    patient_id = "04d"
                    future = executor.submit(self.simulate_patient, patient_id, test_duration)
                    futures.append(future)

                # Attend que toutes les simulations se terminent
                results = []
                for future in as_completed(futures):
                    result = future.result()
                    results.append(result)
                    status = "✅" if result["success"] else "❌"
                    print(f"  {status} Patient {result['patient_id']}: {'OK' if result['success'] else 'FAILED'}")

            end_time = time.time()
            total_time = end_time - start_time

            # Collecte les métriques finales
            final_snapshots = self.get_anomaly_snapshots_count()
            new_snapshots = final_snapshots - initial_snapshots
            system_metrics = self.get_system_metrics()

            # Calcule les statistiques
            successful_patients = sum(1 for r in results if r["success"])
            success_rate = (successful_patients / num_patients) * 100

            self.results[num_patients] = {
                "total_time": total_time,
                "success_rate": success_rate,
                "successful_patients": successful_patients,
                "new_snapshots": new_snapshots,
                "system_metrics": system_metrics,
                "avg_response_time": total_time / num_patients if num_patients > 0 else 0
            }

            print(".2f"            print(".1f"            print(f"📸 Nouvelles anomalies détectées: {new_snapshots}")
            print(".2f"
    def generate_report(self):
        """Génère un rapport des résultats"""
        print("\n" + "="*80)
        print("📊 RAPPORT DE PERFORMANCE - MONTÉE EN CHARGE")
        print("="*80)

        print("<10"        print("-"*80)

        for num_patients, data in self.results.items():
            print("<10"
                  "<8.1f"
                  "<12.2f"
                  "<10")

        print("-"*80)

        # Analyse des résultats
        print("\n🔍 ANALYSE DES PERFORMANCES:")
        print("-" * 40)

        patient_counts = list(self.results.keys())
        success_rates = [data["success_rate"] for data in self.results.values()]
        response_times = [data["avg_response_time"] for data in self.results.values()]

        if success_rates:
            avg_success = statistics.mean(success_rates)
            print(".1f"
            if len(success_rates) > 1:
                success_trend = "stable" if abs(success_rates[-1] - success_rates[0]) < 5 else ("dégradé" if success_rates[-1] < success_rates[0] else "amélioré")
                print(f"  • Tendance taux de succès: {success_trend}")

        if response_times:
            avg_response = statistics.mean(response_times)
            print(".2f"
            if len(response_times) > 1:
                response_trend = "stable" if abs(response_times[-1] - response_times[0]) < 1 else ("dégradé" if response_times[-1] > response_times[0] else "amélioré")
                print(f"  • Tendance temps de réponse: {response_trend}")

        # Recommandations
        print("\n💡 RECOMMANDATIONS:")
        print("-" * 20)

        max_successful_patients = max((k for k, v in self.results.items() if v["success_rate"] > 95), default=0)
        if max_successful_patients > 0:
            print(f"  • Capacité maximale testée: {max_successful_patients} patients simultanés (>95% succès)")
        else:
            print("  • Tests insuffisants pour déterminer la capacité maximale")

        if self.results and any(data["success_rate"] < 90 for data in self.results.values()):
            print("  • ⚠️  Dégradation des performances détectée - optimisation recommandée")
        else:
            print("  • ✅ Performances stables maintenues")

def main():
    # Configuration des tests
    patient_counts = [3, 5, 10, 50, 100]  # Comme demandé par l'utilisateur
    test_duration = 30  # secondes par test

    # Vérifie que les services sont démarrés
    print("🔍 Vérification des services...")
    try:
        response = requests.get("http://localhost:8081/actuator/health", timeout=5)
        if response.status_code != 200:
            print("❌ Service ingestion-service non disponible")
            return
    except:
        print("❌ Impossible de contacter ingestion-service")
        return

    # Lance les tests
    tester = LoadTest()
    tester.run_load_test(patient_counts, test_duration)
    tester.generate_report()

    # Sauvegarde les résultats détaillés
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    results_file = f"load_test_results_{timestamp}.json"

    with open(results_file, 'w', encoding='utf-8') as f:
        json.dump(tester.results, f, indent=2, ensure_ascii=False)

    print(f"\n💾 Résultats détaillés sauvegardés dans: {results_file}")

if __name__ == "__main__":
    main()