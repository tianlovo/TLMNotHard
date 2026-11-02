package com.tianluoqaq.tlmnh.common.combat.shield;

import java.util.UUID;
import java.util.function.BooleanSupplier;

import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.item.ItemStack;
import net.minecraft.util.DamageSource;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.compat.battlegear2.common.shield.BattlegearShieldAccess;
import com.tianluoqaq.tlmnh.core.MixinCompatibilityPlugin;

import cpw.mods.fml.relauncher.Side;

/** 已确认格挡后的原生箭效果，未知投射物不套用普通箭的消耗和体表计数时序 */
public final class MaidShieldArrowCapture {

    private MaidShieldArrowCapture() {}

    public static Pending prepare(EntityMaid maid, ItemStack shield, DamageSource source) {
        if (source == null || !source.isProjectile()
            || source.getSourceOfDamage() == null
            || source.getSourceOfDamage()
                .getClass() != EntityArrow.class)
            return null;
        var arrow = (EntityArrow) source.getSourceOfDamage();
        if (!registeredArrow(maid, arrow)) return null;
        if (!available(maid) || !(arrow instanceof MaidShieldArrowImpact impact)) {
            UUID owner = null;
            try {
                String id = maid.func_152113_b();
                if (id != null && !id.isEmpty()) owner = UUID.fromString(id);
            } catch (IllegalArgumentException invalidOwner) {
                // 诊断仍绑定本女仆，无法解析的原主人字段不在这里修复
            }
            IntegrationGuard.report(
                Side.SERVER,
                key(maid),
                new IllegalStateException(
                    "Native arrow shield capture hook unavailable; arrow and shield count retained"),
                owner);
            return null;
        }
        // 登记箭被其他代码直接用于伤害时没有原生碰撞调用权，不猜测其后续体表计数
        var call = impact.tlmnh$shieldImpactCall(maid);
        if (call == null) return null;
        var count = MaidShieldItems.prepareArrowCapture(shield);
        return count == null ? null : new Pending(maid, shield, arrow, count, call);
    }

    public static final class Pending {

        private final EntityMaid maid;
        private final ItemStack shield;
        private final EntityArrow arrow;
        private final BattlegearShieldAccess.ArrowCapture count;
        private final Object call;

        private Pending(EntityMaid maid, ItemStack shield, EntityArrow arrow, BattlegearShieldAccess.ArrowCapture count,
            Object call) {
            this.maid = maid;
            this.shield = shield;
            this.arrow = arrow;
            this.count = count;
            this.call = call;
        }

        public boolean commit(BooleanSupplier current) {
            if (!IntegrationGuard.available(ExternalFeature.SHIELDS) || !current.getAsBoolean()
                || !registeredArrow(maid, arrow)
                || !(arrow instanceof MaidShieldArrowImpact impact)
                || !impact.tlmnh$isShieldImpactCurrent(maid, call)) return false;
            var updated = count.applyTo(shield);
            if (updated == null) return false;
            shield.setTagCompound(updated);
            arrow.setDead();
            impact.tlmnh$recordShieldCapture(maid, call);
            return true;
        }
    }

    private static boolean registeredArrow(EntityMaid maid, EntityArrow arrow) {
        return maid.worldObj != null && !maid.worldObj.isRemote
            && arrow.worldObj == maid.worldObj
            && !arrow.isDead
            && maid.worldObj.getEntityByID(arrow.getEntityId()) == arrow;
    }

    public static boolean available(EntityMaid maid) {
        return MixinCompatibilityPlugin.hasShieldCaptureHook() && IntegrationGuard.available(Side.SERVER, key(maid));
    }

    private static String key(EntityMaid maid) {
        return "shield_arrow_capture/" + maid.getUniqueID();
    }
}
