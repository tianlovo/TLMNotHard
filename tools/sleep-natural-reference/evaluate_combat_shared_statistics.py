"""共有战斗语义多区组统计评估器：校验鲜JVM区组独立性、分层提取同口径指标并执行有界超鞅序贯非劣分析。"""

import argparse
import hashlib
import json
import math
import random
import struct
from pathlib import Path

from check import require, sha

MECHANISM_ARMS = {
    "melee": ("O", "F", "N"),
    "motion": ("O", "F", "F-R", "N-static", "N-last", "N"),
    "safety": ("O", "F", "F-R", "N"),
    "withdraw": ("O", "F", "F-R", "N"),
}
BOUNDARY_ARMS = {
    "stationary": ("O", "F", "F-R", "N-static", "N-last", "N"),
    "peek": ("O", "F", "F-R", "N-static", "N-last", "N"),
    "finite": ("O", "F", "N"),
}
FLIGHT_PROFILES = {
    "O": "SOURCE_ORIGINAL",
    "F": "SOURCE_ORIGINAL",
    "F-R": "NATIVE_STATIC_COMPENSATION",
    "N-static": "NATIVE_STATIC_COMPENSATION",
    "N-last": "NATIVE_LAST_SAMPLE_COMPENSATION",
    "N": "NATIVE_MOTION_COMPENSATION",
}
SCENE_FRAMES = {
    "melee": 360,
    "motion": 300,
    "safety": 220,
    "withdraw": 360,
    "stationary": 220,
    "peek": 260,
    "finite": 360,
}
ALLOWED_EXECUTION_ORDERS = ("original_then_port", "port_then_original")


class CollectionIntegrityFault(Exception):
    """启动、采集或同侧计数器完整性故障，计入已尝试区组但不归因于算法退化。"""


def _require_finite_number(value, context, exc_cls=ValueError):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise exc_cls(f"non-numeric value in {context}: {value!r}")
    fval = float(value)
    if not math.isfinite(fval):
        raise exc_cls(f"non-finite value in {context}: {value!r}")
    return fval


def derive_scheduled_block_seed(campaign_id, block_index):
    """按预声明活动标识与连续区组序号确定性派生 64 位有符号种子。"""
    if not isinstance(campaign_id, str) or not campaign_id.strip():
        raise ValueError("campaign_id must be a non-empty string")
    if isinstance(block_index, bool) or not isinstance(block_index, int) or block_index < 1:
        raise ValueError(f"block_index must be a positive integer >= 1, got {block_index!r}")
    payload = f"COMBAT_SHARED_STATISTICS_V1:{campaign_id}:block:{block_index}".encode("utf-8")
    digest = hashlib.sha256(payload).digest()
    return struct.unpack(">q", digest[:8])[0]


def expected_pair_execution_order(block_index):
    """奇数区组先原版后移植，偶数区组先移植后原版，抵消宿主机顺序漂移。"""
    if isinstance(block_index, bool) or not isinstance(block_index, int) or block_index < 1:
        raise ValueError(f"block_index must be a positive integer >= 1, got {block_index!r}")
    return "original_then_port" if (block_index % 2 == 1) else "port_then_original"


def build_campaign_schedule(campaign_id, max_blocks=120):
    """生成正式多区组抽样前固定的种子与原版/移植执行顺序表。"""
    if isinstance(max_blocks, bool) or not isinstance(max_blocks, int) or max_blocks < 1:
        raise ValueError(f"max_blocks must be >= 1, got {max_blocks!r}")
    rows = []
    for idx in range(1, max_blocks + 1):
        rows.append({
            "block_index": idx,
            "block_id": f"B{idx:03d}",
            "scheduled_seed": derive_scheduled_block_seed(campaign_id, idx),
            "execution_order": expected_pair_execution_order(idx),
        })
    return {
        "schema": "combat-shared-statistics-campaign-schedule-v1",
        "contract": "COMBAT_SHARED_STATISTICS_V1",
        "campaign_id": campaign_id,
        "max_blocks": max_blocks,
        "blocks": rows,
    }


def _betacf(a, b, x):
    qab = a + b
    qap = a + 1.0
    qam = a - 1.0
    c = 1.0
    d = 1.0 - qab * x / qap
    if abs(d) < 1e-30:
        d = 1e-30
    d = 1.0 / d
    h = d
    for m in range(1, 201):
        m2 = 2 * m
        aa = m * (b - m) * x / ((qam + m2) * (a + m2))
        d = 1.0 + aa * d
        if abs(d) < 1e-30:
            d = 1e-30
        c = 1.0 + aa / c
        if abs(c) < 1e-30:
            c = 1e-30
        d = 1.0 / d
        h *= d * c
        aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2))
        d = 1.0 + aa * d
        if abs(d) < 1e-30:
            d = 1e-30
        c = 1.0 + aa / c
        if abs(c) < 1e-30:
            c = 1e-30
        d = 1.0 / d
        delta = d * c
        h *= delta
        if abs(delta - 1.0) < 1e-15:
            break
    return h


def reg_incomplete_beta(a, b, x):
    require(0.0 <= x <= 1.0 and a > 0.0 and b > 0.0, "invalid incomplete beta arguments")
    if x == 0.0:
        return 0.0
    if x == 1.0:
        return 1.0
    log_bt = math.lgamma(a + b) - math.lgamma(a) - math.lgamma(b) + a * math.log(x) + b * math.log(1.0 - x)
    bt = math.exp(log_bt)
    if x < (a + 1.0) / (a + b + 2.0):
        return bt * _betacf(a, b, x) / a
    return 1.0 - bt * _betacf(b, a, 1.0 - x) / b


def student_t_cdf(value, df):
    require(df > 0, "degrees of freedom must be positive")
    if not math.isfinite(value):
        return 1.0 if value > 0 else 0.0
    if value == 0.0:
        return 0.5
    x = df / (df + value * value)
    ib = reg_incomplete_beta(0.5 * df, 0.5, x)
    return 1.0 - 0.5 * ib if value > 0 else 0.5 * ib


def student_t_quantile(prob, df):
    require(0.0 < prob < 1.0 and df > 0, "quantile probability or df out of range")
    if prob == 0.5:
        return 0.0
    low, high = -1.0, 1.0
    while student_t_cdf(low, df) > prob:
        low *= 2.0
    while student_t_cdf(high, df) < prob:
        high *= 2.0
    for _ in range(90):
        mid = 0.5 * (low + high)
        if student_t_cdf(mid, df) < prob:
            low = mid
        else:
            high = mid
    return 0.5 * (low + high)


def wilson_interval(successes, total, z=1.959963984540054):
    if total <= 0:
        return [0.0, 1.0]
    require(0 <= successes <= total, "invalid binomial counts")
    p = successes / total
    z2 = z * z
    denom = 1.0 + z2 / total
    center = (p + z2 / (2.0 * total)) / denom
    half = z * math.sqrt((p * (1.0 - p) + z2 / (4.0 * total)) / total) / denom
    return [max(0.0, center - half), min(1.0, center + half)]


def clopper_pearson_zero_event_ucb(blocks, alpha=0.05):
    if blocks <= 0:
        return 1.0
    return 1.0 - (alpha ** (1.0 / blocks))


def sample_mean_sd(values):
    require(len(values) >= 1, "empty sample")
    clean = [_require_finite_number(v, "sample_mean_sd") for v in values]
    n = len(clean)
    mean = sum(clean) / n
    if n < 2:
        return mean, None
    var = sum((x - mean) ** 2 for x in clean) / (n - 1)
    return mean, math.sqrt(var)


def _validate_bounded_diffs(diffs, low, high, c_max=0.95):
    low_f = _require_finite_number(low, "physicalSupport[0]")
    high_f = _require_finite_number(high, "physicalSupport[1]")
    c_f = _require_finite_number(c_max, "c_max")
    require(low_f < high_f, f"invalid support [{low_f}, {high_f}]")
    require(0.0 < c_f < 1.0, f"c_max must lie in (0, 1), got {c_f}")
    require(isinstance(diffs, (list, tuple)) and len(diffs) >= 1, "diffs must be non-empty")
    clean = []
    for idx, raw in enumerate(diffs):
        val = _require_finite_number(raw, f"diffs[{idx}]")
        if not (low_f <= val <= high_f):
            raise ValueError(f"diffs[{idx}]={val} outside verified physical support [{low_f}, {high_f}]")
        clean.append(val)
    return clean, low_f, high_f, c_f


