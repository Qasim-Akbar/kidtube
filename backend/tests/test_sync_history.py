"""Per-child event history in GET /sync/pull (what an offline device needs to run the plan engine).

Fake "now" is Mon 2026-01-05 10:00 UTC = 15:00 Karachi, so the family-local today is 2026-01-05.
The seconds window is 30 family-local days: today and the 29 before it, i.e. 2025-12-07 .. 2026-01-05.
Events are accepted up to 30 days old (EVENT_MAX_AGE_DAYS), so 2025-12-06 is accepted by POST /events but is
outside the history window, while views are all-time.
"""

import uuid
from datetime import date, datetime, timedelta, timezone

import pytest
from sqlalchemy import event as sa_event
from sqlalchemy.orm import Session

from app.models import Event
from app.models.enums import EventType
from app.services.engine_input import load_histories
from tests.helpers import (
    bearer, ev, make_child, make_family, make_plan, make_video, pair, post_events, relogin, statuses, view_ev,
)


def pull(client, headers, **h):
    return client.get("/sync/pull", headers={**headers, **h})


def history_of(response, child_id: str) -> dict:
    return next(c for c in response.json()["children"] if c["id"] == child_id)["history"]


def secs(rows) -> list[tuple[str, int]]:
    return [(r["local_date"], r["seconds"]) for r in rows]


def views(rows) -> set[tuple[str, str, int]]:
    return {(r["plan_item_id"], r["local_date"], r["count"]) for r in rows}


def watch(child, video, seconds, d):
    return ev(child, "watch_progress", {"video_id": video, "seconds": seconds}, d)


def session(child, d, seconds):
    start = datetime.fromisoformat(d).replace(hour=9, tzinfo=timezone.utc)
    return ev(child, "app_session", {"start": start.isoformat(), "end": (start + timedelta(seconds=seconds)).isoformat()}, d)


@pytest.fixture
def fam(client, engine):
    h, family_id = make_family(client)
    v = [make_video(engine, family_id, n) for n in (1, 2, 3)]
    ali, sara = make_child(client, h, "Ali"), make_child(client, h, "Sara")
    a = make_plan(client, h, ali, v[:2])          # a[0] -> v[0], a[1] -> v[1]
    s = make_plan(client, h, sara, [v[0], v[2]])  # s[0] -> v[0], s[1] -> v[2]
    events = [
        # ---- Ali
        view_ev(ali, a[0], "2025-12-06"),                                     # 30 days old: accepted, views are all-time
        view_ev(ali, a[0], "2026-01-03"), view_ev(ali, a[0], "2026-01-03"), view_ev(ali, a[0], "2026-01-05"),
        view_ev(ali, a[1], "2026-01-04"),
        watch(ali, v[0], 10.4, "2026-01-05"), watch(ali, v[0], 20.4, "2026-01-05"),   # 30.8 -> 31
        watch(ali, v[0], 100, "2026-01-03"),
        watch(ali, v[0], 7, "2025-12-07"),                                    # 29 days old: the first day inside the window
        watch(ali, v[0], 999, "2025-12-06"),                                  # 30 days old: accepted but outside the window
        session(ali, "2026-01-05", 600), session(ali, "2026-01-05", 330),    # 930
        session(ali, "2026-01-04", 3600),
        ev(ali, "time_extension", {"seconds": 900}, "2026-01-05"), ev(ali, "time_extension", {"seconds": 300}, "2026-01-05"),
        ev(ali, "time_extension", {"seconds": 5000}, "2026-01-04"),
        ev(ali, "playback_error", {"video_id": v[1], "code": 150}, "2026-01-05"),  # not part of the history
        # ---- Sara
        view_ev(sara, s[0], "2026-01-05"),
        watch(sara, v[0], 60, "2026-01-05"),
        # Sara's event that points at ALI's plan item: accepted by POST /events, but it must not count for anyone's history
        view_ev(sara, a[0], "2026-01-05"),
    ]
    assert set(statuses(post_events(client, h, events))) == {"accepted"}
    return {"h": h, "family_id": family_id, "v": v, "ali": ali, "sara": sara, "a": a, "s": s}


