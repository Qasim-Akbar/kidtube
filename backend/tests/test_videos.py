import json
import uuid
from datetime import date

import httpx
import pytest
from fastapi.testclient import TestClient
from sqlalchemy import Engine, func, select
from sqlalchemy.orm import Session

from app.models import Child, Plan, PlanItem, Video
from tests.conftest import OEmbedStub
from tests.helpers import bearer, pair, parent_session

YT = "dQw4w9WgXcQ"
YT2 = "9bZkp7q19f0"
FB = "1234567890123456"


@pytest.fixture
def parent(client: TestClient) -> dict[str, str]:
    return parent_session(client, "a@example.com")


def add(client: TestClient, headers: dict[str, str], url: str):
    return client.post("/videos", json={"url": url}, headers=headers)


def assert_only_youtube_oembed(oembed: OEmbedStub, expected_ids: list[str]) -> None:
    got = []
    for req in oembed.requests:
        assert req.url.scheme == "https" and req.url.host == "www.youtube.com" and req.url.path == "/oembed"
        assert req.method == "GET"
        watch = req.url.params["url"]
        assert watch.startswith("https://www.youtube.com/watch?v=") and "&" not in watch
        got.append(watch.rsplit("=", 1)[1])
        assert req.url.params["format"] == "json"
    assert got == expected_ids


# ---------- create + oEmbed outcomes ----------

def test_youtube_200_is_verified_with_title_and_thumbnail(client, parent, oembed: OEmbedStub) -> None:
    oembed.respond(200)
    r = add(client, parent, f"https://youtu.be/{YT}?si=track")
    assert r.status_code == 201
    v = r.json()
    assert v["status"] == "verified" and v["title"] == f"Title {YT}"
    assert v["thumbnail_url"] == f"https://i.ytimg.com/vi/{YT}/hqdefault.jpg"
    assert v["platform"] == "youtube" and v["platform_video_id"] == YT
    assert v["url"] == f"https://www.youtube.com/watch?v={YT}"  # canonical, tracking params dropped
    assert "family_id" not in v
    assert_only_youtube_oembed(oembed, [YT])


@pytest.mark.parametrize("code", [401, 403, 404])
def test_youtube_401_403_404_is_unavailable(client, parent, oembed: OEmbedStub, code: int) -> None:
    oembed.respond(code, json={"error": "nope"})
    r = add(client, parent, f"https://youtu.be/{YT}")
    assert r.status_code == 201 and r.json()["status"] == "unavailable"
    assert_only_youtube_oembed(oembed, [YT])


@pytest.mark.parametrize(
    "setup",
    [
        lambda o: o.raise_error(httpx.ReadTimeout("slow")),
        lambda o: o.raise_error(httpx.ConnectError("down")),
        lambda o: o.respond(500, json={}),
        lambda o: o.respond(503, json={}),
        lambda o: o.respond(429, json={}),
        lambda o: o.respond(200, json="not an object"),
        lambda o: o.respond(200, json={"no": "title"}),
        lambda o: o.respond(200, json={"title": "   "}),
        lambda o: o.respond(200, content=b"<html>not json</html>"),
    ],
    ids=["timeout", "network", "500", "503", "429", "json-string", "no-title", "blank-title", "html"],
)
def test_transient_failures_keep_pending_and_still_return_video(client, parent, oembed, setup) -> None:
    setup(oembed)
    r = add(client, parent, f"https://youtu.be/{YT}")
    assert r.status_code == 201
    v = r.json()
    assert v["status"] == "pending" and v["title"] is None
    assert len(oembed.requests) == 1


def test_redirects_are_never_followed(client, parent, oembed: OEmbedStub) -> None:
    oembed.respond(302, headers={"Location": "https://evil.example.com/steal"}, content=b"")
    r = add(client, parent, f"https://youtu.be/{YT}")
    assert r.status_code == 201 and r.json()["status"] == "pending"
    assert len(oembed.requests) == 1  # no follow-up request to the redirect target
    assert_only_youtube_oembed(oembed, [YT])


def test_oversized_response_is_not_trusted(client, parent, oembed: OEmbedStub) -> None:
    huge = {"title": "x" * 200_000}
    oembed.respond(200, json=huge)
    r = add(client, parent, f"https://youtu.be/{YT}")
    assert r.status_code == 201 and r.json()["status"] == "pending" and r.json()["title"] is None


def test_foreign_thumbnail_host_is_replaced_and_long_title_truncated(client, parent, oembed: OEmbedStub) -> None:
    oembed.respond(200, json={"title": "t" * 900, "thumbnail_url": "https://evil.example.com/x.jpg"})
    v = add(client, parent, f"https://youtu.be/{YT}").json()
    assert v["thumbnail_url"] == f"https://i.ytimg.com/vi/{YT}/hqdefault.jpg"
    assert len(v["title"]) == 500