def single_point_betting_eprocess_ni(diffs, low, high, delta, c_max=0.95):
    """单点 δ_m 的可测 aGRAPA 非负超鞅，检验 H0: E[D_i | F_{i-1}] <= delta。"""
    clean, low_f, high_f, c_f = _validate_bounded_diffs(diffs, low, high, c_max)
    delta_f = _require_finite_number(delta, "delta")
    require(low_f < delta_f < high_f, f"delta={delta_f} must satisfy low < delta < high")

    lam_cap = c_f / (delta_f - low_f)
    # 伪先验仅固定首步可测赌注初值，不计入财富乘积
    prior_g = -delta_f
    sum_g = prior_g
    sum_sq = prior_g * prior_g
    log_wealth = 0.0
    stakes = []
    wealth_path = []

    for d in clean:
        lam = max(0.0, min(lam_cap, sum_g / sum_sq)) if sum_sq > 0.0 else 0.0
        stakes.append(lam)
        g = d - delta_f
        inc = lam * g
        if inc < -c_f - 1e-12:
            raise AssertionError(f"supermartingale positivity violated: 1 + lam*g = {1.0 + inc}")
        log_wealth += math.log1p(inc)
        wealth_path.append(math.exp(min(log_wealth, 700.0)))
        sum_g += g
        sum_sq += g * g

    return {
        "wealth": math.exp(min(log_wealth, 700.0)),
        "log_wealth": log_wealth,
        "stakes": stakes,
        "wealth_path": wealth_path,
    }


def single_point_betting_eprocess_inf(diffs, low, high, delta, c_max=0.95):
    """对偶单点 δ_m 的可测 aGRAPA 非负超鞅，配合并集界 17/alpha 检出早期显著退化。"""
    clean, low_f, high_f, c_f = _validate_bounded_diffs(diffs, low, high, c_max)
    delta_f = _require_finite_number(delta, "delta")
    require(low_f < delta_f < high_f, f"delta={delta_f} must satisfy low < delta < high")

    lam_cap = c_f / (high_f - delta_f)
    prior_excess = min(1.0, 0.5 * (high_f - delta_f))
    sum_g = prior_excess
    sum_sq = prior_excess * prior_excess
    log_wealth = 0.0
    stakes = []
    wealth_path = []

    for d in clean:
        lam = max(0.0, min(lam_cap, sum_g / sum_sq)) if sum_sq > 0.0 else 0.0
        stakes.append(lam)
        g = delta_f - d
        inc = lam * g
        if inc < -c_f - 1e-12:
            raise AssertionError(f"dual supermartingale positivity violated: 1 + lam*g = {1.0 + inc}")
        log_wealth += math.log1p(inc)
        wealth_path.append(math.exp(min(log_wealth, 700.0)))
        sum_g += g
        sum_sq += g * g

    return {
        "wealth": math.exp(min(log_wealth, 700.0)),
        "log_wealth": log_wealth,
        "stakes": stakes,
        "wealth_path": wealth_path,
    }


def _prpl_eb_raw_stakes(clean_diffs, low_f, high_f, alpha_stake):
    """计算与候选均值 μ 完全无关、严格关于 F_{i-1} 可测的经验 Bernstein 原始赌注序列。"""
    width = high_f - low_f
    mu_hat_sum = 0.5 * (low_f + high_f)
    var_hat_sum = (0.25 * width) ** 2
    log_inv_alpha = math.log(1.0 / alpha_stake)
    raw_stakes = []
    for i, d in enumerate(clean_diffs, 1):
        sigma2_prev = var_hat_sum / i
        lam_raw = math.sqrt(2.0 * log_inv_alpha / (i * sigma2_prev))
        raw_stakes.append(lam_raw)
        mu_curr = (mu_hat_sum + d) / (i + 1)
        mu_hat_sum += d
        var_hat_sum += (d - mu_curr) ** 2
    return raw_stakes


def prpl_eb_monotone_log_wealth(diffs, low, high, mu, alpha_stake, c_max=0.95):
    """在候选均值 μ 处计算全局对 μ 单调非增的截断 PrPl-EB 对数财富，无非区间接受洞。"""
    clean, low_f, high_f, c_f = _validate_bounded_diffs(diffs, low, high, c_max)
    mu_f = _require_finite_number(mu, "mu")
    alpha_f = _require_finite_number(alpha_stake, "alpha_stake")
    require(low_f < mu_f <= high_f, f"mu={mu_f} must lie in (low, high]")
    require(0.0 < alpha_f < 1.0, f"alpha_stake={alpha_f} must lie in (0, 1)")

    raw_stakes = _prpl_eb_raw_stakes(clean, low_f, high_f, alpha_f)
    cap = c_f / (mu_f - low_f)
    lw = 0.0
    for d, lam_raw in zip(clean, raw_stakes):
        lam = min(cap, lam_raw)
        lw += math.log1p(lam * (d - mu_f))
    return lw


def monotone_prpl_eb_anytime_lcb(diffs, low, high, threshold, alpha_stake, c_max=0.95, running_intersection=True):
    """对全局单调非增的 PrPl-EB 超鞅族做纯二分保守求逆，输出随时有效置信下界。"""
    clean, low_f, high_f, c_f = _validate_bounded_diffs(diffs, low, high, c_max)
    thr_f = _require_finite_number(threshold, "threshold")
    alpha_f = _require_finite_number(alpha_stake, "alpha_stake")
    require(thr_f > 1.0 and 0.0 < alpha_f < 1.0, "invalid LCB threshold or alpha_stake")

    log_thr = math.log(thr_f)
    raw_stakes = _prpl_eb_raw_stakes(clean, low_f, high_f, alpha_f)
    eps = 1e-12 * (high_f - low_f)

    def prefix_log_wealth(prefix_len, mu_val):
        cap = c_f / (mu_val - low_f)
        acc = 0.0
        for idx in range(prefix_len):
            lam = cap if raw_stakes[idx] > cap else raw_stakes[idx]
            acc += math.log1p(lam * (clean[idx] - mu_val))
        return acc

    start_step = 1 if running_intersection else len(clean)
    best_lcb = low_f
    for step in range(start_step, len(clean) + 1):
        probe = best_lcb + eps
        if probe >= high_f or prefix_log_wealth(step, probe) < log_thr:
            continue
        lo, hi = probe, high_f
        for _ in range(64):
            mid = 0.5 * (lo + hi)
            if prefix_log_wealth(step, mid) >= log_thr:
                lo = mid
            else:
                hi = mid
        if lo > best_lcb:
            best_lcb = lo
    return best_lcb


def time_uniform_spending_hoeffding_lcb(diffs, low, high, total_alpha):
    """基于级数支出 alpha_t = alpha / (t*(t+1)) 的闭式随时有效 Hoeffding 下界复核。"""
    clean, low_f, high_f, _ = _validate_bounded_diffs(diffs, low, high, 0.95)
    alpha_f = _require_finite_number(total_alpha, "total_alpha")
    require(0.0 < alpha_f < 1.0, f"total_alpha={alpha_f} out of range")
    width = high_f - low_f
    running_sum = 0.0
    best = low_f
    for t, val in enumerate(clean, 1):
        running_sum += val
        mean_t = running_sum / t
        alpha_t = alpha_f / (t * (t + 1.0))
        rad_t = width * math.sqrt(math.log(1.0 / alpha_t) / (2.0 * t))
        best = max(best, mean_t - rad_t)
    return best


def oracle_zero_diff_min_blocks(low, delta, alpha=0.05):
    """零差值平局序列在任意有效分布无关均值检验下的信息论最小区组数。"""
    low_f = _require_finite_number(low, "low")
    delta_f = _require_finite_number(delta, "delta")
    alpha_f = _require_finite_number(alpha, "alpha")
    require(low_f < delta_f < 0.0 and 0.0 < alpha_f < 1.0, "invalid oracle block floor arguments")
    return int(math.ceil(math.log(1.0 / alpha_f) / math.log((-low_f) / (delta_f - low_f))))


def bounded_fixed_sample_reference(diffs, support_min, support_max, alpha=0.05):
    """固定样本量描述性有界参考值，不含可选停时校正，不参与正式准入裁决。"""
    clean, low_f, high_f, _ = _validate_bounded_diffs(diffs, support_min, support_max, 0.95)
    n = len(clean)
    width = high_f - low_f
    mean, sd = sample_mean_sd(clean)
    hoeffding = max(low_f, mean - width * math.sqrt(math.log(1.0 / alpha) / (2.0 * n)))
    if n >= 2:
        eb_rad = sd * math.sqrt(2.0 * math.log(2.0 / alpha) / n) + (
            7.0 * width * math.log(2.0 / alpha)
        ) / (3.0 * (n - 1))
        empirical_bernstein = max(low_f, mean - eb_rad)
    else:
        empirical_bernstein = low_f
    min_obs = min(clean)
    survive_prob = alpha ** (1.0 / n)
    adversarial_two_point = survive_prob * min_obs + (1.0 - survive_prob) * low_f
    return {
        "governs_formal_verdict": False,
        "not_anytime_valid_fixed_n_only": True,
        "physical_support": [low_f, high_f],
        "alpha": alpha,
        "fixed_n_hoeffding_lcb": hoeffding,
        "fixed_n_empirical_bernstein_lcb": empirical_bernstein,
        "adversarial_two_point_tail_floor": adversarial_two_point,
    }


