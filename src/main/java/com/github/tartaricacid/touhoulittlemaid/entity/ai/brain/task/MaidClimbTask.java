/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/ai/brain/task/MaidClimbTask.java, MIT
 * Adaptation: guarded actor and current-navigation path view; outer component owns Behavior lifecycle
 */
package com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task;

import com.tianluoqaq.tlmnh.common.ai.faithful.MaidClimbActor;

public final class MaidClimbTask {

    public boolean checkExtraStartConditions(MaidClimbActor maid) {
        return maid.activeClimbing() && maid.onClimbable();
    }

    public void start(MaidClimbActor maid) {
        maid.centerOnBlock(maid.climbFeet());
        maid.setClimbMotion(0, maid.climbMotionY(), 0);
    }

    public boolean canStillUse(MaidClimbActor maid) {
        return checkExtraStartConditions(maid);
    }

    public void tick(MaidClimbActor maid) {
        var path = maid.climbPath();
        if (path == null || path.done()) {
            maid.setCanClimb(false);
            return;
        }
        maid.setCanClimb(true);
        int index = path.index();
        var next = path.node(index);
        var feet = maid.climbFeet();
        boolean up = true;
        if (index > 0 && next.y <= path.node(index - 1).y) up = false;
        maid.setShiftDown(!up);
        if (feet.y <= next.y && up && maid.feetAreLadder()) {
            maid.setClimbMotion(0, 1, 0);
            maid.setClimbMotion(0, .25, 0);
        } else {
            maid.setClimbMotion(0, -1, 0);
            maid.setClimbMotion(0, -.25, 0);
        }
        if (!up && next.y != feet.y) {
            int count = path.length();
            for (int i = 0; i < count; i++) {
                var node = path.node(i);
                var following = path.node(Math.min(i + 1, count - 1));
                if (node.y == feet.y && node.x == feet.x && node.z == feet.z && node.y == following.y) {
                    index = i;
                    next = node;
                    path.index(i);
                    break;
                }
            }
        }
        if (next.y - feet.y >= 0 && next.y - feet.y <= 1.2 && index + 1 < path.length()) {
            var current = path.node(index);
            var following = path.node(index + 1);
            boolean surface = following.y == current.y;
            if (surface || following == path.end() || feet.y == current.y) {
                maid.setCanClimb(false);
                int dx = following.x - current.x, dz = following.z - current.z;
                double y = maid.climbMotionY();
                maid.setClimbMotion(.2, 1, .2);
                maid.setClimbMotion(dx * .3, y + .012, dz * .3);
                maid.lookAtClimbNode(following);
            }
        }
    }

    public void stop(MaidClimbActor maid) {
        maid.setShiftDown(false);
        maid.setCanClimb(false);
    }
}
