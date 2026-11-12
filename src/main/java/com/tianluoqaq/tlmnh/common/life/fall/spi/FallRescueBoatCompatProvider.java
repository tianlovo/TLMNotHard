package com.tianluoqaq.tlmnh.common.life.fall.spi;

import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

/** Et Futurum 全木种新船贴地救援生成、入座与无残留清理窄接口 */
public interface FallRescueBoatCompatProvider {

    FallRescueBoatCompatProvider NOOP = new FallRescueBoatCompatProvider() {};

    default boolean handlesVanillaBoatReplacement() {
        return false;
    }

    default boolean isCustomBoatItem(ItemStack stack) {
        return false;
    }

    default Entity spawnAndMountCustomBoat(World world, Entity maid, ItemStack boatStack, double x, double y, double z,
        float yaw) {
        return null;
    }

    default boolean dismountAndDiscardCustomBoat(Entity maid, Entity boat) {
        return false;
    }
}
