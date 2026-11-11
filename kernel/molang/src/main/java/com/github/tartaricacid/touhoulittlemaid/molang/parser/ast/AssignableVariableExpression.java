/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/molang/parser/ast/AssignableVariableExpression.java
 * Original Molang engine: Unnamed Team, MIT; see licenses/UnnamedTeam-Molang-MIT.txt
 * Adaptation: Java 8-compatible collection and annotation types
 */
package com.github.tartaricacid.touhoulittlemaid.molang.parser.ast;

import java.util.Objects;

import com.github.tartaricacid.touhoulittlemaid.molang.runtime.AssignableVariable;

public class AssignableVariableExpression implements Expression {

    private final AssignableVariable target;

    public AssignableVariableExpression(AssignableVariable target) {
        Objects.requireNonNull(target, "target");
        this.target = target;
    }

    public AssignableVariable target() {
        return target;
    }

    @Override
    public <R> R visit(final ExpressionVisitor<R> visitor) {
        return visitor.visitAssignableVariable(this);
    }

    @Override
    public String toString() {
        return target.toString();
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AssignableVariableExpression that = (AssignableVariableExpression) o;
        return target.equals(that.target);
    }

    @Override
    public int hashCode() {
        return target.hashCode();
    }
}
