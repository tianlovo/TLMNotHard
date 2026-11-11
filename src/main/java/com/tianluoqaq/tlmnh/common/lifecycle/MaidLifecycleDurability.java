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
                throw new IllegalStateException("Lifecycle storage no longer owns the active ledger");
            server.checkSessionLock();
            CompatServices.lifecycleStorage()
                .beforeLedgerWrite();
            var target = server.getSaveHandler()
                .getMapFileFromName(MaidLifecycleData.NAME)
                .getCanonicalFile();
            var expected = new File(
                server.getSaveHandler()
                    .getWorldDirectory(),
                "data/" + MaidLifecycleData.NAME + ".dat").getCanonicalFile();
            if (!target.equals(expected) || !target.toPath()
                .startsWith(
                    server.getSaveHandler()
                        .getWorldDirectory()
                        .getCanonicalFile()
                        .toPath()))
                throw new IllegalStateException("Unknown lifecycle data location");
            var data = new NBTTagCompound();
            ledger.writeToNBT(data);
            var root = new NBTTagCompound();
            root.setTag("data", data);
            var buffer = new ByteArrayOutputStream();
            CompressedStreamTools.writeCompressed(root, buffer);
            Files.createDirectories(
                target.toPath()
                    .getParent());
            var temporary = Files.createTempFile(
                target.toPath()
                    .getParent(),
                MaidLifecycleData.NAME + ".",
                ".tmp");
            try (var output = new FileOutputStream(temporary.toFile())) {
                output.write(buffer.toByteArray());
                output.getFD()
                    .sync();
            }
            // 不以delete+rename或非原子fallback替代；写前失败不得进入外部交付
            Files.move(temporary, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            if (!root.equals(read(target))) throw new IllegalStateException("Durable lifecycle snapshot differs");
            ledger.setDirty(false);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot confirm durable lifecycle escrow; delivery remains held", failure);
        }
    }

    static void requirePlayer(EntityPlayer player) {
        var server = server(player.worldObj);
        if (!(player instanceof EntityPlayerMP actual) || actual.playerNetServerHandler == null
            || !(server.getSaveHandler()
                .getSaveHandler() instanceof SaveHandler))
            throw new IllegalStateException("Unknown native player persistence target");
        try {
            playerFile(player);
        } catch (Exception failure) {
            throw new IllegalStateException("Unknown native player data location", failure);
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
                throw new IllegalStateException("Player delivery changed before native save");
            // 沿原生SaveHandler及PlayerSavingEvent写出，绝不readPlayerData覆写活玩家
            store.writePlayerData(player);
            var file = playerFile(player);
            var saved = read(file);
            if (!saved.hasKey("Inventory", Constants.NBT.TAG_LIST)
                || !saved.hasKey("SelectedItemSlot", Constants.NBT.TAG_INT)
                || !expectedInventory.equals(saved.getTagList("Inventory", Constants.NBT.TAG_COMPOUND))
                || saved.getInteger("SelectedItemSlot") != selected
                || !expectedInventory.equals(player.inventory.writeToNBT(new NBTTagList()))
                || player.inventory.currentItem != selected
                || health != null
                    && (!saved.hasKey("HealF", Constants.NBT.TAG_FLOAT) || saved.getFloat("HealF") != health
                        || !saved.hasKey("Health", Constants.NBT.TAG_SHORT)
                        || saved.getShort("Health") != (short) Math.ceil(health)
                        || player.getHealth() != health))
                throw new IllegalStateException("Native player inventory save was rejected or rewritten");
            try (var channel = FileChannel.open(file.toPath(), StandardOpenOption.WRITE)) {
                channel.force(true);
            }
        } catch (Exception failure) {
            throw new IllegalStateException(
                "Cannot confirm native player delivery; original escrow remains held",
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
            .chunkExists(x >> 4, z >> 4)) throw new IllegalStateException("Source tile chunk is no longer loaded");
        var block = world.getBlock(x, y, z);
        int metadata = world.getBlockMetadata(x, y, z);
        storeTileState(world, x, y, z, block, metadata, tile, expected);
    }

    static void absentTile(World world, int x, int y, int z, Block original) {
        var actual = server(world);
        if (!actual.getChunkProvider()
            .chunkExists(x >> 4, z >> 4) || actual.getTileEntity(x, y, z) != null
            || actual.getBlock(x, y, z) == original)
            throw new IllegalStateException("Original source block removal is not confirmed");
        storeTileState(actual, x, y, z, actual.getBlock(x, y, z), actual.getBlockMetadata(x, y, z), null, null);
    }

    private static void storeTileState(WorldServer world, int x, int y, int z, Block block, int metadata,
        TileEntity tile, NBTTagCompound expected) {
        try {
            expected = expected == null ? null : (NBTTagCompound) expected.copy();
            checkTileState(world, x, y, z, block, metadata, tile, expected);
            var chunks = new LinkedHashMap<Long, Chunk>();
            addChunk(world, chunks, x >> 4, z >> 4);
            var loader = loader(world);
            for (var chunk : chunks.values()) loader.saveChunk(world, chunk);
            ThreadedFileIOBase.threadedIOInstance.waitForFinish();
            try (var input = RegionFileCache.getChunkInputStream(loader.chunkSaveLocation, x >> 4, z >> 4)) {
                if (input == null) throw new IllegalStateException("No native saved source tile chunk");
                var root = CompressedStreamTools.read(input);
                var level = root.getCompoundTag("Level");
                if (!root.hasKey("Level", Constants.NBT.TAG_COMPOUND) || !level.hasKey("xPos", Constants.NBT.TAG_INT)
                    || !level.hasKey("zPos", Constants.NBT.TAG_INT)
                    || level.getInteger("xPos") != x >> 4
                    || level.getInteger("zPos") != z >> 4
                    || !level.hasKey("Sections", Constants.NBT.TAG_LIST)
                    || !level.hasKey("TileEntities", Constants.NBT.TAG_LIST))
                    throw new IllegalStateException("Unknown native source tile chunk layout");
                var sections = level.getTagList("Sections", Constants.NBT.TAG_COMPOUND);
                if (((NBTTagList) level.getTag("Sections")).tagCount() != sections.tagCount())
                    throw new IllegalStateException("Unknown source block section entries");
                int sectionCount = 0;
                for (int i = 0; i < sections.tagCount(); i++) {
                    var section = sections.getCompoundTagAt(i);
                    if (!section.hasKey("Y", Constants.NBT.TAG_BYTE))
                        throw new IllegalStateException("Unknown native section index");
                    if (section.getByte("Y") != y >> 4) continue;
                    sectionCount++;
                    int index = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
                    if (CompatServices.lifecycleStorage()
                        .sectionBlockId(section, index) != Block.getIdFromBlock(block)
                        || CompatServices.lifecycleStorage()
                            .sectionMetadata(section, index) != metadata)
                        throw new IllegalStateException("Native source block or metadata changed during save");
                }
                if (sectionCount != 1 && !(sectionCount == 0 && block == Blocks.air && metadata == 0))
                    throw new IllegalStateException("Native source block section is not unique");
                var tiles = level.getTagList("TileEntities", Constants.NBT.TAG_COMPOUND);
                if (((NBTTagList) level.getTag("TileEntities")).tagCount() != tiles.tagCount())
                    throw new IllegalStateException("Unknown source tile list");
                int count = 0;
                for (int i = 0; i < tiles.tagCount(); i++) {
                    var saved = tiles.getCompoundTagAt(i);
                    if (!saved.hasKey("x", Constants.NBT.TAG_INT) || !saved.hasKey("y", Constants.NBT.TAG_INT)
                        || !saved.hasKey("z", Constants.NBT.TAG_INT))
                        throw new IllegalStateException("Unknown saved tile position");
                    if (saved.getInteger("x") != x || saved.getInteger("y") != y || saved.getInteger("z") != z)
                        continue;
                    count++;
                    if (tile == null || !expected.equals(saved))
                        throw new IllegalStateException("Native source tile contents changed during save");
                }
                if (count != (tile == null ? 0 : 1))
                    throw new IllegalStateException("Native source tile presence differs");
            }
            checkTileState(world, x, y, z, block, metadata, tile, expected);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread()
                .interrupt();
            throw new IllegalStateException(
                "Cannot confirm native source tile; original material remains held",
                failure);
        }
    }

    private static void checkTileState(WorldServer world, int x, int y, int z, Block block, int metadata,
        TileEntity tile, NBTTagCompound expected) {
        if (!world.getChunkProvider()
            .chunkExists(x >> 4, z >> 4) || world.getBlock(x, y, z) != block
            || world.getBlockMetadata(x, y, z) != metadata
            || world.getTileEntity(x, y, z) != tile) throw new IllegalStateException("Source tile identity changed");
        if (tile != null) {
            var snapshot = new NBTTagCompound();
            tile.writeToNBT(snapshot);
            if (tile.isInvalid() || tile.getWorldObj() != world
                || tile.xCoord != x
                || tile.yCoord != y
                || tile.zCoord != z
                || !expected.equals(snapshot))
                throw new IllegalStateException("Unknown source tile successor retained");
        }
    }

    static NBTTagCompound maidSnapshot(EntityMaid maid) {
        var result = new NBTTagCompound();
        var type = EntityList.getEntityString(maid);
        if (type == null || maid.ridingEntity != null || maid.riddenByEntity != null)
            throw new IllegalStateException("Unknown or mounted maid publication");
        result.setString("id", type);
        maid.writeToNBT(result);
        return (NBTTagCompound) result.copy();
    }

    static void shrine(MaidShrineTile shrine, NBTTagCompound expected, int metadata) {
        try {
            var world = server(shrine.getWorldObj());
            var chunks = new LinkedHashMap<Long, Chunk>();
            checkShrine(shrine, expected, metadata);
            addChunk(world, chunks, shrine.xCoord >> 4, shrine.zCoord >> 4);
            var loader = loader(world);
            for (var chunk : chunks.values()) loader.saveChunk(world, chunk);
            ThreadedFileIOBase.threadedIOInstance.waitForFinish();
            try (var input = RegionFileCache
                .getChunkInputStream(loader.chunkSaveLocation, shrine.xCoord >> 4, shrine.zCoord >> 4)) {
                if (input == null) throw new IllegalStateException("No saved shrine chunk");
                var root = CompressedStreamTools.read(input);
                var level = root.getCompoundTag("Level");
                if (!root.hasKey("Level", Constants.NBT.TAG_COMPOUND)
                    || !level.hasKey("TileEntities", Constants.NBT.TAG_LIST)
                    || !level.hasKey("xPos", Constants.NBT.TAG_INT)
                    || !level.hasKey("zPos", Constants.NBT.TAG_INT)
                    || level.getInteger("xPos") != (shrine.xCoord >> 4)
                    || level.getInteger("zPos") != (shrine.zCoord >> 4))
                    throw new IllegalStateException("Unknown saved shrine chunk");
                checkShrineBlock(level, shrine, metadata);
                var tiles = level.getTagList("TileEntities", Constants.NBT.TAG_COMPOUND);
                if (((NBTTagList) level.getTag("TileEntities")).tagCount() != tiles.tagCount())
                    throw new IllegalStateException("Unknown saved shrine tile list");
                int count = 0;
                for (int i = 0; i < tiles.tagCount(); i++) {
                    var tile = tiles.getCompoundTagAt(i);
                    if (tile.getInteger("x") == shrine.xCoord && tile.getInteger("y") == shrine.yCoord
                        && tile.getInteger("z") == shrine.zCoord) {
                        count++;
                        if (!expected.equals(tile))
                            throw new IllegalStateException("Saved shrine differs from its owned snapshot");
                    }
                }
                if (count != 1) throw new IllegalStateException("Saved shrine identity is not unique");
            }
            checkShrine(shrine, expected, metadata);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread()
                .interrupt();
            throw new IllegalStateException("Cannot confirm native shrine cost; redemption remains pending", failure);
        }
    }

    static void checkShrine(MaidShrineTile shrine, NBTTagCompound expected, int metadata) {
        var world = shrine.getWorldObj();
        var actual = new NBTTagCompound();
        if (shrine.isInvalid() || !world.getChunkProvider()
            .chunkExists(shrine.xCoord >> 4, shrine.zCoord >> 4)
            || world.getTileEntity(shrine.xCoord, shrine.yCoord, shrine.zCoord) != shrine
            || world.getBlock(shrine.xCoord, shrine.yCoord, shrine.zCoord) != MaidResurrectionContent.SHRINE
            || world.getBlockMetadata(shrine.xCoord, shrine.yCoord, shrine.zCoord) != metadata)
            throw new IllegalStateException("Shrine identity changed during redemption");
        shrine.writeToNBT(actual);
        if (!expected.equals(actual)) throw new IllegalStateException("Shrine storage changed during redemption");
    }

    private static void checkShrineBlock(NBTTagCompound level, MaidShrineTile shrine, int metadata) {
        if (!level.hasKey("Sections", Constants.NBT.TAG_LIST))
            throw new IllegalStateException("Unknown native shrine block sections");
        var sections = level.getTagList("Sections", Constants.NBT.TAG_COMPOUND);
        if (((NBTTagList) level.getTag("Sections")).tagCount() != sections.tagCount())
            throw new IllegalStateException("Unknown native shrine section entries");
        int count = 0;
        for (int i = 0; i < sections.tagCount(); i++) {
            var section = sections.getCompoundTagAt(i);
            if (!section.hasKey("Y", Constants.NBT.TAG_BYTE) || section.getByte("Y") != shrine.yCoord >> 4) continue;
            count++;
            int index = ((shrine.yCoord & 15) << 8) | ((shrine.zCoord & 15) << 4) | (shrine.xCoord & 15);
            int id = CompatServices.lifecycleStorage()
                .sectionBlockId(section, index);
            if (id != Block.getIdFromBlock(MaidResurrectionContent.SHRINE) || CompatServices.lifecycleStorage()
                .sectionMetadata(section, index) != metadata)
                throw new IllegalStateException("Native shrine block or facing changed during save");
        }
        if (count != 1) throw new IllegalStateException("Native shrine block section is not unique");
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
                    if (input == null) throw new IllegalStateException("No saved native chunk for delivery");
                    var raw = CompressedStreamTools.read(input);
                    if (!raw.hasKey("Level", Constants.NBT.TAG_COMPOUND))
                        throw new IllegalStateException("Unknown native chunk data root");
                    var level = raw.getCompoundTag("Level");
                    if (!level.hasKey("xPos", Constants.NBT.TAG_INT) || !level.hasKey("zPos", Constants.NBT.TAG_INT)
                        || level.getInteger("xPos") != chunk.xPosition
                        || level.getInteger("zPos") != chunk.zPosition
                        || !level.hasKey("Entities", Constants.NBT.TAG_LIST))
                        throw new IllegalStateException("Unknown native chunk entity storage");
                    var list = level.getTagList("Entities", Constants.NBT.TAG_COMPOUND);
                    if (((NBTTagList) level.getTag("Entities")).tagCount() != list.tagCount())
                        throw new IllegalStateException("Invalid native chunk entity list");
                    for (int i = 0; i < list.tagCount(); i++) stored.add(list.getCompoundTagAt(i));
                }
            }
            for (var publication : publications) {
                publication.check(world, present);
                int count = 0;
                for (var entity : stored) if (publication.identity
                    .equals(new UUID(entity.getLong("UUIDMost"), entity.getLong("UUIDLeast")))) {
                        count++;
                        if (present && !publication.matches(entity))
                            throw new IllegalStateException("Native chunk rewrote the material publication");
                    }
                if (count != (present ? 1 : 0))
                    throw new IllegalStateException("Native chunk publication or retraction was not confirmed");
            }
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread()
                .interrupt();
            throw new IllegalStateException(
                "Cannot confirm native material publication; original escrow remains held",
                failure);
        }
    }

    private static void addChunk(WorldServer world, LinkedHashMap<Long, Chunk> chunks, int x, int z) {
        if (!world.theChunkProviderServer.chunkExists(x, z))
            throw new IllegalStateException("Delivery chunk is no longer loaded");
        long key = ((long) x << 32) ^ (z & 0xffffffffL);
        chunks.put(key, world.theChunkProviderServer.provideChunk(x, z));
    }

    private static WorldServer server(World world) {
        if (!(world instanceof WorldServer server) || world.isRemote)
            throw new IllegalStateException("Native server world required for durable delivery");
        return server;
    }

    private static AnvilChunkLoader loader(WorldServer world) {
        if (!(world.theChunkProviderServer.currentChunkLoader instanceof AnvilChunkLoader loader))
            throw new IllegalStateException("Unknown native chunk persistence target");
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
        var file = new File(directory, "playerdata/" + player.getUniqueID() + ".dat").getCanonicalFile();
        if (!directory.equals(
            server.getSaveHandler()
                .getWorldDirectory()
                .getCanonicalFile())
            || !file.toPath()
                .startsWith(directory.toPath()))
            throw new IllegalStateException("Player storage is outside the owned native world");
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
            this(entity, expected.copy(), 0);
        }

        Publication(EntityXPOrb entity, int expected) {
            this(entity, null, expected);
        }

        Publication(EntityMaid entity, NBTTagCompound expected) {
            this(entity, null, 0);
            maid = (NBTTagCompound) expected.copy();
        }

        Publication(EntityMaid entity, NBTTagCompound expected, boolean deathSource) {
            this(entity, expected);
            this.deathSource = deathSource;
        }

        Publication(MaidTombstoneEntity entity, NBTTagCompound expected) {
            this(entity, null, 0);
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
            chunkX = (int) Math.floor(x) >> 4;
            chunkZ = (int) Math.floor(z) >> 4;
            type = EntityList.getEntityString(entity);
            if (type == null) throw new IllegalStateException("Unknown material publication entity type");
        }

        void check(World world, boolean present) {
            if (!owned(world) || (present ? !MaidSpawnChecks.present(entity, world) : !retracted || !entity.isDead))
                throw new IllegalStateException("Material publication changed ownership or presence");
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
                throw new IllegalStateException("Unknown material successor or prior consumption must remain intact");
            world.removeEntity(entity);
            retracted = true;
            check(world, false);
        }

        private boolean matches(NBTTagCompound saved) {
            var position = saved.getTagList("Pos", Constants.NBT.TAG_DOUBLE);
            if (maid != null) return maid.equals(saved);
            return saved.hasKey("Pos", Constants.NBT.TAG_LIST) && position.tagCount() == 3
                && position.func_150309_d(0) == x
                && position.func_150309_d(1) == y
                && position.func_150309_d(2) == z
                && type.equals(saved.getString("id"))
                && (item == null
                    ? saved.hasKey("Value", Constants.NBT.TAG_SHORT) && saved.getShort("Value") == experience
                    : item.writeToNBT(new NBTTagCompound())
                        .equals(saved.getCompoundTag("Item")));
        }
    }
}
