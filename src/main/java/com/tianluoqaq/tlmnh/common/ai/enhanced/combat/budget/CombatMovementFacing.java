package com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget;

/** 一条已下发MOVE_TO的单次朝向资格，不取得新的移动许可 */
public final class CombatMovementFacing {

    private CombatQueuedMove command;
    private long revision;
    private long retiredRevision;
    private boolean revisionRetired;
    private float yaw;

    public static final class Input {

        public final float forward, strafe;

        private Input(float forward, float strafe) {
            this.forward = forward;
            this.strafe = strafe;
        }
    }

    public boolean request(CombatQueuedMove requested, long expectedRevision, long actualRevision, boolean eligible,
        float admittedYaw) {
        if (!eligible || requested == null
            || expectedRevision != actualRevision
            || revisionRetired && actualRevision == retiredRevision
            || !Float.isFinite(admittedYaw)
            || command != null && command != requested
            || !requested.current()) return false;
        command = requested;
        revision = actualRevision;
        yaw = admittedYaw;
        return true;
    }

    public boolean consume(long actualRevision, boolean eligible, float currentYaw) {
        CombatQueuedMove requested = command;
        if (requested == null) return false;
        clear();
        if (revision != actualRevision || !eligible
            || Float.floatToRawIntBits(yaw) != Float.floatToRawIntBits(currentYaw)
            || !requested.consume()) {
            requested.rejected();
            return false;
        }
        return true;
    }

    public boolean cancel(CombatQueuedMove expected) {
        if (expected == null || command != expected) return false;
        clear();
        return true;
    }

    public void clear() {
        if (command != null) {
            retiredRevision = revision;
            revisionRetired = true;
        }
        command = null;
    }

    /** 原MOVE_TO世界heading与速度投影到已准入身体朝向，保持原生moveFlying的世界输入向量 */
    public static Input resolve(float magnitude, float movementSin, float movementCos, float bodySin, float bodyCos) {
        if (!Float.isFinite(magnitude) || magnitude < 0
            || !Float.isFinite(movementSin)
            || !Float.isFinite(movementCos)
            || !Float.isFinite(bodySin)
            || !Float.isFinite(bodyCos)) return null;
        double determinant = (double) bodySin * bodySin + (double) bodyCos * bodyCos;
        if (!(determinant > 0) || movementSin == 0 && movementCos == 0) return null;
        // 使用调用方同一原生sin/cos基底，避免将查表误差引入世界方向
        double forward = magnitude * ((double) movementSin * bodySin + (double) movementCos * bodyCos) / determinant;
        double strafe = magnitude * (-(double) movementSin * bodyCos + (double) movementCos * bodySin) / determinant;
        float forwardInput = (float) forward, strafeInput = (float) strafe;
        if (!Float.isFinite(forwardInput) || !Float.isFinite(strafeInput)) return null;
        return new Input(forwardInput, strafeInput);
    }
}
