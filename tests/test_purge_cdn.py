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


class ThrottleWaitTests(unittest.TestCase):
    def _body(self, result, path="plugins.json"):
        key = f"/gh/{purge_cdn.SLUG}@{purge_cdn.BRANCH}/{path}"
        return json.dumps({"status": "finished", "paths": {key: result}}).encode()

    def test_throttled_response_reports_reset_seconds(self):
        wait = purge_cdn._throttle_wait(200, self._body({"throttled": True, "throttlingReset": 89}))

        self.assertEqual(89, wait)

    def test_successful_purge_needs_no_wait(self):
        wait = purge_cdn._throttle_wait(200, self._body({"throttled": False, "providers": {"CF": True}}))

        self.assertEqual(0, wait)

    def test_non_200_and_broken_json_need_no_wait(self):
        self.assertEqual(0, purge_cdn._throttle_wait(429, b"{}"))
        self.assertEqual(0, purge_cdn._throttle_wait(200, b"<html>"))


class PurgeRetryTests(unittest.TestCase):
    def test_throttled_attempt_is_retried_until_success(self):
        bodies = [
            (200, ThrottleWaitTests._body(self, {"throttled": True, "throttlingReset": 5})),
            (200, ThrottleWaitTests._body(self, {"throttled": False, "providers": {"CF": True}})),
        ]
        calls = []

        def fake_get(url, timeout=30):
            calls.append(url)
            return bodies[min(len(calls), len(bodies)) - 1]

        slept = []
        original_get, original_sleep = purge_cdn._get, purge_cdn.time.sleep
        purge_cdn._get = fake_get
        purge_cdn.time.sleep = slept.append
        try:
            failed = purge_cdn.purge(["plugins.json"], attempts=3)
        finally:
            purge_cdn._get, purge_cdn.time.sleep = original_get, original_sleep

        self.assertEqual(0, failed)
        self.assertEqual(2, len(calls))
        self.assertEqual([5], slept)
