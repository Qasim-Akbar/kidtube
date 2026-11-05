import uuid
from datetime import datetime, timezone

import pytest

from app.engine import run_feed, run_projection
from tests.helpers import (
    add_item, bearer, ev, feed, feed_ids, make_child, make_family, make_plan, make_video, pair, post_events,
    put_plan, relogin, set_video_status, statuses, view_ev,
)

EMAIL = "a@example.com"


@pytest.fixture
def fam(client, engine):
    h, family_id = make_family(client, EMAIL)
    child = make_child(client, h)
    videos = [make_video(engine, family_id, n) for n in range(1, 5)]
    return {"h": h, "family_id": family_id, "child": child, "videos": videos}


def projection(client, headers, child_id, **params):
    return client.get(f"/children/{child_id}/projection", headers=headers, params=params)


ENRICH = {"title", "thumbnail_url", "platform", "platform_video_id", "duration_seconds"}
EXTRA = {"date", "pace_type", "unavailable_item_ids"}


# ---------------------------------------------------------------- DB state -> feed == engine on hand-built input
def test_feed_equals_the_engine_on_hand_built_input(client, fam, clock) -> None:
    c, v = fam["child"], fam["videos"]
    i1, i2, i3 = make_plan(
        client, fam["h"], c, v[:3], plan_kw={"pace_value": 2},
        item_kw=[{"required": True, "view_target": 1}, {"view_target": 3}, {"view_target": 2}],
    )
    assert statuses(post_events(client, fam["h"], [view_ev(c, i1, "2026-01-05"), view_ev(c, i2, "2026-01-06")])) == ["accepted"] * 2
    clock.advance(days=2)  # 2026-01-07
    h = relogin(client, EMAIL)

    response = feed(client, h, c)
    assert response.status_code == 200
    body = response.json()

    item = lambda iid, vid, pos, **kw: {  # noqa: E731
        "id": iid, "video_id": vid, "position": pos, "required": False, "view_target": None, "daily_cap": None,
        "anchor_date": None, "on_miss": "shift", "status": "active", "duration_seconds": None,
        "added_on": "2026-01-05", **kw}
    hand_built = {
        "plan": {"pace_type": "videos_per_day", "pace_value": 2, "active_weekdays": 127, "max_extra_per_day": 1,
                 "start_date": "2026-01-05", "end_date": None},
        "items": [item(i1, v[0], 0, required=True, view_target=1), item(i2, v[1], 1, view_target=3),
                  item(i3, v[2], 2, view_target=2)],
        "views": [{"item_id": i1, "local_date": "2026-01-05"}, {"item_id": i2, "local_date": "2026-01-06"}],
        "seconds": [], "app_seconds": [], "limits": {"daily_app_minutes": None, "app_time_extra_seconds": 0},
        "today": "2026-01-07",
    }
    expected = run_feed(hand_built)
    api = {k: body[k] for k in expected}
    api["items"] = [{k: i[k] for k in ("item_id", "video_id", "locked", "required")} for i in body["items"]]
    assert api == expected
    # and the numbers are what the semantics say: window Jan 5-6 expects 4 views, 2 done -> backlog 2, extra 1
    assert (body["status"], [i["item_id"] for i in body["items"]]) == ("ok", [i2, i3])
    assert (body["quota_today"], body["backlog"], body["views_today"], body["remaining_videos"]) == (3, 2, 0, 3)
    assert body["date"] == "2026-01-07" and body["pace_type"] == "videos_per_day" and body["remaining_seconds"] is None


def test_items_are_enriched_with_video_metadata(client, fam) -> None:
    make_plan(client, fam["h"], fam["child"], fam["videos"][:1])
    (item,) = feed(client, fam["h"], fam["child"]).json()["items"]
    assert item["title"] == "Video 1" and item["platform"] == "youtube" and item["platform_video_id"] == "vid00000001"
    assert item["thumbnail_url"].startswith("https://i.ytimg.com/") and item["duration_seconds"] is None
    assert item["locked"] is False and item["required"] is False and item["video_id"] == fam["videos"][0]


