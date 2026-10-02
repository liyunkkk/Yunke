"""Stdlib-only release-artifact regression tests; no Android build required."""

from contextlib import redirect_stderr, redirect_stdout
import hashlib
import importlib.util
import io
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile
import zlib


ROOT = Path(__file__).resolve().parents[4]
SCRIPT = ROOT / ".github/scripts/check_vivo_release_reflection.py"
SPEC = importlib.util.spec_from_file_location("check_vivo_release_reflection", SCRIPT)
CHECK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECK)
NAMES = CHECK.TYPE_NAMES


def uleb128(value):
    result = bytearray()
    while value >= 0x80:
        result.append((value & 0x7f) | 0x80)
        value >>= 7
    result.append(value)
    return bytes(result)


def string_data(value):
    # DEX uses MUTF-8 with a UTF-16 code-unit count, not ordinary UTF-8.
    utf16 = value.encode("utf-16-le", errors="surrogatepass")
    encoded = bytearray(uleb128(len(utf16) // 2))
    for (unit,) in struct.iter_unpack("<H", utf16):
        if 0 < unit < 0x80:
            encoded.append(unit)
        elif unit < 0x800:
            encoded.extend((0xc0 | (unit >> 6), 0x80 | (unit & 0x3f)))
        else:
            encoded.extend((0xe0 | (unit >> 12), 0x80 | ((unit >> 6) & 0x3f),
                            0x80 | (unit & 0x3f)))
    encoded.append(0)
    return bytes(encoded)


def seal(data):
    data = bytearray(data)
    data[12:32] = hashlib.sha1(data[32:]).digest()
    struct.pack_into("<I", data, 8, zlib.adler32(data[12:]) & 0xffffffff)
    return bytes(data)


def make_dex(strings, unindexed=b""):
    """Minimal standard DEX with real header, map, string_ids and string_data."""
    strings = sorted(set(strings))
    data = bytearray(0x70 + len(strings) * 4)
    data[:8] = b"dex\n039\x00"
    struct.pack_into("<I", data, 36, 0x70)
    struct.pack_into("<I", data, 40, 0x12345678)
    data_off = len(data)
    first_string = data_off
    if strings:
        struct.pack_into("<II", data, 56, len(strings), 0x70)
    for index, value in enumerate(strings):
        struct.pack_into("<I", data, 0x70 + index * 4, len(data))
        data.extend(string_data(value))
    # Unindexed bytes must never count as reflection strings.
    data.extend(unindexed)
    data.extend(b"\x00" * (-len(data) % 4))
    map_off = len(data)
    entries = [(0x0000, 1, 0)]
    if strings:
        entries.extend(((0x0001, len(strings), 0x70),
                        (0x2002, len(strings), first_string)))
    entries.append((0x1000, 1, map_off))
    entries.sort(key=lambda entry: entry[2])
    data.extend(struct.pack("<I", len(entries)))
    for kind, count, offset in entries:
        data.extend(struct.pack("<HHII", kind, 0, count, offset))
    struct.pack_into("<I", data, 32, len(data))
    struct.pack_into("<I", data, 52, map_off)
    struct.pack_into("<II", data, 104, len(data) - data_off, data_off)
    return seal(data)


class VivoReleaseReflectionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.apk = self.directory / "release.apk"
        self.mapping = self.directory / "mapping.txt"
        self.write_mapping()
        self.write_apk({"classes.dex": make_dex(NAMES)})

    def write_mapping(self, renames=None, omit=()):
        renames = renames or {}
        lines = ["# compiler: R8", "example.Other -> x:", "    int member -> a"]
        lines.extend(f"{name} -> {renames.get(name, name)}:" for name in NAMES if name not in omit)
        self.mapping.write_text("\n".join(lines) + "\n", encoding="utf-8")

    def write_apk(self, entries):
        with zipfile.ZipFile(self.apk, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for name, data in entries.items():
                archive.writestr(name, data)

    def run_cli(self, argv=None):
        stdout, stderr = io.StringIO(), io.StringIO()
        with redirect_stdout(stdout), redirect_stderr(stderr):
            result = CHECK.main(argv if argv is not None else [
                "--apk", str(self.apk), "--mapping", str(self.mapping)])
        return result, stdout.getvalue(), stderr.getvalue()

    def assert_rejected(self, expected):
        code, stdout, stderr = self.run_cli()
        self.assertNotEqual(code, 0)
        self.assertEqual(stdout, "")
        self.assertIn(expected, stderr)
        self.assertNotIn(str(self.directory), stderr)
        self.assertNotIn("Traceback", stderr)

    def test_preserved_mapping_and_dotted_strings_pass(self):
        code, stdout, stderr = self.run_cli()
        self.assertEqual(code, 0)
        self.assertEqual(stderr, "")
        self.assertEqual(stdout.splitlines(), ["Vivo release reflection: OK", *NAMES])

    def test_names_may_be_in_different_dex_files(self):
        self.write_apk({"classes.dex": make_dex([NAMES[0]]),
                        "classes10.dex": make_dex([NAMES[1]]),
                        "assets/readme.txt": b"not DEX"})
        CHECK.check_release(self.apk, self.mapping)

    def test_mutf8_non_ascii_null_and_surrogate_strings_are_valid(self):
        data = make_dex([*NAMES, "nul\x00string", "中文", "emoji \U0001f600", "\ud800", "中" * 140])
        self.assertEqual(CHECK.dex_reflection_names(data), set(NAMES))

    def test_known_release_renames_are_rejected_without_echoing_aliases(self):
        for name, alias in zip(NAMES, ("oh2", "lh1")):
            with self.subTest(name=name):
                self.write_mapping({name: alias})
                self.assert_rejected("renamed type: " + name)
                self.assertNotIn(alias, self.run_cli()[2])

    def test_missing_each_mapping_entry_is_rejected(self):
        for name in NAMES:
            with self.subTest(name=name):
                self.write_mapping(omit=(name,))
                self.assert_rejected("missing mapping entry: " + name)

    def test_empty_member_only_and_duplicate_mapping_are_rejected(self):
        for content in ("", "    " + NAMES[0] + " -> " + NAMES[0] + ":\n"):
            with self.subTest(content=content):
                self.mapping.write_text(content, encoding="utf-8")
                self.assert_rejected("missing mapping entry")
        self.write_mapping()
        with self.mapping.open("a", encoding="utf-8") as stream:
            stream.write(f"{NAMES[0]} -> {NAMES[0]}:\n")
        self.assert_rejected("duplicate mapping entry")

    def test_missing_and_invalid_utf8_mapping_are_rejected(self):
        self.mapping.unlink()
        self.assert_rejected("mapping unreadable")
        self.mapping.write_bytes(b"\xff\xfe")
        self.assert_rejected("mapping unreadable")

    def test_missing_each_reflection_string_is_rejected(self):
        for name in NAMES:
            with self.subTest(name=name):
                self.write_apk({"classes.dex": make_dex([value for value in NAMES if value != name])})
                self.assert_rejected("missing reflection string: " + name)

    def test_descriptors_are_not_reflection_strings(self):
        descriptors = ["L" + value.replace(".", "/") + ";" for value in NAMES]
        self.write_apk({"classes.dex": make_dex(descriptors)})
        self.assert_rejected("missing reflection string")

    def test_unindexed_binary_substrings_are_not_accepted(self):
        padding = b"\x00".join(name.encode("ascii") for name in NAMES)
        self.write_apk({"classes.dex": make_dex(["unrelated"], unindexed=padding)})
        self.assert_rejected("missing reflection string")

    def test_missing_apk_bad_zip_and_no_dex_are_rejected(self):
        self.apk.unlink()
        self.assert_rejected("APK unreadable")
        self.apk.write_bytes(b"not a zip")
        self.assert_rejected("malformed ZIP")
        self.write_apk({"assets/unused.dex": make_dex(NAMES)})
        self.assert_rejected("APK has no DEX")

    def test_zip_crc_corruption_is_rejected(self):
        with zipfile.ZipFile(self.apk, "w", compression=zipfile.ZIP_STORED) as archive:
            archive.writestr("classes.dex", make_dex(NAMES))
        damaged = bytearray(self.apk.read_bytes())
        # First local header has 30 fixed bytes, followed by name and extra data.
        name_size, extra_size = struct.unpack_from("<HH", damaged, 26)
        payload = 30 + name_size + extra_size
        damaged[payload + 20] ^= 1
        self.apk.write_bytes(damaged)
        self.assert_rejected("malformed ZIP")

    def test_standard_dex_versions_pass(self):
        good = make_dex(NAMES)
        for version in (b"035", b"037", b"038", b"039", b"040"):
            with self.subTest(version=version):
                data = bytearray(good)
                data[4:7] = version
                self.assertEqual(CHECK.dex_reflection_names(seal(data)), set(NAMES))

    def test_corrupt_later_dex_is_not_skipped_after_names_found(self):
        self.write_apk({"classes.dex": make_dex(NAMES), "classes2.dex": b"broken"})
        self.assert_rejected("malformed DEX")

    def test_duplicate_zip_dex_entries_are_rejected(self):
        # zipfile warns about the intentional duplicate; keep test output quiet.
        import warnings
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(self.apk, "w") as archive:
                for _ in range(2):
                    archive.writestr("classes.dex", make_dex(NAMES))
        self.assert_rejected("duplicate APK DEX entry")

    def test_bad_dex_checksums_and_short_header_are_rejected(self):
        good = make_dex(NAMES)
        damaged = bytearray(good)
        damaged[-1] ^= 1
        for data in (b"", good[:60], bytes(damaged)):
            with self.subTest(size=len(data)):
                self.write_apk({"classes.dex": data})
                self.assert_rejected("malformed DEX")

    def test_dex_table_and_header_bounds_are_checked_with_valid_checksums(self):
        good = make_dex(NAMES)
        for position, value in ((32, len(good) + 1), (36, 0x78), (40, 0x78563412),
                                (52, len(good)), (56, 0xffffffff), (60, len(good)),
                                (104, len(good)), (108, 0x6c), (0x70, len(good))):
            with self.subTest(position=position):
                damaged = bytearray(good)
                struct.pack_into("<I", damaged, position, value)
                self.write_apk({"classes.dex": seal(damaged)})
                self.assert_rejected("malformed DEX")

    def test_dex_map_bounds_are_checked(self):
        damaged = bytearray(make_dex(NAMES))
        map_off = struct.unpack_from("<I", damaged, 52)[0]
        struct.pack_into("<I", damaged, map_off, 0xffffffff)
        self.write_apk({"classes.dex": seal(damaged)})
        self.assert_rejected("malformed DEX")

    def test_string_length_encoding_and_termination_are_checked(self):
        good = make_dex([NAMES[0]])
        offset = struct.unpack_from("<I", good, 0x70)[0]
        modifications = (
            (offset, bytes([len(NAMES[0]) + 1])),  # Wrong UTF-16 length.
            (offset, b"\x80\x80\x80\x80\x80"),  # Invalid ULEB128.
            (offset + 1, b"\xc2 "),  # Invalid continuation byte.
            (offset + 1, b"\xc0\xaf"),  # Overlong non-NUL encoding.
            (offset + 1 + len(NAMES[0]), b"A"),  # Missing terminator.
        )
        for position, replacement in modifications:
            with self.subTest(position=position, replacement=replacement):
                damaged = bytearray(good)
                damaged[position:position + len(replacement)] = replacement
                self.write_apk({"classes.dex": seal(damaged)})
                self.assert_rejected("malformed DEX")

    def test_unsupported_dex_format_fails_closed(self):
        damaged = bytearray(make_dex(NAMES))
        damaged[:8] = b"dex\n041\x00"
        self.write_apk({"classes.dex": seal(damaged)})
        self.assert_rejected("unsupported or malformed DEX")

    def test_cli_requires_both_artifacts_without_echoing_arguments(self):
        for args in ([], ["--apk", str(self.apk)], ["--private-path", str(self.directory)]):
            with self.subTest(args=args):
                code, stdout, stderr = self.run_cli(args)
                self.assertNotEqual(code, 0)
                self.assertEqual(stdout, "")
                self.assertEqual(stderr, "Vivo release reflection: invalid arguments\n")

    def test_precise_keepnames_and_workflow_wiring(self):
        rules = (ROOT / "app/proguard-rules.pro").read_text(encoding="utf-8")
        self.assertIn("-keepnames interface " + NAMES[0], rules.splitlines())
        self.assertIn("-keepnames class " + NAMES[1], rules.splitlines())
        workflow = (ROOT / ".github/workflows/build-debug.yml").read_text(encoding="utf-8")
        check = workflow.index("python3 .github/scripts/check_vivo_release_reflection.py")
        self.assertLess(workflow.index(":app:assembleRelease"), check)
        self.assertLess(check, workflow.index("- name: 上传 Release APK"))
        self.assertLess(check, workflow.index("- name: 上传 Release 混淆映射"))
        self.assertIn("--apk app/build/outputs/apk/release/app-release.apk", workflow)
        self.assertIn("--mapping app/build/outputs/mapping/release/mapping.txt", workflow)


if __name__ == "__main__":
    unittest.main()
