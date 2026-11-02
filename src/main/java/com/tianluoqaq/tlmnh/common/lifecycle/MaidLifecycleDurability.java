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

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.chunk.storage.RegionFileCache;
import net.minecraft.world.storage.SaveHandler;
import net.minecraft.world.storage.ThreadedFileIOBase;

/** 两类出站保管的写前屏障与实际目标读回；不把原生吞掉IO异常的返回当作存盘成功 */
final class MaidLifecycleDurability {

    private MaidLifecycleDurability() {}

    static void ledger(World world, MaidLifecycleData ledger) {
        try {
            var server = server(world);
            if (MaidLifecycleData.get(world) != ledger)
                throw new IllegalStateException("Lifecycle storage no longer owns the active ledger");
            server.checkSessionLock();
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
        requirePlayer(player);
        try {
            expectedInventory = (NBTTagList) expectedInventory.copy();
            var server = server(player.worldObj);
            var store = (SaveHandler) server.getSaveHandler()
                .getSaveHandler();
            if (!expectedInventory.equals(player.inventory.writeToNBT(new NBTTagList()))
                || player.inventory.currentItem != selected)
                throw new IllegalStateException("Player delivery changed before native save");
            // 沿原生SaveHandler及PlayerSavingEvent写出，绝不readPlayerData覆写活玩家
            store.writePlayerData(player);
            var file = playerFile(player);
            var saved = read(file);
            if (!saved.hasKey("Inventory", 9) || !saved.hasKey("SelectedItemSlot", 3)
                || !expectedInventory.equals(saved.getTagList("Inventory", 10))
                || saved.getInteger("SelectedItemSlot") != selected
                || !expectedInventory.equals(player.inventory.writeToNBT(new NBTTagList()))
                || player.inventory.currentItem != selected)
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
                    if (!raw.hasKey("Level", 10)) throw new IllegalStateException("Unknown native chunk data root");
                    var level = raw.getCompoundTag("Level");
                    if (!level.hasKey("xPos", 3) || !level.hasKey("zPos", 3)
                        || level.getInteger("xPos") != chunk.xPosition
                        || level.getInteger("zPos") != chunk.zPosition
                        || !level.hasKey("Entities", 9))
                        throw new IllegalStateException("Unknown native chunk entity storage");
                    var list = level.getTagList("Entities", 10);
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
        private boolean retracted;

        Publication(EntityItem entity, ItemStack expected) {
            this(entity, expected.copy(), 0);
        }

        Publication(EntityXPOrb entity, int expected) {
            this(entity, null, expected);
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
                && (item == null ? entity instanceof EntityXPOrb orb && orb.xpValue == experience
                    : entity instanceof EntityItem drop && ItemStack.areItemStacksEqual(item, drop.getEntityItem()));
        }

        void discard(World world) {
            if (!owned(world) || entity.isDead)
                throw new IllegalStateException("Unknown material successor or prior consumption must remain intact");
            world.removeEntity(entity);
            retracted = true;
            check(world, false);
        }

        private boolean matches(NBTTagCompound saved) {
            var position = saved.getTagList("Pos", 6);
            return saved.hasKey("Pos", 9) && position.tagCount() == 3
                && position.func_150309_d(0) == x
                && position.func_150309_d(1) == y
                && position.func_150309_d(2) == z
                && type.equals(saved.getString("id"))
                && (item == null ? saved.hasKey("Value", 2) && saved.getShort("Value") == experience
                    : item.writeToNBT(new NBTTagCompound())
                        .equals(saved.getCompoundTag("Item")));
        }
    }
}
