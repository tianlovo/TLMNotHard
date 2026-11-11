package com.tianluoqaq.tlmnh.api.behavior;

import java.util.Objects;

/** 行为模块稳定标识，统一采用 namespace:path 规范格式 */
public final class MaidBehaviorId implements Comparable<MaidBehaviorId> {

    public static final String BUILTIN_NAMESPACE = BehaviorApiIds.BUILTIN_NAMESPACE;

    private final String namespace;
    private final String path;
    private final String canonical;

    public MaidBehaviorId(String namespace, String path) {
        this.namespace = validateNamespace(namespace);
        this.path = validatePath(path);
        this.canonical = this.namespace + BehaviorApiIds.COLON_DELIMITER + this.path;
    }

    public static MaidBehaviorId of(String namespace, String path) {
        return new MaidBehaviorId(namespace, path);
    }

    public static MaidBehaviorId builtin(String path) {
        return new MaidBehaviorId(BehaviorApiIds.BUILTIN_NAMESPACE, path);
    }

    public static MaidBehaviorId parse(String raw) {
        Objects.requireNonNull(raw, BehaviorApiIds.ID_RAW);
        int colon = raw.indexOf(':');
        if (colon <= 0 || colon != raw.lastIndexOf(':') || colon == raw.length() - 1)
            throw new IllegalArgumentException(BehaviorApiDiagnostics.MSG_INVALID_BEHAVIOR_ID + raw);
        return new MaidBehaviorId(
            raw.substring(BehaviorApiTuning.SUBSTRING_START_OFFSET, colon),
            raw.substring(colon + 1));
    }

    public String namespace() {
        return namespace;
    }

    public String path() {
        return path;
    }

    public boolean isBuiltin() {
        return BehaviorApiIds.BUILTIN_NAMESPACE.equals(namespace);
    }

    private static String validateNamespace(String value) {
        Objects.requireNonNull(value, BehaviorApiIds.ID_NAMESPACE);
        if (value.isEmpty() || value.length() > BehaviorApiTuning.MAX_NAMESPACE_LENGTH
            || BehaviorApiIds.DOT_SEPARATOR.equals(value)
            || BehaviorApiIds.BEHAVIOR_ID_VALIDATE_NAMESPACE_MESSAGE.equals(value))
            throw new IllegalArgumentException(BehaviorApiDiagnostics.MSG_INVALID_BEHAVIOR_NAMESPACE + value);
        for (int i = 0; i < value.length(); i++) {
            if (!isTokenChar(value.charAt(i))) throw new IllegalArgumentException(
                BehaviorApiDiagnostics.MSG_INVALID_BEHAVIOR_NAMESPACE_CHARACTER_IN + value);
        }
        return value;
    }

    private static String validatePath(String value) {
        Objects.requireNonNull(value, BehaviorApiIds.ID_PATH);
        if (value.isEmpty() || value.length() > BehaviorApiTuning.MAX_PATH_LENGTH)
            throw new IllegalArgumentException(BehaviorApiDiagnostics.MSG_INVALID_BEHAVIOR_PATH_LENGTH + value);
        if (value.charAt(BehaviorApiTuning.SUBSTRING_START_OFFSET) == '/' || value.charAt(value.length() - 1) == '/'
            || value.indexOf('\\') >= 0)
            throw new IllegalArgumentException(BehaviorApiDiagnostics.MSG_INVALID_BEHAVIOR_PATH_SLASH_BOUNDARY + value);
        int segmentStart = 0;
        for (int i = 0; i <= value.length(); i++) {
            if (i == value.length() || value.charAt(i) == '/') {
                int len = i - segmentStart;
                if (len <= 0)
                    throw new IllegalArgumentException(BehaviorApiDiagnostics.MSG_EMPTY_BEHAVIOR_PATH_SEGMENT + value);
                if (len == BehaviorApiTuning.TARGET_LEN_THRESHOLD && value.charAt(segmentStart) == '.'
                    || len == BehaviorApiTuning.TARGET_LEN_THRESHOLD_2 && value.charAt(segmentStart) == '.'
                        && value.charAt(segmentStart + 1) == '.')
                    throw new IllegalArgumentException(BehaviorApiDiagnostics.MSG_RELATIVE_DOT_SEGMENT_IS_NOT + value);
                segmentStart = i + 1;
            } else if (!isTokenChar(value.charAt(i))) {
                throw new IllegalArgumentException(
                    BehaviorApiDiagnostics.MSG_INVALID_BEHAVIOR_PATH_CHARACTER_IN + value);
            }
        }
        return value;
    }

    private static boolean isTokenChar(char ch) {
        return ch >= 'a' && ch <= 'z' || ch >= '0' && ch <= '9' || ch == '_' || ch == '.' || ch == '-';
    }

    @Override
    public int compareTo(MaidBehaviorId other) {
        int ns = namespace.compareTo(other.namespace);
        return ns != 0 ? ns : path.compareTo(other.path);
    }

    @Override
    public boolean equals(Object obj) {
        return this == obj || obj instanceof MaidBehaviorId other && canonical.equals(other.canonical);
    }

    @Override
    public int hashCode() {
        return canonical.hashCode();
    }

    @Override
    public String toString() {
        return canonical;
    }
}
