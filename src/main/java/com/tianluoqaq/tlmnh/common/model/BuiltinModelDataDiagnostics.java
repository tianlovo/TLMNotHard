package com.tianluoqaq.tlmnh.common.model;

/** 内置模型包解析、回退与选择常量（Diagnostics） */
public final class BuiltinModelDataDiagnostics {

    public static final String MSG_MISSING_TLMNH_BUILTIN_MODEL_CATALOG = "Missing TLMNH builtin model catalog";
    public static final String MSG_UNKNOWN_BUILTIN_MODEL_SCHEMA = "Unknown builtin model schema";
    public static final String MSG_DUPLICATE_BUILTIN_MODEL_ID = "Duplicate builtin model ID: ";
    public static final String MSG_CANNOT_READ_TLMNH_BUILTIN_MODEL = "Cannot read TLMNH builtin model catalog";
    public static final String MSG_MISSING_TLM_DEFAULT_MAID_MODEL = "Missing TLM default maid model";
    public static final String MSG_UNEXPECTED_BUILTIN_MODEL_INVENTORY = "Unexpected builtin model inventory";
    public static final String MSG_TLMNH_MODEL_CATALOG_READY_CHOICES = "TLMNH_MODEL_CATALOG_READY choices={} winefox={}";
    public static final String MSG_INVALID_BUILTIN_MODEL_ACTION = "Invalid builtin model action";
    public static final String MSG_BUILTIN_MODEL_C11_CAPACITY_EXCEEDED = "Builtin model C11 capacity exceeded; migrate the selection protocol";
    public static final String MSG_CHOICE_IS_NOT_IN_THE = "Choice is not in the builtin catalog";

    private BuiltinModelDataDiagnostics() {}
}
