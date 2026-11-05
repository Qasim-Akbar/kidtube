"""Reports. The fixture's numbers are worked out by hand in the comments, not read back from the code.

Fake "now" is Mon 2026-01-05 10:00 UTC = 15:00 Karachi, so family-local today = Mon 2026-01-05.
Weeks run Mon..Sun; the week of Thu 2026-01-01 is Mon 2025-12-29 .. Sun 2026-01-04 (it crosses the year/month boundary).
"""

import uuid
from datetime import date

import pytest
from sqlalchemy import event as sa_event
from sqlalchemy.orm import Session

from app.models import Event
from app.models.enums import EventType
from tests.helpers import (
    add_item, bearer, ev, make_child, make_family, make_plan, make_video, pair, post_events, put_plan, statuses,
    view_ev,
)

MON_TO_FRI = 0b0011111


def sess(child, local_date, minutes_from, seconds):
    """An app_session starting 09:00 UTC on `local_date` and lasting `seconds`."""
    from datetime import datetime, timedelta, timezone

    start = datetime.fromisoformat(local_date).replace(hour=9, tzinfo=timezone.utc) + timedelta(minutes=minutes_from)
    end = start + timedelta(seconds=seconds)
    return ev(child, "app_session", {"start": start.isoformat(), "end": end.isoformat()}, local_date)


def watch(child, video, seconds, local_date):
    return ev(child, "watch_progress", {"video_id": video, "seconds": seconds}, local_date)


def error(child, video, code, local_date):
    return ev(child, "playback_error", {"video_id": video, "code": code}, local_date)


@pytest.fixture
def fam(client, engine):
    h, family_id = make_family(client)
    v = [make_video(engine, family_id, n) for n in range(1, 5)]  # v[0]..v[3]
    ali = make_child(client, h, "Ali")
    sara = make_child(client, h, "Sara")

    # Ali: 2 videos/day, Mon-Fri, from Mon 2025-12-29
    a = make_plan(
        client, h, ali, v,
        plan_kw={"pace_value": 2, "active_weekdays": MON_TO_FRI, "start_date": "2025-12-29"},
        item_kw=[{"required": True, "view_target": 2}, {"view_target": 1}, {}, {"view_target": 3}],
    )
    # Sara: 10 minutes/day, every day, from Thu 2026-01-01
    s = make_plan(
        client, h, sara, v[:2],
        plan_kw={"pace_type": "minutes_per_day", "pace_value": 10, "active_weekdays": 127, "start_date": "2026-01-01"},
        item_kw=[{"view_target": 3}, {"view_target": 2}],
    )
    events = [
        # ---- Ali
        # Mon 12-29: views i1, i2 ; watch v1 60+40, v2 50.4 ; app 600 s
        view_ev(ali, a[0], "2025-12-29"), view_ev(ali, a[1], "2025-12-29"),
        watch(ali, v[0], 60, "2025-12-29"), watch(ali, v[0], 40, "2025-12-29"), watch(ali, v[1], 50.4, "2025-12-29"),
        sess(ali, "2025-12-29", 0, 400), sess(ali, "2025-12-29", 30, 200),
        # Tue 12-30: view i1 ; watch v1 30 ; app 300 ; error v2 150 (int) x2
        view_ev(ali, a[0], "2025-12-30"), watch(ali, v[0], 30, "2025-12-30"), sess(ali, "2025-12-30", 0, 300),
        error(ali, v[1], 150, "2025-12-30"), error(ali, v[1], 150, "2025-12-30"),
        # Wed 12-31: only an error v2 "150" (str; same code as the int) and a different one on Thu
        error(ali, v[1], "150", "2025-12-31"),
        # Thu 01-01: views i3 x2 ; watch v3 200 ; app 100 (less than watch, so browsing = 0) ; error v2 "101"
        view_ev(ali, a[2], "2026-01-01"), view_ev(ali, a[2], "2026-01-01"), watch(ali, v[2], 200, "2026-01-01"),
        sess(ali, "2026-01-01", 0, 100), error(ali, v[1], "101", "2026-01-01"),
        # Sat 01-03: view i3
        view_ev(ali, a[2], "2026-01-03"),
        # Mon 01-05 (today): watch v1 10 ; app 60 ; error v3 150
        watch(ali, v[0], 10, "2026-01-05"), sess(ali, "2026-01-05", 0, 60), error(ali, v[2], 150, "2026-01-05"),
        # ---- Sara
        view_ev(sara, s[0], "2026-01-01"), watch(sara, v[0], 120, "2026-01-01"), sess(sara, "2026-01-01", 0, 600),
        view_ev(sara, s[0], "2026-01-02"), view_ev(sara, s[0], "2026-01-02"),
        sess(sara, "2026-01-03", 0, 30),
    ]
    assert all(x == "accepted" for x in statuses(post_events(client, h, events)))
    return {"h": h, "family_id": family_id, "ali": ali, "sara": sara, "v": v, "a": a, "s": s}


