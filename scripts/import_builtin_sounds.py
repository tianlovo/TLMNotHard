"""从固定 Git blob 导入全部内置声音，原始 OGG 作为可离线构建的源码资源。"""

import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

from import_builtin_assets import GitBlobReader, UPSTREAM_COMMIT

PROJECT = Path(__file__).resolve().parents[1]
BASE = "src/main/resources/assets/touhou_little_maid/tlm_custom_pack/touhou_little_maid-1.0.0/assets/"
JAVA = "src/main/java/com/github/tartaricacid/touhoulittlemaid/"
PACKS = ("littlemaid_peco", "touhou_little_maid")
BASE_ROOT = "src/main/resources/assets/touhou_little_maid/"
BASE_AUDIO = (
    "block/altar_craft_1.ogg", "block/altar_craft_2.ogg", "block/gomoku_reset.ogg", "block/piece.ogg",
    "entity/box.ogg", "item/camera_use.ogg", "item/point.ogg", "maid/empty.ogg",
    "ui/recording_end.ogg", "ui/recording_start.ogg",
)


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def collect(upstream):
    paths = subprocess.check_output(["git", "-C", str(upstream), "ls-tree", "-r", "--name-only", UPSTREAM_COMMIT], text=True).splitlines()
    with GitBlobReader(upstream) as read:
        constants = dict(re.findall(r'(\w+) = registerSound\("([^"]+)"\)', read(JAVA + "init/InitSounds.java").decode()))
        loader = read(JAVA + "client/sound/CustomSoundLoader.java").decode().split("public static void loadSoundPack(ZipFile")[0]
        events = [(constants[key], folder, name) for key, folder, name in re.findall(
            r'buffers.put\(InitSounds\.(\w+)\.get\(\)\.getLocation\(\), loadSounds\(rootPath.resolve\("([^"]+)"\), "([^"]+)"\)\)', loader)]
        reuse = [(constants[a], constants[b]) for a, b in re.findall(r'reuseSounds\(buffers, InitSounds\.(\w+)\.get\(\), InitSounds\.(\w+)\.get\(\)\)', loader)]
        assert len(events) == 31
        files, audio, packs = {}, {}, []
        groups = {}
        base_definitions = json.loads(read(BASE_ROOT + "sounds.json"))
        base_ids = {"touhou_little_maid:" + path[:-4] for path in BASE_AUDIO}
        base_events = {}
        for event, definition in base_definitions.items():
            references = [value if isinstance(value, str) else value["name"] for value in definition["sounds"]]
            if event not in {value[0] for value in events} and references and all(value in base_ids for value in references):
                # 旧版没有 voice 类别；不引入 fairy/rabbit 外部资源或新的 Java 触发
                base_events[event] = {"category": "neutral" if definition.get("category") == "voice"
                                      else definition.get("category", "master"), "sounds": definition["sounds"]}
        for relative in BASE_AUDIO:
            source = BASE_ROOT + "sounds/" + relative
            target = "assets/touhou_little_maid/sounds/" + relative
            data = read(source)
            if not data.startswith(b"OggS"):
                raise ValueError("Not an original Ogg resource: " + source)
            audio[target] = (data, source)
            groups[target] = "base"
        for pack in PACKS:
            prefix = BASE + pack + "/"
            info_path = prefix + "maid_sound.json"
            info = json.loads(read(info_path))
            voice_audio = [path for path in paths if path.startswith(prefix + "sounds/maid/") and path.endswith(".ogg")]
            event_files = {}
            for event, folder, name in events:
                pattern = re.compile(re.escape(prefix + "sounds/maid/" + folder + "/" + name) + r"\d*\.ogg$")
                event_files[event] = [pack + ":" + path[len(prefix + "sounds/"):-4] for path in voice_audio if pattern.fullmatch(path)]
            for source, target in reuse:
                event_files[target].extend(event_files[source])
            native = {event: {"category": "neutral", "sounds": sounds} for event, sounds in event_files.items()}
            if pack == "touhou_little_maid":
                native.update({event: definition for event, definition in base_events.items() if event not in native})
            for path in [info_path] + [path for path in paths if path.startswith(prefix + "lang/") and path.endswith(".json")]:
                files["assets/" + pack + "/" + path[len(prefix):]] = (read(path), path)
            for path in voice_audio:
                data = read(path)
                assert data[:4] == b"OggS"
                target = "assets/" + pack + "/" + path[len(prefix):]
                audio[target] = (data, path)
                groups[target] = "voice"
            packs.append({"id": pack, "info": info, "events": {key: len(value) for key, value in event_files.items()}})
            files["assets/" + pack + "/sounds.json"] = (json_bytes(native), None)
        for target, source in [
            ("assets/touhou_little_maid/textures/gui/maid_custom_sound.png", "src/main/resources/assets/touhou_little_maid/textures/gui/maid_custom_sound.png"),
            ("assets/touhou_little_maid/textures/sound_icon.png", BASE + "touhou_little_maid/textures/sound_icon.png")]:
            files[target] = (read(source), source)
        source_audio = {path for path in paths if path.endswith(".ogg")}
        if len(audio) != 445 or len([value for value in groups.values() if value == "voice"]) != 435:
            raise ValueError("Expected exactly 435 voice and 10 base Ogg resources")
        if {source for _, source in audio.values()} != source_audio:
            raise ValueError("Fixed upstream Ogg tree contains an omitted or unexpected resource")
        catalog = {"schema": 1, "upstream_commit": UPSTREAM_COMMIT, "packs": packs, "base_events": base_events}
        provenance = {"schema": 2, "commit": UPSTREAM_COMMIT, "audio_distribution_cleared": False,
                      "audio_scope": "tracked original-byte source resources; release attribution/reporting remains recorded separately",
                      "audio_files": 445, "voice_files": 435, "base_files": 10,
                      "catalog_sha256": hashlib.sha256(json_bytes(catalog)).hexdigest(),
                      "derivation_sources": [{"path": path, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
                                             for path in [JAVA + "init/InitSounds.java", JAVA + "client/sound/CustomSoundLoader.java", BASE_ROOT + "sounds.json"]
                                             for data in [read(path)]],
                      "files": [{"path": path, "source": source, "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data),
                                 "audio": path in audio, "audio_group": groups.get(path)}
                                for path, (data, source) in sorted({**files, **audio}.items())]}
        return files, audio, catalog, provenance


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", type=Path, required=True)
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    files, audio, catalog, provenance = collect(args.upstream)
    if args.write:
        for base, entries in [(PROJECT / "src/main/resources", {**files, **audio})]:
            for name, (data, _) in entries.items():
                path = base / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)
        for name, value in [("builtin-sounds.json", catalog), ("builtin-sounds-provenance.json", provenance)]:
            (PROJECT / "src/main/resources/assets/tlmnh" / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    report = {"passed": True, "metadata_files": len(files), "audio_files": len(audio), "audio_bytes": sum(len(data) for data, _ in audio.values()),
              "voice_files": 435, "base_files": 10, "base_events": len(catalog["base_events"]),
              "provenance_sha256": hashlib.sha256(json_bytes(provenance)).hexdigest(),
              "events": {pack["id"]: pack["events"] for pack in catalog["packs"]}, "audio_distribution_cleared": False}
    if args.report:
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
