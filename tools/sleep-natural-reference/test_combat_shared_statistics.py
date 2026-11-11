"""共有战斗语义多区组统计评估器单元测试。"""

import json
import math
import random
import tempfile
import unittest
import uuid
from pathlib import Path

from evaluate_combat_shared_statistics import (
    BOUNDARY_ARMS,
    FLIGHT_PROFILES,
    MECHANISM_ARMS,
    SCENE_FRAMES,
    build_campaign_schedule,
    clopper_pearson_zero_event_ucb,
    derive_scheduled_block_seed,
    evaluate_manifest,
    expected_pair_execution_order,
    monotone_prpl_eb_anytime_lcb,
    oracle_zero_diff_min_blocks,
    prpl_eb_monotone_log_wealth,
    single_point_betting_eprocess_inf,
    single_point_betting_eprocess_ni,
    student_t_quantile,
    time_uniform_spending_hoeffding_lcb,
    wilson_interval,
)

CONTRACT_PATH = Path(__file__).with_name("combat-shared-statistics-contract.json")


def _frozen_contract_bytes(mutate_fn=None):
    doc = json.loads(CONTRACT_PATH.read_bytes())
    doc["contractStatus"] = "FROZEN"
    if mutate_fn is not None:
        mutate_fn(doc)
    return json.dumps(doc, ensure_ascii=False).encode("utf-8")


def _new_uid(seed_tag):
    return str(uuid.uuid5(uuid.NAMESPACE_OID, seed_tag))


def _make_shots(count, hits, source_orig, start_frame=20, step=22, safety_crossings=False):
    shots = []
    for idx in range(count):
        spawn = start_frame + idx * step
        if safety_crossings and 60 <= spawn < 105:
            spawn = 125 + (idx - 2) * 22
        row = {
            "uuid": _new_uid(f"shot-{source_orig}-{spawn}-{idx}-{uuid.uuid4()}"),
            "spawnFrame": spawn,
            "sourceOriginal": source_orig,
            "hit": idx < hits,
        }
        if safety_crossings and spawn == 125:
            row["friendlyCrossing"] = "same_owner_pet"
            row["allyHealthAfterCrossing"] = 20.0
            row["allyBurningAfterCrossing"] = False
        elif safety_crossings and spawn == 147:
            row["friendlyCrossing"] = "native_owner"
            row["allyHealthAfterCrossing"] = 20.0
            row["allyBurningAfterCrossing"] = False
        shots.append(row)
    return shots


