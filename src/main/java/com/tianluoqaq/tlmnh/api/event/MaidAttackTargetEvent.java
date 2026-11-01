package com.tianluoqaq.tlmnh.api.event;

import net.minecraft.entity.EntityLivingBase;
import net.minecraftforge.event.entity.living.LivingEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import cpw.mods.fml.common.eventhandler.Cancelable;

/** 1.7平台的Brain选敌事件，对应现代Forge的可取消BEHAVIOR_TARGET变更 */
@Cancelable
public final class MaidAttackTargetEvent extends LivingEvent {

    private final EntityMaid maid;
    private final EntityLivingBase oldTarget;
    private EntityLivingBase newTarget;

    public MaidAttackTargetEvent(EntityMaid maid, EntityLivingBase oldTarget, EntityLivingBase newTarget) {
        super(maid);
        this.maid = maid;
        this.oldTarget = oldTarget;
        this.newTarget = newTarget;
    }

    public EntityMaid getMaid() {
        return maid;
    }

    public EntityLivingBase getOldTarget() {
        return oldTarget;
    }

    public EntityLivingBase getNewTarget() {
        return newTarget;
    }

    public void setNewTarget(EntityLivingBase target) {
        newTarget = target;
    }
}
