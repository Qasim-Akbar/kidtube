import uuid

import pytest
from fastapi.testclient import TestClient

from tests.helpers import bearer, pair, parent_session


@pytest.fixture
def family(client: TestClient):
    headers = parent_session(client, "a@example.com")
    child = client.post("/children", json={"name": "Ali"}, headers=headers).json()
    kid = pair(client, headers, "Ali tab")
    return {"headers": headers, "child": child, "kid": kid, "kid_headers": bearer(kid["device_token"])}


def test_children_crud_for_parent(client: TestClient) -> None:
    h = parent_session(client)
    r = client.post("/children", json={"name": "Sara", "avatar": "cat", "daily_app_minutes": 45}, headers=h)
    assert r.status_code == 201
    child = r.json()
    assert (child["name"], child["avatar"], child["daily_app_minutes"]) == ("Sara", "cat", 45)

    r = client.patch(f"/children/{child['id']}", json={"daily_app_minutes": None, "name": "Sarah"}, headers=h)
    assert r.status_code == 200 and r.json()["daily_app_minutes"] is None and r.json()["name"] == "Sarah"
    r = client.patch(f"/children/{child['id']}", json={"avatar": "dog"}, headers=h)
    assert r.json()["avatar"] == "dog" and r.json()["name"] == "Sarah"  # unsent fields untouched
    assert client.patch(f"/children/{child['id']}", json={"name": None}, headers=h).status_code == 422
    assert client.post("/children", json={"name": ""}, headers=h).status_code == 422
    assert [c["name"] for c in client.get("/children", headers=h).json()] == ["Sarah"]


def test_device_token_can_read_children(client: TestClient, family) -> None:
    r = client.get("/children", headers=family["kid_headers"])
    assert r.status_code == 200
    assert [c["id"] for c in r.json()] == [family["child"]["id"]]


def test_device_token_rejected_by_parent_only_endpoints(client: TestClient, family) -> None:
    kh = family["kid_headers"]
    cid, did = family["child"]["id"], family["kid"]["device_id"]
    assert client.post("/children", json={"name": "X"}, headers=kh).status_code == 401
    assert client.patch(f"/children/{cid}", json={"name": "X"}, headers=kh).status_code == 401
    assert client.post("/devices/pair-code", headers=kh).status_code == 401
    assert client.post(f"/devices/{did}/revoke", headers=kh).status_code == 401
    assert client.get("/devices", headers=kh).status_code == 401
    # nothing changed
    assert client.get("/children", headers=family["headers"]).json()[0]["name"] == "Ali"
    assert client.get("/devices", headers=family["headers"]).json()[0]["revoked_at"] is None


def test_unauthenticated_requests_rejected(client: TestClient, family) -> None:
    assert client.get("/children").status_code == 401
    assert client.get("/devices").status_code == 401
    assert client.post("/children", json={"name": "X"}).status_code == 401
    assert client.post("/devices/pair-code").status_code == 401
    assert client.get("/health").status_code == 200  # public


def test_unknown_opaque_token_rejected(client: TestClient) -> None:
    assert client.get("/children", headers=bearer("not-a-real-device-token")).status_code == 401


def test_cross_family_isolation(client: TestClient, family) -> None:
    hb = parent_session(client, "b@example.com")
    child_b = client.post("/children", json={"name": "Zain"}, headers=hb).json()
    kid_b = pair(client, hb, "Zain tab")

    # parents only see their own family
    assert [c["name"] for c in client.get("/children", headers=family["headers"]).json()] == ["Ali"]
    assert [c["name"] for c in client.get("/children", headers=hb).json()] == ["Zain"]
    assert [d["name"] for d in client.get("/devices", headers=family["headers"]).json()] == ["Ali tab"]
    assert [d["name"] for d in client.get("/devices", headers=hb).json()] == ["Zain tab"]

    # device tokens only see their own family's children
    assert [c["name"] for c in client.get("/children", headers=family["kid_headers"]).json()] == ["Ali"]
    assert [c["name"] for c in client.get("/children", headers=bearer(kid_b["device_token"])).json()] == ["Zain"]

    # cross-family writes look like "not found" and change nothing
    assert client.patch(f"/children/{family['child']['id']}", json={"name": "Hacked"}, headers=hb).status_code == 404
    assert client.post(f"/devices/{family['kid']['device_id']}/revoke", headers=hb).status_code == 404
    assert client.patch(f"/children/{uuid.uuid4()}", json={"name": "x"}, headers=hb).status_code == 404
    assert client.get("/children", headers=family["kid_headers"]).status_code == 200
    assert client.get("/children", headers=family["headers"]).json()[0]["name"] == "Ali"
    assert child_b["id"] != family["child"]["id"]


def test_pair_code_attaches_device_to_issuing_family(client: TestClient, family) -> None:
    hb = parent_session(client, "b@example.com")
    pair(client, hb, "B device")
    assert [d["name"] for d in client.get("/devices", headers=family["headers"]).json()] == ["Ali tab"]
    assert [d["name"] for d in client.get("/devices", headers=hb).json()] == ["B device"]
