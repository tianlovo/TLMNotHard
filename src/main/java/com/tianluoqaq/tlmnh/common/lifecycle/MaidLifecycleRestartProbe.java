package com.tianluoqaq.tlmnh.common.lifecycle;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.DamageSource;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.WorldSavedData;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings.GameType;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.config.MaidAcquisitionConfig;
import com.tianluoqaq.tlmnh.common.development.AnimationLab;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventory;
import com.tianluoqaq.tlmnh.common.registry.MaidResurrectionContent;

/** 两个JVM之间验证真实区块和世界账本；隔离玩家不代表正常player.dat保存验收 */
public final class MaidLifecycleRestartProbe {

    private static final String DATA_NAME = "TLMNH_LifecycleRestartProbe";
    private static final String RUN_TAG = "TLMNH_LifecycleRestartRun";
    private static final String MODEL = "geckolib:winefox_hanfu";
    private static final int HEIGHT = 190;
    private static final long PROCESS_START = ManagementFactory.getRuntimeMXBean()
        .getStartTime();

    private MaidLifecycleRestartProbe() {}

    public static void create(WorldServer world) {
        guard(world);
        var previous = load(world);
        require(
            previous == null || previous.phase.equals("VERIFIED"),
            "unfinished fixture exists; verify or inspect it first");
        var data = new ProbeData(DATA_NAME);
        data.run = UUID.randomUUID()
            .toString();
        data.owner = UUID.nameUUIDFromBytes(("owner:" + data.run).getBytes(StandardCharsets.UTF_8));
        data.giftedOwner = UUID.nameUUIDFromBytes(("gifted-owner:" + data.run).getBytes(StandardCharsets.UTF_8));
        data.dimension = world.provider.dimensionId;
        data.x = world.getSpawnPoint().posX + 56;
        data.y = HEIGHT;
        data.z = world.getSpawnPoint().posZ + 56;
        data.processStart = PROCESS_START;
        preflight(world, data);
        world.mapStorage.setData(DATA_NAME, data);
        data.markDirty();
        try {
            platform(world, data);
            var owner = player(world, data.owner);
            var gifted = player(world, data.giftedOwner);
            position(owner, data, -2);
            position(gifted, data, -2);
            withGiftEnabled(() -> {
                require(MaidLifecycleEvents.giveInitial(owner), "initial soul slab granted");
                require(MaidLifecycleEvents.giveInitial(gifted), "unredeemed soul slab granted");
            });
            data.initialSlab = findItem(owner, MaidResurrectionContent.INITIAL_SLAB).copy();
            data.giftedSlab = findItem(gifted, MaidResurrectionContent.INITIAL_SLAB).copy();
            require(
                !MaidLifecycleItems.token(data.initialSlab)
                    .equals(MaidLifecycleItems.token(data.giftedSlab)),
                "separate player credentials");
            click(owner, world, data.x - 2, data.y, data.z);
            require(
                owner.getCurrentEquippedItem() != null && owner.getCurrentEquippedItem()
                    .getItem() == MaidResurrectionContent.EMPTY_SLAB,
                "initial soul slab redeemed through native item path");
            var first = onlyOwnedMaid(world, data.owner);
            mark(first, data);
            first.setModelId(MODEL);
            first.setCustomNameTag(name(data, "revived"));
            first.setFavorability(23);
            data.firstDeadMaid = first.getUniqueID();
            var firstTomb = kill(world, first, data);
            require(
                owner.interactWith(firstTomb) && firstTomb.isDead,
                "first tombstone collected through native entity path");
            var film = findItem(owner, MaidResurrectionContent.FILM);
            data.usedFilm = film.copy();
            equip(owner, film);
            require(world.setBlock(data.x, data.y, data.z, MaidResurrectionContent.SHRINE), "place persistent shrine");
            var shrine = shrine(world, data);
            position(owner, data, 0);
            click(owner, world, data.x, data.y, data.z);
            require(
                shrine.getFilm() != null && owner.getCurrentEquippedItem() == null,
                "film inserted through native block path");
            owner.setHealth(20);
            click(owner, world, data.x, data.y, data.z);
            require(
                shrine.getFilm() == null && owner.getHealth() == 0.25F,
                "first film consumed with upstream life cost");
            var revived = onlyOwnedMaid(world, data.owner);
            require(
                !revived.getUniqueID()
                    .equals(data.firstDeadMaid) && revived.isOwner(owner),
                "new entity retains original owner");
            require(
                revived.getModelId()
                    .equals(MODEL)
                    && revived.getCustomNameTag()
                        .equals(name(data, "revived")),
                "film retained identity");
            data.revivedMaid = revived.getUniqueID();
            protect(revived, data);
            require(shrine.insert(data.usedFilm), "persistent shrine holds used-film clone for negative test");

            var stored = new EntityMaid(world);
            require(stored.bindTo(data.owner), "second maid owner");
            stored.setCustomNameTag(name(data, "stored"));
            mark(stored, data);
            stored.setLocationAndAngles(data.x + 2.5D, data.y + 1, data.z + 0.5D, 0, 0);
            for (int slot = 0; slot < MaidInventory.TOTAL_SIZE; slot++) {
                var stack = new ItemStack(Items.arrow, slot + 1);
                stack.setStackDisplayName(slotName(data, slot));
                stored.getMaidInventory()
                    .setInventorySlotContents(slot, stack);
            }
            require(
                world.spawnEntityInWorld(stored) && MaidSpawnChecks.present(stored, world),
                "second maid entered real world");
            data.secondDeadMaid = stored.getUniqueID();
            var tombstone = kill(world, stored, data);
            data.tombstone = tombstone.getUniqueID();
            data.tombstoneToken = tombstone.getToken();
            verifyStoredInventory(world, data);
            require(
                "tlmnh.tombstone".equals(EntityList.getEntityString(tombstone)),
                "registered tombstone save identity");
            data.phase = "PENDING";
            data.markDirty();
            TLMNotHard.LOG.info(
                "TLMNH_LIFECYCLE_RESTART_PENDING run={} dimension={} revived={} tombstone={} owner={} giftedOwner={} processStart={} playerDatVerified=false crashAtomicityVerified=false",
                data.run,
                data.dimension,
                data.revivedMaid,
                data.tombstone,
                data.owner,
                data.giftedOwner,
                PROCESS_START);
        } catch (RuntimeException exception) {
            data.phase = "FAILED_CREATE";
            data.markDirty();
            TLMNotHard.LOG
                .error("TLMNH_LIFECYCLE_RESTART_CREATE_FAILED run={} retainedForInspection=true", data.run, exception);
            throw exception;
        }
    }

