#!/usr/bin/env python3
"""Compares two builds of a Kotlin library and prints a Markdown section.

  scripts/kotlin-size-report.py <base.jar> <base.dex> <head.jar> <head.dex> [--title TEXT]

Each build is its classes.jar plus the single classes.dex D8 makes from it. The dex is what an app
pays for before its own shrinking; the jar says where it came from. Unlike so-size-report.py, the
output carries no marker line: it is meant to be appended to that report.
"""

import argparse
import struct
import zipfile
from collections import defaultdict

# Item types of a dex map list (https://source.android.com/docs/core/runtime/dex-format#type-codes).
DEX_SECTIONS = {
  0x0000: "header",
  0x0001: "string_ids",
  0x0002: "type_ids",
  0x0003: "proto_ids",
  0x0004: "field_ids",
  0x0005: "method_ids",
  0x0006: "class_defs",
  0x0007: "call_site_ids",
  0x0008: "method_handles",
  0x1000: "map_list",
  0x1001: "type_lists",
  0x1002: "annotation_set_ref_lists",
  0x1003: "annotation_sets",
  0x2000: "class_data",
  0x2001: "code",
  0x2002: "string_data",
  0x2003: "debug_info",
  0x2004: "annotations",
  0x2005: "encoded_arrays",
  0x2006: "annotations_directories",
}


def read_jar(path):
  """Returns ({package: [class file bytes, class count]}, total class file bytes, class count)."""
  packages = defaultdict(lambda: [0, 0])
  total = count = 0
  with zipfile.ZipFile(path) as jar:
    for info in jar.infolist():
      if not info.filename.endswith(".class"):
        continue
      package = info.filename.rpartition("/")[0].replace("/", ".") or "(default)"
      packages[package][0] += info.file_size
      packages[package][1] += 1
      total += info.file_size
      count += 1
  return packages, total, count


def read_dex(path):
  """Returns (file size, {section: bytes in the file}, method count)."""
  with open(path, "rb") as f:
    data = f.read()

  if data[:4] != b"dex\n":
    raise SystemExit(f"{path}: not a dex file")

  (file_size,) = struct.unpack_from("<I", data, 0x20)
  (map_off,) = struct.unpack_from("<I", data, 0x34)
  (method_ids,) = struct.unpack_from("<I", data, 0x58)

  (map_size,) = struct.unpack_from("<I", data, map_off)
  entries = []
  for i in range(map_size):
    type_, _, _, offset = struct.unpack_from("<HHII", data, map_off + 4 + i * 12)
    entries.append((offset, type_))
  # The map gives where each section starts; it ends where the next one starts.
  items = sorted(entries)
  sections = {}
  for n, (offset, type_) in enumerate(items):
    end = items[n + 1][0] if n + 1 < len(items) else file_size
    name = DEX_SECTIONS.get(type_, f"type 0x{type_:04x}")
    sections[name] = sections.get(name, 0) + end - offset
  return file_size, sections, method_ids


def delta(base, head, unit=""):
  change = head - base
  if change == 0:
    return "0"
  text = f"{change:+,}{unit}"
  if base:
    text += f" ({change / base:+.2%})"
  return text


def kib(size):
  return f"{size:,} B ({size / 1024:,.1f} KiB)"


def bold_if_changed(change):
  return f"**{change}**" if change != "0" else change


def main():
  parser = argparse.ArgumentParser()
  parser.add_argument("base_jar")
  parser.add_argument("base_dex")
  parser.add_argument("head_jar")
  parser.add_argument("head_dex")
  parser.add_argument("--title", default="Kotlin code size")
  args = parser.parse_args()

  base_packages, base_class_bytes, base_classes = read_jar(args.base_jar)
  head_packages, head_class_bytes, head_classes = read_jar(args.head_jar)
  base_dex, base_sections, base_methods = read_dex(args.base_dex)
  head_dex, head_sections, head_methods = read_dex(args.head_dex)

  lines = [
    f"### {args.title}",
    "",
    f"**{delta(base_dex, head_dex, ' B')}**" if head_dex != base_dex else "**No change**",
    "",
    "| | Base | This PR | Change |",
    "|---|---:|---:|---:|",
    f"| Dex size | {kib(base_dex)} | {kib(head_dex)} | {delta(base_dex, head_dex, ' B')} |",
    f"| Class files | {kib(base_class_bytes)} | {kib(head_class_bytes)} | {delta(base_class_bytes, head_class_bytes, ' B')} |",
    f"| Classes | {base_classes:,} | {head_classes:,} | {delta(base_classes, head_classes)} |",
    f"| Methods | {base_methods:,} | {head_methods:,} | {delta(base_methods, head_methods)} |",
    "",
  ]

  names = list(dict.fromkeys([*base_sections, *head_sections]))
  changed = sum(base_sections.get(n, 0) != head_sections.get(n, 0) for n in names)
  lines += [
    f"<details><summary>Dex sections ({changed} changed)</summary>",
    "",
    "| Section | Base | This PR | Change |",
    "|---|---:|---:|---:|",
  ]
  for name in sorted(names, key=lambda n: -max(base_sections.get(n, 0), head_sections.get(n, 0))):
    base, head = base_sections.get(name, 0), head_sections.get(name, 0)
    lines.append(f"| `{name}` | {base:,} | {head:,} | {bold_if_changed(delta(base, head))} |")
  lines += ["", "</details>", ""]

  names = list(dict.fromkeys([*base_packages, *head_packages]))
  changed = sum(base_packages.get(n, (0, 0)) != head_packages.get(n, (0, 0)) for n in names)
  lines += [
    f"<details><summary>Class files by package ({changed} changed)</summary>",
    "",
    "| Package | Base | This PR | Change | Classes |",
    "|---|---:|---:|---:|---:|",
  ]
  for name in sorted(names, key=lambda n: -max(base_packages.get(n, (0, 0))[0], head_packages.get(n, (0, 0))[0])):
    (base, base_count), (head, head_count) = base_packages.get(name, (0, 0)), head_packages.get(name, (0, 0))
    classes = f"{head_count:,}" if head_count == base_count else f"{base_count:,} → {head_count:,}"
    lines.append(f"| `{name}` | {base:,} | {head:,} | {bold_if_changed(delta(base, head))} | {classes} |")
  lines += ["", "</details>"]

  print("\n".join(lines))


if __name__ == "__main__":
  main()
