#!/usr/bin/env python3
"""Compares two stripped builds of a shared library and prints a Markdown report.

  scripts/so-size-report.py <base.so> <head.so> [--title TEXT] [--footer TEXT]

The first line of the output is a fixed HTML comment, so a CI job can find the report it posted
before and update it in place. Only 64-bit little-endian ELF files are read (arm64-v8a, x86_64).
"""

import argparse
import struct

MARKER = "<!-- so-size-report -->"

SHT_NOBITS = 8
SHT_DYNSYM = 11
SHN_UNDEF = 0
STB_GLOBAL = 1
STB_WEAK = 2


def read_elf(path):
  """Returns (file size, {section: bytes in the file}, exported symbol count)."""
  with open(path, "rb") as f:
    data = f.read()

  if data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
    raise SystemExit(f"{path}: not a 64-bit little-endian ELF file")

  (shoff,) = struct.unpack_from("<Q", data, 0x28)
  shentsize, shnum, shstrndx = struct.unpack_from("<HHH", data, 0x3A)

  headers = [struct.unpack_from("<IIQQQQIIQQ", data, shoff + i * shentsize) for i in range(shnum)]
  names_offset = headers[shstrndx][4]

  def name_of(header):
    start = names_offset + header[0]
    return data[start:data.index(b"\0", start)].decode()

  sections = {}
  exported = 0
  for header in headers[1:]:
    _, sh_type, _, _, sh_offset, sh_size, _, _, _, sh_entsize = header
    # .bss and the like take memory at run time but no bytes in the file.
    if sh_type != SHT_NOBITS:
      sections[name_of(header)] = sh_size
    if sh_type == SHT_DYNSYM:
      for i in range(sh_size // sh_entsize):
        _, st_info, _, st_shndx = struct.unpack_from("<IBBH", data, sh_offset + i * sh_entsize)
        if st_shndx != SHN_UNDEF and st_info >> 4 in (STB_GLOBAL, STB_WEAK):
          exported += 1

  # ELF and program headers, the section header table, and alignment padding between segments.
  sections["(headers and padding)"] = len(data) - sum(sections.values())
  return len(data), sections, exported


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


def main():
  parser = argparse.ArgumentParser()
  parser.add_argument("base")
  parser.add_argument("head")
  parser.add_argument("--title", default="Native library size")
  parser.add_argument("--footer", default="")
  args = parser.parse_args()

  base_size, base_sections, base_exported = read_elf(args.base)
  head_size, head_sections, head_exported = read_elf(args.head)

  lines = [
    MARKER,
    f"### {args.title}",
    "",
    f"**{delta(base_size, head_size, ' B')}**" if head_size != base_size else "**No change**",
    "",
    "| | Base | This PR | Change |",
    "|---|---:|---:|---:|",
    f"| File size | {kib(base_size)} | {kib(head_size)} | {delta(base_size, head_size, ' B')} |",
    f"| Exported symbols | {base_exported:,} | {head_exported:,} | {delta(base_exported, head_exported)} |",
    "",
  ]

  names = list(dict.fromkeys([*base_sections, *head_sections]))
  changed = sum(base_sections.get(n, 0) != head_sections.get(n, 0) for n in names)
  lines += [
    f"<details><summary>Sections ({changed} changed)</summary>",
    "",
    "| Section | Base | This PR | Change |",
    "|---|---:|---:|---:|",
  ]
  for name in sorted(names, key=lambda n: -max(base_sections.get(n, 0), head_sections.get(n, 0))):
    base, head = base_sections.get(name, 0), head_sections.get(name, 0)
    change = delta(base, head)
    if change != "0":
      change = f"**{change}**"
    lines.append(f"| `{name}` | {base:,} | {head:,} | {change} |")
  lines += ["", "</details>"]

  if args.footer:
    lines += ["", args.footer]

  print("\n".join(lines))


if __name__ == "__main__":
  main()