def paired_block_bootstrap(diffs, resamples, seed, one_sided_alpha):
    n = len(diffs)
    rng = random.Random(seed)
    means = []
    for _ in range(resamples):
        total = 0.0
        for _ in range(n):
            total += diffs[rng.randrange(n)]
        means.append(total / n)
    means.sort()

    def pct(p):
        idx = max(0, min(resamples - 1, int(math.floor(p * resamples))))
        return means[idx]

    return {
        "governs_formal_verdict": False,
        "resamples": resamples,
        "seed": seed,
        "two_sided_95_percentile": [pct(0.025), pct(0.975)],
        "one_sided_alpha": one_sided_alpha,
        "one_sided_percentile_lcb": pct(one_sided_alpha),
        "degeneracy_note": "collapses to point mass when all block differences tie; descriptive only",
    }


def _check_calibration_exclusion(path_obj, raw_bytes, excluded_tokens, excluded_shas, preview_mode):
    path_text = str(path_obj).replace("\\", "/")
    matched_tokens = [tok for tok in excluded_tokens if tok in path_text]
    digest = sha(raw_bytes) if raw_bytes is not None else None
    sha_excluded = digest in excluded_shas if digest is not None else False
    if not preview_mode and (matched_tokens or sha_excluded):
        reason = f"token={matched_tokens}" if matched_tokens else f"sha256={digest}"
        raise ValueError(f"calibration sample excluded from formal statistics ({reason})")
    return digest, bool(matched_tokens or sha_excluded)


def _load_json_checked(path_obj, excluded_tokens, excluded_shas, preview_mode, check_sha=False):
    if not path_obj.is_file():
        raise CollectionIntegrityFault(f"missing required file: {path_obj.name}")
    raw = path_obj.read_bytes()
    digest, hit_cal = _check_calibration_exclusion(
        path_obj, raw if check_sha else None, excluded_tokens, excluded_shas, preview_mode
    )
    if check_sha and digest is None:
        digest = sha(raw)
    try:
        data = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise CollectionIntegrityFault(f"corrupted JSON in {path_obj.name}: {exc}") from exc
    return data, sha(raw), hit_cal


def _find_original_capture(directory, prefix, scene, excluded_tokens, excluded_shas, preview_mode):
    if not directory.is_dir():
        raise CollectionIntegrityFault(f"missing original captures directory: {directory.name}")
    matches = sorted(directory.glob(f"{prefix}-original-{scene}*.json"))
    if len(matches) != 1:
        raise CollectionIntegrityFault(f"expected 1 original capture for {scene}, found {len(matches)}")
    return _load_json_checked(matches[0], excluded_tokens, excluded_shas, preview_mode, check_sha=True)


def _verify_common_capture_integrity(data, scene, arm, contract_sha, is_boundary, is_orig):
    expected_schema = (
        f"combat-{'boundary' if is_boundary else 'mechanism'}-{'original' if is_orig else 'port'}-v1"
    )
    if data.get("schema") != expected_schema or data.get("arm") != arm:
        raise CollectionIntegrityFault(f"schema/arm mismatch in {scene}/{arm}")
    if data.get("contractSha256") != contract_sha:
        raise ValueError(f"bound contract SHA256 mismatch in {scene}/{arm}")
    if data.get("requestedFlightProfile") != FLIGHT_PROFILES[arm]:
        raise ValueError(f"flight profile mismatch in {scene}/{arm}")
    if data.get("randomSourcesReset") is not False or data.get("statisticalAdmission") is not False:
        raise ValueError(f"unapproved random reset or pre-admission flag in {scene}/{arm}")
    if data.get("observationComplete") is not True or data.get("collectedFramesComplete") is not True:
        raise CollectionIntegrityFault(f"incomplete capture in {scene}/{arm}")
    if data.get("error") or data.get("cleanupError"):
        raise CollectionIntegrityFault(f"capture recorded runtime/cleanup error in {scene}/{arm}")
    frames = data.get("frames", [])
    if len(frames) != SCENE_FRAMES[scene]:
        raise CollectionIntegrityFault(f"truncated frames in {scene}/{arm}: {len(frames)} != {SCENE_FRAMES[scene]}")
    expected_source_orig = arm in ("O", "F")
    for idx, row in enumerate(frames):
        if row.get("frame") != idx or row.get("age") != idx + 1 or row.get("livingTicks") != 1:
            raise CollectionIntegrityFault(f"broken tick/age sequence in {scene}/{arm} at frame {idx}")
        if not is_orig:
            expected_prof = "enhanced" if arm.startswith("N") else "faithful_tlm"
            if (
                row.get("fullRootAuthorized") is not True
                or row.get("legacyTicks") != 0
                or row.get("actualProfile") != expected_prof
                or row.get("actualFlightProfile") != FLIGHT_PROFILES[arm]
            ):
                raise CollectionIntegrityFault(f"port root/profile/flight authorization failed in {scene}/{arm}")
    for s_idx, shot in enumerate(data.get("shots", [])):
        if shot.get("sourceOriginal") is not expected_source_orig:
            raise ValueError(f"mixed source/native arrow physics in {scene}/{arm}")
        sf = shot.get("spawnFrame")
        if isinstance(sf, bool) or not isinstance(sf, int) or not (0 <= sf < SCENE_FRAMES[scene]):
            raise CollectionIntegrityFault(f"invalid shot spawnFrame in {scene}/{arm} shot[{s_idx}]: {sf!r}")


def _extract_ally_damage_violations(data, scene, arm, is_orig):
    owner_uuid = data["actualInputs"]["owner"]["uuid"] if is_orig else data["ownerInitial"]["uuid"]
    friend_uuid = data["friendInitial"]["uuid"]
    target_uuid = data["targetInitial"]["uuid"]
    defense_frame = data.get("ownerDefenseInputFrame", -1) if scene == "melee" else -1
    allowed_scripted_used = False
    scripted_amount = 0.0
    bad_events = 0
    bad_hp = 0.0
    for event in data.get("damageEvents", []):
        amt = _require_finite_number(
            event.get("amount", 0.0), f"{scene}/{arm} damageEvents.amount", CollectionIntegrityFault
        )
        if event.get("canceled") is True or amt <= 0.0:
            continue
        victim = event.get("victim")
        if victim not in (owner_uuid, friend_uuid):
            continue
        src = event.get("directSource" if is_orig else "source", event.get("directSource", ""))
        frame = event.get("frame", -999)
        if (
            scene == "melee"
            and not allowed_scripted_used
            and victim == owner_uuid
            and src == target_uuid
            and defense_frame >= 0
            and frame in (defense_frame, defense_frame + 1)
        ):
            allowed_scripted_used = True
            scripted_amount = amt
            continue
        bad_events += 1
        bad_hp += amt
    if is_orig:
        pet_loss = max(
            0.0,
            _require_finite_number(
                data.get("alliedPetHealthChange", 0.0),
                f"{scene}/{arm} alliedPetHealthChange",
                CollectionIntegrityFault,
            ),
        )
        if pet_loss > 1e-6:
            bad_hp = max(bad_hp, pet_loss)
            bad_events = max(bad_events, 1)
        owner_loss = _require_finite_number(
            data.get("ownerHealthChange", 0.0), f"{scene}/{arm} ownerHealthChange", CollectionIntegrityFault
        )
        allowed_loss = (
            _require_finite_number(
                data["frames"][defense_frame].get("actualOwnerHurtHealthDelta", scripted_amount),
                f"{scene}/{arm} actualOwnerHurtHealthDelta",
                CollectionIntegrityFault,
            )
            if (scene == "melee" and defense_frame >= 0)
            else 0.0
        )
        if owner_loss > allowed_loss + 1e-5:
            bad_hp += owner_loss - allowed_loss
            bad_events = max(bad_events, 1)
    else:
        owner_end = _require_finite_number(
            data["frames"][-1]["owner"]["health"], f"{scene}/{arm} owner_end_health", CollectionIntegrityFault
        )
        owner_start = _require_finite_number(
            data["ownerInitial"]["health"], f"{scene}/{arm} owner_start_health", CollectionIntegrityFault
        )
        allowed_loss = (
            _require_finite_number(
                data["frames"][defense_frame].get("actualOwnerHurtHealthDelta", scripted_amount),
                f"{scene}/{arm} actualOwnerHurtHealthDelta",
                CollectionIntegrityFault,
            )
            if (scene == "melee" and defense_frame >= 0)
            else 0.0
        )
        if owner_start - owner_end > allowed_loss + 1e-5:
            bad_hp += (owner_start - owner_end) - allowed_loss
            bad_events = max(bad_events, 1)
    return bad_events, bad_hp


