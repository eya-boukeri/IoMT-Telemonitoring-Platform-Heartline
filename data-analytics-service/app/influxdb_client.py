from datetime import datetime, timedelta

import pytz
from influxdb_client import InfluxDBClient

class PPGInfluxReader:
    def __init__(self, url, token, org, bucket, signal_field="ppgGreenAverage"):
        self.client = InfluxDBClient(url=url, token=token, org=org)
        self.org = org
        self.bucket = bucket
        self.signal_field = signal_field

    def get_patient_ids(self):
        """Liste des patientId ayant des données PPG dans la dernière heure"""
        query = f'''
        from(bucket: "{self.bucket}")
          |> range(start: -1h)
          |> filter(fn: (r) => r._measurement == "vitals")
          |> distinct(column: "patientId")
        '''
        tables = self.client.query_api().query(query, org=self.org)
        patient_ids = []
        for table in tables:
            for record in table.records:
                patient_ids.append(record.get_value())
        return list(set(patient_ids))  # supprimer les doublons

    def get_devices(self):
        """Alias de compatibilité vers get_patient_ids()."""
        return self.get_patient_ids()

    def get_ppg_window(self, patient_id, window_seconds=30, field=None):
        """Récupère les valeurs PPG des dernières `window_seconds` secondes."""
        field_name = field or self.signal_field
        now = datetime.now(tz=pytz.UTC)
        start = now - timedelta(seconds=window_seconds)
        query = f'''
        from(bucket: "{self.bucket}")
          |> range(start: {start.isoformat()}, stop: {now.isoformat()})
          |> filter(fn: (r) => r._measurement == "vitals")
          |> filter(fn: (r) => r.patientId == "{patient_id}")
          |> filter(fn: (r) => r._field == "{field_name}")
          |> sort(columns: ["_time"])
        '''
        tables = self.client.query_api().query(query, org=self.org)
        values = []
        for table in tables:
            for record in table.records:
                values.append(record.get_value())
        return values

    def close(self):
        self.client.close()