    public static void verify(WorldServer world) {
        guard(world);
        var data = load(world);
        require(data != null && data.phase.equals("PENDING"), "pending fixture metadata required");
        require(
            data.loadedFromDisk && data.processStart != PROCESS_START,
            "a different JVM must load fixture from disk");
        require(data.dimension == world.provider.dimensionId, "fixture dimension");
        world.getChunkFromBlockCoords(data.x - 3, data.z - 2);
        world.getChunkFromBlockCoords(data.x + 3, data.z + 2);
        var owner = player(world, data.owner);
        var gifted = player(world, data.giftedOwner);
        position(owner, data, 0);
        position(gifted, data, 0);
        withGiftEnabled(() -> {
            require(
                !MaidLifecycleEvents.giveInitial(owner),
                "redeemed player does not receive another slab after restart");
            require(
                !MaidLifecycleEvents.giveInitial(gifted),
                "gifted unredeemed player does not receive another slab after restart");
        });
        require(inventoryEmpty(owner) && inventoryEmpty(gifted), "no regranted player inventory");
        var ledger = MaidLifecycleData.get(world);
        var giftedToken = MaidLifecycleItems.token(data.giftedSlab);
        require(
            data.giftedOwner.equals(ledger.owner(giftedToken, MaidLifecycleData.Kind.INITIAL)),
            "gifted owner persisted");
        require(
            ledger.reserve(giftedToken, MaidLifecycleData.Kind.INITIAL) != null,
            "unredeemed slab remains redeemable");
        ledger.rollback(giftedToken);
        var revived = (EntityMaid) find(world, data.revivedMaid, EntityMaid.class);
        require(
            revived.isOwner(owner) && revived.isSitting() && revived.isEntityInvulnerable(),
            "persisted maid owner and safe sitting state");
        require(
            revived.getModelId()
                .equals(MODEL)
                && revived.getCustomNameTag()
                    .equals(name(data, "revived"))
                && revived.getFavorability() == 23,
            "persisted maid identity");
        require(
            data.run.equals(
                revived.getEntityData()
                    .getString(RUN_TAG)),
            "persisted entity fixture marker");
        revived.getBrainCoordinator()
            .setSuspended(true);
        require(
            count(world, data.firstDeadMaid) == 0 && count(world, data.secondDeadMaid) == 0,
            "dead maid UUIDs did not reload");

        // 先释放神龛上方空间，使错误凭证不能被无关的碰撞失败掩盖
        revived.setPosition(data.x - 2.5D, data.y + 1, data.z + 0.5D);
        var shrine = shrine(world, data);
        require(
            ItemStack.areItemStacksEqual(shrine.getFilm(), data.usedFilm),
            "used-film clone actually loaded from shrine chunk NBT");
        owner.setHealth(20);
        owner.setSneaking(false);
        owner.lastTranslation = "";
        click(owner, world, data.x, data.y, data.z);
        require(
            owner.lastTranslation.equals("message.tlmnh.lifecycle.invalid_credential"),
            "native shrine refused consumed credential, not health or geometry");
        require(
            owner.getHealth() == 20 && ItemStack.areItemStacksEqual(shrine.getFilm(), data.usedFilm),
            "failed duplicate film preserves costs");
        require(ownedMaidCount(world, data.owner) == 1, "duplicate film created no second maid");
        position(owner, data, -2);
        owner.inventory.mainInventory[0] = data.initialSlab.copy();
        owner.lastTranslation = "";
        click(owner, world, data.x - 2, data.y, data.z);
        require(
            owner.lastTranslation.equals("message.tlmnh.lifecycle.invalid_credential"),
            "native initial slab refused redeemed credential");
        require(
            ItemStack.areItemStacksEqual(owner.getCurrentEquippedItem(), data.initialSlab),
            "duplicate slab remains unchanged");
        clear(owner);

        var tombstone = (MaidTombstoneEntity) find(world, data.tombstone, MaidTombstoneEntity.class);
        require(
            data.owner.equals(tombstone.getOwnerId()) && data.tombstoneToken.equals(tombstone.getToken()),
            "tombstone identity loaded from actual entity NBT");
        require(
            data.run.equals(
                tombstone.getEntityData()
                    .getString(RUN_TAG)),
            "persisted tombstone marker");
        verifyStoredInventory(world, data);
        owner.setPosition(tombstone.posX + 2, tombstone.posY, tombstone.posZ);
        require(owner.interactWith(tombstone) && tombstone.isDead, "native interaction claimed restored tombstone");
        verifyReceivedInventory(owner, data);
        require(ledger.tombstoneEmpty(data.tombstoneToken), "authoritative tombstone source cleared");
        var received = owner.inventory.writeToNBT(new NBTTagList());
        owner.interactWith(tombstone);
        require(
            received.equals(owner.inventory.writeToNBT(new NBTTagList())),
            "repeated native claim creates no items");
        require(ledger.reserveTombstone(data.tombstoneToken) == null, "claimed token has no second inventory");
        clear(owner);
        cleanup(world, data);
        data.phase = "VERIFIED";
        data.markDirty();
        TLMNotHard.LOG.info(
            "TLMNH_LIFECYCLE_RESTART_PASS run={} oldProcessStart={} processStart={} initial=gifted-and-redeemed-no-regrant film=native-invalid-credential owner=unchanged tombstone=12-slots-78-arrows-one-film-once cleanup=entities-and-platform playerDatVerified=false crashAtomicityVerified=false",
            data.run,
            data.processStart,
            PROCESS_START);
    }

