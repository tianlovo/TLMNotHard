package com.tianluoqaq.tlmnh.common.ai.policy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorId;

/** 本轮合法意图的快照；建议本身不授予执行器或物品提交权限 */
public final class MaidIntentFrame {

    public enum Group {
        EMERGENCY,
        DANGER,
        RECOVERY,
        COMBAT,
        COMMAND,
        CARE,
        AUTONOMOUS,
        AMBIENT
    }

    public enum Kind {
        FOLLOW,
        HOME,
        SLEEP,
        SNACK,
        PICKUP,
        BREATHE,
        HEAL,
        MELEE,
        RANGED,
        PANIC,
        VEHICLE,
        RIDE,
        MEAL,
        BEG,
        OBSERVE,
        WANDER,
        SNOWBALL,
        COMBAT,
        EXTENSION
    }

    private final MaidPolicyContext context;
    private final MaidPolicyContext.Token token;
    private final long tick;
    private final Object identity;
    private final List<Intent> options = new ArrayList<>();
    private int kindMask;
    private int groupMask;

    public MaidIntentFrame(MaidPolicyContext context, MaidPolicyContext.Token token, long tick) {
        this.context = Objects.requireNonNull(context, "context");
        this.token = token;
        this.tick = tick;
        identity = context.beginDecisionFrame(token);
    }

    public Intent offer(Group group, Kind kind, String target, double utility, Object payload) {
        validateUtility(utility);
        var intent = new Intent(this, null, group, kind, target, target, utility, payload, null);
        record(intent);
        return intent;
    }

    public <P> Intent offerTyped(MaidBehaviorId moduleId, Group group, Kind kind, String target, double utility,
        P payload, Consumer<? super P> action) {
        return offerTyped(moduleId, group, kind, target, target, utility, payload, action);
    }

    public <P> Intent offerTyped(MaidBehaviorId moduleId, Group group, Kind kind, String arbiterTarget,
        String rawTarget, double utility, P payload, Consumer<? super P> action) {
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(action, "action");
        if (!isCurrent()) throw new IllegalStateException("Decision frame is no longer current");
        validateUtility(utility);
        Runnable bound = () -> action.accept(payload);
        var intent = new Intent(this, moduleId, group, kind, arbiterTarget, rawTarget, utility, payload, bound);
        record(intent);
        return intent;
    }

    private static void validateUtility(double utility) {
        if (!Double.isFinite(utility) || utility < 0 || utility > 1)
            throw new IllegalArgumentException("Utility must be finite and normalized");
    }

    private void record(Intent intent) {
        options.add(intent);
        kindMask |= 1 << intent.kind.ordinal();
        groupMask |= 1 << intent.group.ordinal();
    }

    public int kindMask() {
        return kindMask;
    }

    public int groupMask() {
        return groupMask;
    }

    public boolean hasOffers() {
        return !options.isEmpty();
    }

    public boolean hasGroupOffer(Group group) {
        return (groupMask & (1 << group.ordinal())) != 0;
    }

    public boolean hasNonAmbientOffer() {
        return (groupMask & ~(1 << Group.AMBIENT.ordinal())) != 0;
    }

    public boolean hasModuleOffer(MaidBehaviorId moduleId) {
        if (moduleId == null) return false;
        for (var option : options) {
            if (moduleId.equals(option.moduleId)) return true;
        }
        return false;
    }

    /** 撤销当帧指定模块已登记的全部提案，用于模块在 offer 半途故障熔断时清理残余竞标 */
    public void revokeModuleOffers(MaidBehaviorId moduleId) {
        if (moduleId == null || options.isEmpty()) return;
        if (!options.removeIf(option -> moduleId.equals(option.moduleId))) return;
        int recomputedKinds = 0;
        int recomputedGroups = 0;
        for (var option : options) {
            recomputedKinds |= 1 << option.kind.ordinal();
            recomputedGroups |= 1 << option.group.ordinal();
        }
        kindMask = recomputedKinds;
        groupMask = recomputedGroups;
    }

    public boolean accepts(Intent intent) {
        return isCurrent() && intent != null && intent.frame == this && options.contains(intent);
    }

    public boolean isCurrent() {
        return context.isDecisionFrame(token, identity);
    }

    public long tick() {
        return tick;
    }

    MaidPolicyContext.Token token() {
        return token;
    }

    List<Intent> options() {
        return Collections.unmodifiableList(options);
    }

    public static final class Intent {

        private final MaidIntentFrame frame;
        private final MaidBehaviorId moduleId;
        private final String rawTarget;
        private final Runnable boundAction;
        public final Group group;
        public final Kind kind;
        public final String target;
        public final double utility;
        public final Object payload;

        private Intent(MaidIntentFrame frame, MaidBehaviorId moduleId, Group group, Kind kind, String target,
            String rawTarget, double utility, Object payload, Runnable boundAction) {
            this.frame = frame;
            this.moduleId = moduleId;
            this.group = Objects.requireNonNull(group, "group");
            this.kind = Objects.requireNonNull(kind, "kind");
            this.target = Objects.requireNonNull(target, "target");
            this.rawTarget = Objects.requireNonNull(rawTarget, "rawTarget");
            this.utility = utility;
            this.payload = payload;
            this.boundAction = boundAction;
        }

        public MaidBehaviorId moduleId() {
            return moduleId;
        }

        public String rawTarget() {
            return rawTarget;
        }

        public boolean hasBoundAction() {
            return boundAction != null;
        }

        public void execute() {
            if (!frame.accepts(this)) throw new IllegalStateException("Intent is no longer accepted by active frame");
            if (boundAction == null) throw new IllegalStateException("Intent has no typed execution action bound");
            boundAction.run();
        }

        boolean sameGoal(Intent other) {
            return other != null && group == other.group && kind == other.kind && target.equals(other.target);
        }
    }
}
