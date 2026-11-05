import jwt
import pytest
from fastapi.testclient import TestClient
from sqlalchemy import Engine, select
from sqlalchemy.orm import Session

from app.core.config import Settings
from app.main import create_app
from app.models import Parent
from tests.helpers import PASSWORD, bearer, login, register


def test_register_success_lowercases_email_and_hashes_with_argon2(client: TestClient, engine: Engine) -> None:
    r = register(client, "  Mum@Example.COM ", family_name="Akbar")
    assert r.status_code == 201
    body = r.json()
    assert body["email"] == "mum@example.com"
    assert "password" not in str(body)
    with Session(engine) as s:
        parent = s.scalars(select(Parent)).one()
        assert parent.email == "mum@example.com"
        assert parent.password_hash.startswith("$argon2")
        assert parent.family.name == "Akbar" and parent.family.timezone == "Asia/Karachi"


def test_register_duplicate_email_case_insensitive(client: TestClient) -> None:
    assert register(client, "a@example.com").status_code == 201
    assert register(client, "A@EXAMPLE.com").status_code == 409


@pytest.mark.parametrize("password", ["short", "1234567", ""])
def test_register_rejects_short_password(client: TestClient, password: str) -> None:
    assert register(client, password=password).status_code == 422


def test_register_rejects_bad_email(client: TestClient) -> None:
    assert register(client, "not-an-email").status_code == 422


def test_registration_disabled_flag(settings: Settings, client: TestClient) -> None:
    settings.allow_registration = False
    r = register(client)
    assert r.status_code == 403


def test_login_success_returns_both_tokens(client: TestClient) -> None:
    register(client)
    r = client.post("/auth/login", json={"email": "MUM@example.com", "password": PASSWORD})
    assert r.status_code == 200
    body = r.json()
    assert body["token_type"] == "bearer" and body["access_token"] and body["refresh_token"]
    assert body["access_token"] != body["refresh_token"]


def test_token_claims(client: TestClient, settings: Settings) -> None:
    reg = register(client).json()
    tokens = login(client)
    for kind in ("access", "refresh"):
        claims = jwt.decode(
            tokens[f"{kind}_token"], settings.jwt_secret, algorithms=["HS256"], options={"verify_exp": False}
        )
        assert claims["type"] == kind
        assert claims["sub"] == reg["parent_id"] and claims["family_id"] == reg["family_id"]
        assert "exp" in claims


@pytest.mark.parametrize(
    "email,password", [("mum@example.com", "wrong-password"), ("nobody@example.com", PASSWORD)]
)
def test_login_failure(client: TestClient, email: str, password: str) -> None:
    register(client)
    r = client.post("/auth/login", json={"email": email, "password": password})
    assert r.status_code == 401
    assert r.json()["detail"] == "Incorrect email or password"  # same message either way


def test_refresh_returns_working_access_token(client: TestClient) -> None:
    register(client)
    tokens = login(client)
    r = client.post("/auth/refresh", json={"refresh_token": tokens["refresh_token"]})
    assert r.status_code == 200
    assert client.get("/children", headers=bearer(r.json()["access_token"])).status_code == 200


def test_refresh_token_cannot_be_used_as_access(client: TestClient) -> None:
    register(client)
    tokens = login(client)
    assert client.get("/children", headers=bearer(tokens["refresh_token"])).status_code == 401


def test_access_token_cannot_be_used_to_refresh(client: TestClient) -> None:
    register(client)
    tokens = login(client)
    r = client.post("/auth/refresh", json={"refresh_token": tokens["access_token"]})
    assert r.status_code == 401


def test_expired_access_token_rejected(client: TestClient, clock) -> None:
    register(client)
    tokens = login(client)
    headers = bearer(tokens["access_token"])
    clock.advance(minutes=14)
    assert client.get("/children", headers=headers).status_code == 200
    clock.advance(minutes=2)
    assert client.get("/children", headers=headers).status_code == 401


def test_expired_refresh_token_rejected(client: TestClient, clock) -> None:
    register(client)
    tokens = login(client)
    clock.advance(days=29)
    assert client.post("/auth/refresh", json={"refresh_token": tokens["refresh_token"]}).status_code == 200
    clock.advance(days=2)
    assert client.post("/auth/refresh", json={"refresh_token": tokens["refresh_token"]}).status_code == 401


def test_garbage_and_wrongly_signed_tokens_rejected(client: TestClient, settings: Settings) -> None:
    reg = register(client).json()
    forged = jwt.encode(
        {"sub": reg["parent_id"], "family_id": reg["family_id"], "type": "access", "exp": 4_000_000_000},
        "some-other-secret-" + "y" * 32, algorithm="HS256",
    )
    for token in ("garbage", "a.b.c", forged):
        assert client.get("/children", headers=bearer(token)).status_code == 401
    assert client.get("/children").status_code == 401


def test_token_with_mismatched_family_rejected(client: TestClient, settings: Settings) -> None:
    import uuid

    reg = register(client).json()
    forged = jwt.encode(
        {"sub": reg["parent_id"], "family_id": str(uuid.uuid4()), "type": "access", "exp": 4_000_000_000},
        settings.jwt_secret, algorithm="HS256",
    )
    assert client.get("/children", headers=bearer(forged)).status_code == 401


def test_startup_refuses_default_secret_outside_dev() -> None:
    with pytest.raises(RuntimeError, match="JWT_SECRET"):
        create_app(Settings(_env_file=None, app_env="prod", jwt_secret="change-me"))


def test_startup_allows_default_secret_in_dev_and_real_secret_in_prod() -> None:
    create_app(Settings(_env_file=None, app_env="dev", jwt_secret="change-me"))
    create_app(Settings(_env_file=None, app_env="prod", jwt_secret="a-real-secret-" + "z" * 32))
