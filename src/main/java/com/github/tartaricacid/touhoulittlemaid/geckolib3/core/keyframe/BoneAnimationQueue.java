/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/core/keyframe/BoneAnimationQueue.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt and licenses/GeckoLib-MIT.txt
 * Adaptation: Java 8 collections and pure animation target; see SOURCE_PROVENANCE.md
 */
/*
 * Copyright (c) 2020.
 * Author: Bernie G. (Gecko)
 */

package com.github.tartaricacid.touhoulittlemaid.geckolib3.core.keyframe;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.snapshot.BoneSnapshot;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.snapshot.BoneTopLevelSnapshot;

public class BoneAnimationQueue {

    public final BoneTopLevelSnapshot topLevelSnapshot;
    public final BoneSnapshot controllerSnapshot;
    public BoneAnimation animation;

    public AnimationPointQueue rotationQueue = new AnimationPointQueue();
    public AnimationPointQueue positionQueue = new AnimationPointQueue();
    public AnimationPointQueue scaleQueue = new AnimationPointQueue();

    public BoneAnimationQueue(BoneTopLevelSnapshot snapshot) {
        topLevelSnapshot = snapshot;
        controllerSnapshot = new BoneSnapshot(snapshot);
    }

    public BoneSnapshot snapshot() {
        return controllerSnapshot;
    }

    public AnimationPointQueue rotationQueue() {
        return rotationQueue;
    }

    public AnimationPointQueue positionQueue() {
        return positionQueue;
    }

    public AnimationPointQueue scaleQueue() {
        return scaleQueue;
    }

    public void updateSnapshot() {
        controllerSnapshot.copyFrom(topLevelSnapshot);
    }

    // 链表重开比 clear() 快
    public void resetQueues() {
        rotationQueue = new AnimationPointQueue();
        positionQueue = new AnimationPointQueue();
        scaleQueue = new AnimationPointQueue();
    }
}
