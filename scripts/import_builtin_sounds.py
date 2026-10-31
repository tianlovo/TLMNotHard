"""读取固定上游声音目录；配音只写入被忽略的本机构建输入，不上传素材集合"""

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


def collect(upstream):
    paths = subprocess.check_output(["git", "-C", str(upstream), "ls-tree", "-r", "--name-only", UPSTREAM_COMMIT], text=True).splitlines()
    with GitBlobReader(upstream) as read:
        constants = dict(re.findall(r'(\w+) = registerSound\("([^"]+)"\)', read(JAVA + "init/InitSounds.java").decode()))
        loader = read(JAVA + "client/sound/CustomSoundLoader.java").decode().split("public static void loadSoundPack(ZipFile")[0]
        events = [(constants[key], folder, name) for key, folder, name in re.findall(
            r'buffers.put\(InitSounds\.(\w+)\.get\(\)\.getLocation\(\), loadSounds\(rootPath.resolve\("([^"]+)"\), "([^"]+)"\)\)', loader)]
        reuse = [(constants[a], constants[b]) for a, b in re.findall(r'reuseSounds\(buffers, InitSounds\.(\w+)\.get\(\), InitSounds\.(\w+)\.get\(\)\)', loader)]
        assert len(events) == 31
        files, local_audio, packs = {}, {}, []
        for pack in PACKS:
            prefix = BASE + pack + "/"
            info_path = prefix + "maid_sound.json"
            info = json.loads(read(info_path))
            audio = [path for path in paths if path.startswith(prefix + "sounds/maid/") and path.endswith(".ogg")]
            event_files = {}
            for event, folder, name in events:
                pattern = re.compile(re.escape(prefix + "sounds/maid/" + folder + "/" + name) + r"\d*\.ogg$")
                event_files[event] = [pack + ":" + path[len(prefix + "sounds/"):-4] for path in audio if pattern.fullmatch(path)]
            for source, target in reuse:
                event_files[target].extend(event_files[source])
            native = {event: {"category": "neutral", "sounds": sounds} for event, sounds in event_files.items()}
            for path in [info_path] + [path for path in paths if path.startswith(prefix + "lang/") and path.endswith(".json")]:
                files["assets/" + pack + "/" + path[len(prefix):]] = (read(path), path)
            for path in audio:
                data = read(path)
                assert data[:4] == b"OggS"
                local_audio["assets/" + pack + "/" + path[len(prefix):]] = (data, path)
            packs.append({"id": pack, "info": info, "events": {key: len(value) for key, value in event_files.items()}})
            files["assets/" + pack + "/sounds.json"] = ((json.dumps(native, ensure_ascii=False, indent=2) + "\n").encode(), None)
        for target, source in [
            ("assets/touhou_little_maid/textures/gui/maid_custom_sound.png", "src/main/resources/assets/touhou_little_maid/textures/gui/maid_custom_sound.png"),
            ("assets/touhou_little_maid/textures/sound_icon.png", BASE + "touhou_little_maid/textures/sound_icon.png")]:
            files[target] = (read(source), source)
        catalog = {"schema": 1, "upstream_commit": UPSTREAM_COMMIT, "packs": packs}
        provenance = {"commit": UPSTREAM_COMMIT, "audio_distribution_cleared": False,
                      "audio_scope": "local generated build input only; creator terms require separate release review",
                      "files": [{"path": path, "source": source, "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data),
                                 "local_audio": path in local_audio} for path, (data, source) in sorted({**files, **local_audio}.items())]}
        return files, local_audio, catalog, provenance


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", type=Path, required=True)
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    files, audio, catalog, provenance = collect(args.upstream)
    if args.write:
        for base, entries in [(PROJECT / "src/main/resources", files), (PROJECT / "build/generated/maid-sounds", audio)]:
            for name, (data, _) in entries.items():
                path = base / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)
        for name, value in [("builtin-sounds.json", catalog), ("builtin-sounds-provenance.json", provenance)]:
            (PROJECT / "src/main/resources/assets/tlmnh" / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    report = {"passed": True, "metadata_files": len(files), "audio_files": len(audio), "audio_bytes": sum(len(data) for data, _ in audio.values()),
              "events": {pack["id"]: pack["events"] for pack in catalog["packs"]}, "audio_distribution_cleared": False}
    if args.report:
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
