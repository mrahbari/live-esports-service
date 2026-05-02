#!/usr/bin/env python3
"""Normalize scripts/*.sh and scripts/*.py to LF (fixes `bash\\r`, bad shebang).

Do **not** run this file as a shell script (`./...` invokes bash/sh on some setups).
Prefer:
  python3 scripts/fix_sh_line_endings.py
Or from repo root (with shebang above + chmod +x):
  ./scripts/fix_sh_line_endings.py
Windows CMD/PowerShell:
  py scripts\\fix_sh_line_endings.py
"""

from __future__ import annotations

import pathlib


def _normalize(content: bytes) -> bytes | None:
    text = content.decode("utf-8")
    normalized = text.replace("\r\n", "\n").replace("\r", "\n")
    if normalized == text:
        return None
    return normalized.encode("utf-8")


def main() -> None:
    here = pathlib.Path(__file__).resolve().parent
    fixed = 0
    targets = sorted(here.glob("*.sh"))
    targets += sorted(here.glob("*.py"))

    for path in targets:
        raw = path.read_bytes()
        out = _normalize(raw)
        if out is None:
            continue
        path.write_bytes(out)
        print(f"LF-normalized: {path.name}")
        fixed += 1

    if fixed == 0:
        print("No CRLF changes needed under scripts/*.sh and scripts/*.py")


if __name__ == "__main__":
    main()