def _build_capture(scene, arm, block_tag, contract_sha, is_boundary, overrides=None):
    overrides = overrides or {}
    is_orig = arm == "O"
    source_orig = arm in ("O", "F")
    total_frames = overrides.get("frame_count", SCENE_FRAMES[scene])
    maid_id = overrides.get("maid_uuid", _new_uid(f"{block_tag}-{scene}-{arm}-maid"))
    owner_id = overrides.get("owner_uuid", _new_uid(f"{block_tag}-{scene}-{arm}-owner"))
    friend_id = _new_uid(f"{block_tag}-{scene}-{arm}-friend")
    target_id = _new_uid(f"{block_tag}-{scene}-{arm}-target")
    comp_id = _new_uid(f"{block_tag}-{scene}-{arm}-comp")
    zombie_id = _new_uid(f"{block_tag}-{scene}-{arm}-zombie")

    if scene == "safety":
        joins = overrides.get("joins", 7)
        hits = overrides.get("hits", 7)
        post_start = 105 + overrides.get("restore_latency", 20)
        shots = [
            {
                "uuid": _new_uid(f"{block_tag}-{arm}-s0"),
                "spawnFrame": 20,
                "sourceOriginal": source_orig,
                "hit": hits >= 1,
            },
            {
                "uuid": _new_uid(f"{block_tag}-{arm}-s1"),
                "spawnFrame": 42,
                "sourceOriginal": source_orig,
                "hit": hits >= 2,
            },
        ]
        for k in range(max(0, joins - 2)):
            sf = min(219, post_start + k * 22)
            srow = {
                "uuid": _new_uid(f"{block_tag}-{arm}-sp-{k}"),
                "spawnFrame": sf,
                "sourceOriginal": source_orig,
                "hit": (k + 2) < hits,
            }
            if k == 0:
                srow["friendlyCrossing"] = "same_owner_pet"
                srow["allyHealthAfterCrossing"] = 20.0
                srow["allyBurningAfterCrossing"] = False
            elif k == 1:
                srow["friendlyCrossing"] = "native_owner"
                srow["allyHealthAfterCrossing"] = 20.0
                srow["allyBurningAfterCrossing"] = False
            shots.append(srow)
    elif scene == "withdraw":
        joins = overrides.get("joins", 4)
        hits = overrides.get("hits", 3)
        spawns = overrides.get("spawns", [48, 95, 117, 139])[:joins]
        shots = [
            {
                "uuid": _new_uid(f"{block_tag}-{arm}-w-{i}"),
                "spawnFrame": sf,
                "sourceOriginal": source_orig,
                "hit": i < hits,
            }
            for i, sf in enumerate(spawns)
        ]
    elif scene == "finite":
        spawns = [20, 42, 64, 130]
        shots = [
            {
                "uuid": _new_uid(f"{block_tag}-{arm}-fin-{i}"),
                "spawnFrame": sf,
                "sourceOriginal": source_orig,
                "hit": True,
            }
            for i, sf in enumerate(spawns)
        ]
    elif scene == "peek":
        shots = [
            {
                "uuid": _new_uid(f"{block_tag}-{arm}-pk-0"),
                "spawnFrame": 20,
                "sourceOriginal": source_orig,
                "hit": True,
            }
        ]
    elif scene == "melee":
        shots = []
    else:
        joins = overrides.get("joins", 10 if scene == "stationary" else 13)
        hits = overrides.get("hits", 9 if scene == "stationary" else 12)
        shots = _make_shots(joins, hits, source_orig, start_frame=20, step=22)

    if "extra_shots" in overrides:
        shots.extend(overrides["extra_shots"])

    wear_key = "weaponWear" if (is_orig or scene == "melee") else "bowWear"
    stage1_melee = overrides.get("stage1_melee", 12)
    stage2_melee = overrides.get("stage2_melee", 3)

    frames = []
    for t in range(total_frames):
        joins_now = sum(1 for s in shots if s["spawnFrame"] <= t)
        row = {
            "frame": t,
            "age": t + 1,
            "livingTicks": 1,
            "owner": {"health": 19.0 if (scene == "melee" and t >= 59) else 20.0},
            "target": {"health": 912.0 if (scene == "melee" and t >= 100) else 1000.0},
            "targetVisible": True,
        }
        if not is_orig:
            row["fullRootAuthorized"] = True
            row["legacyTicks"] = 0
            row["actualProfile"] = "enhanced" if arm.startswith("N") else "faithful_tlm"
            row["actualFlightProfile"] = FLIGHT_PROFILES[arm]
        if scene == "withdraw":
            ext = 24 - joins_now if (7 <= t < 28) else 0
            inv = 0 if (7 <= t < 28) else (24 - joins_now)
            row["inventoryArrows"] = inv
            row["externallyHeldArrows"] = ext
            row[wear_key] = joins_now
        elif scene == "finite":
            ext = 1 if t < 108 else 0
            inv = (3 - joins_now) if t < 108 else (4 - joins_now)
            row["ammoBudget"] = 4
            row["inventoryArrows"] = inv
            row["externallyHeldArrows"] = ext
            row[wear_key] = joins_now
        elif scene == "melee":
            acc = 0 if t < 60 else (stage1_melee if t <= 210 else stage1_melee + stage2_melee)
            row["weaponWear"] = acc
            if not is_orig:
                row["acceptedMelee"] = acc
            row["competitor"] = {"health": 904.0 if t >= 90 else 1000.0}
            if t == (39 if is_orig else 59):
                row["actualOwnerHurtHealthDelta"] = 1.0
        elif scene == "peek":
            if t >= 40:
                row["targetVisible"] = (t % 25) < 12
            row[wear_key] = joins_now
        else:
            row[wear_key] = joins_now
        frames.append(row)

    damage_events = list(overrides.get("damage_events", []))
    if scene == "melee":
        def_frame = 39 if is_orig else 59
        damage_events.insert(
            0,
            {
                "frame": def_frame + (1 if is_orig else 0),
                "victim": owner_id,
                "amount": 1.0,
                "canceled": False,
                ("directSource" if is_orig else "source"): target_id,
            },
        )
        for z_idx in range(overrides.get("zombie_hurt_candidates", 3)):
            damage_events.append(
                {
                    "frame": 220 + z_idx * 15,
                    "victim": zombie_id,
                    "amount": 8.0,
                    "canceled": False,
                    ("directSource" if is_orig else "source"): maid_id,
                }
            )

    doc = {
        "schema": f"combat-{'boundary' if is_boundary else 'mechanism'}-{'original' if is_orig else 'port'}-v1",
        "arm": arm,
        "contractSha256": contract_sha,
        "requestedFlightProfile": FLIGHT_PROFILES[arm],
        "randomSourcesReset": False,
        "statisticalAdmission": False,
        "observationComplete": True,
        "collectedFramesComplete": True,
        "targetInitial": {"uuid": target_id, "health": 1000.0},
        "friendInitial": {"uuid": friend_id, "health": 20.0},
        "shots": shots,
        "frames": frames,
        "damageEvents": damage_events,
    }
    if is_orig:
        doc["mechanism"] = scene
        doc["maidUuid"] = maid_id
        doc["actualInputs"] = {"owner": {"uuid": owner_id}}
        doc["alliedPetHealthChange"] = 0.0
        doc["ownerHealthChange"] = 1.0 if scene == "melee" else 0.0
    else:
        doc["scene"] = scene
        doc["maidInitial"] = {"uuid": maid_id}
        doc["ownerInitial"] = {"uuid": owner_id, "health": 20.0}

    if scene == "withdraw":
        exit_act = "remove_target" if is_orig else "unregister_target"
        ev_rows = [
            {"frame": 7, "action": "remove_ammo"},
            {"frame": 28, "action": "restore_ammo"},
            {"frame": 54, "action": "remove_weapon"},
            {"frame": 75, "action": "restore_weapon"},
            {"frame": 96, "action": "arm_break"},
            {"frame": 118, "action": "replace_broken_bow"},
            {"frame": 140, "action": exit_act},
        ]
        doc["events" if is_orig else "inputEvents"] = ev_rows
        doc["naturalBreakExercised"] = True
        if is_orig:
            doc["withdrawalSequenceExercised"] = True
        else:
            doc["aliveWorldExitExercised"] = True
    elif scene == "finite":
        doc["firstNaturalZeroFrame"] = 64
        doc["restockStartFrame"] = 107
        doc["secondNaturalZeroFrame"] = 130
        doc["firstDryCompletedFrames"] = 40
        doc["secondDryCompletedFrames"] = 229
        doc["finiteSequenceComplete"] = True
    elif scene == "melee":
        doc["competitorInitial"] = {"uuid": comp_id, "health": 1000.0}
        doc["ownerDefenseInputFrame"] = 39 if is_orig else 59
        doc["competitionInputFrame"] = 57 if is_orig else 75
        doc["defenseAndCompetitionExercised"] = True
        doc["nativeEnemyFinal"] = {"uuid": zombie_id, "alive": True, "health": 4.0}
        if not is_orig:
            doc["acceptedMelee"] = stage1_melee + stage2_melee

    if "mutate_doc" in overrides:
        overrides["mutate_doc"](doc)
    return doc


