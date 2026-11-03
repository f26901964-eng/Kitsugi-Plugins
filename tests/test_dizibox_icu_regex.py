"""Check literal Kotlin regexes with ICU, which is used by Android's regex engine.

This complements JVM tests: java.util.regex accepts constructs that ICU rejects.
"""
import ctypes
import ctypes.util
from pathlib import Path
import re
import unittest


class DiziBoxIcuRegexTest(unittest.TestCase):
    def test_parser_literal_patterns_compile_in_icu(self):
        name = ctypes.util.find_library("icui18n")
        if not name:
            self.skipTest("ICU shared library not installed")
        lib = ctypes.CDLL(name)
        version = re.search(r"\.so\.(\d+)", name)
        suffix = "_" + version.group(1) if version else ""
        try:
            compile_regex = getattr(lib, "uregex_open" + suffix)
            close_regex = getattr(lib, "uregex_close" + suffix)
        except AttributeError:
            self.skipTest("ICU regex symbols unavailable")
        compile_regex.argtypes = [ctypes.POINTER(ctypes.c_uint16), ctypes.c_int32,
                                  ctypes.c_uint32, ctypes.c_void_p, ctypes.POINTER(ctypes.c_int32)]
        compile_regex.restype = ctypes.c_void_p
        close_regex.argtypes = [ctypes.c_void_p]
        source = (Path(__file__).resolve().parents[1] / "DiziBox/src/main/kotlin/com/keyiflerolsun/DiziBoxPlayerParser.kt").read_text()
        patterns = re.findall(r'Regex\(\s*"""(.*?)"""', source, re.S)
        self.assertGreater(len(patterns), 20)
        for pattern in patterns:
            # Kotlin interpolation requires runtime values, so only static literals are tested.
            if re.search(r'\$(?:[A-Za-z_]|\{)', pattern):
                continue
            encoded = pattern.encode("utf-16-le")
            chars = (ctypes.c_uint16 * (len(encoded) // 2)).from_buffer_copy(encoded)
            error = ctypes.c_int32()
            compiled = compile_regex(chars, len(chars), 0, None, ctypes.byref(error))
            if compiled:
                close_regex(compiled)
            self.assertLessEqual(error.value, 0, f"ICU error {error.value}: {pattern}")


if __name__ == "__main__":
    unittest.main()
