/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/molang/parser/ast/ExpressionVisitor.java
 * Original Molang engine: Unnamed Team, MIT; see licenses/UnnamedTeam-Molang-MIT.txt
 * Adaptation: Java 8-compatible collection and annotation types
 */
/*
 * This file is part of molang, licensed under the MIT license
 * Copyright (c) 2021-2023 Unnamed Team
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.github.tartaricacid.touhoulittlemaid.molang.parser.ast;

import com.github.tartaricacid.touhoulittlemaid.molang.runtime.Function;

/**
 * An {@link Expression} visitor. Provides a way to add
 * functionalities to the expression interface and all
 * of its implementations.
 *
 * <p>
 * See the following example on visiting an expression:
 * </p>
 * 
 * <pre>
 * {@code
 *      Expression expr = ...;
 *      String str = expr.visit(new ToStringVisitor());
 * }
 * </pre>
 *
 * <p>
 * Please note that users MUST use {@link Expression#visit(ExpressionVisitor)}
 * and NOT ExpressionVisitor's {@link ExpressionVisitor#visit(Expression)}, because
 * it will not work as intended.
 * </p>
 *
 * @param <R> The visit result type
 * @since 3.0.0
 */
public interface ExpressionVisitor<R> {

    /**
     * Evaluate for the given unknown expression.
     *
     * @param expression The expression.
     * @return The result.
     * @since 3.0.0
     */
    R visit(final Expression expression);

    /**
     * Evaluate for double expression.
     *
     * @param expression The expression.
     * @return The result.
     * @since 3.0.0
     */
    default R visitDouble(final DoubleExpression expression) {
        return visit(expression);
    }

    /**
     * Evaluate for string expression.
     *
     * @param expression The expression.
     * @return The result.
     * @since 3.0.0
     */
    default R visitString(final StringExpression expression) {
        return visit(expression);
    }

    /**
     * Evaluate for identifier expression.
     *
     * @param expression The expression.
     * @return The result.
     * @since 3.0.0
     */
    default R visitIdentifier(final IdentifierExpression expression) {
        return visit(expression);
    }

    default R visitVariable(final VariableExpression expression) {
        return visit(expression);
    }

    default R visitAssignableVariable(final AssignableVariableExpression expression) {
        return visit(expression);
    }

    default R visitStruct(final StructAccessExpression expression) {
        return visit(expression);
    }

    /**
     * Evaluate for ternary conditional expression.
     *
     * @param expression The expression.
     * @return The result.
     * @since 3.0.0
     */
    default R visitTernaryConditional(final TernaryConditionalExpression expression) {
        return visit(expression);
    }

    /**
     * Evaluate for unary expression.
     *
     * @param expression The expression.
     * @return The result.
     * @since 3.0.0
     */
    default R visitUnary(final UnaryExpression expression) {
        return visit(expression);
    }

    /**
     * Evaluate for execution scope expression.
     *
     * @param expression The expression.
     * @return The result.
     * @since 3.0.0
     */
    default R visitExecutionScope(final ExecutionScopeExpression expression) {
        return visit(expression);
    }

    default Function buildExecutionScopeFunction(final ExecutionScopeExpression expression) {
        throw new UnsupportedOperationException("Unsupported expression type: " + expression);
    }

    /**
     * Evaluate for binary expression.
     *
     * @param expression The expression.
     * @return The result.
     * @since 3.0.0
     */
    default R visitBinary(final BinaryExpression expression) {
        return visit(expression);
    }

    /**
     * Evaluate for call expression.
     *
     * @param expression The expression.
     * @return The result.
     * @since 3.0.0
     */
    default R visitCall(final CallExpression expression) {
        return visit(expression);
    }

    /**
     * Evaluate for statement expression.
     *
     * @param expression The expression.
     * @return The result.
     * @since 3.0.0
     */
    default R visitStatement(final StatementExpression expression) {
        return visit(expression);
    }

}
