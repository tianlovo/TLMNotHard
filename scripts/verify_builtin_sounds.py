"""核对源码、处理后资源或 JAR 的完整固定声音资源，不依赖上游 checkout 或网络。"""

import argparse
import hashlib
import json
import sys
import zipfile
from pathlib import Path, PurePosixPath

PROJECT = Path(__file__).resolve().parents[1]
MANIFEST = "assets/tlmnh/builtin-sounds-provenance.json"
CATALOG = "assets/tlmnh/builtin-sounds.json"
UPSTREAM_COMMIT = "8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6"
# 从固定 GitBlobReader 完整导入获得，不能同时改音频和清单后自行通过完整性门槛
MANIFEST_SHA256 = "2c7957f377103ea90ec71b82c5969c334b52954c66e21c13036869d429aaf5b0"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def safe_member(name):
    path = PurePosixPath(name)
    require(name.startswith("assets/") and not path.is_absolute() and ".." not in path.parts
            and "\\" not in name and ":" not in name and path.as_posix() == name,
            "Unsafe sound resource path: " + name)


def verify(read, names):
    names = list(names)
    raw = read(MANIFEST)
    require(digest(raw) == MANIFEST_SHA256, "Sound authority manifest differs from the fixed upstream inventory")
    provenance = json.loads(raw)
    catalog_raw = read(CATALOG)
    require(digest(catalog_raw) == provenance["catalog_sha256"], "Sound catalog differs from the authority manifest")
    catalog = json.loads(catalog_raw)
    require(provenance["schema"] == 2 and provenance["commit"] == catalog["upstream_commit"] == UPSTREAM_COMMIT,
            "Sound source schema or fixed commit differs")
    recorded, audio, voice, base = set(), set(), set(), set()
    for entry in provenance["files"]:
        name = entry["path"]
        safe_member(name)
        require(name not in recorded and names.count(name) == 1, "Missing or duplicate sound resource: " + name)
        recorded.add(name)
        data = read(name)
        require(len(data) == entry["bytes"] and digest(data) == entry["sha256"], "Sound bytes differ: " + name)
        require(isinstance(entry["audio"], bool), "Invalid audio classification: " + name)
        if entry["audio"]:
            require(name.endswith(".ogg") and data.startswith(b"OggS") and entry["source"], "Invalid sourced Ogg: " + name)
            audio.add(name)
            require(entry["audio_group"] in ("voice", "base"), "Unknown original audio group: " + name)
            (voice if entry["audio_group"] == "voice" else base).add(name)
    require(names.count(MANIFEST) == names.count(CATALOG) == 1, "Missing or duplicate sound manifests")
    actual_audio = [name for name in names if name.lower().endswith(".ogg")]
    require(len(actual_audio) == len(set(actual_audio)) and set(actual_audio) == audio,
            "Actual Ogg inventory differs from the complete fixed inventory")
    require(len(audio) == 445 and len(voice) == 435 and len(base) == 10, "Expected 435 voice and 10 base Ogg resources")
    packs, base_references = [], set()
    for pack in catalog["packs"]:
        namespace = pack["id"]
        require(json.loads(read(f"assets/{namespace}/maid_sound.json")) == pack["info"], "Original author metadata differs")
        events = json.loads(read(f"assets/{namespace}/sounds.json"))
        base_events = catalog["base_events"] if namespace == "touhou_little_maid" else {}
        require(set(events) == set(pack["events"]) | set(base_events), "Sound event inventory differs: " + namespace)
        references = set()
        for event, definition in events.items():
            entries = definition["sounds"]
            if event in pack["events"]:
                require(len(entries) == pack["events"][event], "Original voice event count differs: " + event)
            else:
                require(definition == base_events[event], "Original base event association differs: " + event)
            for resource in entries:
                reference = resource if isinstance(resource, str) else resource["name"]
                domain, path = reference.split(":", 1) if ":" in reference else (namespace, reference)
                target = f"assets/{domain}/sounds/{path}.ogg"
                require(target in audio, "Sound event references a missing or unsourced resource: " + target)
                references.add(target)
                if event in base_events:
                    base_references.add(target)
        pack_audio = {name for name in voice if name.startswith(f"assets/{namespace}/")}
        packs.append({"id": namespace, "authors": pack["info"]["author"], "source_url": pack["info"]["url"],
                      "events": len(pack["events"]), "audio_files": len(pack_audio),
                      "referenced_audio_files": len(references), "audio_bytes": sum(len(read(name)) for name in pack_audio)})
    require({pack["id"] for pack in packs} == {"littlemaid_peco", "touhou_little_maid"} and len(packs) == 2,
            "Unexpected sound pack inventory")
    require(base_references == base, "Base event associations omit original base Ogg resources")
    return {"passed": True, "audio_files": 445, "voice_files": 435, "base_files": 10,
            "audio_bytes": sum(entry["bytes"] for entry in provenance["files"] if entry["audio"]),
            "source_entries": len(recorded), "provenance_sha256": digest(raw), "upstream_commit": UPSTREAM_COMMIT,
            "audio_distribution_cleared": provenance["audio_distribution_cleared"], "packs": packs,
            "scope": "complete fixed bytes and event references; not playback or release approval"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifact", nargs="?", type=Path)
    parser.add_argument("--resources", type=Path, default=PROJECT / "src/main/resources")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    try:
        if args.artifact:
            with zipfile.ZipFile(args.artifact) as archive:
                report = verify(archive.read, (entry.filename for entry in archive.infolist() if not entry.is_dir()))
        else:
            names = [path.relative_to(args.resources).as_posix() for path in args.resources.rglob("*") if path.is_file()]
            report = verify(lambda name: (args.resources / name).read_bytes(), names)
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile) as error:
        print("Builtin sound verification failed: " + str(error), file=sys.stderr)
        return 1
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(json.dumps(report, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
