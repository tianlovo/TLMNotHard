package com.tianluoqaq.tlmnh.common.ai.faithful.locomotion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/** 固定1.20.1 RunOne/GateBehavior/ShufflingList与本批六种原OneShot的生命周期 */
public class MaidRandomGate {

    private static final class Entry {

        final MaidRandomActor.LookKind look;
        final boolean walk;
        final int weight;
        double order;

        Entry(MaidRandomActor.LookKind look, boolean walk, int weight) {
            this.look = look;
            this.walk = walk;
            this.weight = weight;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Random random = new Random();
    private Entry running;
    private long deadline;

    public MaidRandomGate(boolean walking) {
        for (var kind : MaidRandomActor.LookKind.values()) entries.add(new Entry(kind, false, 1));
        if (walking) entries.add(new Entry(null, true, FaithfulLocomotionTuning.ENTRY_SINGLE_UNIT));
        entries.add(new Entry(null, false, FaithfulLocomotionTuning.RANDOM_GATE_ENTRY_THIRD_PARAM));
    }

    public boolean tryStart(MaidRandomActor maid) {
        if (running != null) return false;
        for (var entry : entries) entry.order = -Math
            .pow(random.nextFloat(), (double) (FaithfulLocomotionTuning.RANDOM_GATE_POW_SECOND_FACTOR / entry.weight));
        // 与原ShufflingList相同，后续抽样沿上次原地排序后的顺序分配，不重新建初始列表
        entries.sort(Comparator.comparingDouble(entry -> entry.order));
        for (var entry : entries) {
            if (entry.look != null) {
                if (!maid.tryRandomLook(entry.look, FaithfulLocomotionTuning.RANDOM_GATE_TRY_RANDOM_LOOK_SECOND_PARAM))
                    continue;
            } else if (entry.walk) {
                if (maid.hasRandomWalkTarget()) continue;
                var pos = maid.sampleRandomWalk(
                    FaithfulLocomotionTuning.RANDOM_GATE_TRY_RANDOM_LOOK_SECOND_PARAM,
                    FaithfulLocomotionTuning.RANDOM_GATE_SAMPLE_RANDOM_WALK_SECOND_PARAM);
                if (pos == null) maid.eraseWalkTarget();
                else maid.setVectorWalkTarget(
                    pos,
                    FaithfulLocomotionTuning.RANDOM_GATE_SET_VECTOR_WALK_TARGET_SECOND_FACTOR,
                    FaithfulLocomotionTuning.SET_VECTOR_WALK_TARGET_INITIAL_VALUE);
                // 原RandomStroll即使候选为空也成功，由setOrErase清掉记忆
            } else deadline = maid.worldGameTime() + FaithfulLocomotionTuning.RANDOM_GATE_DEADLINE
                + maid.nextWorldInt(FaithfulLocomotionTuning.DOOR_ACTOR_HASH_CODE_PARAM);
            running = entry;
            return true;
        }
        return true;
    }

    public void tickOrStop(MaidRandomActor maid) {
        if (running == null) return;
        if (running.look != null || running.walk || maid.worldGameTime() > deadline) running = null;
    }

    public void cancel() {
        running = null;
    }

    public boolean running() {
        return running != null;
    }
}
