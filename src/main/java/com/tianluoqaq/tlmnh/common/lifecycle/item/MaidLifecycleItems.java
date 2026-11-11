/*
 * Mechanics adapted from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: item/ItemSmartSlab.java and item/ItemFilm.java, TartaricAcid contributors, MIT
 * See licenses/TouhouLittleMaid-MIT.txt; adaptation adds server-owned single-use credentials
 */
package com.tianluoqaq.tlmnh.common.lifecycle.item;

import java.util.List;
import java.util.UUID;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;

import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleService;
import com.tianluoqaq.tlmnh.common.registry.MaidResurrectionContent;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 凭证只保存token与展示归属，实际女仆身份由世界账本持有 */
public final class MaidLifecycleItems {

    private MaidLifecycleItems() {}

    public static ItemStack credential(Item item, UUID token, UUID owner) {
        var stack = new ItemStack(item);
        var tag = new NBTTagCompound();
        tag.setString(LifecycleItemNbtKeys.KEY_TLMNH_TOKEN, token.toString());
        tag.setString(LifecycleItemNbtKeys.KEY_INIT_MAID_OWNER, owner.toString());
        stack.setTagCompound(tag);
        return stack;
    }

    public static UUID token(ItemStack stack) {
        if (stack == null || stack.stackSize != LifecycleItemTuning.SET_MAX_STACK_SIZE_SINGLE_UNIT
            || !stack.hasTagCompound()) return null;
        try {
            return UUID.fromString(
                stack.getTagCompound()
                    .getString(LifecycleItemNbtKeys.KEY_TLMNH_TOKEN));
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
                tag.setBoolean(LifecycleItemNbtKeys.KEY_INVULNERABLE, true);
                entity.readFromNBT(tag);
            }
            if (entity.posY < 0) {
                entity.setPosition(
                    entity.posX,
                    LifecycleItemTuning.LIFECYCLE_ITEMS_SET_POSITION_SECOND_FACTOR,
                    entity.posZ);
                entity.motionX = entity.motionY = entity.motionZ = LifecycleItemTuning.LIFECYCLE_ITEMS_MOTION_Z;
                entity.fallDistance = LifecycleItemTuning.LIFECYCLE_ITEMS_MOTION_Z;
            }
            return false;
        }
    }

    public static final class InitialSlab extends StoredMaidItem {

        public InitialSlab() {
            setMaxStackSize(LifecycleItemTuning.SET_MAX_STACK_SIZE_SINGLE_UNIT);
            setUnlocalizedName(LifecycleItemIds.ID_TLMNH_SMART_SLAB_INIT);
            setTextureName(LifecycleItemIds.ID_TOUHOU_LITTLE_MAID_SMART_SLAB_HAS_MAID);
        }

        @Override
        public boolean onItemUse(ItemStack stack, EntityPlayer player, World world, int x, int y, int z, int side,
            float hitX, float hitY, float hitZ) {
            if (side != LifecycleItemTuning.SET_MAX_STACK_SIZE_SINGLE_UNIT) return false;
            if (world.isRemote) return true;
            MaidLifecycleService.tell(player, MaidLifecycleService.releaseInitial(player, stack, x, y, z));
            return true;
        }
    }

    public static final class FilledSlab extends StoredMaidItem {

        public FilledSlab() {
            setMaxStackSize(LifecycleItemTuning.SET_MAX_STACK_SIZE_SINGLE_UNIT);
            setUnlocalizedName(LifecycleItemIds.ID_TLMNH_SMART_SLAB_HAS_MAID);
            setTextureName(LifecycleItemIds.ID_TOUHOU_LITTLE_MAID_SMART_SLAB_HAS_MAID);
        }

        @Override
        @SideOnly(Side.CLIENT)
        public void addInformation(ItemStack stack, EntityPlayer player, List<String> lines, boolean advanced) {
            lines.add(StatCollector.translateToLocal(LifecycleItemIds.I18N_TOOLTIPS_TLMNH_SMART_SLAB_HAS_MAID_RELEASE));
        }

        @Override
        public boolean onItemUse(ItemStack stack, EntityPlayer player, World world, int x, int y, int z, int side,
            float hitX, float hitY, float hitZ) {
            if (side != LifecycleItemTuning.SET_MAX_STACK_SIZE_SINGLE_UNIT) return false;
            if (world.isRemote) return true;
            MaidLifecycleService.tell(player, MaidLifecycleService.releaseCaptured(player, stack, x, y, z));
            return true;
        }
    }

    public static boolean isFilm(ItemStack stack) {
        return stack != null && stack.stackSize == LifecycleItemTuning.SET_MAX_STACK_SIZE_SINGLE_UNIT
            && stack.getItem() == MaidResurrectionContent.FILM;
    }
}
