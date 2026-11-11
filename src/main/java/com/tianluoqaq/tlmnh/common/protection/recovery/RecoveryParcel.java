package com.tianluoqaq.tlmnh.common.protection.recovery;

import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;

import cpw.mods.fml.common.registry.GameRegistry;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 安全物品只持有原始NBT，不执行原外部物品的行为 */
public final class RecoveryParcel extends Item {

    public static final RecoveryParcel ITEM = new RecoveryParcel();

    private RecoveryParcel() {
        setUnlocalizedName(ProtectionRecoveryIds.ID_TLMNH_RECOVERY_PARCEL);
        setTextureName(ProtectionRecoveryIds.ID_MINECRAFT_PAPER);
        setMaxStackSize(ProtectionRecoveryTuning.MIN_SCHEMA_THRESHOLD);
    }

    public static void register() {
        GameRegistry.registerItem(ITEM, ProtectionRecoveryIds.ID_RECOVERY_PARCEL);
    }

    @Override
    public ItemStack onItemRightClick(ItemStack stack, World world, EntityPlayer player) {
        if (world.isRemote) return stack;
        ItemStack restored = ProtectedItemCodec.restore(stack);
        if (restored == null) {
            player
                .addChatMessage(new ChatComponentTranslation(ProtectionRecoveryIds.I18N_CHAT_TLMNH_PROTECTION_SEALED));
            return stack;
        }
        return restored;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack stack, EntityPlayer player, List lines, boolean advanced) {
        lines.add(StatCollector.translateToLocal(ProtectionRecoveryIds.I18N_TOOLTIP_TLMNH_PROTECTION_PARCEL));
        if (stack.hasTagCompound()) lines.add(
            stack.getTagCompound()
                .getString(ProtectionRecoveryIds.KEY_REASON));
    }
}