def report(client, fam, child, range_, on=None, headers=None):
    params = {"range": range_, **({"date": on} if on else {})}
    return client.get(f"/reports/children/{fam[child]}", params=params, headers=headers or fam["h"])


def day_map(body):
    return {d["date"]: d for d in body["days"]}


def test_week_for_ali_crossing_the_year_boundary(client, fam) -> None:
    r = report(client, fam, "ali", "week", "2026-01-01")  # a Thursday
    assert r.status_code == 200, r.text
    b = r.json()
    assert (b["range"], b["date"], b["start"], b["end"]) == ("week", "2026-01-01", "2025-12-29", "2026-01-04")
    assert b["child_id"] == fam["ali"]
    assert [d["date"] for d in b["days"]] == [
        "2025-12-29", "2025-12-30", "2025-12-31", "2026-01-01", "2026-01-02", "2026-01-03", "2026-01-04"
    ]
    cols = lambda k: [d[k] for d in b["days"]]  # noqa: E731
    # watch: Mon 60+40+50.4=150.4 -> 150, Tue 30, Thu 200
    assert cols("watch_seconds") == [150, 30, 0, 200, 0, 0, 0]
    assert cols("app_seconds") == [600, 300, 0, 100, 0, 0, 0]
    assert cols("browsing_seconds") == [450, 270, 0, 0, 0, 0, 0]  # Thursday: 100 - 200 clamps to 0
    assert cols("views_completed") == [2, 1, 0, 2, 0, 1, 0]
    assert b["totals"] == {
        "watch_seconds": 380, "app_seconds": 1000,
        "browsing_seconds": 620,  # max(0, 1000 - 380); the daily values sum to 720
        "views_completed": 6,
    }

    # top videos: v3 has 3 views (200 s), v1 2 views (130 s), v2 1 view (50 s); v4 has no activity
    assert [(t["video_id"], t["views"], t["watch_seconds"]) for t in b["top_videos"]] == [
        (fam["v"][2], 3, 200), (fam["v"][0], 2, 130), (fam["v"][1], 1, 50)
    ]
    assert b["top_videos"][0]["title"] == "Video 3" and b["top_videos"][0]["thumbnail_url"].endswith("hqdefault.jpg")

    # playback errors: v2 code 150 three times (int and str merge), then 101
    assert [(e["video_id"], e["code"], e["count"], e["title"]) for e in b["playback_errors"]] == [
        (fam["v"][1], "150", 3, "Video 2"), (fam["v"][1], "101", 1, "Video 2")
    ]

    # per-item completion (all-time views up to today, not just the range)
    per = {p["video_id"]: p for p in b["per_video"]}
    assert len(per) == 4
    assert (per[fam["v"][0]]["views_total"], per[fam["v"][0]]["view_target"], per[fam["v"][0]]["completed"]) == (2, 2, True)
    assert (per[fam["v"][1]]["views_total"], per[fam["v"][1]]["completed"]) == (1, True)
    assert (per[fam["v"][2]]["views_total"], per[fam["v"][2]]["completed"], per[fam["v"][2]]["view_target"]) == (3, False, None)  # unlimited
    assert (per[fam["v"][3]]["views_total"], per[fam["v"][3]]["completed"]) == (0, False)
    assert [per[fam["v"][i]]["views_in_range"] for i in range(4)] == [2, 1, 3, 0]
    assert per[fam["v"][0]]["required"] is True

    # planned vs watched: pace 2 x 5 active days (Mon..Fri) = 10 expected; the weekend view still counts as actual
    assert b["planned_vs_watched"] == {
        "pace_type": "videos_per_day", "unit": "videos", "window_start": "2025-12-29", "window_end": "2026-01-04",
        "active_days": 5, "expected": 10, "actual": 6,
    }
    # progress: only i4 (target 3) is unfinished; quota 2/day -> 2 views today (Mon 1-05), 1 on Tue 1-06
    assert b["plan_progress"] == {"as_of": "2026-01-05", "finish_date": "2026-01-06", "remaining_finite_views": 3}