def _extract_scene_arm(data, scene, arm, is_orig):
    shots = data.get("shots", [])
    frames = data["frames"]
    bad_events, bad_hp = _extract_ally_damage_violations(data, scene, arm, is_orig)
    violations = []
    if bad_events > 0 or bad_hp > 0.0:
        violations.append(f"{scene}/{arm}: friendly_or_owner_damage(events={bad_events}, hp={bad_hp:.3f})")
    out = {
        "arm": arm,
        "arrow_joins": float(len(shots)),
        "confirmed_hits": float(sum(1 for s in shots if s.get("hit") is True)),
        "friendly_or_owner_damage_events": bad_events,
        "friendly_or_owner_damage_hp": bad_hp,
        "occluded_wall_arrow_joins": 0,
        "conservation_or_window_mismatches": 0,
    }
    if scene == "safety":
        wall_joins = [s["spawnFrame"] for s in shots if 60 <= s["spawnFrame"] < 105]
        out["occluded_wall_arrow_joins"] = len(wall_joins)
        if wall_joins:
            violations.append(f"safety/{arm}: wall_arrow_joins={wall_joins}")
        crossed = {s.get("friendlyCrossing") for s in shots if "friendlyCrossing" in s}
        if crossed != {"same_owner_pet", "native_owner"}:
            out["conservation_or_window_mismatches"] += 1
            violations.append(f"safety/{arm}: missing_friendly_crossings={sorted(crossed)}")
        for s in shots:
            ally_hp = _require_finite_number(
                s.get("allyHealthAfterCrossing", 20.0),
                f"safety/{arm} allyHealthAfterCrossing",
                CollectionIntegrityFault,
            )
            if s.get("allyBurningAfterCrossing") is True or ally_hp < 20.0:
                out["friendly_or_owner_damage_events"] += 1
                violations.append(f"safety/{arm}: crossing_ally_harmed_at_spawn_{s['spawnFrame']}")
        post_wall = [s["spawnFrame"] for s in shots if s["spawnFrame"] >= 105]
        if not post_wall:
            out["post_wall_restore_latency_ticks"] = 114.0
            out["post_wall_censored"] = True
            out["conservation_or_window_mismatches"] += 1
            violations.append(f"safety/{arm}: zero_post_wall_restoration_shots")
        else:
            out["post_wall_restore_latency_ticks"] = float(min(post_wall) - 105)
            out["post_wall_censored"] = False
    elif scene == "withdraw":
        ev_list = data.get("events" if is_orig else "inputEvents", [])
        exit_name = "remove_target" if is_orig else "unregister_target"
        expected_actions = [
            "remove_ammo", "restore_ammo", "remove_weapon", "restore_weapon",
            "arm_break", "replace_broken_bow", exit_name,
        ]
        actions = [e.get("action") for e in ev_list]
        seq_ok = (
            actions == expected_actions
            and data.get("naturalBreakExercised") is True
            and data.get("withdrawalSequenceExercised", data.get("aliveWorldExitExercised")) is True
        )
        mismatches = 0
        if seq_ok:
            ev_frames = [int(e["frame"]) for e in ev_list]
            if any(ev_frames[i] >= ev_frames[i + 1] for i in range(len(ev_frames) - 1)):
                seq_ok = False
            t0, t1, t2, t3, t4, t5, t6 = ev_frames
            no_ammo_shots = sum(1 for s in shots if t0 <= s["spawnFrame"] < t1)
            no_weap_shots = sum(1 for s in shots if t2 <= s["spawnFrame"] < t3)
            break_shots = sum(1 for s in shots if t4 <= s["spawnFrame"] < t5)
            post_exit_shots = sum(1 for s in shots if s["spawnFrame"] >= t6)
            extra_break = max(0, break_shots - 1)
            mismatches += no_ammo_shots + no_weap_shots + extra_break + post_exit_shots
            if break_shots != 1:
                seq_ok = False
        else:
            mismatches += 1
        for row in frames:
            t = row["frame"]
            joins_now = sum(1 for s in shots if s["spawnFrame"] <= t)
            if row.get("inventoryArrows", 0) + row.get("externallyHeldArrows", 0) + joins_now != 24:
                mismatches += 1
                break
        out["conservation_or_window_mismatches"] = mismatches
        out["sequence_passed"] = bool(seq_ok and mismatches == 0)
        if not out["sequence_passed"]:
            violations.append(f"withdraw/{arm}: sequence_or_window_violation(mismatches={mismatches})")
    elif scene == "finite":
        mismatches = 0
        wear_key = "weaponWear" if is_orig else "bowWear"
        for row in frames:
            t = row["frame"]
            joins_now = sum(1 for s in shots if s["spawnFrame"] <= t)
            if (
                row.get("ammoBudget") != 4
                or row.get("inventoryArrows", 0) + row.get("externallyHeldArrows", 0) + joins_now != 4
                or row.get(wear_key) != joins_now
            ):
                mismatches += 1
                break
        first_zero = int(data.get("firstNaturalZeroFrame", -1))
        restock_at = int(data.get("restockStartFrame", -1))
        second_zero = int(data.get("secondNaturalZeroFrame", -1))
        dry1_shots = sum(
            1 for s in shots if first_zero >= 0 and restock_at >= 0 and first_zero < s["spawnFrame"] < restock_at
        )
        dry2_shots = sum(1 for s in shots if second_zero >= 0 and s["spawnFrame"] > second_zero)
        mismatches += dry1_shots + dry2_shots
        seq_ok = (
            data.get("finiteSequenceComplete") is True
            and len(shots) == 4
            and int(data.get("firstDryCompletedFrames", 0)) >= 40
            and int(data.get("secondDryCompletedFrames", 0)) >= 40
            and mismatches == 0
        )
        out["conservation_or_window_mismatches"] = mismatches
        out["sequence_passed"] = seq_ok
        if not seq_ok:
            violations.append(f"finite/{arm}: depletion_or_conservation_violation(mismatches={mismatches})")
    elif scene == "melee":
        def_at = int(data.get("ownerDefenseInputFrame", -1))
        comp_at = int(data.get("competitionInputFrame", -1))
        wear_key = "weaponWear"
        idle1_hold = (
            frames[200][wear_key] == frames[210][wear_key]
            and frames[200]["target"]["health"] == frames[210]["target"]["health"]
            and frames[200]["competitor"]["health"] == frames[210]["competitor"]["health"]
        )
        idle2_hold = frames[340][wear_key] == frames[359][wear_key]
        seq_ok = (
            data.get("defenseAndCompetitionExercised") is True
            and 0 <= def_at < comp_at < 200
            and idle1_hold
            and idle2_hold
        )
        out["owner_defense_frame"] = def_at
        out["competition_frame"] = comp_at
        out["stage1_weapon_wear"] = int(frames[200]["weaponWear"])
        out["stage2_weapon_wear"] = int(frames[340]["weaponWear"] - frames[210]["weaponWear"])
        if is_orig:
            out["stage1_accepted_melee"] = "UNKNOWN_original_native_counter"
            out["stage2_accepted_melee"] = "UNKNOWN_original_native_counter"
        else:
            prev = 0
            for idx, row in enumerate(frames):
                acc = row.get("acceptedMelee")
                wear = row.get("weaponWear")
                if isinstance(acc, bool) or not isinstance(acc, int) or acc != wear or acc < prev:
                    raise CollectionIntegrityFault(
                        f"melee/{arm}: per-frame acceptedMelee/weaponWear counter inconsistency at frame {idx}"
                    )
                prev = acc
            if def_at >= 0 and frames[min(def_at, len(frames) - 1)]["acceptedMelee"] != 0:
                raise CollectionIntegrityFault(f"melee/{arm}: nonzero acceptedMelee before owner defense trigger")
            if frames[-1]["acceptedMelee"] != data.get("acceptedMelee"):
                raise CollectionIntegrityFault(f"melee/{arm}: terminal acceptedMelee differs from frame[359]")
            if frames[200]["acceptedMelee"] != frames[210]["acceptedMelee"] or (
                frames[340]["acceptedMelee"] != frames[359]["acceptedMelee"]
            ):
                seq_ok = False
                out["conservation_or_window_mismatches"] += 1
            out["stage1_accepted_melee"] = float(frames[200]["acceptedMelee"])
            out["stage2_accepted_melee"] = float(frames[340]["acceptedMelee"] - frames[210]["acceptedMelee"])
        zombie_final = data.get("nativeEnemyFinal", {})
        out["native_enemy_final_alive"] = zombie_final.get("alive")
        out["native_enemy_final_health"] = zombie_final.get("health")
        out["sequence_passed"] = seq_ok
        if not seq_ok:
            violations.append(f"melee/{arm}: defense_competition_or_idle_hold_failed")
    elif scene == "peek":
        clear_shots = [s for s in shots if 0 <= s["spawnFrame"] < 40]
        out["clear_0_to_39_joins"] = len(clear_shots)
        out["clear_0_to_39_hits"] = sum(1 for s in clear_shots if s.get("hit") is True)
        max_vis = {}
        for start, stop, name in ((40, 140, "pillar"), (140, 260, "doorway")):
            cur = best = 0
            for row in frames[start:stop]:
                if row.get("targetVisible"):
                    cur += 1
                    best = max(best, cur)
                else:
                    cur = 0
            max_vis[name] = best
        out["pillar_max_continuous_visible_ticks"] = max_vis["pillar"]
        out["doorway_max_continuous_visible_ticks"] = max_vis["doorway"]
        out["obstructed_recovery_status"] = (
            "NOT_CHALLENGED" if max(max_vis.values()) < 20 else "QUALIFIED_WINDOW_PRESENT"
        )
    return out, violations