def test_ali_history_with_hand_computed_numbers(client, fam) -> None:
    hist = history_of(pull(client, fam["h"]), fam["ali"])
    a = fam["a"]
    assert views(hist["views"]) == {
        (a[0], "2025-12-06", 1), (a[0], "2026-01-03", 2), (a[0], "2026-01-05", 1), (a[1], "2026-01-04", 1),
    }
    assert secs(hist["watch_seconds"]) == [("2025-12-07", 7), ("2026-01-03", 100), ("2026-01-05", 31)]  # 999 on 12-06 excluded
    assert secs(hist["app_seconds"]) == [("2026-01-04", 3600), ("2026-01-05", 930)]
    assert secs(hist["time_extension_seconds"]) == [("2026-01-04", 5000), ("2026-01-05", 1200)]
    assert set(hist) == {"views", "watch_seconds", "app_seconds", "time_extension_seconds"}


def test_children_are_independent(client, fam) -> None:
    body = pull(client, fam["h"])
    sara = history_of(body, fam["sara"])
    assert views(sara["views"]) == {(fam["s"][0], "2026-01-05", 1)}  # the event aimed at Ali's item is not hers
    assert secs(sara["watch_seconds"]) == [("2026-01-05", 60)]
    assert sara["app_seconds"] == [] and sara["time_extension_seconds"] == []
    ali = history_of(body, fam["ali"])
    assert (fam["a"][0], "2026-01-05", 1) in views(ali["views"])  # Ali's count of 1 on 01-05 is unaffected by Sara's event
    assert sum(r["count"] for r in ali["views"]) == 5


def test_views_only_for_items_still_in_the_plan(client, fam) -> None:
    assert client.delete(f"/plan-items/{fam['a'][1]}", headers=fam["h"]).status_code == 204
    hist = history_of(pull(client, fam["h"]), fam["ali"])
    assert fam["a"][1] not in {r["plan_item_id"] for r in hist["views"]}
    assert sum(r["count"] for r in hist["views"]) == 4
    # deleting a whole video takes its items (and their views) away as well
    assert client.delete(f"/videos/{fam['v'][0]}", headers=fam["h"]).status_code == 204
    after = pull(client, fam["h"]).json()
    assert next(c for c in after["children"] if c["id"] == fam["ali"])["history"]["views"] == []
    assert next(c for c in after["children"] if c["id"] == fam["sara"])["history"]["views"] == []


@pytest.mark.parametrize("days, included", [(29, True), (30, False), (31, False)])
def test_seconds_window_is_30_days_but_views_stay_all_time(client, fam, clock, days, included) -> None:
    clock.advance(days=days)  # Jan 5 is now `days` days old (same Karachi time of day)
    h = relogin(client)
    hist = history_of(pull(client, h), fam["ali"])
    # the Jan 5 rows are in the window only up to 29 days old; everything older than Jan 5 is out of it already
    assert secs(hist["watch_seconds"]) == ([("2026-01-05", 31)] if included else [])
    assert secs(hist["app_seconds"]) == ([("2026-01-05", 930)] if included else [])
    assert secs(hist["time_extension_seconds"]) == ([("2026-01-05", 1200)] if included else [])
    assert sum(r["count"] for r in hist["views"]) == 5  # views are all-time, including the 2025-12-06 one
    assert (fam["a"][0], "2025-12-06", 1) in views(hist["views"])


