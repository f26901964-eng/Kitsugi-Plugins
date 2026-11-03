"""Domain kayıt defteri (eklenti_domainleri.txt) ile eklenti kaynağını karşılaştırır.

NEDEN:
  Site alan adları sık değişiyor ve iki yerde tutuluyor:
    * eklentinin Kotlin kaynağındaki `mainUrl`
    * `eklenti_domainleri.txt` (depo genelindeki alan adı listesi)
  Biri güncellenip diğeri unutulduğunda eklenti ölü alan adına istek atıyor ya da
  dokümantasyon yanlış adresi gösteriyor. Bu test, etkin eklentilerin `mainUrl`
  değerinin kayıt defteriyle aynı olduğunu doğrular.
"""

import glob
import os
import re
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ENABLED = os.path.join(ROOT, "enabled_plugins.txt")
REGISTRY = os.path.join(ROOT, "eklenti_domainleri.txt")

#: Yalnız MainAPI sınıfındaki tanım: yardımcı sınıflarda da `mainUrl` geçebiliyor.
MAIN_URL = re.compile(r'override\s+var\s+mainUrl\s*=\s*"(https?://[^"]+)"')


def enabled_plugins():
    if not os.path.exists(ENABLED):
        return []
    names = []
    with open(ENABLED, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if line and not line.startswith("#"):
                names.append(line)
    return names


def registry_domains():
    domains = {}
    if not os.path.exists(REGISTRY):
        return domains
    with open(REGISTRY, encoding="utf-8") as fh:
        for line in fh:
            for item in line.strip().split("|"):
                if ":" not in item:
                    continue
                name, _, url = item.partition(":")
                name, url = name.strip(), url.strip()
                if name and url.startswith("http"):
                    domains[name] = url.rstrip("/")
    return domains


def source_main_url(plugin):
    for path in glob.glob(os.path.join(ROOT, plugin, "src", "main", "kotlin", "**", "*.kt"), recursive=True):
        with open(path, encoding="utf-8", errors="replace") as fh:
            body = fh.read()
        if "MainAPI()" not in body:
            continue
        match = MAIN_URL.search(body)
        if match:
            return match.group(1).rstrip("/"), os.path.relpath(path, ROOT)
    return None, None


class DomainRegistryTests(unittest.TestCase):
    def test_enabled_plugins_match_registry(self):
        domains = registry_domains()
        self.assertTrue(domains, "eklenti_domainleri.txt okunamadı veya boş")

        checked = []
        mismatches = []
        for plugin in enabled_plugins():
            url, path = source_main_url(plugin)
            if url is None:
                continue
            checked.append(plugin)
            if plugin not in domains:
                # Kayıt defteri tüm eklentileri kapsamıyor; kaydı olmayan eklenti atlanır.
                continue
            if domains[plugin] != url:
                mismatches.append(
                    f"{plugin}: kaynak {url} ≠ kayıt defteri {domains[plugin]} (kaynak {path})"
                )

        self.assertEqual([], mismatches, "\n".join(mismatches))
        self.assertIn("DiziPal", checked)

    def test_dizipal_live_domain_is_used_everywhere(self):
        """DiziPal için ölü alan adları (dizipal3008.com vb.) geri sızmasın."""
        expected = "https://dizipal1593.com"
        self.assertEqual(expected, registry_domains().get("DiziPal"))

        url, path = source_main_url("DiziPal")
        self.assertEqual(expected, url, path)

        stale = re.compile(r"dizipal(?![0-9]*1593)[0-9]+\.com")
        offenders = []
        for pattern in ("prebuilt_plugins.json", "eklenti_domainleri.txt", "DiziPal/**/*.kt"):
            for file in glob.glob(os.path.join(ROOT, pattern), recursive=True):
                with open(file, encoding="utf-8", errors="replace") as fh:
                    if stale.search(fh.read()):
                        offenders.append(os.path.relpath(file, ROOT))
        self.assertEqual([], offenders, "ölü alan adı kalmış: " + ", ".join(offenders))


if __name__ == "__main__":
    unittest.main()
