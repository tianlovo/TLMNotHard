package com.tianluoqaq.tlmnh.api.event;

import net.minecraft.entity.EntityLivingBase;
import net.minecraftforge.event.entity.living.LivingEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import cpw.mods.fml.common.eventhandler.Cancelable;

/**
 * 1.7 平台的 Brain 选敌事件，对应现代 Forge 的可取消 BEHAVIOR_TARGET 变更。
 * 作为 Forge 事件总线契约，按平台惯例直接携带宿主实体 EntityMaid（公开 API 自包含规则的文档化例外）
 */
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
