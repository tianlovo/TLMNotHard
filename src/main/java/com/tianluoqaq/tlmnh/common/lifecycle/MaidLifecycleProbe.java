package com.tianluoqaq.tlmnh.common.lifecycle;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.util.DamageSource;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings.GameType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingSpawnEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.config.MaidAcquisitionConfig;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventory;
import com.tianluoqaq.tlmnh.common.registry.MaidResurrectionContent;

import cpw.mods.fml.common.eventhandler.Event;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** 真实服务端生命周期夹具；不代替跨进程重启或客户端外观验收 */
public final class MaidLifecycleProbe {

    private MaidLifecycleProbe() {}

    public static void run(WorldServer world) {
        try (var f = new Fixture(world)) {
            acquisition(f);
            var maid = release(f, false);
            maid.setCustomNameTag("生命周期 酒狐");
            maid.setModelId("geckolib:winefox_hanfu");
            maid.setExperience(155);
            maid.setFavorability(23);
            for (int slot = 0; slot < MaidInventory.TOTAL_SIZE; slot++) maid.getMaidInventory()
                .setInventorySlotContents(slot, new ItemStack(Items.arrow, slot + 1));
            var tombstone = death(f, maid, true);
            collect(f, tombstone);
            revive(f);
            release(f, true);
            forceAndRetry(f);
            cancelledDeathAndPendingPublication(f);
            brokenShrineRecovery(f);
            TLMNotHard.LOG.info(
                "TLMNH_LIFECYCLE_TEST_PASS acquisition=4-combinations initial=bound-single-use death=tombstone-and-void shrine=health-and-spawn-rollback duplicates=rejected restart=not-claimed visuals=not-claimed");
        }
    }

    private static void acquisition(Fixture f) {
        boolean oldGive = MaidAcquisitionConfig.giveInitialSlab;
        boolean oldNatural = MaidAcquisitionConfig.naturalSpawning;
        var oldDimensions = MaidAcquisitionConfig.naturalDimensions;
        try {
            MaidAcquisitionConfig.naturalDimensions = new int[] { f.world.provider.dimensionId };
            for (boolean natural : new boolean[] { false, true }) {
                for (boolean give : new boolean[] { false, true }) {
                    var player = Fixture.player(f.world);
                    player.setPosition(f.owner.posX, f.owner.posY, f.owner.posZ);
                    MaidAcquisitionConfig.naturalSpawning = natural;
                    MaidAcquisitionConfig.giveInitialSlab = give;
                    require(MaidLifecycleEvents.giveInitial(player) == give, "independent initial switch");
                    require(!MaidLifecycleEvents.giveInitial(player), "no repeated login gift");
                    var check = new LivingSpawnEvent.CheckSpawn(new EntityMaid(f.world), f.world, f.x, f.y + 1, f.z);
                    new MaidLifecycleEvents().onSpawn(check);
                    require((check.getResult() == Event.Result.DENY) == !natural, "independent natural spawn gate");
                }
            }
            MaidAcquisitionConfig.giveInitialSlab = true;
            var full = Fixture.player(f.world);
            full.setPosition(f.owner.posX, f.owner.posY, f.owner.posZ);
            for (int slot = 0; slot < full.inventory.mainInventory.length; slot++)
                full.inventory.mainInventory[slot] = new ItemStack(Items.stick, 64);
            require(!MaidLifecycleEvents.giveInitial(full), "full inventory waits");
            full.inventory.mainInventory[0] = null;
            require(MaidLifecycleEvents.giveInitial(full), "gift delivered after slot available");
            require(!MaidLifecycleEvents.giveInitial(full), "gift not repeated after delivery");
        } finally {
            MaidAcquisitionConfig.giveInitialSlab = oldGive;
            MaidAcquisitionConfig.naturalSpawning = oldNatural;
            MaidAcquisitionConfig.naturalDimensions = oldDimensions;
        }
    }

