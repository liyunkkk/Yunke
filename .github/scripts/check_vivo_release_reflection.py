#!/usr/bin/env python3
"""Guard Vivo's two external-ClassLoader names in the actual R8 release artifacts.

This checks mapping entries and DEX string_ids/string_data, not instructions or
hook success. Descriptors (Lkotlin/...;) are not the dotted Class.forName names.
Only standard, little-endian DEX 035-040 is supported; other formats fail closed.
Diagnostics never echo paths, mapping contents, obfuscated names or APK strings.
"""

import argparse
import hashlib
from pathlib import Path
import re
import struct
import sys
import zipfile
import zlib


TYPE_NAMES = (
    "kotlin.jvm.functions.Function2",
    "kotlin.coroutines.jvm.internal.ContinuationImpl",
)
_CLASS_MAPPING = re.compile(r"^(\S+) -> (\S+):$")
_DEX_NAME = re.compile(r"classes(?:[0-9]+)?\.dex")
_DEX_MAGIC = re.compile(rb"dex\n(?:035|037|038|039|040)\x00")
_HEADER_SIZE = 0x70
_MAX_NAME_LENGTH = max(map(len, TYPE_NAMES))


class ValidationError(Exception):
    """A fixed, safe diagnostic, optionally including one of TYPE_NAMES."""


def check_mapping(path):
    found = set()
    try:
        with Path(path).open(encoding="utf-8") as stream:
            for line in stream:
                # Member lines are indented; R8 metadata comments start with '#'.
                match = _CLASS_MAPPING.fullmatch(line.rstrip("\r\n"))
                if match is None or match[1] not in TYPE_NAMES:
                    continue
                original, renamed = match.groups()
                if original in found:
                    raise ValidationError("duplicate mapping entry: " + original)
                if renamed != original:
                    raise ValidationError("renamed type: " + original)
                found.add(original)
    except (OSError, ValueError):
        raise ValidationError("mapping unreadable") from None
    for name in TYPE_NAMES:
        if name not in found:
            raise ValidationError("missing mapping entry: " + name)


def _require(condition):
    if not condition:
        raise ValidationError("malformed DEX")


def _range(offset, size, end, start=0):
    _require(start <= offset <= end and 0 <= size <= end - offset)


def _u32(data, offset):
    _range(offset, 4, len(data))
    return struct.unpack_from("<I", data, offset)[0]


def _uleb128(data, offset, end):
    value = 0
    for index in range(5):
        _range(offset, 1, end)
        byte = data[offset]
        offset += 1
        if index == 4:
            _require(byte <= 0x0f)
        value |= (byte & 0x7f) << (7 * index)
        if byte < 0x80:
            return value, offset
    raise ValidationError("malformed DEX")


def _string(data, offset, end):
    """Validate bounded MUTF-8 and UTF-16 length; retain only short ASCII names."""
    length, offset = _uleb128(data, offset, end)
    units = 0
    ascii_only = True
    candidate = bytearray()
    while True:
        _range(offset, 1, end)
        first = data[offset]
        offset += 1
        if first == 0:
            _require(units == length)
            if ascii_only and units <= _MAX_NAME_LENGTH:
                return candidate.decode("ascii")
            return None
        _require(units < length)
        if first < 0x80:
            code_unit = first
        elif 0xc0 <= first <= 0xdf:
            _range(offset, 1, end)
            second = data[offset]
            offset += 1
            _require(second & 0xc0 == 0x80)
            code_unit = ((first & 0x1f) << 6) | (second & 0x3f)
            _require(code_unit >= 0x80 or (first == 0xc0 and second == 0x80))
        elif 0xe0 <= first <= 0xef:
            _range(offset, 2, end)
            second, third = data[offset:offset + 2]
            offset += 2
            _require(second & 0xc0 == 0x80 and third & 0xc0 == 0x80)
            code_unit = ((first & 0x0f) << 12) | ((second & 0x3f) << 6) | (third & 0x3f)
            # Surrogate code units are legal MUTF-8 (including supplementary text).
            _require(code_unit >= 0x800)
        else:
            raise ValidationError("malformed DEX")
        units += 1
        if code_unit > 0x7f:
            ascii_only = False
        if ascii_only and units <= _MAX_NAME_LENGTH:
            candidate.append(code_unit)


