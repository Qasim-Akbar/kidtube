from collections.abc import Callable
from datetime import datetime, timezone

NowFn = Callable[[], datetime]


def now() -> datetime:
    """Current time, timezone-aware UTC."""
    return datetime.now(timezone.utc)


def get_now() -> NowFn:
    """FastAPI dependency; tests override it to control time."""
    return now