    private static EntityMaid release(Fixture f, boolean creative) {
        f.clear();
        f.owner.theItemInWorldManager.setGameType(creative ? GameType.CREATIVE : GameType.SURVIVAL);
        // 每位玩家一次赠送，创造分支使用独立真实服务端玩家
        if (creative) f.owner = Fixture.player(f.world);
        f.owner.theItemInWorldManager.setGameType(creative ? GameType.CREATIVE : GameType.SURVIVAL);
        f.owner.setPosition(f.x + 2.5D, f.y + 1, f.z + 0.5D);
        f.stranger.setPosition(f.owner.posX, f.owner.posY, f.owner.posZ);
        var ledger = MaidLifecycleData.get(f.world);
        var token = ledger.initialToken(f.owner.getUniqueID());
        var slab = MaidLifecycleItems.credential(MaidResurrectionContent.INITIAL_SLAB, token, f.owner.getUniqueID());
        f.hand(slab);
        var copy = slab.copy();
        f.stranger.inventory.mainInventory[0] = copy;
        require(
            MaidLifecycleService.releaseInitial(f.stranger, copy, f.x, f.y, f.z)
                == MaidLifecycleService.Result.WRONG_OWNER,
            "initial owner cannot be replaced");
        f.world.setBlock(f.x, f.y + 1, f.z, Blocks.stone);
        require(
            MaidLifecycleService.releaseInitial(f.owner, slab, f.x, f.y, f.z) == MaidLifecycleService.Result.BLOCKED,
            "blocked release retains credential");
        f.world.setBlockToAir(f.x, f.y + 1, f.z);
        var reject = new RejectSpawn(f.world, true);
        MinecraftForge.EVENT_BUS.register(reject);
        try {
            require(
                MaidLifecycleService.releaseInitial(f.owner, slab, f.x, f.y, f.z)
                    == MaidLifecycleService.Result.SPAWN_REJECTED,
                "cancelled release rejected");
        } finally {
            MinecraftForge.EVENT_BUS.unregister(reject);
        }
        for (var mutation : new Mutation[] { Mutation.MAID_DEAD, Mutation.MAID_OWNER }) {
            var mutate = new MutateJoin(f.world, mutation, f.owner);
            MinecraftForge.EVENT_BUS.register(mutate);
            try {
                require(
                    MaidLifecycleService.releaseInitial(f.owner, slab, f.x, f.y, f.z)
                        != MaidLifecycleService.Result.SUCCESS,
                    "post-spawn mutation rejects initial " + mutation);
                require(f.owner.getCurrentEquippedItem() == slab, "mutated initial retains credential");
            } finally {
                MinecraftForge.EVENT_BUS.unregister(mutate);
            }
        }
        require(
            f.owner.theItemInWorldManager
                .activateBlockOrUseItem(f.owner, f.world, slab, f.x, f.y, f.z, 1, 0.5F, 1, 0.5F),
            "real ItemInWorldManager soul slab path");
        require(
            f.owner.getCurrentEquippedItem()
                .getItem() == MaidResurrectionContent.EMPTY_SLAB,
            "success replaces whole hand including creative");
        var maid = f.latestMaid();
        require(maid.isOwner(f.owner), "released maid already bound");
        f.hand(copy);
        require(
            MaidLifecycleService.releaseInitial(f.owner, copy, f.x, f.y, f.z)
                == MaidLifecycleService.Result.INVALID_CREDENTIAL,
            "copied initial credential cannot spawn twice");
        maid.getBrainCoordinator()
            .setSuspended(true);
        // 创造分支只验证释放，清除实体使下一组安全落点不受其影响
        if (creative) f.world.removeEntity(maid);
        return maid;
    }

