from datetime import timedelta

from fastapi import FastAPI

from app.api.router import api_router
from app.core.config import Settings, get_settings, validate_settings
from app.core.ratelimit import FailureLimiter


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or get_settings()
    validate_settings(settings)  # refuses to start with the default JWT secret outside dev
    app = FastAPI(title="KidTube")
    app.state.settings = settings
    app.state.pair_limiter = FailureLimiter(
        settings.pair_max_failures, timedelta(minutes=settings.pair_failure_window_minutes)
    )
    app.include_router(api_router)
    return app


app = create_app()
