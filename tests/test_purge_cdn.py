import json
import unittest

from tools import purge_cdn


class PurgeResponseTests(unittest.TestCase):
    def _body(self, path, result):
        key = f"/gh/{purge_cdn.SLUG}@{purge_cdn.BRANCH}/{path}"
        return json.dumps({"status": "finished", "paths": {key: result}}).encode()

    def test_http_200_with_all_providers_purged_is_success(self):
        ok, detail = purge_cdn.purge_response_succeeded(
            200,
            self._body("plugins.json", {
                "throttled": False,
                "providers": {"CF": True, "FY": True},
            }),
            "plugins.json",
        )

        self.assertTrue(ok)
        self.assertIn("tum CDN", detail)

    def test_http_200_throttled_response_is_failure(self):
        ok, detail = purge_cdn.purge_response_succeeded(
            200,
            self._body("plugins.json", {
                "throttled": True,
                "throttlingReset": 89,
            }),
            "plugins.json",
        )

        self.assertFalse(ok)
        self.assertIn("89s", detail)

    def test_partial_provider_failure_is_failure(self):
        ok, detail = purge_cdn.purge_response_succeeded(
            200,
            self._body("DiziBox.cs3", {
                "throttled": False,
                "providers": {"CF": True, "FY": False},
            }),
            "DiziBox.cs3",
        )

        self.assertFalse(ok)
        self.assertIn("FY", detail)

    def test_missing_target_path_is_failure(self):
        ok, detail = purge_cdn.purge_response_succeeded(
            200,
            json.dumps({"status": "finished", "paths": {}}).encode(),
            "plugins.json",
        )

        self.assertFalse(ok)
        self.assertIn("hedef yol yok", detail)

    def test_non_200_response_is_failure(self):
        ok, detail = purge_cdn.purge_response_succeeded(
            429,
            b"too many requests",
            "plugins.json",
        )

        self.assertFalse(ok)
        self.assertEqual(detail, "HTTP 429")


if __name__ == "__main__":
    unittest.main()
