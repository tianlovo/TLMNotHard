package com.tianluoqaq.tlmnh.common.ai.faithful;

import net.minecraft.entity.EntityLivingBase;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

/** 按固定现代HurtBySensor字节码适配；无实体的新伤害不主动擦仍活同世界的旧攻击者 */
public final class MaidHurtBySensor {

    public void doTick(EntityMaid maid, MaidModernPerception memory) {
        var source = maid.getMaidLastDamageSource();
        memory.setHurtBy(source);
        if (source != null && source.getEntity() instanceof EntityLivingBase entity) memory.setHurtByEntity(entity);
        var attacker = memory.hurtByEntity();
        if (attacker != null && (!attacker.isEntityAlive() || attacker.worldObj != maid.worldObj))
            memory.setHurtByEntity(null);
    }
}
