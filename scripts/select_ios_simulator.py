#!/usr/bin/env python3
"""Select the best available iPhone simulator UDID for xcodebuild CI/local runs."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys


def parse_ios_runtime_version(runtime_id: str) -> tuple[int, ...] | None:
    match = re.search(r"SimRuntime\.iOS-(\d+(?:-\d+)*)$", runtime_id)
    if not match:
        return None
    return tuple(int(part) for part in match.group(1).split("-"))


def iphone_model_rank(name: str) -> tuple[int, int, str]:
    match = re.fullmatch(r"iPhone\s+(\d+)(?:\s+(.*))?", name.strip())
    if not match:
        return (0, 0, name)
    generation = int(match.group(1))
    suffix = (match.group(2) or "").strip()
    if suffix == "":
        tier = 4
    elif suffix == "Plus":
        tier = 3
    elif suffix in {"Pro", "Pro Max", "Air"}:
        tier = 2
    else:
        tier = 1
    return (tier, generation, name)


def select_simulator() -> tuple[str, str, str]:
    raw = subprocess.check_output(
        ["xcrun", "simctl", "list", "devices", "available", "-j"],
        text=True,
    )
    payload = json.loads(raw)
    devices_by_runtime: dict[str, list[dict[str, object]]] = payload.get("devices", {})

    candidates: list[tuple[tuple[int, ...], tuple[int, int, str], str, str, str]] = []
    for runtime_id, devices in devices_by_runtime.items():
        version = parse_ios_runtime_version(runtime_id)
        if version is None:
            continue
        for dev in devices:
            if not dev.get("isAvailable"):
                continue
            name = str(dev.get("name", ""))
            udid = str(dev.get("udid", ""))
            if not udid or "iPhone" not in name:
                continue
            runtime_label = f"iOS {'.'.join(str(p) for p in version)}"
            candidates.append((version, iphone_model_rank(name), udid, name, runtime_label))

    if not candidates:
        print("Error: no available iPhone simulator found via xcrun simctl.", file=sys.stderr)
        sys.exit(1)

    candidates.sort(key=lambda item: (item[0], item[1]), reverse=True)
    _, _, udid, name, runtime_label = candidates[0]
    return udid, name, runtime_label


def main() -> None:
    parser = argparse.ArgumentParser(description="Select an available iPhone simulator.")
    parser.add_argument(
        "--id-only",
        action="store_true",
        help="Print only the simulator UDID.",
    )
    args = parser.parse_args()

    udid, name, runtime_label = select_simulator()
    if args.id_only:
        print(udid)
    else:
        print(f"Selected simulator: {name} ({runtime_label}) [{udid}]")


if __name__ == "__main__":
    main()