def test_outbound_request_is_built_from_id_not_from_user_input(client, parent, oembed: OEmbedStub) -> None:
    oembed.respond(200)
    hostile = f"https://www.youtube.com/watch?v={YT}&url=http://169.254.169.254/latest&format=xml#@evil.com"
    assert add(client, parent, hostile).status_code == 201
    assert_only_youtube_oembed(oembed, [YT])
    assert "169.254" not in str(oembed.requests[0].url) and "evil" not in str(oembed.requests[0].url)


@pytest.mark.parametrize(
    "url",
    [
        f"https://youtube.com.evil.com/watch?v={YT}",
        f"https://evil.com/youtube.com/watch?v={YT}",
        "javascript:alert(1)",
        "file:///etc/passwd",
        f"https://user:pw@youtube.com/watch?v={YT}",
        "https://www.youtube.com/playlist?list=PL123",
        f"hey https://youtu.be/{YT}",
        "https://youtu.be/short",
    ],
)
def test_invalid_urls_422_and_no_outbound_request(client, parent, oembed: OEmbedStub, url: str) -> None:
    r = add(client, parent, url)
    assert r.status_code == 422 and isinstance(r.json()["detail"], str) and r.json()["detail"]
    assert oembed.requests == []
    assert client.get("/videos", headers=parent).json() == []


def test_missing_or_empty_url_422(client, parent) -> None:
    assert client.post("/videos", json={}, headers=parent).status_code == 422
    assert client.post("/videos", json={"url": ""}, headers=parent).status_code == 422


# ---------- dedupe / idempotency ----------

def test_dedupe_across_url_forms_201_then_200(client, parent, oembed: OEmbedStub, engine: Engine) -> None:
    oembed.respond(200)
    first = add(client, parent, f"https://youtu.be/{YT}")
    second = add(client, parent, f"https://www.youtube.com/watch?v={YT}&feature=share")
    third = add(client, parent, f"https://www.youtube.com/shorts/{YT}")
    assert (first.status_code, second.status_code, third.status_code) == (201, 200, 200)
    assert first.json() == second.json() == third.json()
    assert len(client.get("/videos", headers=parent).json()) == 1
    assert len(oembed.requests) == 1  # duplicates cost no outbound request
    with Session(engine) as s:
        assert s.scalar(select(func.count()).select_from(Video)) == 1


def test_pending_video_dedupes_too(client, parent, oembed: OEmbedStub) -> None:
    oembed.respond(500, json={})
    assert add(client, parent, f"https://youtu.be/{YT}").status_code == 201
    assert add(client, parent, f"https://youtu.be/{YT}").status_code == 200
    assert len(oembed.requests) == 1


def test_different_videos_are_separate(client, parent, oembed: OEmbedStub) -> None:
    oembed.respond(200)
    assert add(client, parent, f"https://youtu.be/{YT}").status_code == 201
    assert add(client, parent, f"https://youtu.be/{YT2}").status_code == 201
    assert [v["platform_video_id"] for v in client.get("/videos", headers=parent).json()] == [YT, YT2]


# ---------- facebook ----------

@pytest.mark.parametrize(
    "url",
    [
        f"https://www.facebook.com/watch/?v={FB}",
        f"https://www.facebook.com/SomePage/videos/{FB}",
        f"https://www.facebook.com/reel/{FB}",
        "https://fb.watch/abC123/",
        "https://www.facebook.com/share/v/1AbCdEfGhI/",
    ],
)
def test_facebook_stays_pending_with_no_outbound_request(client, parent, oembed: OEmbedStub, url: str) -> None:
    r = add(client, parent, url)
    assert r.status_code == 201
    v = r.json()
    assert v["status"] == "pending" and v["title"] == "Facebook video" and v["platform"] == "facebook"
    assert oembed.requests == []  # the autouse stub would also fail the test on any call


def test_facebook_dedupe_across_forms(client, parent) -> None:
    assert add(client, parent, f"https://www.facebook.com/watch/?v={FB}").status_code == 201
    assert add(client, parent, f"https://www.facebook.com/SomePage/videos/{FB}").status_code == 200


# ---------- reverify ----------

def test_reverify_recovers_pending_to_verified(client, parent, oembed: OEmbedStub) -> None:
    oembed.raise_error(httpx.ReadTimeout("slow"))
    vid = add(client, parent, f"https://youtu.be/{YT}").json()["id"]
    oembed.respond(200)
    r = client.post(f"/videos/{vid}/reverify", headers=parent)
    assert r.status_code == 200 and r.json()["status"] == "verified" and r.json()["title"] == f"Title {YT}"
    assert_only_youtube_oembed(oembed, [YT, YT])


def test_reverify_flags_video_that_became_unavailable_and_back(client, parent, oembed: OEmbedStub) -> None:
    oembed.respond(200)
    vid = add(client, parent, f"https://youtu.be/{YT}").json()["id"]
    oembed.respond(404, json={})
    r = client.post(f"/videos/{vid}/reverify", headers=parent)
    assert r.json()["status"] == "unavailable" and r.json()["title"] == f"Title {YT}"  # title kept
    oembed.respond(200)
    assert client.post(f"/videos/{vid}/reverify", headers=parent).json()["status"] == "verified"