def test_required_item_is_locked_and_first(client, fam) -> None:
    ids = make_plan(client, fam["h"], fam["child"], fam["videos"][:3], plan_kw={"pace_value": 3},
                    item_kw=[{}, {"required": True}, {"required": True}])
    items = feed(client, fam["h"], fam["child"]).json()["items"]
    assert [(i["item_id"], i["locked"]) for i in items] == [(ids[1], True)]
    post_events(client, fam["h"], [view_ev(fam["child"], ids[1])])
    assert [(i["item_id"], i["locked"]) for i in feed(client, fam["h"], fam["child"]).json()["items"]] == [(ids[2], True)]
    post_events(client, fam["h"], [view_ev(fam["child"], ids[2])])
    after = feed(client, fam["h"], fam["child"]).json()
    assert after["views_today"] == 2 and after["remaining_videos"] == 1
    assert [(i["item_id"], i["locked"]) for i in after["items"]] == [(ids[0], False)]


def test_quota_exhaustion_and_view_target(client, fam) -> None:
    ids = make_plan(client, fam["h"], fam["child"], fam["videos"][:2], item_kw=[{"view_target": 2}, {}])
    c = fam["child"]
    assert feed_ids(feed(client, fam["h"], c)) == [ids[0]]
    post_events(client, fam["h"], [view_ev(c, ids[0])])
    done = feed(client, fam["h"], c).json()
    assert done["status"] == "done_today" and done["items"] == [] and done["remaining_videos"] == 0


# ---------------------------------------------------------------- separate children
def test_two_children_on_the_same_device_have_independent_feeds(client, fam) -> None:
    kid = bearer(pair(client, fam["h"])["device_token"])
    ali, sara = fam["child"], make_child(client, fam["h"], "Sara")
    a = make_plan(client, fam["h"], ali, fam["videos"][:3], plan_kw={"pace_value": 1}, item_kw=[{"view_target": 1}] * 3)
    b = make_plan(client, fam["h"], sara, fam["videos"][1:4], plan_kw={"pace_value": 2}, item_kw=[{"view_target": 1}] * 3)
    assert feed_ids(feed(client, kid, ali)) == [a[0]]
    assert feed_ids(feed(client, kid, sara)) == [b[0], b[1]]

    post_events(client, kid, [view_ev(ali, a[0])])  # Ali watches; Sara is unaffected
    assert feed(client, kid, ali).json()["status"] == "done_today"
    sara_feed = feed(client, kid, sara).json()
    assert [i["item_id"] for i in sara_feed["items"]] == [b[0], b[1]] and sara_feed["views_today"] == 0

    post_events(client, kid, [view_ev(sara, b[0])])
    assert feed(client, kid, ali).json()["views_today"] == 1
    assert feed(client, kid, sara).json()["views_today"] == 1
    assert feed_ids(feed(client, kid, sara)) == [b[1]]


def test_a_view_posted_for_one_childs_item_does_not_count_for_a_sibling(client, fam) -> None:
    ali, sara = fam["child"], make_child(client, fam["h"], "Sara")
    a = make_plan(client, fam["h"], ali, fam["videos"][:1])
    make_plan(client, fam["h"], sara, fam["videos"][:1])
    post_events(client, fam["h"], [view_ev(sara, a[0])])  # Sara's event naming Ali's plan item
    assert feed_ids(feed(client, fam["h"], ali)) == [a[0]]  # Ali's plan is untouched
    assert feed(client, fam["h"], ali).json()["views_today"] == 0


# ---------------------------------------------------------------- time
def test_feed_uses_the_family_local_date(client, engine, clock) -> None:
    clock.current = datetime(2026, 11, 5, 20, 0, tzinfo=timezone.utc)  # 2026-11-06 01:00 in Karachi
    h, family_id = make_family(client, "tz@example.com")
    ali, sara = make_child(client, h, "Ali"), make_child(client, h, "Sara")
    vid = make_video(engine, family_id, 1)
    (starts_in_karachi_day,) = make_plan(client, h, ali, [vid], plan_kw={"start_date": "2026-11-06"})
    make_plan(client, h, sara, [vid], plan_kw={"start_date": "2026-11-05", "end_date": "2026-11-05"})

    ok = feed(client, h, ali).json()
    assert ok["date"] == "2026-11-06" and ok["status"] == "ok" and [i["item_id"] for i in ok["items"]] == [starts_in_karachi_day]
    over = feed(client, h, sara).json()  # the plan ended on "UTC today"; Karachi is already a day later
    assert over["date"] == "2026-11-06" and over["status"] == "outside_dates"
    # a view logged on the Karachi date counts today
    post_events(client, h, [view_ev(ali, starts_in_karachi_day, "2026-11-06")])
    assert feed(client, h, ali).json()["views_today"] == 1
    assert projection(client, h, ali).json()["today"] == "2026-11-06"


