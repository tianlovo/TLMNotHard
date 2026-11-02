"""核对原版自然睡眠可行性资料，输出自身变异，不授予移植等价或完整B10结论"""

import argparse
import hashlib
import json
import math
from pathlib import Path

UPSTREAM = "8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def require(value, message):
    if not value:
        raise ValueError(message)


def check(capture, build, tool, agent_bytes):
    data = json.loads(capture)
    manifest = json.loads(build)
    require(manifest["schema"] == "sleep-natural-build-v1" and manifest["upstreamCommit"] == UPSTREAM,
            "wrong original build")
    require(manifest["baseReferenceBuildIdentity"]["status"] == "LOCK_VERIFIED", "base toolchain unverified")
    for item in manifest["toolInputs"]:
        path = (tool / item["file"]).resolve()
        require(path.is_relative_to(tool.resolve()) and sha(path.read_bytes()) == item["sha256"],
                "runtime tool input changed: " + item["file"])
    contract_bytes = (tool / "contract.json").read_bytes()
    contract = json.loads(contract_bytes)
    require(contract["schema"] == "sleep-natural-feasibility-contract-v1"
            and contract["schedule"] == "NIGHT" and contract["dayTime"] == 6000
            and contract["bedFacing"] == "NORTH" and contract["owner"] == "offline_uuid_no_player",
            "unsupported baseline inputs")
    require(data["contractSha256"] == manifest["contractSha256"] == sha(contract_bytes), "contract mismatch")
    require(data["contract"] == contract and contract["upstreamCommit"] == UPSTREAM, "raw contract changed")
    require(data["observationComplete"] and data["cleanupComplete"] and "error" not in data,
            "observation or cleanup incomplete")
    require(data["benchmarkVerdict"] == "NOT_EVALUATED" and data["completeB10Accepted"] is False
            and data["randomSourcesReset"] is False, "scope was upgraded")
    identity = data["identity"]
    require(identity["wholeClasspathOrPostDefinitionBytesVerified"] is False, "identity scope was upgraded")
    require(identity["buildIdentitySha256"] == identity["agent"]["manifestSha256"] == sha(build),
            "premain build identity mismatch")
    require(identity["agent"]["agentSha256"] == manifest["definitionAgentSha256"] == sha(agent_bytes),
            "actual definition agent mismatch")
    require(set(identity["classes"]) == set(manifest["compiledClasses"]) and len(identity["classes"]) == 9,
            "selected class set changed")
    different = []
    for name, observed in identity["classes"].items():
        require(observed["resourceSha256"] == manifest["compiledClasses"][name], "compiled class changed: " + name)
        definition = observed["definition"]
        require(definition["count"] == 1 and definition["stage"] ==
                "premain-transformer-input-after-defining-loader-transformations"
                and len(definition["sha256"]) == 64, "invalid first definition: " + name)
        if definition["sha256"] != observed["resourceSha256"]:
            different.append(name)
    runs = data["runs"]
    require(len(runs) == contract["repetitions"] and len({row["uuid"] for row in runs}) == len(runs),
            "fresh actor repetitions missing")
    summaries = []
    for index, row in enumerate(runs):
        require(row["repetition"] == index and row["nativeBedPoiCount"] == 1, "wrong actor/bed inputs")
        require(row["entityClass"] == "com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid"
                and row["brainClass"] == "net.minecraft.world.entity.ai.Brain", "not the exact original entity/Brain")
        frames = row["frames"]
        require(len(frames) == contract["framesPerActor"], "frames missing")
        for frame, sample in enumerate(frames):
            require(sample["frame"] == frame and sample["age"] == frame + 1 and sample["livingTicks"] == 1,
                    "natural tick sequence corrupted")
            require(sample["dayTime"] == contract["dayTime"] and sample["activity"] == "rest", "rest input changed")
            require({s.strip() for s in sample["activeBrainActivities"].strip("[]").split(",")} == {"core", "rest"},
                    "actual original activities changed")
            require(all(math.isfinite(v) for key in ("position", "motion") for v in sample[key]), "nonfinite sample")
            if frame:
                require(sample["gameTime"] == frames[frame - 1]["gameTime"] + 1, "world tick sequence corrupted")
        asleep = [sample for sample in frames if sample["sleeping"]]
        summaries.append({"repetition": index, "frames": len(frames),
                          "first_sleep_frame": asleep[0]["frame"] if asleep else None,
                          "sleep_frames": len(asleep), "actual_radius": row["actualRestrictRadius"]})
    return {"passed": True, "scope": "original observation integrity and self-variation only",
            "benchmark_verdict": "NOT_EVALUATED", "complete_b10_accepted": False,
            "frames": sum(row["frames"] for row in summaries), "runs": summaries,
            "definition_resource_differences": different,
            "definition_difference_explanation_verified": False,
            "random_sources_reset": False, "statistical_or_port_equivalence_accepted": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("capture", type=Path)
    parser.add_argument("--build-identity", required=True, type=Path)
    parser.add_argument("--agent", required=True, type=Path)
    parser.add_argument("--tool", type=Path, default=Path(__file__).resolve().parent)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = check(args.capture.read_bytes(), args.build_identity.read_bytes(), args.tool, args.agent.read_bytes())
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
