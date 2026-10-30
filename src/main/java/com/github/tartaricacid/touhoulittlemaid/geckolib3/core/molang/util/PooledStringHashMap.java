/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/core/molang/util/PooledStringHashMap.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: JDK map for pooled string IDs
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.util;

import java.util.HashMap;
import java.util.Map;

public class PooledStringHashMap<V> extends HashMap<Integer, V> {

    public PooledStringHashMap() {}

    public PooledStringHashMap(int initialCapacity) {
        super(initialCapacity);
    }

    public PooledStringHashMap(Map<Integer, V> m) {
        super(m);
    }

    public V get(String key) {
        int intKey = StringPool.getName(key);
        if (intKey == StringPool.NONE) {
            return null;
        } else {
            return super.get(intKey);
        }
    }

    public void put(String key, V value) {
        super.put(StringPool.computeIfAbsent(key), value);
    }
}
