/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/molang/runtime/Struct.java
 * Original Molang engine: Unnamed Team, MIT; see licenses/UnnamedTeam-Molang-MIT.txt
 * Adaptation: Java 8-compatible collection and annotation types
 */
package com.github.tartaricacid.touhoulittlemaid.molang.runtime;

public interface Struct {

    Object getProperty(int name);

    void putProperty(int name, Object value);

    // FIXME: 作为 foreign 变量被访问时有线程安全问题
    Struct copy();
}
