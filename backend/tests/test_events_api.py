import uuid
from datetime import datetime, timezone

import pytest
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.models import Event
from tests.helpers import (
    bearer, ev, feed, feed_ids, make_child, make_family, make_plan, make_video, pair, post_events, put_plan,
    statuses, view_ev,
)


@pytest.fixture
def fam(client, engine):
    h, family_id = make_family(client)
    child = make_child(client, h)
    videos = [make_video(engine, family_id, n) for n in range(1, 4)]
    items = make_plan(client, h, child, videos, item_kw=[{"view_target": 1}] * 3)
    return {"h": h, "family_id": family_id, "child": child, "videos": videos, "items": items}


def count_events(engine) -> int:
    with Session(engine) as s:
        return s.scalar(select(func.count()).select_from(Event)) or 0


def good_events(fam) -> dict[str, dict]:
    c, v = fam["child"], fam["videos"][0]
    return {
        "view_complete": view_ev(c, fam["items"][0]),
        "watch_progress": ev(c, "watch_progress", {"video_id": v, "seconds": 42.5}),
        "app_session": ev(c, "app_session", {"start": "2026-01-05T09:00:00Z", "end": "2026-01-05T09:30:00+00:00"}),
        "playback_error": ev(c, "playback_error", {"video_id": v, "code": 150}),
        "time_extension": ev(c, "time_extension", {"seconds": 900}),
    }


def test_each_type_is_accepted_and_stored_normalised(client, fam, engine) -> None:
    events = list(good_events(fam).values())
    r = post_events(client, fam["h"], events)
    assert statuses(r) == ["accepted"] * 5
    assert [x["id"] for x in r.json()["results"]] == [e["id"] for e in events]
    assert [x["index"] for x in r.json()["results"]] == [0, 1, 2, 3, 4]
    with Session(engine) as s:
        rows = {e.type.value: e for e in s.scalars(select(Event))}
        assert rows["view_complete"].plan_item_id == uuid.UUID(fam["items"][0])
        assert rows["app_session"].payload == {"start": "2026-01-05T09:00:00+00:00", "end": "2026-01-05T09:30:00+00:00"}
        assert rows["watch_progress"].payload["seconds"] == 42.5
        assert all(e.device_id is None for e in rows.values())  # parent-submitted
        assert rows["view_complete"].occurred_at == datetime(2026, 1, 5, 10, 0, tzinfo=timezone.utc)


def test_unknown_payload_keys_are_dropped(client, fam, engine) -> None:
    e = ev(fam["child"], "time_extension", {"seconds": 60, "evil": "x" * 1000})
    assert statuses(post_events(client, fam["h"], [e])) == ["accepted"]
    with Session(engine) as s:
        assert s.scalars(select(Event.payload)).one() == {"seconds": 60}


def test_resending_a_batch_is_idempotent(client, fam, engine) -> None:
    events = [view_ev(fam["child"], fam["items"][0]), view_ev(fam["child"], fam["items"][1]),
              ev(fam["child"], "watch_progress", {"video_id": fam["videos"][0], "seconds": 10})]
    assert statuses(post_events(client, fam["h"], events)) == ["accepted"] * 3
    before = feed(client, fam["h"], fam["child"]).json()
    again = post_events(client, fam["h"], events)
    assert statuses(again) == ["duplicate"] * 3 and count_events(engine) == 3
    assert feed(client, fam["h"], fam["child"]).json() == before  # nothing double-counts


def test_duplicate_inside_one_batch_and_partial_resend(client, fam, engine) -> None:
    a, b = view_ev(fam["child"], fam["items"][0]), view_ev(fam["child"], fam["items"][1])
    assert statuses(post_events(client, fam["h"], [a, a])) == ["accepted", "duplicate"]
    assert statuses(post_events(client, fam["h"], [a, b])) == ["duplicate", "accepted"]
    assert count_events(engine) == 2


def test_a_rejected_event_never_blocks_valid_ones(client, fam, engine) -> None:
    bad1 = {**view_ev(fam["child"], fam["items"][0]), "type": "nope"}
    good = view_ev(fam["child"], fam["items"][1])
    bad2 = "not even an object"
    bad3 = {**view_ev(fam["child"], fam["items"][2]), "id": "not-a-uuid"}
    r = post_events(client, fam["h"], [bad1, good, bad2, bad3])
    assert statuses(r) == ["rejected", "accepted", "rejected", "rejected"]
    results = r.json()["results"]
    assert results[0]["reason"] == "unknown type" and results[1]["reason"] is None
    assert results[2]["id"] is None and results[3]["id"] == "not-a-uuid"
    assert count_events(engine) == 1
    # the rejected event can be fixed and resent
    fixed = {**bad1, "type": "view_complete"}
    assert statuses(post_events(client, fam["h"], [fixed])) == ["accepted"]


def mutate(base: dict, **changes) -> dict:
    out = {**base}
    for key, value in changes.items():
        if key.startswith("payload."):
            payload = {k: v for k, v in out["payload"].items() if k != key[8:]}
            if value is not ...:
                payload[key[8:]] = value
            out["payload"] = payload
        elif value is ...:
            out.pop(key, None)
        else:
            out[key] = value
    return out