    private static MaidTombstoneEntity death(Fixture f, EntityMaid maid, boolean loot) {
        String original = f.world.getGameRules()
            .getGameRuleStringValue("doMobLoot");
        try {
            f.world.getGameRules()
                .setOrCreateGameRule("doMobLoot", Boolean.toString(loot));
            maid.setPosition(f.x + 0.5D, -80, f.z + 0.5D);
            maid.setHealth(0);
            maid.onDeath(DamageSource.outOfWorld);
            require(MaidDeathService.preserve(maid), "death already handled on repeated callback");
            for (int slot = 0; slot < MaidInventory.TOTAL_SIZE; slot++) require(
                maid.getMaidInventory()
                    .getStackInSlot(slot) == null,
                "death inventory transferred once");
            var token = MaidLifecycleData.get(f.world)
                .deathToken(maid.getUniqueID());
            var tombstone = f.tombstone(token);
            require(tombstone != null && tombstone.posY == 5, "void death has one safe tombstone");
            f.world.removeEntity(maid);
            f.owner.setPosition(tombstone.posX + 2, tombstone.posY, tombstone.posZ);
            f.stranger.setPosition(f.owner.posX, f.owner.posY, f.owner.posZ);
            return tombstone;
        } finally {
            f.world.getGameRules()
                .setOrCreateGameRule("doMobLoot", original);
        }
    }

    private static void collect(Fixture f, MaidTombstoneEntity tombstone) {
        f.clear();
        require(!MaidTombstoneTransfer.collect(tombstone, f.stranger), "stranger cannot loot tombstone");
        for (int slot = 0; slot < f.owner.inventory.mainInventory.length; slot++)
            f.owner.inventory.mainInventory[slot] = new ItemStack(Items.stick, 64);
        require(
            !MaidTombstoneTransfer.collect(tombstone, f.owner) && !tombstone.isDead,
            "full inventory leaves entire tombstone");
        f.clear();
        var saved = new NBTTagCompound();
        tombstone.writeToNBT(saved);
        var duplicate = new MaidTombstoneEntity(f.world);
        duplicate.readFromNBT(saved);
        require(f.owner.interactWith(tombstone) && tombstone.isDead, "real owner interaction collects tombstone");
        require(
            f.count(Items.arrow) == 78 && f.count(MaidResurrectionContent.FILM) == 1,
            "all twelve slots and one film recovered");
        require(!MaidTombstoneTransfer.collect(duplicate, f.owner), "copied tombstone NBT has no second inventory");
        require(
            f.count(Items.arrow) == 78 && f.count(MaidResurrectionContent.FILM) == 1,
            "duplicate claim conserved inventory");
    }

    private static void revive(Fixture f) {
        f.owner.setPosition(f.x + 2.5D, f.y + 1, f.z + 0.5D);
        f.world.setBlock(f.x, f.y, f.z, MaidResurrectionContent.SHRINE);
        var shrine = (MaidShrineTile) f.world.getTileEntity(f.x, f.y, f.z);
        for (int slot = 0; slot < f.owner.inventory.mainInventory.length; slot++) {
            var stack = f.owner.inventory.mainInventory[slot];
            if (stack != null && stack.getItem() == MaidResurrectionContent.FILM) {
                var swap = f.owner.inventory.mainInventory[0];
                f.owner.inventory.mainInventory[0] = stack;
                f.owner.inventory.mainInventory[slot] = swap;
                break;
            }
        }
        var film = f.owner.getCurrentEquippedItem();
        var duplicate = film.copy();
        f.click();
        require(
            shrine.getFilm() != null && f.owner.getCurrentEquippedItem() == null,
            "actual block insertion transfers one film");
        var saved = new NBTTagCompound();
        shrine.writeToNBT(saved);
        var restored = new MaidShrineTile();
        restored.readFromNBT(saved);
        f.world.setTileEntity(f.x, f.y, f.z, restored);
        shrine = restored;
        f.owner.setHealth(10.999F);
        f.click();
        require(shrine.getFilm() != null && f.owner.getHealth() == 10.999F, "low health preserves film and health");
        f.owner.setHealth(11);
        f.world.setBlock(f.x, f.y + 1, f.z, Blocks.stone);
        f.click();
        require(shrine.getFilm() != null && f.owner.getHealth() == 11, "blocked shrine preserves film and health");
        f.world.setBlockToAir(f.x, f.y + 1, f.z);
        var reject = new RejectSpawn(f.world, true);
        MinecraftForge.EVENT_BUS.register(reject);
        try {
            f.click();
        } finally {
            MinecraftForge.EVENT_BUS.unregister(reject);
        }
        require(shrine.getFilm() != null && f.owner.getHealth() == 11, "cancelled shrine spawn rolls back both costs");
        for (var mutation : new Mutation[] { Mutation.MAID_DEAD, Mutation.MAID_OWNER }) {
            var mutate = new MutateJoin(f.world, mutation, f.owner);
            MinecraftForge.EVENT_BUS.register(mutate);
            try {
                f.click();
            } finally {
                MinecraftForge.EVENT_BUS.unregister(mutate);
            }
            require(
                shrine.getFilm() != null && f.owner.getHealth() == 11,
                "mutated resurrection retains film and life " + mutation);
        }
        f.click();
        require(
            shrine.getFilm() == null && f.owner.getHealth() == 0.25F,
            "successful shrine consumes film and original health cost");
        var revived = f.latestMaid();
        require(
            revived.isOwner(f.owner) && revived.getModelId()
                .equals("geckolib:winefox_hanfu")
                && revived.getCustomNameTag()
                    .equals("生命周期 酒狐")
                && revived.getFavorability() == 23,
            "resurrection preserves owner and identity");
        require(
            revived.getExperience() == 0 && revived.getHealth() == revived.getMaxHealth(),
            "resurrection clears XP and restores health");
        for (int slot = 0; slot < MaidInventory.TOTAL_SIZE; slot++) require(
            revived.getMaidInventory()
                .getStackInSlot(slot) == null,
            "film never clones death inventory");
        require(shrine.insert(duplicate), "duplicate film can be inspected without redemption");
        f.owner.setHealth(20);
        require(
            MaidLifecycleService.resurrect(f.owner, shrine) == MaidLifecycleService.Result.INVALID_CREDENTIAL,
            "duplicate film cannot revive twice");
        shrine.extract();
        f.world.removeEntity(revived);
        f.world.setBlock(f.x, f.y, f.z, Blocks.stone);
    }

