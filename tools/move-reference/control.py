"""控制独立原版移动sink组件采集器，保留每次请求与响应"""

import argparse
import hashlib
import json
import os
import time
import uuid
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("status", "lab_create", "lab_open", "capture_move", "shutdown"))
    parser.add_argument("--home", type=Path, default=os.environ.get("TLMNH_REFERENCE_HOME"))
    parser.add_argument("--timeout", type=float, default=150)
    args = parser.parse_args()
    if args.home is None:
        parser.error("--home or TLMNH_REFERENCE_HOME is required")
    home = Path(args.home).resolve()
    project = Path(__file__).resolve().parents[2]
    if home.is_relative_to(project) or not 0 < args.timeout <= 600:
        parser.error("External runtime and timeout in (0,600] required")
    root = home / "client/tlmnh-move-reference"
    if not (root / "control").is_dir():
        raise RuntimeError("Move reference client has not created its control directory")
    name = uuid.uuid4().hex + ".json"
    request = {"command": args.command}
    if args.command == "capture_move":
        request["repetitions"] = 3
    target = root / "control" / name
    with target.open("x", encoding="utf-8") as stream:
        json.dump(request, stream)
        stream.write("\n")
    response_file = root / "responses" / name
    deadline = time.monotonic() + args.timeout
    while not response_file.is_file():
        if time.monotonic() >= deadline:
            raise TimeoutError("Same reference process may still be working; inspect retained request/response before retrying")
        time.sleep(0.1)
    response = json.loads(response_file.read_text(encoding="utf-8"))
    if "capture" in response:
        relative = Path(response["capture"])
        if relative.parts != ("captures", name):
            raise ValueError("Unexpected move capture path")
        capture = root / relative
        if capture.is_symlink() or not capture.resolve().is_relative_to(root.resolve()):
            raise ValueError("Redirected move capture")
        if hashlib.sha256(capture.read_bytes()).hexdigest() != response["captureSha256"]:
            raise ValueError("Move capture hash differs from response")
    print(json.dumps(response, ensure_ascii=False))
    return 0 if response.get("status") == "complete" else 2


if __name__ == "__main__":
    raise SystemExit(main())
