package com.tianluoqaq.tlmnh.common.combat;

/** 战斗结算、装备纪元与弓箭射击参数（Diagnostics） */
public final class MaidCombatDiagnostics {

    public static final String MSG_COMBAT_CADENCE_CANNOT_BE_REPLACED = "Combat cadence cannot be replaced during an active attack call";
    public static final String MSG_INVALID_ACCEPTED_HIT_TRANSITION = "Invalid accepted hit transition";
    public static final String MSG_INVALID_SETTLED_HIT_TRANSITION = "Invalid settled hit transition";
    public static final String MSG_UNRESOLVED_COMBAT_HIT_RETAINED = "Unresolved combat hit retained: ";
    public static final String MSG_UNCLOSED_COMBAT_RECEIPT_CANNOT_BE = "Unclosed combat receipt cannot be overwritten by an in-place read";
    public static final String MSG_UNCLOSED_OR_UNKNOWN_COMBAT_RECEIPT = "Unclosed or unknown combat receipt retained without replay";
    public static final String MSG_NATIVE_EQUIPMENT_CAPABILITY_WITHHELD = "Native equipment capability withheld: ";
    public static final String MSG_DEVELOPMENT_FLIGHT_PROFILE_CANNOT_CHANGE = "Development flight profile cannot change in this context";

    private MaidCombatDiagnostics() {}
}
