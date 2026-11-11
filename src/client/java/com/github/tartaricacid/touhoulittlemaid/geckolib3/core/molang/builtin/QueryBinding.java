/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/core/molang/builtin/QueryBinding.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: pure query snapshots and Java 8; see SOURCE_PROVENANCE.md
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.builtin;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.builtin.query.ItemMaxDurability;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.builtin.query.ItemNameAny;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.builtin.query.ItemRemainingDurability;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.builtin.query.Position;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.builtin.query.PositionDelta;
import com.tianluoqaq.tlmnh.client.render.molang.MolangFrameBinding;

public class QueryBinding extends MolangFrameBinding {

    public static final QueryBinding INSTANCE = new QueryBinding();

    private QueryBinding() {
        function("is_item_name_any", new ItemNameAny());
        function("position", new Position());
        function("position_delta", new PositionDelta());
        function("max_durability", new ItemMaxDurability());
        function("remaining_durability", new ItemRemainingDurability());
        var(
            "anim_time",
            ctx -> ctx.animationControllerContext()
                .animTime());
        frameVar("life_time", frame -> frame.lifetime);
        frameVar("head_x_rotation", frame -> frame.netHeadYaw);
        frameVar("head_y_rotation", frame -> frame.headPitch);
        frameVar("body_y_rotation", frame -> frame.bodyYaw);
        frameVar("yaw_speed", frame -> frame.yawSpeed);
        frameVar("ground_speed", frame -> frame.groundSpeed);
        frameVar("vertical_speed", frame -> frame.verticalSpeed);
        frameVar("is_in_water", frame -> frame.inWater);
        frameVar("is_in_water_or_rain", frame -> frame.inWaterOrRain);
        frameVar("is_sneaking", frame -> frame.sneaking);
        frameVar("is_jumping", frame -> frame.jumping);
        frameVar("health", frame -> frame.health);
        frameVar("max_health", frame -> frame.maxHealth);
    }

}
