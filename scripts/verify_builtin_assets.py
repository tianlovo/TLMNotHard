"""核对源码或最终 JAR 中的内置模型资源摘要，不能替代渲染与动画验收"""

import argparse
import hashlib
import json
import sys
import zipfile
from pathlib import Path

PROJECT = Path(__file__).resolve().parents[1]


def verify(read):
    provenance = json.loads(read("assets/tlmnh/builtin-assets-provenance.json"))
    catalog = json.loads(read("assets/tlmnh/builtin-models.json"))
    if catalog["upstream_commit"] != provenance["commit"]:
        raise ValueError("Catalog and provenance commit differ")
    available = set()
    for target, expected in provenance["files"].items():
        if target.startswith("src/main/resources/"):
            path = target.removeprefix("src/main/resources/")
        elif target.startswith("licenses/"):
            path = "META-INF/" + target
        else:
            raise ValueError(f"Unknown resource target: {target}")
        data = read(path)
        if len(data) != expected["bytes"] or hashlib.sha256(data).hexdigest() != expected["sha256"]:
            raise ValueError(f"Resource differs from fixed upstream: {path}")
        available.add(path)
    ids = set()
    bases = set()
    winefox = set()
    internal = set(catalog["internal_animation_ids"])
    for pack in catalog["packs"]:
        for choice in pack["models"]:
            if choice["id"] in ids:
                raise ValueError(f"Duplicate model ID: {choice['id']}")
            ids.add(choice["id"])
            bases.add(choice["base_id"])
            if choice["id"] != choice["base_id"]:
                texture_path = choice["texture"].split(":", 1)[1]
                expected_id = choice["base_id"] + "_" + hashlib.md5(texture_path.encode("utf-8")).hexdigest()
                if choice["id"] != expected_id:
                    raise ValueError(f"Incorrect extra-texture ID: {choice['id']}")
            if "winefox" in choice["base_id"] or "wine_fox" in choice["base_id"]:
                winefox.add(choice["id"])
            for reference in [choice["geometry"], choice["texture"], *choice["animations"]]:
                if reference in internal:
                    continue
                namespace, path = reference.split(":", 1)
                if f"assets/{namespace}/{path}" not in available:
                    raise ValueError(f"Untracked model resource: {choice['id']}/{reference}")
    if len(bases) != 234 or len(ids) != 254 or len(winefox) != 35:
        raise ValueError(f"Unexpected model inventory: choices={len(ids)}, winefox={len(winefox)}")
    return {"passed": True, "scope": "asset bytes and catalog references only; no rendering or animation execution",
            "upstream_commit": provenance["commit"], "files": len(available),
            "base_models": len(bases), "choices": len(ids), "winefox_choices": len(winefox),
            "runtime_animation_implementation_verified": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifact", type=Path, nargs="?")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()

    def source_read(path):
        if path.startswith("META-INF/licenses/"):
            return (PROJECT / path.removeprefix("META-INF/")).read_bytes()
        return (PROJECT / "src/main/resources" / path).read_bytes()

    try:
        if args.artifact:
            with zipfile.ZipFile(args.artifact) as archive:
                report = verify(archive.read)
            report["artifact"] = str(args.artifact)
            report["sha256"] = hashlib.sha256(args.artifact.read_bytes()).hexdigest()
        else:
            report = verify(source_read)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        print(f"Builtin asset verification failed: {error}", file=sys.stderr)
        return 1
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