@pytest.mark.parametrize("start", [200, 404])
def test_reverify_transient_failure_never_downgrades_status(client, parent, oembed: OEmbedStub, start: int) -> None:
    oembed.respond(start, json=None if start == 200 else {})
    created = add(client, parent, f"https://youtu.be/{YT}").json()
    oembed.respond(500, json={})
    r = client.post(f"/videos/{created['id']}/reverify", headers=parent)
    assert r.status_code == 200 and r.json()["status"] == created["status"]


def test_reverify_facebook_makes_no_request(client, parent, oembed: OEmbedStub) -> None:
    vid = add(client, parent, f"https://fb.watch/abC123/").json()["id"]
    r = client.post(f"/videos/{vid}/reverify", headers=parent)
    assert r.status_code == 200 and r.json()["status"] == "pending"
    assert oembed.requests == []


def test_reverify_unknown_404(client, parent) -> None:
    assert client.post(f"/videos/{uuid.uuid4()}/reverify", headers=parent).status_code == 404


# ---------- delete ----------

def test_delete_video_and_cascade_to_plan_items(client, parent, oembed: OEmbedStub, engine: Engine) -> None:
    oembed.respond(200)
    v = add(client, parent, f"https://youtu.be/{YT}").json()
    child = client.post("/children", json={"name": "Ali"}, headers=parent).json()
    with Session(engine) as s:
        plan = Plan(child_id=uuid.UUID(child["id"]), start_date=date(2026, 1, 1))
        plan.items = [PlanItem(video_id=uuid.UUID(v["id"]), position=0)]
        s.add(plan)
        s.commit()

    assert client.delete(f"/videos/{v['id']}", headers=parent).status_code == 204
    assert client.get("/videos", headers=parent).json() == []
    assert client.delete(f"/videos/{v['id']}", headers=parent).status_code == 404
    with Session(engine) as s:
        assert s.scalar(select(func.count()).select_from(PlanItem)) == 0
        assert s.scalar(select(func.count()).select_from(Plan)) == 1
        assert s.scalar(select(func.count()).select_from(Child)) == 1


def test_re_adding_after_delete_creates_fresh_video(client, parent, oembed: OEmbedStub) -> None:
    oembed.respond(200)
    first = add(client, parent, f"https://youtu.be/{YT}").json()
    client.delete(f"/videos/{first['id']}", headers=parent)
    second = add(client, parent, f"https://youtu.be/{YT}")
    assert second.status_code == 201 and second.json()["id"] != first["id"]


# ---------- authorization ----------

def test_kid_device_can_list_but_not_write(client, parent, oembed: OEmbedStub) -> None:
    oembed.respond(200)
    v = add(client, parent, f"https://youtu.be/{YT}").json()
    kid = bearer(pair(client, parent)["device_token"])
    listed = client.get("/videos", headers=kid)
    assert listed.status_code == 200 and [x["id"] for x in listed.json()] == [v["id"]]

    calls_before = len(oembed.requests)
    assert client.post("/videos", json={"url": f"https://youtu.be/{YT2}"}, headers=kid).status_code == 401
    assert client.delete(f"/videos/{v['id']}", headers=kid).status_code == 401
    assert client.post(f"/videos/{v['id']}/reverify", headers=kid).status_code == 401
    assert len(oembed.requests) == calls_before
    assert len(client.get("/videos", headers=parent).json()) == 1


def test_unauthenticated_rejected(client, parent) -> None:
    assert client.get("/videos").status_code == 401
    assert client.post("/videos", json={"url": f"https://youtu.be/{YT}"}).status_code == 401
    assert client.delete(f"/videos/{uuid.uuid4()}").status_code == 401
    assert client.post(f"/videos/{uuid.uuid4()}/reverify").status_code == 401


def test_cross_family_isolation(client, parent, oembed: OEmbedStub) -> None:
    oembed.respond(200)
    other = parent_session(client, "b@example.com")
    mine = add(client, parent, f"https://youtu.be/{YT}").json()

    assert client.get("/videos", headers=other).json() == []
    assert client.delete(f"/videos/{mine['id']}", headers=other).status_code == 404
    assert client.post(f"/videos/{mine['id']}/reverify", headers=other).status_code == 404
    assert len(client.get("/videos", headers=parent).json()) == 1

    # the same video added by the other family is independent (not deduped across families)
    theirs = add(client, other, f"https://youtu.be/{YT}")
    assert theirs.status_code == 201 and theirs.json()["id"] != mine["id"]
    kid_b = bearer(pair(client, other, "B tab")["device_token"])
    assert [v["id"] for v in client.get("/videos", headers=kid_b).json()] == [theirs.json()["id"]]


def test_oembed_request_shape_uses_no_redirect_and_timeout(client, parent, oembed: OEmbedStub) -> None:
    oembed.respond(200)
    add(client, parent, f"https://youtu.be/{YT}")
    ext = oembed.requests[0].extensions
    assert ext["timeout"]["read"] == 5.0 and ext["timeout"]["connect"] == 5.0
    assert json.dumps(dict(oembed.requests[0].url.params)).count(YT) >= 1
