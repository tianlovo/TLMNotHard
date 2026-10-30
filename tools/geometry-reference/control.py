"""File control for the separate original-client bind geometry probe."""

import argparse
import hashlib
import json
import time
import uuid
from pathlib import Path

from compare import CONTRACT, require


def send(game_directory, command, *, model=None, timeout=60):
    root = Path(game_directory).resolve() / "tlmnh-geometry-reference"
    require((root / "control").is_dir() and (root / "responses").is_dir(), "Original geometry probe is not ready")
    request = {"command": command}
    if command == "capture_bind":
        require(model in CONTRACT["models"], "Unreviewed model")
        request.update(model=model, repetitions=3)
    name = str(time.time_ns()) + "-" + uuid.uuid4().hex[:12] + ".json"
    temporary = root / "control" / (name + ".tmp")
    with temporary.open("x", encoding="utf-8") as stream:
        json.dump(request, stream)
    temporary.rename(root / "control" / name)
    deadline = time.monotonic() + timeout
    response = root / "responses" / name
    while not response.is_file():
        if time.monotonic() > deadline:
            raise TimeoutError("Original geometry response timed out: " + name)
        time.sleep(0.2)
    result = json.loads(response.read_text(encoding="utf-8"))
    require(result["request"] == name, "Different response request")
    require(result["status"] == "complete", "Original geometry request inconclusive: " + str(result.get("error")))
    if command == "capture_bind":
        capture = (root / result["capture"]).resolve()
        require(capture.is_relative_to(root.resolve() / "captures") and capture.is_file(), "Unsafe/missing original capture")
        require(hashlib.sha256(capture.read_bytes()).hexdigest() == result["captureSha256"], "Original capture digest differs")
        result["localCapture"] = str(capture)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--game-dir", required=True, type=Path)
    parser.add_argument("--timeout", type=float, default=60)
    parser.add_argument("command", choices=("status", "lab_create", "lab_open", "capture_bind", "shutdown"))
    parser.add_argument("--model", choices=tuple(CONTRACT["models"]))
    args = parser.parse_args()
    print(json.dumps(send(args.game_dir, args.command, model=args.model, timeout=args.timeout), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
