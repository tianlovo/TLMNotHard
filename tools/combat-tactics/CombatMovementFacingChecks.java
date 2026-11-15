import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatMovementFacing;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatQueuedMove;

/** 不创建Minecraft实体，核原生世界输入分解及单次命令资格 */
public final class CombatMovementFacingChecks {

    public static void main(String[] args) {
        worldVectorAndSpeed();
        oneShotRevisionAndCancellation();
        expiryForeignInputAndModeChanges();
        System.out.println("CombatMovementFacingChecks: vector and exact one-shot command controls passed; no native game");
    }

    private static void worldVectorAndSpeed() {
        for (float bodyYaw : new float[] { -180, -90, 0, 45, 90, 179, 360 }) {
            for (float movementYaw : new float[] { -180, -90, 0, 90, 179 }) {
                for (float magnitude : new float[] { 0, .1F, .37F, 1.4F }) {
                    float bodySin = (float) Math.sin(Math.toRadians(bodyYaw));
                    float bodyCos = (float) Math.cos(Math.toRadians(bodyYaw));
                    float movementSin = (float) Math.sin(Math.toRadians(movementYaw));
                    float movementCos = (float) Math.cos(Math.toRadians(movementYaw));
                    var input = CombatMovementFacing.resolve(magnitude, movementSin, movementCos, bodySin, bodyCos);
                    check(input != null, "Finite native basis lost movement input");
                    near(input.strafe * bodyCos - input.forward * bodySin, -magnitude * movementSin,
                        "Facing changed world X input");
                    near(input.forward * bodyCos + input.strafe * bodySin, magnitude * movementCos,
                        "Facing changed world Z input");
                    near(Math.hypot(input.forward, input.strafe), magnitude, "Facing changed movement magnitude");
                }
            }
        }
        // 基底包含原生查表/float舍入时也用逆变换，不把单位圆近似误差叠入世界方向
        var rounded = CombatMovementFacing.resolve(.37F, .7070F, -.7072F, .5001F, .8660F);
        near(rounded.strafe * .8660F - rounded.forward * .5001F, -.37F * .7070F,
            "Rounded native basis changed world X");
        near(rounded.forward * .8660F + rounded.strafe * .5001F, .37F * -.7072F,
            "Rounded native basis changed world Z");
        check(CombatMovementFacing.resolve(-1, 0, 1, 0, 1) == null, "Negative speed acquired facing input");
        check(CombatMovementFacing.resolve(1, Float.NaN, 1, 0, 1) == null, "Unknown direction became an input");
        check(CombatMovementFacing.resolve(1, 0, 1, 0, 0) == null, "Singular body basis became an input");
        check(CombatMovementFacing.resolve(1, 0, 0, 0, 1) == null, "Missing movement basis became an input");
        check(CombatMovementFacing.resolve(Float.POSITIVE_INFINITY, 0, 1, 0, 1) == null,
            "Non-finite movement acquired facing input");
    }

    private static void oneShotRevisionAndCancellation() {
        var facing = new CombatMovementFacing();
        var rejected = new AtomicInteger();
        var command = new CombatQueuedMove(() -> true, value -> rejected.incrementAndGet());
        check(facing.request(command, 7, 7, true, 90), "Current ordinary command was refused");
        var foreign = new CombatQueuedMove(() -> true, value -> rejected.incrementAndGet());
        check(!facing.request(foreign, 7, 7, true, 90), "Foreign command replaced the current same-revision request");
        check(!facing.cancel(foreign), "Foreign cancellation released the current request");
        check(facing.consume(7, true, 90), "Exact current request was lost");
        check(!facing.consume(7, true, 90), "Facing command was replayed");
        check(!facing.request(foreign, 7, 7, true, 90), "Consumed source revision acquired another facing command");
        check(rejected.get() == 0, "Successful or foreign operation rejected the owned command");
        var next = new CombatQueuedMove(() -> true, value -> rejected.incrementAndGet());
        check(facing.request(next, 8, 8, true, 90) && facing.cancel(next), "Exact cancellation failed");
        check(!facing.consume(8, true, 90), "Canceled facing remained consumable");
        check(!facing.request(next, 8, 8, true, 90), "Canceled source revision was readmitted");
    }

    private static void expiryForeignInputAndModeChanges() {
        var facing = new CombatMovementFacing();
        var current = new AtomicBoolean(true);
        var rejected = new AtomicInteger();
        var expired = new CombatQueuedMove(current::get, value -> rejected.incrementAndGet());
        check(facing.request(expired, 9, 9, true, 10), "Expiry fixture failed to admit the original request");
        current.set(false);
        check(!facing.consume(9, true, 10) && rejected.get() == 1, "Expired Actor/path proof retained facing");
        var revised = new CombatQueuedMove(() -> true, value -> rejected.incrementAndGet());
        check(!facing.request(revised, 9, 10, true, 10), "Foreign setter revision acquired facing");
        check(facing.request(revised, 10, 10, true, 10), "Revision fixture failed to admit request");
        check(!facing.consume(11, true, 10), "Replaced setter retained facing");
        var mode = new CombatQueuedMove(() -> true, value -> rejected.incrementAndGet());
        check(!facing.request(mode, 12, 12, false, 10), "Non-ordinary or water/jump mode admitted facing");
        check(facing.request(mode, 12, 12, true, 10), "Mode-change fixture failed to admit request");
        check(!facing.consume(12, false, 10), "Water/jump or non-ordinary transition retained facing");
        var yaw = new CombatQueuedMove(() -> true, value -> rejected.incrementAndGet());
        check(facing.request(yaw, 13, 13, true, 10), "Yaw fixture failed to admit request");
        check(!facing.consume(13, true, 20), "Foreign body rotation was overwritten");
        var cleared = new CombatQueuedMove(() -> true, value -> rejected.incrementAndGet());
        check(facing.request(cleared, 14, 14, true, 10), "Clear fixture failed to admit request");
        facing.clear();
        check(!facing.consume(14, true, 10), "Ordinary setter clear retained combat facing");
        check(!facing.consume(15, true, 10), "Ordinary mode without a request acquired facing");
    }

    private static void near(double actual, double expected, String message) {
        check(Math.abs(actual - expected) <= 2e-6, message + ": " + actual + " versus " + expected);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