def _verify_turbo_and_comparisons(block_spec, contract, excluded_tokens, excluded_shas, preview_mode):
    bound_m = contract["boundContracts"]["mechanisms"]
    bound_b = contract["boundContracts"]["boundaries"]
    mech_cmp_path = Path(block_spec["mechanisms_comparison"])
    bound_cmp_path = Path(block_spec["boundaries_comparison"])
    mech_cmp, _, cal1 = _load_json_checked(mech_cmp_path, excluded_tokens, excluded_shas, preview_mode)
    bound_cmp, _, cal2 = _load_json_checked(bound_cmp_path, excluded_tokens, excluded_shas, preview_mode)
    if mech_cmp.get("schema") != bound_m["comparisonSchema"] or mech_cmp.get("contract_sha256") != bound_m["sha256"]:
        raise ValueError("mechanisms comparison schema or contract SHA256 mismatch")
    if bound_cmp.get("schema") != bound_b["comparisonSchema"] or bound_cmp.get("contract_sha256") != bound_b["sha256"]:
        raise ValueError("boundaries comparison schema or contract SHA256 mismatch")
    if (
        mech_cmp.get("observation_integrity_passed") is not True
        or bound_cmp.get("observation_integrity_passed") is not True
    ):
        raise CollectionIntegrityFault("single-block comparison observation_integrity_passed is not True")
    for label, cmp_doc in (("mechanisms", mech_cmp), ("boundaries", bound_cmp)):
        hqa = cmp_doc.get("hqa_batch", {})
        if hqa.get("status") != "passed":
            raise CollectionIntegrityFault(f"{label} hqa_batch status is {hqa.get('status')}")
    if mech_cmp["candidate_sha256"] != bound_cmp["candidate_sha256"] or (
        mech_cmp["pilot_sha256"] != bound_cmp["pilot_sha256"]
    ):
        raise ValueError("within-block mechanisms vs boundaries candidate/pilot artifact mismatch")
    port_m_dir = Path(block_spec.get("port_mechanisms_run") or block_spec["port_run"])
    port_b_dir = Path(block_spec.get("port_boundaries_run") or block_spec["port_run"])
    for pdir in {port_m_dir, port_b_dir}:
        obs_path = pdir / "observed.json"
        hqa_path = pdir / "horizonqa-result.json"
        if obs_path.is_file():
            obs, _, _ = _load_json_checked(obs_path, excluded_tokens, excluded_shas, preview_mode)
            if obs.get("supervisorTimeout") is True or obs.get("process_exit", 0) != 0:
                raise CollectionIntegrityFault(f"port JVM abnormal exit or timeout in {pdir.name}")
            turbo = obs.get("status", {}).get("configuration", {}).get("turbo")
            if turbo != contract["sampleUnit"]["requiredTurbo"]:
                raise ValueError(f"port run turbo={turbo} violates requiredTurbo=1")
        elif hqa_path.is_file():
            hqa_doc, _, _ = _load_json_checked(hqa_path, excluded_tokens, excluded_shas, preview_mode)
            turbo = hqa_doc.get("configuration", {}).get("turbo")
            if turbo != contract["sampleUnit"]["requiredTurbo"]:
                raise ValueError(f"port run turbo={turbo} violates requiredTurbo=1")
        else:
            raise CollectionIntegrityFault(f"missing observed.json/horizonqa-result.json in {pdir.name}")
    return mech_cmp, bound_cmp, port_m_dir, port_b_dir, bool(cal1 or cal2)


def _verify_block_metric_supports(block_id, scene_data, contract):
    for spec in contract["pairedEfficacyMetrics"]:
        mid = spec["id"]
        scene = spec["mechanism"]
        test_arm = spec["testArm"]
        base_arm = spec["baselineArm"]
        field = spec["field"]
        direction = spec["direction"]
        sup_min, sup_max = float(spec["physicalSupport"][0]), float(spec["physicalSupport"][1])
        t_val = _require_finite_number(
            scene_data[scene][test_arm][field], f"{mid}/{block_id}/{test_arm}", CollectionIntegrityFault
        )
        b_val = _require_finite_number(
            scene_data[scene][base_arm][field], f"{mid}/{block_id}/{base_arm}", CollectionIntegrityFault
        )
        diff = (t_val - b_val) if direction == "higher_better" else (b_val - t_val)
        if not (sup_min <= diff <= sup_max):
            raise CollectionIntegrityFault(
                f"{mid} block {block_id} difference {diff} outside physical support [{sup_min}, {sup_max}]"
            )


def evaluate_completed_block(block_spec, contract, excluded_tokens, excluded_shas, preview_mode):
    block_id = str(block_spec["block_id"])
    mech_cmp, bound_cmp, port_m_dir, port_b_dir, hit_cal = _verify_turbo_and_comparisons(
        block_spec, contract, excluded_tokens, excluded_shas, preview_mode
    )
    orig_m_dir = Path(block_spec["original_mechanisms_captures"])
    orig_b_dir = Path(block_spec["original_boundaries_captures"])
    bound_m_sha = contract["boundContracts"]["mechanisms"]["sha256"]
    bound_b_sha = contract["boundContracts"]["boundaries"]["sha256"]

    capture_shas = set()
    actor_uuids = set()
    owner_uuids = set()
    scene_data = {}
    violations = []

    all_groups = (
        (MECHANISM_ARMS, False, orig_m_dir, port_m_dir, "combat-mechanism", bound_m_sha),
        (BOUNDARY_ARMS, True, orig_b_dir, port_b_dir, "combat-boundary", bound_b_sha),
    )
    for arm_map, is_boundary, orig_dir, port_dir, prefix, c_sha in all_groups:
        for scene, arms in arm_map.items():
            scene_data[scene] = {}
            for arm in arms:
                is_orig = arm == "O"
                if is_orig:
                    raw_doc, digest, cal = _find_original_capture(
                        orig_dir, prefix, scene, excluded_tokens, excluded_shas, preview_mode
                    )
                else:
                    cap_path = port_dir / f"{prefix}-{scene}-{arm}.json"
                    raw_doc, digest, cal = _load_json_checked(
                        cap_path, excluded_tokens, excluded_shas, preview_mode, check_sha=True
                    )
                hit_cal = hit_cal or cal
                _verify_common_capture_integrity(raw_doc, scene, arm, c_sha, is_boundary, is_orig)
                maid_id = raw_doc["maidUuid"] if is_orig else raw_doc["maidInitial"]["uuid"]
                owner_id = raw_doc["actualInputs"]["owner"]["uuid"] if is_orig else raw_doc["ownerInitial"]["uuid"]
                if digest in capture_shas or maid_id in actor_uuids or owner_id in owner_uuids:
                    raise ValueError(f"within-block capture/actor/owner reuse in {block_id} {scene}/{arm}")
                capture_shas.add(digest)
                actor_uuids.add(maid_id)
                owner_uuids.add(owner_id)
                arm_metrics, arm_violations = _extract_scene_arm(raw_doc, scene, arm, is_orig)
                scene_data[scene][arm] = arm_metrics
                violations.extend(arm_violations)

    f_def = scene_data["melee"]["F"]["owner_defense_frame"]
    n_def = scene_data["melee"]["N"]["owner_defense_frame"]
    if f_def != n_def:
        raise CollectionIntegrityFault(
            f"melee owner_defense_frame mismatch within block {block_id}: F={f_def} vs N={n_def}"
        )
    require(len(capture_shas) == 32 and len(actor_uuids) == 32 and len(owner_uuids) == 32, "incomplete 32-arm block")
    _verify_block_metric_supports(block_id, scene_data, contract)

    artifacts = {
        "candidate_sha256": mech_cmp["candidate_sha256"],
        "pilot_sha256": mech_cmp["pilot_sha256"],
        "mechanisms_original_build_sha256": mech_cmp["original_runtime_build_sha256"],
        "boundaries_original_build_sha256": bound_cmp["original_runtime_build_sha256"],
        "mechanisms_analysis_inputs_sha256": mech_cmp["analysis_inputs_sha256"],
        "boundaries_analysis_inputs_sha256": bound_cmp["analysis_inputs_sha256"],
    }
    return {
        "block_id": block_id,
        "status": "completed_valid" if not violations else "completed_behavioral_violation",
        "calibration_sample_touched": hit_cal,
        "artifacts": artifacts,
        "capture_shas": capture_shas,
        "actor_uuids": actor_uuids,
        "owner_uuids": owner_uuids,
        "scenes": scene_data,
        "behavioral_violations": violations,
    }


