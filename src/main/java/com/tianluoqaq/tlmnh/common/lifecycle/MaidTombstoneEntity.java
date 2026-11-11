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
        setSize(
            MaidLifecycleTuning.TOMBSTONE_ENTITY_SET_SIZE_FIRST_FACTOR,
            MaidLifecycleTuning.TOMBSTONE_ENTITY_SET_SIZE_SECOND_FACTOR);
        preventEntitySpawning = false;
    }

    public void initialize(UUID token, UUID owner, String name) {
        if (worldObj.isRemote || this.token != null || token == null || owner == null)
            throw new IllegalStateException(MaidLifecyclePart3Diagnostics.MSG_INVALID_TOMBSTONE_INITIALIZATION);
        this.token = token;
        this.owner = owner;
        dataWatcher.updateObject(MaidLifecycleTuning.CAPTURE_SERVICE_PUT_SECOND_PARAM, name == null ? "" : name);
    }

    public UUID getToken() {
        return token;
    }

    public UUID getOwnerId() {
        return owner;
    }

    public String getMaidName() {
        return dataWatcher.getWatchableObjectString(MaidLifecycleTuning.CAPTURE_SERVICE_PUT_SECOND_PARAM);
    }

    @Override
    protected void entityInit() {
        dataWatcher.addObject(MaidLifecycleTuning.CAPTURE_SERVICE_PUT_SECOND_PARAM, "");
    }

    @Override
    public void onUpdate() {
        lastTickPosX = prevPosX = posX;
        lastTickPosY = prevPosY = posY;
        lastTickPosZ = prevPosZ = posZ;
        if (!worldObj.isRemote && posY < MaidLifecycleTuning.DEATH_EXPERIENCE_BLOCK_EXISTS_SECOND_PARAM)
            setPosition(posX, MaidLifecycleTuning.DEATH_EXPERIENCE_BLOCK_EXISTS_SECOND_PARAM, posZ);
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
            token = UUID.fromString(tag.getString(MaidLifecyclePart2NbtKeys.KEY_TOKEN));
            owner = UUID.fromString(tag.getString(MaidLifecyclePart2NbtKeys.KEY_OWNER_ID));
        } catch (IllegalArgumentException exception) {
            token = null;
            owner = null;
        }
        dataWatcher.updateObject(
            MaidLifecycleTuning.CAPTURE_SERVICE_PUT_SECOND_PARAM,
            tag.getString(MaidLifecyclePart2NbtKeys.KEY_MAID_NAME));
    }

    @Override
    protected void writeEntityToNBT(NBTTagCompound tag) {
        if (token != null) tag.setString(MaidLifecyclePart2NbtKeys.KEY_TOKEN, token.toString());
        if (owner != null) tag.setString(MaidLifecyclePart2NbtKeys.KEY_OWNER_ID, owner.toString());
        tag.setString(MaidLifecyclePart2NbtKeys.KEY_MAID_NAME, getMaidName());
    }
}
