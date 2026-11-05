import uuid

import httpx
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.models import Event, Video
from tests.helpers import bearer, ev, make_child, make_family, make_plan, make_video, pair, view_ev

URL_A = "https://www.youtube.com/watch?v=aaaaaaaaaaa"
URL_B = "https://youtu.be/bbbbbbbbbbb"


def push(client, headers, videos=(), events=()):
    return client.post("/sync/push", json={"videos": list(videos), "events": list(events)}, headers=headers)


def counts(engine) -> tuple[int, int]:
    with Session(engine) as s:
        return s.scalar(select(func.count()).select_from(Video)), s.scalar(select(func.count()).select_from(Event))


def setup(client, engine):
    h, family_id = make_family(client)
    child = make_child(client, h)
    vids = [make_video(engine, family_id, n) for n in (1, 2)]
    items = make_plan(client, h, child, vids, item_kw=[{"view_target": 1}] * 2)
    return h, family_id, child, vids, items


def test_mixed_batch(client, engine, oembed) -> None:
    oembed.respond()
    h, family_id, child, vids, items = setup(client, engine)
    saved = client.post("/videos", json={"url": URL_B}, headers=h).json()  # already in the library
    good = view_ev(child, items[0])
    batch_videos = [
        {"url": URL_A},                       # 0 new
        {"url": "https://example.com/x"},     # 1 invalid
        {"url": URL_B},                       # 2 duplicate of a saved video
        {"url": URL_A},                       # 3 duplicate within the batch
        {"nope": 1},                          # 4 no url
        "https://www.youtube.com/watch?v=aaaaaaaaaaa",  # 5 not an object
        {"url": "see https://youtu.be/ccccccccccc please"},  # 6 text around the link
    ]
    batch_events = [
        good,                                                              # 0 accepted
        {**good},                                                          # 1 duplicate id
        ev(child, "view_complete", {"plan_item_id": "junk", "watched_pct": 1}),  # 2 bad payload
        ev(str(uuid.uuid4()), "watch_progress", {"video_id": vids[0], "seconds": 3}),  # 3 unknown child
        ev(child, "watch_progress", {"video_id": vids[0], "seconds": 3}),  # 4 accepted
    ]
    r = push(client, h, batch_videos, batch_events)
    assert r.status_code == 200, r.text
    v, e = r.json()["videos"], r.json()["events"]
    assert [x["status"] for x in v] == ["created", "rejected", "existing", "existing", "rejected", "rejected", "rejected"]
    assert [x["index"] for x in v] == list(range(7))
    assert v[0]["video_id"] and v[0]["video_status"] == "verified" and v[0]["retryable"] is False
    assert v[2]["video_id"] == saved["id"] and v[3]["video_id"] == v[0]["video_id"]
    assert all(x["retryable"] is False and x["video_id"] is None and x["reason"] for x in v if x["status"] == "rejected")
    assert [x["status"] for x in e] == ["accepted", "duplicate", "rejected", "rejected", "accepted"]
    assert e[2]["reason"] == "invalid payload.plan_item_id" and e[3]["reason"] == "unknown child"
    assert all(x["retryable"] is False for x in e)
    assert counts(engine) == (2 + 2, 2)  # 2 helper videos + URL_A + URL_B; 2 events

    # the same video through POST /videos is the same row (shared logic)
    assert client.post("/videos", json={"url": URL_A}, headers=h).json()["id"] == v[0]["video_id"]


def test_retrying_the_identical_batch_changes_nothing(client, engine, oembed) -> None:
    oembed.respond()
    h, family_id, child, vids, items = setup(client, engine)
    videos = [{"url": URL_A}, {"url": URL_B}, {"url": "nonsense"}]
    events = [view_ev(child, items[0]), ev(child, "watch_progress", {"video_id": vids[0], "seconds": 9})]
    first = push(client, h, videos, events).json()
    state = counts(engine)
    sent = len(oembed.requests)
    second = push(client, h, videos, events).json()
    assert counts(engine) == state
    assert len(oembed.requests) == sent  # duplicates trigger no outbound request
    assert [x["status"] for x in second["videos"]] == ["existing", "existing", "rejected"]
    assert [x["video_id"] for x in second["videos"]] == [x["video_id"] for x in first["videos"]]
    assert [x["status"] for x in second["events"]] == ["duplicate", "duplicate"]
    assert [x["id"] for x in second["events"]] == [x["id"] for x in first["events"]]


