"""核对源码或最终 JAR 中的内置模型资源摘要。"""

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
    core = json.loads(read("assets/tlmnh/core-assets-provenance.json"))
    if core["commit"] != provenance["commit"] or core["license"] != "CC-BY-NC-SA-4.0" or len(core["files"]) != 4:
        raise ValueError("Core asset provenance differs from the fixed upstream")
    for path, expected in core["files"].items():
        data = read(path)
        if len(data) != expected["bytes"] or hashlib.sha256(data).hexdigest() != expected["sha256"]:
            raise ValueError(f"Resource differs from fixed upstream: {path}")
    gui = json.loads(read("assets/tlmnh/gui-assets-provenance.json"))
    if gui["commit"] != provenance["commit"] or len(gui["files"]) != 42:
        raise ValueError("GUI asset provenance commit differs")
    for expected in gui["files"]:
        if hashlib.sha256(read(expected["path"])).hexdigest() != expected["sha256"]:
            raise ValueError(f"GUI resource differs from its provenance: {expected['path']}")
    lifecycle = json.loads(read("assets/tlmnh/lifecycle-assets-provenance.json"))
    if lifecycle["commit"] != provenance["commit"] or lifecycle["license"] != "CC-BY-NC-SA-4.0" or len(lifecycle["files"]) != 6:
        raise ValueError("Lifecycle asset provenance differs from fixed upstream")
    for path, expected in lifecycle["files"].items():
        data = read(path)
        if len(data) != expected["bytes"] or hashlib.sha256(data).hexdigest() != expected["sha256"]:
            raise ValueError(f"Lifecycle resource differs from fixed upstream: {path}")
    bed = json.loads(read("assets/tlmnh/bed-assets-provenance.json"))
    colors = {"white", "black", "yellow", "blue", "green", "purple", "pink"}
    expected_bed = {f"assets/touhou_little_maid/{kind}/{color}.{extension}"
                    for color in colors for kind, extension in (
                        ("models/bedrock/block/maid_bed", "json"), ("textures/bedrock/block/maid_bed", "png"))}
    expected_bed.add("assets/touhou_little_maid/textures/items/maid_bed.png")
    if bed["commit"] != provenance["commit"] or bed["license"] != "CC-BY-NC-SA-4.0" or set(bed["files"]) != expected_bed:
        raise ValueError("Bed asset provenance differs from fixed upstream palette")
    for path, expected in bed["files"].items():
        data = read(path)
        if len(data) != expected["bytes"] or hashlib.sha256(data).hexdigest() != expected["sha256"]:
            raise ValueError(f"Bed resource differs from fixed upstream: {path}")
    cabinet = json.loads(read("assets/tlmnh/cabinet-assets-provenance.json"))
    expected_cabinet = {
        "assets/touhou_little_maid/models/bedrock/block/snack_cabinet.json",
        "assets/touhou_little_maid/textures/bedrock/block/snack_cabinet.png",
        "assets/touhou_little_maid/textures/items/snack_cabinet.png"}
    if cabinet["commit"] != provenance["commit"] or cabinet["license"] != "CC-BY-NC-SA-4.0" or set(cabinet["files"]) != expected_cabinet:
        raise ValueError("Cabinet asset provenance differs from fixed upstream")
    for path, expected in cabinet["files"].items():
        data = read(path)
        if len(data) != expected["bytes"] or hashlib.sha256(data).hexdigest() != expected["sha256"]:
            raise ValueError(f"Cabinet resource differs from fixed upstream: {path}")
    shrine = json.loads(read("assets/tlmnh/shrine-assets-provenance.json"))
    expected_shrine = {
        "assets/touhou_little_maid/models/bedrock/block/shrine.json",
        "assets/touhou_little_maid/textures/bedrock/block/shrine.png",
        "assets/touhou_little_maid/textures/items/shrine.png"}
    if shrine["commit"] != provenance["commit"] or shrine["license"] != "CC-BY-NC-SA-4.0" or set(shrine["files"]) != expected_shrine:
        raise ValueError("Shrine asset provenance differs from fixed upstream")
    for path, expected in shrine["files"].items():
        data = read(path)
        if len(data) != expected["bytes"] or hashlib.sha256(data).hexdigest() != expected["sha256"]:
            raise ValueError(f"Shrine resource differs from fixed upstream: {path}")
    banner = json.loads(read("assets/tlmnh/banner-assets-provenance.json"))
    expected_banner = {
        "assets/touhou_little_maid/models/bedrock/entity/maid_banner.json",
        "assets/touhou_little_maid/textures/bedrock/entity/maid_banner.png"}
    if banner["commit"] != provenance["commit"] or banner["license"] != "CC-BY-NC-SA-4.0" or set(banner["files"]) != expected_banner:
        raise ValueError("Banner asset provenance differs from fixed upstream")
    for path, expected in banner["files"].items():
        data = read(path)
        if len(data) != expected["bytes"] or hashlib.sha256(data).hexdigest() != expected["sha256"]:
            raise ValueError(f"Banner resource differs from fixed upstream: {path}")
    return {"passed": True, "scope": "asset bytes and catalog references only; no rendering or animation execution",
            "upstream_commit": provenance["commit"], "files": len(available),
            "core_files": len(core["files"]), "gui_files": len(gui["files"]),
            "lifecycle_files": len(lifecycle["files"]),
            "bed_files": len(bed["files"]),
            "cabinet_files": len(cabinet["files"]),
            "shrine_files": len(shrine["files"]),
            "banner_files": len(banner["files"]),
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
