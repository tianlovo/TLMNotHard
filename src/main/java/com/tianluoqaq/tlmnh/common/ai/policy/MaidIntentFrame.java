package com.tianluoqaq.tlmnh.common.ai.policy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** 本轮合法意图的快照；建议本身不授予执行器或物品提交权限 */
public final class MaidIntentFrame {

    public enum Group {
        COMMAND,
        CARE,
        AUTONOMOUS
    }

    public enum Kind {
        FOLLOW,
        HOME,
        SLEEP,
        SNACK,
        PICKUP
    }

    private final MaidPolicyContext context;
    private final MaidPolicyContext.Token token;
    private final long tick;
    private final Object identity;
    private final List<Intent> options = new ArrayList<>();
    private int kindMask;

    public MaidIntentFrame(MaidPolicyContext context, MaidPolicyContext.Token token, long tick) {
        this.context = Objects.requireNonNull(context, "context");
        this.token = token;
        this.tick = tick;
        identity = context.beginDecisionFrame(token);
    }

    public Intent offer(Group group, Kind kind, String target, double utility, Object payload) {
        if (!Double.isFinite(utility) || utility < 0 || utility > 1)
            throw new IllegalArgumentException("Utility must be finite and normalized");
        var intent = new Intent(this, group, kind, target, utility, payload);
        options.add(intent);
        kindMask |= 1 << kind.ordinal();
        return intent;
    }

    public int kindMask() {
        return kindMask;
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
        public final Group group;
        public final Kind kind;
        public final String target;
        public final double utility;
        public final Object payload;

        private Intent(MaidIntentFrame frame, Group group, Kind kind, String target, double utility, Object payload) {
            this.frame = frame;
            this.group = Objects.requireNonNull(group, "group");
            this.kind = Objects.requireNonNull(kind, "kind");
            this.target = Objects.requireNonNull(target, "target");
            this.utility = utility;
            this.payload = payload;
        }

        boolean sameGoal(Intent other) {
            return other != null && group == other.group && kind == other.kind && target.equals(other.target);
        }
    }
}
