/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/ai/brain/task/MaidInteractWithDoor.java, MIT
 * Adaptation: guarded native path/memory and animated-door acceptance; native permissions remain in the block API
 */
package com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import com.tianluoqaq.tlmnh.common.ai.faithful.MaidDoorActor;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidDoorActor.Kind;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidDoorActor.Position;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point;

public final class MaidInteractWithDoor {

    private Point lastNode;
    private int cooldown;

    public boolean trigger(MaidDoorActor maid) {
        var path = maid.doorPath();
        if (path == null || path.notStarted() || path.done()) return false;
        var next = path.next();
        if (Objects.equals(lastNode, next)) cooldown = 3;
        else if (--cooldown > 0) return false;
        lastNode = next;
        var previous = path.previous();
        var positions = maid.doorsToClose();
        var prevPos = maid.doorPosition(previous);
        if (mayOpen(maid, prevPos)) {
            if (!maid.doorOpen(prevPos)) {
                maid.setDoorOpen(prevPos, true);
                if (!maid.isCurrent()) return true;
            }
            positions = remember(maid, positions, prevPos);
        }
        var nextPos = maid.doorPosition(next);
        if (mayOpen(maid, nextPos) && !maid.doorOpen(nextPos)) {
            maid.setDoorOpen(nextPos, true);
            if (!maid.isCurrent()) return true;
            positions = remember(maid, positions, nextPos);
        }
        if (positions != null) closeDoors(maid, previous, next, positions);
        return true;
    }

    private static boolean mayOpen(MaidDoorActor maid, Position pos) {
        var kind = maid.doorKind(pos);
        return kind == Kind.WOOD_DOOR && maid.canOpenDoor() || kind == Kind.FENCE_GATE && maid.canOpenFenceGate();
    }

    private static Set<Position> remember(MaidDoorActor maid, Set<Position> positions, Position pos) {
        if (positions == null) {
            positions = new HashSet<>();
            maid.rememberDoors(positions);
        }
        positions.add(pos);
        return positions;
    }

    private static void closeDoors(MaidDoorActor maid, Point previous, Point next, Set<Position> positions) {
        var iterator = positions.iterator();
        while (iterator.hasNext()) {
            var pos = iterator.next();
            var point = pos.point;
            if ((previous == null || !previous.equals(point)
                || next != null && maid.doorFeet()
                    .equals(next))
                && (next == null || !next.equals(point))) {
                if (maid.doorTooFar(pos, 8)) iterator.remove();
                else {
                    var kind = maid.doorKind(pos);
                    if (kind == Kind.NONE || !maid.doorOpen(pos) || maid.otherMaidsComingThrough(pos))
                        iterator.remove();
                    else {
                        boolean accepted = maid.setDoorOpen(pos, false);
                        if (!maid.isCurrent()) return;
                        // Malisis开门动画尚未完成时可能不接受关闭，保留本条等待下一次原任务尝试
                        if (kind == Kind.WOOD_DOOR && accepted) iterator.remove();
                        // 原栅栏门关闭分支本次不remove，下一次观察到关闭后才擦记忆
                    }
                }
            }
        }
    }
}
