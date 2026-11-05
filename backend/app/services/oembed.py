"""YouTube oEmbed verification.

SSRF safety: this module never fetches a user-supplied URL. The request is built from a fixed
host plus an already-validated 11-character video ID; redirects are not followed, the response
size is capped, and the scheme/host of the built request are asserted before sending.
"""

import json
import re
from dataclasses import dataclass
from urllib.parse import urlsplit

import httpx

from app.models.enums import VideoStatus

OEMBED_HOST = "www.youtube.com"
OEMBED_URL = f"https://{OEMBED_HOST}/oembed"
TIMEOUT_SECONDS = 5.0
MAX_RESPONSE_BYTES = 64 * 1024
MAX_TITLE_LENGTH = 500

_YOUTUBE_ID = re.compile(r"[A-Za-z0-9_-]{11}")
_UNAVAILABLE_STATUSES = frozenset({401, 403, 404})


@dataclass(frozen=True)
class OEmbedResult:
    """`conclusive` is False when YouTube could not be asked (timeout/5xx/...): keep the old status and retry later."""

    status: VideoStatus
    title: str | None = None
    thumbnail_url: str | None = None
    conclusive: bool = True


_INCONCLUSIVE = OEmbedResult(status=VideoStatus.pending, conclusive=False)


def _thumbnail(candidate: object, video_id: str) -> str:
    fallback = f"https://i.ytimg.com/vi/{video_id}/hqdefault.jpg"
    if not isinstance(candidate, str) or len(candidate) > 2048:
        return fallback
    parts = urlsplit(candidate)
    host = (parts.hostname or "").lower()
    if parts.scheme == "https" and (host == "ytimg.com" or host.endswith(".ytimg.com")):
        return candidate
    return fallback


def check_youtube_video(client: httpx.Client, video_id: str) -> OEmbedResult:
    if not _YOUTUBE_ID.fullmatch(video_id):
        raise ValueError("invalid YouTube video ID")

    request = client.build_request(
        "GET",
        OEMBED_URL,
        params={"url": f"https://www.youtube.com/watch?v={video_id}", "format": "json"},
        timeout=TIMEOUT_SECONDS,
    )
    if request.url.scheme != "https" or request.url.host != OEMBED_HOST or request.url.port not in (None, 443):
        raise RuntimeError("refusing to send oEmbed request to an unexpected host")  # pragma: no cover

    try:
        response = client.send(request, stream=True, follow_redirects=False)
    except httpx.HTTPError:  # timeouts, connection errors, protocol errors
        return _INCONCLUSIVE
    try:
        if response.status_code in _UNAVAILABLE_STATUSES:
            return OEmbedResult(status=VideoStatus.unavailable)
        if response.status_code != 200:  # 3xx (not followed), 429, 5xx, ...
            return _INCONCLUSIVE
        declared = response.headers.get("content-length", "")
        if declared.isdigit() and int(declared) > MAX_RESPONSE_BYTES:
            return _INCONCLUSIVE
        body = bytearray()
        for chunk in response.iter_bytes():
            body += chunk
            if len(body) > MAX_RESPONSE_BYTES:
                return _INCONCLUSIVE
    except httpx.HTTPError:
        return _INCONCLUSIVE
    finally:
        response.close()

    try:
        data = json.loads(bytes(body))
    except ValueError:
        return _INCONCLUSIVE
    title = data.get("title") if isinstance(data, dict) else None
    if not isinstance(title, str) or not title.strip():
        return _INCONCLUSIVE
    return OEmbedResult(
        status=VideoStatus.verified,
        title=title.strip()[:MAX_TITLE_LENGTH],
        thumbnail_url=_thumbnail(data.get("thumbnail_url"), video_id),
    )
