from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import Session
from sqlalchemy.pool import StaticPool

from app.core.db import enable_sqlite_fks
from app.main import create_app
from app.models import Base


@pytest.fixture
def client() -> TestClient:
    return TestClient(create_app())


@pytest.fixture
def session() -> Iterator[Session]:
    engine = create_engine(
        "sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool
    )
    enable_sqlite_fks(engine)
    Base.metadata.create_all(engine)
    with Session(engine) as s:
        yield s
    engine.dispose()
