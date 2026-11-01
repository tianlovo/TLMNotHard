package com.tianluoqaq.tlmnh.common.ai.policy;

import java.util.Objects;
import java.util.UUID;

/** 单实体运行身份；数值 epoch 供诊断，授权校验使用不可复用的 token 身份 */
public final class MaidPolicyContext {

    private final UUID maidId;
    private long epoch;
    private Token token;
    private boolean active = true;
    private boolean retired;
    private Object decisionFrame;

    MaidPolicyContext(UUID maidId) {
        this.maidId = Objects.requireNonNull(maidId, "maidId");
        token = new Token(maidId, epoch);
    }

    public UUID getMaidId() {
        return maidId;
    }

    public long getEpoch() {
        return epoch;
    }

    public Token getToken() {
        return token;
    }

    public boolean isCurrent(Token candidate) {
        return active && candidate == token;
    }

    void invalidate() {
        decisionFrame = null;
        active = false;
        if (epoch < Long.MAX_VALUE) epoch++;
        token = new Token(maidId, epoch);
    }

    void setActive(boolean active) {
        this.active = active && !retired;
    }

    void retire() {
        decisionFrame = null;
        retired = true;
        active = false;
    }

    Object beginDecisionFrame(Token expected) {
        if (!isCurrent(expected)) return null;
        decisionFrame = new Object();
        return decisionFrame;
    }

    boolean isDecisionFrame(Token expected, Object frame) {
        return frame != null && decisionFrame == frame && isCurrent(expected);
    }

    public static final class Token {

        private final UUID maidId;
        private final long epoch;

        private Token(UUID maidId, long epoch) {
            this.maidId = maidId;
            this.epoch = epoch;
        }

        public UUID getMaidId() {
            return maidId;
        }

        public long getEpoch() {
            return epoch;
        }
    }
}
