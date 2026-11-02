package com.tianluoqaq.tlmnh.mixin;

import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.util.MovingObjectPosition;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import com.github.tartaricacid.touhoulittlemaid.event.EntityHurtEvent;
import com.tianluoqaq.tlmnh.common.combat.MaidArrowImpactState;

/** 仅过滤原生选出的当前碰撞；移动、阻力、重力及命中后处理继续由原方法执行 */
@Mixin(EntityArrow.class)
public abstract class MixinArrowImpact implements MaidArrowImpactState {

    @Unique
    private int tlmnh$ignoredImpacts;

    @ModifyVariable(method = "onUpdate", at = @At("STORE"), ordinal = 0, require = 0)
    private MovingObjectPosition tlmnh$filterImpact(MovingObjectPosition hit) {
        var filtered = EntityHurtEvent.onArrowImpact((EntityArrow) (Object) this, hit);
        if (hit != null && filtered == null) tlmnh$ignoredImpacts++;
        return filtered;
    }

    @Override
    public int tlmnh$ignoredImpacts() {
        return tlmnh$ignoredImpacts;
    }
}
