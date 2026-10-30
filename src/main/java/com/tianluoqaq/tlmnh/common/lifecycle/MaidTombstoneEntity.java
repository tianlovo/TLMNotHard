/*
 * Adapted from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/item/EntityTombstone.java, TartaricAcid contributors, MIT
 * See licenses/TouhouLittleMaid-MIT.txt; inventory authority moved to world data
 */
package com.tianluoqaq.tlmnh.common.lifecycle;

import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.EntityLightningBolt;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.DamageSource;
import net.minecraft.world.World;

/** 墓碑只持世界库存凭证，复制实体NBT不会产生第二份库存 */
public final class MaidTombstoneEntity extends Entity {

    private UUID token;
    private UUID owner;

    public MaidTombstoneEntity(World world) {
        super(world);
        setSize(0.8F, 1.2F);
        preventEntitySpawning = false;
    }

    public void initialize(UUID token, UUID owner, String name) {
        if (worldObj.isRemote || this.token != null || token == null || owner == null)
            throw new IllegalStateException("Invalid tombstone initialization");
        this.token = token;
        this.owner = owner;
        dataWatcher.updateObject(20, name == null ? "" : name);
    }

    public UUID getToken() {
        return token;
    }

    public UUID getOwnerId() {
        return owner;
    }

    public String getMaidName() {
        return dataWatcher.getWatchableObjectString(20);
    }

    @Override
    protected void entityInit() {
        dataWatcher.addObject(20, "");
    }

    @Override
    public void onUpdate() {
        lastTickPosX = prevPosX = posX;
        lastTickPosY = prevPosY = posY;
        lastTickPosZ = prevPosZ = posZ;
        if (!worldObj.isRemote && posY < 5) setPosition(posX, 5, posZ);
    }

    @Override
    public boolean interactFirst(EntityPlayer player) {
        if (worldObj.isRemote) return true;
        MaidTombstoneTransfer.collect(this, player);
        return true;
    }

    @Override
    public boolean canBeCollidedWith() {
        return !isDead;
    }

    @Override
    public boolean canBePushed() {
        return false;
    }

    @Override
    public boolean attackEntityFrom(DamageSource source, float amount) {
        return false;
    }

    @Override
    public void moveEntity(double x, double y, double z) {}

    @Override
    public void addVelocity(double x, double y, double z) {}

    @Override
    public void onStruckByLightning(EntityLightningBolt lightning) {}

    @Override
    protected void readEntityFromNBT(NBTTagCompound tag) {
        try {
            token = UUID.fromString(tag.getString("Token"));
            owner = UUID.fromString(tag.getString("OwnerId"));
        } catch (IllegalArgumentException exception) {
            token = null;
            owner = null;
        }
        dataWatcher.updateObject(20, tag.getString("MaidName"));
    }

    @Override
    protected void writeEntityToNBT(NBTTagCompound tag) {
        if (token != null) tag.setString("Token", token.toString());
        if (owner != null) tag.setString("OwnerId", owner.toString());
        tag.setString("MaidName", getMaidName());
    }
}
