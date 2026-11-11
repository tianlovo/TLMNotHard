package com.tianluoqaq.tlmnh.common.life.bed;

/** 女仆床方块与睡眠绑定常量（Diagnostics） */
public final class MaidBedDiagnostics {

    public static final String MSG_UNRECOGNIZED_SAVED_MAID_BED_COLOR = "Unrecognized saved maid-bed color; dye refused at {},{},{}";
    public static final String MSG_SAVED_BEDCOLOR_IS_NOT_SUPPORTED = "Saved BedColor is not a supported integer color";
    public static final String MSG_NESTED_RESTORING_PLACEMENT_REFUSED = "Nested/restoring placement refused";
    public static final String MSG_BED_FOOT_WRITE_FAILED = "Bed foot write failed";
    public static final String MSG_BED_AREA_CHANGED_AFTER_FOOT = "Bed area changed after foot write";
    public static final String MSG_BED_HEAD_WRITE_FAILED = "Bed head write failed";
    public static final String MSG_BED_SOURCE_INITIALIZATION_REFUSED = "Bed source initialization refused";
    public static final String MSG_BED_IDENTITY_CHANGED_DURING_CALLBACKS = "Bed identity changed during callbacks";
    public static final String MSG_PLACEMENT_SOURCE_CHANGED_DURING_CALLBACKS = "Placement source changed during callbacks";
    public static final String MSG_PLACEMENT_STOPPED_BY_NESTED_FAILURE = "Placement stopped by a nested failure";
    public static final String MSG_UNCOMMITTED_BED_SOURCE_RECORD_RETAINED = "Uncommitted bed source record retained; no automatic cleanup or reissue";
    public static final String MSG_AMBIGUOUS_HEAD_IDENTITY_OR_CHANGED = "Ambiguous head identity or changed foot retained";
    public static final String MSG_CHANGED_BED_HEAD_RETAINED = "Changed bed head retained";
    public static final String MSG_EXISTING_BED_STATE_RETAINED = "Existing bed state retained";
    public static final String MSG_BED_PLACEMENT_MARKER_CHANGED = "Bed placement marker changed";
    public static final String MSG_UNCOMMITTED_BED_CANNOT_TRANSFER_AN = "Uncommitted bed cannot transfer an item";
    public static final String MSG_AUTHORITATIVE_BED_HEAD_REQUIRED_FOR = "Authoritative bed head required for item transfer";
    public static final String MSG_RELOADED_BED_ITEM_TRANSFER_STATE = "Reloaded bed item transfer state retained at {},{},{}";
    public static final String MSG_BED_ITEM_OWNERSHIP_NEEDS_REVIEW = "Bed item ownership needs review; no automatic reissue";

    private MaidBedDiagnostics() {}
}
