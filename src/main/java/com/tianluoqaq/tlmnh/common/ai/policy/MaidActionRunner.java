package com.tianluoqaq.tlmnh.common.ai.policy;

import java.util.Objects;

/** 单实体移动/注视动作的控制权交接，不决定任务优先级或接管被动物品能力 */
public final class MaidActionRunner {

    public enum Owner {
        FOLLOW,
        HOME,
        PICKUP,
        LIFE,
        COMBAT
    }

    private Frame frame;
    private MaidActionRun current;
    private Owner owner;
    private Runnable cancelCurrent;
    private boolean changing, transitionRefused;

    public void beginFrame(MaidPolicyContext context, MaidPolicyContext.Token token, Object world, Object command) {
        frame = context != null && context.isCurrent(token) && world != null && command != null
            ? new Frame(context, token, world, command)
            : null;
    }

    public MaidActionRun newRun(Object world, Object command) {
        var captured = frame;
        return captured != null && captured.world == world
            && captured.command == command
            && captured.context.isCurrent(captured.token)
                ? new MaidActionRun(captured.context, captured.token, world, command)
                : null;
    }

    public boolean claim(Owner requested, MaidActionRun proposed, Runnable onCancel) {
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(onCancel, "onCancel");
        if (changing) {
            transitionRefused = true;
            return false;
        }
        var captured = frame;
        if (!admitted(proposed, captured)) return false;
        if (current == proposed) return owner == requested;
        // 已开始清理的执行器必须先完成自己的release，不能在其取消回调栈中提前交接
        if (current != null && current.isCancelled()) return false;
        var old = current;
        var cleanup = cancelCurrent;
        current = null;
        owner = null;
        cancelCurrent = null;
        changing = true;
        transitionRefused = false;
        if (old != null) old.cancel();
        try {
            if (cleanup != null) cleanup.run();
            if (transitionRefused || frame != captured || !admitted(proposed, captured)) {
                proposed.cancel();
                return false;
            }
            current = proposed;
            owner = requested;
            cancelCurrent = onCancel;
            return true;
        } catch (RuntimeException | Error failure) {
            proposed.cancel();
            throw failure;
        } finally {
            changing = false;
        }
    }

    public boolean isOwned(MaidActionRun run, Object world, Object command) {
        return current != null && current == run
            && frame != null
            && frame.world == world
            && frame.command == command
            && admitted(run, frame);
    }

    /** 只释放调用者持有的实例；旧清理回调不能清掉后来获得的控制权 */
    public void release(MaidActionRun run) {
        if (run == null) return;
        run.cancel();
        if (current == run) {
            current = null;
            owner = null;
            cancelCurrent = null;
        }
    }

    /** 协调器完成各执行器清理后撤销余下身份，不在这里递归调用它们 */
    public void clear() {
        if (current != null) current.cancel();
        current = null;
        owner = null;
        cancelCurrent = null;
        frame = null;
        if (changing) transitionRefused = true;
    }

    public Owner getOwner() {
        return admitted(current, frame) ? owner : null;
    }

    public MaidActionRun getCurrentRun() {
        return getOwner() == null ? null : current;
    }

    private static boolean admitted(MaidActionRun run, Frame captured) {
        return run != null && captured != null
            && run.matches(captured.context, captured.token)
            && run.isCurrent(captured.world, captured.command);
    }

    private static final class Frame {

        private final MaidPolicyContext context;
        private final MaidPolicyContext.Token token;
        private final Object world, command;

        private Frame(MaidPolicyContext context, MaidPolicyContext.Token token, Object world, Object command) {
            this.context = context;
            this.token = token;
            this.world = world;
            this.command = command;
        }
    }
}
