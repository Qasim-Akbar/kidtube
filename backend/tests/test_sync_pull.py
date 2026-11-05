from sqlalchemy.orm import Session

from app.models import Video
from tests.helpers import add_item, bearer, make_child, make_family, make_plan, make_video, pair, put_plan, relogin


def pull(client, headers, **h):
    return client.get("/sync/pull", headers={**headers, **h})


def setup_family(client, engine):
    h, family_id = make_family(client)
    ali = make_child(client, h, "Ali", avatar="cat", daily_app_minutes=60)
    sara = make_child(client, h, "Sara")  # no plan
    videos = [make_video(engine, family_id, n) for n in range(1, 4)]
    items = make_plan(client, h, ali, videos[:2], item_kw=[{"required": True}, {"view_target": 2}])
    return h, family_id, ali, sara, videos, items


def test_snapshot_content(client, engine, clock) -> None:
    h, family_id, ali, sara, videos, items = setup_family(client, engine)
    r = pull(client, h)
    assert r.status_code == 200
    body = r.json()
    assert body["timezone"] == "Asia/Karachi"
    assert body["server_time"].startswith("2026-01-05T10:00:00")
    assert [c["name"] for c in body["children"]] == ["Ali", "Sara"]
    ali_out, sara_out = body["children"]
    assert (ali_out["id"], ali_out["avatar"], ali_out["daily_app_minutes"]) == (ali, "cat", 60)
    assert sara_out["plan"] is None
    plan = ali_out["plan"]
    assert plan["child_id"] == ali and plan["version"] == 3 and plan["pace_value"] == 1
    assert [i["id"] for i in plan["items"]] == items  # ordered
    assert [i["video"]["title"] for i in plan["items"]] == ["Video 1", "Video 2"]
    assert plan["items"][0]["required"] is True and plan["items"][1]["view_target"] == 2
    assert {v["id"] for v in body["videos"]} == set(videos)  # includes videos that are not in any plan
    # same shapes as the existing endpoints
    assert plan == client.get(f"/children/{ali}/plan", headers=h).json()
    assert body["videos"] == client.get("/videos", headers=h).json()
    assert body["children"] == [
        {**c, "plan": plan if c["id"] == ali else None} for c in client.get("/children", headers=h).json()
    ]
    assert r.headers["etag"] == f'"{body["snapshot_version"]}"'


def test_empty_family(client) -> None:
    h, _ = make_family(client)
    body = pull(client, h).json()
    assert body["children"] == [] and body["videos"] == []


def test_etag_and_304(client, engine, clock) -> None:
    h, *_ = setup_family(client, engine)
    first = pull(client, h)
    etag = first.headers["etag"]
    clock.advance(hours=3)  # server_time changes but the content does not
    h = relogin(client)  # access tokens last 15 fake minutes
    again = pull(client, h)
    assert again.headers["etag"] == etag and again.json()["server_time"] != first.json()["server_time"]
    hit = pull(client, h, **{"If-None-Match": etag})
    assert hit.status_code == 304 and hit.content == b"" and hit.headers["etag"] == etag
    for variant in (f"W/{etag}", f'"nope", {etag}', "*"):
        assert pull(client, h, **{"If-None-Match": variant}).status_code == 304
    assert pull(client, h, **{"If-None-Match": '"stale"'}).status_code == 200


def test_etag_changes_after_each_kind_of_change(client, engine) -> None:
    h, family_id, ali, sara, videos, items = setup_family(client, engine)
    seen = [pull(client, h).headers["etag"]]

    def changed() -> None:
        etag = pull(client, h).headers["etag"]
        assert etag not in seen
        seen.append(etag)

    client.patch(f"/children/{sara}", json={"name": "Sara K"}, headers=h); changed()  # child
    client.patch(f"/children/{ali}", json={"daily_app_minutes": 30}, headers=h); changed()
    make_child(client, h, "Zed"); changed()
    make_video(engine, family_id, 9); changed()  # video
    with Session(engine) as s:  # a background re-verification fills in the title
        s.query(Video).filter_by(platform_video_id="vid00000009").one().title = "New title"
        s.commit()
    changed()
    put_plan(client, h, ali, pace_value=2); changed()  # plan config
    client.patch(f"/plan-items/{items[1]}", json={"view_target": 3}, headers=h); changed()  # plan item
    add_item(client, h, ali, videos[2]); changed()
    bad = client.post(f"/children/{ali}/plan/reorder", json={"item_ids": items[::-1]}, headers=h)
    assert bad.status_code == 422  # the new item is missing, so nothing changes
    assert pull(client, h).headers["etag"] == seen[-1]
    plan = client.get(f"/children/{ali}/plan", headers=h).json()
    ids = [i["id"] for i in plan["items"]]
    client.post(f"/children/{ali}/plan/reorder", json={"item_ids": ids[::-1]}, headers=h); changed()
    client.delete(f"/plan-items/{items[0]}", headers=h); changed()
    # an idempotent no-op PUT changes nothing
    put_plan(client, h, ali, pace_value=2)
    assert pull(client, h).headers["etag"] == seen[-1]


def test_deleted_video_disappears(client, engine) -> None:
    h, family_id, ali, sara, videos, items = setup_family(client, engine)
    assert client.delete(f"/videos/{videos[0]}", headers=h).status_code == 204
    body = pull(client, h).json()
    assert videos[0] not in {v["id"] for v in body["videos"]}
    plan_items = body["children"][0]["plan"]["items"]
    assert [(i["id"], i["position"]) for i in plan_items] == [(items[1], 0)]  # positions stay dense
    assert videos[0] not in {i["video_id"] for i in plan_items}


def test_device_can_pull_and_gets_same_snapshot(client, engine) -> None:
    h, *_ = setup_family(client, engine)
    kid = bearer(pair(client, h)["device_token"])
    assert pull(client, kid).json() == pull(client, h).json()
    assert pull(client, kid, **{"If-None-Match": pull(client, kid).headers["etag"]}).status_code == 304


def test_requires_auth(client) -> None:
    assert client.get("/sync/pull").status_code == 401
    assert client.get("/sync/pull", headers=bearer("junk")).status_code == 401


def test_cross_family_isolation(client, engine) -> None:
    h, *_ = setup_family(client, engine)
    other, other_family = make_family(client, "b@example.com")
    assert pull(client, other).json()["children"] == [] and pull(client, other).json()["videos"] == []
    other_child = make_child(client, other, "Omar")
    mine = pull(client, h).json()
    theirs = pull(client, other).json()
    assert [c["id"] for c in theirs["children"]] == [other_child]
    assert other_child not in {c["id"] for c in mine["children"]}
    assert pull(client, h).headers["etag"] != pull(client, other).headers["etag"]
    kid = bearer(pair(client, other)["device_token"])
    assert [c["id"] for c in pull(client, kid).json()["children"]] == [other_child]


def test_query_count_is_constant(client, engine) -> None:
    from sqlalchemy import event

    h, family_id, ali, sara, videos, items = setup_family(client, engine)
    count = {"n": 0}
    event.listen(engine, "before_cursor_execute", lambda *a, **k: count.__setitem__("n", count["n"] + 1))
    pull(client, h)
    small = count["n"]
    for n in range(10, 20):
        c = make_child(client, h, f"Kid{n}")
        v = make_video(engine, family_id, n)
        make_plan(client, h, c, [v])
    count["n"] = 0
    pull(client, h)
    assert count["n"] == small
