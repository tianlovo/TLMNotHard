package com.tianluoqaq.tlmnh.common.development;

import java.util.UUID;

import net.minecraft.entity.EntityList;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventory;

/** 开发服务器中的真实区块保存夹具，期望值由独立 WorldSavedData 保存 */
public final class MaidPersistenceProbe {

    private static final String DATA_NAME = "TLMNH_PersistenceProbe";
    private static final String[] STABLE_KEYS = { "OwnerUUID", "CustomName", "Sitting", "Health", "Equipment",
        "ModelId", "MaidTask", "MaidScheduleMode", "MaidExperience", "MaidHunger", "MaidFavorability", "PickupItems",
        "PickupExperience", "MaidInventory", "TLMNHOffhand", "TLMNH" };

    private MaidPersistenceProbe() {}

    public static void create(World world) {
        require(!world.isRemote, "server world");
        require(
            world.mapStorage.loadData(ProbeData.class, DATA_NAME) == null,
            "fixture already exists; verify it instead");
        var maid = new EntityMaid(world);
        require("tlmnh.maid".equals(EntityList.getEntityString(maid)), "registered persistent entity ID");
        require(maid.bindTo(UUID.fromString("81774baa-24f5-4b74-af5a-d566adee2108")), "fixture owner");
        maid.setCustomNameTag("TLMNH persistence fixture");
        maid.setHealth(14.5F);
        maid.setSitting(true);
        maid.setTask(MaidTask.RANGED_ATTACK);
        maid.setSchedule(MaidSchedule.NIGHT);
        maid.setExperience(155);
        maid.setHunger(17);
        maid.setFavorability(23);
        maid.setPickupItems(false);
        maid.setPickupExperience(false);
        var inventory = maid.getMaidInventory();
        for (int slot = 0; slot < MaidInventory.BACKPACK_SIZE; slot++) {
            var stack = new ItemStack(Items.arrow, slot + 1);
            stack.setStackDisplayName("persistent-slot-" + slot);
            inventory.setInventorySlotContents(slot, stack);
        }
        inventory.setInventorySlotContents(MaidInventory.MAIN_HAND, new ItemStack(Items.bow, 1, 11));
        inventory.setInventorySlotContents(MaidInventory.OFF_HAND, new ItemStack(Items.golden_apple, 3));
        inventory.setInventorySlotContents(MaidInventory.ARMOR_START, new ItemStack(Items.iron_boots, 1, 4));
        inventory.setInventorySlotContents(MaidInventory.ARMOR_START + 1, new ItemStack(Items.iron_leggings));
        inventory.setInventorySlotContents(MaidInventory.ARMOR_START + 2, new ItemStack(Items.iron_chestplate));
        inventory.setInventorySlotContents(MaidInventory.ARMOR_START + 3, new ItemStack(Items.iron_helmet));
        var tag = new NBTTagCompound();
        maid.writeToNBT(tag);
        tag.setBoolean("Invulnerable", true);
        tag.getCompoundTag("TLMNH")
            .setString("ProbeField", "retained-across-restart");
        maid.readFromNBT(tag);
        var spawn = world.getSpawnPoint();
        int x = spawn.posX + 2;
        int z = spawn.posZ + 2;
        world.getChunkFromBlockCoords(x, z);
        maid.setPosition(x + 0.5D, world.getTopSolidOrLiquidBlock(x, z), z + 0.5D);
        require(world.spawnEntityInWorld(maid), "spawn fixture");
        var data = new ProbeData(DATA_NAME);
        data.entityId = maid.getUniqueID()
            .toString();
        data.x = x;
        data.z = z;
        data.dimension = world.provider.dimensionId;
        maid.writeToNBT(data.expected);
        world.mapStorage.setData(DATA_NAME, data);
        data.markDirty();
        TLMNotHard.LOG
            .info("TLMNH_PERSISTENCE_CREATED uuid={} dimension={} x={} z={}", data.entityId, data.dimension, x, z);
    }

    public static void verify(World world) {
        var data = (ProbeData) world.mapStorage.loadData(ProbeData.class, DATA_NAME);
        require(data != null && data.dimension == world.provider.dimensionId, "saved fixture metadata");
        world.getChunkFromBlockCoords(data.x, data.z);
        EntityMaid found = null;
        int matches = 0;
        for (var entity : world.loadedEntityList) {
            if (entity instanceof EntityMaid maid && data.entityId.equals(
                maid.getUniqueID()
                    .toString())) {
                found = maid;
                matches++;
            }
        }
        require(matches == 1 && found != null, "exactly one restored entity, found " + matches);
        var actual = new NBTTagCompound();
        found.writeToNBT(actual);
        for (var key : STABLE_KEYS) {
            require(
                data.expected.hasKey(key) && data.expected.getTag(key)
                    .equals(actual.getTag(key)),
                "restored field " + key);
        }
        require(found.isEntityAlive() && found.isTamed(), "live tamed entity");
        TLMNotHard.LOG.info(
            "TLMNH_PERSISTENCE_CHECK_PASS uuid={} fields={} slots={}",
            data.entityId,
            STABLE_KEYS.length,
            MaidInventory.TOTAL_SIZE);
    }

    private static void require(boolean condition, String name) {
        if (!condition) throw new IllegalStateException("TLMNH persistence probe failed: " + name);
    }

    /** MapStorage 需要公开的 String 构造器，通过常规世界保存流程写盘 */
    public static final class ProbeData extends WorldSavedData {

        private String entityId = "";
        private int x;
        private int z;
        private int dimension;
        private NBTTagCompound expected = new NBTTagCompound();

        public ProbeData(String name) {
            super(name);
        }

        @Override
        public void readFromNBT(NBTTagCompound tag) {
            entityId = tag.getString("EntityUUID");
            x = tag.getInteger("X");
            z = tag.getInteger("Z");
            dimension = tag.getInteger("Dimension");
            expected = (NBTTagCompound) tag.getCompoundTag("Expected")
                .copy();
        }

        @Override
        public void writeToNBT(NBTTagCompound tag) {
            tag.setString("EntityUUID", entityId);
            tag.setInteger("X", x);
            tag.setInteger("Z", z);
            tag.setInteger("Dimension", dimension);
            tag.setTag("Expected", expected.copy());
        }
    }
}
