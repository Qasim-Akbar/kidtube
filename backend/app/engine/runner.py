"""JSON entry points: the exact shape the Kotlin port is tested against (tests/engine_vectors)."""

from __future__ import annotations

from typing import Any, Mapping

from app.engine.feed import DEFAULT_DURATION_SECONDS, build_feed
from app.engine.projection import project
from app.engine.types import (
    AppSecondsRecord,
    ChildLimits,
    ItemState,
    PlanConfig,
    SecondsRecord,
    ViewRecord,
    _date,
    _int,
)


def run_feed(data: Mapping[str, Any]) -> dict[str, Any]:
    return build_feed(
        PlanConfig.from_dict(data["plan"]),
        [ItemState.from_dict(i) for i in data["items"]],
        [ViewRecord.from_dict(v) for v in data.get("views", [])],
        [SecondsRecord.from_dict(s) for s in data.get("seconds", [])],
        [AppSecondsRecord.from_dict(s) for s in data.get("app_seconds", [])],
        ChildLimits.from_dict(data.get("limits") or {}),
        _date(data["today"], "today"),
        _int(data.get("default_duration_seconds", DEFAULT_DURATION_SECONDS), "default_duration_seconds"),
    ).to_dict()


def run_projection(data: Mapping[str, Any]) -> dict[str, Any]:
    return project(
        PlanConfig.from_dict(data["plan"]),
        [ItemState.from_dict(i) for i in data["items"]],
        [ViewRecord.from_dict(v) for v in data.get("views", [])],
        [SecondsRecord.from_dict(s) for s in data.get("seconds", [])],
        _date(data["today"], "today"),
        _int(data["days"], "days"),
        _int(data.get("default_duration_seconds", DEFAULT_DURATION_SECONDS), "default_duration_seconds"),
    ).to_dict()
