#!/usr/bin/env python3
"""检查发布输入是否是可复现的精确 commit，而不是 branch tip。"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


SHA = re.compile(r"^[0-9a-f]{40}$")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("path", type=Path)
    args = parser.parse_args()
    data = json.loads(args.path.read_text(encoding="utf-8"))
    if data.get("schema") != 1:
        raise SystemExit("release inputs must use schema 1")
    worker = data.get("worker", {})
    if not SHA.fullmatch(worker.get("ref", "")):
        raise SystemExit("worker.ref must be a full 40-character commit SHA")
    if worker.get("java_major") != 17 or worker.get("protocol_version") != 2:
        raise SystemExit("Worker release inputs must target Java 17 and protocol v2")
    client_core = data.get("client_core", {})
    if not SHA.fullmatch(client_core.get("ref", "")):
        raise SystemExit("client_core.ref must be a full commit SHA")
    versions = data.get("versions", {})
    required = {"1.21.1", "1.20.1", "1.12.2"}
    if set(versions) != required:
        raise SystemExit(f"release inputs must cover exactly {sorted(required)}")
    for version, entry in versions.items():
        if not SHA.fullmatch(entry.get("ref", "")):
            raise SystemExit(f"versions.{version}.ref must be a full commit SHA")
        if not isinstance(entry.get("java_major"), int):
            raise SystemExit(f"versions.{version}.java_major must be numeric")
    print("release inputs: exact commit refs verified")


if __name__ == "__main__":
    main()
