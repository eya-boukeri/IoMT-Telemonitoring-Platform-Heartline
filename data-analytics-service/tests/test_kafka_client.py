from app.kafka_client import send_alert


class FakeFuture:
    def __init__(self, should_fail=False):
        self.should_fail = should_fail

    def get(self, timeout=5):
        if self.should_fail:
            raise RuntimeError("send failed")
        return None


class FakeProducer:
    def __init__(self, should_fail=False):
        self.should_fail = should_fail
        self.calls = []

    def send(self, topic, key=None, value=None):
        self.calls.append({"topic": topic, "key": key, "value": value})
        return FakeFuture(should_fail=self.should_fail)


def test_send_alert_generates_alert_id_and_sends_message():
    producer = FakeProducer()
    alert = {"patientId": "patient-1", "alertId": None}

    ok = send_alert(producer, "alerts", alert)

    assert ok is True
    assert "alertId" in alert and alert["alertId"] is not None
    assert len(producer.calls) == 1
    assert producer.calls[0]["topic"] == "alerts"
    assert producer.calls[0]["key"] == b"patient-1"


def test_send_alert_returns_false_on_send_failure():
    producer = FakeProducer(should_fail=True)
    alert = {"patientId": "patient-2", "alertId": None}

    ok = send_alert(producer, "alerts", alert)

    assert ok is False
