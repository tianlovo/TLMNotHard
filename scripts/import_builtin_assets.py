"""从固定 TLM 提交核对并导入内置女仆模型，不导入椅子、LLM 设置或外部模型"""

import argparse
import hashlib
import json
import re
import struct
import subprocess
from pathlib import Path, PurePosixPath

UPSTREAM_COMMIT = "8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6"
PACKS = ("touhou_little_maid", "touhou_little_maid_old", "touhou_little_maid_seihou",
         "geckolib", "authors_and_credits", "minecraft_15th")
DEFAULT_GECKO_ANIMATION = "touhou_little_maid:animation/maid.animation.json"
PROJECT = Path(__file__).resolve().parents[1]
RESOURCE_ID = re.compile(r"[a-z0-9_.-]+:[a-z0-9_./-]+")


def digest(data):
    return hashlib.sha256(data).hexdigest()


class GitBlobReader:
    """读取提交中的规范字节，不将本机 checkout 的换行形式视为上游原字节"""

    def __init__(self, upstream):
        self.process = subprocess.Popen(["git", "-C", str(upstream), "cat-file", "--batch"],
                                        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def __enter__(self):
        return self

    def __call__(self, path):
        self.process.stdin.write(f"{UPSTREAM_COMMIT}:{path}\n".encode("utf-8"))
        self.process.stdin.flush()
        header = self.process.stdout.readline().decode("ascii").strip().split()
        if len(header) != 3 or header[1] != "blob":
            raise ValueError(f"Missing upstream blob: {path}")
        data = self.process.stdout.read(int(header[2]))
        if len(data) != int(header[2]) or self.process.stdout.read(1) != b"\n":
            raise ValueError(f"Truncated upstream blob: {path}")
        return data

    def __exit__(self, *unused):
        self.process.stdin.close()
        self.process.stdout.close()
        self.process.stderr.close()
        self.process.wait(timeout=10)


def resource_path(identifier):
    if not isinstance(identifier, str) or not RESOURCE_ID.fullmatch(identifier):
        raise ValueError(f"Invalid resource ID: {identifier!r}")
    namespace, path = identifier.split(":", 1)
    parts = PurePosixPath(path)
    if parts.is_absolute() or ".." in parts.parts or "//" in path:
        raise ValueError(f"Unsafe resource ID: {identifier}")
    return Path("assets") / namespace / Path(*parts.parts)


def inspect_geometry(data, identifier):
    geometry = json.loads(data)
    if "minecraft:geometry" in geometry:
        entries = geometry["minecraft:geometry"]
    else:
        entries = [value for key, value in geometry.items() if key.startswith("geometry.")]
    if not entries:
        raise ValueError(f"Missing geometry: {identifier}")
    summaries = []
    for entry in entries:
        bones = entry.get("bones", [])
        by_name = {bone["name"]: bone for bone in bones}
        # 上游 Bedrock 加载器以名称建表，同名骨骼会复用同一 part，保留资产并记录此情况
        duplicates = sorted({b["name"] for b in bones if sum(other["name"] == b["name"] for other in bones) > 1})
        for bone in bones:
            visited = set()
            current = bone
            while current.get("parent") is not None:
                name = current["name"]
                if name in visited:
                    raise ValueError(f"Bone cycle: {identifier}/{name}")
                visited.add(name)
                parent = current["parent"]
                if parent not in by_name:
                    raise ValueError(f"Unknown parent: {identifier}/{parent}")
                current = by_name[parent]
        summaries.append({"bones": len(bones), "cubes": sum(len(b.get("cubes", [])) for b in bones),
                          "duplicate_names": duplicates})
    return {"format": geometry.get("format_version"), "geometries": summaries}


def inspect_png(data, identifier):
    if data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
        raise ValueError(f"Invalid PNG header: {identifier}")
    width, height = struct.unpack(">II", data[16:24])
    if not width or not height:
        raise ValueError(f"Empty PNG: {identifier}")
    return {"width": width, "height": height}


def collect(upstream):
    actual = subprocess.check_output(["git", "-C", str(upstream), "rev-parse", "HEAD"], text=True).strip()
    if actual != UPSTREAM_COMMIT:
        raise ValueError(f"Expected {UPSTREAM_COMMIT}, got {actual}")
    subprocess.run(["git", "-C", str(upstream), "diff", "--exit-code", "HEAD", "--",
                    "src/main", "LICENSE-CC"], check=True, stdout=subprocess.DEVNULL)
    with GitBlobReader(upstream) as read_blob:
        return collect_files(upstream, read_blob)


def collect_files(upstream, read_blob):
    base = upstream / "src/main/resources"
    pack_root = base / "assets/touhou_little_maid/tlm_custom_pack/touhou_little_maid-1.0.0"
    java_root = upstream / "src/main/java/com/github/tartaricacid/touhoulittlemaid"
    info_source = (java_root / "client/resource/pojo/MaidModelInfo.java").read_text(encoding="utf-8")
    defaults = re.findall(r'new ResourceLocation\(TouhouLittleMaid\.MOD_ID, "(animation/[^"\n]+\.js)"\)', info_source)
    defaults = ["touhou_little_maid:" + value for value in defaults]
    internal = set()
    for path in (java_root / "client/animation/inner").glob("*.java"):
        internal.update(re.findall(r'INNER_ANIMATION\.put\(new ResourceLocation\("([^"\n]+)"\)',
                                   path.read_text(encoding="utf-8")))
    files = {}
    findings = {}

    def add_file(source, target):
        if not source.is_file():
            raise FileNotFoundError(source)
        source.resolve().relative_to(upstream.resolve())
        target = str(target).replace("\\", "/")
        relative = str(source.relative_to(upstream)).replace("\\", "/")
        data = read_blob(relative)
        if target in files and files[target]["sha256"] != digest(data):
            raise ValueError(f"Conflicting resource: {target}")
        files[target] = {"source": relative,
                         "sha256": digest(data), "bytes": len(data)}
        return data

    def add_resource(identifier):
        path = resource_path(identifier)
        candidates = [pack_root / path, base / path]
        found = [candidate for candidate in candidates if candidate.is_file()]
        if not found:
            raise FileNotFoundError(f"Missing {identifier}: {candidates}")
        if len(found) > 1 and read_blob(found[0].relative_to(upstream).as_posix()) != read_blob(found[1].relative_to(upstream).as_posix()):
            raise ValueError(f"Ambiguous resource: {identifier}")
        data = add_file(found[0], Path("src/main/resources") / path)
        if identifier.endswith(".png"):
            findings[identifier] = inspect_png(data, identifier)
        elif identifier.endswith(".json"):
            json.loads(data)
        return data

    normalized_packs = []
    all_ids = set()
    winefox = []
    used_internal = set()
    base_count = 0
    for pack_id in PACKS:
        metadata = pack_root / "assets" / pack_id / "maid_model.json"
        raw = json.loads(add_file(metadata, Path("src/main/resources/assets") / pack_id / "maid_model.json"))
        icon = raw.get("icon", "touhou_little_maid:textures/gui/empty_model_pack_icon.png")
        add_resource(icon)
        pack = {key: raw[key] for key in ("pack_name", "author", "description", "version", "date") if key in raw}
        pack.update({"id": pack_id, "icon": icon, "models": []})
        for item in raw["model_list"]:
            base_count += 1
            model_id = item["model_id"]
            namespace, path = model_id.split(":", 1)
            geometry = item.get("model", f"{namespace}:models/entity/{path}.json")
            texture = item.get("texture", f"{namespace}:textures/entity/{path}.png")
            gecko = bool(item.get("is_gecko", False))
            animations = item.get("animation") or ([DEFAULT_GECKO_ANIMATION] if gecko else defaults)
            if gecko:
                animations = [animation for animation in animations if animation.endswith(".json")]
                add_resource(DEFAULT_GECKO_ANIMATION)
            for animation in animations:
                if animation in internal:
                    used_internal.add(animation)
                    continue
                add_resource(animation)
            findings[geometry] = inspect_geometry(add_resource(geometry), geometry)
            for index, selected_texture in enumerate([texture, *item.get("extra_textures", [])]):
                selected_id = model_id
                if index:
                    suffix = hashlib.md5(selected_texture.split(":", 1)[1].encode("utf-8")).hexdigest()
                    selected_id += "_" + suffix
                if selected_id in all_ids:
                    raise ValueError(f"Duplicate model choice: {selected_id}")
                all_ids.add(selected_id)
                add_resource(selected_texture)
                name = item.get("name", f"{{model.{namespace}.{path}.name}}")
                if item.get("easter_egg") is not None:
                    kind = "encrypt" if item["easter_egg"].get("encrypt", False) else "normal"
                    name = "{gui.touhou_little_maid.model_gui.easter_egg." + kind + "}"
                choice = {"id": selected_id, "base_id": model_id, "geometry": geometry,
                          "texture": selected_texture, "gecko": gecko, "animations": animations,
                          "name": name, "source_info": item,
                          "description": item.get("description", []),
                          "entity_scale": max(0.2, min(2.0, item.get("render_entity_scale", 1.0))),
                          "item_scale": item.get("render_item_scale", 1.0)}
                pack["models"].append(choice)
                if "winefox" in model_id or "wine_fox" in model_id:
                    winefox.append(selected_id)
        for language in sorted((metadata.parent / "lang").glob("*.json")):
            add_file(language, Path("src/main/resources/assets") / pack_id / "lang" / language.name)
        normalized_packs.append(pack)
    if base_count != 234 or len(winefox) != 35:
        raise ValueError(f"Unexpected fixed inventory: base={base_count}, winefox={len(winefox)}")
    add_file(upstream / "LICENSE-CC", Path("licenses/TouhouLittleMaid-CC-BY-NC-SA-4.0.txt"))
    catalog = {"schema": 1, "upstream_commit": UPSTREAM_COMMIT,
               "internal_animation_ids": sorted(used_internal), "packs": normalized_packs}
    provenance = {"schema": 1, "upstream": "https://github.com/TartaricAcid/TouhouLittleMaid",
                  "commit": UPSTREAM_COMMIT, "license": "CC-BY-NC-SA-4.0",
                  "changes": "Resource paths flattened from the bundled pack; asset file bytes preserved",
                  "files": dict(sorted(files.items()))}
    report = {"passed": True, "scope": "static geometry, texture and listed animation resources; rendering and animation not tested",
              "base_models": base_count, "choices": len(all_ids), "winefox_choices": winefox,
              "file_count": len(files), "bytes": sum(f["bytes"] for f in files.values()),
              "resource_inspection": findings, "internal_animation_ids": sorted(used_internal),
              "sound_particle_events_validated": False}
    return catalog, provenance, report


def write(upstream, catalog, provenance):
    # 只写已核对资源或本脚本先前拥有的目标，不覆盖无关源码
    manifest = PROJECT / "src/main/resources/assets/tlmnh/builtin-assets-provenance.json"
    previous = json.loads(manifest.read_text(encoding="utf-8"))["files"] if manifest.is_file() else {}
    for target, entry in provenance["files"].items():
        destination = PROJECT / target
        destination.resolve().relative_to(PROJECT.resolve())
        if destination.exists():
            actual = digest(destination.read_bytes())
            if actual != entry["sha256"] and actual != previous.get(target, {}).get("sha256"):
                raise ValueError(f"Refusing to overwrite unowned content: {destination}")
    with GitBlobReader(upstream) as read_blob:
        for target, entry in provenance["files"].items():
            data = read_blob(entry["source"])
            if digest(data) != entry["sha256"]:
                raise ValueError(f"Upstream blob changed: {entry['source']}")
            destination = PROJECT / target
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(data)
    for name, data in (("builtin-models.json", catalog), (manifest.name, provenance)):
        target = manifest.parent / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", type=Path, required=True)
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    catalog, provenance, report = collect(args.upstream.resolve(strict=True))
    if args.write:
        write(args.upstream, catalog, provenance)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: report[key] for key in ("passed", "scope", "base_models", "choices", "file_count", "bytes")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
