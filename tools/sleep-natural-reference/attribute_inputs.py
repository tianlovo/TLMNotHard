"""独立判断实际属性输入；不从Attributes序列化空列表推断属性值或全NBT等价"""

import math
import uuid
from decimal import Decimal, localcontext

from check import require

SCHEMA = "sleep-attribute-inputs-v1"
PHASE = "registered_spawn_before_first_natural_tick"
MAPPINGS = {
    "max_health": ("minecraft:generic.max_health", "generic.maxHealth"),
    "movement_speed": ("minecraft:generic.movement_speed", "generic.movementSpeed"),
    "follow_range": ("minecraft:generic.follow_range", "generic.followRange"),
    "knockback_resistance": ("minecraft:generic.knockback_resistance", "generic.knockbackResistance"),
    "attack_damage": ("minecraft:generic.attack_damage", "generic.attackDamage"),
}


def number(value):
    require(isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value),
            "nonfinite or nonnumeric attribute input")
    return value


def measurement(original, port, tolerance):
    number(original)
    number(port)
    original_hex, port_hex = float(original).hex(), float(port).hex()
    with localcontext() as context:
        # binary64最大数量级到最小次正规值的精确十进制差，不依赖默认28位上下文舍入
        context.prec = 2200
        a = Decimal(original) if type(original) is int else Decimal.from_float(original)
        b = Decimal(port) if type(port) is int else Decimal.from_float(port)
        difference = b - a
        within = abs(difference) <= Decimal.from_float(float(tolerance))
        exact_difference = format(difference, "f")
    return {"original": original, "port": port, "original_binary64": original_hex, "port_binary64": port_hex,
            "port_minus_original_exact_decimal": exact_difference,
            "exact_equal": original_hex == port_hex, "within_tolerance": within,
            "absolute_tolerance": tolerance, "relative_tolerance": 0}


def modifiers(rows):
    require(isinstance(rows, list), "attribute modifier list missing")
    result = {}
    for row in rows:
        identifier = row["uuid"]
        require(isinstance(identifier, str) and str(uuid.UUID(identifier)) == identifier,
                "modifier UUID is not canonical")
        require(identifier not in result, "duplicate attribute modifier UUID")
        require(type(row["operation"]) is int and row["operation"] in (0, 1, 2),
                "unknown modifier operation")
        require(isinstance(row["name"], str) and type(row["persistent"]) is bool,
                "modifier identity/persistence missing")
        result[identifier] = {"uuid": identifier, "name": row["name"], "operation": row["operation"],
                              "amount": number(row["amount"]), "persistent": row["persistent"]}
    return result


def snapshot(value, side):
    require(value["schema"] == SCHEMA and value["side"] == side and value["samplePhase"] == PHASE,
            "attribute snapshot schema/side/phase differs")
    require(value["rawNbtUnchanged"] is value["attributeInstanceMembershipUnchanged"] is True,
            "attribute observer changed raw NBT or instance membership")
    require(isinstance(value["rawEntityNBT"], str) and value["rawEntityNBT"]
            and isinstance(value["rawAttributesNBT"], str) and value["rawAttributesNBT"],
            "complete raw attribute/entity inputs missing")
    require(type(value["serializedAttributeCount"]) is int and value["serializedAttributeCount"] >= 0,
            "invalid serialized attribute count")
    available, materialized = value["availableAttributeNames"], value["materializedAttributeNames"]
    require(isinstance(available, list) and isinstance(materialized, list)
            and all(isinstance(name, str) and name for name in available + materialized),
            "attribute name census missing")
    require(len(set(available)) == len(available) and len(set(materialized)) == len(materialized)
            and set(materialized) <= set(available), "attribute name census is inconsistent")
    if side == "port":
        require(set(materialized) == set(available), "native attribute map unexpectedly claims lazy defaults")
    selected = {}
    for row in value["attributes"]:
        key = row["key"]
        require(isinstance(key, str) and key not in selected and type(row["present"]) is bool,
                "duplicate/invalid selected attribute")
        require(isinstance(row["platformName"], str) and row["platformName"], "actual attribute name missing")
        name = row["platformName"]
        if row["present"]:
            require(name in available and row["storage"] in ("instance", "supplier_default"),
                    "present attribute has no actual storage")
            require((name in materialized) == (row["storage"] == "instance"), "lazy storage membership differs")
            require(side == "original" or row["storage"] == "instance", "native attribute has no instance")
            number(row["base"])
            number(row["value"])
            modifiers(row["modifiers"])
        else:
            require(name not in available and row["storage"] == "absent"
                    and not any(field in row for field in ("base", "value", "modifiers")),
                    "absent attribute was replaced with fabricated values")
        selected[key] = row
    actual_unmapped = set(available) - {row["platformName"] for row in selected.values() if row["present"]}
    unmapped = value["unmappedAttributeNames"]
    require(isinstance(unmapped, list) and all(isinstance(name, str) and name for name in unmapped)
            and len(unmapped) == len(set(unmapped)) and set(unmapped) == actual_unmapped,
            "unmapped attribute names were discarded")
    return selected


