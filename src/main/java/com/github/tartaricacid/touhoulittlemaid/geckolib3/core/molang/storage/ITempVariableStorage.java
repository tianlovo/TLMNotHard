/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original:
 * src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/core/molang/storage/ITempVariableStorage.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: Java 8 collections and context boundary; see SOURCE_PROVENANCE.md
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.storage;

public interface ITempVariableStorage {

    void clearTemp();

    Object getTemp(int address);

    void setTemp(int address, Object value);
}