    private static void forceAndRetry(Fixture f) {
        f.clear();
        f.owner.theItemInWorldManager.setGameType(GameType.SURVIVAL);
        var maid = new EntityMaid(f.world);
        require(maid.bindTo(f.owner.getUniqueID()), "secondary death owner");
        maid.getMaidInventory()
            .setInventorySlotContents(0, new ItemStack(Items.diamond, 3));
        var tombstone = death(f, maid, false);
        for (int slot = 0; slot < f.owner.inventory.mainInventory.length; slot++)
            f.owner.inventory.mainInventory[slot] = new ItemStack(Items.stick, 64);
        f.owner.setSneaking(true);
        var reject = new RejectSpawn(f.world, false);
        MinecraftForge.EVENT_BUS.register(reject);
        try {
            require(!MaidTombstoneTransfer.collect(tombstone, f.owner), "force claim rejects cancelled drops");
        } finally {
            MinecraftForge.EVENT_BUS.unregister(reject);
        }
        require(
            !tombstone.isDead && !MaidLifecycleData.get(f.world)
                .tombstoneEmpty(tombstone.getToken()),
            "rejected overflow remains in tombstone");
        for (var mutation : new Mutation[] { Mutation.ITEM_DEAD, Mutation.ITEM_STACK, Mutation.ITEM_PICKUP_CANCEL,
            Mutation.ITEM_PICKUP_MUTATE }) {
            var originalInventory = f.owner.inventory.writeToNBT(new net.minecraft.nbt.NBTTagList());
            var ledger = MaidLifecycleData.get(f.world);
            var originalSource = ledger.reserveTombstone(tombstone.getToken());
            ledger.rollback(tombstone.getToken());
            var mutate = new MutateJoin(f.world, mutation, f.owner);
            MinecraftForge.EVENT_BUS.register(mutate);
            try {
                require(!MaidTombstoneTransfer.collect(tombstone, f.owner), "mutated overflow rejected " + mutation);
            } finally {
                MinecraftForge.EVENT_BUS.unregister(mutate);
            }
            require(
                originalInventory.equals(f.owner.inventory.writeToNBT(new net.minecraft.nbt.NBTTagList())),
                "full player inventory restored after callback " + mutation);
            var retained = ledger.reserveTombstone(tombstone.getToken());
            try {
                require(originalSource.equals(retained), "mutated overflow retains entire source " + mutation);
            } finally {
                ledger.rollback(tombstone.getToken());
            }
            for (var entity : f.world.loadedEntityList)
                if (entity instanceof EntityItem item && !f.original.contains(item))
                    require(!MaidSpawnChecks.present(item), "failed claim leaves no actual spawned drop " + mutation);
        }
        require(
            MaidTombstoneTransfer.collect(tombstone, f.owner) && tombstone.isDead,
            "force retry spills and clears once");
        int diamonds = 0;
        for (var entity : f.world.loadedEntityList) {
            if (entity instanceof EntityItem item && !f.original.contains(item)
                && MaidSpawnChecks.present(item)
                && item.getEntityItem()
                    .getItem() == Items.diamond)
                diamonds += item.getEntityItem().stackSize;
        }
        require(diamonds == 3, "forced overflow has exactly one diamond stack");
        f.owner.setSneaking(false);
    }