    private static MaidTombstoneEntity kill(WorldServer world, EntityMaid maid, ProbeData data) {
        maid.setHealth(0);
        maid.onDeath(DamageSource.generic);
        var token = MaidLifecycleData.get(world)
            .deathToken(maid.getUniqueID());
        require(token != null, "native death hook deposited inventory");
        MaidTombstoneEntity found = null;
        for (var entity : world.loadedEntityList)
            if (entity instanceof MaidTombstoneEntity tombstone && token.equals(tombstone.getToken())
                && MaidSpawnChecks.present(tombstone, world)) {
                    require(found == null, "one tombstone per actual death");
                    found = tombstone;
                }
        require(found != null, "native death published tombstone");
        for (int slot = 0; slot < MaidInventory.TOTAL_SIZE; slot++) require(
            maid.getMaidInventory()
                .getStackInSlot(slot) == null,
            "dead inventory transferred");
        mark(found, data);
        world.removeEntity(maid);
        return found;
    }

    private static void verifyStoredInventory(WorldServer world, ProbeData data) {
        var ledger = MaidLifecycleData.get(world);
        var source = ledger.reserveTombstone(data.tombstoneToken);
        require(source != null && source.tagCount() == 13, "twelve actual inventory stacks plus one film");
        try {
            var seen = new HashSet<Integer>();
            int arrows = 0, films = 0;
            for (int i = 0; i < source.tagCount(); i++) {
                var stack = ItemStack.loadItemStackFromNBT(source.getCompoundTagAt(i));
                if (stack != null && stack.getItem() == Items.arrow) {
                    int slot = slot(stack, data);
                    require(seen.add(slot) && stack.stackSize == slot + 1, "stored slot formula");
                    arrows += stack.stackSize;
                } else {
                    require(
                        MaidLifecycleItems.isFilm(stack) && data.tombstoneToken.equals(MaidLifecycleItems.token(stack)),
                        "stored film credential");
                    films++;
                }
            }
            require(seen.size() == 12 && arrows == 78 && films == 1, "authoritative stored inventory counts");
        } finally {
            ledger.rollback(data.tombstoneToken);
        }
    }