def test_missed_days_shift_the_queue(client, fam, clock) -> None:
    c = fam["child"]
    a, b, cc, d = make_plan(client, fam["h"], c, fam["videos"], item_kw=[{"view_target": 1}] * 4)  # pace 1, max_extra 1
    assert feed_ids(feed(client, fam["h"], c)) == [a]  # day 1 (Mon 2026-01-05)
    post_events(client, fam["h"], [view_ev(c, a, "2026-01-05")])  # watched on day 1, nothing on day 2

    clock.advance(days=2)  # day 3
    h = relogin(client, EMAIL)
    day3 = feed(client, h, c).json()
    assert day3["date"] == "2026-01-07"
    assert [i["item_id"] for i in day3["items"]] == [b, cc]  # a stays done; the queue moved on
    assert (day3["quota_today"], day3["backlog"]) == (2, 1)  # window Jan 5-6: expected 2, done 1
    # what day 2 looked like (parent preview): window Jan 5 only, nothing missed yet
    day2 = feed(client, h, c, date="2026-01-06").json()
    assert [i["item_id"] for i in day2["items"]] == [b] and (day2["quota_today"], day2["backlog"]) == (1, 0)
    # the catch-up cap holds after a long gap
    clock.advance(days=10)
    h = relogin(client, EMAIL)
    late = feed(client, h, c).json()
    assert late["quota_today"] == 2 and late["backlog"] > 2 and [i["item_id"] for i in late["items"]] == [b, cc]


def test_events_for_deleted_items_are_ignored(client, fam) -> None:
    ids = make_plan(client, fam["h"], fam["child"], fam["videos"][:2])
    post_events(client, fam["h"], [view_ev(fam["child"], ids[0])])
    client.delete(f"/plan-items/{ids[0]}", headers=fam["h"])
    r = feed(client, fam["h"], fam["child"]).json()
    assert r["views_today"] == 0 and [i["item_id"] for i in r["items"]] == [ids[1]]


# ---------------------------------------------------------------- time extension / app time
def test_time_extension_clears_time_up_and_only_a_parent_can_post_it(client, fam) -> None:
    c = fam["child"]
    assert client.patch(f"/children/{c}", json={"daily_app_minutes": 1}, headers=fam["h"]).status_code == 200
    (item,) = make_plan(client, fam["h"], c, fam["videos"][:1])
    kid = bearer(pair(client, fam["h"])["device_token"])
    session = lambda start, end: ev(c, "app_session", {"start": start, "end": end})  # noqa: E731

    assert feed(client, kid, c).json()["status"] == "ok"
    post_events(client, kid, [session("2026-01-05T10:00:00Z", "2026-01-05T10:00:59Z")])
    assert feed(client, kid, c).json()["status"] == "ok"  # 59 s of a 60 s limit
    post_events(client, kid, [session("2026-01-05T11:00:00Z", "2026-01-05T11:00:01Z")])
    timed_out = feed(client, kid, c).json()
    assert timed_out["status"] == "time_up" and timed_out["items"] == []  # 60 s used

    forged = ev(c, "time_extension", {"seconds": 900})
    assert statuses(post_events(client, kid, [forged])) == ["rejected"]
    assert feed(client, kid, c).json()["status"] == "time_up"  # a kid cannot extend their own time

    assert statuses(post_events(client, fam["h"], [forged])) == ["accepted"]
    cleared = feed(client, kid, c).json()
    assert cleared["status"] == "ok" and [i["item_id"] for i in cleared["items"]] == [item]
    # the grant is finite: using up the extra time brings the limit back
    post_events(client, kid, [session("2026-01-05T12:00:00Z", "2026-01-05T12:15:00Z")])
    assert feed(client, kid, c).json()["status"] == "time_up"
    post_events(client, fam["h"], [ev(c, "time_extension", {"seconds": 600})])
    assert feed(client, kid, c).json()["status"] == "ok"


def test_time_extension_applies_only_to_its_own_day_and_child(client, fam) -> None:
    c, sara = fam["child"], make_child(client, fam["h"], "Sara")
    for child in (c, sara):
        client.patch(f"/children/{child}", json={"daily_app_minutes": 1}, headers=fam["h"])
        make_plan(client, fam["h"], child, fam["videos"][:1])
        post_events(client, fam["h"], [ev(child, "app_session", {"start": "2026-01-05T10:00:00Z", "end": "2026-01-05T10:02:00Z"})])
    post_events(client, fam["h"], [ev(c, "time_extension", {"seconds": 900}),
                                   ev(sara, "time_extension", {"seconds": 900}, local_date="2026-01-04")])
    assert feed(client, fam["h"], c).json()["status"] == "ok"
    assert feed(client, fam["h"], sara).json()["status"] == "time_up"  # her grant was for yesterday


