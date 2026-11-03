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


class MainExitCodeTests(unittest.TestCase):
    """Purge/ayna gecikmesi yayın içeriğini etkilemiyorsa iş kırmızı olmamalı."""

    def _run(self, failed, mirror_status, argv=()):
        calls = {}
        original = (purge_cdn.published_paths, purge_cdn.purge, purge_cdn.check, purge_cdn.sys.argv)
        purge_cdn.published_paths = lambda: ["plugins.json"]
        purge_cdn.purge = lambda paths, **kw: failed
        purge_cdn.check = lambda: mirror_status
        purge_cdn.sys.argv = ["purge_cdn.py", *argv]
        try:
            return purge_cdn.main()
        finally:
            (purge_cdn.published_paths, purge_cdn.purge, purge_cdn.check, purge_cdn.sys.argv) = original

    def test_clean_publish_returns_success(self):
        self.assertEqual(0, self._run(0, 0))

    def test_throttled_purge_with_stale_mirror_is_warning_not_failure(self):
        self.assertEqual(0, self._run(3, 1))

    def test_strict_mode_keeps_old_red_behaviour(self):
        self.assertEqual(1, self._run(3, 1, argv=("--strict",)))


class PurgeBudgetTests(unittest.TestCase):
    def test_throttle_on_first_path_defers_the_rest(self):
        calls = []
        body = json.dumps({
            "status": "finished",
            "paths": {f"/gh/{purge_cdn.SLUG}@{purge_cdn.BRANCH}/repo.json":
                      {"throttled": True, "throttlingReset": 5}},
        }).encode()

        def fake_get(url, timeout=30):
            calls.append(url)
            return 200, body

        original_get, original_sleep = purge_cdn._get, purge_cdn.time.sleep
        purge_cdn._get, purge_cdn.time.sleep = fake_get, lambda _: None
        try:
            failed = purge_cdn.purge(["repo.json", "plugins.json", "DiziPal.cs3"], attempts=3)
        finally:
            purge_cdn._get, purge_cdn.time.sleep = original_get, original_sleep

        self.assertEqual(3, failed)
        # Yalnız ilk yol denendi; iki yol ertelendi (3 deneme = 3 istek).
        self.assertEqual(3, len(calls))
        self.assertTrue(all("repo.json" in url for url in calls))
