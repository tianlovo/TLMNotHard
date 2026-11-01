package com.tianluoqaq.tlmnh.common.ai.faithful;

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
        if (walking) entries.add(new Entry(null, true, 1));
        entries.add(new Entry(null, false, 2));
    }

    public boolean tryStart(MaidRandomActor maid) {
        if (running != null) return false;
        for (var entry : entries) entry.order = -Math.pow(random.nextFloat(), (double) (1.0F / entry.weight));
        // 与原ShufflingList相同，后续抽样沿上次原地排序后的顺序分配，不重新建初始列表
        entries.sort(Comparator.comparingDouble(entry -> entry.order));
        for (var entry : entries) {
            if (entry.look != null) {
                if (!maid.tryRandomLook(entry.look, 5)) continue;
            } else if (entry.walk) {
                if (maid.hasRandomWalkTarget()) continue;
                var pos = maid.sampleRandomWalk(5, 3);
                if (pos == null) maid.eraseWalkTarget();
                else maid.setVectorWalkTarget(pos, .3F, 0);
                // 原RandomStroll即使候选为空也成功，由setOrErase清掉记忆
            } else deadline = maid.worldGameTime() + 30 + maid.nextWorldInt(31);
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