def _write_synthetic_block(root_dir, block_id, contract, candidate_sha="cand_v1", turbo=1, scene_overrides=None):
    scene_overrides = scene_overrides or {}
    bdir = root_dir / block_id
    orig_m = bdir / "orig_m"
    orig_b = bdir / "orig_b"
    port_dir = bdir / "port"
    orig_m.mkdir(parents=True)
    orig_b.mkdir(parents=True)
    port_dir.mkdir(parents=True)

    m_sha = contract["boundContracts"]["mechanisms"]["sha256"]
    b_sha = contract["boundContracts"]["boundaries"]["sha256"]

    for scene, arms in MECHANISM_ARMS.items():
        for arm in arms:
            ovr = scene_overrides.get((scene, arm), {})
            doc = _build_capture(scene, arm, block_id, m_sha, False, ovr)
            target_path = (
                orig_m / f"combat-mechanism-original-{scene}-1.json"
                if arm == "O"
                else port_dir / f"combat-mechanism-{scene}-{arm}.json"
            )
            target_path.write_text(json.dumps(doc), encoding="utf-8")

    for scene, arms in BOUNDARY_ARMS.items():
        for arm in arms:
            ovr = scene_overrides.get((scene, arm), {})
            doc = _build_capture(scene, arm, block_id, b_sha, True, ovr)
            target_path = (
                orig_b / f"combat-boundary-original-{scene}-1.json"
                if arm == "O"
                else port_dir / f"combat-boundary-{scene}-{arm}.json"
            )
            target_path.write_text(json.dumps(doc), encoding="utf-8")

    (port_dir / "observed.json").write_text(
        json.dumps({
            "process_exit": 0,
            "supervisorTimeout": False,
            "status": {"status": "passed", "configuration": {"turbo": turbo}},
        }),
        encoding="utf-8",
    )
    mech_cmp = bdir / "comparison-mechanisms-unified-v1.json"
    bound_cmp = bdir / "comparison-boundaries-unified-v1.json"
    mech_cmp.write_text(
        json.dumps({
            "schema": "combat-mechanisms-comparison-unified-v1",
            "contract_sha256": m_sha,
            "observation_integrity_passed": True,
            "hqa_batch": {"status": "passed"},
            "candidate_sha256": candidate_sha,
            "pilot_sha256": "pilot_v1",
            "original_runtime_build_sha256": "orig_m_build_v1",
            "analysis_inputs_sha256": {"compare": "a1"},
        }),
        encoding="utf-8",
    )
    bound_cmp.write_text(
        json.dumps({
            "schema": "combat-boundaries-comparison-unified-v1",
            "contract_sha256": b_sha,
            "observation_integrity_passed": True,
            "hqa_batch": {"status": "passed"},
            "candidate_sha256": candidate_sha,
            "pilot_sha256": "pilot_v1",
            "original_runtime_build_sha256": "orig_b_build_v1",
            "analysis_inputs_sha256": {"compare": "b1"},
        }),
        encoding="utf-8",
    )
    return {
        "block_id": block_id,
        "status": "completed",
        "mechanisms_comparison": str(mech_cmp),
        "boundaries_comparison": str(bound_cmp),
        "original_mechanisms_captures": str(orig_m),
        "original_boundaries_captures": str(orig_b),
        "port_run": str(port_dir),
    }


