/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/core/molang/storage/VariableStorage.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: Java 8 collections and context boundary; see SOURCE_PROVENANCE.md
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.storage;

import java.util.Arrays;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.util.PooledStringHashMap;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.util.PooledStringHashSet;

@SuppressWarnings("MapOrSetKeyShouldOverrideHashCodeEquals")
public class VariableStorage implements ITempVariableStorage, IScopedVariableStorage, IForeignVariableStorage {

    private static final int TEMP_INIT_CAPACITY = 16;
    private static final int SCOPED_INIT_CAPACITY = 16;

    private Object[] stackFrame = new Object[TEMP_INIT_CAPACITY];
    private final PooledStringHashMap<VariableValueHolder> scopedMap = new PooledStringHashMap<>(SCOPED_INIT_CAPACITY);
    private PooledStringHashMap<VariableValueHolder> publicMap = new PooledStringHashMap<>();

    public VariableStorage() {}

    public void clearTemp() {
        Arrays.fill(stackFrame, null);
    }

    private void ensureStackFrameSize(int size) {
        if (stackFrame.length >= size) {
            return;
        }
        if (size < stackFrame.length * 2) {
            size = stackFrame.length * 2;
        }
        stackFrame = Arrays.copyOf(stackFrame, size);
    }

    @Override
    public Object getTemp(int index) {
        ensureStackFrameSize(index + 1);
        return stackFrame[index];
    }

    @Override
    public void setTemp(int index, Object value) {
        ensureStackFrameSize(index + 1);
        stackFrame[index] = value;
    }

    @Override
    public Object getScoped(int name) {
        VariableValueHolder valueHolder = scopedMap.computeIfAbsent(name, n -> new VariableValueHolder());
        return valueHolder.value;
    }

    @Override
    public void setScoped(int name, Object value) {
        VariableValueHolder valueHolder = scopedMap.computeIfAbsent(name, n -> new VariableValueHolder());
        valueHolder.value = value;
    }

    @Override
    public Object getPublic(int name) {
        VariableValueHolder valueHolder = publicMap.get(name);
        if (valueHolder != null) {
            return valueHolder.value;
        } else {
            return null;
        }
    }

    // 注意 this.publicMap 线程安全
    public void initialize(PooledStringHashSet publicVariableNames) {
        clearTemp();
        scopedMap.clear();

        PooledStringHashMap<VariableValueHolder> newPublicMap = new PooledStringHashMap<>();
        if (publicVariableNames != null) {
            for (int publicVariableName : publicVariableNames) {
                VariableValueHolder value = new VariableValueHolder();
                scopedMap.put(publicVariableName, value);
                newPublicMap.put(publicVariableName, value);
            }
        }
        this.publicMap = newPublicMap;
    }

    private static class VariableValueHolder {

        public Object value = null;
    }
}
