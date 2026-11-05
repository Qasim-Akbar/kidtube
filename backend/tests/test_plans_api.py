import uuid

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models import Event, PlanItem
from tests.helpers import (
    add_item, bearer, make_child, make_family, make_plan, make_video, pair, post_events, put_plan, view_ev,
)


@pytest.fixture
def fam(client, engine):
    headers, family_id = make_family(client)
    child = make_child(client, headers)
    videos = [make_video(engine, family_id, n) for n in range(1, 5)]
    return {"h": headers, "family_id": family_id, "child": child, "videos": videos}


def plan_of(client, fam):
    return client.get(f"/children/{fam['child']}/plan", headers=fam["h"])


def version(client, fam) -> int:
    return plan_of(client, fam).json()["version"]


# ---------------------------------------------------------------- plan config
def test_get_plan_404_until_created_and_nothing_is_created_by_get(client, fam, engine) -> None:
    assert plan_of(client, fam).status_code == 404
    assert plan_of(client, fam).status_code == 404
    assert put_plan(client, fam["h"], fam["child"]).status_code == 201
    assert plan_of(client, fam).status_code == 200


def test_put_creates_then_updates_and_bumps_version_only_on_change(client, fam) -> None:
    r = put_plan(client, fam["h"], fam["child"], pace_value=2, active_weekdays=31, end_date="2026-02-01")
    assert r.status_code == 201
    body = r.json()
    assert (body["version"], body["pace_value"], body["active_weekdays"], body["max_extra_per_day"]) == (1, 2, 31, 1)
    assert body["items"] == [] and body["end_date"] == "2026-02-01"

    same = put_plan(client, fam["h"], fam["child"], pace_value=2, active_weekdays=31, end_date="2026-02-01")
    assert same.status_code == 200 and same.json()["version"] == 1 and same.json()["id"] == body["id"]
    changed = put_plan(client, fam["h"], fam["child"], pace_value=3, active_weekdays=31, end_date="2026-02-01")
    assert changed.status_code == 200 and changed.json()["version"] == 2 and changed.json()["pace_value"] == 3
    cleared = put_plan(client, fam["h"], fam["child"], pace_value=3, active_weekdays=31)
    assert cleared.json()["version"] == 3 and cleared.json()["end_date"] is None


@pytest.mark.parametrize(
    "kw",
    [
        {"pace_value": 0}, {"pace_value": -1}, {"active_weekdays": 0}, {"active_weekdays": 128},
        {"end_date": "2026-01-04"}, {"pace_type": "weekly"}, {"max_extra_per_day": -1}, {"start_date": "nope"},
        {"surprise": 1},
    ],
)
def test_put_validation(client, fam, kw) -> None:
    assert put_plan(client, fam["h"], fam["child"], **kw).status_code == 422
    assert plan_of(client, fam).status_code == 404


def test_end_date_equal_to_start_is_valid(client, fam) -> None:
    assert put_plan(client, fam["h"], fam["child"], end_date="2026-01-05").status_code == 201


def test_max_extra_defaults_by_mode_and_explicit_values_are_kept(client, fam) -> None:
    r = put_plan(client, fam["h"], fam["child"], pace_type="minutes_per_day", pace_value=20)
    assert r.json()["max_extra_per_day"] == 20  # = pace_value, in minutes
    r = put_plan(client, fam["h"], fam["child"], pace_type="minutes_per_day", pace_value=20, max_extra_per_day=5)
    assert r.json()["max_extra_per_day"] == 5
    r = put_plan(client, fam["h"], fam["child"], pace_type="minutes_per_day", pace_value=20, max_extra_per_day=0)
    assert r.json()["max_extra_per_day"] == 0
    r = put_plan(client, fam["h"], fam["child"], pace_type="videos_per_day", pace_value=3)
    assert r.json()["max_extra_per_day"] == 1


# ---------------------------------------------------------------- items
def test_items_append_in_order_with_video_metadata(client, fam) -> None:
    ids = make_plan(client, fam["h"], fam["child"], fam["videos"][:3])
    plan = plan_of(client, fam).json()
    assert [i["id"] for i in plan["items"]] == ids and [i["position"] for i in plan["items"]] == [0, 1, 2]
    first = plan["items"][0]
    assert first["video"]["title"] == "Video 1" and first["video"]["platform"] == "youtube"
    assert first["video"]["thumbnail_url"].startswith("https://i.ytimg.com/") and first["video"]["status"] == "verified"
    assert (first["required"], first["view_target"], first["daily_cap"], first["on_miss"], first["status"]) == (
        False, None, None, "shift", "active")
    assert first["watch_threshold"] == 0.9 and first["added_on"] == "2026-01-05"
    assert plan["version"] == 1 + 3