    private static void cancelledDeathAndPendingPublication(Fixture f) {
        var cancelled = new EntityMaid(f.world);
        require(cancelled.bindTo(f.owner.getUniqueID()), "cancelled death owner");
        cancelled.getMaidInventory()
            .setInventorySlotContents(0, new ItemStack(Items.emerald, 3));
        cancelled.setHealth(0);
        var cancel = new CancelDeath(cancelled);
        MinecraftForge.EVENT_BUS.register(cancel);
        try {
            cancelled.onDeath(DamageSource.generic);
        } finally {
            MinecraftForge.EVENT_BUS.unregister(cancel);
        }
        require(
            MaidLifecycleData.get(f.world)
                .deathToken(cancelled.getUniqueID()) == null
                && cancelled.getMaidInventory()
                    .getStackInSlot(0).stackSize == 3,
            "cancelled LivingDeathEvent never deposits inventory");
        for (var mutation : new Mutation[] { Mutation.TOMB_CANCEL, Mutation.TOMB_DEAD }) {
            var maid = new EntityMaid(f.world);
            require(maid.bindTo(f.owner.getUniqueID()), "pending death owner");
            maid.getMaidInventory()
                .setInventorySlotContents(0, new ItemStack(Items.emerald, 3));
            maid.setPosition(f.x + 0.5D, -80, f.z + 0.5D);
            maid.setHealth(0);
            var mutate = new MutateJoin(f.world, mutation, f.owner);
            MinecraftForge.EVENT_BUS.register(mutate);
            try {
                maid.onDeath(DamageSource.outOfWorld);
            } finally {
                MinecraftForge.EVENT_BUS.unregister(mutate);
            }
            var ledger = MaidLifecycleData.get(f.world);
            var token = ledger.deathToken(maid.getUniqueID());
            require(
                token != null && ledger.pendingTombstones(f.world.provider.dimensionId)
                    .contains(token),
                "failed tombstone stays pending " + mutation);
            MaidDeathService.retryPending(f.world);
            MaidDeathService.retryPending(f.world);
            var tombstone = f.tombstone(token);
            require(MaidSpawnChecks.present(tombstone), "one live tombstone published on retry " + mutation);
            f.clear();
            f.owner.setPosition(tombstone.posX + 2, tombstone.posY, tombstone.posZ);
            require(
                MaidTombstoneTransfer.collect(tombstone, f.owner) && f.count(Items.emerald) == 3,
                "retry preserved exactly one inventory " + mutation);
        }
    }