# ---------------------------------------------------------------- minutes pace
def test_minutes_pace_units_and_watch_progress(client, engine, fam) -> None:
    c = fam["child"]
    videos = [make_video(engine, fam["family_id"], n, duration=300) for n in range(10, 14)]
    make_plan(client, fam["h"], c, videos, plan_kw={"pace_type": "minutes_per_day", "pace_value": 10})
    r = feed(client, fam["h"], c).json()
    assert r["pace_type"] == "minutes_per_day"
    assert (r["quota_today"], r["remaining_seconds"], r["remaining_videos"], r["backlog"]) == (600, 600, None, 0)
    assert len(r["items"]) == 2 and r["items"][0]["duration_seconds"] == 300

    post_events(client, fam["h"], [ev(c, "watch_progress", {"video_id": videos[0], "seconds": 250}),
                                   ev(c, "watch_progress", {"video_id": videos[0], "seconds": 200.4})])
    r = feed(client, fam["h"], c).json()
    assert r["remaining_seconds"] == 150 and len(r["items"]) == 1  # 450 s watched of 600 s
    post_events(client, fam["h"], [ev(c, "watch_progress", {"video_id": videos[0], "seconds": 200})])
    assert feed(client, fam["h"], c).json()["status"] == "done_today"


def test_openapi_documents_the_units(client) -> None:
    schema = client.get("/openapi.json").json()["components"]["schemas"]["FeedOut"]["properties"]
    assert "SECONDS in minutes_per_day mode" in schema["quota_today"]["description"]
    assert "seconds in minutes mode" in schema["backlog"]["description"]


# ---------------------------------------------------------------- unavailable videos
def test_unavailable_video_is_listed_and_not_offered(client, engine, fam) -> None:
    c = fam["child"]
    ids = make_plan(client, fam["h"], c, fam["videos"][:3], plan_kw={"pace_value": 3})
    assert feed_ids(feed(client, fam["h"], c)) == ids
    set_video_status(engine, fam["videos"][0], "unavailable")  # e.g. a later reverify flagged it

    r = feed(client, fam["h"], c).json()
    assert r["unavailable_item_ids"] == [ids[0]] and [i["item_id"] for i in r["items"]] == ids[1:]
    assert r["quota_today"] == 3
    p = projection(client, fam["h"], c).json()
    assert p["unavailable_item_ids"] == [ids[0]]
    assert ids[0] not in [v["item_id"] for d in p["days"] for v in d["views"]]
    assert client.get(f"/children/{c}/plan", headers=fam["h"]).json()["items"][0]["video"]["status"] == "unavailable"


def test_plan_with_only_unavailable_videos_is_empty(client, engine, fam) -> None:
    (item,) = make_plan(client, fam["h"], fam["child"], fam["videos"][:1])
    set_video_status(engine, fam["videos"][0], "unavailable")
    r = feed(client, fam["h"], fam["child"]).json()
    assert r["status"] == "empty" and r["items"] == [] and r["unavailable_item_ids"] == [item]


# ---------------------------------------------------------------- preview date / authorization
def test_date_preview_is_parent_only(client, fam) -> None:
    c = fam["child"]
    make_plan(client, fam["h"], c, fam["videos"][:1], plan_kw={"active_weekdays": 31})  # Mon-Fri
    kid = bearer(pair(client, fam["h"])["device_token"])
    saturday = feed(client, fam["h"], c, date="2026-01-10")
    assert saturday.status_code == 200 and saturday.json()["status"] == "day_off" and saturday.json()["date"] == "2026-01-10"
    assert feed(client, fam["h"], c, date="2026-01-12").json()["status"] == "ok"
    assert feed(client, kid, c, date="2026-01-10").status_code == 403
    assert feed(client, kid, c, date="2026-01-05").status_code == 403  # any date= is forbidden for a device
    assert feed(client, kid, c).status_code == 200
    assert feed(client, fam["h"], c, date="not-a-date").status_code == 422


def test_device_can_read_plan_feed_and_projection(client, fam) -> None:
    c = fam["child"]
    make_plan(client, fam["h"], c, fam["videos"][:2])
    kid = bearer(pair(client, fam["h"])["device_token"])
    assert client.get(f"/children/{c}/plan", headers=kid).status_code == 200
    assert feed(client, kid, c).json() == feed(client, fam["h"], c).json()
    assert projection(client, kid, c).json() == projection(client, fam["h"], c).json()