def _evaluate_paired_metric(spec, metric_index, num_metrics, valid_blocks, contract):
    mid = spec["id"]
    scene = spec["mechanism"]
    test_arm = spec["testArm"]
    base_arm = spec["baselineArm"]
    field = spec["field"]
    direction = spec["direction"]
    delta = _require_finite_number(spec["delta"], f"{mid}.delta")
    sup_min = _require_finite_number(spec["physicalSupport"][0], f"{mid}.physicalSupport[0]")
    sup_max = _require_finite_number(spec["physicalSupport"][1], f"{mid}.physicalSupport[1]")

    inf_cfg = contract["inferenceMethod"]
    schedule = contract["sequentialSchedule"]
    c_max = float(inf_cfg.get("stakeTruncationCapCMax", 0.95))
    total_alpha = float(schedule.get("totalOneSidedAlpha", 0.05))
    pass_thr = float(inf_cfg.get("passThresholdPerMetricIut", 1.0 / total_alpha))
    fail_thr = float(inf_cfg.get("futilityThresholdUnionBound", num_metrics / total_alpha))
    min_futility_b = int(inf_cfg.get("minBlocksBeforeFutilityStop", 3))

    diffs = []
    test_vals = []
    base_vals = []
    for blk in valid_blocks:
        t_val = _require_finite_number(
            blk["scenes"][scene][test_arm][field], f"{mid}/{blk['block_id']}/{test_arm}", CollectionIntegrityFault
        )
        b_val = _require_finite_number(
            blk["scenes"][scene][base_arm][field], f"{mid}/{blk['block_id']}/{base_arm}", CollectionIntegrityFault
        )
        diff = (t_val - b_val) if direction == "higher_better" else (b_val - t_val)
        if not (sup_min <= diff <= sup_max):
            raise CollectionIntegrityFault(
                f"{mid} block {blk['block_id']} difference {diff} outside physical support [{sup_min}, {sup_max}]"
            )
        test_vals.append(t_val)
        base_vals.append(b_val)
        diffs.append(diff)

    n = len(diffs)
    mean_d, sd_d = sample_mean_sd(diffs)
    mean_test, _ = sample_mean_sd(test_vals)
    mean_base, _ = sample_mean_sd(base_vals)

    ni_res = single_point_betting_eprocess_ni(diffs, sup_min, sup_max, delta, c_max=c_max)
    inf_res = single_point_betting_eprocess_inf(diffs, sup_min, sup_max, delta, c_max=c_max)

    # 与单点 aGRAPA 准入完全分离的全局单调 PrPl-EB 随时有效置信下界，分别给出单指标边际 95% 与 17 指标联合 95%
    marg_lcb = monotone_prpl_eb_anytime_lcb(
        diffs, sup_min, sup_max, threshold=pass_thr, alpha_stake=total_alpha, c_max=c_max
    )
    sim_lcb = monotone_prpl_eb_anytime_lcb(
        diffs, sup_min, sup_max, threshold=fail_thr, alpha_stake=total_alpha / num_metrics, c_max=c_max
    )
    hoeff_marg_lcb = time_uniform_spending_hoeffding_lcb(diffs, sup_min, sup_max, total_alpha)
    hoeff_sim_lcb = time_uniform_spending_hoeffding_lcb(diffs, sup_min, sup_max, total_alpha / num_metrics)

    if n >= min_futility_b and inf_res["wealth"] >= fail_thr:
        m_decision = "FAIL_FUTILITY_UNION_BOUND"
    elif n >= min_futility_b and ni_res["wealth"] >= pass_thr:
        m_decision = "PASS_SINGLE_POINT_EPROCESS"
    else:
        m_decision = "INCONCLUSIVE_EXTEND"

    if n < 2:
        t_diag = {
            "governs_formal_verdict": False,
            "status": "single_block_undefined",
            "lcb_95_one_sided": None,
            "two_sided_95_interval": None,
        }
    elif sd_d == 0.0:
        t_diag = {
            "governs_formal_verdict": False,
            "status": "degenerate_zero_variance",
            "lcb_95_one_sided": None,
            "two_sided_95_interval": None,
        }
    else:
        se = sd_d / math.sqrt(n)
        t95_one = student_t_quantile(1.0 - total_alpha, n - 1)
        t95_two = student_t_quantile(0.975, n - 1)
        t_diag = {
            "governs_formal_verdict": False,
            "status": "parametric_reference_only_not_valid_for_bounded_discrete_alpha",
            "lcb_95_one_sided": mean_d - t95_one * se,
            "two_sided_95_interval": [mean_d - t95_two * se, mean_d + t95_two * se],
        }

    boot = paired_block_bootstrap(
        diffs,
        int(schedule["blockBootstrapResamples"]),
        int(schedule["blockBootstrapSeed"]) + metric_index * 1009,
        total_alpha,
    )
    fixed_ref = bounded_fixed_sample_reference(diffs, sup_min, sup_max, total_alpha)

    return {
        "id": mid,
        "mechanism": scene,
        "stratum": spec["stratum"],
        "pairing": f"{test_arm} - {base_arm}" if direction == "higher_better" else f"{base_arm} - {test_arm}",
        "field": field,
        "unit": spec["unit"],
        "direction": direction,
        "physical_support": [sup_min, sup_max],
        "support_proof": spec.get("supportProof", ""),
        "delta": delta,
        "oracle_zero_diff_min_blocks": oracle_zero_diff_min_blocks(sup_min, delta, total_alpha),
        "quality_meaning": spec["qualityMeaning"],
        "blocks": n,
        "test_arm_mean": mean_test,
        "baseline_arm_mean": mean_base,
        "block_differences_oriented": diffs,
        "mean_difference": mean_d,
        "sample_sd": sd_d,
        "quantization_variance_floor_used": False,
        "zero_variance_sign_test_used": False,
        "single_point_admission_eprocess": {
            "method": "predictable_aGRAPA_single_point_delta",
            "c_max": c_max,
            "ni_wealth_at_delta": ni_res["wealth"],
            "ni_log_wealth_at_delta": ni_res["log_wealth"],
            "pass_threshold_iut": pass_thr,
            "dual_inf_wealth_at_delta": inf_res["wealth"],
            "dual_inf_log_wealth_at_delta": inf_res["log_wealth"],
            "futility_threshold_union_bound": fail_thr,
            "predictable_stakes_ni": ni_res["stakes"],
        },
        "anytime_valid_lower_confidence_bounds": {
            "algorithm_separation_note": (
                "Inverted from candidate-mean-independent monotone PrPl-EB supermartingale family; "
                "separate from single-point aGRAPA delta admission"
            ),
            "marginal_95_anytime_lcb": marg_lcb,
            "marginal_95_coverage_scope": "per_metric_marginal_95_only_not_simultaneous_across_17_metrics",
            "simultaneous_95_familywise_anytime_lcb": sim_lcb,
            "simultaneous_95_coverage_scope": "union_bounded_joint_95_simultaneous_across_all_17_metrics",
            "spending_hoeffding_marginal_95_anytime_lcb": hoeff_marg_lcb,
            "spending_hoeffding_simultaneous_95_familywise_anytime_lcb": hoeff_sim_lcb,
        },
        "metric_eprocess_decision": m_decision,
        "descriptive_diagnostics": {
            "governs_formal_verdict": False,
            "classic_unfloored_student_t": t_diag,
            "paired_block_bootstrap": boot,
            "fixed_sample_non_sequential_reference": fixed_ref,
        },
    }