def test_item_options_are_stored(client, fam) -> None:
    put_plan(client, fam["h"], fam["child"])
    r = add_item(client, fam["h"], fam["child"], fam["videos"][0], required=True, view_target=3, daily_cap=1,
                 anchor_date="2026-01-09", on_miss="skip", watch_threshold=0.8)
    assert r.status_code == 201
    body = r.json()
    assert (body["required"], body["view_target"], body["daily_cap"], body["anchor_date"], body["on_miss"],
            body["watch_threshold"]) == (True, 3, 1, "2026-01-09", "skip", 0.8)


def test_insert_at_position_shifts_others(client, fam) -> None:
    ids = make_plan(client, fam["h"], fam["child"], fam["videos"][:2])
    r = add_item(client, fam["h"], fam["child"], fam["videos"][2], position=0)
    assert r.status_code == 201 and r.json()["position"] == 0
    items = plan_of(client, fam).json()["items"]
    assert [i["id"] for i in items] == [r.json()["id"], *ids] and [i["position"] for i in items] == [0, 1, 2]
    far = add_item(client, fam["h"], fam["child"], fam["videos"][3], position=99)
    assert far.json()["position"] == 3  # clamped to the end


@pytest.mark.parametrize("kw", [{"view_target": 0}, {"daily_cap": 0}, {"position": -1}, {"on_miss": "explode"},
                                {"watch_threshold": 0}, {"watch_threshold": 1.5}, {"unknown": 1}])
def test_item_validation(client, fam, kw) -> None:
    put_plan(client, fam["h"], fam["child"])
    assert add_item(client, fam["h"], fam["child"], fam["videos"][0], **kw).status_code == 422


def test_add_item_needs_a_plan_and_a_known_video(client, fam) -> None:
    assert add_item(client, fam["h"], fam["child"], fam["videos"][0]).status_code == 404  # no plan yet
    put_plan(client, fam["h"], fam["child"])
    assert add_item(client, fam["h"], fam["child"], str(uuid.uuid4())).status_code == 404


def test_video_from_another_family_is_404(client, engine, fam) -> None:
    other_h, other_family = make_family(client, "b@example.com")
    foreign = make_video(engine, other_family, 99)
    put_plan(client, fam["h"], fam["child"])
    assert add_item(client, fam["h"], fam["child"], foreign).status_code == 404


def test_unavailable_video_rejected(client, engine, fam) -> None:
    bad = make_video(engine, fam["family_id"], 50, status="unavailable")
    put_plan(client, fam["h"], fam["child"])
    r = add_item(client, fam["h"], fam["child"], bad)
    assert r.status_code == 422 and "unavailable" in r.json()["detail"]
    assert plan_of(client, fam).json()["items"] == []


def test_pending_video_is_allowed(client, engine, fam) -> None:
    pending = make_video(engine, fam["family_id"], 51, status="pending")
    put_plan(client, fam["h"], fam["child"])
    assert add_item(client, fam["h"], fam["child"], pending).status_code == 201


def test_same_video_twice_is_409_unless_the_first_is_not_active(client, fam) -> None:
    ids = make_plan(client, fam["h"], fam["child"], fam["videos"][:1])
    assert add_item(client, fam["h"], fam["child"], fam["videos"][0]).status_code == 409
    client.patch(f"/plan-items/{ids[0]}", json={"status": "completed"}, headers=fam["h"])
    again = add_item(client, fam["h"], fam["child"], fam["videos"][0])
    assert again.status_code == 201
    # reactivating the completed one would now duplicate the active one
    assert client.patch(f"/plan-items/{ids[0]}", json={"status": "active"}, headers=fam["h"]).status_code == 409


def test_same_video_can_be_in_two_childrens_plans(client, fam) -> None:
    other_child = make_child(client, fam["h"], "Sara")
    make_plan(client, fam["h"], fam["child"], fam["videos"][:1])
    make_plan(client, fam["h"], other_child, fam["videos"][:1])


def test_added_on_is_the_childs_local_date(client, engine, clock) -> None:
    from datetime import datetime, timezone

    clock.current = datetime(2026, 11, 5, 20, 0, tzinfo=timezone.utc)  # already 2026-11-06 in Asia/Karachi
    h, family_id = make_family(client, "k@example.com")  # log in after moving the clock (tokens expire)
    child = make_child(client, h)
    put_plan(client, h, child, start_date="2026-11-06")
    r = add_item(client, h, child, make_video(engine, family_id, 1))
    assert r.json()["added_on"] == "2026-11-06"


def test_added_on_follows_a_non_default_family_timezone(client, engine, clock) -> None:
    from datetime import datetime, timezone

    clock.current = datetime(2026, 11, 5, 3, 0, tzinfo=timezone.utc)  # 2026-11-04 19:00 in Los Angeles
    h, family_id = make_family(client, "tz@example.com", timezone="America/Los_Angeles")
    child = make_child(client, h)
    put_plan(client, h, child, start_date="2026-11-04")
    r = add_item(client, h, child, make_video(engine, family_id, 1))
    assert r.json()["added_on"] == "2026-11-04"