def test_week_starts_on_monday_for_every_weekday(client, fam) -> None:
    for d in ("2025-12-29", "2025-12-31", "2026-01-04"):  # Mon, Wed, Sun of the same week
        b = report(client, fam, "ali", "week", d).json()
        assert (b["start"], b["end"]) == ("2025-12-29", "2026-01-04")
    b = report(client, fam, "ali", "week", "2026-01-05").json()  # the next Monday starts a new week
    assert (b["start"], b["end"]) == ("2026-01-05", "2026-01-11")


def test_default_date_is_family_local_today(client, fam, clock) -> None:
    from datetime import datetime, timezone

    b = report(client, fam, "ali", "day").json()
    assert (b["date"], b["start"], b["end"]) == ("2026-01-05", "2026-01-05", "2026-01-05")
    assert b["days"] == [{"date": "2026-01-05", "watch_seconds": 10, "app_seconds": 60, "browsing_seconds": 50, "views_completed": 0}]
    # 20:00 UTC on Jan 5 is already Tue Jan 6 in Karachi
    clock.current = datetime(2026, 1, 5, 20, 0, tzinfo=timezone.utc)
    h = bearer(client.post("/auth/login", json={"email": "a@example.com", "password": "correct-horse"}).json()["access_token"])
    assert client.get(f"/reports/children/{fam['ali']}", params={"range": "day"}, headers=h).json()["date"] == "2026-01-06"


def test_month_boundaries_and_zero_filled_days(client, fam) -> None:
    dec = report(client, fam, "ali", "month", "2025-12-15").json()
    assert (dec["start"], dec["end"]) == ("2025-12-01", "2025-12-31") and len(dec["days"]) == 31
    assert sum(1 for d in dec["days"] if d["watch_seconds"] or d["app_seconds"] or d["views_completed"]) == 2
    assert day_map(dec)["2025-12-01"] == {"date": "2025-12-01", "watch_seconds": 0, "app_seconds": 0, "browsing_seconds": 0, "views_completed": 0}
    assert dec["totals"] == {"watch_seconds": 180, "app_seconds": 900, "browsing_seconds": 720, "views_completed": 3}
    # window starts at the plan start (Dec 29): Mon, Tue, Wed active = 3 days
    assert dec["planned_vs_watched"]["window_start"] == "2025-12-29" and dec["planned_vs_watched"]["window_end"] == "2025-12-31"
    assert (dec["planned_vs_watched"]["active_days"], dec["planned_vs_watched"]["expected"], dec["planned_vs_watched"]["actual"]) == (3, 6, 3)
    assert dec["playback_errors"][0]["count"] == 3  # the Jan 1 and Jan 5 errors are outside December

    jan = report(client, fam, "ali", "month", "2026-01-31").json()
    assert (jan["start"], jan["end"]) == ("2026-01-01", "2026-01-31") and len(jan["days"]) == 31
    # watch 200 + 10 ; app 100 + 60 -> 160 - 210 < 0 so browsing is 0, never negative
    assert jan["totals"] == {"watch_seconds": 210, "app_seconds": 160, "browsing_seconds": 0, "views_completed": 3}
    # future days (after Mon Jan 5) are not expected: Thu 1, Fri 2, Mon 5 = 3 active days
    assert (jan["planned_vs_watched"]["active_days"], jan["planned_vs_watched"]["expected"], jan["planned_vs_watched"]["actual"]) == (3, 6, 3)
    assert jan["planned_vs_watched"]["window_end"] == "2026-01-05"
    assert all(d["browsing_seconds"] >= 0 for d in jan["days"])

    feb = report(client, fam, "ali", "month", "2026-02-10").json()  # 28 days; the whole month is in the future
    assert (feb["start"], feb["end"]) == ("2026-02-01", "2026-02-28") and len(feb["days"]) == 28
    assert feb["totals"] == {"watch_seconds": 0, "app_seconds": 0, "browsing_seconds": 0, "views_completed": 0}
    assert feb["planned_vs_watched"] == {
        "pace_type": "videos_per_day", "unit": "videos", "window_start": None, "window_end": None,
        "active_days": 0, "expected": 0, "actual": 0,
    }


