#!/usr/bin/env python3
"""Small dependency-free client for Badee Phone Agent's local command protocol."""

from __future__ import annotations

import argparse
import base64
import json
import os
import socket
import sys
import uuid
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Send one authenticated command to Badee Phone Agent")
    parser.add_argument("action", help="status, tap, swipe, click_text, screenshot, …")
    parser.add_argument("--args", default="{}", help="JSON object containing command arguments")
    parser.add_argument("--token", default=os.environ.get("BADEE_AGENT_TOKEN"), help="Pairing token")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--screenshot", type=Path, help="Save screenshot data to this file")
    return parser.parse_args()


def main() -> int:
    options = parse_args()
    if not options.token:
        print("Missing --token or BADEE_AGENT_TOKEN", file=sys.stderr)
        return 2

    try:
        command_args = json.loads(options.args)
        if not isinstance(command_args, dict):
            raise ValueError("--args must be a JSON object")
    except (json.JSONDecodeError, ValueError) as error:
        print(f"Invalid arguments: {error}", file=sys.stderr)
        return 2

    request = {
        "id": str(uuid.uuid4()),
        "token": options.token,
        "action": options.action,
        "args": command_args,
    }

    with socket.create_connection((options.host, options.port), timeout=15) as connection:
        connection.sendall(json.dumps(request, separators=(",", ":")).encode() + b"\n")
        response_bytes = bytearray()
        while True:
            chunk = connection.recv(64 * 1024)
            if not chunk:
                break
            response_bytes.extend(chunk)

    response = json.loads(response_bytes)
    screenshot = response.get("data", {}).pop("base64", None)
    if screenshot and options.screenshot:
        options.screenshot.parent.mkdir(parents=True, exist_ok=True)
        options.screenshot.write_bytes(base64.b64decode(screenshot))
        response["data"]["saved_to"] = str(options.screenshot)
    elif screenshot:
        response["data"]["base64"] = f"[{len(screenshot)} base64 characters omitted]"

    print(json.dumps(response, indent=2, ensure_ascii=False))
    return 0 if response.get("ok") else 1


if __name__ == "__main__":
    raise SystemExit(main())