def _build_aim_ablations_and_exclusions(valid_blocks):
    ablations = []
    for scene in ("stationary", "motion", "peek"):
        for base_arm in ("N-static", "N-last"):
            for field in ("confirmed_hits", "arrow_joins"):
                diffs = [
                    blk["scenes"][scene]["N"][field] - blk["scenes"][scene][base_arm][field] for blk in valid_blocks
                ]
                mean_d, sd_d = sample_mean_sd(diffs)
                ablations.append({
                    "mechanism": scene,
                    "pairing": f"N - {base_arm}",
                    "field": field,
                    "mean_difference": mean_d,
                    "sample_sd": sd_d,
                    "block_differences": diffs,
                })
    melee_desc = {
        "original_O_stage1_weapon_wear": [blk["scenes"]["melee"]["O"]["stage1_weapon_wear"] for blk in valid_blocks],
        "original_O_stage2_weapon_wear": [blk["scenes"]["melee"]["O"]["stage2_weapon_wear"] for blk in valid_blocks],
        "original_O_owner_defense_frames": [blk["scenes"]["melee"]["O"]["owner_defense_frame"] for blk in valid_blocks],
        "port_F_owner_defense_frames": [blk["scenes"]["melee"]["F"]["owner_defense_frame"] for blk in valid_blocks],
        "port_F_stage2_accepted_melee": [blk["scenes"]["melee"]["F"]["stage2_accepted_melee"] for blk in valid_blocks],
        "port_N_stage2_accepted_melee": [blk["scenes"]["melee"]["N"]["stage2_accepted_melee"] for blk in valid_blocks],
        "port_F_zombie_final_alive": [blk["scenes"]["melee"]["F"]["native_enemy_final_alive"] for blk in valid_blocks],
        "port_N_zombie_final_alive": [blk["scenes"]["melee"]["N"]["native_enemy_final_alive"] for blk in valid_blocks],
    }
    peek_desc = {}
    for arm in BOUNDARY_ARMS["peek"]:
        peek_desc[arm] = {
            "clear_0_to_39_hits": [blk["scenes"]["peek"][arm]["clear_0_to_39_hits"] for blk in valid_blocks],
            "pillar_max_visible_ticks": [
                blk["scenes"]["peek"][arm]["pillar_max_continuous_visible_ticks"] for blk in valid_blocks
            ],
            "doorway_max_visible_ticks": [
                blk["scenes"]["peek"][arm]["doorway_max_continuous_visible_ticks"] for blk in valid_blocks
            ],
            "statuses": [blk["scenes"]["peek"][arm]["obstructed_recovery_status"] for blk in valid_blocks],
        }
    return ablations, {
        "known_arrow_evasion": "INTERNAL_ONLY_excluded_from_shared_comparison",
        "melee_cross_engine_and_zombie_stage": melee_desc,
        "peek_obstructed_recovery_qualification": peek_desc,
    }


def _verify_manifest_protocol(manifest, blocks_input):
    formal_campaign = manifest.get("protocol_mode") == "formal_campaign"
    enforce_seeds = formal_campaign or (manifest.get("enforce_seed_schedule") is True)
    enforce_order = formal_campaign or (manifest.get("enforce_alternating_order") is True)
    campaign_id = manifest.get("campaign_id")
    if enforce_seeds and (not isinstance(campaign_id, str) or not campaign_id.strip()):
        raise ValueError("formal_campaign or enforce_seed_schedule requires non-empty manifest campaign_id")

    any_block_index = any("block_index" in entry for entry in blocks_input)
    if formal_campaign or enforce_seeds or enforce_order or any_block_index:
        indices = []
        for entry in blocks_input:
            b_idx = entry.get("block_index")
            if isinstance(b_idx, bool) or not isinstance(b_idx, int):
                raise ValueError("every block must provide integer block_index when protocol indexing is active")
            indices.append(b_idx)
        expected_indices = list(range(1, len(blocks_input) + 1))
        if indices != expected_indices:
            raise ValueError(
                f"non-contiguous or reordered block_index sequence {indices} != {expected_indices}; "
                "incomplete or failed blocks must never be skipped or post-hoc replaced"
            )

    seen_seeds = set()
    recorded_orders = []
    for pos, entry in enumerate(blocks_input, 1):
        b_idx = entry.get("block_index", pos)
        order = entry.get("execution_order")
        if enforce_order and order is None:
            raise ValueError(f"missing execution_order in block {entry.get('block_id')}")
        if order is not None:
            if order not in ALLOWED_EXECUTION_ORDERS:
                raise ValueError(f"invalid execution_order {order!r} in block {entry.get('block_id')}")
            if enforce_order and order != expected_pair_execution_order(b_idx):
                raise ValueError(
                    f"execution_order {order!r} in block {entry.get('block_id')} violates pre-fixed schedule "
                    f"{expected_pair_execution_order(b_idx)!r}"
                )
            recorded_orders.append(order)

        sched_seed = entry.get("scheduled_seed")
        if enforce_seeds and sched_seed is None:
            raise ValueError(f"missing scheduled_seed in block {entry.get('block_id')}")
        if sched_seed is not None:
            if isinstance(sched_seed, bool) or not isinstance(sched_seed, int):
                raise ValueError(f"non-integer scheduled_seed in block {entry.get('block_id')}")
            if isinstance(campaign_id, str) and campaign_id.strip():
                expected_seed = derive_scheduled_block_seed(campaign_id, b_idx)
                if sched_seed != expected_seed:
                    raise ValueError(
                        f"scheduled_seed mismatch in block {entry.get('block_id')}: {sched_seed} != {expected_seed}"
                    )
            if sched_seed in seen_seeds:
                raise ValueError(f"duplicate scheduled_seed across blocks in {entry.get('block_id')}")
            seen_seeds.add(sched_seed)

        world_seed = entry.get("world_seed")
        if world_seed is not None and sched_seed is None:
            if isinstance(world_seed, bool) or not isinstance(world_seed, int):
                raise ValueError(f"non-integer world_seed in block {entry.get('block_id')}")
            if world_seed in seen_seeds:
                raise ValueError(f"duplicate world_seed across blocks in {entry.get('block_id')}")
            seen_seeds.add(world_seed)

    return {
        "campaign_id": campaign_id,
        "formal_campaign_protocol_enforced": formal_campaign,
        "contiguous_block_indices_verified": bool(formal_campaign or enforce_seeds or enforce_order or any_block_index),
        "seed_schedule_verified": enforce_seeds,
        "alternating_pair_order_verified": enforce_order,
        "recorded_execution_orders": recorded_orders,
    }


