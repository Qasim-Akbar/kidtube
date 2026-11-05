import pytest

from app.models.enums import Platform
from app.services.video_urls import VideoUrlError, parse_video_url

YT = "dQw4w9WgXcQ"
YT_CANON = f"https://www.youtube.com/watch?v={YT}"
FB = "1234567890123456"
FB_WATCH = f"https://www.facebook.com/watch/?v={FB}"


@pytest.mark.parametrize(
    "raw",
    [
        f"https://www.youtube.com/watch?v={YT}",
        f"http://youtube.com/watch?v={YT}",
        f"https://youtube.com/watch?v={YT}&si=abc&feature=share&t=42s&list=PLxyz&index=2",
        f"https://www.youtube.com/watch?list=PLxyz&v={YT}",
        f"https://m.youtube.com/watch?v={YT}",
        f"https://music.youtube.com/watch?v={YT}&si=x",
        f"https://youtu.be/{YT}",
        f"https://youtu.be/{YT}?si=abc&t=3",
        f"https://youtu.be/{YT}/",
        f"https://www.youtube.com/shorts/{YT}?feature=share",
        f"https://www.youtube.com/embed/{YT}",
        f"https://www.youtube.com/live/{YT}?si=zzz",
        f"HTTPS://WWW.YOUTUBE.COM/watch?v={YT}",
        f"  https://youtu.be/{YT}\n",
        f"https://www.youtube.com:443/watch?v={YT}",
        f"https://www.youtube.com/watch?v={YT}#t=10",
    ],
)
def test_youtube_valid_variants_share_one_canonical_form(raw: str) -> None:
    assert parse_video_url(raw) == (Platform.youtube, YT, YT_CANON)


@pytest.mark.parametrize("vid", ["a-b_c-d_e-f", "-_-_-_-_-_-", "AAAAAAAAAAA", "aaaaaaaaaaa", "0123456789a"])
def test_youtube_id_charset_and_case_preserved(vid: str) -> None:
    assert parse_video_url(f"https://youtu.be/{vid}").platform_video_id == vid


@pytest.mark.parametrize(
    "raw,expected",
    [
        (f"https://www.facebook.com/watch/?v={FB}", (Platform.facebook, FB, FB_WATCH)),
        (f"https://facebook.com/watch?v={FB}", (Platform.facebook, FB, FB_WATCH)),
        (f"https://m.facebook.com/watch/?v={FB}&ref=sharing", (Platform.facebook, FB, FB_WATCH)),
        (f"https://web.facebook.com/watch/?v={FB}", (Platform.facebook, FB, FB_WATCH)),
        (f"https://www.facebook.com/SomePage/videos/{FB}", (Platform.facebook, FB, FB_WATCH)),
        (f"https://www.facebook.com/Some.Page-1/videos/{FB}/?idorvanity=1", (Platform.facebook, FB, FB_WATCH)),
        (
            f"https://www.facebook.com/reel/{FB}?s=yWDuG2&fs=e",
            (Platform.facebook, FB, f"https://www.facebook.com/reel/{FB}"),
        ),
        ("https://fb.watch/abC123_x-Y/", (Platform.facebook, "fb.watch:abC123_x-Y", "https://fb.watch/abC123_x-Y/")),
        ("https://fb.watch/abC123", (Platform.facebook, "fb.watch:abC123", "https://fb.watch/abC123/")),
        (
            "https://www.facebook.com/share/v/1AbCdEfGhI/?mibextid=wwXIfr",
            (Platform.facebook, "share:1AbCdEfGhI", "https://www.facebook.com/share/v/1AbCdEfGhI/"),
        ),
    ],
)
def test_facebook_valid_variants(raw: str, expected: tuple) -> None:
    assert parse_video_url(raw) == expected