def test_leap_year_february(client, fam) -> None:
    b = report(client, fam, "ali", "month", "2028-02-10").json()
    assert (b["start"], b["end"], len(b["days"])) == ("2028-02-01", "2028-02-29", 29)


def test_today_is_included_in_the_expected_days(client, fam) -> None:
    b = report(client, fam, "ali", "week").json()  # Mon Jan 5 .. Sun Jan 11, only Monday has happened
    assert b["planned_vs_watched"]["active_days"] == 1 and b["planned_vs_watched"]["expected"] == 2
    assert b["planned_vs_watched"]["actual"] == 0
    assert (b["totals"]["watch_seconds"], b["totals"]["app_seconds"], b["totals"]["browsing_seconds"]) == (10, 60, 50)
    assert [(e["video_id"], e["code"]) for e in b["playback_errors"]] == [(fam["v"][2], "150")]


def test_minutes_mode_for_sara_is_independent_of_ali(client, fam) -> None:
    b = report(client, fam, "sara", "month", "2026-01-20").json()
    # Jan 1: watch 120, app 600 ; Jan 3: app 30 ; views: Jan 1 x1, Jan 2 x2
    assert b["totals"] == {"watch_seconds": 120, "app_seconds": 630, "browsing_seconds": 510, "views_completed": 3}
    assert day_map(b)["2026-01-03"]["browsing_seconds"] == 30
    assert [(t["video_id"], t["views"], t["watch_seconds"]) for t in b["top_videos"]] == [(fam["v"][0], 3, 120)]
    assert b["playback_errors"] == []
    # 10 min/day, every day, Jan 1..Jan 5 (today) = 5 days = 3000 s expected, 120 s watched
    assert b["planned_vs_watched"] == {
        "pace_type": "minutes_per_day", "unit": "seconds", "window_start": "2026-01-01", "window_end": "2026-01-05",
        "active_days": 5, "expected": 3000, "actual": 120,
    }
    per = {p["video_id"]: p for p in b["per_video"]}
    assert (per[fam["v"][0]]["views_total"], per[fam["v"][0]]["completed"]) == (3, True)
    assert (per[fam["v"][1]]["views_total"], per[fam["v"][1]]["completed"]) == (0, False)
    # v2 (2 views owed, default 300 s each) fits into today's 10 minutes: both views are scheduled today
    assert b["plan_progress"] == {"as_of": "2026-01-05", "finish_date": "2026-01-05", "remaining_finite_views": 2}
    # Ali's numbers are unchanged by Sara's events
    ali = report(client, fam, "ali", "month", "2026-01-20").json()
    assert ali["totals"]["app_seconds"] == 160


def test_plan_end_date_clips_expected(client, fam) -> None:
    put_plan(client, fam["h"], fam["ali"], pace_value=2, active_weekdays=MON_TO_FRI, start_date="2025-12-29", end_date="2025-12-30")
    b = report(client, fam, "ali", "week", "2026-01-01").json()
    p = b["planned_vs_watched"]
    assert (p["window_start"], p["window_end"], p["active_days"], p["expected"], p["actual"]) == ("2025-12-29", "2025-12-30", 2, 4, 3)


