/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: api/event/MaidPickupEvent.java, MIT
 * Adaptation: Forge 1.7.10 events; excluded non-core PowerPoint entity
 */
package com.github.tartaricacid.touhoulittlemaid.api.event;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.item.ItemStack;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import cpw.mods.fml.common.eventhandler.Event;

public abstract class MaidPickupEvent extends Event {

    private final EntityMaid maid;
    private final boolean simulate;
    private boolean canPickup;

    public MaidPickupEvent(EntityMaid maid, boolean simulate) {
        this.maid = maid;
        this.simulate = simulate;
    }

    public EntityMaid getMaid() {
        return maid;
    }

    public boolean isSimulate() {
        return simulate;
    }

    public boolean isCanPickup() {
        return canPickup;
    }

    public void setCanPickup(boolean value) {
        canPickup = value;
    }

    @Override
    public boolean isCancelable() {
        return true;
    }

    public static class ItemResultPre extends MaidPickupEvent {

        private final EntityItem entityItem;

        public ItemResultPre(EntityMaid maid, EntityItem item, boolean simulate) {
            super(maid, simulate);
            entityItem = item;
        }

        public EntityItem getEntityItem() {
            return entityItem;
        }
    }

    public static class ItemResultPost extends MaidPickupEvent {

        private final ItemStack pickupItem;

        public ItemResultPost(EntityMaid maid, ItemStack stack) {
            super(maid, false);
            pickupItem = stack;
        }

        public ItemStack getPickupItem() {
            return pickupItem;
        }

        @Override
        public boolean isCancelable() {
            return false;
        }
    }

    public static class ExperienceResult extends MaidPickupEvent {

        private final EntityXPOrb experienceOrb;

        public ExperienceResult(EntityMaid maid, EntityXPOrb orb, boolean simulate) {
            super(maid, simulate);
            experienceOrb = orb;
        }

        public EntityXPOrb getExperienceOrb() {
            return experienceOrb;
        }
    }

    public static class ArrowResult extends MaidPickupEvent {

        private final EntityArrow arrow;

        public ArrowResult(EntityMaid maid, EntityArrow arrow, boolean simulate) {
            super(maid, simulate);
            this.arrow = arrow;
        }

        public EntityArrow getArrow() {
            return arrow;
        }
    }
}
