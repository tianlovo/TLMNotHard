package com.tianluoqaq.tlmnh.common.lifecycle;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.chunk.storage.RegionFileCache;
import net.minecraft.world.storage.SaveHandler;
import net.minecraft.world.storage.ThreadedFileIOBase;
import net.minecraftforge.common.util.Constants;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.compat.CompatServices;
import com.tianluoqaq.tlmnh.common.lifecycle.world.MaidSpawnChecks;
import com.tianluoqaq.tlmnh.common.registry.MaidResurrectionContent;

/** 两类出站保管的写前屏障与实际目标读回；不把原生吞掉IO异常的返回当作存盘成功 */
final class MaidLifecycleDurability {

    private MaidLifecycleDurability() {}

    static void ledger(World world, MaidLifecycleData ledger) {
        try {
            var server = server(world);
            if (MaidLifecycleData.get(world) != ledger)
                throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_LIFECYCLE_STORAGE_NO_LONGER_OWNS);
            server.checkSessionLock();
            CompatServices.lifecycleStorage()
                .beforeLedgerWrite();
            var target = server.getSaveHandler()
                .getMapFileFromName(MaidLifecycleData.NAME)
                .getCanonicalFile();
            var expected = new File(
                server.getSaveHandler()
                    .getWorldDirectory(),
                MaidLifecycleIds.ID_DATA + MaidLifecycleData.NAME + MaidLifecycleIds.ID_DAT).getCanonicalFile();
            if (!target.equals(expected) || !target.toPath()
                .startsWith(
                    server.getSaveHandler()
                        .getWorldDirectory()
                        .getCanonicalFile()
                        .toPath()))
                throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_UNKNOWN_LIFECYCLE_DATA_LOCATION);
            var data = new NBTTagCompound();
            ledger.writeToNBT(data);
            var root = new NBTTagCompound();
            root.setTag(MaidLifecycleIds.ID_DATA_2, data);
            var buffer = new ByteArrayOutputStream();
            CompressedStreamTools.writeCompressed(root, buffer);
            Files.createDirectories(
                target.toPath()
                    .getParent());
            var temporary = Files.createTempFile(
                target.toPath()
                    .getParent(),
                MaidLifecycleData.NAME + MaidLifecycleIds.DOT_SEPARATOR,
                MaidLifecycleIds.ID_TMP);
            try (var output = new FileOutputStream(temporary.toFile())) {
                output.write(buffer.toByteArray());
                output.getFD()
                    .sync();
            }
            // 不以delete+rename或非原子fallback替代；写前失败不得进入外部交付
            Files.move(temporary, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            if (!root.equals(read(target)))
                throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_DURABLE_LIFECYCLE_SNAPSHOT_DIFFERS);
            ledger.setDirty(false);
        } catch (Exception failure) {
            throw new IllegalStateException(
                MaidLifecyclePart2Diagnostics.MSG_CANNOT_CONFIRM_DURABLE_LIFECYCLE_ESCROW,
                failure);
        }
    }

    static void requirePlayer(EntityPlayer player) {
        var server = server(player.worldObj);
        if (!(player instanceof EntityPlayerMP actual) || actual.playerNetServerHandler == null
            || !(server.getSaveHandler()
                .getSaveHandler() instanceof SaveHandler))
            throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_UNKNOWN_NATIVE_PLAYER_PERSISTENCE_TARGET);
        try {
            playerFile(player);
        } catch (Exception failure) {
            throw new IllegalStateException(
                MaidLifecyclePart2Diagnostics.MSG_UNKNOWN_NATIVE_PLAYER_DATA_LOCATION,
                failure);
        }
    }

    static void player(EntityPlayer player, NBTTagList expectedInventory, int selected) {
        player(player, expectedInventory, selected, null);
    }

    static void player(EntityPlayer player, NBTTagList expectedInventory, int selected, Float health) {
        requirePlayer(player);
        try {
            expectedInventory = (NBTTagList) expectedInventory.copy();
            var server = server(player.worldObj);
            var store = (SaveHandler) server.getSaveHandler()
                .getSaveHandler();
            if (!expectedInventory.equals(player.inventory.writeToNBT(new NBTTagList()))
                || player.inventory.currentItem != selected
                || health != null && player.getHealth() != health)
                throw new IllegalStateException(
                    MaidLifecyclePart2Diagnostics.MSG_PLAYER_DELIVERY_CHANGED_BEFORE_NATIVE);
            // 沿原生SaveHandler及PlayerSavingEvent写出，绝不readPlayerData覆写活玩家
            store.writePlayerData(player);
            var file = playerFile(player);
            var saved = read(file);
            if (!saved.hasKey(MaidLifecyclePart2NbtKeys.KEY_INVENTORY, Constants.NBT.TAG_LIST)
                || !saved.hasKey(MaidLifecyclePart2NbtKeys.KEY_SELECTED_ITEM_SLOT, Constants.NBT.TAG_INT)
                || !expectedInventory
                    .equals(saved.getTagList(MaidLifecyclePart2NbtKeys.KEY_INVENTORY, Constants.NBT.TAG_COMPOUND))
                || saved.getInteger(MaidLifecyclePart2NbtKeys.KEY_SELECTED_ITEM_SLOT) != selected
                || !expectedInventory.equals(player.inventory.writeToNBT(new NBTTagList()))
                || player.inventory.currentItem != selected
                || health != null && (!saved.hasKey(MaidLifecyclePart2NbtKeys.KEY_HEAL_F, Constants.NBT.TAG_FLOAT)
                    || saved.getFloat(MaidLifecyclePart2NbtKeys.KEY_HEAL_F) != health
                    || !saved.hasKey(MaidLifecyclePart2NbtKeys.KEY_HEALTH, Constants.NBT.TAG_SHORT)
                    || saved.getShort(MaidLifecyclePart2NbtKeys.KEY_HEALTH) != (short) Math.ceil(health)
                    || player.getHealth() != health))
                throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_NATIVE_PLAYER_INVENTORY_SAVE_WAS);
            try (var channel = FileChannel.open(file.toPath(), StandardOpenOption.WRITE)) {
                channel.force(true);
            }
        } catch (Exception failure) {
            throw new IllegalStateException(
                MaidLifecyclePart2Diagnostics.MSG_CANNOT_CONFIRM_NATIVE_PLAYER_DELIVERY,
                failure);
        }
    }

    static void requireEntities(World world) {
        loader(server(world));
    }

    static void tile(TileEntity tile, NBTTagCompound expected) {
        var world = server(tile.getWorldObj());
        int x = tile.xCoord, y = tile.yCoord, z = tile.zCoord;
        if (!world.getChunkProvider()
            .chunkExists(x >> MaidLifecycleTuning.CHUNK_SECTION_SHIFT, z >> MaidLifecycleTuning.CHUNK_SECTION_SHIFT))
            throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_SOURCE_TILE_CHUNK_IS_NO);
        var block = world.getBlock(x, y, z);
        int metadata = world.getBlockMetadata(x, y, z);
        storeTileState(world, x, y, z, block, metadata, tile, expected);
    }

    static void absentTile(World world, int x, int y, int z, Block original) {
        var actual = server(world);
        if (!actual.getChunkProvider()
            .chunkExists(x >> MaidLifecycleTuning.CHUNK_SECTION_SHIFT, z >> MaidLifecycleTuning.CHUNK_SECTION_SHIFT)
            || actual.getTileEntity(x, y, z) != null
            || actual.getBlock(x, y, z) == original)
            throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_ORIGINAL_SOURCE_BLOCK_REMOVAL_IS);
        storeTileState(actual, x, y, z, actual.getBlock(x, y, z), actual.getBlockMetadata(x, y, z), null, null);
    }

    private static void storeTileState(WorldServer world, int x, int y, int z, Block block, int metadata,
        TileEntity tile, NBTTagCompound expected) {
        try {
            expected = expected == null ? null : (NBTTagCompound) expected.copy();
            checkTileState(world, x, y, z, block, metadata, tile, expected);
            var chunks = new LinkedHashMap<Long, Chunk>();
            addChunk(
                world,
                chunks,
                x >> MaidLifecycleTuning.CHUNK_SECTION_SHIFT,
                z >> MaidLifecycleTuning.CHUNK_SECTION_SHIFT);
            var loader = loader(world);
            for (var chunk : chunks.values()) loader.saveChunk(world, chunk);
            ThreadedFileIOBase.threadedIOInstance.waitForFinish();
            try (var input = RegionFileCache.getChunkInputStream(
                loader.chunkSaveLocation,
                x >> MaidLifecycleTuning.CHUNK_SECTION_SHIFT,
                z >> MaidLifecycleTuning.CHUNK_SECTION_SHIFT)) {
                if (input == null)
                    throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_NO_NATIVE_SAVED_SOURCE_TILE);
                var root = CompressedStreamTools.read(input);
                var level = root.getCompoundTag(MaidLifecyclePart2NbtKeys.KEY_LEVEL);
                if (!root.hasKey(MaidLifecyclePart2NbtKeys.KEY_LEVEL, Constants.NBT.TAG_COMPOUND)
                    || !level.hasKey(MaidLifecycleIds.ID_XPOS, Constants.NBT.TAG_INT)
                    || !level.hasKey(MaidLifecycleIds.ID_ZPOS, Constants.NBT.TAG_INT)
                    || level.getInteger(MaidLifecycleIds.ID_XPOS) != x >> MaidLifecycleTuning.CHUNK_SECTION_SHIFT
                    || level.getInteger(MaidLifecycleIds.ID_ZPOS) != z >> MaidLifecycleTuning.CHUNK_SECTION_SHIFT
                    || !level.hasKey(MaidLifecyclePart2NbtKeys.KEY_SECTIONS, Constants.NBT.TAG_LIST)
                    || !level.hasKey(MaidLifecyclePart2NbtKeys.KEY_TILE_ENTITIES, Constants.NBT.TAG_LIST))
                    throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_UNKNOWN_NATIVE_SOURCE_TILE_CHUNK);
                var sections = level.getTagList(MaidLifecyclePart2NbtKeys.KEY_SECTIONS, Constants.NBT.TAG_COMPOUND);
                if (((NBTTagList) level.getTag(MaidLifecyclePart2NbtKeys.KEY_SECTIONS)).tagCount()
                    != sections.tagCount())
                    throw new IllegalStateException(
                        MaidLifecyclePart2Diagnostics.MSG_UNKNOWN_SOURCE_BLOCK_SECTION_ENTRIES);
                int sectionCount = 0;
                for (int i = 0; i < sections.tagCount(); i++) {
                    var section = sections.getCompoundTagAt(i);
                    if (!section.hasKey(MaidLifecyclePart2NbtKeys.KEY_Y, Constants.NBT.TAG_BYTE))
                        throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_UNKNOWN_NATIVE_SECTION_INDEX);
                    if (section.getByte(MaidLifecyclePart2NbtKeys.KEY_Y)
                        != y >> MaidLifecycleTuning.CHUNK_SECTION_SHIFT) continue;
                    sectionCount++;
                    int index = ((y & MaidLifecycleTuning.CHUNK_LOCAL_MASK)
                        << MaidLifecycleTuning.CAPTURE_RECORD_VALIDATE_PARAM)
                        | ((z & MaidLifecycleTuning.CHUNK_LOCAL_MASK) << MaidLifecycleTuning.CHUNK_SECTION_SHIFT)
                        | (x & MaidLifecycleTuning.CHUNK_LOCAL_MASK);
                    if (CompatServices.lifecycleStorage()
                        .sectionBlockId(section, index) != Block.getIdFromBlock(block)
                        || CompatServices.lifecycleStorage()
                            .sectionMetadata(section, index) != metadata)
                        throw new IllegalStateException(
                            MaidLifecyclePart2Diagnostics.MSG_NATIVE_SOURCE_BLOCK_OR_METADATA);
                }
                if (sectionCount != MaidLifecycleTuning.TARGET_STACK_SIZE_THRESHOLD
                    && !(sectionCount == 0 && block == Blocks.air && metadata == 0))
                    throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_NATIVE_SOURCE_BLOCK_SECTION_IS);
                var tiles = level.getTagList(MaidLifecyclePart2NbtKeys.KEY_TILE_ENTITIES, Constants.NBT.TAG_COMPOUND);
                if (((NBTTagList) level.getTag(MaidLifecyclePart2NbtKeys.KEY_TILE_ENTITIES)).tagCount()
                    != tiles.tagCount())
                    throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_UNKNOWN_SOURCE_TILE_LIST);
                int count = 0;
                for (int i = 0; i < tiles.tagCount(); i++) {
                    var saved = tiles.getCompoundTagAt(i);
                    if (!saved.hasKey(MaidLifecycleIds.ID_X, Constants.NBT.TAG_INT)
                        || !saved.hasKey(MaidLifecycleIds.ID_Y, Constants.NBT.TAG_INT)
                        || !saved.hasKey(MaidLifecycleIds.ID_Z, Constants.NBT.TAG_INT))
                        throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_UNKNOWN_SAVED_TILE_POSITION);
                    if (saved.getInteger(MaidLifecycleIds.ID_X) != x || saved.getInteger(MaidLifecycleIds.ID_Y) != y
                        || saved.getInteger(MaidLifecycleIds.ID_Z) != z) continue;
                    count++;
                    if (tile == null || !expected.equals(saved)) throw new IllegalStateException(
                        MaidLifecyclePart2Diagnostics.MSG_NATIVE_SOURCE_TILE_CONTENTS_CHANGED);
                }
                if (count != (tile == null ? MaidLifecycleTuning.CORE_INITIAL_VALUE
                    : MaidLifecycleTuning.TARGET_STACK_SIZE_THRESHOLD))
                    throw new IllegalStateException(
                        MaidLifecyclePart2Diagnostics.MSG_NATIVE_SOURCE_TILE_PRESENCE_DIFFERS);
            }
            checkTileState(world, x, y, z, block, metadata, tile, expected);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread()
                .interrupt();
            throw new IllegalStateException(
                MaidLifecyclePart2Diagnostics.MSG_CANNOT_CONFIRM_NATIVE_SOURCE_TILE,
                failure);
        }
    }

    private static void checkTileState(WorldServer world, int x, int y, int z, Block block, int metadata,
        TileEntity tile, NBTTagCompound expected) {
        if (!world.getChunkProvider()
            .chunkExists(x >> MaidLifecycleTuning.CHUNK_SECTION_SHIFT, z >> MaidLifecycleTuning.CHUNK_SECTION_SHIFT)
            || world.getBlock(x, y, z) != block
            || world.getBlockMetadata(x, y, z) != metadata
            || world.getTileEntity(x, y, z) != tile)
            throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_SOURCE_TILE_IDENTITY_CHANGED);
        if (tile != null) {
            var snapshot = new NBTTagCompound();
            tile.writeToNBT(snapshot);
            if (tile.isInvalid() || tile.getWorldObj() != world
                || tile.xCoord != x
                || tile.yCoord != y
                || tile.zCoord != z
                || !expected.equals(snapshot))
                throw new IllegalStateException(
                    MaidLifecyclePart2Diagnostics.MSG_UNKNOWN_SOURCE_TILE_SUCCESSOR_RETAINED);
        }
    }

    static NBTTagCompound maidSnapshot(EntityMaid maid) {
        var result = new NBTTagCompound();
        var type = EntityList.getEntityString(maid);
        if (type == null || maid.ridingEntity != null || maid.riddenByEntity != null)
            throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_UNKNOWN_OR_MOUNTED_MAID_PUBLICATION);
        result.setString(MaidLifecycleIds.ID_ID, type);
        maid.writeToNBT(result);
        return (NBTTagCompound) result.copy();
    }

    static void shrine(MaidShrineTile shrine, NBTTagCompound expected, int metadata) {
        try {
            var world = server(shrine.getWorldObj());
            var chunks = new LinkedHashMap<Long, Chunk>();
            checkShrine(shrine, expected, metadata);
            addChunk(
                world,
                chunks,
                shrine.xCoord >> MaidLifecycleTuning.CHUNK_SECTION_SHIFT,
                shrine.zCoord >> MaidLifecycleTuning.CHUNK_SECTION_SHIFT);
            var loader = loader(world);
            for (var chunk : chunks.values()) loader.saveChunk(world, chunk);
            ThreadedFileIOBase.threadedIOInstance.waitForFinish();
            try (var input = RegionFileCache.getChunkInputStream(
                loader.chunkSaveLocation,
                shrine.xCoord >> MaidLifecycleTuning.CHUNK_SECTION_SHIFT,
                shrine.zCoord >> MaidLifecycleTuning.CHUNK_SECTION_SHIFT)) {
                if (input == null)
                    throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_NO_SAVED_SHRINE_CHUNK);
                var root = CompressedStreamTools.read(input);
                var level = root.getCompoundTag(MaidLifecyclePart2NbtKeys.KEY_LEVEL);
                if (!root.hasKey(MaidLifecyclePart2NbtKeys.KEY_LEVEL, Constants.NBT.TAG_COMPOUND)
                    || !level.hasKey(MaidLifecyclePart2NbtKeys.KEY_TILE_ENTITIES, Constants.NBT.TAG_LIST)
                    || !level.hasKey(MaidLifecycleIds.ID_XPOS, Constants.NBT.TAG_INT)
                    || !level.hasKey(MaidLifecycleIds.ID_ZPOS, Constants.NBT.TAG_INT)
                    || level.getInteger(MaidLifecycleIds.ID_XPOS)
                        != (shrine.xCoord >> MaidLifecycleTuning.CHUNK_SECTION_SHIFT)
                    || level.getInteger(MaidLifecycleIds.ID_ZPOS)
                        != (shrine.zCoord >> MaidLifecycleTuning.CHUNK_SECTION_SHIFT))
                    throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_UNKNOWN_SAVED_SHRINE_CHUNK);
                checkShrineBlock(level, shrine, metadata);
                var tiles = level.getTagList(MaidLifecyclePart2NbtKeys.KEY_TILE_ENTITIES, Constants.NBT.TAG_COMPOUND);
                if (((NBTTagList) level.getTag(MaidLifecyclePart2NbtKeys.KEY_TILE_ENTITIES)).tagCount()
                    != tiles.tagCount())
                    throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_UNKNOWN_SAVED_SHRINE_TILE_LIST);
                int count = 0;
                for (int i = 0; i < tiles.tagCount(); i++) {
                    var tile = tiles.getCompoundTagAt(i);
                    if (tile.getInteger(MaidLifecycleIds.ID_X) == shrine.xCoord
                        && tile.getInteger(MaidLifecycleIds.ID_Y) == shrine.yCoord
                        && tile.getInteger(MaidLifecycleIds.ID_Z) == shrine.zCoord) {
                        count++;
                        if (!expected.equals(tile)) throw new IllegalStateException(
                            MaidLifecyclePart2Diagnostics.MSG_SAVED_SHRINE_DIFFERS_FROM_ITS);
                    }
                }
                if (count != MaidLifecycleTuning.TARGET_STACK_SIZE_THRESHOLD)
                    throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_SAVED_SHRINE_IDENTITY_IS_NOT);
            }
            checkShrine(shrine, expected, metadata);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread()
                .interrupt();
            throw new IllegalStateException(
                MaidLifecyclePart2Diagnostics.MSG_CANNOT_CONFIRM_NATIVE_SHRINE_COST,
                failure);
        }
    }

    static void checkShrine(MaidShrineTile shrine, NBTTagCompound expected, int metadata) {
        var world = shrine.getWorldObj();
        var actual = new NBTTagCompound();
        if (shrine.isInvalid() || !world.getChunkProvider()
            .chunkExists(
                shrine.xCoord >> MaidLifecycleTuning.CHUNK_SECTION_SHIFT,
                shrine.zCoord >> MaidLifecycleTuning.CHUNK_SECTION_SHIFT)
            || world.getTileEntity(shrine.xCoord, shrine.yCoord, shrine.zCoord) != shrine
            || world.getBlock(shrine.xCoord, shrine.yCoord, shrine.zCoord) != MaidResurrectionContent.SHRINE
            || world.getBlockMetadata(shrine.xCoord, shrine.yCoord, shrine.zCoord) != metadata)
            throw new IllegalStateException(
                MaidLifecyclePart2Diagnostics.MSG_SHRINE_IDENTITY_CHANGED_DURING_REDEMPTION);
        shrine.writeToNBT(actual);
        if (!expected.equals(actual))
            throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_SHRINE_STORAGE_CHANGED_DURING_REDEMPTION);
    }

    private static void checkShrineBlock(NBTTagCompound level, MaidShrineTile shrine, int metadata) {
        if (!level.hasKey(MaidLifecyclePart2NbtKeys.KEY_SECTIONS, Constants.NBT.TAG_LIST))
            throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_UNKNOWN_NATIVE_SHRINE_BLOCK_SECTIONS);
        var sections = level.getTagList(MaidLifecyclePart2NbtKeys.KEY_SECTIONS, Constants.NBT.TAG_COMPOUND);
        if (((NBTTagList) level.getTag(MaidLifecyclePart2NbtKeys.KEY_SECTIONS)).tagCount() != sections.tagCount())
            throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_UNKNOWN_NATIVE_SHRINE_SECTION_ENTRIES);
        int count = 0;
        for (int i = 0; i < sections.tagCount(); i++) {
            var section = sections.getCompoundTagAt(i);
            if (!section.hasKey(MaidLifecyclePart2NbtKeys.KEY_Y, Constants.NBT.TAG_BYTE)
                || section.getByte(MaidLifecyclePart2NbtKeys.KEY_Y)
                    != shrine.yCoord >> MaidLifecycleTuning.CHUNK_SECTION_SHIFT)
                continue;
            count++;
            int index = ((shrine.yCoord & MaidLifecycleTuning.CHUNK_LOCAL_MASK)
                << MaidLifecycleTuning.CAPTURE_RECORD_VALIDATE_PARAM)
                | ((shrine.zCoord & MaidLifecycleTuning.CHUNK_LOCAL_MASK) << MaidLifecycleTuning.CHUNK_SECTION_SHIFT)
                | (shrine.xCoord & MaidLifecycleTuning.CHUNK_LOCAL_MASK);
            int id = CompatServices.lifecycleStorage()
                .sectionBlockId(section, index);
            if (id != Block.getIdFromBlock(MaidResurrectionContent.SHRINE) || CompatServices.lifecycleStorage()
                .sectionMetadata(section, index) != metadata)
                throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_NATIVE_SHRINE_BLOCK_OR_FACING);
        }
        if (count != MaidLifecycleTuning.TARGET_STACK_SIZE_THRESHOLD)
            throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_NATIVE_SHRINE_BLOCK_SECTION_IS);
    }

    /** 只保存涉及的已加载区块；一批经验球共用一次原生IO等待，不遍历整个世界 */
    static void entities(World world, List<Publication> publications, boolean present) {
        if (publications.isEmpty()) return;
        try {
            var server = server(world);
            var loader = loader(server);
            var chunks = new LinkedHashMap<Long, Chunk>();
            for (var publication : publications) {
                publication.check(world, present);
                addChunk(server, chunks, publication.chunkX, publication.chunkZ);
                if (publication.entity.addedToChunk)
                    addChunk(server, chunks, publication.entity.chunkCoordX, publication.entity.chunkCoordZ);
            }
            for (var chunk : chunks.values()) loader.saveChunk(world, chunk);
            ThreadedFileIOBase.threadedIOInstance.waitForFinish();
            var stored = new ArrayList<NBTTagCompound>();
            for (var chunk : chunks.values()) {
                try (var input = RegionFileCache
                    .getChunkInputStream(loader.chunkSaveLocation, chunk.xPosition, chunk.zPosition)) {
                    if (input == null)
                        throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_NO_SAVED_NATIVE_CHUNK_FOR);
                    var raw = CompressedStreamTools.read(input);
                    if (!raw.hasKey(MaidLifecyclePart2NbtKeys.KEY_LEVEL, Constants.NBT.TAG_COMPOUND))
                        throw new IllegalStateException(
                            MaidLifecyclePart2Diagnostics.MSG_UNKNOWN_NATIVE_CHUNK_DATA_ROOT);
                    var level = raw.getCompoundTag(MaidLifecyclePart2NbtKeys.KEY_LEVEL);
                    if (!level.hasKey(MaidLifecycleIds.ID_XPOS, Constants.NBT.TAG_INT)
                        || !level.hasKey(MaidLifecycleIds.ID_ZPOS, Constants.NBT.TAG_INT)
                        || level.getInteger(MaidLifecycleIds.ID_XPOS) != chunk.xPosition
                        || level.getInteger(MaidLifecycleIds.ID_ZPOS) != chunk.zPosition
                        || !level.hasKey(MaidLifecyclePart2NbtKeys.KEY_ENTITIES, Constants.NBT.TAG_LIST))
                        throw new IllegalStateException(
                            MaidLifecyclePart2Diagnostics.MSG_UNKNOWN_NATIVE_CHUNK_ENTITY_STORAGE);
                    var list = level.getTagList(MaidLifecyclePart2NbtKeys.KEY_ENTITIES, Constants.NBT.TAG_COMPOUND);
                    if (((NBTTagList) level.getTag(MaidLifecyclePart2NbtKeys.KEY_ENTITIES)).tagCount()
                        != list.tagCount())
                        throw new IllegalStateException(
                            MaidLifecyclePart2Diagnostics.MSG_INVALID_NATIVE_CHUNK_ENTITY_LIST);
                    for (int i = 0; i < list.tagCount(); i++) stored.add(list.getCompoundTagAt(i));
                }
            }
            for (var publication : publications) {
                publication.check(world, present);
                int count = 0;
                for (var entity : stored) if (publication.identity.equals(
                    new UUID(
                        entity.getLong(MaidLifecyclePart2NbtKeys.KEY_UUID_MOST),
                        entity.getLong(MaidLifecyclePart2NbtKeys.KEY_UUID_LEAST)))) {
                            count++;
                            if (present && !publication.matches(entity)) throw new IllegalStateException(
                                MaidLifecyclePart3Diagnostics.MSG_NATIVE_CHUNK_REWROTE_THE_MATERIAL);
                        }
                if (count != (present ? MaidLifecycleTuning.TARGET_STACK_SIZE_THRESHOLD
                    : MaidLifecycleTuning.CORE_INITIAL_VALUE))
                    throw new IllegalStateException(
                        MaidLifecyclePart3Diagnostics.MSG_NATIVE_CHUNK_PUBLICATION_OR_RETRACTION);
            }
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread()
                .interrupt();
            throw new IllegalStateException(
                MaidLifecyclePart3Diagnostics.MSG_CANNOT_CONFIRM_NATIVE_MATERIAL_PUBLICATION,
                failure);
        }
    }

    private static void addChunk(WorldServer world, LinkedHashMap<Long, Chunk> chunks, int x, int z) {
        if (!world.theChunkProviderServer.chunkExists(x, z))
            throw new IllegalStateException(MaidLifecyclePart3Diagnostics.MSG_DELIVERY_CHUNK_IS_NO_LONGER);
        long key = ((long) x << MaidLifecycleTuning.UPPER_WORD_SHIFT) ^ (z & MaidLifecycleTuning.UNSIGNED_INT_BIT_MASK);
        chunks.put(key, world.theChunkProviderServer.provideChunk(x, z));
    }

    private static WorldServer server(World world) {
        if (!(world instanceof WorldServer server) || world.isRemote)
            throw new IllegalStateException(MaidLifecyclePart3Diagnostics.MSG_NATIVE_SERVER_WORLD_REQUIRED_FOR);
        return server;
    }

    private static AnvilChunkLoader loader(WorldServer world) {
        if (!(world.theChunkProviderServer.currentChunkLoader instanceof AnvilChunkLoader loader))
            throw new IllegalStateException(MaidLifecyclePart3Diagnostics.MSG_UNKNOWN_NATIVE_CHUNK_PERSISTENCE_TARGET);
        return loader;
    }

    private static NBTTagCompound read(File file) throws Exception {
        try (var input = new FileInputStream(file)) {
            return CompressedStreamTools.readCompressed(input);
        }
    }

    private static File playerFile(EntityPlayer player) throws Exception {
        var server = server(player.worldObj);
        var store = (SaveHandler) server.getSaveHandler()
            .getSaveHandler();
        var directory = store.getWorldDirectory()
            .getCanonicalFile();
        var file = new File(directory, MaidLifecycleIds.ID_PLAYERDATA + player.getUniqueID() + MaidLifecycleIds.ID_DAT)
            .getCanonicalFile();
        if (!directory.equals(
            server.getSaveHandler()
                .getWorldDirectory()
                .getCanonicalFile())
            || !file.toPath()
                .startsWith(directory.toPath()))
            throw new IllegalStateException(MaidLifecyclePart3Diagnostics.MSG_PLAYER_STORAGE_IS_OUTSIDE_THE);
        return file;
    }

    /** 外部回调前捕获具体对象、UUID、位置和完整物品；被改写的后继不属于本次可撤销对象 */
    static final class Publication {

        final Entity entity;
        final UUID identity;
        final int id, chunkX, chunkZ, experience;
        final double x, y, z;
        final String type;
        final ItemStack item;
        private NBTTagCompound maid;
        private boolean deathSource;
        private boolean retracted;

        Publication(EntityItem entity, ItemStack expected) {
            this(entity, expected.copy(), MaidLifecycleTuning.CORE_INITIAL_VALUE);
        }

        Publication(EntityXPOrb entity, int expected) {
            this(entity, null, expected);
        }

        Publication(EntityMaid entity, NBTTagCompound expected) {
            this(entity, null, MaidLifecycleTuning.CORE_INITIAL_VALUE);
            maid = (NBTTagCompound) expected.copy();
        }

        Publication(EntityMaid entity, NBTTagCompound expected, boolean deathSource) {
            this(entity, expected);
            this.deathSource = deathSource;
        }

        Publication(MaidTombstoneEntity entity, NBTTagCompound expected) {
            this(entity, null, MaidLifecycleTuning.CORE_INITIAL_VALUE);
            maid = (NBTTagCompound) expected.copy();
        }

        private Publication(Entity entity, ItemStack item, int experience) {
            this.entity = entity;
            this.item = item;
            this.experience = experience;
            identity = entity.getUniqueID();
            id = entity.getEntityId();
            x = entity.posX;
            y = entity.posY;
            z = entity.posZ;
            chunkX = (int) Math.floor(x) >> MaidLifecycleTuning.CHUNK_SECTION_SHIFT;
            chunkZ = (int) Math.floor(z) >> MaidLifecycleTuning.CHUNK_SECTION_SHIFT;
            type = EntityList.getEntityString(entity);
            if (type == null) throw new IllegalStateException(
                MaidLifecyclePart3Diagnostics.MSG_UNKNOWN_MATERIAL_PUBLICATION_ENTITY_TYPE);
        }

        void check(World world, boolean present) {
            if (!owned(world) || (present ? !MaidSpawnChecks.present(entity, world) : !retracted || !entity.isDead))
                throw new IllegalStateException(
                    MaidLifecyclePart3Diagnostics.MSG_MATERIAL_PUBLICATION_CHANGED_OWNERSHIP_OR);
        }

        boolean owned(World world) {
            return entity.worldObj == world && entity.getEntityId() == id
                && identity.equals(entity.getUniqueID())
                && entity.posX == x
                && entity.posY == y
                && entity.posZ == z
                && (world.getEntityByID(id) == null || world.getEntityByID(id) == entity)
                && (maid != null
                    ? entity instanceof MaidTombstoneEntity || deathSource
                        ? maid.equals(MaidDeathIngress.snapshot(entity))
                        : entity instanceof EntityMaid actual && maid.equals(maidSnapshot(actual))
                    : item == null ? entity instanceof EntityXPOrb orb && orb.xpValue == experience
                        : entity instanceof EntityItem drop
                            && ItemStack.areItemStacksEqual(item, drop.getEntityItem()));
        }

        void discard(World world) {
            if (!owned(world) || entity.isDead && (maid == null || entity instanceof MaidTombstoneEntity))
                throw new IllegalStateException(MaidLifecyclePart3Diagnostics.MSG_UNKNOWN_MATERIAL_SUCCESSOR_OR_PRIOR);
            world.removeEntity(entity);
            retracted = true;
            check(world, false);
        }

        private boolean matches(NBTTagCompound saved) {
            var position = saved.getTagList(MaidLifecyclePart2NbtKeys.KEY_POS, Constants.NBT.TAG_DOUBLE);
            if (maid != null) return maid.equals(saved);
            return saved.hasKey(MaidLifecyclePart2NbtKeys.KEY_POS, Constants.NBT.TAG_LIST)
                && position.tagCount() == MaidLifecycleTuning.TARGET_SIZE_THRESHOLD
                && position.func_150309_d(MaidLifecycleTuning.CORE_INITIAL_VALUE) == x
                && position.func_150309_d(MaidLifecycleTuning.TARGET_STACK_SIZE_THRESHOLD) == y
                && position.func_150309_d(MaidLifecycleTuning.DEATH_INGRESS_STATE_PARAM) == z
                && type.equals(saved.getString(MaidLifecycleIds.ID_ID))
                && (item == null
                    ? saved.hasKey(MaidLifecyclePart2NbtKeys.KEY_VALUE, Constants.NBT.TAG_SHORT)
                        && saved.getShort(MaidLifecyclePart2NbtKeys.KEY_VALUE) == experience
                    : item.writeToNBT(new NBTTagCompound())
                        .equals(saved.getCompoundTag(MaidLifecyclePart2NbtKeys.KEY_ITEM)));
        }
    }
}
