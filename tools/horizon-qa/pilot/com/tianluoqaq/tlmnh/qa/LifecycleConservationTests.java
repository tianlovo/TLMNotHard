package com.tianluoqaq.tlmnh.qa;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.util.DamageSource;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings.GameType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.common.development.IsolatedPlayerChannel;
import com.tianluoqaq.tlmnh.common.life.MaidLifeRules.Activity;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidDeathExperience;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleData;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleItems;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleService;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidShrineTile;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidSpawnChecks;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidTombstoneEntity;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidTombstoneTransfer;
import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.ProtectedItemCodec;
import com.tianluoqaq.tlmnh.common.registry.MaidResurrectionContent;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class LifecycleConservationTests {

    private LifecycleConservationTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 40, batch = "lifecycle_claim_conservation")
    public static void rejectedTombstoneClaimKeepsCallbackInventoryAndCanDeliverOnceAfterRepair(GameTestHelper helper) {
        var f = new Fixture(helper);
        for (String mutation : new String[] { "inventory", "distance", "throw", "tag" }) {
            f.resetPlayer();
            for (int slot = 0; slot < f.owner.inventory.mainInventory.length; slot++)
                f.owner.inventory.mainInventory[slot] = new ItemStack(Items.stick, 64);
            if (mutation.equals("tag")) {
                var tag = new NBTTagCompound();
                tag.setString("CallbackPayload", "before");
                f.owner.inventory.mainInventory[5].setTagCompound(tag);
            }
            f.owner.setSneaking(true);
            var item = new ItemStack(Items.diamond, 9, 3);
            item.setStackDisplayName("Conservation original");
            item.getTagCompound()
                .setString("ClaimMarker", f.owner.getUniqueID() + "/" + mutation);
            var items = new NBTTagList();
            items.appendTag(ProtectedItemCodec.encode(item));
            var token = f.ledger()
                .depositDeath(
                    f.owner.getUniqueID(),
                    UUID.randomUUID(),
                    new NBTTagCompound(),
                    items,
                    helper.getWorld().provider.dimensionId,
                    f.x + 6.5,
                    f.y + 1,
                    f.z + 4.5,
                    "[QA-Claim]");
            f.ledger()
                .markPublished(token);
            var tombstone = new MaidTombstoneEntity(helper.getWorld());
            tombstone.initialize(token, f.owner.getUniqueID(), "[QA-Claim]");
            f.owned.add(tombstone);
            helper.spawnEntity(tombstone, 6.5, 1, 4.5);
            int[] calls = { 0 };
            NBTTagList[] changed = { null };
            var injected = new IllegalStateException("Controlled tombstone claim failure");
            String faultKey = "player_inventory/tombstone/" + token;
            if (mutation.equals("throw")) f.expectFault(faultKey, injected);
            f.itemCallback = drop -> {
                calls[0]++;
                if (mutation.equals("inventory") || mutation.equals("throw")) {
                    f.owner.inventory.mainInventory[5] = new ItemStack(Items.gold_ingot, 17, 2);
                    f.owner.inventory.setItemStack(new ItemStack(Items.emerald, 3));
                    f.owner.inventory.currentItem = 1;
                    changed[0] = f.owner.inventory.writeToNBT(new NBTTagList());
                } else if (mutation.equals("tag")) {
                    f.owner.inventory.mainInventory[5].getTagCompound()
                        .setString("CallbackPayload", "callback-owned");
                    changed[0] = (NBTTagList) f.owner.inventory.writeToNBT(new NBTTagList())
                        .copy();
                } else f.owner.setPosition(f.x + 40, f.y + 1, f.z + 4);
                if (mutation.equals("throw")) throw injected;
            };
            helper
                .assertTrue(!MaidTombstoneTransfer.collect(tombstone, f.owner), "Changed claim unexpectedly committed");
            f.itemCallback = null;
            helper.assertTrue(calls[0] == 1 && !tombstone.isDead, "Controlled overflow callback was not reached once");
            var retained = f.ledger()
                .reserveTombstone(token);
            f.ledger()
                .rollback(token);
            helper.assertTrue(items.equals(retained), "Failed claim changed the original complete escrow");
            helper.assertTrue(
                !f.ledger()
                    .isTombstoneHeld(token),
                "Safely retracted overflow permanently held the claim");
            if (mutation.equals("tag")) helper.assertTrue(
                changed[0] != null && changed[0].equals(f.owner.inventory.writeToNBT(new NBTTagList()))
                    && f.owner.inventory.mainInventory[5].getTagCompound()
                        .getString("CallbackPayload")
                        .equals("callback-owned"),
                "Rejected claim overwrote the callback's in-place original item NBT");
            else if (changed[0] != null) helper.assertTrue(
                changed[0].equals(f.owner.inventory.writeToNBT(new NBTTagList())) && f.owner.inventory.currentItem == 1
                    && f.owner.inventory.getItemStack().stackSize == 3,
                "Failed claim overwrote the callback's inventory, cursor or selected slot");
            else helper.assertTrue(f.owner.posX == f.x + 40, "Rejected claim rewound the external player move");
            if (mutation.equals("throw")) f.removeExpectedFault(faultKey);
            f.assertPriorFaultsRetained();
            f.resetPlayer();
            helper.assertTrue(MaidTombstoneTransfer.collect(tombstone, f.owner), "Repaired claim could not deliver");
            helper.assertTrue(
                ItemStack.areItemStacksEqual(item, f.owner.inventory.mainInventory[0])
                    && !MaidTombstoneTransfer.collect(tombstone, f.owner),
                "Repaired claim changed original NBT or delivered twice");
            System.out.println("TLMNH_CLAIM_CONSERVATION_PASS mutation=" + mutation + " callback=1 delivery=once");
        }
        helper.succeed();
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 40, batch = "lifecycle_claim_quarantine")
    public static void rewrittenOverflowAndXpRemainHeldAcrossReloadWithoutAnotherSpawn(GameTestHelper helper) {
        var f = new Fixture(helper);
        for (int slot = 0; slot < f.owner.inventory.mainInventory.length; slot++)
            f.owner.inventory.mainInventory[slot] = new ItemStack(Items.stick, 64);
        f.owner.setSneaking(true);
        var original = new ItemStack(Items.diamond, 9, 3);
        original.setStackDisplayName("Held original");
        original.getTagCompound()
            .setString("ClaimMarker", f.owner.getUniqueID() + "/held");
        var changed = original.copy();
        changed.stackSize = 10;
        var items = new NBTTagList();
        items.appendTag(ProtectedItemCodec.encode(original));
        var token = f.ledger()
            .depositDeath(
                f.owner.getUniqueID(),
                UUID.randomUUID(),
                new NBTTagCompound(),
                items,
                helper.getWorld().provider.dimensionId,
                f.x + 6.5,
                f.y + 1,
                f.z + 4.5,
                "[QA-Held]");
        f.ledger()
            .markPublished(token);
        var grave = new MaidTombstoneEntity(helper.getWorld());
        grave.initialize(token, f.owner.getUniqueID(), "[QA-Held]");
        f.owned.add(grave);
        helper.spawnEntity(grave, 6.5, 1, 4.5);
        int[] callbacks = { 0 };
        EntityItem[] external = { null };
        f.itemCallback = drop -> {
            callbacks[0]++;
            external[0] = drop;
            drop.setEntityItemStack(changed.copy());
        };
        var inventory = f.owner.inventory.writeToNBT(new NBTTagList());
        helper.assertTrue(!MaidTombstoneTransfer.collect(grave, f.owner), "Rewritten overflow claim committed");
        helper.assertTrue(
            callbacks[0] == 1 && external[0] != null
                && MaidSpawnChecks.itemPresent(external[0], changed, helper.getWorld())
                && inventory.equals(f.owner.inventory.writeToNBT(new NBTTagList()))
                && !grave.isDead
                && f.ledger()
                    .isTombstoneHeld(token)
                && !f.ledger()
                    .isDeathExperienceHeld(token)
                && !IntegrationGuard.available(Side.SERVER, "player_inventory/tombstone/" + token),
            "Unknown overflow was deleted, replaced inventory or left the original token redeemable");
        helper.assertTrue(
            !MaidTombstoneTransfer.collect(grave, f.owner) && callbacks[0] == 1,
            "Second held claim spawned another copy");
        var restored = ledgerCopy(f.ledger());
        helper.assertTrue(
            restored.isTombstoneHeld(token) && restored.reserveTombstone(token) == null
                && items.equals(storedTombstoneItems(restored, token))
                && restored.reserve(token, MaidLifecycleData.Kind.FILM) != null,
            "Reload lost the hold, original item NBT or independent film right");
        restored.rollback(token);

        f.itemCallback = null;
        f.trackXp = true;
        f.rewriteXp = true;
        f.xpX = f.x + 9.5;
        f.xpY = f.y + 1;
        f.xpZ = f.z + 4.5;
        var xpToken = f.ledger()
            .depositDeath(
                f.owner.getUniqueID(),
                UUID.randomUUID(),
                new NBTTagCompound(),
                new NBTTagList(),
                helper.getWorld().provider.dimensionId,
                f.xpX,
                f.xpY,
                f.xpZ,
                "[QA-Held-XP]",
                53,
                0);
        f.ledger()
            .markPublished(xpToken);
        MaidDeathExperience.retryPending(helper.getWorld());
        int changedXp = EntityXPOrb.getXPSplit(53) + 1;
        helper.assertTrue(
            f.xpCallbacks == 1 && f.liveXp() == changedXp
                && f.ledger()
                    .remainingDeathExperience(xpToken) == 53
                && f.ledger()
                    .isDeathExperienceHeld(xpToken)
                && !f.ledger()
                    .isTombstoneHeld(xpToken)
                && !IntegrationGuard.available(Side.SERVER, "death_experience/" + xpToken),
            "Unknown native orb was deleted, consumed the balance or left XP publication eligible");
        MaidDeathExperience.retryPending(helper.getWorld());
        helper.assertTrue(f.xpCallbacks == 1 && f.liveXp() == changedXp, "Held XP published another native orb");
        var reloadedXp = ledgerCopy(f.ledger());
        helper.assertTrue(
            reloadedXp.isDeathExperienceHeld(xpToken) && reloadedXp.remainingDeathExperience(xpToken) == 53
                && reloadedXp.reserveDeathExperience(xpToken) == 0
                && !reloadedXp.pendingDeathExperience(helper.getWorld().provider.dimensionId, Long.MAX_VALUE)
                    .contains(xpToken)
                && reloadedXp.reserve(xpToken, MaidLifecycleData.Kind.FILM) != null,
            "Reload discarded held XP or linked the hold to film redemption");
        reloadedXp.rollback(xpToken);
        f.assertPriorFaultsRetained();
        System.out.println(
            "TLMNH_LIFECYCLE_HOLD_PASS overflow=9_to_10 secondSpawn=0 escrow=retained xp=held reload=retained film=independent");
        helper.succeed();
    }

    private static MaidLifecycleData ledgerCopy(MaidLifecycleData original) {
        var saved = new NBTTagCompound();
        original.writeToNBT(saved);
        var restored = new MaidLifecycleData("[QA-Held-Copy]");
        restored.readFromNBT(saved);
        return restored;
    }

    private static NBTTagList storedTombstoneItems(MaidLifecycleData data, UUID token) {
        var saved = new NBTTagCompound();
        data.writeToNBT(saved);
        var entries = saved.getTagList("Entries", 10);
        for (int index = 0; index < entries.tagCount(); index++) {
            var entry = entries.getCompoundTagAt(index);
            if (token.toString()
                .equals(entry.getString("Token")))
                return (NBTTagList) entry.getTagList("TombstoneItems", 10)
                    .copy();
        }
        throw new IllegalStateException("Fixture death entry is absent");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 40, batch = "lifecycle_core_conservation")
    public static void corePermissionCallbackCannotConvertAnInPlaceReplacementOrChangedPayload(GameTestHelper helper) {
        var f = new Fixture(helper);
        helper.assertTrue(
            IntegrationGuard.available(Side.SERVER, ExternalFeature.PROGRESSION_RECIPES),
            "Actual paid-core capability is unavailable; callback integrity has not been exercised");
        for (boolean changeItem : new boolean[] { false, true }) {
            f.resetPlayer();
            var core = new ItemStack(MaidResurrectionContent.MAID_CORE);
            core.setTagCompound(new NBTTagCompound());
            core.getTagCompound()
                .setString("OriginalPayload", "paid");
            f.owner.inventory.mainInventory[0] = core;
            var before = new NBTTagCompound();
            f.ledger()
                .writeToNBT(before);
            int[] callbacks = { 0 };
            f.owner.onEdit = stack -> {
                callbacks[0]++;
                if (changeItem) stack.func_150996_a(Items.stick);
                stack.getTagCompound()
                    .setString("OriginalPayload", "callback-owned");
            };
            var result = MaidLifecycleService.activateCore(f.owner, core, f.x + 6, f.y, f.z + 4);
            var after = new NBTTagCompound();
            f.ledger()
                .writeToNBT(after);
            helper.assertTrue(
                callbacks[0] == 1 && result == MaidLifecycleService.Result.DENIED
                    && f.owner.getCurrentEquippedItem() == core
                    && "callback-owned".equals(
                        core.getTagCompound()
                            .getString("OriginalPayload"))
                    && core.getItem() == (changeItem ? Items.stick : MaidResurrectionContent.MAID_CORE)
                    && before.equals(after),
                "Core conversion consumed callback data or created a new acquisition right: " + changeItem);
        }
        System.out.println("TLMNH_CORE_CONSERVATION_PASS payload=retained replacement=retained newRights=0");
        helper.succeed();
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 120, batch = "lifecycle_death_experience")
    public static void deathExperienceRetriesNativeOrbsAndResurrectionRetainsPointsWithHomeDisabled(
        GameTestHelper helper) {
        var f = new Fixture(helper);
        f.resetPlayer();
        f.trackXp = true;
        f.xpX = f.x + 6.5;
        f.xpY = f.y + 1;
        f.xpZ = f.z + 4.5;
        var maid = new EntityMaid(helper.getWorld());
        f.owned.add(maid);
        helper.assertTrue(maid.bindTo(f.owner.getUniqueID()), "Source owner binding failed");
        helper.spawnEntity(maid, 6.5, 1, 4.5);
        maid.getBrainCoordinator()
            .setSuspended(true);
        helper.assertTrue(
            maid.getHomeArea()
                .setEnabled(true)
                && maid.getHomeArea()
                    .capture(Activity.WORK),
            "Home setup failed");
        var home = maid.getHomeArea()
            .settings();
        maid.setExperience(53);
        maid.setHealth(0);
        f.cancelXp = true;
        maid.onDeath(DamageSource.generic);
        var token = f.ledger()
            .deathToken(maid.getUniqueID());
        f.cleanupXp.add(token);
        helper.assertTrue(
            token != null && maid.getExperience() == 0
                && f.ledger()
                    .remainingDeathExperience(token) == 53,
            "Death did not move all stored XP to one independent balance");
        boolean[] complete = { false };
        helper.onEachTick("native death XP retry, pickup and shrine loop", () -> {
            if (complete[0] || f.xpCallbacks == 0) return;
            helper.assertTrue(
                f.ledger()
                    .remainingDeathExperience(token) == 53 && f.liveXp() == 0
                    && !f.ledger()
                        .isDeathExperienceHeld(token),
                "Canceled native orb consumed XP or retained a second active copy");
            var saved = new NBTTagCompound();
            f.ledger()
                .writeToNBT(saved);
            var restored = new MaidLifecycleData("[QA-XP-Copy]");
            restored.readFromNBT(saved);
            helper.assertTrue(restored.remainingDeathExperience(token) == 53, "XP balance did not survive native NBT");
            f.cancelXp = false;
            f.throwXp = true;
            f.xpFailure = new IllegalStateException("Controlled native XP spawn failure");
            f.expectFault("death_experience/" + token, f.xpFailure);
            MaidDeathExperience.retryPending(helper.getWorld());
            helper.assertTrue(
                f.liveXp() == 0 && f.ledger()
                    .remainingDeathExperience(token) == 53
                    && !f.ledger()
                        .isDeathExperienceHeld(token)
                    && IntegrationGuard.faults(Side.SERVER)
                        .containsKey("death_experience/" + token),
                "Throwing XP callback consumed balance or retained another live orb");
            f.throwXp = false;
            f.removeExpectedFault("death_experience/" + token);
            MaidDeathExperience.retryPending(helper.getWorld());
            helper.assertTrue(
                f.liveXp() == 53 && f.ledger()
                    .remainingDeathExperience(token) == 0,
                "Native XP sum differs from escrow");
            int total = f.owner.experienceTotal;
            for (var entity : f.owned) if (entity instanceof EntityXPOrb orb && !orb.isDead) {
                f.owner.xpCooldown = 0;
                orb.onCollideWithPlayer(f.owner);
            }
            helper.assertTrue(
                f.owner.experienceTotal - total == 53,
                "Actual native pickup did not deliver exactly 53 XP");
            MaidDeathExperience.retryPending(helper.getWorld());
            helper.assertTrue(f.liveXp() == 0, "Completed XP escrow was delivered twice");
            helper.assertTrue(
                f.tombstone != null && MaidTombstoneTransfer.collect(f.tombstone, f.owner),
                "Death film collection failed");
            ItemStack film = null;
            for (int slot = 0; slot < f.owner.inventory.mainInventory.length; slot++)
                if (MaidLifecycleItems.isFilm(f.owner.inventory.mainInventory[slot])) {
                    film = f.owner.inventory.mainInventory[slot];
                    f.owner.inventory.mainInventory[slot] = null;
                    break;
                }
            helper.assertTrue(film != null, "Death produced no film");
            helper.setBlock(10, 0, 4, MaidResurrectionContent.SHRINE);
            var shrine = (MaidShrineTile) helper.getWorld()
                .getTileEntity(f.x + 10, f.y, f.z + 4);
            helper.assertTrue(shrine.insert(film), "Actual shrine did not accept film");
            f.owner.inventory.mainInventory[f.owner.inventory.currentItem] = null;
            f.owner.setHealth(f.owner.getMaxHealth());
            var result = MaidLifecycleService.resurrect(f.owner, shrine);
            var revived = f.revived;
            helper.assertTrue(
                result == MaidLifecycleService.Result.SUCCESS && revived != null
                    && revived != maid
                    && revived.isOwner(f.owner)
                    && !revived.getHomeArea()
                        .enabled()
                    && revived.getHomeArea()
                        .settings().configured == home.configured
                    && revived.getHomeArea()
                        .settings().dimension == home.dimension
                    && revived.getHomeArea()
                        .settings().work.toString()
                            .equals(home.work.toString())
                    && revived.getExperience() == 0
                    && f.owner.getHealth() == .25F,
                "Resurrection changed owner/points, enabled old home or copied paid death XP: " + result);
            var items = new NBTTagList();
            var huge = f.ledger()
                .depositDeath(
                    f.owner.getUniqueID(),
                    UUID.randomUUID(),
                    new NBTTagCompound(),
                    items,
                    helper.getWorld().provider.dimensionId,
                    f.xpX,
                    f.xpY,
                    f.xpZ,
                    "[QA-Huge-XP]",
                    Integer.MAX_VALUE,
                    0);
            f.ledger()
                .markPublished(huge);
            f.cleanupXp.add(huge);
            int start = f.xpCallbacks;
            MaidDeathExperience.retryPending(helper.getWorld());
            int actual = f.liveXp();
            helper.assertTrue(
                f.xpCallbacks - start > 0 && f.xpCallbacks - start <= 64
                    && actual > 0
                    && (long) actual + f.ledger()
                        .remainingDeathExperience(huge) == Integer.MAX_VALUE,
                "Large XP publication was not bounded or changed the full int total");
            complete[0] = true;
            System.out.println(
                "TLMNH_DEATH_LOOP_PASS canceledXp=retained throwingXp=retained nativePickedUp=53 home=disabled points=retained largeInt=bounded");
        });
        helper.succeedWhen(() -> complete[0]);
    }

    public static final class Fixture {

        final GameTestHelper helper;
        final ProbePlayer owner;
        final int x, y, z;
        final List<Entity> owned = new ArrayList<>();
        final List<UUID> cleanupXp = new ArrayList<>();
        final Map<String, IntegrationGuard.Fault> priorFaults = IntegrationGuard.faults(Side.SERVER);
        final Map<String, Throwable> expectedFaults = new LinkedHashMap<>();
        Consumer<EntityItem> itemCallback;
        MaidTombstoneEntity tombstone;
        EntityMaid revived;
        double xpX, xpY, xpZ;
        int xpCallbacks;
        boolean cancelXp, throwXp, trackXp, rewriteXp;
        IllegalStateException xpFailure;

        Fixture(GameTestHelper helper) {
            this.helper = helper;
            helper.assertTrue(Boolean.getBoolean("tlmnh.devCommands"), "Development fault isolation required");
            var origin = helper.absolute(0, 0, 0);
            x = origin.x();
            y = origin.y();
            z = origin.z();
            var server = MinecraftServer.getServer();
            boolean animals = server.getCanSpawnAnimals();
            server.setCanSpawnAnimals(true);
            String loot = helper.getWorld()
                .getGameRules()
                .getGameRuleStringValue("doMobLoot");
            helper.getWorld()
                .getGameRules()
                .setOrCreateGameRule("doMobLoot", "true");
            owner = new ProbePlayer(server, helper.getWorld());
            var manager = new NetworkManager(false);
            var channel = new EmbeddedChannel(manager);
            manager.setConnectionState(EnumConnectionState.PLAY);
            IsolatedPlayerChannel.verify(manager, channel);
            new NetHandlerPlayServer(server, manager, owner);
            owner.theItemInWorldManager.setGameType(GameType.SURVIVAL);
            MinecraftForge.EVENT_BUS.register(this);
            helper.afterTest(() -> {
                MinecraftForge.EVENT_BUS.unregister(this);
                try {
                    for (var entity : owned) {
                        var indexed = entity.worldObj.getEntityByID(entity.getEntityId());
                        helper.assertTrue(indexed == null || indexed == entity, "Cleanup refused foreign successor");
                        entity.worldObj.removePlayerEntityDangerously(entity);
                    }
                    for (var token : cleanupXp) {
                        int remaining = ledger().reserveDeathExperience(token);
                        if (remaining > 0) ledger().finishDeathExperience(token, remaining, remaining);
                    }
                    owner.setDead();
                    channel.finish();
                    Object packet;
                    while ((packet = channel.readOutbound()) != null) ReferenceCountUtil.release(packet);
                    while ((packet = channel.readInbound()) != null) ReferenceCountUtil.release(packet);
                } finally {
                    server.setCanSpawnAnimals(animals);
                    helper.getWorld()
                        .getGameRules()
                        .setOrCreateGameRule("doMobLoot", loot);
                    try {
                        for (String key : expectedFaults.keySet()) {
                            if (IntegrationGuard.faults(Side.SERVER)
                                .containsKey(key)) removeExpectedFault(key);
                        }
                    } finally {
                        assertPriorFaultsRetained();
                    }
                }
            });
            for (int a = 1; a < 19; a++) for (int c = 1; c < 8; c++) helper.setBlock(a, 0, c, Blocks.stone);
            resetPlayer();
        }

        MaidLifecycleData ledger() {
            return MaidLifecycleData.get(helper.getWorld());
        }

        void expectFault(String key, Throwable injected) {
            helper.assertTrue(
                IntegrationGuard.available(Side.SERVER, key),
                "Fresh fixture fault key already exists: " + key);
            expectedFaults.put(key, injected);
        }

        void assertPriorFaultsRetained() {
            var actual = IntegrationGuard.faults(Side.SERVER);
            for (var entry : priorFaults.entrySet()) helper.assertTrue(
                actual.get(entry.getKey()) == entry.getValue(),
                "Fixture replaced or removed a pre-existing fault: " + entry.getKey());
        }

        @SuppressWarnings("unchecked")
        void removeExpectedFault(String key) {
            synchronized (IntegrationGuard.class) {
                var before = IntegrationGuard.faults(Side.SERVER);
                var fault = before.get(key);
                helper.assertTrue(
                    fault != null && expectedFaults.get(key) != null
                        && fault.cause == expectedFaults.get(key)
                        && fault.side == Side.SERVER
                        && owner.getUniqueID()
                            .equals(fault.owner),
                    "Refusing to remove an unknown fault instead of the exact injected exception: " + key);
                try {
                    // 生产API只有全量reset；夹具只访问已读的同一张表，按对象身份删除本例预先注入的异常
                    var field = IntegrationGuard.class.getDeclaredField("FAULTS");
                    field.setAccessible(true);
                    var all = (Map<Side, Map<String, IntegrationGuard.Fault>>) field.get(null);
                    var server = all.get(Side.SERVER);
                    helper.assertTrue(
                        server != null && server.get(key) == fault,
                        "Guard fault identity changed during cleanup");
                    server.remove(key);
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("Cannot isolate the exact controlled QA fault", failure);
                }
                before.remove(key);
                helper.assertTrue(
                    before.equals(IntegrationGuard.faults(Side.SERVER)),
                    "Controlled cleanup changed another fault object");
                assertPriorFaultsRetained();
            }
        }

        void resetPlayer() {
            for (int slot = 0; slot < owner.inventory.mainInventory.length; slot++)
                owner.inventory.mainInventory[slot] = null;
            owner.inventory.setItemStack(null);
            owner.inventory.currentItem = 0;
            owner.setSneaking(false);
            owner.setPosition(x + 5.5, y + 1, z + 4.5);
        }

        int liveXp() {
            int sum = 0;
            for (var entity : owned) if (entity instanceof EntityXPOrb orb && !orb.isDead) sum += orb.xpValue;
            return sum;
        }

        @SubscribeEvent
        public void onJoin(EntityJoinWorldEvent event) {
            if (event.world != helper.getWorld()) return;
            if (event.entity instanceof MaidTombstoneEntity grave && owner.getUniqueID()
                .equals(grave.getOwnerId())) {
                tombstone = grave;
                if (!owned.contains(grave)) owned.add(grave);
            } else if (event.entity instanceof EntityMaid maid && maid.isOwner(owner)) {
                revived = maid;
                if (!owned.contains(maid)) owned.add(maid);
            } else if (trackXp && event.entity instanceof EntityXPOrb orb
                && orb.posX == xpX
                && orb.posY == xpY
                && orb.posZ == xpZ) {
                    xpCallbacks++;
                    owned.add(orb);
                    if (rewriteXp) orb.xpValue++;
                    if (cancelXp) event.setCanceled(true);
                    if (throwXp) throw xpFailure;
                } else if (event.entity instanceof EntityItem item && itemCallback != null) {
                    var stack = item.getEntityItem();
                    if (stack.hasTagCompound() && stack.getTagCompound()
                        .getString("ClaimMarker")
                        .startsWith(owner.getUniqueID() + "/")) {
                        owned.add(item);
                        itemCallback.accept(item);
                    }
                }
        }
    }

    public static final class ProbePlayer extends EntityPlayerMP {

        Consumer<ItemStack> onEdit;

        ProbePlayer(MinecraftServer server, WorldServer world) {
            super(server, world, new GameProfile(UUID.randomUUID(), "[QA-Life]"), new ItemInWorldManager(world));
        }

        @Override
        public boolean canPlayerEdit(int x, int y, int z, int side, ItemStack stack) {
            boolean allowed = super.canPlayerEdit(x, y, z, side, stack);
            var callback = onEdit;
            onEdit = null;
            if (allowed && callback != null) callback.accept(stack);
            return allowed;
        }
    }
}
