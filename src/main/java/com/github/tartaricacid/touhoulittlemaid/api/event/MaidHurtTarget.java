/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: api/event/MaidHurtTarget.java, MIT
 * Adaptation: Forge 1.7 event bus and target entity types
 */
package com.github.tartaricacid.touhoulittlemaid.api.event;

import net.minecraft.entity.Entity;
import net.minecraftforge.event.entity.living.LivingEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import cpw.mods.fml.common.eventhandler.Cancelable;

public abstract class MaidHurtTarget extends LivingEvent {

    private final EntityMaid maid;
    private final Entity target;

    public MaidHurtTarget(EntityMaid maid, Entity target) {
        super(maid);
        this.maid = maid;
        this.target = target;
    }

    public EntityMaid getMaid() {
        return maid;
    }

    public Entity getTarget() {
        return target;
    }

    @Cancelable
    public static class Pre extends MaidHurtTarget {

        public Pre(EntityMaid maid, Entity target) {
            super(maid, target);
        }
    }

    public static class Post extends MaidHurtTarget {

        private final boolean hurt;

        public Post(EntityMaid maid, Entity target, boolean hurt) {
            super(maid, target);
            this.hurt = hurt;
        }

        public boolean isHurt() {
            return hurt;
        }
    }
}