def test_etag_changes_when_events_arrive_and_not_otherwise(client, fam) -> None:
    h, ali = fam["h"], fam["ali"]
    etag = pull(client, h).headers["etag"]
    assert pull(client, h, **{"If-None-Match": etag}).status_code == 304

    # resending events that are already stored (duplicates) and events outside every history change nothing
    dup = view_ev(ali, fam["a"][0], "2026-01-05", id=str(uuid.uuid4()))
    assert statuses(post_events(client, h, [dup])) == ["accepted"]          # a new view -> changes (checked below)
    changed = pull(client, h).headers["etag"]
    assert changed != etag
    assert statuses(post_events(client, h, [dup])) == ["duplicate"]
    assert pull(client, h).headers["etag"] == changed
    assert statuses(post_events(client, h, [ev(ali, "playback_error", {"video_id": fam["v"][0], "code": 101})])) == ["accepted"]
    assert pull(client, h).headers["etag"] == changed                        # playback errors are not in the snapshot

    seen = {etag, changed}
    for event in (
        watch(ali, fam["v"][0], 5, "2026-01-05"),
        session(ali, "2026-01-02", 60),
        ev(ali, "time_extension", {"seconds": 60}, "2026-01-05"),
        view_ev(fam["sara"], fam["s"][1], "2026-01-04"),                      # the sibling's event changes the family snapshot too
    ):
        assert statuses(post_events(client, h, [event])) == ["accepted"]
        now = pull(client, h).headers["etag"]
        assert now not in seen
        seen.add(now)


def test_a_sibling_event_changes_only_the_siblings_history(client, fam) -> None:
    before = pull(client, fam["h"]).json()
    post_events(client, fam["h"], [watch(fam["sara"], fam["v"][0], 15, "2026-01-05")])
    after = pull(client, fam["h"]).json()
    assert before["snapshot_version"] != after["snapshot_version"]
    ali_before = next(c for c in before["children"] if c["id"] == fam["ali"])
    ali_after = next(c for c in after["children"] if c["id"] == fam["ali"])
    assert ali_before == ali_after
    assert secs(history_of(pull(client, fam["h"]), fam["sara"])["watch_seconds"]) == [("2026-01-05", 75)]


def test_a_kid_device_can_pull_the_history(client, fam) -> None:
    kid = bearer(pair(client, fam["h"])["device_token"])
    parent_view, kid_view = pull(client, fam["h"]), pull(client, kid)
    assert kid_view.status_code == 200 and kid_view.json() == parent_view.json()
    assert history_of(kid_view, fam["ali"])["time_extension_seconds"]  # includes the parent-granted extension
    # events written by the device show up in the next pull and change the version
    etag = kid_view.headers["etag"]
    assert statuses(post_events(client, kid, [watch(fam["ali"], fam["v"][0], 9, "2026-01-05")])) == ["accepted"]
    refreshed = pull(client, kid)
    assert refreshed.headers["etag"] != etag
    assert secs(history_of(refreshed, fam["ali"])["watch_seconds"])[-1] == ("2026-01-05", 40)


def test_cross_family_isolation(client, fam) -> None:
    other, other_family = make_family(client, "b@example.com")
    other_child = make_child(client, other, "Omar")
    mine_before = pull(client, fam["h"]).json()
    theirs = pull(client, other).json()
    assert [c["id"] for c in theirs["children"]] == [other_child]
    assert theirs["children"][0]["history"] == {"views": [], "watch_seconds": [], "app_seconds": [], "time_extension_seconds": []}
    # the other family cannot write into my child's history, and its own events stay out of mine
    assert statuses(post_events(client, other, [watch(fam["ali"], fam["v"][0], 500, "2026-01-05")])) == ["rejected"]
    assert statuses(post_events(client, other, [watch(other_child, fam["v"][0], 500, "2026-01-05")])) == ["accepted"]
    assert pull(client, fam["h"]).json() == mine_before
    assert secs(history_of(pull(client, other), other_child)["watch_seconds"]) == [("2026-01-05", 500)]
    kid = bearer(pair(client, other)["device_token"])
    assert [c["id"] for c in pull(client, kid).json()["children"]] == [other_child]


