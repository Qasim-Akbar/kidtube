from fastapi.testclient import TestClient
from sqlalchemy import Engine, select
from sqlalchemy.orm import Session

from app.core.config import Settings
from app.models import Device, PairingCode
from tests.helpers import bearer, new_code, pair, parent_session


def test_pair_code_is_six_digits_valid_ten_minutes(client: TestClient, clock) -> None:
    headers = parent_session(client)
    r = client.post("/devices/pair-code", headers=headers)
    assert r.status_code == 201
    body = r.json()
    assert len(body["code"]) == 6 and body["code"].isdigit()
    assert body["expires_at"].startswith("2026-01-05T10:10:00")


def test_pair_flow_success_and_only_hashes_stored(client: TestClient, engine: Engine) -> None:
    headers = parent_session(client)
    code = new_code(client, headers)
    r = client.post("/devices/pair", json={"code": code, "device_name": "Ali's tablet"})
    assert r.status_code == 201
    body = r.json()
    token = body["device_token"]
    assert len(token) >= 40

    devices = client.get("/devices", headers=headers).json()
    assert [(d["name"], d["role"], d["revoked_at"]) for d in devices] == [("Ali's tablet", "kid", None)]
    assert devices[0]["id"] == body["device_id"] and "token" not in str(devices[0])

    with Session(engine) as s:
        device = s.scalars(select(Device)).one()
        assert device.token_hash and token not in device.token_hash and device.role.value == "kid"
        pc = s.scalars(select(PairingCode)).one()
        assert pc.code_hash != code and code not in pc.code_hash and pc.used_at is not None

    assert client.get("/children", headers=bearer(token)).status_code == 200


def test_pair_code_single_use(client: TestClient) -> None:
    headers = parent_session(client)
    code = new_code(client, headers)
    assert client.post("/devices/pair", json={"code": code, "device_name": "A"}).status_code == 201
    r = client.post("/devices/pair", json={"code": code, "device_name": "B"})
    assert r.status_code == 400
    assert len(client.get("/devices", headers=headers).json()) == 1


def test_pair_code_expires(client: TestClient, clock) -> None:
    headers = parent_session(client)
    code = new_code(client, headers)
    clock.advance(minutes=9, seconds=59)
    # still valid just before expiry, so check expiry on a second code
    code2 = new_code(client, headers)
    clock.advance(minutes=10, seconds=1)
    assert client.post("/devices/pair", json={"code": code, "device_name": "A"}).status_code == 400
    assert client.post("/devices/pair", json={"code": code2, "device_name": "A"}).status_code == 400


def test_pair_code_valid_just_before_expiry(client: TestClient, clock) -> None:
    headers = parent_session(client)
    code = new_code(client, headers)
    clock.advance(minutes=9, seconds=59)
    assert client.post("/devices/pair", json={"code": code, "device_name": "A"}).status_code == 201


def test_wrong_code_rejected_and_malformed_code_422(client: TestClient) -> None:
    headers = parent_session(client)
    code = new_code(client, headers)
    wrong = "000000" if code != "000000" else "000001"
    assert client.post("/devices/pair", json={"code": wrong, "device_name": "A"}).status_code == 400
    for bad in ("12345", "1234567", "abcdef"):
        assert client.post("/devices/pair", json={"code": bad, "device_name": "A"}).status_code == 422


def test_pair_rate_limit_blocks_after_failures_even_for_valid_code(client: TestClient, clock) -> None:
    headers = parent_session(client)
    code = new_code(client, headers)
    wrong = "000000" if code != "000000" else "000001"
    for _ in range(5):
        assert client.post("/devices/pair", json={"code": wrong, "device_name": "A"}).status_code == 400
    r = client.post("/devices/pair", json={"code": code, "device_name": "A"})
    assert r.status_code == 429
    assert int(r.headers["Retry-After"]) > 0
    # the window passes; the valid code (extended by a fresh one) works again
    clock.advance(minutes=10, seconds=1)
    code = new_code(client, headers)
    assert client.post("/devices/pair", json={"code": code, "device_name": "A"}).status_code == 201


def test_pair_rate_limit_is_per_client_ip(settings: Settings, client: TestClient) -> None:
    settings.trust_proxy_headers = True
    headers = parent_session(client)
    code = new_code(client, headers)
    wrong = "000000" if code != "000000" else "000001"
    attacker = {"X-Forwarded-For": "6.6.6.6"}
    for _ in range(5):
        client.post("/devices/pair", json={"code": wrong, "device_name": "A"}, headers=attacker)
    assert client.post("/devices/pair", json={"code": code, "device_name": "A"}, headers=attacker).status_code == 429
    ok = client.post("/devices/pair", json={"code": code, "device_name": "A"}, headers={"X-Forwarded-For": "7.7.7.7"})
    assert ok.status_code == 201


def test_forwarded_header_ignored_unless_trusted(client: TestClient) -> None:
    headers = parent_session(client)
    code = new_code(client, headers)
    wrong = "000000" if code != "000000" else "000001"
    for i in range(5):  # rotating spoofed IPs must not dodge the limit
        client.post("/devices/pair", json={"code": wrong, "device_name": "A"}, headers={"X-Forwarded-For": f"1.1.1.{i}"})
    r = client.post("/devices/pair", json={"code": code, "device_name": "A"}, headers={"X-Forwarded-For": "9.9.9.9"})
    assert r.status_code == 429


def test_revoked_device_rejected(client: TestClient) -> None:
    headers = parent_session(client)
    kid = pair(client, headers)
    kid_headers = bearer(kid["device_token"])
    assert client.get("/children", headers=kid_headers).status_code == 200

    r = client.post(f"/devices/{kid['device_id']}/revoke", headers=headers)
    assert r.status_code == 200 and r.json()["revoked_at"] is not None
    assert client.get("/children", headers=kid_headers).status_code == 401
    assert client.post(f"/devices/{kid['device_id']}/revoke", headers=headers).status_code == 200  # idempotent


def test_revoke_unknown_device_404(client: TestClient) -> None:
    import uuid

    headers = parent_session(client)
    assert client.post(f"/devices/{uuid.uuid4()}/revoke", headers=headers).status_code == 404
