package com.tianluoqaq.tlmnh.qa;

import java.lang.management.ManagementFactory;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.world.WorldSavedData;
import net.minecraft.world.WorldSettings.GameType;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.world.WorldEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.gtnewhorizons.horizonqa.internal.GameTestChunkLoader;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.common.config.MaidAcquisitionConfig;
import com.tianluoqaq.tlmnh.common.development.IsolatedPlayerChannel;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleData;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleEvents;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleItems;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleRestartProbe;
import com.tianluoqaq.tlmnh.common.protection.DependencyFingerprint;
import com.tianluoqaq.tlmnh.common.registry.MaidResurrectionContent;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.registry.GameRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

/** 同一专用世界的三个真实进程；旧接口分支只调用旧版已有的生产入口 */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class LifecycleUpgradeTests {

    public static final String NAME = "TLMNH_QA_LifecycleUpgrade";
    private static final String FROZEN = "TLMNH_QA_UpgradeFrozen";
    private static final String LEGACY = "TLMNH_LifecycleRestartProbe";
    private static final long START = ManagementFactory.getRuntimeMXBean()
        .getStartTime();

    private LifecycleUpgradeTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 60, batch = "lifecycle_upgrade")
    public static void nativeSavedWorldSurvivesUpgradeAndAnotherProcessWithoutDuplicateRights(GameTestHelper helper)
        throws Exception {
        String phase = System.getProperty("tlmnh.qaLifecyclePhase", "");
        String version = Loader.instance()
            .getIndexedModList()
            .get("tlmnh")
            .getVersion();
        helper.assertTrue(
            version.equals(System.getProperty("tlmnh.qaExpectedVersion")) && DependencyFingerprint.mod("tlmnh")
                .equals(version + ":sha256:" + System.getProperty("tlmnh.qaExpectedSha")),
            "Actual loaded mod version or source hash differs from the selected artifact");
        var world = helper.getWorld();
        helper.assertTrue(
            MinecraftServer.getServer()
                .getCanSpawnAnimals(),
            "Persistence JVM must retain animals through its last native world tick");
        var spawn = world.getSpawnPoint();
        var chunks = new GameTestChunkLoader();
        helper.afterTest(chunks::releaseAll);
        chunks.forceChunksStrict(world, spawn.posX + 40, 0, spawn.posZ + 40, spawn.posX + 100, 240, spawn.posZ + 72);
        var state = (State) world.mapStorage.loadData(State.class, NAME);
        if (phase.equals("legacy")) {
            helper.assertTrue(
                state == null && !world.getSaveHandler()
                    .getMapFileFromName(NAME)
                    .exists(),
                "Legacy preparation requires a new dedicated world");
            helper.assertTrue(
                GameRegistry.findItem("tlmnh", "maid_core") == null,
                "Legacy process unexpectedly contains the new core item");
            MaidLifecycleRestartProbe.create(world);
            var probe = (MaidLifecycleRestartProbe.ProbeData) world.mapStorage
                .loadData(MaidLifecycleRestartProbe.ProbeData.class, LEGACY);
            var original = new NBTTagCompound();
            probe.writeToNBT(original);
            var ledger = new NBTTagCompound();
            MaidLifecycleData.get(world)
                .writeToNBT(ledger);
            helper.assertTrue(
                ledger.getInteger("Schema") == 1 && original.getString("Phase")
                    .equals("PENDING"),
                "Old binary did not produce its genuine schema1 world");
            state = new State(NAME);
            state.data.setInteger("Schema", 1);
            state.data.setTag("Legacy", original);
            state.data.setTag("LegacyLedger", ledger);
            world.mapStorage.setData(NAME, state);
        } else {
            helper.assertTrue(
                state != null && state.loaded && state.data.getLong("LastProcess") != START,
                "A different JVM must load fixture data from disk");
            if (phase.equals("upgraded")) upgrade(helper, state);
            else if (phase.equals("resumed")) resume(helper, state);
            else throw new IllegalArgumentException("Explicit lifecycle phase required");
        }
        state.data.setString("Phase", phase);
        state.data.setString("LastVersion", version);
        state.data.setLong("LastProcess", START);
        var current = new NBTTagCompound();
        MaidLifecycleData.get(world)
            .writeToNBT(current);
        state.data.setTag("LastLedger", current);
        state.markDirty();
        System.out.println(
            "TLMNH_LIFECYCLE_UPGRADE_PASS phase=" + phase
                + " version="
                + version
                + " schema="
                + current.getInteger("Schema")
                + " processStart="
                + START
                + " playerDatVerified=false crashAtomicityVerified=false frozenInventoryActor=true");
        helper.succeedWhen(() -> true);
    }

    private static void upgrade(GameTestHelper helper, State state) {
        helper.assertTrue(
            state.data.getString("Phase")
                .equals("legacy"),
            "Expected the old process checkpoint");
        MaidLifecycleRestartProbe.verify(helper.getWorld());
        var old = state.data.getCompoundTag("Legacy");
        int x = old.getInteger("X") + 24, y = 210, z = old.getInteger("Z");
        state.data.setInteger("X", x);
        state.data.setInteger("Y", y);
        state.data.setInteger("Z", z);
        for (int dx = -3; dx <= 6; dx++) for (int dz = -2; dz <= 2; dz++) {
            helper.assertTrue(
                helper.getWorld()
                    .isAirBlock(x + dx, y, z + dz),
                "New paid-core platform overlaps existing data");
            helper.getWorld()
                .setBlock(x + dx, y, z + dz, Blocks.stone);
        }
        var owner = player(helper, UUID.fromString(old.getString("Owner")), x + 3.5, y + 1, z + .5);
        var core = GameRegistry.findItem("tlmnh", "maid_core");
        helper.assertTrue(core != null, "Upgraded binary has no production core item");
        owner.inventory.mainInventory[0] = new ItemStack(core);
        helper.getWorld()
            .setBlock(x, y + 1, z, Blocks.stone);
        click(helper, owner, x, y, z);
        var used = owner.getCurrentEquippedItem()
            .copy();
        helper.assertTrue(
            used.getItem() == MaidResurrectionContent.INITIAL_SLAB && MaidLifecycleItems.token(used) != null,
            "Blocked production core did not produce a bound right");
        state.data.setTag("Used", used.writeToNBT(new NBTTagCompound()));
        helper.getWorld()
            .setBlockToAir(x, y + 1, z);
        click(helper, owner, x, y, z);
        helper.assertTrue(
            owner.getCurrentEquippedItem()
                .getItem() == MaidResurrectionContent.EMPTY_SLAB,
            "Paid right did not complete through the native item path");
        EntityMaid paid = null;
        for (var raw : helper.getWorld().loadedEntityList)
            if (raw instanceof EntityMaid maid && !maid.isDead && maid.isOwner(owner)) {
                helper.assertTrue(paid == null, "Upgraded old fixture left an unexpected duplicate owner actor");
                paid = maid;
            }
        helper.assertTrue(paid != null, "Paid actor did not enter the actual world");
        paid.setPosition(x - 2.5, y + 1, z + .5);
        paid.setModelId("geckolib:winefox_hanfu");
        paid.setCustomNameTag("QA-upgrade-owned");
        paid.setSitting(true);
        paid.setPickupItems(false);
        paid.setPickupExperience(false);
        paid.getEntityData()
            .setBoolean(FROZEN, true);
        paid.getBrainCoordinator()
            .setSuspended(true);
        var sword = new ItemStack(Items.diamond_sword, 1, 17);
        sword.setTagInfo(
            "UpgradePayload",
            new NBTTagString(
                UUID.randomUUID()
                    .toString()));
        var apple = new ItemStack(Items.golden_apple, 1, 1);
        apple.setTagInfo(
            "UpgradePayload",
            new NBTTagString(
                UUID.randomUUID()
                    .toString()));
        var arrows = new ItemStack(Items.arrow, 37);
        arrows.setStackDisplayName("QA-upgrade-backpack");
        paid.setCurrentItemOrArmor(0, sword);
        paid.setOffhandItem(apple);
        paid.getMaidInventory()
            .setInventorySlotContents(0, arrows);
        state.data.setString(
            "PaidMaid",
            paid.getUniqueID()
                .toString());
        state.data.setTag("Sword", sword.writeToNBT(new NBTTagCompound()));
        state.data.setTag("Apple", apple.writeToNBT(new NBTTagCompound()));
        state.data.setTag("Arrows", arrows.writeToNBT(new NBTTagCompound()));
        owner.inventory.mainInventory[0] = new ItemStack(core);
        helper.getWorld()
            .setBlock(x + 2, y + 1, z, Blocks.stone);
        click(helper, owner, x + 2, y, z);
        var pending = owner.getCurrentEquippedItem();
        helper.assertTrue(
            pending.getItem() == MaidResurrectionContent.INITIAL_SLAB && !MaidLifecycleItems.token(used)
                .equals(MaidLifecycleItems.token(pending)),
            "Second core did not retain an independent unredeemed right");
        state.data.setTag("Pending", pending.writeToNBT(new NBTTagCompound()));
    }

    private static void resume(GameTestHelper helper, State state) {
        helper.assertTrue(
            state.data.getString("Phase")
                .equals("upgraded"),
            "Expected the upgraded process checkpoint");
        var old = state.data.getCompoundTag("Legacy");
        int x = state.data.getInteger("X"), y = state.data.getInteger("Y"), z = state.data.getInteger("Z");
        var owner = player(helper, UUID.fromString(old.getString("Owner")), x + 3.5, y + 1, z + .5);
        var gifted = player(helper, UUID.fromString(old.getString("GiftedOwner")), x + 3.5, y + 1, z + .5);
        EntityMaid paid = null;
        for (var raw : helper.getWorld().loadedEntityList) if (raw instanceof EntityMaid maid && maid.getUniqueID()
            .toString()
            .equals(state.data.getString("PaidMaid"))) {
                helper.assertTrue(paid == null && !maid.isDead, "Persisted paid UUID was duplicated or dead");
                paid = maid;
            }
        helper.assertTrue(
            paid != null && paid.isOwner(owner)
                && paid.isSitting()
                && paid.getBrainCoordinator()
                    .isSuspended()
                && paid.getModelId()
                    .equals("geckolib:winefox_hanfu")
                && paid.getCustomNameTag()
                    .equals("QA-upgrade-owned"),
            "Paid actor identity or frozen data fixture did not load");
        same(helper, state.data.getCompoundTag("Sword"), paid.getHeldItem());
        same(helper, state.data.getCompoundTag("Apple"), paid.getOffhandItem());
        same(
            helper,
            state.data.getCompoundTag("Arrows"),
            paid.getMaidInventory()
                .getStackInSlot(0));
        owner.inventory.mainInventory[0] = ItemStack.loadItemStackFromNBT(state.data.getCompoundTag("Used"));
        var used = owner.getCurrentEquippedItem()
            .copy();
        click(helper, owner, x, y, z);
        helper.assertTrue(
            ItemStack.areItemStacksEqual(used, owner.getCurrentEquippedItem()) && owned(helper, owner) == 1,
            "Used paid credential redeemed again after restart");
        var pending = ItemStack.loadItemStackFromNBT(state.data.getCompoundTag("Pending"));
        owner.inventory.mainInventory[0] = pending.copy();
        helper.getWorld()
            .setBlockToAir(x + 2, y + 1, z);
        click(helper, owner, x + 2, y, z);
        helper.assertTrue(
            owner.getCurrentEquippedItem()
                .getItem() == MaidResurrectionContent.EMPTY_SLAB && owned(helper, owner) == 2,
            "Unredeemed paid credential was lost or reused the old actor");
        owner.inventory.mainInventory[0] = pending.copy();
        click(helper, owner, x + 4, y, z);
        helper.assertTrue(
            ItemStack.areItemStacksEqual(pending, owner.getCurrentEquippedItem()) && owned(helper, owner) == 2,
            "Second paid credential acquired another redemption right");
        boolean oldGift = MaidAcquisitionConfig.giveInitialSlab;
        try {
            MaidAcquisitionConfig.giveInitialSlab = true;
            helper.assertTrue(
                !MaidLifecycleEvents.giveInitial(owner) && !MaidLifecycleEvents.giveInitial(gifted),
                "Either old player received a duplicate free gift after the two restarts");
        } finally {
            MaidAcquisitionConfig.giveInitialSlab = oldGift;
        }
        var gift = ItemStack.loadItemStackFromNBT(old.getCompoundTag("GiftedSlab"));
        gifted.inventory.mainInventory[0] = gift.copy();
        click(helper, gifted, x + 4, y, z);
        helper.assertTrue(
            gifted.getCurrentEquippedItem()
                .getItem() == MaidResurrectionContent.EMPTY_SLAB && owned(helper, gifted) == 1,
            "Original schema1 unredeemed gift did not retain its owner and right");
        gifted.inventory.mainInventory[0] = gift.copy();
        click(helper, gifted, x + 6, y, z);
        helper.assertTrue(
            ItemStack.areItemStacksEqual(gift, gifted.getCurrentEquippedItem()) && owned(helper, gifted) == 1,
            "Original gift clone redeemed twice after migration");
    }

    private static int owned(GameTestHelper helper, EntityPlayerMP owner) {
        int result = 0;
        for (var raw : helper.getWorld().loadedEntityList)
            if (raw instanceof EntityMaid maid && !maid.isDead && maid.isOwner(owner)) result++;
        return result;
    }

    private static void same(GameTestHelper helper, NBTTagCompound expected, ItemStack actual) {
        helper.assertTrue(
            actual != null && expected.equals(actual.writeToNBT(new NBTTagCompound())),
            "Persisted hand/backpack count, damage or raw NBT changed");
    }

    private static void click(GameTestHelper helper, EntityPlayerMP player, int x, int y, int z) {
        helper.assertTrue(
            player.theItemInWorldManager.activateBlockOrUseItem(
                player,
                helper.getWorld(),
                player.getCurrentEquippedItem(),
                x,
                y,
                z,
                1,
                .5F,
                1,
                .5F),
            "Native lifecycle item interaction failed");
    }

    private static EntityPlayerMP player(GameTestHelper helper, UUID id, double x, double y, double z) {
        var server = MinecraftServer.getServer();
        var player = new EntityPlayerMP(
            server,
            helper.getWorld(),
            new GameProfile(id, "[QA-Upgrade]"),
            new ItemInWorldManager(helper.getWorld()));
        var manager = new NetworkManager(false);
        var channel = new EmbeddedChannel(manager);
        manager.setConnectionState(EnumConnectionState.PLAY);
        IsolatedPlayerChannel.verify(manager, channel);
        new NetHandlerPlayServer(server, manager, player);
        player.theItemInWorldManager.setGameType(GameType.SURVIVAL);
        player.setPosition(x, y, z);
        helper.afterTest(() -> {
            player.setDead();
            channel.finish();
            Object message;
            while ((message = channel.readOutbound()) != null) ReferenceCountUtil.release(message);
            while ((message = channel.readInbound()) != null) ReferenceCountUtil.release(message);
        });
        return player;
    }

    /** HQA默认关动物会在原生WorldServer更新中直接删实体；专用持久化JVM保持开启，不在测试结束时提前恢复 */
    public static final class PersistenceEnvironment {

        @SubscribeEvent
        public void loaded(WorldEvent.Load event) {
            if (!event.world.isRemote) MinecraftServer.getServer()
                .setCanSpawnAnimals(true);
        }

        @SubscribeEvent
        public void joined(EntityJoinWorldEvent event) {
            if (event.entity instanceof EntityMaid maid && maid.getEntityData()
                .getBoolean(FROZEN))
                maid.getBrainCoordinator()
                    .setSuspended(true);
        }
    }

    public static final class State extends WorldSavedData {

        NBTTagCompound data = new NBTTagCompound();
        boolean loaded;

        public State(String name) {
            super(name);
        }

        @Override
        public void readFromNBT(NBTTagCompound tag) {
            if (tag.getInteger("Schema") != 1) throw new IllegalStateException("Unknown upgrade fixture schema");
            data = (NBTTagCompound) tag.copy();
            loaded = true;
        }

        @Override
        public void writeToNBT(NBTTagCompound target) {
            for (Object raw : data.func_150296_c()) {
                String key = (String) raw;
                target.setTag(
                    key,
                    data.getTag(key)
                        .copy());
            }
        }
    }
}
