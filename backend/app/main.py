from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from datetime import timedelta

import httpx
from fastapi import FastAPI

from app.api.router import api_router
from app.core.config import Settings, get_settings, validate_settings
from app.core.ratelimit import FailureLimiter
from app.services.oembed import TIMEOUT_SECONDS


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or get_settings()
    validate_settings(settings)  # refuses to start with the default JWT secret outside dev
    http_client = httpx.Client(timeout=TIMEOUT_SECONDS, follow_redirects=False)

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        yield
        http_client.close()

    app = FastAPI(title="KidTube", lifespan=lifespan)
    app.state.http_client = http_client
    app.state.settings = settings
    app.state.pair_limiter = FailureLimiter(
        settings.pair_max_failures, timedelta(minutes=settings.pair_failure_window_minutes)
    )
    login_window = timedelta(minutes=settings.login_failure_window_minutes)
    app.state.login_ip_limiter = FailureLimiter(settings.login_max_failures, login_window)
    app.state.login_email_limiter = FailureLimiter(settings.login_max_failures, login_window)
    app.include_router(api_router)
    return app


app = create_app()
