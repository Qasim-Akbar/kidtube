"""Pure plan engine (no DB, no ORM, no clock). Semantics: docs/SPEC.md section 7."""

from app.engine.feed import DEFAULT_DURATION_SECONDS, build_feed
from app.engine.projection import project
from app.engine.runner import run_feed, run_projection
from app.engine.types import (
    AppSecondsRecord,
    ChildLimits,
    FeedItem,
    FeedResult,
    ItemState,
    PlanConfig,
    ProjectedDay,
    ProjectedView,
    Projection,
    SecondsRecord,
    ViewRecord,
)

__all__ = [
    "DEFAULT_DURATION_SECONDS", "AppSecondsRecord", "ChildLimits", "FeedItem", "FeedResult", "ItemState",
    "PlanConfig", "ProjectedDay", "ProjectedView", "Projection", "SecondsRecord", "ViewRecord",
    "build_feed", "project", "run_feed", "run_projection",
]