def test_child_without_a_plan(client, fam) -> None:
    kid = make_child(client, fam["h"], "Newborn")
    r = client.get(f"/reports/children/{kid}", params={"range": "week"}, headers=fam["h"])
    assert r.status_code == 200
    b = r.json()
    assert len(b["days"]) == 7 and b["planned_vs_watched"] is None and b["plan_progress"] is None
    assert b["per_video"] == [] and b["top_videos"] == [] and b["playback_errors"] == []


def test_views_of_a_deleted_plan_item_still_count_per_day(client, fam) -> None:
    client.delete(f"/plan-items/{fam['a'][1]}", headers=fam["h"])  # i2 (v2): its Dec 29 view stays in the log
    b = report(client, fam, "ali", "week", "2025-12-29").json()
    assert day_map(b)["2025-12-29"]["views_completed"] == 2
    assert fam["v"][1] not in {t["video_id"] for t in b["top_videos"] if t["views"]}
    assert len(b["per_video"]) == 3


def test_access_control(client, fam, engine) -> None:
    url = f"/reports/children/{fam['ali']}"
    assert client.get(url, params={"range": "week"}).status_code == 401
    kid = bearer(pair(client, fam["h"])["device_token"])
    assert client.get(url, params={"range": "week"}, headers=kid).status_code == 401
    other, _ = make_family(client, "b@example.com")
    assert client.get(url, params={"range": "week"}, headers=other).status_code == 404  # another family's child
    assert client.get(f"/reports/children/{uuid.uuid4()}", params={"range": "week"}, headers=fam["h"]).status_code == 404
    other_kid = bearer(pair(client, other)["device_token"])
    assert client.get(url, params={"range": "week"}, headers=other_kid).status_code == 401


@pytest.mark.parametrize("params", [{}, {"range": "year"}, {"range": "week", "date": "2026-13-01"}, {"range": "week", "date": "x"}])
def test_bad_query(client, fam, params) -> None:
    assert client.get(f"/reports/children/{fam['ali']}", params=params, headers=fam["h"]).status_code == 422


def test_query_count_does_not_grow_with_events(client, fam, engine) -> None:
    statements: list[str] = []
    sa_event.listen(engine, "before_cursor_execute", lambda conn, cur, stmt, *a: statements.append(stmt))

    def count(range_: str, on: str) -> int:
        statements.clear()
        assert report(client, fam, "ali", range_, on).status_code == 200
        return len(statements)

    base_week, base_month = count("week", "2026-01-01"), count("month", "2026-01-01")

    # 600 more events over the month: many videos, many days, every type
    ali = uuid.UUID(fam["ali"])
    rows = []
    for n in range(150):
        d = date(2026, 1, 1 + n % 5)
        vid = str(uuid.uuid4())
        rows += [
            Event(id=uuid.uuid4(), child_id=ali, type=EventType.watch_progress, local_date=d, payload={"video_id": vid, "seconds": 5}, occurred_at=_t()),
            Event(id=uuid.uuid4(), child_id=ali, type=EventType.playback_error, local_date=d, payload={"video_id": vid, "code": n % 7}, occurred_at=_t()),
            Event(id=uuid.uuid4(), child_id=ali, type=EventType.view_complete, local_date=d, plan_item_id=uuid.UUID(fam["a"][n % 4]), payload={}, occurred_at=_t()),
            Event(id=uuid.uuid4(), child_id=ali, type=EventType.app_session, local_date=d, payload={"start": "2026-01-01T09:00:00+00:00", "end": "2026-01-01T09:01:00+00:00"}, occurred_at=_t()),
        ]
    with Session(engine) as s:
        s.add_all(rows)
        s.commit()
    assert count("week", "2026-01-01") == base_week
    assert count("month", "2026-01-01") == base_month
    b = report(client, fam, "ali", "month", "2026-01-01").json()
    assert b["totals"]["views_completed"] == 3 + 150 and len(b["top_videos"]) == 10


def _t():
    from datetime import datetime, timezone

    return datetime(2026, 1, 1, 9, tzinfo=timezone.utc)
