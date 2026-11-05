import math
from datetime import datetime, timedelta
from threading import Lock


class FailureLimiter:
    """In-memory sliding-window limiter on failed attempts per key (single process only)."""

    def __init__(self, max_failures: int, window: timedelta) -> None:
        self.max_failures = max_failures
        self.window = window
        self._failures: dict[str, list[datetime]] = {}
        self._lock = Lock()

    def _prune(self, key: str, now: datetime) -> list[datetime]:
        recent = [t for t in self._failures.get(key, []) if now - t < self.window]
        if recent:
            self._failures[key] = recent
        else:
            self._failures.pop(key, None)
        return recent

    def retry_after(self, key: str, now: datetime) -> int | None:
        """Seconds until the key may try again, or None if it is not blocked."""
        with self._lock:
            recent = self._prune(key, now)
            if len(recent) < self.max_failures:
                return None
            return max(1, math.ceil((recent[0] + self.window - now).total_seconds()))

    def record_failure(self, key: str, now: datetime) -> None:
        with self._lock:
            self._prune(key, now)
            self._failures.setdefault(key, []).append(now)

    def reset(self, key: str) -> None:
        with self._lock:
            self._failures.pop(key, None)
