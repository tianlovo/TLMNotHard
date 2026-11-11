package com.tianluoqaq.tlmnh.common.lifecycle.world;

/** 自然生成与世界存档级生命周期常量（Diagnostics） */
public final class LifecycleWorldDiagnostics {

    public static final String MSG_MAID_SOURCE_DIMENSION_IS_NOT = "Maid source dimension is not the registered native world";
    public static final String MSG_MAID_DESTINATION_DIMENSION_IS_NOT = "Maid destination dimension is not registered: ";
    public static final String MSG_MAID_DESTINATION_WORLD_IS_UNAVAILABLE = "Maid destination world is unavailable: ";
    public static final String MSG_MAID_IDENTITY_ALREADY_EXISTS_IN = "Maid identity already exists in the destination";
    public static final String MSG_NATIVE_ENTITY_FACTORY_DID_NOT = "Native entity factory did not recreate the same maid type";
    public static final String MSG_MAID_DIMENSION_CONTEXT_CHANGED_DURING = "Maid dimension context changed during native placement";
    public static final String MSG_MAID_DESTINATION_SPAWN_CALLBACK_REVOKED = "Maid destination spawn callback revoked the staged transfer";
    public static final String MSG_MAID_SOURCE_REMAINED_ACTIVE_AFTER = "Maid source remained active after native removal";
    public static final String MSG_INVALID_NATIVE_DIMENSION_MOVEMENT_FACTOR = "Invalid native dimension movement factor: ";
    public static final String MSG_NATIVE_DIMENSION_PLACEMENT_COORDINATES_ARE = "Native dimension placement coordinates are not finite";
    public static final String MSG_NATIVE_END_ENTRANCE_IS_UNAVAILABLE = "Native End entrance is unavailable";
    public static final String MSG_NATIVE_DESTINATION_TELEPORTER_IS_UNAVAILABLE = "Native destination teleporter is unavailable";
    public static final String MSG_MAID_TRANSFER_CANDIDATE_CHANGED_OWNERSHIP = "Maid transfer candidate changed ownership; leaving external state intact";
    public static final String MSG_MAID_TRANSFER_CANDIDATE_ID_BELONGS = "Maid transfer candidate ID belongs to a successor; leaving it intact";

    private LifecycleWorldDiagnostics() {}
}
