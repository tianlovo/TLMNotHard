package com.tianluoqaq.tlmnh.common.protection;

/** 外部兼容指纹校验、账本与熔断守卫常量（Diagnostics） */
public final class ProtectionDiagnostics {

    public static final String MSG_EXISTING_COMPATIBILITY_LEDGER_UNREADABLE_NO = "Existing compatibility ledger unreadable; no replacement baseline";
    public static final String MSG_EXISTING_COMPATIBILITY_LEDGER_COULD_NOT = "Existing compatibility ledger could not be read; baseline not replaced";
    public static final String MSG_NEWER_COMPATIBILITY_SCHEMA_RETAINED_WITHOUT = "Newer compatibility schema retained without downgrade";
    public static final String MSG_DEPENDENCY_SOURCE_FINGERPRINT_UNAVAILABLE = "Dependency source fingerprint unavailable";
    public static final String MSG_MALFORMED_COMPATIBILITY_RECORD_RETAINED = "Malformed compatibility record retained";
    public static final String MSG_DEPENDENCY_ENVIRONMENT_CHANGED_FOR = "Dependency environment changed for ";
    public static final String MSG_STORED = "; stored=";
    public static final String MSG_CURRENT = "; current=";
    public static final String MSG_PROTECTED_FALLBACK_FOR_ORIGINAL_OWNED = "{}: {} protected fallback for {}; original owned data must remain intact";
    public static final String MSG_OPTIONAL_API_CLASS_MISSING_WHILE = "Optional API class missing while mod ";
    public static final String MSG_IS_LOADED = " is loaded: ";
    public static final String MSG_OPTIONAL_API_FIELD_SIGNATURE_MISMATCH = "Optional API field signature mismatch on ";
    public static final String MSG_EXPECTED_STATIC = ": expected static ";
    public static final String MSG_FOUND = ", found ";
    public static final String SINGLE_SPACE = " ";
    public static final String MSG_OPTIONAL_API_FIELD_MISSING_OR = "Optional API field missing or inaccessible: ";
    public static final String MSG_OPTIONAL_API_METHOD_SIGNATURE_MISMATCH = "Optional API method signature mismatch on ";
    public static final String MSG_EXPECTED = ": expected ";
    public static final String MSG_STATIC = "static ";
    public static final String MSG_INSTANCE = "instance ";
    public static final String MSG_OPTIONAL_API_METHOD_MISSING_OR = "Optional API method missing or inaccessible: ";
    public static final String MSG_SHA_256_MESSAGEDIGEST_UNAVAILABLE = "SHA-256 MessageDigest unavailable";

    private ProtectionDiagnostics() {}
}
