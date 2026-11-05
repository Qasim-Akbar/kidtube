from datetime import timedelta

import pytest
from fastapi.testclient import TestClient

from app.core.config import Settings
from app.core.ratelimit import FailureLimiter
from tests.helpers import PASSWORD, register

EMAIL = "mum@example.com"


def attempt(client: TestClient, email: str = EMAIL, password: str = "wrong-password", ip: str | None = None):
    headers = {"X-Forwarded-For": ip} if ip else {}
    return client.post("/auth/login", json={"email": email, "password": password}, headers=headers)


@pytest.fixture
def account(client: TestClient) -> None:
    assert register(client, EMAIL).status_code == 201


def test_lockout_after_five_failures_even_with_correct_password(client: TestClient, account) -> None:
    for _ in range(5):
        assert attempt(client).status_code == 401
    r = attempt(client, password=PASSWORD)
    assert r.status_code == 429
    assert int(r.headers["Retry-After"]) == 600
    assert attempt(client, password="wrong-again").status_code == 429


def test_retry_after_counts_down(client: TestClient, clock, account) -> None:
    for _ in range(5):
        attempt(client)
    clock.advance(minutes=4)
    assert int(attempt(client, password=PASSWORD).headers["Retry-After"]) == 360


def test_window_expires_with_controlled_clock(client: TestClient, clock, account) -> None:
    for _ in range(5):
        attempt(client)
    assert attempt(client, password=PASSWORD).status_code == 429
    clock.advance(minutes=9, seconds=59)
    assert attempt(client, password=PASSWORD).status_code == 429
    clock.advance(seconds=2)
    assert attempt(client, password=PASSWORD).status_code == 200


def test_failures_age_out_individually(client: TestClient, clock, account) -> None:
    for _ in range(4):
        attempt(client)
    clock.advance(minutes=11)  # the four old failures are gone
    for _ in range(4):
        assert attempt(client).status_code == 401
    assert attempt(client, password=PASSWORD).status_code == 200


def test_success_resets_email_counter(settings: Settings, client: TestClient, account) -> None:
    settings.trust_proxy_headers = True  # a distinct IP per attempt isolates the email counter
    for i in range(4):
        assert attempt(client, ip=f"10.0.0.{i}").status_code == 401
    assert attempt(client, password=PASSWORD, ip="10.0.1.0").status_code == 200
    for i in range(4):  # fresh budget: four more failures, still no lockout
        assert attempt(client, ip=f"10.0.2.{i}").status_code == 401
    assert attempt(client, password=PASSWORD, ip="10.0.3.0").status_code == 200
    # without the reset, the 5th failure overall would have locked the email
    assert attempt(client, ip="10.0.4.0").status_code == 401


def test_unknown_email_gets_same_generic_error(client: TestClient, account) -> None:
    wrong_pw = attempt(client)
    unknown = attempt(client, email="nobody@example.com")
    assert wrong_pw.status_code == unknown.status_code == 401
    assert wrong_pw.json() == unknown.json() == {"detail": "Incorrect email or password"}
    assert wrong_pw.headers.get("www-authenticate") == unknown.headers.get("www-authenticate")


def test_lockout_response_is_identical_for_known_and_unknown_emails(client: TestClient, account) -> None:
    for _ in range(5):
        attempt(client)
        attempt(client, email="nobody@example.com")
    known = attempt(client, password=PASSWORD)
    unknown = attempt(client, email="nobody@example.com", password=PASSWORD)
    assert known.status_code == unknown.status_code == 429
    assert known.json() == unknown.json()
    assert known.headers["Retry-After"] == unknown.headers["Retry-After"]


def test_email_key_is_case_insensitive(client: TestClient, account) -> None:
    for e in ("MUM@example.com", "Mum@Example.com", " mum@example.com ", "mum@EXAMPLE.com", "mum@example.com"):
        assert attempt(client, email=e).status_code == 401
    assert attempt(client, email="MUM@EXAMPLE.COM", password=PASSWORD).status_code == 429


def test_email_limit_applies_across_ips(settings: Settings, client: TestClient, account) -> None:
    settings.trust_proxy_headers = True
    for i in range(5):
        assert attempt(client, ip=f"10.0.0.{i}").status_code == 401  # a different IP each time
    assert attempt(client, password=PASSWORD, ip="10.9.9.9").status_code == 429  # fresh IP, same email
    assert attempt(client, email="other@example.com", ip="10.9.9.9").status_code == 401  # other emails unaffected


def test_ip_limit_applies_across_emails(settings: Settings, client: TestClient, account) -> None:
    settings.trust_proxy_headers = True
    for i in range(5):
        assert attempt(client, email=f"user{i}@example.com", ip="6.6.6.6").status_code == 401
    # this IP is locked out even for a valid login on an account with no failures of its own
    assert attempt(client, password=PASSWORD, ip="6.6.6.6").status_code == 429
    assert attempt(client, password=PASSWORD, ip="7.7.7.7").status_code == 200


def test_success_does_not_reset_ip_counter(settings: Settings, client: TestClient, account) -> None:
    settings.trust_proxy_headers = True
    ip = "6.6.6.6"
    for i in range(4):
        assert attempt(client, email=f"user{i}@example.com", ip=ip).status_code == 401
    assert attempt(client, password=PASSWORD, ip=ip).status_code == 200  # own account; must not wipe the IP count
    assert attempt(client, email="user9@example.com", ip=ip).status_code == 401  # 5th IP failure
    assert attempt(client, password=PASSWORD, ip=ip).status_code == 429


def test_forwarded_header_ignored_unless_trusted(client: TestClient, account) -> None:
    for i in range(5):  # spoofed rotating IPs must not dodge the IP limit
        attempt(client, email=f"user{i}@example.com", ip=f"1.1.1.{i}")
    assert attempt(client, password=PASSWORD, ip="9.9.9.9").status_code == 429


def test_lockout_does_not_extend_itself(client: TestClient, clock, account) -> None:
    for _ in range(5):
        attempt(client)
    for _ in range(10):  # hammering while locked out is not counted as new failures
        assert attempt(client).status_code == 429
    clock.advance(minutes=10, seconds=1)
    assert attempt(client, password=PASSWORD).status_code == 200


def test_limits_are_configurable(settings: Settings, client: TestClient, account) -> None:
    from app.main import create_app  # separate app so the limiter picks up new settings

    s = Settings(_env_file=None, jwt_secret=settings.jwt_secret, login_max_failures=2, login_failure_window_minutes=1)
    app = create_app(s)
    app.dependency_overrides.update(client.app.dependency_overrides)  # same DB and clock
    c = TestClient(app)
    assert attempt(c).status_code == 401
    assert attempt(c).status_code == 401
    r = attempt(c, password=PASSWORD)
    assert r.status_code == 429 and int(r.headers["Retry-After"]) == 60


def test_limiter_sweeps_stale_keys() -> None:
    from datetime import datetime, timezone

    t0 = datetime(2026, 1, 5, tzinfo=timezone.utc)
    limiter = FailureLimiter(5, timedelta(minutes=10), max_keys=3)
    for i in range(3):
        limiter.record_failure(f"k{i}", t0)
    limiter.record_failure("new", t0 + timedelta(minutes=11))  # at capacity: stale keys are swept
    assert list(limiter._failures) == ["new"]