def _strong_superiority_overrides():
    """在各自合法无条件支撑内构造显著占优配置，使 17 项指标在 B=6 全部跨过 K_NI >= 20。"""
    return {
        ("stationary", "O"): {"joins": 0, "hits": 0},
        ("stationary", "F"): {"joins": 10, "hits": 10},
        ("stationary", "F-R"): {"joins": 0, "hits": 0},
        ("stationary", "N"): {"joins": 10, "hits": 10},
        ("motion", "O"): {"joins": 0, "hits": 0},
        ("motion", "F"): {"joins": 13, "hits": 13},
        ("motion", "F-R"): {"joins": 0, "hits": 0},
        ("motion", "N"): {"joins": 13, "hits": 13},
        ("safety", "O"): {"joins": 4, "hits": 0, "restore_latency": 92},
        ("safety", "F"): {"joins": 7, "hits": 7, "restore_latency": 0},
        ("safety", "F-R"): {"joins": 4, "hits": 0, "restore_latency": 92},
        ("safety", "N"): {"joins": 7, "hits": 7, "restore_latency": 0},
        ("withdraw", "O"): {"joins": 1, "hits": 0, "spawns": [105]},
        ("withdraw", "F"): {"joins": 4, "hits": 4},
        ("withdraw", "F-R"): {"joins": 1, "hits": 0, "spawns": [105]},
        ("withdraw", "N"): {"joins": 4, "hits": 4},
        ("melee", "F"): {"stage1_melee": 1},
        ("melee", "N"): {"stage1_melee": 29},
    }


