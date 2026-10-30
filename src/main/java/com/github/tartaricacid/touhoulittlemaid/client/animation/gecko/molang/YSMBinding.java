/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/client/animation/gecko/molang/YSMBinding.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: built-in animation frame data only; see SOURCE_PROVENANCE.md
 */
package com.github.tartaricacid.touhoulittlemaid.client.animation.gecko.molang;

import com.github.tartaricacid.touhoulittlemaid.client.animation.gecko.molang.functions.BoneRotation;
import com.github.tartaricacid.touhoulittlemaid.client.animation.gecko.molang.functions.SecondOrderFunction;
import com.github.tartaricacid.touhoulittlemaid.client.animation.gecko.molang.variable.MoveInputVariable;
import com.tianluoqaq.tlmnh.client.render.molang.MolangFrameBinding;
import com.tianluoqaq.tlmnh.client.render.molang.MolangQueryFrame.Slot;

/** TLM 内置动画使用的原命名空间，不访问外部 YSM 模组 */
public class YSMBinding extends MolangFrameBinding {

    public static final YSMBinding INSTANCE = new YSMBinding();

    private YSMBinding() {
        function("bone_rot", new BoneRotation());
        function("second_order", new SecondOrderFunction());
        frameVar("head_yaw", frame -> frame.netHeadYaw);
        frameVar("head_pitch", frame -> frame.headPitch);
        frameVar("is_close_eyes", frame -> frame.closeEyes);
        frameVar("food_level", frame -> frame.foodLevel);
        frameVar("has_helmet", frame -> frame.equipment(Slot.HEAD) != null);
        frameVar("has_chest_plate", frame -> frame.equipment(Slot.CHEST) != null);
        frameVar("has_leggings", frame -> frame.equipment(Slot.LEGS) != null);
        frameVar("has_boots", frame -> frame.equipment(Slot.FEET) != null);
        frameVar("has_mainhand", frame -> frame.equipment(Slot.MAINHAND) != null);
        frameVar("has_offhand", frame -> frame.equipment(Slot.OFFHAND) != null);
        frameVar("has_elytra", frame -> frame.hasElytra);
        frameVar("input_vertical", MoveInputVariable::getVertical);
        frameVar("input_horizontal", MoveInputVariable::getHorizontal);
    }
}