    private static void verifyReceivedInventory(ProbePlayer owner, ProbeData data) {
        var seen = new HashSet<Integer>();
        int arrows = 0, films = 0;
        for (var stack : owner.inventory.mainInventory) {
            if (stack == null) continue;
            if (stack.getItem() == Items.arrow) {
                int slot = slot(stack, data);
                require(seen.add(slot) && stack.stackSize == slot + 1, "received slot formula");
                arrows += stack.stackSize;
            } else {
                require(
                    MaidLifecycleItems.isFilm(stack) && data.tombstoneToken.equals(MaidLifecycleItems.token(stack)),
                    "received one original film");
                films++;
            }
        }
        require(
            seen.size() == 12 && arrows == 78 && films == 1,
            "actual received twelve slots, 78 arrows and one film");
    }

    private static int slot(ItemStack stack, ProbeData data) {
        for (int i = 0; i < MaidInventory.TOTAL_SIZE; i++) if (stack.getDisplayName()
            .equals(slotName(data, i))) return i;
        throw new IllegalStateException("Unexpected restart inventory stack");
    }

    private static String slotName(ProbeData data, int slot) {
        return "restart-" + data.run + "-slot-" + slot;
    }

    private static String name(ProbeData data, String kind) {
        return "TLMNH restart " + kind + " " + data.run;
    }

    private static void protect(EntityMaid maid, ProbeData data) {
        maid.setSitting(true);
        maid.getBrainCoordinator()
            .setSuspended(true);
        maid.setPickupItems(false);
        maid.setPickupExperience(false);
        var saved = new NBTTagCompound();
        maid.writeToNBT(saved);
        saved.setBoolean("Invulnerable", true);
        maid.readFromNBT(saved);
        mark(maid, data);
    }

    private static void mark(Entity entity, ProbeData data) {
        entity.getEntityData()
            .setString(RUN_TAG, data.run);
    }

    private static ProbePlayer player(WorldServer world, UUID id) {
        var player = new ProbePlayer(world, id);
        new NetHandlerPlayServer(MinecraftServer.getServer(), new NetworkManager(false), player);
        player.theItemInWorldManager.setGameType(GameType.SURVIVAL);
        return player;
    }