HOSTILE = [
    # lookalike / wrong hosts
    f"https://youtube.com.evil.com/watch?v={YT}",
    f"https://evil.com/youtube.com/watch?v={YT}",
    f"https://evil.com/watch?v={YT}",
    f"https://notyoutube.com/watch?v={YT}",
    f"https://youtube.com.evil/watch?v={YT}",
    f"https://evilyoutu.be/{YT}",
    f"https://youtu.be.evil.com/{YT}",
    f"https://evil.com/?u=https://youtu.be/{YT}",
    f"https://www.youtube.com.evil.com/shorts/{YT}",
    f"https://youtube.co/watch?v={YT}",
    f"https://youtube-com.evil.com/watch?v={YT}",
    f"https://127.0.0.1/watch?v={YT}",
    f"https://localhost/watch?v={YT}",
    f"https://youtube.com./watch?v={YT}",
    f"https://.youtube.com/watch?v={YT}",
    f"https://youtube%2ecom/watch?v={YT}",
    f"https://%79outube.com/watch?v={YT}",
    f"https://youtubе.com/watch?v={YT}",  # cyrillic 'е'
    f"https://www.facebook.com.evil.com/watch/?v={FB}",
    f"https://evil.com/facebook.com/watch/?v={FB}",
    f"https://notfacebook.com/watch/?v={FB}",
    "https://fb.watch.evil.com/abC123/",
    "https://evilfb.watch/abC123/",
    # userinfo / ports / backslashes
    f"https://www.youtube.com@evil.com/watch?v={YT}",
    f"https://user:pw@www.youtube.com/watch?v={YT}",
    f"https://evil.com@youtu.be/{YT}",
    f"https://evil.com\\@youtube.com/watch?v={YT}",
    f"https://www.youtube.com\\.evil.com/watch?v={YT}",
    f"https://www.youtube.com:8080/watch?v={YT}",
    f"https://www.youtube.com:evil/watch?v={YT}",
    f"https://user@www.facebook.com/watch/?v={FB}",
    # schemes
    "javascript:alert(1)",
    f"javascript://www.youtube.com/watch?v={YT}%0Aalert(1)",
    f"file:///etc/passwd?v={YT}",
    "file://www.youtube.com/watch?v=" + YT,
    "data:text/html,<script>alert(1)</script>",
    f"ftp://www.youtube.com/watch?v={YT}",
    f"//www.youtube.com/watch?v={YT}",
    f"www.youtube.com/watch?v={YT}",
    f"youtu.be/{YT}",
    f"https:/www.youtube.com/watch?v={YT}",
    # bad IDs
    "https://www.youtube.com/watch?v=" + YT[:10],
    "https://www.youtube.com/watch?v=" + YT + "X",
    "https://youtu.be/" + YT[:10],
    "https://youtu.be/" + YT + "X",
    "https://www.youtube.com/shorts/" + YT[:10],
    "https://www.youtube.com/embed/" + YT + "XX",
    "https://www.youtube.com/watch?v=dQw4w9WgXc!",
    "https://www.youtube.com/watch?v=dQw4w9WgXc%00",
    "https://www.youtube.com/watch?v=%3Cscript%3E123",
    "https://www.youtube.com/watch?v=",
    "https://www.youtube.com/watch",
    f"https://www.youtube.com/watch?x={YT}",
    f"https://www.youtube.com/watch?v={YT}&v=aaaaaaaaaaa",
    "https://youtu.be/",
    f"https://youtu.be/{YT}/extra",
    f"https://www.youtube.com/shorts/{YT}/extra",
    f"https://www.youtube.com//shorts/{YT}",
    # playlists, channels, other pages
    "https://www.youtube.com/playlist?list=PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf",
    "https://www.youtube.com/channel/UCuAXFkgsw1L7xaCfnd5JJOw",
    "https://www.youtube.com/@somechannel",
    "https://www.youtube.com/c/somechannel",
    "https://www.youtube.com/results?search_query=cats",
    "https://www.youtube.com/feed/subscriptions",
    "https://www.youtube.com/",
    "https://youtube.com",
    f"https://www.youtube.com/watch/{YT}",
    f"https://www.youtube.com/v/{YT}",
    # facebook shapes that are not single videos / bad ids
    "https://www.facebook.com/watch/",
    "https://www.facebook.com/watch/?v=abc",
    "https://www.facebook.com/watch/?v=123",
    f"https://www.facebook.com/watch/?v={FB}&v={FB}1",
    "https://www.facebook.com/SomePage/videos/notnumeric",
    "https://www.facebook.com/SomePage/videos/",
    "https://www.facebook.com/reel/abc",
    "https://www.facebook.com/share/v/",
    "https://www.facebook.com/share/r/abc123",
    "https://www.facebook.com/SomePage",
    "https://www.facebook.com/",
    "https://fb.watch/",
    "https://fb.watch/a",
    "https://fb.watch/abc/def",
    # not a single bare URL
    "",
    "   ",
    "not a url",
    f"check this out https://youtu.be/{YT} cool",
    f"https://youtu.be/{YT} https://youtu.be/{YT}",
    f"https://youtu.be/{YT}\nhttps://youtu.be/{YT}",
    f"https://youtu.be/{YT}\x00",
    f"https://you\ttube.com/watch?v={YT}",
    "https://youtu.be/" + "a" * 3000,
]


@pytest.mark.parametrize("raw", HOSTILE)
def test_hostile_or_unsupported_inputs_rejected(raw: str) -> None:
    with pytest.raises(VideoUrlError):
        parse_video_url(raw)


def test_non_string_rejected() -> None:
    with pytest.raises(VideoUrlError):
        parse_video_url(None)  # type: ignore[arg-type]


def test_errors_have_clear_messages() -> None:
    with pytest.raises(VideoUrlError, match="playlists"):
        parse_video_url("https://www.youtube.com/playlist?list=PL123")
    with pytest.raises(VideoUrlError, match="single URL"):
        parse_video_url(f"look https://youtu.be/{YT}")
    with pytest.raises(VideoUrlError, match="credentials"):
        parse_video_url(f"https://a@youtu.be/{YT}")
    with pytest.raises(VideoUrlError, match="YouTube and Facebook"):
        parse_video_url("https://vimeo.com/12345678")


def test_subdomains_are_real_subdomains_only() -> None:
    assert parse_video_url(f"https://music.youtube.com/watch?v={YT}").platform_video_id == YT
    with pytest.raises(VideoUrlError):
        parse_video_url(f"https://myoutube.com/watch?v={YT}")
