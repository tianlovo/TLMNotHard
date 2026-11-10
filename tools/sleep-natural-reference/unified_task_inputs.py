"""Explicit, typed input mapping for the approved Enhanced unified combat task."""
from check import require
from nbt_binding import binary_snapshot

ATTACK = 'touhou_little_maid:attack'
RANGED = 'touhou_little_maid:ranged_attack'
ENHANCED_ARMS = {'N', 'N-static', 'N-last'}


def compound(tag, name):
    require(isinstance(tag, tuple) and len(tag) == 2 and tag[0] == 10, name + ' is not a typed compound')
    return tag[1]


def validate_unified_root(root):
    require(root.get(b'MaidTask') == (8, ATTACK.encode('ascii')), 'Unified task NBT differs')
    extension = compound(root.get(b'TLMNH'), 'TLMNH')
    state = compound(extension.get(b'CombatTask'), 'CombatTask')
    require(state.get(b'Version') == (3, 1), 'Unsupported unified task state version')
    require(state.get(b'ReferenceId') == (8, RANGED.encode('ascii'))
            and state.get(b'Preference') == (8, RANGED.encode('ascii'))
            and state.get(b'PreferencePending') == (1, 1), 'Requested ranged task preference is not pending')
    equipment = root.get(b'Equipment')
    require(equipment is not None and equipment[0] == 9 and equipment[1][0] == 10
            and len(equipment[1][1]) == 5, 'Initial native equipment layout differs')
    bow = compound(equipment[1][1][0], 'Mainhand')
    require(bow.get(b'id') == (2, 261) and bow.get(b'Count') == (1, 1)
            and bow.get(b'Damage') == (2, 0), 'Mapped ranged command lacks its actual fresh native bow')
    protected = compound(bow.get(b'TLMNHProtection'), 'Mainhand protection')
    require(protected.get(b'Registry') == (8, b'minecraft:bow')
            and protected.get(b'Complete') == (1, 1)
            and protected.get(b'ExactCount') == (3, 1)
            and protected.get(b'ExactDamage') == (3, 0), 'Actual protected bow registry binding differs')


def expected_task_input(data, mechanism, original):
    expected = ATTACK if mechanism == 'melee' else RANGED
    if original or mechanism == 'melee' or data['arm'] not in ENHANCED_ARMS:
        return expected
    record = data['maidInitial']['fullNbt']
    named = binary_snapshot(record, record['rawSNBT'])[1]
    validate_unified_root(compound(named[1], 'Entity root'))
    return ATTACK


def task_mapping_receipt(data, mechanism, original):
    mapped = not original and mechanism != 'melee' and data['arm'] in ENHANCED_ARMS
    return {'actual_raw_task': data['actualInputs']['task'],
            'reference_command': ATTACK if mechanism == 'melee' else RANGED,
            'mapping': 'enhanced_unified_state_v1' if mapped else 'raw_exact',
            'typed_preference_and_native_bow_verified': mapped,
            'raw_capture_rewritten': False}
