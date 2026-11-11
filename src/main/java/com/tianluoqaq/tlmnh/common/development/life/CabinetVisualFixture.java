package com.tianluoqaq.tlmnh.common.development.life;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;
import net.minecraftforge.common.util.Constants;

import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.development.AnimationLab;
import com.tianluoqaq.tlmnh.common.diagnostic.DevelopmentFlags;
import com.tianluoqaq.tlmnh.common.life.TileEntitySnackCabinet;
import com.tianluoqaq.tlmnh.common.registry.MaidLifeContent;

/** 空库存柜体/原生27格界面显示，持久登记后才写世界，未知改动保留 */
public final class CabinetVisualFixture {

    private static final String DATA = "TLMNH_CabinetVisualFixture";
    private static final int[] X = { -5, -1, 3 }, META = { 0, 4, 5 };

    private CabinetVisualFixture() {}

    public static void set(EntityPlayerMP player, String action) {
        require(DevelopmentFlags.isDevCommandsEnabled(), "Development fixture required");
        AnimationLab.ownedSceneMaid(player);
        require(
            player.openContainer == player.inventoryContainer,
            "Close the current menu before changing the fixture");
        var world = player.worldObj;
        var data = (Journal) world.mapStorage.loadData(Journal.class, DATA);
        var file = world.getSaveHandler()
            .getMapFileFromName(DATA);
        require(
            file == null || !file.exists() || data != null && data.readComplete,
            "Unreadable cabinet journal retained");
        if (data == null) {
            data = new Journal(DATA);
            data.readComplete = true;
            world.mapStorage.setData(DATA, data);
        }
        require(
            data.owner.isEmpty() || data.owner.equals(
                player.getUniqueID()
                    .toString()),
            "Another observer owns this fixture");
        if (action.equals("gui")) {
            require(!data.owner.isEmpty() && data.ready, "Ready owned cabinet fixture required");
            validate(world, data.ready);
            MaidLifeContent.snackCabinet.onBlockActivated(world, X[1], 65, -2, player, 2, .5F, .5F, .5F);
            require(
                player.openContainer instanceof ContainerChest chest
                    && chest.getLowerChestInventory() == world.getTileEntity(X[1], 65, -2),
                "Native 27-slot menu did not open");
            TLMNotHard.LOG.info("TLMNH_CABINET_VISUAL_GUI slots=27");
            return;
        }
        require(action.equals("open") || action.equals("clear"), "Unknown cabinet display action");
        if (!data.owner.isEmpty()) {
            validate(world, data.ready);
            for (int i = 0; i < X.length; i++) {
                if (i > 0 && world.getBlock(X[i], 66, -2) == Blocks.cake)
                    world.setBlock(X[i], 66, -2, Blocks.air, 0, 2);
                if (world.getBlock(X[i], 65, -2) == MaidLifeContent.snackCabinet)
                    world.setBlock(X[i], 65, -2, Blocks.air, 0, 2);
            }
            data.owner = "";
            data.ready = false;
            data.markDirty();
        }
        if (action.equals("clear")) {
            TLMNotHard.LOG.info("TLMNH_CABINET_VISUAL_CLEAR complete=true");
            return;
        }
        for (int x : X) for (int y = 65; y <= 66; y++) require(
            world.isAirBlock(x, y, -2) && world.getTileEntity(x, y, -2) == null
                && world.getBlock(x, 64, -2) == Blocks.wool,
            "Fixture area is not empty own lab floor");
        data.owner = player.getUniqueID()
            .toString();
        data.ready = false;
        data.markDirty();
        for (int i = 0; i < X.length; i++) {
            require(
                world.setBlock(X[i], 65, -2, MaidLifeContent.snackCabinet, META[i], 2),
                "Cabinet display placement failed");
            if (i > 0) require(world.setBlock(X[i], 66, -2, Blocks.cake, 0, 2), "Fixture cake placement failed");
        }
        data.ready = true;
        data.markDirty();
        validate(world, true);
        TLMNotHard.LOG.info("TLMNH_CABINET_VISUAL_READY count=3 empty_north=1 full_north=1 full_east=1");
    }

    private static void validate(World world, boolean ready) {
        for (int i = 0; i < X.length; i++) {
            int x = X[i];
            if (world.isAirBlock(x, 65, -2)) {
                require(!ready && world.getTileEntity(x, 65, -2) == null, "Ready cabinet removed; journal retained");
            } else {
                require(
                    world.getBlock(x, 65, -2) == MaidLifeContent.snackCabinet
                        && world.getBlockMetadata(x, 65, -2) == META[i]
                        && world.getTileEntity(x, 65, -2) instanceof TileEntitySnackCabinet,
                    "Changed cabinet retained");
                var cabinet = (TileEntitySnackCabinet) world.getTileEntity(x, 65, -2);
                require(
                    !cabinet.isInvalid() && cabinet.recovery()
                        .size() == 0,
                    "Unexpected cabinet recovery data retained");
                for (int slot = 0; slot < cabinet.getSizeInventory(); slot++)
                    require(cabinet.getStackInSlot(slot) == null, "Unexpected cabinet inventory retained");
            }
            require(world.getTileEntity(x, 66, -2) == null, "Foreign upper tile retained");
            if (i == 0) require(world.isAirBlock(x, 66, -2), "Changed empty cabinet top retained");
            else require(
                world.getBlock(x, 66, -2) == Blocks.cake && world.getBlockMetadata(x, 66, -2) == 0
                    || !ready && world.isAirBlock(x, 66, -2),
                "Changed fixture cake retained");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    public static final class Journal extends WorldSavedData {

        String owner = "";
        boolean ready, readComplete;
        NBTTagCompound raw;

        public Journal(String name) {
            super(name);
        }

        @Override
        public void readFromNBT(NBTTagCompound tag) {
            raw = (NBTTagCompound) tag.copy();
            require(
                tag.hasKey("Schema", Constants.NBT.TAG_INT) && tag.getInteger("Schema") == 1,
                "Unknown cabinet fixture schema retained");
            require(
                tag.hasKey("Owner", Constants.NBT.TAG_STRING) && tag.hasKey("Ready", Constants.NBT.TAG_BYTE),
                "Incomplete cabinet fixture journal retained");
            owner = tag.getString("Owner");
            if (!owner.isEmpty()) UUID.fromString(owner);
            ready = tag.getBoolean("Ready");
            require(!ready || !owner.isEmpty(), "Ready cabinet fixture has no owner; retained");
            readComplete = true;
        }

        @Override
        public void writeToNBT(NBTTagCompound tag) {
            if (!readComplete && raw != null) {
                for (Object key : raw.func_150296_c()) tag.setTag(
                    (String) key,
                    raw.getTag((String) key)
                        .copy());
                return;
            }
            tag.setInteger("Schema", 1);
            tag.setString("Owner", owner);
            tag.setBoolean("Ready", ready);
        }
    }
}
