package com.tianluoqaq.tlmnh.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.util.DamageSource;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.combat.shield.MaidShieldArrowImpact;

/** 保留原生伤害返回值与随后体表箭数+1，只抵消这次调用已提交的捕获 */
@Mixin(EntityArrow.class)
public abstract class MixinEntityArrowShieldCapture implements MaidShieldArrowImpact {

    @Unique
    private EntityMaid tlmnh$shieldImpactTarget;
    @Unique
    private Object tlmnh$shieldImpactCall;
    @Unique
    private boolean tlmnh$shieldImpactCaptured;

    @Redirect(
        method = "onUpdate",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/entity/Entity;attackEntityFrom(Lnet/minecraft/util/DamageSource;F)Z"),
        require = 0,
        allow = 1)
    private boolean tlmnh$shieldArrowDamage(Entity target, DamageSource source, float amount) {
        var arrow = (EntityArrow) (Object) this;
        if (!(target instanceof EntityMaid maid) || arrow.worldObj == null
            || arrow.worldObj.isRemote
            || arrow.getClass() != EntityArrow.class) return target.attackEntityFrom(source, amount);
        var previous = tlmnh$shieldImpactTarget;
        var previousCall = tlmnh$shieldImpactCall;
        boolean capturedBefore = tlmnh$shieldImpactCaptured;
        tlmnh$shieldImpactTarget = maid;
        tlmnh$shieldImpactCall = new Object();
        tlmnh$shieldImpactCaptured = false;
        try {
            boolean accepted = target.attackEntityFrom(source, amount);
            if (accepted && tlmnh$shieldImpactCaptured) maid.setArrowCountInEntity(maid.getArrowCountInEntity() - 1);
            return accepted;
        } finally {
            tlmnh$shieldImpactTarget = previous;
            tlmnh$shieldImpactCall = previousCall;
            tlmnh$shieldImpactCaptured = capturedBefore;
        }
    }

    @Override
    public Object tlmnh$shieldImpactCall(EntityMaid target) {
        return tlmnh$isShieldImpactCurrent(target, tlmnh$shieldImpactCall) ? tlmnh$shieldImpactCall : null;
    }

    @Override
    public boolean tlmnh$isShieldImpactCurrent(EntityMaid target, Object call) {
        return target != null && call != null
            && tlmnh$shieldImpactTarget == target
            && tlmnh$shieldImpactCall == call
            && !tlmnh$shieldImpactCaptured;
    }

    @Override
    public boolean tlmnh$recordShieldCapture(EntityMaid target, Object call) {
        if (!tlmnh$isShieldImpactCurrent(target, call)) return false;
        tlmnh$shieldImpactCaptured = true;
        return true;
    }
}
