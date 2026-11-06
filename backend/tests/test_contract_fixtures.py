"""API contract fixtures for the Android client.

One deterministic scenario drives the REAL API (TestClient, fake clock, fixed settings) and records every interesting
response to ``tests/contract_fixtures/NN_<name>.json`` as ``{name, request, status, headers, body}``. The Android app
parses these files in its unit tests, so the client's JSON models cannot drift from the backend.

* ``test_generate_contract_fixtures`` runs the scenario (and writes the files when ``UPDATE_FIXTURES=1``).
* ``test_contract_fixtures_match_committed`` regenerates in memory and fails if the committed files differ.

Refresh:  cd backend && UPDATE_FIXTURES=1 .venv/bin/pytest tests/test_contract_fixtures.py

Server-generated values are normalised to stable placeholders in order of first appearance (see ``Normaliser``).
Authorization headers and other request headers (except If-None-Match) are never written.
"""
import itertools
import json
import os
import re
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

import httpx
import jwt
import pytest
from fastapi.testclient import TestClient

from app.core.config import Settings
from app.models import Base
from tests.conftest import FakeClock, OEmbedStub

FIXTURE_DIR = Path(__file__).parent / "contract_fixtures"
UPDATE = os.environ.get("UPDATE_FIXTURES") == "1"
REFRESH_HINT = "UPDATE_FIXTURES=1 pytest tests/test_contract_fixtures.py"

START = datetime(2026, 1, 5, 10, 0, 0, 123456, tzinfo=timezone.utc)  # Monday 15:00 in Asia/Karachi, non-zero micros
TODAY = "2026-01-05"
SATURDAY = "2026-01-10"
MUM, OTHER = "mum@example.com", "other@example.com"
PASSWORD = "correct-horse-battery"
PLACEHOLDER_JWT_SECRET = "contract-fixtures-placeholder-secret-0123456789"


# ------------------------------------------------------------------------------------------------ fixtures
@pytest.fixture
def settings() -> Settings:
    """Fixed settings (overrides the shared fixture so other tests' tweaks can never leak in)."""
    return Settings(
        _env_file=None, app_env="dev", jwt_secret="contract-fixtures-secret-" + "x" * 32, allow_registration=True,
        access_token_minutes=15, refresh_token_days=30, default_timezone="Asia/Karachi", pair_code_minutes=10,
        pair_max_failures=5, pair_failure_window_minutes=10, login_max_failures=5, login_failure_window_minutes=10,
        trust_proxy_headers=False, event_max_age_days=30,
    )


def _next_row_timestamp(clock: FakeClock):
    """Each row default reads the fake clock and moves it on by 1 microsecond, so rows created in one request
    (e.g. two videos in one push) get distinct, creation-ordered timestamps. Lists are ordered by (timestamp, random id)."""
    def default(ctx: object) -> datetime:
        clock.advance(microseconds=1)
        return clock()
    return default


@pytest.fixture
def frozen_model_clock(clock: FakeClock, monkeypatch: pytest.MonkeyPatch) -> None:
    """Make generated rows deterministic: timestamps (model defaults read the real clock) and primary keys (random uuid4)."""
    counter = itertools.count(1)
    for table in Base.metadata.tables.values():
        for col in table.columns:
            if col.name == "id" and col.default is not None and getattr(col.default, "is_callable", False):
                # Random primary keys decide the order of lists that sort by id (e.g. history views), so make them sequential.
                monkeypatch.setattr(col.default, "arg", lambda ctx, _n=counter: uuid.UUID(int=next(_n)))
            if col.name not in {"created_at", "updated_at", "added_at"}:
                continue
            for default in (col.default, col.onupdate):
                if default is not None and getattr(default, "is_callable", False):
                    monkeypatch.setattr(default, "arg", _next_row_timestamp(clock))
    clock.current = START


