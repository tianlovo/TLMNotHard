/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/core/molang/util/PooledStringHashSet.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: Java 8 collections and context boundary; see SOURCE_PROVENANCE.md
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.util;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

public class PooledStringHashSet extends HashSet<Integer> {

    public PooledStringHashSet() {}

    public PooledStringHashSet(int initialCapacity) {
        super(initialCapacity);
    }

    public PooledStringHashSet(Collection<String> m) {
        super(
            m.stream()
                .map(StringPool::computeIfAbsent)
                .collect(Collectors.toSet()));
    }

    public PooledStringHashSet(Set<Integer> m) {
        super(m);
    }

    public boolean contains(String key) {
        int intKey = StringPool.getName(key);
        if (intKey == StringPool.NONE) {
            return false;
        } else {
            return super.contains(intKey);
        }
    }

    public boolean add(String key) {
        return super.add(StringPool.computeIfAbsent(key));
    }
}