def test_oembed_trouble_creates_a_pending_video_and_is_flagged_retryable(client, engine, oembed) -> None:
    h, *_ = setup(client, engine)
    oembed.raise_error(httpx.ConnectTimeout("slow"))
    r = push(client, h, [{"url": URL_A}]).json()["videos"][0]
    assert r["status"] == "created" and r["video_status"] == "pending" and r["retryable"] is True and r["reason"] is None
    assert counts(engine)[0] == 3
    # a later reverify (the documented way to finish it) works, and re-pushing is a harmless "existing"
    oembed.respond()
    assert client.post(f"/videos/{r['video_id']}/reverify", headers=h).json()["status"] == "verified"
    again = push(client, h, [{"url": URL_A}]).json()["videos"][0]
    assert again["status"] == "existing" and again["retryable"] is False and again["video_status"] == "verified"


def test_unavailable_video_is_created_not_rejected(client, engine, oembed) -> None:
    h, *_ = setup(client, engine)
    oembed.respond(404)
    r = push(client, h, [{"url": URL_A}]).json()["videos"][0]
    assert (r["status"], r["video_status"], r["retryable"]) == ("created", "unavailable", False)


def test_facebook_link_is_created_pending_and_not_retryable(client, engine) -> None:
    h, *_ = setup(client, engine)
    r = push(client, h, [{"url": "https://www.facebook.com/watch/?v=1234567890123"}]).json()["videos"][0]
    assert (r["status"], r["video_status"], r["retryable"]) == ("created", "pending", False)


def test_kid_device_videos_are_forbidden_but_events_are_processed(client, engine, oembed) -> None:
    h, family_id, child, vids, items = setup(client, engine)
    kid = bearer(pair(client, h)["device_token"])
    r = push(client, kid, [{"url": URL_A}, {"url": "junk"}], [view_ev(child, items[0]), ev(child, "time_extension", {"seconds": 60})])
    assert r.status_code == 200
    body = r.json()
    assert [(x["status"], x["reason"], x["retryable"], x["video_id"]) for x in body["videos"]] == [
        ("rejected", "forbidden", False, None)
    ] * 2
    assert [x["status"] for x in body["events"]] == ["accepted", "rejected"]  # time_extension is parent-only
    assert counts(engine) == (2, 1)
    assert oembed.requests == []
    with Session(engine) as s:
        assert s.scalar(select(Event.device_id)) is not None


def test_limits_and_auth(client, engine) -> None:
    h, family_id, child, vids, items = setup(client, engine)
    assert client.post("/sync/push", json={"videos": [{"url": URL_A}] * 51}, headers=h).status_code == 422
    assert client.post("/sync/push", json={"events": [{}] * 201}, headers=h).status_code == 422
    r = client.post("/sync/push", json={"events": [{}] * 200}, headers=h)
    assert r.status_code == 200 and {x["status"] for x in r.json()["events"]} == {"rejected"}
    assert push(client, h).json() == {"videos": [], "events": []}
    assert client.post("/sync/push", json={}).status_code == 401
    assert client.post("/sync/push", json={}, headers=bearer("junk")).status_code == 401


def test_cross_family_push(client, engine, oembed) -> None:
    oembed.respond()
    h, family_id, child, vids, items = setup(client, engine)
    other, _ = make_family(client, "b@example.com")
    r = push(client, other, [{"url": URL_A}], [view_ev(child, items[0])]).json()
    assert r["videos"][0]["status"] == "created"  # the other family has its own library
    assert r["events"][0]["status"] == "rejected" and r["events"][0]["reason"] == "unknown child"
    assert counts(engine)[1] == 0
