/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/molang/runtime/AssignableVariable.java
 * Original Molang engine: Unnamed Team, MIT; see licenses/UnnamedTeam-Molang-MIT.txt
 * Adaptation: Java 8-compatible collection and annotation types
 */
package com.github.tartaricacid.touhoulittlemaid.molang.runtime;

public interface AssignableVariable extends Variable {

    void assign(final ExecutionContext<?> context, Object value);
}
