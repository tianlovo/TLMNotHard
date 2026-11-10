"""检查原生神龛S35最小显示负载，不替代客户端接收与成像。"""

import argparse
import gzip
import json
import re
import struct
import zipfile

from pathlib import Path

from check_bed_color_packets import MIXIN, MIXIN_SHA, require, sha


def check_packet(raw, kind, origin):
    require(len(raw) >= 14, "truncated S35 packet")
    require(struct.unpack(">ihib", raw[:11]) == (*origin, 0), "shrine S35 header differs")
    require(raw[11] == 0, "unreviewed Hodgepodge NBT mode")
    size = struct.unpack(">h", raw[12:14])[0]
    require(0 < size < 32767 and len(raw) == 14 + size, "compressed S35 length differs")
    nbt = gzip.decompress(raw[14:])
    require(nbt[:3] == b"\x0a\x00\x00", "unnamed compound required")
    offset, values = 3, {}
    while offset < len(nbt) and nbt[offset] != 0:
        require(nbt[offset] == 1 and offset + 3 <= len(nbt), "display fields must be bytes")
        length = struct.unpack(">H", nbt[offset + 1:offset + 3])[0]
        offset += 3
        require(offset + length < len(nbt), "truncated display field")
        key = nbt[offset:offset + length].decode("utf-8")
        require(key not in values, "duplicate display field")
        offset += length
        values[key] = nbt[offset]
        offset += 1
    require(offset + 1 == len(nbt) and nbt[offset] == 0, "unconsumed NBT data")
    require(values == {"DisplayVersion": 1, "StorageKind": kind},
            "display NBT contains private, unknown or incorrect fields")
    return {"packet_bytes": len(raw), "uncompressed_bytes": len(nbt), "fields": values}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", type=Path, required=True)
    parser.add_argument("--candidate-jar", type=Path, required=True)
    parser.add_argument("--hodgepodge-jar", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    observed = json.loads((args.run / "observed.json").read_bytes())
    result = json.loads((args.run / "horizonqa-result.json").read_bytes())
    require(observed["process_exit"] == result["exitCode"] == 0 and result["status"] == "passed"
            and result["counts"]["selectedTests"] == result["counts"]["passed"] == 3
            and observed["status"] == result, "three native shrine tests must pass")
    require(sha(args.candidate_jar) == observed["candidate_sha256"]
            and sha(args.run / "tested-pilot.jar") == observed["pilot_sha256"], "candidate/pilot mismatch")
    require(all(test["id"].startswith("tlmnhqapilot:ShrineDisplayTests.")
                and test["status"] == "passed" for test in result["tests"]), "wrong test scope")
    with zipfile.ZipFile(args.hodgepodge_jar) as jar:
        import hashlib
        require(hashlib.sha256(jar.read(MIXIN)).hexdigest() == MIXIN_SHA, "unreviewed S35 protocol resource")
    wire_tests = [test for test in result["tests"] if test["name"] == "serializeActualEmptyFilmAndRecoveryDescriptionPacketsWithoutPrivateStorage"]
    require(len(wire_tests) == 1, "one exact native serialization test required")
    positions = re.findall(r"Placed template 'tlmnhqapilot:modern_core_cell' at TestPos\{x=(-?\d+), y=(-?\d+), z=(-?\d+)\}",
                           "\n".join(wire_tests[0]["output"]))
    require(len(positions) == 1, "one actual test template origin required")
    fixture = tuple(int(v) for v in positions[0])
    origin = (fixture[0] + 8, fixture[1] + 1, fixture[2] + 4)
    require({p.name for p in args.run.glob("shrine-*.s35")} == {"shrine-" + k + ".s35" for k in ("empty", "film", "recovery")},
            "three actual shrine packets required")
    packets = {}
    for kind, number in (("empty", 0), ("film", 1), ("recovery", 2)):
        path = args.run / ("shrine-" + kind + ".s35")
        packets[kind] = {**check_packet(path.read_bytes(), number, origin), "sha256": sha(path)}
    report = {"passed": True, "packets": packets, "candidate_sha256": observed["candidate_sha256"],
              "pilot_sha256": observed["pilot_sha256"], "protocol_resource_sha256": MIXIN_SHA,
              "runtime_transformed_definition_verified": False, "actual_client_receive_or_render_verified": False,
              "scope": "actual native S35 headers, Hodgepodge mode0 and exact display-only byte fields; no stored item/token/owner"}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"passed": True, "packets": len(packets), "actual_client_receive_verified": False}))


if __name__ == "__main__":
    main()
