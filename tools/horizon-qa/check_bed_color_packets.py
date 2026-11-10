"""检查实装 Hodgepodge 格式的女仆床 S35 协议包。"""

import argparse
import gzip
import hashlib
import json
import struct
import zipfile
from pathlib import Path

MIXIN = "com/mitchej123/hodgepodge/mixins/early/minecraft/packets/MixinS35PacketUpdateTileEntity_ByteArray.class"
MIXIN_SHA = "510ebf740bc370a74227e9f947d3b7347851192618d5bb8f7f11bec00028ca28"
COLORS = {"white": 0, "black": 15, "yellow": 4, "blue": 11, "green": 13, "purple": 10, "pink": 6}
TEST = "tlmnhqapilot:MaidBedColorTests.sevenDyesUseEitherHalfKeepMetadataAndPersistWithoutExtraConsumption"


def require(value, message):
    if not value:
        raise ValueError(message)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def check_packet(raw, color_id, origin):
    require(len(raw) >= 14, "truncated S35 packet")
    x, y, z, action = struct.unpack(">ihib", raw[:11])
    require((x, y, z, action) == (origin[0] + 8, origin[1] + 1, origin[2] + 4, 0), "S35 header changed")
    # 已审 Mixin 在 NBT 前另写 mode；此负载的 BedColor 键为八字符整数，必走 mode0 普通 NBT 分支
    require(raw[11] == 0, "unexpected Hodgepodge NBT mode")
    size = struct.unpack(">h", raw[12:14])[0]
    require(0 < size < 32767 and len(raw) == 14 + size, "S35 compressed payload length mismatch")
    payload = gzip.decompress(raw[14:])
    expected = b"\x0a\x00\x00\x03\x00\x08BedColor" + struct.pack(">i", color_id) + b"\x00"
    require(payload == expected, "color NBT differs or contains unrelated tile state")
    return {"packet_bytes": len(raw), "compressed_bytes": size, "color_id": color_id}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", type=Path, required=True)
    parser.add_argument("--candidate-jar", type=Path, required=True)
    parser.add_argument("--pilot-jar", type=Path, required=True)
    parser.add_argument("--hodgepodge-jar", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    with zipfile.ZipFile(args.hodgepodge_jar) as jar:
        require(hashlib.sha256(jar.read(MIXIN)).hexdigest() == MIXIN_SHA, "unreviewed S35 protocol resource")
    hqa = json.loads((args.run / "horizonqa-result.json").read_bytes())
    observed = json.loads((args.run / "observed.json").read_bytes())
    require(hqa["status"] == "passed" and hqa["exitCode"] == observed["process_exit"] == 0
            and hqa["counts"]["selectedTests"] == hqa["counts"]["passed"] == 1
            and len(hqa["tests"]) == 1 and hqa["tests"][0]["id"] == TEST
            and hqa["tests"][0]["status"] == "passed" and observed["status"] == hqa, "packet run did not pass")
    require(sha(args.candidate_jar) == observed["candidate_sha256"]
            and sha(args.pilot_jar) == observed["pilot_sha256"], "actual candidate/pilot mismatch")
    require({p.name for p in args.run.glob("bed-color-*.s35")} == {"bed-color-" + c + ".s35" for c in COLORS},
            "seven actual packets missing or duplicated")
    origin = tuple(int(v) for v in hqa["configuration"]["gridOrigin"].split(","))
    packets = {}
    for color, color_id in COLORS.items():
        path = args.run / ("bed-color-" + color + ".s35")
        packets[color] = {**check_packet(path.read_bytes(), color_id, origin), "sha256": sha(path)}
    result = {"passed": True, "packets": packets, "candidate_sha256": sha(args.candidate_jar),
              "pilot_sha256": sha(args.pilot_jar), "supplied_protocol_jar_sha256": sha(args.hodgepodge_jar),
              "supplied_protocol_resource_sha256": MIXIN_SHA, "runtime_transformed_definition_verified": False,
              "actual_client_receive_or_render_verified": False,
              "scope": "seven native outbound S35 headers, reviewed mode0 prefix, gzip integrity and exact color-only NBT"}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"passed": True, "packets": len(packets), "actual_client_receive_verified": False}))


if __name__ == "__main__":
    main()
