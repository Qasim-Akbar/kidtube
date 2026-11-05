"""Pure URL parsing for shared video links (no network, no DB).

Strict by design: the backend must not become an open "save any URL" endpoint, and the parsed
video ID is later used to build outbound requests, so only exact/real-subdomain hosts and
validated IDs are accepted.
"""

import re
from typing import NamedTuple
from urllib.parse import parse_qs, urlsplit

from app.models.enums import Platform

MAX_URL_LENGTH = 2048

YOUTUBE_ID = re.compile(r"[A-Za-z0-9_-]{11}")
FB_NUMERIC_ID = re.compile(r"[0-9]{8,25}")
FB_CODE = re.compile(r"[A-Za-z0-9_-]{3,50}")
FB_PAGE = re.compile(r"[A-Za-z0-9._-]{1,100}")
HOST_CHARS = re.compile(r"[a-z0-9.-]+")

YOUTUBE_DOMAINS = ("youtube.com", "youtu.be")
FACEBOOK_DOMAIN = "facebook.com"
FB_WATCH_DOMAIN = "fb.watch"


class VideoUrlError(ValueError):
    """The input is not a single, recognised video URL."""


class ParsedVideo(NamedTuple):
    platform: Platform
    platform_video_id: str
    canonical_url: str


def _host_matches(host: str, domain: str) -> bool:
    return host == domain or host.endswith("." + domain)


def _segments(path: str) -> list[str]:
    if not path.startswith("/"):
        return []
    trimmed = path[1:]
    if trimmed.endswith("/"):
        trimmed = trimmed[:-1]
    segments = trimmed.split("/") if trimmed else []
    if any(s == "" for s in segments):
        raise VideoUrlError("Malformed URL path")
    return segments


def _single_query_value(query: str, key: str) -> str | None:
    values = parse_qs(query, keep_blank_values=True).get(key, [])
    if len(values) > 1:
        raise VideoUrlError(f"Ambiguous URL: repeated '{key}' parameter")
    return values[0] if values else None


def _youtube(host: str, segments: list[str], query: str) -> ParsedVideo:
    video_id: str | None = None
    if _host_matches(host, "youtu.be"):
        if len(segments) == 1:
            video_id = segments[0]
    elif segments == ["watch"]:
        video_id = _single_query_value(query, "v")
    elif len(segments) == 2 and segments[0] in ("shorts", "embed", "live"):
        video_id = segments[1]

    if video_id is None:
        raise VideoUrlError("YouTube URL does not point to a single video (playlists and channels are not supported)")
    if not YOUTUBE_ID.fullmatch(video_id):
        raise VideoUrlError("Invalid YouTube video ID")
    return ParsedVideo(Platform.youtube, video_id, f"https://www.youtube.com/watch?v={video_id}")


def _facebook(host: str, segments: list[str], query: str) -> ParsedVideo:
    if _host_matches(host, FB_WATCH_DOMAIN):
        if len(segments) == 1 and FB_CODE.fullmatch(segments[0]):
            code = segments[0]
            return ParsedVideo(Platform.facebook, f"fb.watch:{code}", f"https://fb.watch/{code}/")
        raise VideoUrlError("Invalid fb.watch link")

    if segments == ["watch"]:
        vid = _single_query_value(query, "v")
        if vid and FB_NUMERIC_ID.fullmatch(vid):
            return ParsedVideo(Platform.facebook, vid, f"https://www.facebook.com/watch/?v={vid}")
    elif len(segments) == 3 and segments[1] == "videos" and FB_PAGE.fullmatch(segments[0]):
        if FB_NUMERIC_ID.fullmatch(segments[2]):
            vid = segments[2]
            return ParsedVideo(Platform.facebook, vid, f"https://www.facebook.com/watch/?v={vid}")
    elif len(segments) == 2 and segments[0] == "reel" and FB_NUMERIC_ID.fullmatch(segments[1]):
        vid = segments[1]
        return ParsedVideo(Platform.facebook, vid, f"https://www.facebook.com/reel/{vid}")
    elif len(segments) == 3 and segments[:2] == ["share", "v"] and FB_CODE.fullmatch(segments[2]):
        code = segments[2]
        return ParsedVideo(Platform.facebook, f"share:{code}", f"https://www.facebook.com/share/v/{code}/")
    raise VideoUrlError("Unrecognised Facebook video URL")


def parse_video_url(raw: str) -> ParsedVideo:
    """Parse one video URL into (platform, platform_video_id, canonical_url) or raise VideoUrlError."""
    if not isinstance(raw, str):
        raise VideoUrlError("URL must be a string")
    url = raw.strip()
    if not url:
        raise VideoUrlError("URL is empty")
    if len(url) > MAX_URL_LENGTH:
        raise VideoUrlError("URL is too long")
    if any(c.isspace() or ord(c) < 32 or ord(c) == 127 for c in url):
        raise VideoUrlError("Expected a single URL with no surrounding text")
    if "\\" in url:
        raise VideoUrlError("Malformed URL")

    try:
        parts = urlsplit(url)
        port = parts.port
    except ValueError:
        raise VideoUrlError("Malformed URL") from None

    if parts.scheme.lower() not in ("http", "https"):
        raise VideoUrlError("URL must start with http:// or https://")
    if "@" in parts.netloc or parts.username is not None or parts.password is not None:
        raise VideoUrlError("URLs with credentials are not allowed")
    if port not in (None, 80, 443):
        raise VideoUrlError("Unexpected port in URL")
    host = (parts.hostname or "").lower()
    if not host or not HOST_CHARS.fullmatch(host) or host.startswith(".") or host.endswith(".") or ".." in host:
        raise VideoUrlError("Invalid host")

    segments = _segments(parts.path)
    if any(_host_matches(host, d) for d in YOUTUBE_DOMAINS):
        return _youtube(host, segments, parts.query)
    if _host_matches(host, FACEBOOK_DOMAIN) or _host_matches(host, FB_WATCH_DOMAIN):
        return _facebook(host, segments, parts.query)
    raise VideoUrlError("Only YouTube and Facebook links are supported")
