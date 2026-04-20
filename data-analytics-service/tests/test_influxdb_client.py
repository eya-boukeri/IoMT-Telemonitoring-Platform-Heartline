from app.influxdb_client import PPGInfluxReader


class FakeRecord:
    def __init__(self, value):
        self._value = value

    def get_value(self):
        return self._value


class FakeTable:
    def __init__(self, values):
        self.records = [FakeRecord(value) for value in values]


class FakeQueryApi:
    def __init__(self, responses):
        self.responses = responses
        self.queries = []

    def query(self, query, org):
        self.queries.append({"query": query, "org": org})
        return self.responses.pop(0)


class FakeClient:
    def __init__(self, responses):
        self.query_api_instance = FakeQueryApi(responses)

    def query_api(self):
        return self.query_api_instance

    def close(self):
        return None


def test_get_patient_ids_uses_vitals_measurement():
    reader = PPGInfluxReader("http://example", "token", "org", "bucket")
    fake_client = FakeClient([[FakeTable(["patient-1", "patient-2"]) ]])
    reader.client = fake_client

    patient_ids = reader.get_patient_ids()

    assert patient_ids == ["patient-1", "patient-2"]
    query = fake_client.query_api_instance.queries[0]["query"]
    assert 'measurement: "vitals"' in query
    assert 'tag: "patientId"' in query


def test_get_ppg_window_reads_ppg_green_average_for_patient():
    reader = PPGInfluxReader("http://example", "token", "org", "bucket")
    fake_client = FakeClient([[FakeTable([0.12, 0.14, 0.15])]])
    reader.client = fake_client

    values = reader.get_ppg_window("patient-123", window_seconds=30)

    assert values == [0.12, 0.14, 0.15]
    query = fake_client.query_api_instance.queries[0]["query"]
    assert 'r._measurement == "vitals"' in query
    assert 'r.patientId == "patient-123"' in query
    assert 'r._field == "ppgGreenAverage"' in query