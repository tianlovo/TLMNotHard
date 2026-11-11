package com.tianluoqaq.tlmnh.common.development.life;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;
import net.minecraftforge.common.util.Constants;

import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.development.AnimationLab;
import com.tianluoqaq.tlmnh.common.diagnostic.DevelopmentFlags;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidShrineTile;
import com.tianluoqaq.tlmnh.common.registry.MaidResurrectionContent;

/** 原模型神龛及合成胶片显示，持久登记后才写世界，未知改动保留 */
public final class ShrineVisualFixture {

    private static final String DATA = "TLMNH_ShrineVisualFixture";
    private static final int[] X = { -4, 0, 4 }, META = { 0, 0, 1 };

    private ShrineVisualFixture() {}

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
            "Unreadable shrine journal retained");
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
        require(action.equals("open") || action.equals("clear"), "Unknown shrine display action");
        if (!data.owner.isEmpty()) {
            validate(world, data.ready);
            data.ready = false;
            data.markDirty();
            for (int i = 0; i < X.length; i++) {
                if (world.getTileEntity(X[i], 65, -2) instanceof MaidShrineTile shrine) shrine.extract();
                if (world.getBlock(X[i], 65, -2) == MaidResurrectionContent.SHRINE)
                    world.setBlock(X[i], 65, -2, Blocks.air, 0, 2);
            }
            data.owner = "";
            data.ready = false;
            data.markDirty();
        }
        if (action.equals("clear")) {
            TLMNotHard.LOG.info("TLMNH_SHRINE_VISUAL_CLEAR complete=true");
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
                world.setBlock(X[i], 65, -2, MaidResurrectionContent.SHRINE, META[i], 2),
                "Shrine display placement failed");
            if (i > 0) require(
                ((MaidShrineTile) world.getTileEntity(X[i], 65, -2)).insert(fixtureFilm()),
                "Synthetic display film admission failed");
        }
        data.ready = true;
        data.markDirty();
        validate(world, true);
        TLMNotHard.LOG.info("TLMNH_SHRINE_VISUAL_READY count=3 empty_north=1 film_north=1 film_east=1");
    }

    private static void validate(World world, boolean ready) {
        for (int i = 0; i < X.length; i++) {
            int x = X[i];
            if (world.isAirBlock(x, 65, -2)) {
                require(!ready && world.getTileEntity(x, 65, -2) == null, "Ready shrine removed; journal retained");
            } else {
                require(
                    world.getBlock(x, 65, -2) == MaidResurrectionContent.SHRINE
                        && world.getBlockMetadata(x, 65, -2) == META[i]
                        && world.getTileEntity(x, 65, -2) instanceof MaidShrineTile,
                    "Changed shrine retained");
                var shrine = (MaidShrineTile) world.getTileEntity(x, 65, -2);
                var film = shrine.getFilm();
                require(
                    !shrine.isInvalid() && (i == 0 ? film == null
                        : ItemStack.areItemStacksEqual(film, fixtureFilm()) || !ready && film == null),
                    "Unexpected shrine inventory retained");
            }
            require(
                world.isAirBlock(x, 66, -2) && world.getTileEntity(x, 66, -2) == null,
                "Changed shrine upper area retained");
        }
    }

    private static ItemStack fixtureFilm() {
        var stack = new ItemStack(MaidResurrectionContent.FILM);
        var tag = new NBTTagCompound();
        tag.setString("TLMNHFixture", "ShrineVisualFixture");
        stack.setTagCompound(tag);
        return stack;
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
                "Unknown shrine fixture schema retained");
            require(
                tag.hasKey("Owner", Constants.NBT.TAG_STRING) && tag.hasKey("Ready", Constants.NBT.TAG_BYTE),
                "Incomplete shrine fixture journal retained");
            owner = tag.getString("Owner");
            if (!owner.isEmpty()) UUID.fromString(owner);
            ready = tag.getBoolean("Ready");
            require(!ready || !owner.isEmpty(), "Ready shrine fixture has no owner; retained");
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