def test_register_rejects_unknown_timezone(client) -> None:
    from tests.helpers import register

    assert register(client, "bad@example.com", timezone="Mars/Phobos").status_code == 422
    assert register(client, "good@example.com", timezone="UTC").status_code == 201


# ---------------------------------------------------------------- patch / delete / reorder / versions
def test_every_change_bumps_the_version(client, fam) -> None:
    ids = make_plan(client, fam["h"], fam["child"], fam["videos"][:3])
    v = version(client, fam)
    assert client.patch(f"/plan-items/{ids[0]}", json={"required": True}, headers=fam["h"]).status_code == 200
    assert version(client, fam) == v + 1
    assert client.post(f"/children/{fam['child']}/plan/reorder", json={"item_ids": [ids[2], ids[0], ids[1]]},
                       headers=fam["h"]).status_code == 200
    assert version(client, fam) == v + 2
    assert client.delete(f"/plan-items/{ids[1]}", headers=fam["h"]).status_code == 204
    assert version(client, fam) == v + 3
    assert add_item(client, fam["h"], fam["child"], fam["videos"][3]).status_code == 201
    assert version(client, fam) == v + 4


def test_noop_patch_and_noop_reorder_do_not_bump(client, fam) -> None:
    ids = make_plan(client, fam["h"], fam["child"], fam["videos"][:2], item_kw=[{"required": True}, {}])
    v = version(client, fam)
    assert client.patch(f"/plan-items/{ids[0]}", json={"required": True}, headers=fam["h"]).status_code == 200
    assert client.patch(f"/plan-items/{ids[0]}", json={}, headers=fam["h"]).status_code == 200
    assert client.post(f"/children/{fam['child']}/plan/reorder", json={"item_ids": ids}, headers=fam["h"]).status_code == 200
    assert version(client, fam) == v


def test_patch_fields_and_clearing_nulls(client, fam) -> None:
    ids = make_plan(client, fam["h"], fam["child"], fam["videos"][:1],
                    item_kw=[{"view_target": 3, "daily_cap": 2, "anchor_date": "2026-01-09"}])
    r = client.patch(f"/plan-items/{ids[0]}", json={"view_target": None, "daily_cap": None, "anchor_date": None,
                                                    "on_miss": "drop", "status": "dropped", "watch_threshold": 0.5},
                     headers=fam["h"])
    body = r.json()
    assert r.status_code == 200
    assert (body["view_target"], body["daily_cap"], body["anchor_date"], body["on_miss"], body["status"],
            body["watch_threshold"]) == (None, None, None, "drop", "dropped", 0.5)
    r = client.patch(f"/plan-items/{ids[0]}", json={"daily_cap": 4}, headers=fam["h"])
    assert r.json()["daily_cap"] == 4 and r.json()["on_miss"] == "drop"  # unsent fields untouched


@pytest.mark.parametrize("kw", [{"required": None}, {"on_miss": None}, {"status": None}, {"watch_threshold": None},
                                {"view_target": 0}, {"status": "weird"}, {"bogus": 1}])
def test_patch_validation(client, fam, kw) -> None:
    ids = make_plan(client, fam["h"], fam["child"], fam["videos"][:1])
    assert client.patch(f"/plan-items/{ids[0]}", json=kw, headers=fam["h"]).status_code == 422


def test_delete_renumbers_positions(client, fam) -> None:
    ids = make_plan(client, fam["h"], fam["child"], fam["videos"][:4])
    client.delete(f"/plan-items/{ids[1]}", headers=fam["h"])
    items = plan_of(client, fam).json()["items"]
    assert [i["id"] for i in items] == [ids[0], ids[2], ids[3]] and [i["position"] for i in items] == [0, 1, 2]
    assert client.delete(f"/plan-items/{ids[1]}", headers=fam["h"]).status_code == 404


def test_reorder(client, fam) -> None:
    ids = make_plan(client, fam["h"], fam["child"], fam["videos"][:3])
    r = client.post(f"/children/{fam['child']}/plan/reorder", json={"item_ids": [ids[2], ids[0], ids[1]]}, headers=fam["h"])
    assert r.status_code == 200
    assert [i["id"] for i in r.json()["items"]] == [ids[2], ids[0], ids[1]]
    assert [i["position"] for i in r.json()["items"]] == [0, 1, 2]


