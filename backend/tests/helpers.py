from fastapi.testclient import TestClient

PASSWORD = "correct-horse"


def register(client: TestClient, email: str = "mum@example.com", password: str = PASSWORD, **extra):
    return client.post("/auth/register", json={"email": email, "password": password, **extra})


def login(client: TestClient, email: str = "mum@example.com", password: str = PASSWORD) -> dict:
    r = client.post("/auth/login", json={"email": email, "password": password})
    assert r.status_code == 200, r.text
    return r.json()


def bearer(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def parent_session(client: TestClient, email: str = "mum@example.com") -> dict[str, str]:
    """Register + login; returns parent auth headers."""
    assert register(client, email).status_code == 201
    return bearer(login(client, email)["access_token"])


def new_code(client: TestClient, headers: dict[str, str]) -> str:
    r = client.post("/devices/pair-code", headers=headers)
    assert r.status_code == 201, r.text
    return r.json()["code"]


def pair(client: TestClient, parent_headers: dict[str, str], name: str = "Tablet") -> dict:
    r = client.post("/devices/pair", json={"code": new_code(client, parent_headers), "device_name": name})
    assert r.status_code == 201, r.text
    return r.json()
