package com.tianluoqaq.tlmnh.common.development;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.world.WorldServer;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.TLMNotHard;

/** 使用不加入在线列表的服务器玩家检查交互状态，不能代替真实客户端点击 */
public final class MaidInteractionProbe {

    private MaidInteractionProbe() {}

    public static void run(WorldServer world) {
        var owner = player(world, "95cddc12-ceb4-411c-8838-0be62d805c27", "[TLMNH-Owner]");
        var stranger = player(world, "7b66d348-72d4-4de5-bb5d-74dc28aeeb9e", "[TLMNH-Other]");
        var maid = new EntityMaid(world);
        var creativeMaid = new EntityMaid(world);
        var spawn = world.getSpawnPoint();
        int x = spawn.posX + 6;
        int z = spawn.posZ + 6;
        world.getChunkFromBlockCoords(x, z);
        double y = world.getTopSolidOrLiquidBlock(x, z);
        owner.setPosition(x + 0.5D, y, z + 0.5D);
        stranger.setPosition(owner.posX, owner.posY, owner.posZ);
        maid.setPosition(owner.posX, owner.posY, owner.posZ);
        creativeMaid.setPosition(owner.posX, owner.posY, owner.posZ);
        try {
            owner.inventory.setInventorySlotContents(0, new ItemStack(Items.cake, 2));
            require(maid.interact(owner) && maid.isOwner(owner), "cake tame");
            require(owner.inventory.getStackInSlot(0).stackSize == 1, "one cake consumed");
            stranger.inventory.setInventorySlotContents(0, new ItemStack(Items.cake, 2));
            require(!maid.interact(stranger) && maid.isOwner(owner), "owner transfer refused");
            require(stranger.inventory.getStackInSlot(0).stackSize == 2, "denied cake conserved");
            owner.inventory.setInventorySlotContents(0, null);
            owner.setSneaking(true);
            require(maid.interact(owner) && maid.isSitting(), "sneak sit");
            require(maid.interact(owner) && !maid.isSitting(), "sneak stand");
            owner.setPosition(x + 100.0D, y, z);
            require(!maid.interact(owner) && !maid.isSitting(), "distant interaction refused");
            owner.setPosition(creativeMaid.posX, creativeMaid.posY, creativeMaid.posZ);
            owner.capabilities.isCreativeMode = true;
            owner.inventory.setInventorySlotContents(0, new ItemStack(Items.cake));
            require(creativeMaid.interact(owner) && creativeMaid.isOwner(owner), "creative tame");
            require(owner.inventory.getStackInSlot(0).stackSize == 1, "creative cake conserved");
            TLMNotHard.LOG.info("TLMNH_INTERACTION_TEST_PASS tame=cake owner=immutable sit=checked distance=checked");
        } finally {
            for (int slot = 0; slot < 36; slot++) {
                owner.inventory.setInventorySlotContents(slot, null);
                stranger.inventory.setInventorySlotContents(slot, null);
            }
            world.removeEntity(maid);
            world.removeEntity(creativeMaid);
        }
    }

    private static EntityPlayerMP player(WorldServer world, String id, String name) {
        return new EntityPlayerMP(
            MinecraftServer.getServer(),
            world,
            new GameProfile(UUID.fromString(id), name),
            new ItemInWorldManager(world));
    }

    private static void require(boolean condition, String name) {
        if (!condition) throw new IllegalStateException("TLMNH interaction probe failed: " + name);
    }
}
