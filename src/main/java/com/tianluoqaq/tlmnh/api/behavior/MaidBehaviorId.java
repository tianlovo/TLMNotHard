package com.tianluoqaq.tlmnh.api.behavior;

import java.util.Objects;

/** 行为模块稳定标识，统一采用 namespace:path 规范格式 */
public final class MaidBehaviorId implements Comparable<MaidBehaviorId> {

    public static final String BUILTIN_NAMESPACE = "touhou_little_maid";
    private static final int MAX_NAMESPACE_LENGTH = 64;
    private static final int MAX_PATH_LENGTH = 128;

    private final String namespace;
    private final String path;
    private final String canonical;

    public MaidBehaviorId(String namespace, String path) {
        this.namespace = validateNamespace(namespace);
        this.path = validatePath(path);
        this.canonical = this.namespace + ":" + this.path;
    }

    public static MaidBehaviorId of(String namespace, String path) {
        return new MaidBehaviorId(namespace, path);
    }

    public static MaidBehaviorId builtin(String path) {
        return new MaidBehaviorId(BUILTIN_NAMESPACE, path);
    }

    public static MaidBehaviorId parse(String raw) {
        Objects.requireNonNull(raw, "raw");
        int colon = raw.indexOf(':');
        if (colon <= 0 || colon != raw.lastIndexOf(':') || colon == raw.length() - 1)
            throw new IllegalArgumentException("Invalid behavior id: " + raw);
        return new MaidBehaviorId(raw.substring(0, colon), raw.substring(colon + 1));
    }

    public String namespace() {
        return namespace;
    }

    public String path() {
        return path;
    }

    public boolean isBuiltin() {
        return BUILTIN_NAMESPACE.equals(namespace);
    }

    private static String validateNamespace(String value) {
        Objects.requireNonNull(value, "namespace");
        if (value.isEmpty() || value.length() > MAX_NAMESPACE_LENGTH || ".".equals(value) || "..".equals(value))
            throw new IllegalArgumentException("Invalid behavior namespace: " + value);
        for (int i = 0; i < value.length(); i++) {
            if (!isTokenChar(value.charAt(i)))
                throw new IllegalArgumentException("Invalid behavior namespace character in: " + value);
        }
        return value;
    }

    private static String validatePath(String value) {
        Objects.requireNonNull(value, "path");
        if (value.isEmpty() || value.length() > MAX_PATH_LENGTH)
            throw new IllegalArgumentException("Invalid behavior path length: " + value);
        if (value.charAt(0) == '/' || value.charAt(value.length() - 1) == '/' || value.indexOf('\\') >= 0)
            throw new IllegalArgumentException("Invalid behavior path slash boundary: " + value);
        int segmentStart = 0;
        for (int i = 0; i <= value.length(); i++) {
            if (i == value.length() || value.charAt(i) == '/') {
                int len = i - segmentStart;
                if (len <= 0) throw new IllegalArgumentException("Empty behavior path segment: " + value);
                if (len == 1 && value.charAt(segmentStart) == '.'
                    || len == 2 && value.charAt(segmentStart) == '.' && value.charAt(segmentStart + 1) == '.')
                    throw new IllegalArgumentException(
                        "Relative dot segment is not allowed in behavior path: " + value);
                segmentStart = i + 1;
            } else if (!isTokenChar(value.charAt(i))) {
                throw new IllegalArgumentException("Invalid behavior path character in: " + value);
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