class CombatSharedStatisticsTests(unittest.TestCase):
    def setUp(self):
        self.contract_bytes = CONTRACT_PATH.read_bytes()
        self.contract = json.loads(self.contract_bytes)
        self.frozen_bytes = _frozen_contract_bytes()

    def test_contract_v3_supports_deltas_schedule_and_exclusions(self):
        self.assertEqual(self.contract["version"], 3)
        self.assertEqual(self.contract["contractStatus"], "PROPOSED_PENDING_REVIEW")
        self.assertFalse(self.contract["inferenceMethod"]["quantizationVarianceFloorUsed"])
        self.assertEqual(self.contract["sequentialSchedule"]["maxScheduledBlocks"], 120)
        looks = [(r["blocks"], r["canGrantPass"]) for r in self.contract["sequentialSchedule"]["scheduledLooks"]]
        self.assertEqual(looks, [(3, False), (6, True), (12, True), (30, True), (60, True), (120, True)])

        expected_supports = {
            "native_stationary_confirmed_hits": ([-10.0, 10.0], -0.5),
            "native_stationary_arrow_joins": ([-10.0, 10.0], -0.5),
            "source_stationary_confirmed_hits": ([-10.0, 10.0], -0.5),
            "source_stationary_arrow_joins": ([-10.0, 10.0], -0.5),
            "native_motion_confirmed_hits": ([-13.0, 13.0], -1.0),
            "native_motion_arrow_joins": ([-13.0, 13.0], -0.5),
            "source_motion_arrow_joins": ([-13.0, 13.0], -0.5),
            "source_motion_confirmed_hits": ([-13.0, 13.0], -0.75),
            "native_safety_confirmed_hits": ([-10.0, 10.0], -0.5),
            "native_safety_restore_latency": ([-114.0, 114.0], -3.0),
            "source_safety_confirmed_hits": ([-10.0, 10.0], -0.5),
            "source_safety_restore_latency": ([-114.0, 114.0], -3.0),
            "native_withdraw_arrow_joins": ([-4.0, 4.0], -0.5),
            "native_withdraw_confirmed_hits": ([-4.0, 4.0], -0.5),
            "source_withdraw_arrow_joins": ([-4.0, 4.0], -0.5),
            "source_withdraw_confirmed_hits": ([-4.0, 4.0], -0.5),
            "shared_melee_stage1_accepted_hits": ([-29.0, 29.0], -1.0),
        }
        metrics = {m["id"]: m for m in self.contract["pairedEfficacyMetrics"]}
        self.assertEqual(set(metrics.keys()), set(expected_supports.keys()))
        for mid, (sup, delta) in expected_supports.items():
            self.assertEqual(metrics[mid]["physicalSupport"], sup)
            self.assertEqual(metrics[mid]["delta"], delta)
            self.assertTrue(metrics[mid].get("supportProof"))

        toks = set(self.contract["calibrationExclusion"]["excludedDirectoryTokens"])
        self.assertIn("combat209-shared-evasion-1794350473736596400", toks)
        self.assertIn("batch196-hqa", toks)
        self.assertIn("batch204-combat-first-hqa", toks)
        self.assertGreaterEqual(len(self.contract["calibrationExclusion"]["excludedCaptureSha256"]), 149)

    def test_student_t_quantiles_and_wilson_ci(self):
        self.assertAlmostEqual(student_t_quantile(0.975, 2), 4.3026527299, places=7)
        self.assertAlmostEqual(student_t_quantile(0.975, 5), 2.5705818356, places=7)
        self.assertAlmostEqual(student_t_quantile(0.985, 11), 2.4906639333, places=7)
        self.assertAlmostEqual(student_t_quantile(0.990, 29), 2.4620213601, places=7)
        low, high = wilson_interval(6, 6)
        self.assertAlmostEqual(high, 1.0, places=9)
        self.assertGreater(low, 0.60)
        self.assertAlmostEqual(clopper_pearson_zero_event_ucb(6, 0.05), 1.0 - 0.05 ** (1.0 / 6.0), places=12)

    def test_predictability_non_anticipation_of_stakes(self):
        prefix = [0.0, -2.0, 1.0, -5.0, 3.0]
        seq_a = prefix + [10.0, 10.0, 10.0]
        seq_b = prefix + [-10.0, -10.0, -10.0]
        ni_a = single_point_betting_eprocess_ni(seq_a, -10.0, 10.0, -0.5)
        ni_b = single_point_betting_eprocess_ni(seq_b, -10.0, 10.0, -0.5)
        inf_a = single_point_betting_eprocess_inf(seq_a, -10.0, 10.0, -0.5)
        inf_b = single_point_betting_eprocess_inf(seq_b, -10.0, 10.0, -0.5)
        # 第 6 步赌注只能依赖前 5 步历史，改变第 6..8 步观测不得改变前 6 个赌注
        self.assertEqual(ni_a["stakes"][:6], ni_b["stakes"][:6])
        self.assertEqual(inf_a["stakes"][:6], inf_b["stakes"][:6])
        self.assertEqual(ni_a["wealth_path"][:5], ni_b["wealth_path"][:5])

    def test_nan_inf_bool_and_out_of_support_rejected(self):
        for bad in (float("nan"), float("inf"), float("-inf"), True, False, -10.01, 10.01, "0"):
            with self.assertRaises(ValueError):
                single_point_betting_eprocess_ni([0.0, bad], -10.0, 10.0, -0.5)
            with self.assertRaises(ValueError):
                single_point_betting_eprocess_inf([0.0, bad], -10.0, 10.0, -0.5)
            with self.assertRaises(ValueError):
                monotone_prpl_eb_anytime_lcb([0.0, bad], -10.0, 10.0, 20.0, 0.05)
            with self.assertRaises(ValueError):
                time_uniform_spending_hoeffding_lcb([0.0, bad], -10.0, 10.0, 0.05)

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            # 构造 stage1_melee = 35 > 29 越过物理支撑 [-29, 29]，应被归入完整性故障而非崩溃或通过
            bad_blk = _write_synthetic_block(
                root,
                "C001",
                self.contract,
                scene_overrides={("melee", "N"): {"stage1_melee": 45}, ("melee", "F"): {"stage1_melee": 10}},
            )
            rep = evaluate_manifest({"blocks": [bad_blk]}, self.frozen_bytes)
            self.assertEqual(rep["verdict"], "BLOCKED_BY_COLLECTION_OR_INTEGRITY_FAULT")
            self.assertFalse(rep["statistical_admission"])
            self.assertEqual(rep["block_accounting"]["collection_or_integrity_fault_blocks"], 1)

    def test_monotone_prpl_eb_lcb_zero_holes_and_marginal_vs_simultaneous_labels(self):
        rng = random.Random(20261111)
        for _ in range(40):
            n = rng.randint(2, 40)
            seq = [rng.choice([-10.0, -9.5, -4.0, 0.0, 3.5, 10.0]) for _ in range(n)]
            mus = [-10.0 + 20.0 * k / 500.0 for k in range(1, 501)]
            wealths = [prpl_eb_monotone_log_wealth(seq, -10.0, 10.0, mu, 0.05) for mu in mus]
            for left_w, right_w in zip(wealths, wealths[1:]):
                self.assertLessEqual(right_w, left_w + 1e-12)

            step_lcb = monotone_prpl_eb_anytime_lcb(
                seq, -10.0, 10.0, 20.0, 0.05, running_intersection=False
            )
            if step_lcb > -10.0:
                self.assertGreaterEqual(
                    prpl_eb_monotone_log_wealth(seq, -10.0, 10.0, step_lcb, 0.05),
                    math.log(20.0) - 1e-9,
                )
            if step_lcb < 10.0 - 1e-6:
                self.assertLess(
                    prpl_eb_monotone_log_wealth(seq, -10.0, 10.0, step_lcb + 1e-5, 0.05),
                    math.log(20.0),
                )

        ties_30 = [0.0] * 30
        marg_30 = monotone_prpl_eb_anytime_lcb(ties_30, -10.0, 10.0, 20.0, 0.05)
        sim_30 = monotone_prpl_eb_anytime_lcb(ties_30, -10.0, 10.0, 340.0, 0.05 / 17.0)
        self.assertLess(sim_30, marg_30)

        # 运行交集 LCB 随区组数 b 单调不减
        running_lcbs = [
            monotone_prpl_eb_anytime_lcb([0.0] * b, -10.0, 10.0, 20.0, 0.05, running_intersection=True)
            for b in range(1, 25)
        ]
        for a, b_val in zip(running_lcbs, running_lcbs[1:]):
            self.assertGreaterEqual(b_val, a - 1e-12)

    def test_calibration_directory_token_rejected_unless_preview(self):
        for token in (
            "combat209-shared-evasion-1794328310477507400",
            "combat209-shared-evasion-1794350473736596400",
        ):
            with tempfile.TemporaryDirectory() as tmp:
                cal_dir = Path(tmp) / token
                blk = _write_synthetic_block(cal_dir, "C001", self.contract)
                manifest = {"blocks": [blk]}
                with self.assertRaisesRegex(ValueError, "calibration sample excluded"):
                    evaluate_manifest(manifest, self.contract_bytes, preview_mode=False)
                preview = evaluate_manifest(manifest, self.contract_bytes, preview_mode=True)
                self.assertEqual(preview["verdict"], "CALIBRATION_PREVIEW_ONLY_NOT_ADMITTED")
                self.assertFalse(preview["statistical_admission"])
                self.assertTrue(preview["calibration_sample_touched"])

    def test_zero_variance_ties_honest_inconclusive_at_small_b_and_b120_threshold(self):
        # 校验全部 17 项指标在零差值平局下：B=60 仍有指标未达 20，B=120 全部跨过 K_NI >= 20
        w60_all = []
        w120_all = []
        for m in self.contract["pairedEfficacyMetrics"]:
            low, high = m["physicalSupport"]
            delta = m["delta"]
            w60_all.append(single_point_betting_eprocess_ni([0.0] * 60, low, high, delta)["wealth"])
            w120_all.append(single_point_betting_eprocess_ni([0.0] * 120, low, high, delta)["wealth"])
        self.assertLess(min(w60_all), 20.0)
        self.assertGreaterEqual(min(w120_all), 20.0)
        self.assertEqual(oracle_zero_diff_min_blocks(-114.0, -3.0, 0.05), 113)

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            blocks = [_write_synthetic_block(root, f"C{i:03d}", self.contract) for i in range(1, 7)]
            report = evaluate_manifest({"blocks": blocks}, self.frozen_bytes, preview_mode=False)
            self.assertEqual(report["verdict"], "INCONCLUSIVE_EXTEND")
            self.assertFalse(report["statistical_admission"])
            self.assertFalse(report["algorithmic_degradation_or_violation_inferred"])

            motion_metric = next(
                m for m in report["paired_efficacy_metrics"] if m["id"] == "source_motion_confirmed_hits"
            )
            self.assertEqual(motion_metric["sample_sd"], 0.0)
            self.assertFalse(motion_metric["quantization_variance_floor_used"])
            self.assertFalse(motion_metric["zero_variance_sign_test_used"])
            self.assertFalse(motion_metric["descriptive_diagnostics"]["governs_formal_verdict"])
            self.assertEqual(
                motion_metric["descriptive_diagnostics"]["classic_unfloored_student_t"]["status"],
                "degenerate_zero_variance",
            )
            lcbs = motion_metric["anytime_valid_lower_confidence_bounds"]
            self.assertLess(lcbs["simultaneous_95_familywise_anytime_lcb"], lcbs["marginal_95_anytime_lcb"])
            self.assertIn(
                "not_simultaneous", lcbs["marginal_95_coverage_scope"]
            )
            self.assertIn(
                "simultaneous_across_all_17_metrics", lcbs["simultaneous_95_coverage_scope"]
            )

    def test_melee_stage1_uses_same_per_frame_counter_and_detects_counter_corruption(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            blocks = [
                _write_synthetic_block(
                    root,
                    f"C{i:03d}",
                    self.contract,
                    scene_overrides={
                        ("melee", "F"): {"stage1_melee": 11, "stage2_melee": 3, "zombie_hurt_candidates": 0},
                        ("melee", "N"): {"stage1_melee": 13, "stage2_melee": 3, "zombie_hurt_candidates": 9},
                    },
                )
                for i in range(1, 4)
            ]
            report = evaluate_manifest({"blocks": blocks}, self.contract_bytes)
            melee_metric = next(
                m for m in report["paired_efficacy_metrics"] if m["id"] == "shared_melee_stage1_accepted_hits"
            )
            self.assertEqual(melee_metric["mean_difference"], 2.0)

        with tempfile.TemporaryDirectory() as tmp2:
            root2 = Path(tmp2)

            def corrupt_melee(doc):
                doc["frames"][150]["acceptedMelee"] = 99

            bad_blk = _write_synthetic_block(
                root2,
                "C001",
                self.contract,
                scene_overrides={("melee", "N"): {"mutate_doc": corrupt_melee}},
            )
            bad_report = evaluate_manifest({"blocks": [bad_blk]}, self.frozen_bytes)
            self.assertEqual(bad_report["verdict"], "BLOCKED_BY_COLLECTION_OR_INTEGRITY_FAULT")
            self.assertFalse(bad_report["algorithmic_degradation_or_violation_inferred"])
            self.assertEqual(bad_report["block_accounting"]["attempted_blocks"], 1)
            self.assertEqual(bad_report["block_accounting"]["collection_or_integrity_fault_blocks"], 1)

    def test_fault_taxonomy_separates_collection_faults_from_behavioral_violations(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            valid_five = [_write_synthetic_block(root, f"C{i:03d}", self.contract) for i in range(1, 6)]
            fault_six = {"block_id": "C006", "status": "collection_fault", "fault_reason": "JVM startup channel error"}
            rep_fault = evaluate_manifest({"blocks": valid_five + [fault_six]}, self.frozen_bytes)
            self.assertEqual(rep_fault["verdict"], "BLOCKED_BY_COLLECTION_OR_INTEGRITY_FAULT")
            self.assertFalse(rep_fault["algorithmic_degradation_or_violation_inferred"])
            self.assertFalse(rep_fault["statistical_admission"])
            self.assertEqual(rep_fault["block_accounting"]["attempted_blocks"], 6)
            self.assertEqual(rep_fault["block_accounting"]["completed_valid_blocks"], 5)

        with tempfile.TemporaryDirectory() as tmp2:
            root2 = Path(tmp2)
            wall_shot = [{"uuid": _new_uid("wall-arrow"), "spawnFrame": 80, "sourceOriginal": False, "hit": False}]
            viol_blk = _write_synthetic_block(
                root2, "C001", self.contract, scene_overrides={("safety", "N"): {"extra_shots": wall_shot}}
            )
            fault_blk = {"block_id": "C002", "status": "collection_fault", "fault_reason": "timeout"}
            rep_both = evaluate_manifest({"blocks": [viol_blk, fault_blk]}, self.frozen_bytes)
            self.assertEqual(rep_both["verdict"], "FAILED_BEHAVIORAL_HARD_GATE")
            self.assertTrue(rep_both["algorithmic_degradation_or_violation_inferred"])
            self.assertEqual(rep_both["block_accounting"]["attempted_blocks"], 2)

    def test_cross_block_independence_turbo_and_artifact_drift_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            b1 = _write_synthetic_block(root, "C001", self.contract)
            b2_reused_orig = dict(_write_synthetic_block(root, "C002", self.contract))
            b2_reused_orig["original_mechanisms_captures"] = b1["original_mechanisms_captures"]
            with self.assertRaisesRegex(ValueError, "reuse"):
                evaluate_manifest({"blocks": [b1, b2_reused_orig]}, self.frozen_bytes)

            b3_drift = _write_synthetic_block(root, "C003", self.contract, candidate_sha="cand_v2")
            with self.assertRaisesRegex(ValueError, "drift"):
                evaluate_manifest({"blocks": [b1, b3_drift]}, self.frozen_bytes)

            b4_turbo = _write_synthetic_block(root, "C004", self.contract, turbo=20)
            with self.assertRaisesRegex(ValueError, "turbo"):
                evaluate_manifest({"blocks": [b4_turbo]}, self.frozen_bytes)

    def test_sequential_schedule_pass_futility_cap_and_non_skippable_incomplete_blocks(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            sup_ovr = _strong_superiority_overrides()
            sched = build_campaign_schedule("camp-v3-unit", max_blocks=7)
            blocks = []
            for row in sched["blocks"][:6]:
                blk = _write_synthetic_block(root, row["block_id"], self.contract, scene_overrides=sup_ovr)
                blk["block_index"] = row["block_index"]
                blk["scheduled_seed"] = row["scheduled_seed"]
                blk["execution_order"] = row["execution_order"]
                blocks.append(blk)

            # 未冻结合同即使 6 区组全部满足 K_NI >= 20，仍只能输出预览态，不授予统计准入
            unfrozen_rep = evaluate_manifest(
                {"protocol_mode": "formal_campaign", "campaign_id": "camp-v3-unit", "blocks": blocks},
                self.contract_bytes,
            )
            self.assertEqual(unfrozen_rep["verdict"], "PROPOSED_CONTRACT_UNFROZEN_PREVIEW")
            self.assertEqual(unfrozen_rep["sequential_decision_if_contract_frozen"], "PASSED_NONINFERIORITY")
            self.assertFalse(unfrozen_rep["statistical_admission"])

            # B=3 为故障检出查看点（canGrantPass=False），不允许直接授予 PASSED_NONINFERIORITY
            b3_rep = evaluate_manifest(
                {"protocol_mode": "formal_campaign", "campaign_id": "camp-v3-unit", "blocks": blocks[:3]},
                self.frozen_bytes,
            )
            self.assertEqual(b3_rep["verdict"], "INCONCLUSIVE_EXTEND")
            self.assertFalse(b3_rep["statistical_admission"])

            # B=5 不在预声明查看点上，输出 EXTEND_TO_NEXT_SCHEDULED_LOOK
            b5_rep = evaluate_manifest(
                {"protocol_mode": "formal_campaign", "campaign_id": "camp-v3-unit", "blocks": blocks[:5]},
                self.frozen_bytes,
            )
            self.assertEqual(b5_rep["verdict"], "EXTEND_TO_NEXT_SCHEDULED_LOOK")
            self.assertFalse(b5_rep["statistical_admission"])

            # B=6 正式查看点上 17 项全部 K_NI >= 20，授予 PASSED_NONINFERIORITY
            b6_rep = evaluate_manifest(
                {"protocol_mode": "formal_campaign", "campaign_id": "camp-v3-unit", "blocks": blocks},
                self.frozen_bytes,
            )
            self.assertEqual(b6_rep["verdict"], "PASSED_NONINFERIORITY")
            self.assertTrue(b6_rep["statistical_admission"])
            self.assertTrue(b6_rep["protocol_verification"]["formal_campaign_protocol_enforced"])

            # 跳过不完整区组序号（如删掉第 2 块）必须立即报错拒绝
            skipped = [blocks[0]] + blocks[2:6]
            with self.assertRaisesRegex(ValueError, "non-contiguous"):
                evaluate_manifest(
                    {"protocol_mode": "formal_campaign", "campaign_id": "camp-v3-unit", "blocks": skipped},
                    self.frozen_bytes,
                )

            # 篡改预声明种子或交替顺序必须立即报错拒绝
            tampered_order = [dict(b) for b in blocks[:3]]
            tampered_order[1]["execution_order"] = "original_then_port"
            with self.assertRaisesRegex(ValueError, "execution_order"):
                evaluate_manifest(
                    {"protocol_mode": "formal_campaign", "campaign_id": "camp-v3-unit", "blocks": tampered_order},
                    self.frozen_bytes,
                )
            tampered_seed = [dict(b) for b in blocks[:3]]
            tampered_seed[2]["scheduled_seed"] = derive_scheduled_block_seed("other-campaign", 3)
            with self.assertRaisesRegex(ValueError, "scheduled_seed mismatch"):
                evaluate_manifest(
                    {"protocol_mode": "formal_campaign", "campaign_id": "camp-v3-unit", "blocks": tampered_seed},
                    self.frozen_bytes,
                )

            # 即使已有 6 个可通过区组，只要含 1 个未完成/故障区组试图后补到第 7 块，仍永久阻断通过
            row7 = sched["blocks"][6]
            compensated_attempt = blocks[:5] + [
                {
                    "block_id": blocks[5]["block_id"],
                    "block_index": 6,
                    "scheduled_seed": blocks[5]["scheduled_seed"],
                    "execution_order": expected_pair_execution_order(6),
                    "status": "incomplete",
                    "fault_reason": "interrupted block 6",
                },
                dict(
                    _write_synthetic_block(root, "B007", self.contract, scene_overrides=sup_ovr),
                    block_index=7,
                    scheduled_seed=row7["scheduled_seed"],
                    execution_order=row7["execution_order"],
                ),
            ]
            rep_comp = evaluate_manifest(
                {"protocol_mode": "formal_campaign", "campaign_id": "camp-v3-unit", "blocks": compensated_attempt},
                self.frozen_bytes,
            )
            self.assertEqual(rep_comp["verdict"], "BLOCKED_BY_COLLECTION_OR_INTEGRITY_FAULT")
            self.assertFalse(rep_comp["statistical_admission"])
            self.assertEqual(rep_comp["block_accounting"]["completed_valid_blocks"], 6)
            self.assertEqual(rep_comp["block_accounting"]["attempted_blocks"], 7)

            # 达到计划上限仍未跨过 K_NI >= 20 时诚实返回 INCONCLUSIVE_AT_MAX_BLOCKS
            cap6_contract = _frozen_contract_bytes(
                lambda d: d["sequentialSchedule"].update({
                    "maxScheduledBlocks": 6,
                    "scheduledLooks": [
                        {"blocks": 3, "canGrantPass": False, "role": "fault_check"},
                        {"blocks": 6, "canGrantPass": True, "role": "terminal_cap"},
                    ],
                })
            )
            tie_blocks = [_write_synthetic_block(root, f"T{i:03d}", self.contract) for i in range(1, 7)]
            cap_rep = evaluate_manifest({"blocks": tie_blocks}, cap6_contract)
            self.assertEqual(cap_rep["verdict"], "INCONCLUSIVE_AT_MAX_BLOCKS")
            self.assertFalse(cap_rep["statistical_admission"])

        # 显著退化时对偶并集超鞅 K_INF >= 340 在非查看点（如 B=10）也能提早停止判 FAILED_NONINFERIORITY
        with tempfile.TemporaryDirectory() as tmp_fail:
            root_f = Path(tmp_fail)
            reg_blocks = [
                _write_synthetic_block(
                    root_f,
                    f"R{i:03d}",
                    self.contract,
                    scene_overrides={("motion", "N"): {"hits": 0}, ("motion", "F-R"): {"hits": 13}},
                )
                for i in range(1, 11)
            ]
            fail_rep = evaluate_manifest({"blocks": reg_blocks}, self.frozen_bytes)
            self.assertEqual(fail_rep["verdict"], "FAILED_NONINFERIORITY")
            self.assertTrue(fail_rep["algorithmic_degradation_or_violation_inferred"])
            self.assertFalse(fail_rep["statistical_admission"])


if __name__ == "__main__":
    unittest.main()