    private static void position(ProbePlayer player, ProbeData data, int offset) {
        player.setPosition(data.x + offset + 0.5D, data.y + 1, data.z + 2.5D);
    }

    private static void click(ProbePlayer player, WorldServer world, int x, int y, int z) {
        player.inventory.currentItem = 0;
        require(
            player.theItemInWorldManager
                .activateBlockOrUseItem(player, world, player.getCurrentEquippedItem(), x, y, z, 1, 0.5F, 1, 0.5F),
            "native item/block path handled");
    }

    private static ItemStack findItem(ProbePlayer player, net.minecraft.item.Item item) {
        ItemStack found = null;
        for (var stack : player.inventory.mainInventory) if (stack != null && stack.getItem() == item) {
            require(found == null, "one fixture item");
            found = stack;
        }
        require(found != null, "fixture item exists");
        return found;
    }

    private static void equip(ProbePlayer player, ItemStack stack) {
        for (int slot = 0; slot < player.inventory.mainInventory.length; slot++)
            if (player.inventory.mainInventory[slot] == stack) {
                player.inventory.mainInventory[slot] = player.inventory.mainInventory[0];
                player.inventory.mainInventory[0] = stack;
                return;
            }
        throw new IllegalStateException("Fixture item not in inventory");
    }

    private static boolean inventoryEmpty(ProbePlayer player) {
        for (var stack : player.inventory.mainInventory) if (stack != null) return false;
        return true;
    }

    private static void clear(ProbePlayer player) {
        for (int i = 0; i < player.inventory.mainInventory.length; i++) player.inventory.mainInventory[i] = null;
        player.inventory.setItemStack(null);
    }

    private static EntityMaid onlyOwnedMaid(WorldServer world, UUID owner) {
        EntityMaid found = null;
        for (var entity : world.loadedEntityList)
            if (entity instanceof EntityMaid maid && MaidSpawnChecks.present(maid, world)
                && maid.isTamed()
                && owner.toString()
                    .equals(maid.func_152113_b())) {
                        require(found == null, "one live maid for isolated owner");
                        found = maid;
                    }
        require(found != null, "isolated owner's live maid");
        return found;
    }

    private static int ownedMaidCount(WorldServer world, UUID owner) {
        int value = 0;
        for (var entity : world.loadedEntityList)
            if (entity instanceof EntityMaid maid && MaidSpawnChecks.present(maid, world)
                && owner.toString()
                    .equals(maid.func_152113_b()))
                value++;
        return value;
    }

    private static Entity find(WorldServer world, UUID id, Class<? extends Entity> type) {
        Entity found = null;
        for (var entity : world.loadedEntityList)
            if (type.isInstance(entity) && id.equals(((Entity) entity).getUniqueID())
                && MaidSpawnChecks.present((Entity) entity, world)) {
                    require(found == null, "unique restored fixture UUID");
                    found = (Entity) entity;
                }
        require(found != null, "restored fixture entity " + id);
        return found;
    }

    private static int count(WorldServer world, UUID id) {
        int value = 0;
        for (var entity : world.loadedEntityList)
            if (id.equals(((Entity) entity).getUniqueID()) && !((Entity) entity).isDead) value++;
        return value;
    }

    private static MaidShrineTile shrine(WorldServer world, ProbeData data) {
        require(world.getBlock(data.x, data.y, data.z) == MaidResurrectionContent.SHRINE, "actual saved shrine block");
        require(world.getTileEntity(data.x, data.y, data.z) instanceof MaidShrineTile, "actual saved shrine tile");
        return (MaidShrineTile) world.getTileEntity(data.x, data.y, data.z);
    }

    private static void withGiftEnabled(Runnable operation) {
        boolean original = MaidAcquisitionConfig.giveInitialSlab;
        MaidAcquisitionConfig.giveInitialSlab = true;
        try {
            operation.run();
        } finally {
            MaidAcquisitionConfig.giveInitialSlab = original;
        }
    }

