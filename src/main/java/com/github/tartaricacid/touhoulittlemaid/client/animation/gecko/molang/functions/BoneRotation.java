/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original:
 * src/main/java/com/github/tartaricacid/touhoulittlemaid/client/animation/gecko/molang/functions/BoneRotation.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: built-in animation frame data only; see SOURCE_PROVENANCE.md
 */
package com.github.tartaricacid.touhoulittlemaid.client.animation.gecko.molang.functions;

import com.github.tartaricacid.touhoulittlemaid.client.animation.gecko.molang.struct.Vec3fStruct;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.processor.IBone;

public final class BoneRotation extends BoneParamFunction {

    @Override
    protected Vec3fStruct getParam(IBone bone) {
        return new BoneRotationStruct(bone);
    }

    private static final class BoneRotationStruct extends Vec3fStruct {

        private final IBone bone;

        public BoneRotationStruct(IBone bone) {
            this.bone = bone;
        }

        @Override
        protected float getX() {
            return -(float) Math.toDegrees(bone.getRotationX());
        }

        @Override
        protected float getY() {
            return -(float) Math.toDegrees(bone.getRotationY());
        }

        @Override
        protected float getZ() {
            return (float) Math.toDegrees(bone.getRotationZ());
        }
    }
}
