import unittest

from merge_plugins import filter_enabled_plugins


class FilterEnabledPluginsTests(unittest.TestCase):
    def test_exact_enabled_name_wins_over_case_only_legacy_alias(self):
        foreign = {"url": "https://example.invalid/legacy.cs3"}
        local = {"url": "https://example.invalid/current.cs3"}
        dizibox = {"url": "https://example.invalid/dizibox.cs3"}
        combined = {
            "HDFilmizle": foreign,
            "HDFilmIzle": local,
            "DiziBox": dizibox,
        }

        filtered = filter_enabled_plugins(combined, ["HDFilmIzle", "DiziBox"])

        self.assertEqual(set(filtered), {"HDFilmIzle", "DiziBox"})
        self.assertIs(filtered["HDFilmIzle"], local)
        self.assertNotIn("HDFilmizle", filtered)

    def test_single_case_insensitive_match_is_kept_for_compatibility(self):
        plugin = {"url": "https://example.invalid/plugin.cs3"}

        filtered = filter_enabled_plugins({"Openanime": plugin}, ["OpenAnime"])

        self.assertEqual(filtered, {"Openanime": plugin})

    def test_ambiguous_aliases_without_exact_match_fail_closed(self):
        combined = {
            "hdfilmizle": {"url": "first"},
            "HDFILMizle": {"url": "second"},
        }

        with self.assertRaisesRegex(ValueError, "HDFilmIzle"):
            filter_enabled_plugins(combined, ["HDFilmIzle"])

    def test_duplicate_enabled_names_ignoring_case_fail(self):
        with self.assertRaisesRegex(ValueError, "enabled_plugins.txt"):
            filter_enabled_plugins({}, ["HDFilmIzle", "HDFilmizle"])

    def test_missing_enabled_plugin_is_not_fabricated(self):
        filtered = filter_enabled_plugins({"DiziBox": {}}, ["DiziBox", "HDFilmIzle"])

        self.assertEqual(set(filtered), {"DiziBox"})


if __name__ == "__main__":
    unittest.main()
