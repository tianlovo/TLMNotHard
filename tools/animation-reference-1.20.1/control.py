"""发送原版1.20.1小样本实验请求，记录观测凭据，不作等价性判定"""

import argparse
import hashlib
import json
import subprocess
import time
from datetime import datetime
from pathlib import Path

from reference_environment import PROBE, RUNTIME, UPSTREAM, EVIDENCE, GIT, GAME


def safe(path):
    path.resolve().relative_to(GAME.resolve(strict=True))
    for parent in (path, *path.parents):
        if parent.exists() and (parent.is_symlink() or parent.is_junction()):
            raise ValueError("Reference runtime must not use reparse points")
        if parent == GAME:
            break


def git(*args):
    return subprocess.check_output([GIT, "-C", str(UPSTREAM), *args],
                                   text=True, encoding="utf-8").strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("status", "create_lab", "open_lab", "clear_scene", "reload", "spawn_case", "sample_case", "observe", "shutdown"))
    parser.add_argument("--model")
    parser.add_argument("--entity")
    parser.add_argument("--frames", type=int, default=30)
    parser.add_argument("--seed", type=int, default=424242)
    parser.add_argument("--scenario", choices=("idle", "idle_walk_idle", "head_turn"), default="idle")
    parser.add_argument("--fault", choices=("none", "head_offset", "clock_offset", "wrong_clip"), default="none")
    parser.add_argument("--timeout", type=float, default=30)
    args = parser.parse_args()
    if not 0 < args.timeout <= 55:
        parser.error("timeout must be greater than zero and at most 55 seconds")
    controls = GAME / "tlmnh-reference/control"
    safe(controls)
    if not controls.is_dir():
        raise ValueError("Reference probe is not ready")
    request = {"command": args.command}
    if args.command in ("spawn_case", "sample_case"):
        if not args.model:
            parser.error("spawn_case requires --model")
        request["model"] = args.model
        if args.command == "sample_case":
            request.update(frames=args.frames, seed=args.seed, fault=args.fault)
            request["scenario"] = args.scenario
    if args.command == "observe":
        if not args.entity:
            parser.error("observe requires --entity")
        request.update(entityUuid=args.entity, frames=args.frames)
    name = str(time.time_ns()) + "-" + args.command + ".json"
    pending = controls / (name + ".tmp")
    pending.write_text(json.dumps(request) + "\n", encoding="utf-8")
    pending.replace(controls / name)
    response_path = controls.parent / "responses" / name
    safe(response_path)
    deadline = time.monotonic() + args.timeout
    while time.monotonic() < deadline:
        if response_path.is_file():
            try:
                response = json.loads(response_path.read_text(encoding="utf-8"))
                if response.get("status") not in ("waiting-render", "waiting-reload"):
                    break
            except (json.JSONDecodeError, PermissionError):
                pass
        time.sleep(.1)
    else:
        raise TimeoutError("Reference client did not complete the request within this bounded wait")
    probe = PROBE
    evidence = {"recorded_at": datetime.now().astimezone().isoformat(), "request": request, "response": response,
                "upstream_source_head": git("rev-parse", "HEAD"), "upstream_source_dirty": bool(git("status", "--porcelain")),
                "probe_sources": {p.relative_to(probe).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                                  for p in probe.rglob("*.java")}, "equivalence_verified": False,
                "prism_modified": False,
                "upstream_probe_patch_sha256": hashlib.sha256((PROBE / "upstream-probe.patch").read_bytes()).hexdigest()}
    if response.get("capture"):
        relative = Path(response["capture"])
        if relative.is_absolute() or len(relative.parts) != 2 or relative.parts[0] != "captures":
            raise ValueError("Unexpected reference capture path")
        capture = controls.parent / relative
        safe(capture)
        data = capture.read_bytes()
        snapshot = json.loads(data)
        evidence["capture"] = {"path": str(capture), "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data),
                               "schema": snapshot["schema"], "frames": len(snapshot["frames"]),
                               "all_inputs_captured": snapshot["allInputsCaptured"],
                               "bone_counts": sorted(set(len(frame["bones"]) for frame in snapshot["frames"])),
                               "model_choices": sorted(set(frame["modelChoice"] for frame in snapshot["frames"])),
                               "fallback_frames": sum(bool(frame["fallback"]) for frame in snapshot["frames"])}
    reports = EVIDENCE / "requests"
    reports.mkdir(parents=True, exist_ok=True)
    (reports / name).write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(evidence, ensure_ascii=False))
    return 0 if response.get("status") == "complete" else 1


if __name__ == "__main__":
    raise SystemExit(main())
