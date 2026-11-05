from collections.abc import Iterator
from datetime import datetime, timedelta, timezone

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from sqlalchemy import Engine, create_engine
from sqlalchemy.orm import Session
from sqlalchemy.pool import StaticPool

from app.core.clock import get_now
from app.core.config import Settings
from app.core.db import enable_sqlite_fks, get_db
from app.main import create_app
from app.models import Base


class FakeClock:
    def __init__(self) -> None:
        self.current = datetime(2026, 1, 5, 10, 0, tzinfo=timezone.utc)

    def __call__(self) -> datetime:
        return self.current

    def advance(self, **kwargs: float) -> None:
        self.current += timedelta(**kwargs)


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
def app(settings: Settings, engine: Engine, clock: FakeClock) -> FastAPI:
    application = create_app(settings)

    def override_db() -> Iterator[Session]:
        with Session(engine, expire_on_commit=False) as s:
            yield s

    application.dependency_overrides[get_db] = override_db
    application.dependency_overrides[get_now] = lambda: clock
    return application


@pytest.fixture
def client(app: FastAPI) -> TestClient:
    return TestClient(app)
