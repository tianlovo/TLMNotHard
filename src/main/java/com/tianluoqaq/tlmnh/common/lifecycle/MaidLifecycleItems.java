/*
 * Mechanics adapted from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: item/ItemSmartSlab.java and item/ItemFilm.java, TartaricAcid contributors, MIT
 * See licenses/TouhouLittleMaid-MIT.txt; adaptation adds server-owned single-use credentials
 */
package com.tianluoqaq.tlmnh.common.lifecycle;

import java.util.UUID;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

import com.tianluoqaq.tlmnh.common.registry.MaidResurrectionContent;

/** 凭证只保存token与展示归属，实际女仆身份由世界账本持有 */
public final class MaidLifecycleItems {

    private MaidLifecycleItems() {}

    public static ItemStack credential(Item item, UUID token, UUID owner) {
        var stack = new ItemStack(item);
        var tag = new NBTTagCompound();
        tag.setString("TLMNHToken", token.toString());
        tag.setString("InitMaidOwner", owner.toString());
        stack.setTagCompound(tag);
        return stack;
    }

    public static UUID token(ItemStack stack) {
        if (stack == null || stack.stackSize != 1 || !stack.hasTagCompound()) return null;
        try {
            return UUID.fromString(
                stack.getTagCompound()
                    .getString("TLMNHToken"));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public static class StoredMaidItem extends Item {

        @Override
        public boolean onEntityItemUpdate(EntityItem entity) {
            if (!entity.worldObj.isRemote && !entity.isEntityInvulnerable()) {
                var tag = new NBTTagCompound();
                entity.writeToNBT(tag);
                tag.setBoolean("Invulnerable", true);
                entity.readFromNBT(tag);
            }
            if (entity.posY < 0) {
                entity.setPosition(entity.posX, 0.1D, entity.posZ);
                entity.motionX = entity.motionY = entity.motionZ = 0;
                entity.fallDistance = 0;
            }
            return false;
        }
    }

    public static final class InitialSlab extends StoredMaidItem {

        public InitialSlab() {
            setMaxStackSize(1);
            setUnlocalizedName("tlmnh.smart_slab_init");
            setTextureName("touhou_little_maid:smart_slab_has_maid");
        }

        @Override
        public boolean onItemUse(ItemStack stack, EntityPlayer player, World world, int x, int y, int z, int side,
            float hitX, float hitY, float hitZ) {
            if (side != 1) return false;
            if (world.isRemote) return true;
            MaidLifecycleService.tell(player, MaidLifecycleService.releaseInitial(player, stack, x, y, z));
            return true;
        }
    }

    public static boolean isFilm(ItemStack stack) {
        return stack != null && stack.stackSize == 1 && stack.getItem() == MaidResurrectionContent.FILM;
    }
}
