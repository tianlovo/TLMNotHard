"""手工原生NBT字节反例；不由被测decoder生成输入或启动游戏。"""

import base64
import copy
import unittest

from nbt_binding import compare_same_side_nbt, decode_named_nbt


def record(payload, raw):
    return {"schema": "named-uncompressed-nbt-v1", "encoding": "base64", "rawSNBT": raw,
            "data": base64.b64encode(bytes.fromhex("0a0000" + payload + "00")).decode('ascii')}


def compare(left, right, left_text="{opaque:retained}", right_text="{opaque:retained}"):
    return compare_same_side_nbt(
        {"rawEntityNBT": left_text, "rawEntityNbtBinary": record(left, left_text)},
        {"rawInitialNBT": right_text, "rawInitialNbtBinary": record(right, right_text)})


class SameSideNbtBindingCounterexamples(unittest.TestCase):

    def test_compound_order_alone_binds_and_both_raw_strings_stay_retained(self):
        result = compare("0300016100000007 0100016201", "0100016201 0300016100000007",
                         "{a:7,b:1b}", "{b:1b,a:7}")
        self.assertTrue(result["typed_structure_equal"])
        self.assertFalse(result["raw_snbt_text_equal"])
        self.assertFalse(result["binary_bytes_equal"])
        self.assertEqual(result["retained_raw_snbt"], {"attribute": "{a:7,b:1b}", "paired": "{b:1b,a:7}"})
        self.assertFalse(result["cross_engine_nbt_semantic_equivalence_evaluated"])

    def test_equal_integer_values_with_different_tag_types_do_not_bind(self):
        for changed in ("020001780001", "0300017800000001", "040001780000000000000001"):
            with self.subTest(changed=changed):
                result = compare("0100017801", changed)
                self.assertFalse(result["typed_structure_equal"])
                self.assertEqual(result["first_difference"]["kind"], "tag_id")

    def test_lists_keep_order_element_type_and_empty_element_type(self):
        ordered = "0900016c03 00000002 00000001 00000002"
        reversed_order = "0900016c03 00000002 00000002 00000001"
        int_array = "0b00016c 00000002 00000001 00000002"
        self.assertFalse(compare(ordered, reversed_order)["typed_structure_equal"])
        self.assertFalse(compare(ordered, int_array)["typed_structure_equal"])
        self.assertFalse(compare("0900016c00 00000000", "0900016c03 00000000")["typed_structure_equal"])

    def test_exact_float_bits_signed_zero_and_large_long_are_not_rounded(self):
        for left, right in (("0500016680000000", "0500016600000000"),
                            ("060001643ff0000000000000", "060001643ff0000000000001"),
                            ("0400016c0020000000000000", "0400016c0020000000000001")):
            with self.subTest(left=left):
                result = compare(left, right)
                self.assertFalse(result["typed_structure_equal"])
                self.assertEqual(result["first_difference"]["kind"], "exact_payload")

    def test_unknown_nested_fields_and_all_array_payloads_remain_significant(self):
        arrays = ("070001620000000300ff7f "
                  "0b00016900000002800000007fffffff "
                  "0c00016c0000000280000000000000007fffffffffffffff")
        nested = "0a000467746e68" + arrays + "00"
        self.assertTrue(compare(nested, nested)["typed_structure_equal"])
        for changed in (nested.replace("00ff7f", "007fff"),
                        nested.replace("800000007fffffff", "7fffffff80000000"),
                        nested.replace("80000000000000007fffffffffffffff", "7fffffffffffffff8000000000000000"),
                        "0a000467746e6800"):
            self.assertFalse(compare(nested, changed)["typed_structure_equal"])

    def test_modified_utf8_bytes_are_never_replaced_or_lossily_decoded(self):
        self.assertFalse(compare("080001730002c080", "080001730003efbfbd")["typed_structure_equal"])
        # 原生 Modified UTF-8 会写出未配对代理项；保留原字节，无 errors=replace
        self.assertTrue(compare("080001730003eda080", "080001730003eda080")["typed_structure_equal"])

    def test_legacy_text_alone_remains_unproven_even_if_identical(self):
        left, right = {"rawEntityNBT": "{Bytes:[3 bytes],Empty:[]}"}, {"rawInitialNBT": "{Bytes:[3 bytes],Empty:[]}"}
        before = copy.deepcopy((left, right))
        result = compare_same_side_nbt(left, right)
        self.assertEqual(result["status"], "NOT_PROVEN")
        self.assertFalse(result["typed_structure_equal"])
        self.assertEqual((left, right), before)

    def test_malformed_binary_and_relabelled_raw_string_fail_closed(self):
        for raw in ("0a0000", "0a00000000", "0a00000100017801010001780200",
                    "0a00000900016c00ffffffff00", "0a00000d00017800"):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                decode_named_nbt(bytes.fromhex(raw))
        with self.assertRaisesRegex(ValueError, "retained original SNBT"):
            compare_same_side_nbt({"rawEntityNBT": "{changed:1}",
                                   "rawEntityNbtBinary": record("", "{}")},
                                  {"rawInitialNBT": "{}", "rawInitialNbtBinary": record("", "{}")})


if __name__ == "__main__":
    unittest.main()
