"""Regression checks for the explicitly selected unified task input contract."""
import copy
import unittest

from unified_task_inputs import ATTACK, RANGED, expected_task_input, validate_unified_root


def initial_root():
    state = {b'Version': (3, 1), b'ReferenceId': (8, RANGED.encode()),
             b'Preference': (8, RANGED.encode()), b'PreferencePending': (1, 1)}
    protection = {b'Registry': (8, b'minecraft:bow'), b'Complete': (1, 1),
                  b'ExactCount': (3, 1), b'ExactDamage': (3, 0)}
    bow = {b'id': (2, 261), b'Count': (1, 1), b'Damage': (2, 0), b'TLMNHProtection': (10, protection)}
    equipment = ((10, bow),) + ((10, {}),) * 4
    return {b'MaidTask': (8, ATTACK.encode()), b'TLMNH': (10, {b'CombatTask': (10, state)}),
            b'Equipment': (9, (10, equipment))}


class UnifiedTaskInputsTest(unittest.TestCase):
    def test_actual_bow_and_pending_ranged_command_are_required(self):
        validate_unified_root(initial_root())
        for key, value in ((b'Version', (3, 2)), (b'Preference', (8, ATTACK.encode())),
                           (b'ReferenceId', (8, ATTACK.encode())), (b'PreferencePending', (1, 0))):
            root = initial_root()
            root[b'TLMNH'][1][b'CombatTask'][1][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_unified_root(root)

    def test_wrong_native_weapon_or_protected_registry_is_rejected(self):
        for key, value in ((b'id', (2, 267)), (b'Count', (1, 2)), (b'Damage', (2, 1))):
            root = initial_root()
            root[b'Equipment'][1][1][0][1][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_unified_root(root)
        root = copy.deepcopy(initial_root())
        root[b'Equipment'][1][1][0][1][b'TLMNHProtection'][1][b'Registry'] = (8, b'minecraft:iron_sword')
        with self.assertRaises(ValueError):
            validate_unified_root(root)

    def test_source_faithful_and_melee_inputs_keep_their_raw_contract(self):
        self.assertEqual(RANGED, expected_task_input({'arm': 'O'}, 'motion', True))
        self.assertEqual(RANGED, expected_task_input({'arm': 'F'}, 'motion', False))
        self.assertEqual(RANGED, expected_task_input({'arm': 'F-R'}, 'motion', False))
        self.assertEqual(ATTACK, expected_task_input({'arm': 'N'}, 'melee', False))


if __name__ == '__main__':
    unittest.main()
