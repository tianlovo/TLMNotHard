package com.tianluoqaq.tlmnh.common.lifecycle;

import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

/** Forge出生事件返回后再次检查实际实体，不能只信spawn的布尔值 */
public final class MaidSpawnChecks {

    private MaidSpawnChecks() {}

    public static boolean present(Entity entity) {
        return entity != null && !entity.isDead
            && entity.worldObj != null
            && entity.worldObj.loadedEntityList.contains(entity)
            && entity.worldObj.getEntityByID(entity.getEntityId()) == entity;
    }

    public static boolean itemPresent(EntityItem entity, ItemStack expected) {
        return present(entity) && ItemStack.areItemStacksEqual(expected, entity.getEntityItem());
    }

    public static boolean present(Entity entity, World world) {
        return entity != null && entity.worldObj == world && present(entity);
    }

    public static boolean itemPresent(EntityItem entity, ItemStack expected, World world) {
        return present(entity, world) && ItemStack.areItemStacksEqual(expected, entity.getEntityItem());
    }

    public static void discard(Entity entity) {
        if (entity == null) return;
        var world = entity.worldObj;
        if (world != null
            && (world.loadedEntityList.contains(entity) || world.getEntityByID(entity.getEntityId()) == entity))
            world.removeEntity(entity);
        else entity.setDead();
    }
}