def rejected(client, fam, event: dict) -> str:
    r = post_events(client, fam["h"], [event])
    assert statuses(r) == ["rejected"], r.json()
    return r.json()["results"][0]["reason"]


@pytest.mark.parametrize(
    "kind,changes,reason",
    [
        ("view_complete", {"payload.plan_item_id": "nope"}, "invalid payload.plan_item_id"),
        ("view_complete", {"payload.plan_item_id": ...}, "invalid payload.plan_item_id"),
        ("view_complete", {"payload.watched_pct": 1.01}, "payload.watched_pct out of range"),
        ("view_complete", {"payload.watched_pct": -0.1}, "payload.watched_pct out of range"),
        ("view_complete", {"payload.watched_pct": "0.9"}, "invalid payload.watched_pct"),
        ("view_complete", {"payload.watched_pct": True}, "invalid payload.watched_pct"),
        ("view_complete", {"payload.watched_pct": ...}, "invalid payload.watched_pct"),
        ("watch_progress", {"payload.seconds": -1}, "payload.seconds out of range"),
        ("watch_progress", {"payload.seconds": 90000}, "payload.seconds out of range"),
        ("watch_progress", {"payload.seconds": "10"}, "invalid payload.seconds"),
        ("watch_progress", {"payload.video_id": "x"}, "invalid payload.video_id"),
        ("app_session", {"payload.end": "2026-01-05T08:00:00Z"}, "payload.end is before payload.start"),
        ("app_session", {"payload.end": "2026-01-06T09:00:01Z"}, "session longer than 24 hours"),
        ("app_session", {"payload.start": "2026-01-05T09:00:00"}, "payload.start must include a timezone offset"),
        ("app_session", {"payload.start": "garbage"}, "invalid payload.start"),
        ("app_session", {"payload.end": ...}, "invalid payload.end"),
        ("playback_error", {"payload.code": ...}, "invalid payload.code"),
        ("playback_error", {"payload.code": ""}, "invalid payload.code"),
        ("playback_error", {"payload.code": True}, "invalid payload.code"),
        ("playback_error", {"payload.code": ["a"]}, "invalid payload.code"),
        ("playback_error", {"payload.video_id": ...}, "invalid payload.video_id"),
        ("time_extension", {"payload.seconds": 0}, "payload.seconds out of range"),
        ("time_extension", {"payload.seconds": -5}, "payload.seconds out of range"),
        ("view_complete", {"payload": []}, "payload must be an object"),
        ("view_complete", {"payload": None}, "payload must be an object"),
        ("view_complete", {"occurred_at": "2026-01-05T10:00:00"}, "occurred_at must include a timezone offset"),
        ("view_complete", {"occurred_at": "yesterday"}, "invalid occurred_at"),
        ("view_complete", {"occurred_at": ...}, "invalid occurred_at"),
        ("view_complete", {"local_date": "2026-1-5"}, "invalid local_date"),
        ("view_complete", {"local_date": "2026-02-30"}, "invalid local_date"),
        ("view_complete", {"local_date": ...}, "invalid local_date"),
        ("view_complete", {"local_date": "2025-12-05"}, "local_date outside the accepted range"),
        ("view_complete", {"local_date": "2026-01-07"}, "local_date outside the accepted range"),
        ("view_complete", {"type": "bogus"}, "unknown type"),
        ("view_complete", {"type": 5}, "unknown type"),
        ("view_complete", {"type": ...}, "unknown type"),
        ("view_complete", {"id": ...}, "invalid id"),
        ("view_complete", {"id": 123}, "invalid id"),
        ("view_complete", {"child_id": str(uuid.uuid4())}, "unknown child"),
        ("view_complete", {"child_id": "junk"}, "invalid child_id"),
    ],
)
def test_validation_rejects(client, fam, kind, changes, reason) -> None:
    changes = {k: v for k, v in changes.items()}
    base = good_events(fam)[kind]
    if "payload" in changes and changes["payload"] is None:
        event = {**base, "payload": None}
    else:
        event = mutate(base, **changes)
    assert rejected(client, fam, event) == reason


def test_non_finite_numbers_are_rejected(client, fam) -> None:
    import json

    e = view_ev(fam["child"], fam["items"][0])
    raw = json.dumps({"events": [{**e, "payload": {"plan_item_id": fam["items"][0], "watched_pct": 0}}]})
    raw = raw.replace('"watched_pct": 0', '"watched_pct": NaN')
    r = client.post("/events", content=raw, headers={**fam["h"], "Content-Type": "application/json"})
    assert statuses(r) == ["rejected"] and r.json()["results"][0]["reason"] == "invalid payload.watched_pct"