    private static void brokenShrineRecovery(Fixture f) {
        for (var mutation : new Mutation[] { Mutation.ITEM_CANCEL, Mutation.ITEM_DEAD, Mutation.ITEM_STACK }) {
            f.world.setBlock(f.x, f.y, f.z, MaidResurrectionContent.SHRINE);
            var shrine = (MaidShrineTile) f.world.getTileEntity(f.x, f.y, f.z);
            var data = new NBTTagCompound();
            data.setString(
                "OwnerUUID",
                f.owner.getUniqueID()
                    .toString());
            var ledger = MaidLifecycleData.get(f.world);
            var token = ledger.newFilm(f.owner.getUniqueID(), UUID.randomUUID(), data);
            var film = MaidLifecycleItems.credential(MaidResurrectionContent.FILM, token, f.owner.getUniqueID());
            require(shrine.insert(film), "recovery fixture inserted film");
            int before = ledger.pendingReturnedFilms(f.world.provider.dimensionId)
                .size();
            var mutate = new MutateJoin(f.world, mutation, f.owner);
            MinecraftForge.EVENT_BUS.register(mutate);
            try {
                f.world.setBlockToAir(f.x, f.y, f.z);
            } finally {
                MinecraftForge.EVENT_BUS.unregister(mutate);
            }
            require(
                ledger.pendingReturnedFilms(f.world.provider.dimensionId)
                    .size() == before + 1,
                "broken shrine keeps failed film delivery " + mutation);
            MaidFilmRecovery.retryPending(f.world);
            MaidFilmRecovery.retryPending(f.world);
            require(
                ledger.pendingReturnedFilms(f.world.provider.dimensionId)
                    .size() == before,
                "broken shrine escrow delivered on retry " + mutation);
            int count = 0;
            for (var entity : f.world.loadedEntityList)
                if (entity instanceof EntityItem item && MaidSpawnChecks.present(item)
                    && token.equals(MaidLifecycleItems.token(item.getEntityItem()))) count++;
            require(count == 1, "broken shrine produces exactly one original film " + mutation);
        }
        f.world.setBlock(f.x, f.y, f.z, Blocks.stone);
    }

    private static void require(boolean value, String detail) {
        if (!value) throw new IllegalStateException("TLMNH lifecycle probe failed: " + detail);
    }

    public static final class RejectSpawn {

        private final WorldServer world;
        private final boolean maid;

        public RejectSpawn(WorldServer world, boolean maid) {
            this.world = world;
            this.maid = maid;
        }

        @SubscribeEvent
        public void onJoin(EntityJoinWorldEvent event) {
            if (event.world == world
                && (maid ? event.entity instanceof EntityMaid : event.entity instanceof EntityItem))
                event.setCanceled(true);
        }
    }

    public enum Mutation {
        MAID_DEAD,
        MAID_OWNER,
        TOMB_CANCEL,
        TOMB_DEAD,
        ITEM_CANCEL,
        ITEM_DEAD,
        ITEM_STACK,
        ITEM_PICKUP_CANCEL,
        ITEM_PICKUP_MUTATE
    }

    public static final class MutateJoin {

        private final WorldServer world;
        private final Mutation mutation;
        private final EntityPlayerMP recipient;

        public MutateJoin(WorldServer world, Mutation mutation, EntityPlayerMP recipient) {
            this.world = world;
            this.mutation = mutation;
            this.recipient = recipient;
        }

        @SubscribeEvent
        public void onJoin(EntityJoinWorldEvent event) {
            if (event.world != world) return;
            if (event.entity instanceof EntityMaid maid) {
                if (mutation == Mutation.MAID_DEAD) maid.setDead();
                if (mutation == Mutation.MAID_OWNER) maid.func_152115_b(
                    UUID.randomUUID()
                        .toString());
            } else if (event.entity instanceof MaidTombstoneEntity tombstone) {
                if (mutation == Mutation.TOMB_CANCEL) event.setCanceled(true);
                if (mutation == Mutation.TOMB_DEAD) tombstone.setDead();
            } else if (event.entity instanceof EntityItem item) {
                if (mutation == Mutation.ITEM_CANCEL) event.setCanceled(true);
                if (mutation == Mutation.ITEM_DEAD) item.setDead();
                if (mutation == Mutation.ITEM_STACK) item.setEntityItemStack(new ItemStack(Items.stick));
                if (mutation == Mutation.ITEM_PICKUP_CANCEL || mutation == Mutation.ITEM_PICKUP_MUTATE) {
                    recipient.inventory.mainInventory[0] = null;
                    recipient.inventory.addItemStackToInventory(item.getEntityItem());
                    item.setEntityItemStack(new ItemStack(Items.stick));
                    if (mutation == Mutation.ITEM_PICKUP_CANCEL) event.setCanceled(true);
                }
            }
        }
    }