def test_feed_and_history_come_from_the_same_loader(client, fam) -> None:
    """Ali's feed counts one view today; the history says the same, and the loader's numbers drive both."""
    feed = client.get(f"/children/{fam['ali']}/feed", headers=fam["h"]).json()
    hist = history_of(pull(client, fam["h"]), fam["ali"])
    assert feed["views_today"] == sum(r["count"] for r in hist["views"] if r["local_date"] == "2026-01-05") == 1


def test_query_count_is_constant_with_600_events(client, fam, engine) -> None:
    statements: list[str] = []
    sa_event.listen(engine, "before_cursor_execute", lambda conn, cur, stmt, *a: statements.append(stmt))

    def count() -> int:
        statements.clear()
        assert pull(client, fam["h"]).status_code == 200
        return len(statements)

    base = count()
    rows = []
    for n in range(150):
        child = uuid.UUID(fam["ali"] if n % 2 else fam["sara"])
        item = uuid.UUID(fam["a"][n % 2] if n % 2 else fam["s"][n % 2])
        d = date(2026, 1, 1 + n % 5)
        t = datetime(2026, 1, 1, 9, tzinfo=timezone.utc)
        rows += [
            Event(id=uuid.uuid4(), child_id=child, type=EventType.view_complete, local_date=d, plan_item_id=item, payload={}, occurred_at=t),
            Event(id=uuid.uuid4(), child_id=child, type=EventType.watch_progress, local_date=d, payload={"video_id": str(uuid.uuid4()), "seconds": 3}, occurred_at=t),
            Event(id=uuid.uuid4(), child_id=child, type=EventType.time_extension, local_date=d, payload={"seconds": 60}, occurred_at=t),
            Event(id=uuid.uuid4(), child_id=child, type=EventType.app_session, local_date=d, payload={"start": "2026-01-01T09:00:00+00:00", "end": "2026-01-01T09:01:00+00:00"}, occurred_at=t),
        ]
    with Session(engine) as s:
        s.add_all(rows)
        s.commit()
    assert count() == base
    body = pull(client, fam["h"])
    assert sum(r["count"] for r in history_of(body, fam["ali"])["views"]) == 5 + 75  # 75 of the 150 rows were Ali's views
    assert sum(r["seconds"] for r in history_of(body, fam["ali"])["time_extension_seconds"]) == 6200 + 75 * 60


def test_load_histories_directly(session: Session) -> None:
    """The shared loader without HTTP: several children in one call, an item of another child, a future-dated event."""
    from tests.test_engine_input import add_event, build, view

    today = date(2026, 1, 7)
    _, ali, _, ali_items = build(session)
    sara = type(ali)(family_id=ali.family_id, name="Sara")
    session.add(sara)
    session.flush()
    view(session, ali, ali_items[0], date(2025, 6, 1))     # months old: views are all-time
    view(session, ali, ali_items[0], date(2026, 1, 8))     # after today: not loaded
    view(session, sara, ali_items[0], today)               # Sara pointing at Ali's item: not hers
    add_event(session, ali, EventType.watch_progress, today - timedelta(days=29), {"video_id": str(uuid.uuid4()), "seconds": 2.6})
    add_event(session, ali, EventType.watch_progress, today - timedelta(days=30), {"video_id": str(uuid.uuid4()), "seconds": 50})
    add_event(session, sara, EventType.time_extension, today, {"seconds": 120})
    session.commit()
    result = load_histories(session, [ali.id, sara.id], today)
    assert result[ali.id].views == [(ali_items[0].id, date(2025, 6, 1), 1)]
    assert result[ali.id].watch_seconds == [(today - timedelta(days=29), 3)]
    assert result[sara.id].views == [] and result[sara.id].watch_seconds == []
    assert result[sara.id].time_extension_seconds == [(today, 120)]
    assert load_histories(session, [], today) == {}
