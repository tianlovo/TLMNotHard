"""控制真实原版世界tick跟随采集，超时保留同一进程和原始请求"""
import argparse
import hashlib
import json
import os
import time
import uuid
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("status", "lab_create", "lab_open", "capture_natural", "cancel", "shutdown"))
    parser.add_argument("--home", type=Path, default=os.environ.get("TLMNH_REFERENCE_HOME"))
    parser.add_argument("--timeout", type=float, default=300)
    args = parser.parse_args()
    if args.home is None or not 0 < args.timeout <= 600:
        parser.error("External --home and timeout in (0,600] required")
    home = Path(args.home).resolve()
    if home.is_relative_to(Path(__file__).resolve().parents[2]):
        parser.error("Runtime must be outside public source")
    root = home / "client/tlmnh-follow-natural-reference"
    if not (root / "control").is_dir():
        raise RuntimeError("Natural reference client has not created its control directory")
    name = uuid.uuid4().hex + ".json"
    request = {"command": args.command}
    if args.command == "capture_natural":
        request["repetitions"] = 3
    target = root / "control" / name
    temporary = target.with_suffix(".tmp")
    with temporary.open("x", encoding="utf-8") as stream:
        json.dump(request, stream)
        stream.write("\n")
    temporary.rename(target)
    response_path = root / "responses" / name
    deadline = time.monotonic() + args.timeout
    while not response_path.is_file():
        if time.monotonic() >= deadline:
            raise TimeoutError("Same process may still own an active capture; inspect status and retained request before retrying or stopping")
        time.sleep(0.1)
    response = json.loads(response_path.read_text(encoding="utf-8"))
    if "capture" in response:
        relative = Path(response["capture"])
        expected = name[:-5] + ".json.gz"
        if relative.parts != ("captures", expected):
            raise ValueError("Unexpected natural capture path")
        capture = root / relative
        if capture.is_symlink() or not capture.resolve().is_relative_to(root.resolve()):
            raise ValueError("Redirected natural capture")
        raw = capture.read_bytes()
        if len(raw) != response["compressedBytes"] or hashlib.sha256(raw).hexdigest() != response["captureSha256"]:
            raise ValueError("Natural capture differs from retained response")
    print(json.dumps(response, ensure_ascii=False))
    return 0 if response.get("status") == "complete" else 2


if __name__ == "__main__":
    raise SystemExit(main())