def test_local_date_window_boundaries(client, fam) -> None:
    # fake "now" is 2026-01-05 10:00 UTC = 15:00 in Karachi, so today = 2026-01-05.
    # Default EVENT_MAX_AGE_DAYS=30: accepted range is [2025-12-06, 2026-01-06].
    ok = [view_ev(fam["child"], fam["items"][0], d) for d in ("2025-12-06", "2026-01-06", "2026-01-05")]
    assert statuses(post_events(client, fam["h"], ok)) == ["accepted"] * 3  # 30 days back is still accepted
    bad = [view_ev(fam["child"], fam["items"][0], d) for d in ("2025-12-05", "2026-01-07")]
    assert statuses(post_events(client, fam["h"], bad)) == ["rejected"] * 2  # 31 days back is rejected


def test_local_date_window_is_configurable(app, client, fam) -> None:
    app.state.settings.event_max_age_days = 3
    days = ("2026-01-02", "2026-01-01")
    r = post_events(client, fam["h"], [view_ev(fam["child"], fam["items"][0], d) for d in days])
    assert statuses(r) == ["accepted", "rejected"]


def test_local_date_window_uses_the_family_timezone(client, engine, clock) -> None:
    clock.current = datetime(2026, 11, 5, 20, 0, tzinfo=timezone.utc)  # 2026-11-06 01:00 in Karachi
    h, family_id = make_family(client, "tz@example.com")
    child = make_child(client, h)
    base = lambda d: ev(child, "watch_progress", {"video_id": str(uuid.uuid4()), "seconds": 1}, d)  # noqa: E731
    r = post_events(client, h, [base(d) for d in ("2026-10-07", "2026-10-06", "2026-11-07", "2026-11-08")])
    assert statuses(r) == ["accepted", "rejected", "accepted", "rejected"]  # window is centred on the Karachi date Nov 6


def test_batch_size_limit(client, fam, engine) -> None:
    c = fam["child"]
    events = [ev(c, "watch_progress", {"video_id": fam["videos"][0], "seconds": 1}) for _ in range(200)]
    r = post_events(client, fam["h"], events)
    assert statuses(r) == ["accepted"] * 200 and count_events(engine) == 200
    too_many = events + [ev(c, "watch_progress", {"video_id": fam["videos"][0], "seconds": 1})]
    assert post_events(client, fam["h"], too_many).status_code == 422
    assert post_events(client, fam["h"], []).json() == {"results": []}
    assert client.post("/events", json={}, headers=fam["h"]).status_code == 422
    assert client.post("/events", json={"events": "x"}, headers=fam["h"]).status_code == 422


def test_time_extension_is_parent_only(client, fam, engine) -> None:
    kid = bearer(pair(client, fam["h"])["device_token"])
    e = ev(fam["child"], "time_extension", {"seconds": 900})
    r = post_events(client, kid, [e])
    assert statuses(r) == ["rejected"] and "parent" in r.json()["results"][0]["reason"]
    assert count_events(engine) == 0
    assert statuses(post_events(client, fam["h"], [e])) == ["accepted"]  # the same event id is still free for the parent


def test_device_can_write_ordinary_events_for_any_child_in_the_family(client, fam, engine) -> None:
    sibling = make_child(client, fam["h"], "Sara")
    kid = bearer(pair(client, fam["h"])["device_token"])
    r = post_events(client, kid, [
        view_ev(fam["child"], fam["items"][0]),
        ev(sibling, "watch_progress", {"video_id": fam["videos"][0], "seconds": 5}),
        ev(sibling, "app_session", {"start": "2026-01-05T09:00:00Z", "end": "2026-01-05T09:10:00Z"}),
        ev(fam["child"], "playback_error", {"video_id": fam["videos"][0], "code": "101"}),
    ])
    assert statuses(r) == ["accepted"] * 4
    with Session(engine) as s:
        assert {e.device_id for e in s.scalars(select(Event))} != {None}
        assert len({e.device_id for e in s.scalars(select(Event))}) == 1


def test_cross_family_events_are_rejected_without_a_trace(client, engine, fam) -> None:
    other_h, _ = make_family(client, "b@example.com")
    other_kid = bearer(pair(client, other_h)["device_token"])
    mine = view_ev(fam["child"], fam["items"][0])
    for headers in (other_h, other_kid):
        r = post_events(client, headers, [mine])
        assert statuses(r) == ["rejected"] and r.json()["results"][0]["reason"] == "unknown child"
    assert count_events(engine) == 0
    assert feed_ids(feed(client, fam["h"], fam["child"]))  # my child's feed is untouched


def test_auth_required_and_revoked_device_rejected(client, fam) -> None:
    e = view_ev(fam["child"], fam["items"][0])
    assert client.post("/events", json={"events": [e]}).status_code == 401
    assert client.post("/events", json={"events": [e]}, headers=bearer("junk")).status_code == 401
    paired = pair(client, fam["h"])
    kid = bearer(paired["device_token"])
    assert statuses(post_events(client, kid, [e])) == ["accepted"]
    client.post(f"/devices/{paired['device_id']}/revoke", headers=fam["h"])
    assert post_events(client, kid, [view_ev(fam["child"], fam["items"][1])]).status_code == 401


def test_parent_access_token_is_required_to_be_an_access_token(client, fam) -> None:
    from tests.helpers import login

    refresh = login(client, "a@example.com")["refresh_token"]
    assert post_events(client, bearer(refresh), []).status_code == 401