    private static void preflight(WorldServer world, ProbeData data) {
        require(data.y + 4 < world.getHeight(), "fixture fits world height");
        for (int dx = -3; dx <= 3; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = 0; dy <= 3; dy++) {
            require(world.blockExists(data.x + dx, data.y + dy, data.z + dz), "fixture spawn chunks already loaded");
            require(
                world.isAirBlock(data.x + dx, data.y + dy, data.z + dz)
                    && world.getTileEntity(data.x + dx, data.y + dy, data.z + dz) == null,
                "fixture area must be untouched air");
        }
        var bounds = AxisAlignedBB.getBoundingBox(data.x - 3, data.y, data.z - 2, data.x + 4, data.y + 4, data.z + 3);
        require(
            world.getEntitiesWithinAABBExcludingEntity(null, bounds)
                .isEmpty(),
            "fixture area contains no original entities");
    }

    private static void platform(WorldServer world, ProbeData data) {
        for (int dx = -3; dx <= 3; dx++) for (int dz = -2; dz <= 2; dz++)
            require(world.setBlock(data.x + dx, data.y, data.z + dz, Blocks.stone), "create isolated platform");
    }

    private static void cleanup(WorldServer world, ProbeData data) {
        for (int dx = -3; dx <= 3; dx++) for (int dz = -2; dz <= 2; dz++) {
            var block = world.getBlock(data.x + dx, data.y, data.z + dz);
            require(
                block == (dx == 0 && dz == 0 ? MaidResurrectionContent.SHRINE : Blocks.stone),
                "refuse to remove modified fixture platform");
        }
        var shrine = shrine(world, data);
        require(
            ItemStack.areItemStacksEqual(shrine.extract(), data.usedFilm),
            "remove only fixture's used-film clone before clearing block");
        for (var entity : new java.util.ArrayList<>(world.loadedEntityList)) {
            var current = (Entity) entity;
            if (data.run.equals(
                current.getEntityData()
                    .getString(RUN_TAG)))
                world.removeEntity(current);
        }
        for (int dx = -3; dx <= 3; dx++) for (int dz = -2; dz <= 2; dz++) {
            require(world.setBlockToAir(data.x + dx, data.y, data.z + dz), "clear fixture platform");
            require(
                world.isAirBlock(data.x + dx, data.y, data.z + dz)
                    && world.getTileEntity(data.x + dx, data.y, data.z + dz) == null,
                "original air restored");
        }
        for (var entity : world.loadedEntityList) {
            var current = (Entity) entity;
            require(
                !data.run.equals(
                    current.getEntityData()
                        .getString(RUN_TAG))
                    || !MaidSpawnChecks.present(current, world),
                "fixture entities removed");
        }
    }

    private static ProbeData load(WorldServer world) {
        var data = (ProbeData) world.mapStorage.loadData(ProbeData.class, DATA_NAME);
        if (data == null) {
            var file = world.getSaveHandler()
                .getMapFileFromName(DATA_NAME);
            require(file == null || !file.exists(), "existing fixture metadata could not be read");
        } else require(data.healthy, "invalid fixture metadata");
        return data;
    }

    private static void guard(WorldServer world) {
        require(Boolean.getBoolean("tlmnh.devCommands"), "development commands must be enabled");
        require(world != null && !world.isRemote && !AnimationLab.isLab(world), "non-lab server world required");
    }

    private static void require(boolean value, String detail) {
        if (!value) throw new IllegalStateException("TLMNH lifecycle restart probe failed: " + detail);
    }

    /** 仅记录生产分派的提示键，不替换服务端生命周期判断 */
    private static final class ProbePlayer extends EntityPlayerMP {

        private String lastTranslation = "";

        private ProbePlayer(WorldServer world, UUID id) {
            super(
                MinecraftServer.getServer(),
                world,
                new GameProfile(id, "[TLMNH-Restart]"),
                new ItemInWorldManager(world));
        }

        @Override
        public void addChatMessage(IChatComponent message) {
            if (message instanceof ChatComponentTranslation translation) lastTranslation = translation.getKey();
            super.addChatMessage(message);
        }
    }

    /** 只保存期望身份和凭证快照，实际库存始终来自区块实体及MaidLifecycleData */
    public static final class ProbeData extends WorldSavedData {

