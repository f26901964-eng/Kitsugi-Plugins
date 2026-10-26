import base64
import unittest
from urllib.parse import urlsplit

from tools import live_player_probe


class LivePlayerProbeTests(unittest.TestCase):
    def test_vidrame_ee_dd_decoder_matches_observed_reverse_rot13_base64url(self):
        target = "https://cdn.example/master.m3u8?token=private-value"
        rot13 = target[::-1].translate(str.maketrans(
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz",
            "NOPQRSTUVWXYZABCDEFGHIJKLMnopqrstuvwxyzabcdefghijklm",
        ))
        encoded = base64.urlsafe_b64encode(rot13.encode()).decode().rstrip("=")
        self.assertEqual(
            target,
            live_player_probe.decode_vidrame_file(f'EE.dd("{encoded}")'),
        )

    def test_parses_video_source_objects_and_keeps_kind(self):
        script = '''
            player.setup({ sources: [
                { file: EE.dd("encoded-file"), type: "application/x-mpegURL", kind: "main", label: "1080p" },
                { file: "https://cdn.example/caption.vtt", type: "text/vtt", kind: "captions" }
            ] });
        '''
        sources = live_player_probe.source_objects(script)
        self.assertEqual(1, len(sources))
        self.assertEqual('EE.dd("encoded-file")', sources[0]["file"])
        self.assertEqual("main", sources[0]["kind"])
        self.assertEqual("application/x-mpegURL", sources[0]["type"])

    def test_detects_only_hls_or_plain_https_playlist_hops(self):
        self.assertTrue(live_player_probe.hls_manifest("\ufeff  #EXTM3U\n#EXT-X-VERSION:3"))
        self.assertFalse(live_player_probe.hls_manifest("<html>challenge</html>"))
        self.assertEqual(
            "https://cdn.example/master.m3u8?token=1",
            live_player_probe.plain_https_target("  https://cdn.example/master.m3u8?token=1  "),
        )
        self.assertIsNone(live_player_probe.plain_https_target("<html>challenge</html>"))
        self.assertIsNone(live_player_probe.plain_https_target("https://user:password@cdn.example/video.m3u8"))

    def test_cloudflare_block_marker_is_not_confused_with_ordinary_html(self):
        self.assertTrue(
            live_player_probe.is_cloudflare_block(
                "Attention Required! This website uses Cloudflare. Ray ID: abc"
            )
        )
        self.assertFalse(live_player_probe.is_cloudflare_block("<html><title>Not found</title></html>"))

    def test_report_redacts_query_strings_and_embed_identifiers(self):
        value = live_player_probe.safe_url(
            "https://dbx.molystream.org/embed/sheila/9d6d2384e2cd4b6a?token=private-value"
        )
        self.assertEqual("dbx.molystream.org/embed/sheila/<id>", value)
        self.assertNotIn("private-value", value)

    def test_iframe_scan_prefers_lazy_src_and_restricts_hosts(self):
        html = '''
            <iframe src="about:blank" data-src="https://vidrame.pro/e/fixture"></iframe>
            <iframe src="https://ads.example/frame"></iframe>
            <iframe src="https://www.hdfilmizle.to/embed/player"></iframe>
        '''
        frames = live_player_probe.iframe_urls(
            html,
            "https://www.hdfilmizle.to/dizi/example/sezon-1/bolum-1/",
            prefer_data_src=True,
            domains=("hdfilmizle.to", "vidrame.pro"),
        )
        self.assertEqual(
            [
                "https://vidrame.pro/e/fixture",
                "https://www.hdfilmizle.to/embed/player",
            ],
            frames,
        )
        self.assertEqual("https", urlsplit(frames[0]).scheme)


    def test_vidrame_xor_matches_independent_vectors(self):
        # plaintext: https://cdn.example/stream/master.m3u8, key: secret
        data = [
            22, 15, 56, 66, 71, 44, 47, 206, 149, 176, 188, 146, 207, 247, 249, 19,
            8, 54, 41, 26, 113, 116, 148, 133, 183, 190, 139, 199, 237, 253, 12, 28,
            60, 98, 71, 39, 119, 223,
        ]
        self.assertEqual(
            "https://cdn.example/stream/master.m3u8",
            live_player_probe.decode_vidrame_xor(data, "secret"),
        )

    def test_spg_decode_splits_pipe_suffix(self):
        n = "A0QEAgZJWkRWEQEBAxkKSV4fGh5aHFEEER1cFAlTQUBGDxwMXh8AEBc="
        o = "azBwcnVzdQ=="
        self.assertEqual(
            "https://fastplay.mom/watch/abc123",
            live_player_probe.decode_spg(n, o),
        )

    def test_fnv1a_and_x_sp_signature(self):
        self.assertEqual("811c9dc5", live_player_probe.fnv1a_hex(""))
        self.assertEqual("e40c292c", live_player_probe.fnv1a_hex("a"))
        token = live_player_probe.build_x_sp("sp", 1700000000, "ab12")
        self.assertEqual(
            f"1700000000.ab12.{live_player_probe.fnv1a_hex('sp|1700000000|ab12')}",
            token,
        )

    def test_hls_variants_resolve_every_resolution(self):
        playlist = """#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
360p/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=1400000,RESOLUTION=842x480
/hls/480p/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=2500000
https://cdn.example/hls/auto/index.m3u8
"""
        variants = live_player_probe.hls_variants(
            playlist, "https://dbx.molystream.org/embed/sheila/abc"
        )
        self.assertEqual(
            [
                ("https://dbx.molystream.org/embed/sheila/360p/index.m3u8", 360),
                ("https://dbx.molystream.org/hls/480p/index.m3u8", 480),
                ("https://cdn.example/hls/auto/index.m3u8", 0),
            ],
            [(v["url"], v["height"]) for v in variants],
        )

    def test_dwls_results_and_wmode_and_parts(self):
        payload = (
            '{"results":[{"post_title":"Örnek","permalink":"https:\\/\\/x\\/dizi\\/",'
            '"attachment_thumbnail":"https:\\/\\/x\\/50x50\\/k.jpg"},{"post_title":""}]}'
        )
        rows = live_player_probe.dwls_results(payload)
        self.assertEqual(1, len(rows))
        self.assertEqual("https://x/dizi/", rows[0]["url"])

        self.assertTrue(
            live_player_probe.with_wmode(
                "https://x/player/king/king.php?v=1"
            ).endswith("&wmode=opaque")
        )
        already = "https://x/player/moly/moly.php?wmode=opaque&h=z"
        self.assertEqual(already, live_player_probe.with_wmode(already))

        page = (
            r'<script>let parts = [{"name":"Vidrame","lang":"dual",'
            r'"data":"<iframe src=\"https:\/\/vidrame.pro\/vr\/abc\">"}];</script>'
        )
        frames = live_player_probe.parts_frames(page)
        self.assertEqual(1, len(frames))
        self.assertEqual("https://vidrame.pro/vr/abc", frames[0]["url"])
        self.assertEqual("dual", frames[0]["lang"])


if __name__ == "__main__":
    unittest.main()
