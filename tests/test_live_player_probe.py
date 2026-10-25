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


if __name__ == "__main__":
    unittest.main()