def test_no_plan_is_404_everywhere(client, fam) -> None:
    c = fam["child"]
    assert feed(client, fam["h"], c).status_code == 404
    assert projection(client, fam["h"], c).status_code == 404
    assert feed(client, fam["h"], str(uuid.uuid4())).status_code == 404
    assert projection(client, fam["h"], str(uuid.uuid4())).status_code == 404


def test_unauthenticated_feed_and_projection(client, fam) -> None:
    c = fam["child"]
    make_plan(client, fam["h"], c, fam["videos"][:1])
    assert client.get(f"/children/{c}/feed").status_code == 401
    assert client.get(f"/children/{c}/projection").status_code == 401


def test_cross_family_isolation(client, engine, fam) -> None:
    c = fam["child"]
    make_plan(client, fam["h"], c, fam["videos"][:1])
    other_h, _ = make_family(client, "b@example.com")
    other_kid = bearer(pair(client, other_h)["device_token"])
    for headers in (other_h, other_kid):
        assert feed(client, headers, c).status_code == 404
        assert projection(client, headers, c).status_code == 404
    assert feed(client, other_h, c, date="2026-01-06").status_code == 404
    assert feed(client, other_kid, c, date="2026-01-06").status_code == 404  # 404 (not found) wins over 403


# ---------------------------------------------------------------- projection
def test_projection_matches_the_engine_and_is_enriched(client, fam) -> None:
    c = fam["child"]
    ids = make_plan(client, fam["h"], c, fam["videos"][:3], item_kw=[{"view_target": 1}] * 3)
    r = projection(client, fam["h"], c, days=5)
    assert r.status_code == 200
    body = r.json()
    assert body["today"] == "2026-01-05" and body["pace_type"] == "videos_per_day" and len(body["days"]) == 5
    assert [[v["item_id"] for v in d["views"]] for d in body["days"]] == [[ids[0]], [ids[1]], [ids[2]], [], []]
    assert [d["date"] for d in body["days"]] == ["2026-01-05", "2026-01-06", "2026-01-07", "2026-01-08", "2026-01-09"]
    assert body["finish_date"] == "2026-01-07" and body["remaining_finite_views"] == 3
    assert body["days"][0]["views"][0]["title"] == "Video 1"
    assert body["unscheduled_item_ids"] == [] and body["excluded_unlimited_item_ids"] == []

    direct = run_projection({
        "plan": {"pace_type": "videos_per_day", "pace_value": 1, "active_weekdays": 127, "max_extra_per_day": 1,
                 "start_date": "2026-01-05", "end_date": None},
        "items": [{"id": i, "video_id": v, "position": n, "required": False, "view_target": 1, "daily_cap": None,
                   "anchor_date": None, "on_miss": "shift", "status": "active", "duration_seconds": None,
                   "added_on": "2026-01-05"} for n, (i, v) in enumerate(zip(ids, fam["videos"]))],
        "views": [], "seconds": [], "today": "2026-01-05", "days": 5,
    })
    for key in ("finish_date", "remaining_finite_views", "unscheduled_item_ids", "excluded_unlimited_item_ids"):
        assert body[key] == direct[key]
    assert [[v["item_id"] for v in d["views"]] for d in body["days"]] == [[v["item_id"] for v in d["views"]] for d in direct["days"]]


def test_projection_reflects_progress_and_required_chains(client, fam) -> None:
    c = fam["child"]
    ids = make_plan(client, fam["h"], c, fam["videos"][:3], plan_kw={"pace_value": 3}, item_kw=[{"required": True}] * 3)
    day0 = projection(client, fam["h"], c, days=2).json()
    assert [v["item_id"] for v in day0["days"][0]["views"]] == ids and day0["finish_date"] == "2026-01-05"  # one day
    post_events(client, fam["h"], [view_ev(c, ids[0])])
    after = projection(client, fam["h"], c, days=2).json()
    assert [v["item_id"] for v in after["days"][0]["views"]] == ids[1:] and after["remaining_finite_views"] == 2


def test_projection_days_parameter(client, fam) -> None:
    c = fam["child"]
    make_plan(client, fam["h"], c, fam["videos"][:1])
    assert len(projection(client, fam["h"], c).json()["days"]) == 30  # default
    assert len(projection(client, fam["h"], c, days=1).json()["days"]) == 1
    assert len(projection(client, fam["h"], c, days=91).json()["days"]) == 91
    for bad in (0, 92, -3, "x"):
        assert projection(client, fam["h"], c, days=bad).status_code == 422
