import unittest

from tools import tizle_dump


class EmbedWrapperTests(unittest.TestCase):
    def test_inner_url_keeps_query_string(self):
        self.assertEqual(
            "https://voe.sx/e/abc?x=1&y=2",
            tizle_dump.embed_inner_url("https://luffytra2.top/embed2/?id=https://voe.sx/e/abc?x=1&y=2"),
        )

    def test_inner_url_decodes_encoded_value(self):
        self.assertEqual(
            "https://voe.sx/e/abc?x=1&y=2",
            tizle_dump.embed_inner_url(
                "https://luffytra2.top/embed2/?id=https%3A%2F%2Fvoe.sx%2Fe%2Fabc%3Fx%3D1%26y%3D2"
            ),
        )

    def test_bare_id_is_not_a_target(self):
        self.assertIsNone(tizle_dump.embed_inner_url("https://luffytra2.top/embed2/?id=12345"))
        self.assertIsNone(tizle_dump.embed_inner_url("https://voe.sx/e/abc"))


class DownloadBridgeTests(unittest.TestCase):
    def test_decodes_base64_target(self):
        self.assertEqual(
            "https://ok.ru/videoembed/123",
            tizle_dump.download_bridge_url(
                "https://www.tranimeizle.io/downloadru2.php?url=aHR0cHM6Ly9vay5ydS92aWRlb2VtYmVkLzEyMw=="
            ),
        )

    def test_ignores_non_bridge_urls(self):
        self.assertIsNone(tizle_dump.download_bridge_url("https://ok.ru/videoembed/1"))


class EpisodePageTests(unittest.TestCase):
    def test_episode_id_from_input_and_script(self):
        self.assertEqual(
            4211, tizle_dump.episode_id_from('<input type="hidden" id="EpisodeId" value="4211">')
        )
        self.assertEqual(
            998, tizle_dump.episode_id_from("animeWatch.initialize(12, 998, 7);")
        )
        self.assertIsNone(tizle_dump.episode_id_from("<html></html>"))

    def test_fansubs_and_sources(self):
        page = """
            <div class="fansubSelector" data-fid="12" data-fad="Aitr">Aitr</div>
            <div class="fansubSelector" data-fid="13">TR Altyazılı</div>
        """
        fansubs = tizle_dump.fansubs_from(page)
        self.assertEqual([12, 13], [f["id"] for f in fansubs])
        self.assertEqual("Aitr", fansubs[0]["label"])

        sources = """
            <ul id="sourceList">
              <li class="sourceBtn" data-id="77"><p class="title">Ok.RU</p></li>
              <li class="sourceBtn" data-id="78"><p class="title">Sibnet</p></li>
            </ul>
        """
        parsed = tizle_dump.sources_from(sources)
        self.assertEqual(["77", "78"], [s["id"] for s in parsed])
        self.assertEqual("Ok.RU", parsed[0]["label"])

    def test_cards_from_flx_block(self):
        page = """
            <div class="post-body">
              <div class="flx-block" data-href="/one-piece-1120-bolum-izle">
                <img data-src="/uploads/one-piece.jpg">
                <div class="bar"><h4>One Piece 1120. Bölüm İzle</h4></div>
              </div>
            </div>
        """
        cards = tizle_dump.cards_from(page)
        self.assertEqual(1, len(cards))
        self.assertEqual("/one-piece-1120-bolum-izle", cards[0]["href"])
        self.assertEqual("One Piece 1120. Bölüm İzle", cards[0]["title"].strip())
        self.assertEqual("/uploads/one-piece.jpg", cards[0]["poster"])


class CaptchaTests(unittest.TestCase):
    def test_detects_real_gate_only(self):
        self.assertTrue(tizle_dump.detect_captcha('<div class="captcha-holder"></div>'))
        self.assertTrue(tizle_dump.detect_captcha("<script>new IconCaptcha()</script>"))
        self.assertFalse(tizle_dump.detect_captcha("<script>var u = '/api/Captcha/';</script>"))


if __name__ == "__main__":
    unittest.main()