# ------------------------------------------------------------------------------------------------ normaliser
UUID_RE = re.compile(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
PLACEHOLDER_UUID_RE = re.compile(r"00000000-0000-4000-8000-\d{12}")
HEX32_RE = re.compile(r"\b[0-9a-f]{32}\b")  # snapshot_version / ETag
JWT_RE = re.compile(r"eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*")
TS_RE = re.compile(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d{6})?Z")  # the one raw format both databases must emit
SERVER_TS_KEYS = {"added_at", "paired_at", "expires_at", "revoked_at", "server_time"}
KEPT_RESPONSE_HEADERS = ("etag", "cache-control", "retry-after", "www-authenticate")


class Normaliser:
    """Maps server-generated values to stable placeholders, numbered in order of first appearance."""

    def __init__(self) -> None:
        self.uuids: dict[str, str] = {}
        self.hexes: dict[str, str] = {}
        self.timestamps: dict[str, str] = {}
        self.secrets: dict[str, str] = {}  # random values with no recognisable shape (device tokens, pair codes)

    def uuid(self, raw: str) -> str:
        return self.uuids.setdefault(raw.lower(), f"00000000-0000-4000-8000-{len(self.uuids) + 1:012d}")

    def hex(self, raw: str) -> str:
        return self.hexes.setdefault(raw, f"{len(self.hexes) + 1:032x}")

    def timestamp(self, raw: str) -> str:
        if not TS_RE.fullmatch(raw):  # a database emitting another format must be reported, not hidden
            raise AssertionError(f"server timestamp in an unexpected raw format: {raw!r}")
        if raw not in self.timestamps:
            when = datetime(2030, 1, 1) + timedelta(seconds=len(self.timestamps))
            frac = "." + "0" * (len(raw.split(".")[1]) - 1) if "." in raw else ""  # keep the fractional digit count
            self.timestamps[raw] = when.strftime("%Y-%m-%dT%H:%M:%S") + frac + "Z"
        return self.timestamps[raw]

    def jwt(self, raw: str) -> str:
        claims = jwt.decode(raw, options={"verify_signature": False, "verify_exp": False})
        for key in ("sub", "family_id"):
            claims[key] = self.uuid(claims[key])
        return jwt.encode(claims, PLACEHOLDER_JWT_SECRET, algorithm="HS256")  # valid JWT, exp unchanged

    def learn(self, kind: str, raw: str) -> None:
        if raw not in self.secrets:
            n = sum(1 for v in self.secrets.values() if v.startswith(kind)) + 1
            self.secrets[raw] = f"{kind}{n:010d}" if kind == "kidtube-device-token-placeholder-" else f"{100000 + n}"

    def text(self, s: str) -> str:
        if s in self.secrets:
            return self.secrets[s]
        if JWT_RE.fullmatch(s):
            return self.jwt(s)
        return HEX32_RE.sub(lambda m: self.hex(m.group(0)), UUID_RE.sub(lambda m: self.uuid(m.group(0)), s))

    def walk(self, value: Any, key: str | None = None, *, response: bool) -> Any:
        if isinstance(value, dict):
            return {k: self.walk(v, k, response=response) for k, v in value.items()}
        if isinstance(value, list):
            return [self.walk(v, key, response=response) for v in value]
        if isinstance(value, str):
            if key in SERVER_TS_KEYS:
                return self.timestamp(value)
            if response and TS_RE.fullmatch(value):
                raise AssertionError(f"response field {key!r} holds a timestamp the normaliser does not know: {value!r}")
            return self.text(value)
        return value


@dataclass
class RawCase:
    name: str
    method: str
    path: str
    body: Any
    request_headers: dict[str, str]
    status: int
    headers: dict[str, str]
    response: Any


def render(cases: list[RawCase]) -> dict[str, str]:
    """Normalise the recorded cases and return {filename: file text}."""
    norm = Normaliser()
    files: dict[str, str] = {}
    for i, c in enumerate(cases, start=1):
        # random values with no recognisable shape are learned from the response that first reveals them
        if c.path == "/devices/pair-code" and c.status == 201:
            norm.learn("", c.response["code"])
        if c.path == "/devices/pair" and c.status == 201:
            norm.learn("kidtube-device-token-placeholder-", c.response["device_token"])
        request: dict[str, Any] = {"method": c.method, "path": norm.text(c.path)}
        if c.body is not None:
            request["body"] = norm.walk(c.body, response=False)
        if c.request_headers:
            request["headers"] = {k: norm.text(v) for k, v in c.request_headers.items()}
        doc = {
            "name": c.name, "request": request, "status": c.status,
            "headers": {k: norm.text(v) for k, v in sorted(c.headers.items())},
            "body": norm.walk(c.response, response=True),
        }
        files[f"{i:02d}_{c.name}.json"] = json.dumps(doc, indent=2, sort_keys=True, ensure_ascii=False) + "\n"
    for name, text in files.items():
        stray = [u for u in UUID_RE.findall(text) if not PLACEHOLDER_UUID_RE.fullmatch(u)]
        assert not stray, f"{name}: un-normalised ids {stray}"
    return files


# ------------------------------------------------------------------------------------------------ recorder
class Recorder:
    def __init__(self, client: TestClient, clock: FakeClock) -> None:
        self.client, self.clock = client, clock
        self.cases: list[RawCase] = []
        self.last_headers: dict[str, str] = {}

    def call(
        self, name: str, method: str, path: str, body: Any = None, *, token: str | None = None,
        headers: dict[str, str] | None = None, expect: int, record: bool = True,
    ) -> Any:
        """One request. Time moves 1 s per call so rows created in sequence never tie on created_at/added_at."""
        self.clock.advance(seconds=1)
        sent = dict(headers or {})
        if token:
            sent["Authorization"] = f"Bearer {token}"  # sent, never recorded
        r = self.client.request(method, path, json=body, headers=sent)
        assert r.status_code == expect, f"{name}: {method} {path} -> {r.status_code}, expected {expect}: {r.text}"
        self.last_headers = {k.lower(): v for k, v in r.headers.items()}
        parsed = r.json() if r.content else None
        if record:
            assert all(c.name != name for c in self.cases), f"duplicate case name {name}"
            self.cases.append(RawCase(
                name, method, path, body,
                {k: v for k, v in (headers or {}).items() if k.lower() == "if-none-match"},
                r.status_code, {k: v for k, v in self.last_headers.items() if k in KEPT_RESPONSE_HEADERS}, parsed,
            ))
        return parsed


# ------------------------------------------------------------------------------------------------ scenario
TITLES = {
    "vid00000001": "Counting to ten", "vid00000002": "The alphabet song", "vid00000003": "Colours with Pip",
    "vid00000004": "Animal sounds", "vid00000005": "Bedtime story", "vid00000006": "Spare video",
    "vidslow0001": "Slow to verify", "vidnew00001": "Fresh from the outbox", "vidkid00001": "Sent by a kid",
}


def yt(vid: str) -> str:
    return f"https://www.youtube.com/watch?v={vid}"


def ev(child: str, type_: str, payload: dict[str, Any], n: int, local_date: str = TODAY,
       occurred_at: str = "2026-01-05T09:30:00Z") -> dict[str, Any]:
    """Event with a deterministic client id (uuid5), so the scenario input is stable."""
    return {"id": str(uuid.uuid5(uuid.NAMESPACE_URL, f"kidtube-contract/{n}")), "child_id": child, "type": type_,
            "occurred_at": occurred_at, "local_date": local_date, "payload": payload}


def session(start: str, end: str) -> dict[str, str]:
    return {"start": start, "end": end}


def run_scenario(client: TestClient, clock: FakeClock, oembed: OEmbedStub) -> list[RawCase]:
    rec = Recorder(client, clock)
    c = rec.call
    state = {"slow": True}

    def oembed_handler(request: httpx.Request) -> httpx.Response:
        vid = request.url.params["url"].split("v=")[-1]
        if vid == "vidgone0001":
            return httpx.Response(404, json={})
        if vid == "vidslow0001" and state["slow"]:
            raise httpx.ConnectTimeout("slow")
        return httpx.Response(200, json={
            "title": TITLES.get(vid, f"Title {vid}"), "type": "video",
            "thumbnail_url": f"https://i.ytimg.com/vi/{vid}/hqdefault.jpg",
        })

    oembed.handler = oembed_handler

    c("health", "GET", "/health", expect=200)

    # ---- auth
    reg = {"email": MUM, "password": PASSWORD, "family_name": "The Khans", "timezone": "Asia/Karachi"}
    c("register", "POST", "/auth/register", reg, expect=201)
    c("register_email_taken", "POST", "/auth/register", reg, expect=409)
    c("register_validation_error", "POST", "/auth/register", {"email": "not-an-email", "password": "short"}, expect=422)
    c("login_wrong_password", "POST", "/auth/login", {"email": MUM, "password": "wrong-password"}, expect=401)
    tokens = c("login", "POST", "/auth/login", {"email": MUM, "password": PASSWORD}, expect=200)
    access = tokens["access_token"]
    c("refresh", "POST", "/auth/refresh", {"refresh_token": tokens["refresh_token"]}, expect=200)
    c("refresh_invalid_token", "POST", "/auth/refresh", {"refresh_token": "not-a-token"}, expect=401)
    c("children_unauthenticated", "GET", "/children", expect=401)
    c("children_invalid_token", "GET", "/children", token="garbage", expect=401)

    # ---- children
    ali = c("child_create", "POST", "/children", {"name": "Ali", "avatar": "fox"}, token=access, expect=201)["id"]
    sara = c("child_create_with_limit", "POST", "/children", {"name": "Sara", "avatar": "owl"}, token=access, expect=201)["id"]
    omar = c("child_create_omar", "POST", "/children", {"name": "Omar"}, token=access, expect=201, record=False)["id"]
    noor = c("child_create_noor", "POST", "/children", {"name": "Noor"}, token=access, expect=201, record=False)["id"]
    pip = c("child_create_pip", "POST", "/children", {"name": "Pip"}, token=access, expect=201, record=False)["id"]
    c("children_list", "GET", "/children", token=access, expect=200)
    c("child_update", "PATCH", f"/children/{ali}", {"avatar": "lion"}, token=access, expect=200)
    c("child_validation_error", "POST", "/children", {"name": ""}, token=access, expect=422)

    # ---- videos
    v: dict[int, str] = {}
    v[1] = c("video_created", "POST", "/videos", {"url": yt("vid00000001")}, token=access, expect=201)["id"]
    c("video_existing", "POST", "/videos", {"url": yt("vid00000001")}, token=access, expect=200)
    for n in range(2, 7):
        v[n] = c(f"video_{n}", "POST", "/videos", {"url": yt(f"vid0000000{n}")}, token=access, expect=201, record=False)["id"]
    c("video_rejected_url", "POST", "/videos", {"url": "https://example.com/watch?v=vid00000001"}, token=access, expect=422)
    gone = c("video_unavailable", "POST", "/videos", {"url": yt("vidgone0001")}, token=access, expect=201)["id"]
    c("video_facebook_pending", "POST", "/videos", {"url": "https://www.facebook.com/watch/?v=1234567890123"},
      token=access, expect=201)
    c("video_reverify_still_unavailable", "POST", f"/videos/{gone}/reverify", token=access, expect=200)
    c("videos_list", "GET", "/videos", token=access, expect=200)
    c("video_deleted", "DELETE", f"/videos/{v[6]}", token=access, expect=204)

    # ---- plans
    c("plan_not_found", "GET", f"/children/{pip}/plan", token=access, expect=404)
    plan = {"pace_type": "videos_per_day", "pace_value": 1, "active_weekdays": 31, "start_date": TODAY}
    c("plan_create", "PUT", f"/children/{ali}/plan", plan, token=access, expect=201)
    c("plan_update", "PUT", f"/children/{ali}/plan", {**plan, "pace_value": 2, "max_extra_per_day": 2}, token=access, expect=200)
    c("plan_validation_error", "PUT", f"/children/{ali}/plan", {**plan, "pace_value": 0}, token=access, expect=422)
    i1 = c("plan_item_create_required", "POST", f"/children/{ali}/plan/items",
           {"video_id": v[1], "required": True, "view_target": 1}, token=access, expect=201)["id"]
    i2 = c("plan_item_create_optional", "POST", f"/children/{ali}/plan/items",
           {"video_id": v[2], "view_target": 2, "daily_cap": 1}, token=access, expect=201)["id"]
    i3 = c("plan_item_create_unlimited", "POST", f"/children/{ali}/plan/items", {"video_id": v[3]},
           token=access, expect=201, record=False)["id"]
    i4 = c("plan_item_create_anchored", "POST", f"/children/{ali}/plan/items",
           {"video_id": v[4], "anchor_date": "2026-01-12", "on_miss": "drop", "watch_threshold": 0.8},
           token=access, expect=201)["id"]
    c("plan_item_already_in_plan", "POST", f"/children/{ali}/plan/items", {"video_id": v[1]}, token=access, expect=409)
    c("plan_item_unavailable_video", "POST", f"/children/{ali}/plan/items", {"video_id": gone}, token=access, expect=422)
    c("plan_item_validation_error", "POST", f"/children/{ali}/plan/items", {"video_id": v[5], "view_target": 0},
      token=access, expect=422)
    c("plan_item_patch", "PATCH", f"/plan-items/{i2}", {"view_target": 3, "watch_threshold": 0.85}, token=access, expect=200)
    c("plan_reorder", "POST", f"/children/{ali}/plan/reorder", {"item_ids": [i1, i3, i2, i4]}, token=access, expect=200)
    c("plan_reorder_invalid", "POST", f"/children/{ali}/plan/reorder", {"item_ids": [i1, i3]}, token=access, expect=422)
    spare = c("plan_item_spare", "POST", f"/children/{ali}/plan/items", {"video_id": v[5]},
              token=access, expect=201, record=False)["id"]
    c("plan_item_deleted", "DELETE", f"/plan-items/{spare}", token=access, expect=204)
    c("plan_get", "GET", f"/children/{ali}/plan", token=access, expect=200)

    sara_plan = {"pace_type": "videos_per_day", "pace_value": 2, "active_weekdays": 127, "start_date": TODAY}
    c("sara_plan", "PUT", f"/children/{sara}/plan", sara_plan, token=access, expect=201, record=False)
    s2 = c("sara_item_2", "POST", f"/children/{sara}/plan/items", {"video_id": v[2], "view_target": 3},
           token=access, expect=201, record=False)["id"]
    s3 = c("sara_item_3", "POST", f"/children/{sara}/plan/items", {"video_id": v[3]}, token=access, expect=201, record=False)["id"]
    c("omar_plan", "PUT", f"/children/{omar}/plan", {**sara_plan, "pace_value": 1}, token=access, expect=201, record=False)
    o1 = c("omar_item", "POST", f"/children/{omar}/plan/items", {"video_id": v[1], "required": True},
           token=access, expect=201, record=False)["id"]
    c("noor_plan", "PUT", f"/children/{noor}/plan", sara_plan, token=access, expect=201, record=False)

    # ---- devices
    code = c("pair_code", "POST", "/devices/pair-code", token=access, expect=201)["code"]
    paired = c("pair", "POST", "/devices/pair", {"code": code, "device_name": "Kids tablet"}, expect=201)
    kid, kid_id = paired["device_token"], paired["device_id"]
    code_b = c("pair_code_b", "POST", "/devices/pair-code", token=access, expect=201, record=False)["code"]
    lost = c("pair_b", "POST", "/devices/pair", {"code": code_b, "device_name": "Lost phone"}, expect=201, record=False)
    c("devices_list", "GET", "/devices", token=access, expect=200)
    c("device_revoke", "POST", f"/devices/{lost['device_id']}/revoke", token=access, expect=200)
    c("revoked_device_token_rejected", "GET", "/children", token=lost["device_token"], expect=401)
    c("device_token_on_parent_endpoint", "POST", "/children", {"name": "Sneaky"}, token=kid, expect=401)
    c("device_reads_children", "GET", "/children", token=kid, expect=200)
    wrong = "000000" if code_b != "000000" else "000001"
    c("pair_wrong_code", "POST", "/devices/pair", {"code": wrong, "device_name": "Tablet"}, expect=400)
    c("pair_validation_error", "POST", "/devices/pair", {"code": "12", "device_name": "Tablet"}, expect=422)

    # ---- other family (for 404s)
    c("other_register", "POST", "/auth/register", {"email": OTHER, "password": PASSWORD}, expect=201, record=False)
    other_access = c("other_login", "POST", "/auth/login", {"email": OTHER, "password": PASSWORD},
                     expect=200, record=False)["access_token"]
    stranger = c("other_child", "POST", "/children", {"name": "Stranger"}, token=other_access, expect=201, record=False)["id"]
    c("plan_other_family_child", "GET", f"/children/{stranger}/plan", token=access, expect=404)
    c("feed_other_family_child", "GET", f"/children/{stranger}/feed", token=access, expect=404)
    c("report_other_family_child", "GET", f"/reports/children/{stranger}?range=day", token=access, expect=404)

    # ---- feed (before any events)
    c("feed_ok_locked_required", "GET", f"/children/{ali}/feed", token=access, expect=200)
    c("feed_day_off", "GET", f"/children/{ali}/feed?date={SATURDAY}", token=access, expect=200)
    c("feed_empty", "GET", f"/children/{noor}/feed", token=access, expect=200)
    c("feed_ok", "GET", f"/children/{sara}/feed", token=kid, expect=200)
    c("feed_plan_not_found", "GET", f"/children/{pip}/feed", token=access, expect=404)
    c("feed_date_preview_forbidden_for_device", "GET", f"/children/{sara}/feed?date={SATURDAY}", token=kid, expect=403)
    c("feed_bad_date", "GET", f"/children/{sara}/feed?date=yesterday", token=access, expect=422)

    # ---- events
    batch = [
        ev(ali, "view_complete", {"plan_item_id": i1, "watched_pct": 0.95}, 1),
        ev(ali, "watch_progress", {"video_id": v[1], "seconds": 95.5}, 2),
        ev(ali, "app_session", session("2026-01-05T09:00:00+05:00", "2026-01-05T09:20:00+05:00"), 3),
        ev(ali, "playback_error", {"video_id": v[2], "code": 150}, 4),
        ev(ali, "time_extension", {"seconds": 900}, 5),
        ev(ali, "view_complete", {"plan_item_id": i1}, 6),  # rejected: watched_pct missing
        ev(str(uuid.UUID(int=0xFFFF)), "watch_progress", {"video_id": v[1], "seconds": 5}, 7),  # rejected: unknown child
        ev(ali, "dance_party", {}, 8),  # rejected: unknown type
        ev(ali, "watch_progress", {"video_id": v[1], "seconds": 5}, 9, local_date="2025-01-01"),  # rejected: too old
    ]
    c("events_batch_all_types", "POST", "/events", {"events": batch}, token=access, expect=200)
    c("events_batch_duplicates", "POST", "/events", {"events": batch[:2]}, token=access, expect=200)
    c("events_batch_as_device", "POST", "/events", token=kid, expect=200, body={"events": [
        ev(sara, "watch_progress", {"video_id": v[2], "seconds": 120}, 20),
        ev(sara, "time_extension", {"seconds": 600}, 21),  # rejected: a kid device cannot extend its own time
    ]})
    c("events_validation_error", "POST", "/events", {"events": "nope"}, token=access, expect=422)

    c("feed_after_required_view", "GET", f"/children/{ali}/feed", token=access, expect=200)
    c("sara_views", "POST", "/events", {"events": [
        ev(sara, "view_complete", {"plan_item_id": s2, "watched_pct": 1.0}, 30),
        ev(sara, "view_complete", {"plan_item_id": s3, "watched_pct": 0.92}, 31),
        ev(sara, "app_session", session("2026-01-05T08:00:00+05:00", "2026-01-05T08:11:00+05:00"), 32),
    ]}, token=kid, expect=200, record=False)
    c("feed_done_today", "GET", f"/children/{sara}/feed", token=kid, expect=200)
    c("child_set_daily_limit", "PATCH", f"/children/{sara}", {"daily_app_minutes": 10}, token=access, expect=200)
    c("feed_time_up", "GET", f"/children/{sara}/feed", token=kid, expect=200)
    c("omar_view", "POST", "/events", {"events": [ev(omar, "view_complete", {"plan_item_id": o1, "watched_pct": 0.9}, 40)]},
      token=kid, expect=200, record=False)
    c("feed_all_done", "GET", f"/children/{omar}/feed", token=kid, expect=200)

    # ---- projection
    c("projection", "GET", f"/children/{ali}/projection?days=14", token=access, expect=200)
    c("projection_validation_error", "GET", f"/children/{ali}/projection?days=0", token=access, expect=422)

    # ---- sync push
    c("sync_push_mixed", "POST", "/sync/push", token=access, expect=200, body={
        "videos": [{"url": yt("vidnew00001")}, {"url": yt("vidslow0001")}, {"url": "https://example.com/nope"},
                   {"url": yt("vid00000001")}],
        "events": [
            ev(sara, "watch_progress", {"video_id": v[3], "seconds": 60}, 50),
            ev(sara, "watch_progress", {"video_id": v[3], "seconds": -1}, 51),  # rejected: negative seconds
            batch[1],  # duplicate
        ],
    })
    c("sync_push_as_kid_device", "POST", "/sync/push", token=kid, expect=200, body={
        "videos": [{"url": yt("vidkid00001")}],
        "events": [ev(sara, "watch_progress", {"video_id": v[3], "seconds": 30}, 52)],
    })
    c("sync_push_too_many_items", "POST", "/sync/push", {"videos": [{"url": yt("vid00000001")}] * 51}, token=access, expect=422)
    state["slow"] = False
    pending = [x for x in c("videos_after_push", "GET", "/videos", token=access, expect=200, record=False)
               if x["platform_video_id"] == "vidslow0001" and x["status"] == "pending"]
    assert len(pending) == 1, pending
    c("video_reverify", "POST", f"/videos/{pending[0]['id']}/reverify", token=access, expect=200)

    # ---- sync pull
    c("sync_pull", "GET", "/sync/pull", token=access, expect=200)
    etag = rec.last_headers["etag"]
    c("sync_pull_not_modified", "GET", "/sync/pull", headers={"If-None-Match": etag}, token=access, expect=304)
    c("sync_pull_as_device", "GET", "/sync/pull", headers={"If-None-Match": etag}, token=kid, expect=304)

    # ---- reports
    c("report_day", "GET", f"/reports/children/{sara}?range=day&date={TODAY}", token=access, expect=200)
    c("report_week", "GET", f"/reports/children/{ali}?range=week&date={TODAY}", token=access, expect=200)
    c("report_month", "GET", f"/reports/children/{sara}?range=month&date={TODAY}", token=access, expect=200)
    c("report_no_plan", "GET", f"/reports/children/{pip}?range=week&date={TODAY}", token=access, expect=200)
    c("report_validation_error", "GET", f"/reports/children/{sara}?range=year", token=access, expect=422)
    c("report_device_token_rejected", "GET", f"/reports/children/{sara}?range=day", token=kid, expect=401)

    # ---- rate limits (last: they lock the pairing and login limiters)
    fresh = c("pair_code_for_rate_limit", "POST", "/devices/pair-code", token=access, expect=201)["code"]
    for _ in range(4):  # the earlier wrong code was failure number one
        c("pair_wrong_again", "POST", "/devices/pair", {"code": wrong, "device_name": "Tablet"}, expect=400, record=False)
    c("pair_rate_limited", "POST", "/devices/pair", {"code": fresh, "device_name": "Tablet"}, expect=429)
    for _ in range(4):
        c("login_wrong_again", "POST", "/auth/login", {"email": MUM, "password": "nope-nope"}, expect=401, record=False)
    c("login_rate_limited", "POST", "/auth/login", {"email": MUM, "password": PASSWORD}, expect=429)
    return rec.cases


# ------------------------------------------------------------------------------------------------ tests
def _generate(client: TestClient, clock: FakeClock, oembed: OEmbedStub) -> dict[str, str]:
    return render(run_scenario(client, clock, oembed))


def test_generate_contract_fixtures(client: TestClient, clock: FakeClock, oembed: OEmbedStub, frozen_model_clock) -> None:
    files = _generate(client, clock, oembed)
    docs = [json.loads(t) for t in files.values()]
    assert {d["status"] for d in docs} >= {200, 201, 204, 304, 400, 401, 403, 404, 409, 422, 429}
    statuses = {d["name"]: d["body"]["status"] for d in docs if d["name"].startswith("feed_") and isinstance(d["body"], dict)
                and "status" in d["body"]}
    assert {"ok", "done_today", "day_off", "time_up", "all_done", "empty"} <= set(statuses.values()), statuses
    assert any(i["locked"] for d in docs if d["name"] == "feed_ok_locked_required" for i in d["body"]["items"])
    push = next(d for d in docs if d["name"] == "sync_push_mixed")["body"]
    assert any(x["status"] == "created" and x["video_status"] == "pending" and x["retryable"] for x in push["videos"])
    assert [x["status"] for x in push["events"]] == ["accepted", "rejected", "duplicate"]
    kid_push = next(d for d in docs if d["name"] == "sync_push_as_kid_device")["body"]
    assert kid_push["videos"][0]["status"] == "rejected" and kid_push["videos"][0]["reason"] == "forbidden"
    assert kid_push["events"][0]["status"] == "accepted"
    if UPDATE:
        FIXTURE_DIR.mkdir(exist_ok=True)
        for old in FIXTURE_DIR.glob("*.json"):
            old.unlink()
        for name, text in files.items():
            (FIXTURE_DIR / name).write_text(text, encoding="utf-8", newline="\n")


@pytest.mark.skipif(UPDATE, reason="refreshing fixtures")
def test_contract_fixtures_match_committed(
    client: TestClient, clock: FakeClock, oembed: OEmbedStub, frozen_model_clock
) -> None:
    files = _generate(client, clock, oembed)
    committed = {p.name: p.read_text(encoding="utf-8") for p in FIXTURE_DIR.glob("*.json")}
    problems = [f"missing: {n}" for n in files if n not in committed]
    problems += [f"stale: {n}" for n in committed if n not in files]
    problems += [f"differs: {n}" for n, t in files.items() if n in committed and committed[n] != t]
    assert not problems, (
        "The API responses no longer match the committed contract fixtures (tests/contract_fixtures).\n"
        f"Run `{REFRESH_HINT}` to refresh them, review the diff (the Android JSON models must still parse it) "
        "and commit.\n" + "\n".join(problems[:20])
    )