        private String run = "";
        private String phase = "CREATING";
        private UUID owner, giftedOwner, firstDeadMaid, secondDeadMaid, revivedMaid, tombstone, tombstoneToken;
        private int dimension, x, y, z;
        private long processStart;
        private ItemStack initialSlab, giftedSlab, usedFilm;
        private boolean loadedFromDisk;
        private boolean healthy = true;

        public ProbeData(String name) {
            super(name);
        }

        @Override
        public void readFromNBT(NBTTagCompound tag) {
            healthy = false;
            require(tag.getInteger("Schema") == 1, "fixture metadata schema");
            run = tag.getString("Run");
            phase = tag.getString("Phase");
            owner = uuid(tag, "Owner");
            giftedOwner = uuid(tag, "GiftedOwner");
            firstDeadMaid = uuid(tag, "FirstDeadMaid");
            secondDeadMaid = uuid(tag, "SecondDeadMaid");
            revivedMaid = uuid(tag, "RevivedMaid");
            tombstone = uuid(tag, "Tombstone");
            tombstoneToken = uuid(tag, "TombstoneToken");
            dimension = tag.getInteger("Dimension");
            x = tag.getInteger("X");
            y = tag.getInteger("Y");
            z = tag.getInteger("Z");
            processStart = tag.getLong("ProcessStart");
            initialSlab = ItemStack.loadItemStackFromNBT(tag.getCompoundTag("InitialSlab"));
            giftedSlab = ItemStack.loadItemStackFromNBT(tag.getCompoundTag("GiftedSlab"));
            usedFilm = ItemStack.loadItemStackFromNBT(tag.getCompoundTag("UsedFilm"));
            UUID.fromString(run);
            require(
                owner != null && giftedOwner != null
                    && !owner.equals(giftedOwner)
                    && processStart > 0
                    && y == HEIGHT
                    && Math.abs((long) x) < 29999990
                    && Math.abs((long) z) < 29999990,
                "fixture metadata identity and area");
            require(
                phase.equals("CREATING") || phase.equals("PENDING")
                    || phase.equals("VERIFIED")
                    || phase.equals("FAILED_CREATE"),
                "fixture metadata phase");
            if (phase.equals("PENDING")) require(
                firstDeadMaid != null && secondDeadMaid != null
                    && revivedMaid != null
                    && tombstone != null
                    && tombstoneToken != null
                    && initialSlab != null
                    && giftedSlab != null
                    && usedFilm != null,
                "complete pending fixture metadata");
            loadedFromDisk = true;
            healthy = true;
        }

        @Override
        public void writeToNBT(NBTTagCompound tag) {
            require(healthy, "refuse to overwrite invalid fixture metadata");
            tag.setInteger("Schema", 1);
            tag.setString("Run", run);
            tag.setString("Phase", phase);
            put(tag, "Owner", owner);
            put(tag, "GiftedOwner", giftedOwner);
            put(tag, "FirstDeadMaid", firstDeadMaid);
            put(tag, "SecondDeadMaid", secondDeadMaid);
            put(tag, "RevivedMaid", revivedMaid);
            put(tag, "Tombstone", tombstone);
            put(tag, "TombstoneToken", tombstoneToken);
            tag.setInteger("Dimension", dimension);
            tag.setInteger("X", x);
            tag.setInteger("Y", y);
            tag.setInteger("Z", z);
            tag.setLong("ProcessStart", processStart);
            if (initialSlab != null) tag.setTag("InitialSlab", initialSlab.writeToNBT(new NBTTagCompound()));
            if (giftedSlab != null) tag.setTag("GiftedSlab", giftedSlab.writeToNBT(new NBTTagCompound()));
            if (usedFilm != null) tag.setTag("UsedFilm", usedFilm.writeToNBT(new NBTTagCompound()));
        }

        private static UUID uuid(NBTTagCompound tag, String key) {
            return tag.hasKey(key, 8) ? UUID.fromString(tag.getString(key)) : null;
        }

        private static void put(NBTTagCompound tag, String key, UUID value) {
            if (value != null) tag.setString(key, value.toString());
        }
    }
}
