from influxdb_client import InfluxDBClient
from datetime import datetime, timedelta
import pytz

class PPGInfluxReader:
    def __init__(self, url, token, org, bucket):
        self.client = InfluxDBClient(url=url, token=token, org=org)
        self.org = org
        self.bucket = bucket

    def get_devices(self):
        """Liste des device_id ayant des données PPG dans la dernière heure"""
        query = f'''
        import "influxdata/influxdb/v1"
        v1.measurementTagValues(bucket: "{self.bucket}", measurement: "ppg", tag: "device_id")
        '''
        tables = self.client.query_api().query(query, org=self.org)
        devices = []
        for table in tables:
            for record in table.records:
                devices.append(record.get_value())
        return devices

    def get_ppg_window(self, device_id, window_seconds=30):
        """Récupère les valeurs PPG des dernières `window_seconds` secondes"""
        now = datetime.utcnow().replace(tzinfo=pytz.UTC)
        start = now - timedelta(seconds=window_seconds)
        query = f'''
        from(bucket: "{self.bucket}")
          |> range(start: {start.isoformat()}, stop: {now.isoformat()})
          |> filter(fn: (r) => r._measurement == "ppg")
          |> filter(fn: (r) => r.device_id == "{device_id}")
          |> filter(fn: (r) => r._field == "value")
          |> aggregateWindow(every: 1s, fn: mean)   // facultatif, adaptez selon la fréquence
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