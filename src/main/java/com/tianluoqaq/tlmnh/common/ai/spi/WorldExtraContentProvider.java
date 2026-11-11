package com.tianluoqaq.tlmnh.common.ai.spi;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

/** 后移植方块、载具乘客、盔甲架与容器音效的窄适配契约；默认按 1.7.10 原版世界语义运行 */
public interface WorldExtraContentProvider {

    WorldExtraContentProvider NOOP = new WorldExtraContentProvider() {};

    default boolean isBubbleColumn(Block block) {
        return false;
    }

    default boolean isHoneyBlock(Block block) {
        return false;
    }

    default boolean isWitherRose(Block block) {
        return false;
    }

    default boolean isMagmaBlock(Block block) {
        return false;
    }

    default boolean isArmorStand(Entity entity) {
        return false;
    }

    default boolean isElytraItem(String itemId) {
        return false;
    }

    default Entity vehicleOf(Entity passenger) {
        return passenger == null ? null : passenger.ridingEntity;
    }

    default Entity controlledVehicleOf(EntityPlayer player) {
        return player == null ? null : player.ridingEntity;
    }

    default boolean canAddPassenger(Entity vehicle) {
        return vehicle != null && vehicle.riddenByEntity == null;
    }

    default void mountVehicle(Entity maid, Entity vehicle) {
        if (maid != null) maid.mountEntity(vehicle);
    }

    default String barrelSound(boolean opening) {
        return opening ? "random.chestopen" : "random.chestclosed";
    }

    default void playShrineFrameSound(World world, int x, int y, int z, boolean inserted) {}
}
