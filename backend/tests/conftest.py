from collections.abc import Iterator
from datetime import datetime, timedelta, timezone

import httpx
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from sqlalchemy import Engine, create_engine
from sqlalchemy.orm import Session
from sqlalchemy.pool import StaticPool

from app.core.clock import get_now
from app.core.config import Settings
from app.core.db import enable_sqlite_fks, get_db
from app.core.deps import get_http_client
from app.main import create_app
from app.models import Base


class FakeClock:
    def __init__(self) -> None:
        self.current = datetime(2026, 1, 5, 10, 0, tzinfo=timezone.utc)

    def __call__(self) -> datetime:
        return self.current

    def advance(self, **kwargs: float) -> None:
        self.current += timedelta(**kwargs)


class OEmbedStub:
    """Stands in for the network. Records every outbound request; any unscripted call fails the test."""

    def __init__(self) -> None:
        self.requests: list[httpx.Request] = []
        self.handler = self._no_network

    @staticmethod
    def _no_network(request: httpx.Request) -> httpx.Response:
        raise AssertionError(f"unexpected outbound request: {request.url}")

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        return self.handler(request)

    def respond(self, status: int = 200, json: object = None, **kwargs: object) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            body = json
            if body is None and status == 200:
                vid = request.url.params["url"].split("v=")[-1]
                body = {
                    "title": f"Title {vid}",
                    "thumbnail_url": f"https://i.ytimg.com/vi/{vid}/hqdefault.jpg",
                    "type": "video",
                }
            return httpx.Response(status, json=body, **kwargs)  # type: ignore[arg-type]

        self.handler = handler

    def raise_error(self, exc: Exception) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            raise exc

        self.handler = handler


@pytest.fixture
def oembed() -> OEmbedStub:
    return OEmbedStub()


@pytest.fixture
def clock() -> FakeClock:
    return FakeClock()


@pytest.fixture
def settings() -> Settings:
    return Settings(
        _env_file=None, app_env="dev", jwt_secret="test-secret-" + "x" * 32, allow_registration=True
    )


@pytest.fixture
def engine() -> Iterator[Engine]:
    eng = create_engine("sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool)
    enable_sqlite_fks(eng)
    Base.metadata.create_all(eng)
    yield eng
    eng.dispose()


@pytest.fixture
def session(engine: Engine) -> Iterator[Session]:
    with Session(engine) as s:
        yield s


@pytest.fixture
def app(settings: Settings, engine: Engine, clock: FakeClock, oembed: OEmbedStub) -> FastAPI:
    application = create_app(settings)

    def override_db() -> Iterator[Session]:
        with Session(engine, expire_on_commit=False) as s:
            yield s

    application.dependency_overrides[get_db] = override_db
    application.dependency_overrides[get_now] = lambda: clock
    stub_client = httpx.Client(transport=httpx.MockTransport(oembed), follow_redirects=False)
    application.dependency_overrides[get_http_client] = lambda: stub_client
    return application


@pytest.fixture
def client(app: FastAPI) -> TestClient:
    return TestClient(app)
