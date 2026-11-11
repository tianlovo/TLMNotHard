/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/molang/runtime/HashMapStruct.java
 * Original Molang engine: Unnamed Team, MIT; see licenses/UnnamedTeam-Molang-MIT.txt
 * Adaptation: Java 8-compatible collection and annotation types
 */
package com.github.tartaricacid.touhoulittlemaid.molang.runtime;

import java.util.Map;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.util.PooledStringHashMap;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.util.StringPool;

public class HashMapStruct implements Struct {

    private final PooledStringHashMap<Object> properties;
    private final boolean isRightValue;

    public HashMapStruct() {
        this(false);
    }

    public HashMapStruct(boolean isRightValue) {
        this.properties = new PooledStringHashMap<>();
        this.isRightValue = isRightValue;
    }

    private HashMapStruct(PooledStringHashMap<Object> properties) {
        this.properties = properties;
        this.isRightValue = false;
    }

    @Override
    public Object getProperty(int name) {
        return properties.get(name);
    }

    @Override
    public void putProperty(int name, Object value) {
        properties.put(name, value);
    }

    @Override
    public Struct copy() {
        if (isRightValue) {
            return new HashMapStruct(properties);
        } else {
            return new HashMapStruct(new PooledStringHashMap<>(properties));
        }
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("struct{");
        boolean first = true;
        for (Map.Entry<Integer, Object> entry : properties.entrySet()) {
            if (!first) {
                builder.append(", ");
            }
            first = false;
            builder.append(
                String.format(
                    "%s=%s",
                    StringPool.getString(entry.getKey()),
                    entry.getValue() == null ? "null"
                        : entry.getValue()
                            .toString()));
        }
        builder.append("}");
        return builder.toString();
    }
}
