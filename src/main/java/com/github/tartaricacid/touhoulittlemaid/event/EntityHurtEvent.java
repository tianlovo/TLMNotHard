/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: event/EntityHurtEvent.java, MIT
 * Adaptation: native arrow collision-result hook; only this mod's shooter; guarded pre-damage fallback
 */
package com.github.tartaricacid.touhoulittlemaid.event;

import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.event.entity.living.LivingAttackEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.combat.MaidProjectileImpactProtection;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

public final class EntityHurtEvent {

    public static MovingObjectPosition onArrowImpact(EntityArrow arrow, MovingObjectPosition hit) {
        if (hit != null && hit.entityHit != null
            && arrow.shootingEntity instanceof EntityMaid maid
            && MaidProjectileImpactProtection.ignore(maid, hit.entityHit)) return null;
        return hit;
    }

    /** 没有可用碰撞挂钩时仍在原生伤害之前保护友方，停箭而不修改或补回生命 */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void beforeArrowDamage(LivingAttackEvent event) {
        if (event.entityLiving.worldObj.isRemote || !(event.source.getSourceOfDamage() instanceof EntityArrow arrow)
            || !(arrow.shootingEntity instanceof EntityMaid maid)) return;
        if (MaidProjectileImpactProtection.ignore(maid, event.entityLiving)) {
            event.setCanceled(true);
            arrow.setDead();
            MaidProjectileImpactProtection.missedImpact(maid);
        }
    }
}