def test_reorder_must_list_every_item_once(client, fam) -> None:
    ids = make_plan(client, fam["h"], fam["child"], fam["videos"][:3])
    url = f"/children/{fam['child']}/plan/reorder"
    for bad in ([ids[0], ids[1]], [*ids, ids[0]], [ids[0], ids[1], str(uuid.uuid4())], []):
        assert client.post(url, json={"item_ids": bad}, headers=fam["h"]).status_code == 422
    assert [i["id"] for i in plan_of(client, fam).json()["items"]] == ids


def test_view_events_never_change_item_status(client, fam, engine) -> None:
    ids = make_plan(client, fam["h"], fam["child"], fam["videos"][:1], item_kw=[{"view_target": 1}])
    v = version(client, fam)
    r = post_events(client, fam["h"], [view_ev(fam["child"], ids[0]), view_ev(fam["child"], ids[0])])
    assert r.status_code == 200
    item = plan_of(client, fam).json()["items"][0]
    assert item["status"] == "active" and version(client, fam) == v
    with Session(engine) as s:
        assert s.scalars(select(PlanItem.status)).one().value == "active"
        assert s.scalar(select(Event.plan_item_id)) is not None


# ---------------------------------------------------------------- authorization
WRITES = [
    ("put", "/children/{c}/plan", {"pace_type": "videos_per_day", "pace_value": 1, "active_weekdays": 127, "start_date": "2026-01-05"}),
    ("post", "/children/{c}/plan/items", {"video_id": "{v}"}),
    ("patch", "/plan-items/{i}", {"required": True}),
    ("delete", "/plan-items/{i}", None),
    ("post", "/children/{c}/plan/reorder", {"item_ids": ["{i}"]}),
]


def fill(value, fam, item):
    """Substitute {c}/{i}/{v} placeholders anywhere in a str / list / dict."""
    import json

    text = json.dumps(value).replace("{c}", fam["child"]).replace("{i}", item).replace("{v}", fam["videos"][1])
    return json.loads(text)


def call(client, method, url, body, headers):
    kwargs = {"headers": headers} | ({"json": body} if body is not None else {})
    return getattr(client, method)(url, **kwargs)


def test_device_token_can_read_plan_but_not_write(client, fam) -> None:
    (item,) = make_plan(client, fam["h"], fam["child"], fam["videos"][:1])
    kid = bearer(pair(client, fam["h"])["device_token"])
    assert client.get(f"/children/{fam['child']}/plan", headers=kid).status_code == 200
    before = plan_of(client, fam).json()
    for method, url, body in WRITES:
        assert call(client, method, fill(url, fam, item), fill(body, fam, item), kid).status_code == 401, (method, url)
    assert plan_of(client, fam).json() == before


def test_unauthenticated_requests_rejected(client, fam) -> None:
    (item,) = make_plan(client, fam["h"], fam["child"], fam["videos"][:1])
    assert client.get(f"/children/{fam['child']}/plan").status_code == 401
    for method, url, body in WRITES:
        assert call(client, method, fill(url, fam, item), None if body is None else {}, {}).status_code == 401


def test_cross_family_isolation(client, engine, fam) -> None:
    (item,) = make_plan(client, fam["h"], fam["child"], fam["videos"][:1])
    other_h, other_family = make_family(client, "b@example.com")
    other_child = make_child(client, other_h, "Zain")
    other_video = make_video(engine, other_family, 77)
    other_kid = bearer(pair(client, other_h)["device_token"])
    before = plan_of(client, fam).json()
    c = fam["child"]

    for headers in (other_h, other_kid):
        assert client.get(f"/children/{c}/plan", headers=headers).status_code == 404
    assert put_plan(client, other_h, c).status_code == 404
    assert add_item(client, other_h, c, other_video).status_code == 404
    assert client.patch(f"/plan-items/{item}", json={"required": True}, headers=other_h).status_code == 404
    assert client.delete(f"/plan-items/{item}", headers=other_h).status_code == 404
    assert client.post(f"/children/{c}/plan/reorder", json={"item_ids": [item]}, headers=other_h).status_code == 404
    assert plan_of(client, fam).json() == before

    # their own child still works and is independent
    assert put_plan(client, other_h, other_child).status_code == 201
    assert plan_of(client, fam).json() == before


def test_deleting_a_video_keeps_positions_dense_and_bumps_the_plan_version(client, engine) -> None:
    h, family_id = make_family(client, "del@example.com")
    child = make_child(client, h)
    videos = [make_video(engine, family_id, n) for n in range(1, 4)]
    items = make_plan(client, h, child, videos)
    before = client.get(f"/children/{child}/plan", headers=h).json()["version"]
    assert client.delete(f"/videos/{videos[0]}", headers=h).status_code == 204
    plan = client.get(f"/children/{child}/plan", headers=h).json()
    assert [(i["id"], i["position"]) for i in plan["items"]] == [(items[1], 0), (items[2], 1)]
    assert plan["version"] == before + 1
