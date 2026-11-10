"""同端两次原生完整 NBT 序列化的只读绑定。"""

import base64
import hashlib
import io
import struct

from check import require

BINARY_SCHEMA = "named-uncompressed-nbt-v1"
BINDING_SCHEMA = "same-side-initial-nbt-binding-v1"
MAX_BYTES = 8 * 1024 * 1024


def decode_named_nbt(raw):
    """保留tag id、列表元素类型/顺序、浮点位和Modified UTF-8原字节；仅compound键无序。"""
    require(isinstance(raw, bytes) and 0 < len(raw) <= MAX_BYTES, "invalid complete NBT byte size")
    stream = io.BytesIO(raw)

    def read(count):
        value = stream.read(count)
        require(len(value) == count, "truncated complete NBT")
        return value

    def number(kind):
        return struct.unpack('>' + kind, read(struct.calcsize('>' + kind)))[0]

    def string_bytes():
        return read(number('H'))

    def count():
        size = number('i')
        require(0 <= size <= len(raw) - stream.tell(), "negative or impossible NBT container length")
        return size

    def payload(kind, depth):
        require(depth <= 128, "initial NBT exceeds supported nesting depth")
        require(1 <= kind <= 12, "unknown or unexpected NBT tag id")
        if kind <= 4:
            value = number({1: 'b', 2: 'h', 3: 'i', 4: 'q'}[kind])
        elif kind in (5, 6):
            # 保留原始 IEEE 二进制位、-0 及特殊 NaN 位
            value = read(4 if kind == 5 else 8)
        elif kind == 7:
            value = read(count())
        elif kind == 8:
            value = string_bytes()
        elif kind == 9:
            subtype, size = number('B'), count()
            require(0 <= subtype <= 12 and (subtype != 0 or size == 0), "invalid NBT list element type")
            value = (subtype, tuple(payload(subtype, depth + 1) for _ in range(size)))
        elif kind == 10:
            value = {}
            while True:
                subtype = number('B')
                if subtype == 0:
                    break
                name = string_bytes()
                require(name not in value, "duplicate NBT compound key")
                value[name] = payload(subtype, depth + 1)
        else:
            value = tuple(number('i' if kind == 11 else 'q') for _ in range(count()))
        return kind, value

    kind, name = number('B'), string_bytes()
    require(kind == 10, "initial entity NBT root must be a named compound")
    result = name, payload(kind, 0)
    require(stream.tell() == len(raw), "trailing bytes after complete NBT root")
    return result


def binary_snapshot(record, raw_snbt):
    require(isinstance(record, dict) and record.get("schema") == BINARY_SCHEMA
            and record.get("encoding") == "base64", "unsupported complete NBT binary snapshot")
    require(isinstance(raw_snbt, str) and raw_snbt and record.get("rawSNBT") == raw_snbt,
            "binary snapshot is not attached to its retained original SNBT string")
    encoded = record.get("data")
    require(isinstance(encoded, str) and 0 < len(encoded) <= (MAX_BYTES + 2) // 3 * 4,
            "invalid complete NBT Base64 size")
    try:
        raw = base64.b64decode(encoded, validate=True)
    except (ValueError, TypeError) as failure:
        raise ValueError("invalid complete NBT Base64") from failure
    require(base64.b64encode(raw).decode('ascii') == encoded, "noncanonical complete NBT Base64")
    return raw, decode_named_nbt(raw)


def first_difference(left, right, path="$entity"):
    if left[0] != right[0]:
        return {"path": path, "kind": "tag_id", "attribute": left[0], "paired": right[0]}
    kind, a, b = left[0], left[1], right[1]
    if kind == 10:
        if a.keys() != b.keys():
            return {"path": path, "kind": "compound_keys_modified_utf8_hex",
                    "attribute_only": sorted(k.hex() for k in a.keys() - b.keys()),
                    "paired_only": sorted(k.hex() for k in b.keys() - a.keys())}
        for key in a:
            if a[key] != b[key]:
                return first_difference(a[key], b[key], path + "/key-mutf8:" + key.hex())
    elif kind == 9:
        if a[0] != b[0] or len(a[1]) != len(b[1]):
            return {"path": path, "kind": "list_element_type_or_length",
                    "attribute": [a[0], len(a[1])], "paired": [b[0], len(b[1])]}
        for index, (x, y) in enumerate(zip(a[1], b[1])):
            if x != y:
                return first_difference(x, y, path + "/" + str(index))
    elif a != b:
        return {"path": path, "kind": "exact_payload", "tag_id": kind,
                "attribute": a.hex() if isinstance(a, bytes) else a,
                "paired": b.hex() if isinstance(b, bytes) else b}
    return None


def compare_same_side_nbt(attribute_snapshot, actual_inputs):
    a, b = attribute_snapshot.get("rawEntityNBT"), actual_inputs.get("rawInitialNBT")
    result = {"schema": BINDING_SCHEMA, "status": "NOT_PROVEN", "typed_structure_equal": False,
              "raw_snbt_text_equal": a == b, "retained_raw_snbt": {"attribute": a, "paired": b},
              "scope": "two complete initial NBT snapshots of the same actor on the same engine only",
              "compound_key_order_ignored": True, "tag_types_and_list_order_preserved": True,
              "float_bits_and_modified_utf8_bytes_preserved": True,
              "cross_engine_nbt_semantic_equivalence_evaluated": False}
    left, right = attribute_snapshot.get("rawEntityNbtBinary"), actual_inputs.get("rawInitialNbtBinary")
    if left is None or right is None:
        result["reason"] = "lossless binary snapshots absent; legacy SNBT alone cannot prove complete typed binding"
        return result
    left_raw, left_tree = binary_snapshot(left, a)
    right_raw, right_tree = binary_snapshot(right, b)
    result["retained_binary_snapshots"] = {"attribute": left, "paired": right}
    result["binary_sha256"] = {"attribute": hashlib.sha256(left_raw).hexdigest(),
                               "paired": hashlib.sha256(right_raw).hexdigest()}
    result["binary_bytes_equal"] = left_raw == right_raw
    result["typed_structure_equal"] = left_tree == right_tree
    result["status"] = "EXACT_SAME_SIDE_TYPED_STRUCTURE" if left_tree == right_tree else "DIFFERENT"
    if left_tree[0] != right_tree[0]:
        result["first_difference"] = {"path": "$", "kind": "root_name_modified_utf8_hex",
                                      "attribute": left_tree[0].hex(), "paired": right_tree[0].hex()}
    elif left_tree != right_tree:
        result["first_difference"] = first_difference(left_tree[1], right_tree[1])
    return result