def evaluate_manifest(manifest, contract_bytes, preview_mode=False):
    contract = json.loads(contract_bytes)
    require(
        contract.get("schema") == "combat-shared-statistics-contract-v1"
        and contract.get("contract") == "COMBAT_SHARED_STATISTICS_V1",
        "unsupported shared statistics contract",
    )
    blocks_input = manifest.get("blocks", [])
    require(isinstance(blocks_input, list) and len(blocks_input) >= 1, "manifest must provide at least one block")
    seen_block_ids = set()
    for entry in blocks_input:
        bid = entry.get("block_id")
        require(isinstance(bid, str) and bid and bid not in seen_block_ids, f"duplicate or invalid block_id: {bid}")
        seen_block_ids.add(bid)

    protocol_receipt = _verify_manifest_protocol(manifest, blocks_input)
    excluded_tokens = tuple(contract["calibrationExclusion"]["excludedDirectoryTokens"])
    excluded_shas = frozenset(contract["calibrationExclusion"]["excludedCaptureSha256"])

    collection_faults = []
    behavioral_violations = []
    completed_blocks = []
    global_shas = set()
    global_actors = set()
    global_owners = set()
    reference_artifacts = None
    any_calibration_touched = False

    for entry in blocks_input:
        bid = entry["block_id"]
        declared_status = entry.get("status", "completed")
        if declared_status in ("collection_fault", "attempted_fault", "incomplete"):
            collection_faults.append({
                "block_id": bid,
                "fault_source": "manifest_declared",
                "reason": entry.get("fault_reason", "unspecified startup, incomplete, or collection fault"),
            })
            continue
        require(declared_status == "completed", f"unknown block status {declared_status} in {bid}")
        try:
            evaluated = evaluate_completed_block(entry, contract, excluded_tokens, excluded_shas, preview_mode)
        except CollectionIntegrityFault as fault:
            collection_faults.append({
                "block_id": bid,
                "fault_source": "evaluator_integrity_check",
                "reason": str(fault),
            })
            continue

        any_calibration_touched = any_calibration_touched or evaluated["calibration_sample_touched"]
        if reference_artifacts is None:
            reference_artifacts = evaluated["artifacts"]
        elif evaluated["artifacts"] != reference_artifacts:
            raise ValueError(f"cross-block artifact or runtime build drift detected in {bid}")
        if (
            (global_shas & evaluated["capture_shas"])
            or (global_actors & evaluated["actor_uuids"])
            or (global_owners & evaluated["owner_uuids"])
        ):
            raise ValueError(f"cross-block capture SHA256, maidUuid, or ownerUuid reuse detected in {bid}")
        global_shas |= evaluated["capture_shas"]
        global_actors |= evaluated["actor_uuids"]
        global_owners |= evaluated["owner_uuids"]

        completed_blocks.append(evaluated)
        for item in evaluated["behavioral_violations"]:
            behavioral_violations.append({"block_id": bid, "violation": item})

    attempted_count = len(blocks_input)
    completed_count = len(completed_blocks)
    valid_blocks = [b for b in completed_blocks if not b["behavioral_violations"]]
    valid_count = len(valid_blocks)

    hard_totals = {
        "friendly_or_owner_non_canceled_damage_events": 0,
        "friendly_or_owner_non_canceled_damage_hp": 0.0,
        "safety_occluded_wall_arrow_joins": 0,
        "forbidden_window_or_conservation_mismatches": 0,
    }
    for blk in completed_blocks:
        for arms in blk["scenes"].values():
            for row in arms.values():
                hard_totals["friendly_or_owner_non_canceled_damage_events"] += row["friendly_or_owner_damage_events"]
                hard_totals["friendly_or_owner_non_canceled_damage_hp"] += row["friendly_or_owner_damage_hp"]
                hard_totals["safety_occluded_wall_arrow_joins"] += row["occluded_wall_arrow_joins"]
                hard_totals["forbidden_window_or_conservation_mismatches"] += row["conservation_or_window_mismatches"]

    sequence_gates = []
    for gate in contract["deterministicSequenceGates"]["gates"]:
        scene = gate["mechanism"]
        arms = gate["arms"]
        passed_blocks = sum(
            1 for blk in completed_blocks if all(blk["scenes"][scene][a]["sequence_passed"] for a in arms)
        )
        rate = (passed_blocks / completed_count) if completed_count > 0 else 0.0
        sequence_gates.append({
            "id": gate["id"],
            "mechanism": scene,
            "arms": arms,
            "passed_blocks": passed_blocks,
            "evaluated_completed_blocks": completed_count,
            "attempted_blocks": attempted_count,
            "empirical_rate": rate,
            "wilson_95_two_sided": wilson_interval(passed_blocks, completed_count),
            "gate_passed": (
                completed_count == attempted_count and passed_blocks == completed_count and completed_count > 0
            ),
        })

    schedule = contract["sequentialSchedule"]
    looks_by_b = {int(row["blocks"]): row for row in schedule["scheduledLooks"]}
    look_spec = looks_by_b.get(attempted_count)
    metric_specs = contract["pairedEfficacyMetrics"]
    num_metrics = len(metric_specs)

    paired_results = []
    if valid_count >= 1:
        try:
            for idx, mspec in enumerate(metric_specs):
                paired_results.append(_evaluate_paired_metric(mspec, idx, num_metrics, valid_blocks, contract))
            ablations, exclusions = _build_aim_ablations_and_exclusions(valid_blocks)
        except CollectionIntegrityFault as fault:
            collection_faults.append({
                "block_id": "post_block_metric_check",
                "fault_source": "evaluator_metric_integrity_check",
                "reason": str(fault),
            })
            paired_results, ablations, exclusions = [], [], {}
    else:
        ablations, exclusions = [], {}

    contract_status = contract.get("contractStatus", "PROPOSED_PENDING_REVIEW")
    contract_frozen = contract_status == "FROZEN"
    min_futility_b = int(contract["inferenceMethod"].get("minBlocksBeforeFutilityStop", 3))
    all_seq_passed = bool(sequence_gates) and all(g["gate_passed"] for g in sequence_gates)

    min_ni_wealth = (
        min(m["single_point_admission_eprocess"]["ni_wealth_at_delta"] for m in paired_results)
        if paired_results
        else None
    )
    max_inf_wealth = (
        max(m["single_point_admission_eprocess"]["dual_inf_wealth_at_delta"] for m in paired_results)
        if paired_results
        else None
    )

    if preview_mode:
        verdict = "CALIBRATION_PREVIEW_ONLY_NOT_ADMITTED"
        would_decide = "CALIBRATION_PREVIEW_ONLY"
        algo_degradation = bool(behavioral_violations)
        stat_admitted = False
    elif behavioral_violations:
        verdict = "FAILED_BEHAVIORAL_HARD_GATE"
        would_decide = verdict
        algo_degradation = True
        stat_admitted = False
    elif collection_faults or valid_count != attempted_count:
        # 任何未完成或完整性故障区组永久阻断统计通过，禁止跳过或靠追加区组冲抵
        verdict = "BLOCKED_BY_COLLECTION_OR_INTEGRITY_FAULT"
        would_decide = verdict
        algo_degradation = False
        stat_admitted = False
    else:
        decisions = [m["metric_eprocess_decision"] for m in paired_results]
        if attempted_count >= min_futility_b and any(d == "FAIL_FUTILITY_UNION_BOUND" for d in decisions):
            would_decide = "FAILED_NONINFERIORITY"
            algo_degradation = True
        elif (
            look_spec is not None
            and look_spec["canGrantPass"]
            and all_seq_passed
            and paired_results
            and all(d == "PASS_SINGLE_POINT_EPROCESS" for d in decisions)
        ):
            would_decide = "PASSED_NONINFERIORITY"
            algo_degradation = False
        elif attempted_count >= int(schedule["maxScheduledBlocks"]):
            would_decide = "INCONCLUSIVE_AT_MAX_BLOCKS"
            algo_degradation = False
        elif look_spec is None:
            would_decide = "EXTEND_TO_NEXT_SCHEDULED_LOOK"
            algo_degradation = False
        else:
            would_decide = "INCONCLUSIVE_EXTEND"
            algo_degradation = False

        if contract_frozen:
            verdict = would_decide
            stat_admitted = verdict == "PASSED_NONINFERIORITY"
        else:
            verdict = (
                would_decide
                if would_decide == "FAILED_NONINFERIORITY"
                else "PROPOSED_CONTRACT_UNFROZEN_PREVIEW"
            )
            stat_admitted = False

    return {
        "schema": "combat-shared-statistics-report-v1",
        "contract": contract["contract"],
        "contract_version": contract.get("version"),
        "contract_status": contract_status,
        "contract_sha256": sha(contract_bytes),
        "calibration_scale_preview_mode": preview_mode,
        "calibration_sample_touched": any_calibration_touched,
        "statistical_admission": stat_admitted,
        "verdict": verdict,
        "sequential_decision_if_contract_frozen": would_decide,
        "algorithmic_degradation_or_violation_inferred": algo_degradation,
        "block_accounting": {
            "attempted_blocks": attempted_count,
            "completed_valid_blocks": valid_count,
            "collection_or_integrity_fault_blocks": len(collection_faults),
            "behavioral_violation_blocks": len(completed_blocks) - valid_count,
            "unique_captures_verified": len(global_shas),
            "unique_actors_verified": len(global_actors),
            "unique_owners_verified": len(global_owners),
            "scheduled_look_matched": look_spec["blocks"] if look_spec else None,
            "scheduled_look_can_grant_pass": bool(look_spec["canGrantPass"]) if look_spec else False,
            "iut_min_ni_wealth_across_17_metrics": min_ni_wealth,
            "union_max_dual_inf_wealth_across_17_metrics": max_inf_wealth,
            "unobserved_rare_event_rate_ucb_95_if_iid_bernoulli": clopper_pearson_zero_event_ucb(valid_count, 0.05),
        },
        "protocol_verification": protocol_receipt,
        "locked_artifacts": reference_artifacts,
        "collection_and_integrity_faults": collection_faults,
        "behavioral_hard_gate_summary": {
            "totals": hard_totals,
            "violations": behavioral_violations,
            "passed": len(behavioral_violations) == 0 and completed_count > 0,
        },
        "deterministic_sequence_gates": sequence_gates,
        "paired_efficacy_metrics": paired_results,
        "native_aim_ablations_descriptive": ablations,
        "scope_exclusions_and_descriptive_stages": exclusions,
        "methodology_and_limitations": contract["inferenceMethod"],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, help="JSON manifest listing fresh-JVM blocks")
    parser.add_argument(
        "--contract",
        type=Path,
        default=Path(__file__).with_name("combat-shared-statistics-contract.json"),
        help="Shared combat statistics contract JSON",
    )
    parser.add_argument("--output", type=Path, required=True, help="Output JSON report or campaign schedule path")
    parser.add_argument(
        "--calibration-scale-preview",
        action="store_true",
        help="Inspect historical calibration captures strictly without statistical admission",
    )
    parser.add_argument(
        "--emit-campaign-schedule",
        type=str,
        default=None,
        help="Generate pre-committed seed and AB/BA execution-order schedule for the given campaign_id",
    )
    parser.add_argument(
        "--max-blocks",
        type=int,
        default=120,
        help="Maximum blocks when generating --emit-campaign-schedule (default: 120)",
    )
    args = parser.parse_args()
    if args.emit_campaign_schedule is not None:
        sched = build_campaign_schedule(args.emit_campaign_schedule, max_blocks=args.max_blocks)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(sched, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({
            "schema": sched["schema"],
            "campaign_id": sched["campaign_id"],
            "max_blocks": sched["max_blocks"],
            "schedule_sha256": sha(args.output.read_bytes()),
        }, ensure_ascii=False))
        return

    require(args.manifest is not None, "--manifest is required unless --emit-campaign-schedule is used")
    manifest = json.loads(args.manifest.read_bytes())
    result = evaluate_manifest(manifest, args.contract.read_bytes(), preview_mode=args.calibration_scale_preview)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    summary = {
        k: result[k]
        for k in (
            "schema", "contract_version", "contract_status", "statistical_admission", "verdict",
            "sequential_decision_if_contract_frozen", "algorithmic_degradation_or_violation_inferred",
            "block_accounting",
        )
    }
    print(json.dumps(summary, ensure_ascii=False))


if __name__ == "__main__":
    main()
