from collections.abc import Callable
from datetime import date, datetime, timezone
from zoneinfo import ZoneInfo

NowFn = Callable[[], datetime]


def now() -> datetime:
    """Current time, timezone-aware UTC."""
    return datetime.now(timezone.utc)


def get_now() -> NowFn:
    """FastAPI dependency; tests override it to control time."""
    return now


def family_today(moment: datetime, tz_name: str) -> date:
    """The family's local calendar date at `moment` (an aware datetime). Day boundaries follow the child's day."""
    return moment.astimezone(ZoneInfo(tz_name)).date()