def dex_reflection_names(data):
    """Validate the header/tables and return required dotted string-table names.

    Checksums detect damaged payloads too. This intentionally does not validate
    every class definition, annotation or Dalvik instruction in an otherwise
    well-formed DEX: it is a narrow reflection-name regression gate.
    """
    _require(len(data) >= _HEADER_SIZE)
    if _DEX_MAGIC.fullmatch(data[:8]) is None:
        raise ValidationError("unsupported or malformed DEX")
    _require(_u32(data, 32) == len(data))
    _require(_u32(data, 36) == _HEADER_SIZE)
    _require(_u32(data, 40) == 0x12345678)
    view = memoryview(data)
    _require(hashlib.sha1(view[32:]).digest() == data[12:32])
    _require(zlib.adler32(view[12:]) & 0xffffffff == _u32(data, 8))

    data_size, data_off = _u32(data, 104), _u32(data, 108)
    _require(data_size > 0 and data_off % 4 == 0)
    _range(data_off, data_size, len(data), _HEADER_SIZE)
    _require(data_off + data_size == len(data))
    link_size, link_off = _u32(data, 44), _u32(data, 48)
    if link_size:
        _range(link_off, link_size, len(data), _HEADER_SIZE)
    else:
        _require(link_off == 0)

    # Header ID arrays: count/offset, item width, corresponding map_item type.
    tables = ((56, 4, 0x0001), (64, 4, 0x0002), (72, 12, 0x0003),
              (80, 8, 0x0004), (88, 8, 0x0005), (96, 32, 0x0006))
    expected_map = {0x0000: (1, 0)}
    spans = [(0, _HEADER_SIZE)]
    for position, width, kind in tables:
        count, offset = _u32(data, position), _u32(data, position + 4)
        if count:
            _require(offset % 4 == 0)
            _range(offset, count * width, data_off, _HEADER_SIZE)
            spans.append((offset, offset + count * width))
            expected_map[kind] = (count, offset)
        else:
            _require(offset == 0)
    spans.sort()
    _require(all(left[1] <= right[0] for left, right in zip(spans, spans[1:])))

    map_off = _u32(data, 52)
    _require(map_off % 4 == 0)
    _range(map_off, 4, len(data), data_off)
    map_count = _u32(data, map_off)
    _require(map_count > 0)
    _range(map_off + 4, map_count * 12, len(data), data_off)
    expected_map[0x1000] = (1, map_off)
    entries = {}
    previous = -1
    for index in range(map_count):
        kind, unused, count, offset = struct.unpack_from("<HHII", data, map_off + 4 + index * 12)
        _require(unused == 0 and count > 0 and kind not in entries)
        _require(previous < offset < len(data))
        if kind >= 0x1000:
            _require(offset >= data_off)
        previous = offset
        entries[kind] = (count, offset)
    for kind, entry in expected_map.items():
        _require(entries.get(kind) == entry)
    for _, _, kind in tables:
        if kind not in expected_map:
            _require(kind not in entries)

    string_count, ids_off = _u32(data, 56), _u32(data, 60)
    if string_count:
        _require(0x2002 in entries and entries[0x2002][0] == string_count)
    else:
        _require(0x2002 not in entries)
    found = set()
    first_string_off = len(data)
    for index in range(string_count):
        offset = _u32(data, ids_off + index * 4)
        _range(offset, 1, len(data), data_off)
        first_string_off = min(first_string_off, offset)
        value = _string(data, offset, len(data))
        if value in TYPE_NAMES:
            found.add(value)
    if string_count:
        _require(entries[0x2002][1] == first_string_off)
    return found


def check_apk(path):
    found = set()
    try:
        with zipfile.ZipFile(path) as apk:
            dex_files = [entry for entry in apk.infolist() if _DEX_NAME.fullmatch(entry.filename)]
            if not dex_files:
                raise ValidationError("APK has no DEX")
            names = [entry.filename for entry in dex_files]
            if len(names) != len(set(names)):
                raise ValidationError("duplicate APK DEX entry")
            # Validate every classes*.dex, even after both required names are found.
            for entry in dex_files:
                found.update(dex_reflection_names(apk.read(entry)))
    except (OSError, zipfile.BadZipFile, EOFError, RuntimeError, ValueError, struct.error, zlib.error):
        raise ValidationError("APK unreadable or malformed ZIP") from None
    for name in TYPE_NAMES:
        if name not in found:
            raise ValidationError("missing reflection string: " + name)


def check_release(apk, mapping):
    check_mapping(mapping)
    check_apk(apk)


class _Parser(argparse.ArgumentParser):
    def error(self, message):
        # argparse's default error can echo untrusted arguments or input paths.
        raise ValidationError("invalid arguments")


def main(argv=None):
    parser = _Parser(prog="check_vivo_release_reflection",
                     description="Check the two Vivo release reflection names.")
    parser.add_argument("--apk", required=True, help="release APK")
    parser.add_argument("--mapping", required=True, help="R8 release mapping")
    try:
        args = parser.parse_args(argv)
        check_release(args.apk, args.mapping)
    except ValidationError as error:
        print("Vivo release reflection: " + str(error), file=sys.stderr)
        return 1
    print("Vivo release reflection: OK")
    for name in TYPE_NAMES:
        print(name)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