    public static final class CancelDeath {

        private final EntityMaid maid;

        public CancelDeath(EntityMaid maid) {
            this.maid = maid;
        }

        @SubscribeEvent
        public void onDeath(LivingDeathEvent event) {
            if (event.entityLiving == maid) event.setCanceled(true);
        }
    }

    private static final class Fixture implements AutoCloseable {

        private final WorldServer world;
        private EntityPlayerMP owner;
        private final EntityPlayerMP stranger;
        private final Set<Entity> original = new HashSet<>();
        private final int x;
        private final int y = 180;
        private final int z;

        private Fixture(WorldServer world) {
            this.world = world;
            original.addAll(world.loadedEntityList);
            x = world.getSpawnPoint().posX + 48;
            z = world.getSpawnPoint().posZ + 48;
            world.getChunkFromBlockCoords(x, z);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                require(
                    world.isAirBlock(x + dx, y, z + dz) && world.isAirBlock(x + dx, y + 1, z + dz)
                        && world.isAirBlock(x + dx, y + 2, z + dz),
                    "fixture area must be unused air");
            }
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) world.setBlock(x + dx, y, z + dz, Blocks.stone);
            owner = player(world);
            stranger = player(world);
            owner.setPosition(x + 2.5D, y + 1, z + 0.5D);
            stranger.setPosition(owner.posX, owner.posY, owner.posZ);
        }

        private void hand(ItemStack stack) {
            owner.inventory.currentItem = 0;
            owner.inventory.mainInventory[0] = stack;
        }

        private void clear() {
            for (int i = 0; i < owner.inventory.mainInventory.length; i++) owner.inventory.mainInventory[i] = null;
        }

        private void click() {
            owner.theItemInWorldManager
                .activateBlockOrUseItem(owner, world, owner.getCurrentEquippedItem(), x, y, z, 1, 0.5F, 1, 0.5F);
        }

        private int count(net.minecraft.item.Item item) {
            int value = 0;
            for (var stack : owner.inventory.mainInventory)
                if (stack != null && stack.getItem() == item) value += stack.stackSize;
            return value;
        }

        private EntityMaid latestMaid() {
            EntityMaid found = null;
            for (var entity : world.loadedEntityList)
                if (entity instanceof EntityMaid maid && !original.contains(maid) && !maid.isDead) found = maid;
            require(found != null, "expected actual spawned maid");
            return found;
        }

        private MaidTombstoneEntity tombstone(UUID token) {
            MaidTombstoneEntity found = null;
            for (var entity : world.loadedEntityList)
                if (entity instanceof MaidTombstoneEntity tombstone && !tombstone.isDead
                    && token.equals(tombstone.getToken())) {
                        require(found == null, "one actual tombstone per death");
                        found = tombstone;
                    }
            return found;
        }

        private static EntityPlayerMP player(WorldServer world) {
            var player = new EntityPlayerMP(
                MinecraftServer.getServer(),
                world,
                new GameProfile(UUID.randomUUID(), "[TLMNH-Life]"),
                new ItemInWorldManager(world));
            new NetHandlerPlayServer(MinecraftServer.getServer(), new NetworkManager(false), player);
            player.theItemInWorldManager.setGameType(GameType.SURVIVAL);
            return player;
        }

        @Override
        public void close() {
            for (var entity : new ArrayList<>(world.loadedEntityList)) {
                if (!original.contains(entity) && (entity instanceof EntityMaid || entity instanceof MaidTombstoneEntity
                    || entity instanceof EntityItem)) world.removeEntity((Entity) entity);
            }
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                world.setBlockToAir(x + dx, y + 1, z + dz);
                world.setBlockToAir(x + dx, y, z + dz);
            }
        }
    }
}
