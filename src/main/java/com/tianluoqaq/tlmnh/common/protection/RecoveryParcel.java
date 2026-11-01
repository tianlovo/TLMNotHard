package com.tianluoqaq.tlmnh.common.protection;

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
        setUnlocalizedName("tlmnh.recovery_parcel");
        setTextureName("minecraft:paper");
        setMaxStackSize(1);
    }

    public static void register() {
        GameRegistry.registerItem(ITEM, "recovery_parcel");
    }

    @Override
    public ItemStack onItemRightClick(ItemStack stack, World world, EntityPlayer player) {
        if (world.isRemote) return stack;
        ItemStack restored = ProtectedItemCodec.restore(stack);
        if (restored == null) {
            player.addChatMessage(new ChatComponentTranslation("chat.tlmnh.protection.sealed"));
            return stack;
        }
        return restored;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack stack, EntityPlayer player, List lines, boolean advanced) {
        lines.add(StatCollector.translateToLocal("tooltip.tlmnh.protection.parcel"));
        if (stack.hasTagCompound()) lines.add(
            stack.getTagCompound()
                .getString("Reason"));
    }
}