def compare_attribute_inputs(original, port, contract):
    result = {"status": "NOT_PROVEN", "selected_initial_attribute_inputs_equal": False,
              "selected_initial_attribute_inputs_within_numeric_tolerance": False,
              "all_attribute_semantics_evaluated": False, "full_nbt_semantic_equivalence_evaluated": False,
              "scope": "actual initial base/value and complete modifier identity/operation/amount/persistence for five reviewed mappings only",
              "numeric_tolerance_scope": "numeric representation only; not a noninferiority or statistical margin",
              "missing_or_unproven": [], "differences": [], "measurements": []}
    if original is None or port is None or contract is None:
        result["missing_or_unproven"].append("typed original/port snapshot or mapping contract not captured")
        return result
    require(contract["schema"] == SCHEMA and contract["samplePhase"] == PHASE, "attribute contract differs")
    tolerance = number(contract["absoluteTolerance"])
    require(0 <= tolerance <= 1e-6, "attribute comparison tolerance was widened")
    configured = {}
    for mapping in contract["mappings"]:
        key = mapping["key"]
        require(key not in configured, "duplicate attribute mapping")
        configured[key] = (mapping["original"], mapping["port"])
    left, right = snapshot(original, "original"), snapshot(port, "port")
    result["actual_selected_snapshots"] = {"original": original["attributes"], "port": port["attributes"]}
    all_exact, all_within, all_modifiers_exact = True, True, True
    for key in sorted(set(configured) | set(left) | set(right) | set(MAPPINGS)):
        expected = MAPPINGS.get(key)
        a, b = left.get(key), right.get(key)
        if expected is None or configured.get(key) != expected:
            result["missing_or_unproven"].append({"key": key, "reason": "source-backed mapping absent or different"})
            continue
        if a is None or b is None or not a["present"] or not b["present"]:
            result["missing_or_unproven"].append({"key": key, "reason": "actual attribute missing on one or both actors"})
            continue
        if (a["platformName"], b["platformName"]) != expected:
            result["missing_or_unproven"].append({"key": key, "reason": "actual platform name differs from mapping"})
            continue
        observed = {"key": key, "original_platform_name": a["platformName"], "port_platform_name": b["platformName"],
                    "original_storage": a["storage"], "port_storage": b["storage"]}
        for field in ("base", "value"):
            values = measurement(a[field], b[field], tolerance)
            observed[field] = values
            all_exact = all_exact and values["exact_equal"]
            all_within = all_within and values["within_tolerance"]
            if not values["exact_equal"]:
                result["differences"].append({"key": key, "field": field, **values})
        # UUID/名称不做猜测重映射；相同最终value不能抵消修饰符身份、操作或保存状态的差异
        am, bm = modifiers(a["modifiers"]), modifiers(b["modifiers"])
        observed["modifiers"] = {"original": list(am.values()), "port": list(bm.values()), "exact_equal": am == bm}
        result["measurements"].append(observed)
        if am != bm:
            all_modifiers_exact = False
            result["differences"].append({"key": key, "field": "modifiers",
                                          "original": list(am.values()), "port": list(bm.values())})
    result["unmapped_actual_attributes"] = {"original": original["unmappedAttributeNames"],
                                              "port": port["unmappedAttributeNames"]}
    result["unmapped_actual_attributes_verdict"] = "NOT_PROVEN"
    result["serialized_attribute_counts"] = {"original": original["serializedAttributeCount"],
                                               "port": port["serializedAttributeCount"]}
    result["raw_nbt_retained_in_captures"] = True
    proven = not result["missing_or_unproven"]
    result["selected_initial_attribute_inputs_within_numeric_tolerance"] = proven and all_within and all_modifiers_exact
    if not all_within or not all_modifiers_exact:
        result["status"] = "DIFFERENT"
    elif proven and all_exact:
        result["status"] = "EXACT_SELECTED_INITIAL_INPUTS"
        result["selected_initial_attribute_inputs_equal"] = True
    elif proven:
        result["status"] = "WITHIN_NUMERIC_TOLERANCE_ONLY"
    return result
