/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/task/TaskAttack.java, MIT
 * Adaptation: retained weapon/target range predicates; behavior registration belongs to the modern component
 */
package com.github.tartaricacid.touhoulittlemaid.entity.task;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

public class TaskAttack implements IAttackTask {

    public static final ResourceLocation UID = new ResourceLocation("touhou_little_maid", "attack");

    public ResourceLocation getUid() {
        return UID;
    }

    public ItemStack getIcon() {
        return new ItemStack(Items.diamond_sword);
    }

    public boolean isWeapon(EntityMaid maid, ItemStack stack) {
        return stack != null && stack.stackSize > 0
            && stack.getAttributeModifiers()
                .containsKey(SharedMonsterAttributes.attackDamage.getAttributeUnlocalizedName());
    }

    public boolean hasAssaultWeapon(EntityMaid maid) {
        return isWeapon(maid, maid.getHeldItem());
    }

    public boolean farAway(EntityLivingBase target, EntityMaid maid) {
        if (!target.isEntityAlive()) return true;
        float radius = maid.getHomeArea()
            .restrictionRadius();
        var owner = maid.getOwner();
        if (!maid.getHomeArea()
            .enabled() && owner != null) return owner.getDistanceToEntity(target) > radius;
        return maid.getDistanceToEntity(target) > radius;
    }
}
