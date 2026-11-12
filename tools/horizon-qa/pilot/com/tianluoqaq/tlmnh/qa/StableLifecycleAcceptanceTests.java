package com.tianluoqaq.tlmnh.qa;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

import net.minecraft.entity.EntityList;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.world.WorldSavedData;
import net.minecraft.world.WorldSettings.GameType;
import net.minecraft.world.storage.SaveHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.world.WorldEvent;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidHurtTarget;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.gtnewhorizons.horizonqa.internal.GameTestChunkLoader;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.combat.CombatHitReceipt;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatNbtKeys;
import com.tianluoqaq.tlmnh.common.compat.CompatServices;
import com.tianluoqaq.tlmnh.common.config.MaidBehaviorConfig;
import com.tianluoqaq.tlmnh.common.development.IsolatedPlayerChannel;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventoryLayout;
import com.tianluoqaq.tlmnh.common.inventory.bauble.MaidBaubleLayout;
import com.tianluoqaq.tlmnh.common.life.hunger.MaidHungerNbtKeys;
import com.tianluoqaq.tlmnh.common.life.hunger.MaidHungerTuning;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleData;
import com.tianluoqaq.tlmnh.common.lifecycle.item.MaidLifecycleItems;
import com.tianluoqaq.tlmnh.common.lifecycle.world.MaidSpawnChecks;
import com.tianluoqaq.tlmnh.common.protection.DependencyFingerprint;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.registry.MaidResurrectionContent;
import com.tianluoqaq.tlmnh.common.work.bell.BreedingBellData;
import com.tianluoqaq.tlmnh.common.work.bell.MaintenanceBellData;
import com.tianluoqaq.tlmnh.common.work.breeding.config.BreedingConfigNbtKeys;
import com.tianluoqaq.tlmnh.common.work.breeding.config.BreedingTaskConfigData;
import com.tianluoqaq.tlmnh.common.work.coordinator.MaidWorkItems;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

/** 正式版物品与状态的正常收放、两 JVM 磁盘恢复，保留原未知状态和冷却守卫 */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class StableLifecycleAcceptanceTests {

    public static final String NAME = "TLMNH_QA_StableLifecycle";
    private static final long PROCESS = ManagementFactory.getRuntimeMXBean()
        .getStartTime();
    private static final String OPAQUE = "QACombatLifecycleOpaque";
    private static final int X = 1538, Y = 191, Z = 1538;
    // Codec接受的既存档输入夹具，不声称来自本轮武器攻击，不改变生产reader或时间
    private static final long SAVED_INPUT_REMAINING = 2400;

    private StableLifecycleAcceptanceTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 140, batch = "combat_lifecycle_setup")
    public static void setupOldCombatIdentityCaptureAndUnknownReceiptForNativeRestart(GameTestHelper h)
        throws Exception {
        environment(h, "setup");
        h.assertTrue(
            h.getWorld().mapStorage.loadData(State.class, NAME) == null,
            "New dedicated ordinary world required");
        MinecraftServer.getServer()
            .setCanSpawnAnimals(true);
        force(h);
        for (int x = X - 2; x <= X + 12; x++) for (int z = Z - 3; z <= Z + 4; z++) {
            h.assertTrue(
                h.getWorld()
                    .isAirBlock(x, Y, z),
                "Remote platform would overwrite existing blocks");
            h.getWorld()
                .setBlock(x, Y, z, Blocks.stone);
        }
        var owner = player(h, UUID.randomUUID());
        owner.setPosition(X + .5, Y + 1, Z + .5);
        var positive = legacyActor(h, owner, false);
        var negative = legacyActor(h, owner, true);
        positive.setPosition(X + 3.5, Y + 1, Z + .5);
        negative.setPosition(X + 8.5, Y + 1, Z + .5);
        var state = new State(NAME);
        state.data.setInteger("Schema", 1);
        state.data.setInteger("NutritionBoundaryVersion", 1);
        state.data.setString("Phase", "setup");
        state.data.setLong("SetupProcess", PROCESS);
        state.data.setString(
            "Owner",
            owner.getUniqueID()
                .toString());
        state.data.setString(
            "Positive",
            positive.getUniqueID()
                .toString());
        state.data.setString(
            "Negative",
            negative.getUniqueID()
                .toString());
        state.data.setLong("InputRemaining", SAVED_INPUT_REMAINING);
        state.data.setString(
            "InputScope",
            "Known codec-accepted historical save fixture; not an attack produced in this run");
        state.data.setTag("PositiveSlots", slots(positive));
        state.data.setTag("NegativeSlots", slots(negative));
        state.data.setTag("PositiveInput", snapshot(positive));
        state.data.setTag("NegativeInput", snapshot(negative));
        verifyInitialNutrition(h, positive);
        verifyInitialNutrition(h, negative);
        h.getWorld().mapStorage.setData(NAME, state);
        state.markDirty();
        var watch = lifecycleWatch(h, state, owner, "setup");
        h.assertTrue(
            h.getWorld()
                .spawnEntityInWorld(positive)
                && h.getWorld()
                    .spawnEntityInWorld(negative),
            "Legacy actors rejected");
        watch.positive = positive;
        watch.negative = negative;
        var empty = new ItemStack(MaidResurrectionContent.EMPTY_SLAB);
        owner.inventory.mainInventory[owner.inventory.currentItem] = empty;
        var ledgerBefore = ledger(h);
        owner.setPosition(negative.posX + 1, negative.posY, negative.posZ);
        negative.interact(owner);
        h.assertTrue(
            MaidSpawnChecks.present(negative) && owner.getCurrentEquippedItem() == empty
                && empty.stackSize == 1
                && ledgerBefore.equals(ledger(h)),
            "Unknown receipt capture did not fail closed without payment");
        verifyUnknown(h, state, negative);
        state.data.setBoolean("UnknownCaptureRefused", true);
        owner.setPosition(positive.posX + 1, positive.posY, positive.posZ);
        long beforeCapture = positive.getCombatCadence()
            .remainingTicks();
        state.data.setTag("PositiveCaptureInput", snapshot(positive));
        h.assertTrue(beforeCapture > 0 && positive.interact(owner), "Ordinary positive capture interaction failed");
        var filled = owner.getCurrentEquippedItem();
        UUID token = MaidLifecycleItems.token(filled);
        h.assertTrue(
            filled != null && filled.getItem() == MaidResurrectionContent.FILLED_SLAB
                && token != null
                && !MaidSpawnChecks.present(positive),
            "Positive capture did not remove the original UUID");
        var source = (NBTTagCompound) MaidLifecycleData.get(h.getWorld())
            .capture(token)
            .getCompoundTag("Source")
            .copy();
        h.assertTrue(
            source.getCompoundTag(MaidCombatNbtKeys.TAG)
                .getLong("Remaining") == beforeCapture,
            "Capture reset a nonzero known cadence");
        verifyNutritionBoundary(h, state.data.getCompoundTag("PositiveCaptureInput"), source, "capture commit");
        state.data.setTag("CapturedSource", source);
        state.data.setLong("CapturedRemaining", beforeCapture);
        long capturedAt = h.getWorld()
            .getTotalWorldTime();
        boolean[] released = { false };
        h.onEachTick("real capture cooldown and ordinary release, retained unknown actor", () -> {
            watch.check();
            verifyUnknown(h, state, negative);
            if (h.getWorld()
                .getTotalWorldTime() - capturedAt < 21 || released[0]) return;
            owner.setPosition(X + 1.5, Y + 1, Z + .5);
            click(h, owner, X + 2, Y, Z);
            var restored = one(h, UUID.fromString(state.data.getString("Positive")));
            watch.positive = restored;
            h.assertTrue(
                owner.getCurrentEquippedItem() != null && owner.getCurrentEquippedItem()
                    .getItem() == MaidResurrectionContent.EMPTY_SLAB,
                "Ordinary release did not exchange the consumed capture credential");
            verifyPositive(h, state, restored);
            verifyNutritionBoundary(
                h,
                state.data.getCompoundTag("CapturedSource"),
                snapshot(restored),
                "release before first native tick");
            h.assertTrue(
                restored.getCombatCadence()
                    .remainingTicks() == beforeCapture && beforeCapture > 0,
                "Release changed the captured nonzero remaining cadence");
            state.data.setLong(
                "ReleasedAt",
                h.getWorld()
                    .getTotalWorldTime());
            state.data.setLong(
                "ReleasedRemaining",
                restored.getCombatCadence()
                    .remainingTicks());
            state.data.setTag("ReleasedMaid", snapshot(restored));
            state.data.setTag("PlayerInventory", owner.inventory.writeToNBT(new NBTTagList()));
            state.data.setInteger("Selected", owner.inventory.currentItem);
            state.data.setFloat("PlayerHealth", owner.getHealth());
            state.markDirty();
            savePlayer(h, owner);
            watch.retainForNativeSave = true;
            released[0] = true;
            System.out.println(
                "TLMNH_COMBAT_LIFECYCLE_SETUP captureRelease=true sameUuid=true unknownRefused=true actualNetworkPlayer=false normalStopRequired=true");
            h.succeed();
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 100, batch = "combat_lifecycle_resume")
    public static void resumeBothNativeCombatActorsFromAnotherJvmWithoutReplay(GameTestHelper h) throws Exception {
        environment(h, "resume");
        MinecraftServer.getServer()
            .setCanSpawnAnimals(true);
        var state = (State) h.getWorld().mapStorage.loadData(State.class, NAME);
        h.assertTrue(
            state != null && state.loaded
                && state.data.getLong("SetupProcess") != PROCESS
                && "setup".equals(state.data.getString("Phase")),
            "Actual disk state from another JVM required");
        Path setupSave = Paths.get(System.getProperty("tlmnh.qaCombatLifecycleSetupSave", ""))
            .toRealPath();
        NBTTagCompound saved;
        try (InputStream in = Files.newInputStream(setupSave)) {
            saved = CompressedStreamTools.readCompressed(in);
        }
        h.assertTrue(
            saved.getInteger("Schema") == 1 && "setup".equals(saved.getString("Phase"))
                && saved.getLong("Process") == state.data.getLong("SetupProcess")
                && !saved.hasKey("Failure"),
            "Setup native-save observation is missing, foreign or failed");
        var owner = player(h, UUID.fromString(state.data.getString("Owner")));
        ((SaveHandler) h.getWorld()
            .getSaveHandler()
            .getSaveHandler()).readPlayerData(owner);
        h.assertTrue(
            state.data.getTagList("PlayerInventory", 10)
                .equals(owner.inventory.writeToNBT(new NBTTagList()))
                && owner.inventory.currentItem == state.data.getInteger("Selected")
                && owner.getHealth() == state.data.getFloat("PlayerHealth"),
            "Actual player.dat full inventory/selected/health changed");
        var watch = lifecycleWatch(h, state, owner, "resume");
        force(h); // Register the true Join observer before loading these remote native chunks
        watch.positive = one(h, UUID.fromString(state.data.getString("Positive")));
        watch.negative = one(h, UUID.fromString(state.data.getString("Negative")));
        verifyPositive(h, state, watch.positive);
        verifyUnknown(h, state, watch.negative);
        h.assertTrue(
            watch.positiveJoin != null && watch.positiveJoin.getLong("Remaining") == saved.getCompoundTag("Positive")
                .getCompoundTag(MaidCombatNbtKeys.TAG)
                .getLong("Remaining"),
            "Native disk load did not retain the actual saved nonzero cadence at Join");
        var setupNutrition = new NBTTagCompound();
        for (String role : new String[] { "Positive", "Negative" }) setupNutrition.setTag(
            role,
            saved.getCompoundTag(role)
                .getCompoundTag(MaidHungerNbtKeys.ROOT_TAG)
                .copy());
        state.data.setTag("SetupSaveNutrition", setupNutrition);
        state.data.setTag("NegativeResumeJoin", watch.negativeJoin);
        verifyNutritionBoundary(
            h,
            saved.getCompoundTag("Positive"),
            watch.positiveJoin.getCompoundTag("RawEntity"),
            "positive disk Join");
        verifyNutritionBoundary(h, saved.getCompoundTag("Negative"), watch.negativeJoin, "negative disk Join");
        verifyJoinStable(h, watch.positiveJoin.getCompoundTag("RawEntity"), saved.getCompoundTag("Positive"));
        verifyJoinStable(h, watch.negativeJoin, saved.getCompoundTag("Negative"));
        long joinedAt = watch.positiveJoin.getLong("Tick"), joinedRemaining = watch.positiveJoin.getLong("Remaining");
        h.assertTrue(joinedRemaining > 0, "Expired zero is not positive cadence-restoration evidence");
        boolean[] complete = { false };
        h.onEachTick("actual native tick after another-JVM load; no stale hit or repeated wear", () -> {
            if (complete[0]) return;
            watch.check();
            verifyPositive(h, state, watch.positive);
            verifyUnknown(h, state, watch.negative);
            long remaining = Math.max(
                0,
                joinedRemaining - (h.getWorld()
                    .getTotalWorldTime() - joinedAt));
            h.assertTrue(
                remaining > 0 && watch.positive.getCombatCadence()
                    .remainingTicks() == remaining,
                "Native restart froze/reset cadence or only demonstrated an expired zero");
            state.data.setString("Phase", "resume");
            state.data.setLong("ResumeProcess", PROCESS);
            state.data.setTag("ResumeJoin", watch.positiveJoin);
            state.markDirty();
            savePlayer(h, owner);
            watch.retainForNativeSave = true;
            complete[0] = true;
            System.out.println(
                "TLMNH_COMBAT_LIFECYCLE_RESUME newJvm=true twoUniqueActors=true slots=42full baubles=30 locked=20 unknownHeld=true noAttackReplay=true actualNetworkPlayer=false");
            h.succeed();
        });
    }

    private static EntityMaid legacyActor(GameTestHelper h, EntityPlayerMP owner, boolean unknown) {
        var maid = new EntityMaid(h.getWorld());
        h.assertTrue(maid.bindTo(owner.getUniqueID()), "Fresh lifecycle owner binding failed");
        maid.selectBrainProfileForDevelopment(MaidBrainProfile.ENHANCED);
        var values = equipment();
        for (int i = 0; i < values.length; i++) maid.getMaidInventory()
            .setInventorySlotContents(i, values[i]);
        configureStableState(h, maid, unknown);
        maid.setHealth(maid.getMaxHealth());
        maid.getHungerState()
            .setFood(13);
        maid.getHungerState()
            .setSaturation(2.25F);
        maid.getHungerState()
            .addExhaustion(1.25F);
        maid.setPickup(false);
        maid.setSitting(true);
        maid.setSchedule(MaidSchedule.ALL);
        var input = snapshot(maid);
        input.setTag("StableInputSlots", slots(maid));
        input.setString("MaidTask", MaidTask.RANGED_ATTACK.getId());
        var task = new NBTTagCompound();
        task.setInteger("Version", unknown ? 99 : 1);
        task.setString("OriginalId", MaidTask.RANGED_ATTACK.getId());
        task.setString("ReferenceId", MaidTask.RANGED_ATTACK.getId());
        task.setString("Preference", MaidTask.RANGED_ATTACK.getId());
        task.setBoolean("PreferencePending", true);
        task.setTag(OPAQUE, opaque("task"));
        input.getCompoundTag("TLMNH")
            .setTag("CombatTask", task);
        input.getCompoundTag("TLMNH")
            .setTag(OPAQUE, opaque("extension"));
        var cadence = new NBTTagCompound();
        cadence.setInteger("Version", unknown ? 99 : 1);
        cadence.setLong("Remaining", SAVED_INPUT_REMAINING);
        if (unknown) cadence.setTag(OPAQUE, opaque("cadence"));
        input.setTag(MaidCombatNbtKeys.TAG, cadence);
        input.removeTag(MaidCombatNbtKeys.TAG_2);
        if (unknown) {
            var receipt = opaque("receipt");
            receipt.setInteger("Version", 99);
            receipt.setString("Stage", "FUTURE_HELD");
            input.setTag(MaidCombatNbtKeys.TAG_2, receipt);
        }
        var maps = input.getCompoundTag("MaidTaskDataMaps");
        var attacks = maps.getCompoundTag("touhou_little_maid:maid_attack_list");
        var groups = attacks.getCompoundTag("attack_groups");
        groups.setString("minecraft:pig", "hostile");
        groups.setString("qa:future_target", "future_enum");
        attacks.setTag("attack_groups", groups);
        attacks.setTag(OPAQUE, opaque("attack-config"));
        maps.setTag("touhou_little_maid:maid_attack_list", attacks);
        maps.setTag("qa:future_task", opaque("task-map"));
        input.setTag("MaidTaskDataMaps", maps);
        maid.readFromNBT(input); // Input migration only; the actual restart assertion is a different JVM's native disk
                                 // load
        h.assertTrue(
            unknown ? maid.getTask() == MaidTask.IDLE : maid.getTask() == MaidTask.ATTACK,
            "Legacy task did not enter the current production projection");
        var migrated = snapshot(maid);
        h.assertTrue(
            input.getCompoundTag("TLMNH")
                .equals(migrated.getCompoundTag("TLMNH")) && maps.equals(migrated.getCompoundTag("MaidTaskDataMaps"))
                && input.getTag(MaidCombatNbtKeys.TAG)
                    .equals(migrated.getTag(MaidCombatNbtKeys.TAG)),
            "Initial production migration already lost opaque task/attack/cadence input");
        if (unknown) h.assertTrue(
            input.getTag(MaidCombatNbtKeys.TAG_2)
                .equals(migrated.getTag(MaidCombatNbtKeys.TAG_2))
                && maid.getCombatHitReceipt()
                    .stage() == CombatHitReceipt.Stage.HELD,
            "Initial unknown receipt was cleared or replayed before lifecycle verification");
        else h.assertTrue(
            !migrated.hasKey(MaidCombatNbtKeys.TAG_2) && maid.getCombatCadence()
                .formatSupported(),
            "Positive known state was not transferable after production migration");
        h.assertTrue(
            input.getTagList("StableInputSlots", 10)
                .equals(slots(maid)),
            "Initial production migration lost a complete configured slot");
        return maid;
    }

    private static void verifyPositive(GameTestHelper h, State state, EntityMaid maid) {
        var current = snapshot(maid);
        var input = state.data.getCompoundTag("PositiveInput");
        verifyCommon(h, state, maid, input, "PositiveSlots");
        h.assertTrue(
            maid.getTask() == MaidTask.ATTACK && MaidTask.ATTACK.getId()
                .equals(current.getString("MaidTask"))
                && current.getCompoundTag("TLMNH")
                    .equals(input.getCompoundTag("TLMNH"))
                && maid.getCombatCadence()
                    .formatSupported()
                && maid.getCombatCadence()
                    .remainingTicks() > 0
                && !maid.getCombatHitReceipt()
                    .blocksTransfer()
                && !current.hasKey(MaidCombatNbtKeys.TAG_2),
            "Known projected combat state/opaque fields/cadence changed or gained an unresolved receipt");
    }

    private static void verifyUnknown(GameTestHelper h, State state, EntityMaid maid) {
        var current = snapshot(maid);
        var input = state.data.getCompoundTag("NegativeInput");
        verifyCommon(h, state, maid, input, "NegativeSlots");
        h.assertTrue(
            maid.getTask() == MaidTask.IDLE && !maid.getCombatCadence()
                .formatSupported()
                && maid.getCombatHitReceipt()
                    .blocksTransfer()
                && maid.getCombatHitReceipt()
                    .stage() == CombatHitReceipt.Stage.HELD
                && current.getTag("MaidTask")
                    .equals(input.getTag("MaidTask"))
                && current.getCompoundTag("TLMNH")
                    .equals(input.getCompoundTag("TLMNH"))
                && current.getTag(MaidCombatNbtKeys.TAG)
                    .equals(input.getTag(MaidCombatNbtKeys.TAG))
                && current.getTag(MaidCombatNbtKeys.TAG_2)
                    .equals(input.getTag(MaidCombatNbtKeys.TAG_2)),
            "Unknown task/cadence/receipt was cleared, rewritten, activated or replayed");
    }

    private static void verifyCommon(GameTestHelper h, State state, EntityMaid maid, NBTTagCompound input,
        String inventory) {
        h.assertTrue(
            maid.isEntityAlive() && MaidSpawnChecks.present(maid)
                && state.data.getString("Owner")
                    .equals(maid.func_152113_b())
                && input.getLong("UUIDMost") == maid.getUniqueID()
                    .getMostSignificantBits()
                && input.getLong("UUIDLeast") == maid.getUniqueID()
                    .getLeastSignificantBits()
                && state.data.getTagList(inventory, 10)
                    .equals(slots(maid))
                && input.getCompoundTag("MaidTaskDataMaps")
                    .equals(snapshot(maid).getCompoundTag("MaidTaskDataMaps")),
            "Native lifecycle changed UUID/owner/complete42 stacks/attack configuration");
        verifyStableState(h, maid, input);
    }

    private static LifecycleWatch lifecycleWatch(GameTestHelper h, State state, EntityPlayerMP owner, String phase) {
        var watch = new LifecycleWatch(h, state, owner, phase);
        MinecraftForge.EVENT_BUS.register(watch);
        h.afterTest(() -> { if (!watch.retainForNativeSave) MinecraftForge.EVENT_BUS.unregister(watch); });
        return watch;
    }

    public static final class LifecycleWatch {

        final GameTestHelper h;
        final State state;
        final EntityPlayerMP owner;
        final String phase;
        EntityMaid positive, negative;
        NBTTagCompound positiveJoin, negativeJoin;
        Throwable failure;
        boolean retainForNativeSave;
        int attackEvents, arrowEvents;

        LifecycleWatch(GameTestHelper h, State state, EntityPlayerMP owner, String phase) {
            this.h = h;
            this.state = state;
            this.owner = owner;
            this.phase = phase;
        }

        boolean owned(EntityMaid maid) {
            return maid != null && (maid.getUniqueID()
                .toString()
                .equals(state.data.getString("Positive"))
                || maid.getUniqueID()
                    .toString()
                    .equals(state.data.getString("Negative")));
        }

        void guarded(Runnable action) {
            if (failure != null) return;
            try {
                action.run();
            } catch (RuntimeException | Error ex) {
                IntegrationGuard.throwIfFatal(ex);
                failure = ex;
            }
        }

        void check() {
            if (failure != null) throw new IllegalStateException("Native lifecycle observer failed", failure);
            require(
                attackEvents == 0 && arrowEvents == 0,
                "Lifecycle restored an attack or arrow instead of retaining state");
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void pre(MaidHurtTarget.Pre event) {
            if (owned(event.getMaid())) attackEvents++;
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void join(EntityJoinWorldEvent event) {
            if (event.world != h.getWorld() || event.isCanceled()) return;
            guarded(() -> {
                if (event.entity instanceof EntityArrow arrow && arrow.shootingEntity instanceof EntityMaid maid
                    && owned(maid)) arrowEvents++;
                if (!(event.entity instanceof EntityMaid maid) || !owned(maid)) return;
                if (maid.getUniqueID()
                    .toString()
                    .equals(state.data.getString("Positive"))) {
                    positive = maid;
                    positiveJoin = new NBTTagCompound();
                    positiveJoin.setLong(
                        "Tick",
                        h.getWorld()
                            .getTotalWorldTime());
                    positiveJoin.setLong(
                        "Remaining",
                        maid.getCombatCadence()
                            .remainingTicks());
                    positiveJoin.setTag("RawEntity", snapshot(maid));
                } else {
                    negative = maid;
                    negativeJoin = snapshot(maid);
                }
            });
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void saved(WorldEvent.Save event) {
            if (event.world != h.getWorld() || !retainForNativeSave) return;
            var row = new NBTTagCompound();
            row.setInteger("Schema", 1);
            row.setString("Phase", phase);
            row.setLong("Process", PROCESS);
            row.setString("Fingerprint", DependencyFingerprint.mod("tlmnh"));
            row.setLong(
                "WorldTick",
                h.getWorld()
                    .getTotalWorldTime());
            row.setBoolean("ActualNetworkPlayer", false);
            row.setBoolean("CrashAtomicityClaimed", false);
            try {
                check();
                verifyPositive(h, state, positive);
                verifyUnknown(h, state, negative);
                row.setTag("Positive", snapshot(positive));
                row.setTag("Negative", snapshot(negative));
                row.setTag("PositiveNutritionAtSave", liveNutrition(positive));
                row.setTag("NegativeNutritionAtSave", liveNutrition(negative));
                row.setTag("PositiveSlots", slots(positive));
                row.setTag("NegativeSlots", slots(negative));
                row.setTag("Ledger", ledger(h));
                row.setTag("PlayerInventory", owner.inventory.writeToNBT(new NBTTagList()));
                row.setInteger("Selected", owner.inventory.currentItem);
                row.setFloat("PlayerHealth", owner.getHealth());
                if ("setup".equals(phase)) require(
                    positive.getCombatCadence()
                        .remainingTicks()
                        == state.data.getLong("ReleasedRemaining") - (h.getWorld()
                            .getTotalWorldTime() - state.data.getLong("ReleasedAt")),
                    "Actual native save reset/froze nonzero post-release cadence");
            } catch (RuntimeException | Error ex) {
                IntegrationGuard.throwIfFatal(ex);
                row.setString("Failure", ex.toString());
                failure = ex;
            }
            try {
                write("combat-lifecycle-native-save.dat", row);
            } catch (RuntimeException ex) {
                failure = ex;
                System.err.println("TLMNH_COMBAT_LIFECYCLE_SAVE_OBSERVER_FAILED " + ex);
            }
        }

        @SubscribeEvent
        public void unloaded(WorldEvent.Unload event) {
            if (event.world == h.getWorld()) MinecraftForge.EVENT_BUS.unregister(this);
        }
    }

    private static NBTTagCompound opaque(String kind) {
        var data = new NBTTagCompound();
        data.setString("Kind", kind);
        data.setIntArray("Payload", new int[] { 17, -23, 41 });
        var nested = new NBTTagCompound();
        nested.setLong("Opaque", 0x7ff0000000000001L);
        data.setTag("Nested", nested);
        return data;
    }

    private static ItemStack tagged(ItemStack stack, String id) {
        var tag = opaque(id);
        stack.setTagCompound(tag);
        return stack;
    }

    private static ItemStack[] equipment() {
        var complete = new ItemStack[MaidInventoryLayout.TOTAL_SIZE];
        for (int slot = 0; slot < MaidInventoryLayout.BACKPACK_SIZE; slot++)
            complete[slot] = tagged(new ItemStack(Items.diamond, slot + 1), "raw-" + slot);
        var equipped = new ItemStack[] { new ItemStack(Items.iron_sword, 1, 13), new ItemStack(Items.stick, 7),
            new ItemStack(Items.iron_boots, 1, 2), new ItemStack(Items.iron_leggings, 1, 3),
            new ItemStack(Items.iron_chestplate, 1, 4), new ItemStack(Items.iron_helmet, 1, 5) };
        for (int slot = 0; slot < equipped.length; slot++)
            complete[MaidInventoryLayout.MAIN_HAND + slot] = tagged(equipped[slot], "equipment-" + slot);
        return complete;
    }

    private static void configureStableState(GameTestHelper h, EntityMaid maid, boolean unknown) {
        var inventory = maid.getMaidInventory();
        var maintenance = tagged(new ItemStack(MaidWorkItems.maintenanceBell), "maintenance-bell");
        h.assertTrue(
            MaintenanceBellData.toggleChunk(maintenance, 0, 96, 96).outcome == MaintenanceBellData.ToggleOutcome.BOUND,
            "Maintenance chunk binding failed");
        h.assertTrue(
            MaintenanceBellData.toggleChunk(maintenance, 0, 100, 98).outcome == MaintenanceBellData.ToggleOutcome.BOUND,
            "Separated maintenance chunk binding failed");
        var breeding = tagged(new ItemStack(MaidWorkItems.breedingBell), "breeding-bell");
        BreedingBellData.bind(breeding, 0, X + 4, Y, Z + 2);
        inventory.setInventorySlotContents(33, maintenance);
        inventory.setInventorySlotContents(34, breeding);
        var bag = CropsNhNativeQaSupport.createAdventureBackpack();
        h.assertTrue(
            CompatServices.backpacks()
                .isSupportedBackpack(bag),
            "Actual AdventureBackpack required");
        var inside = new ItemStack[] { tagged(new ItemStack(Items.iron_ingot, 37), "nested-ingots"),
            tagged(new ItemStack(Items.diamond_pickaxe, 1, 17), "nested-worn-tool"),
            tagged(new ItemStack(Items.paper, 11), "nested-document") };
        for (var stack : inside) h.assertTrue(
            CompatServices.backpacks()
                .insertIntoBackpack(bag, stack, false) == null,
            "Actual provider rejected a nested full-NBT stack");
        inventory.setInventorySlotContents(35, bag);
        maid.setFavorability(384);
        for (int slot = 0; slot < MaidBaubleLayout.SIZE; slot++) maid.getMaidBaubleInventory()
            .setInventorySlotContents(slot, tagged(new ItemStack(PilotMod.INVENTORY_BAUBLE), "bauble-" + slot));
        maid.setFavorability(64);
        h.assertTrue(
            MaidBaubleLayout.unlockedSlots(
                maid.getFavorabilityManager()
                    .getLevel())
                == 10,
            "Fixture must retain twenty locked baubles after a real favorability downgrade");
        var config = maid.getBreedingTaskConfig();
        config.setTargetMode(
            unknown ? BreedingTaskConfigData.TargetMode.MUTATION_POOL : BreedingTaskConfigData.TargetMode.CROP);
        config.setTargetId(unknown ? "mutationPool.food" : "cropsnh:carrot");
        config.setTargetGain(17);
        config.setTargetGrowth(23);
        config.setTargetResistance(29);
        config.setStatLogic(unknown ? BreedingTaskConfigData.StatLogic.OR : BreedingTaskConfigData.StatLogic.AND);
        config.setMax31Mode(unknown);
        config.markPersistent();
    }

    private static void verifyStableState(GameTestHelper h, EntityMaid maid, NBTTagCompound input) {
        var current = snapshot(maid);
        h.assertTrue(
            input.getTagList("MaidBaubles", 10)
                .tagCount() == 30 && input.getTagList("MaidBaubles", 10)
                    .equals(current.getTagList("MaidBaubles", 10))
                && maid.getFavorability() == 64
                && MaidBaubleLayout.unlockedSlots(
                    maid.getFavorabilityManager()
                        .getLevel())
                    == 10,
            "All thirty baubles including twenty locked entries must retain complete NBT");
        verifyLiveNutrition(h, maid);
        h.assertTrue(
            input.getCompoundTag(BreedingConfigNbtKeys.ROOT)
                .equals(current.getCompoundTag(BreedingConfigNbtKeys.ROOT)),
            "Complete breeding target config changed");
        h.assertTrue(
            MaintenanceBellData.read(
                maid.getMaidInventory()
                    .getStackInSlot(33))
                .chunkCount() == 2 && BreedingBellData
                    .read(
                        maid.getMaidInventory()
                            .getStackInSlot(34))
                    .matches(0, X + 4, Y, Z + 2),
            "Bound maintenance/breeding bell semantics changed");
        // Full slot equality above binds the provider bag's complete nested contents and unrelated opaque tags
        h.assertTrue(
            CompatServices.backpacks()
                .isSupportedBackpack(
                    maid.getMaidInventory()
                        .getStackInSlot(35)),
            "Restored raw35 stack lost its actual provider identity");
    }

    private static void verifyInitialNutrition(GameTestHelper h, EntityMaid maid) {
        verifyLiveNutrition(h, maid);
        var hunger = maid.getHungerState();
        h.assertTrue(
            hunger.getFood() == 13 && hunger.getSaturation() == 2.25F
                && hunger.getExhaustion() == 1.25F
                && hunger.getRegenTicks() == 0
                && hunger.getStarveTicks() == 0,
            "Original lifecycle nutrition input changed: " + snapshot(maid).getTag(MaidHungerNbtKeys.ROOT_TAG));
    }

    private static void verifyLiveNutrition(GameTestHelper h, EntityMaid maid) {
        var hunger = maid.getHungerState();
        h.assertTrue(
            hunger.isActivated() && !hunger.isPaused()
                && hunger.getFood() >= 0
                && hunger.getFood() <= MaidHungerTuning.MAX_FOOD
                && Float.isFinite(hunger.getSaturation())
                && hunger.getSaturation() >= 0.0F
                && hunger.getSaturation() <= hunger.getFood()
                && Float.isFinite(hunger.getExhaustion())
                && hunger.getExhaustion() >= 0.0F
                && hunger.getExhaustion() <= MaidHungerTuning.MAX_EXHAUSTION
                && hunger.getRegenTicks() >= 0
                && hunger.getStarveTicks() >= 0,
            "Invalid live lifecycle nutrition at tick " + h.getWorld()
                .getTotalWorldTime()
                + ", health="
                + maid.getHealth()
                + "/"
                + maid.getMaxHealth()
                + ", state="
                + snapshot(maid).getTag(MaidHungerNbtKeys.ROOT_TAG));
        h.assertTrue(
            liveNutrition(maid).equals(snapshot(maid).getCompoundTag(MaidHungerNbtKeys.ROOT_TAG)),
            "Live nutrition getters and serialized payload differ at tick " + h.getWorld()
                .getTotalWorldTime());
    }

    private static NBTTagCompound liveNutrition(EntityMaid maid) {
        var hunger = maid.getHungerState();
        var state = new NBTTagCompound();
        state.setInteger(MaidHungerNbtKeys.VERSION, MaidHungerTuning.SCHEMA_VERSION);
        state.setInteger(MaidHungerNbtKeys.FOOD, hunger.getFood());
        state.setFloat(MaidHungerNbtKeys.SATURATION, hunger.getSaturation());
        state.setFloat(MaidHungerNbtKeys.EXHAUSTION, hunger.getExhaustion());
        state.setInteger(MaidHungerNbtKeys.REGEN_TICKS, hunger.getRegenTicks());
        state.setInteger(MaidHungerNbtKeys.STARVE_TICKS, hunger.getStarveTicks());
        return state;
    }

    private static void verifyNutritionBoundary(GameTestHelper h, NBTTagCompound expected, NBTTagCompound actual,
        String boundary) {
        h.assertTrue(
            expected.hasKey(MaidHungerNbtKeys.ROOT_TAG, 10) && actual.hasKey(MaidHungerNbtKeys.ROOT_TAG, 10)
                && expected.getTag(MaidHungerNbtKeys.ROOT_TAG)
                    .equals(actual.getTag(MaidHungerNbtKeys.ROOT_TAG)),
            "Nutrition changed across " + boundary
                + ": expected="
                + expected.getTag(MaidHungerNbtKeys.ROOT_TAG)
                + ", actual="
                + actual.getTag(MaidHungerNbtKeys.ROOT_TAG));
        System.out.println(
            "TLMNH_LIFECYCLE_NUTRITION_BOUNDARY boundary=" + boundary
                + " tick="
                + h.getWorld()
                    .getTotalWorldTime()
                + " state="
                + actual.getTag(MaidHungerNbtKeys.ROOT_TAG));
    }

    private static void verifyJoinStable(GameTestHelper h, NBTTagCompound joined, NBTTagCompound saved) {
        h.assertTrue(joined != null && saved != null, "Both real native Join observations required");
        for (String key : new String[] { "MaidInventory", "MaidBaubles", MaidHungerNbtKeys.ROOT_TAG,
            BreedingConfigNbtKeys.ROOT })
            h.assertTrue(
                joined.hasKey(key) && saved.hasKey(key)
                    && joined.getTag(key)
                        .equals(saved.getTag(key)),
                "Actual another-JVM Join differs from native disk Save for " + key);
    }

    private static NBTTagList slots(EntityMaid maid) {
        var result = new NBTTagList();
        for (int i = 0; i < MaidInventoryLayout.TOTAL_SIZE; i++) {
            var slot = new NBTTagCompound();
            slot.setInteger("Slot", i);
            ItemStack stack = maid.getMaidInventory()
                .getStackInSlot(i);
            slot.setBoolean("Present", stack != null);
            if (stack != null) slot.setTag("Value", stack.writeToNBT(new NBTTagCompound()));
            result.appendTag(slot);
        }
        return result;
    }

    private static NBTTagCompound snapshot(EntityMaid maid) {
        var result = new NBTTagCompound();
        result.setString("id", EntityList.getEntityString(maid));
        maid.writeToNBT(result);
        return result;
    }

    private static NBTTagCompound ledger(GameTestHelper h) {
        var result = new NBTTagCompound();
        MaidLifecycleData.get(h.getWorld())
            .writeToNBT(result);
        return result;
    }

    private static EntityMaid one(GameTestHelper h, UUID id) {
        EntityMaid found = null;
        for (var world : MinecraftServer.getServer().worldServers)
            if (world != null) for (Object raw : world.loadedEntityList.toArray())
                if (raw instanceof EntityMaid maid && id.equals(maid.getUniqueID())) {
                    h.assertTrue(
                        found == null && maid.isEntityAlive() && MaidSpawnChecks.present(maid),
                        "Lifecycle UUID duplicated/dead/unregistered");
                    found = maid;
                }
        h.assertTrue(found != null, "Native lifecycle UUID missing");
        return found;
    }

    private static void force(GameTestHelper h) throws Exception {
        var chunks = new GameTestChunkLoader();
        chunks.forceChunksStrict(h.getWorld(), X - 16, 0, Z - 16, X + 32, 255, Z + 16);
        h.afterTest(chunks::releaseAll);
    }

    private static void click(GameTestHelper h, EntityPlayerMP owner, int x, int y, int z) {
        h.assertTrue(
            owner.theItemInWorldManager
                .activateBlockOrUseItem(owner, h.getWorld(), owner.getCurrentEquippedItem(), x, y, z, 1, .5F, 1, .5F),
            "Native ItemInWorldManager release failed");
        var stack = owner.getCurrentEquippedItem();
        if (stack != null && stack.stackSize == 0) owner.inventory.mainInventory[owner.inventory.currentItem] = null;
    }

    private static EntityPlayerMP player(GameTestHelper h, UUID id) {
        var server = MinecraftServer.getServer();
        var owner = new EntityPlayerMP(
            server,
            h.getWorld(),
            new GameProfile(id, "CombatLifecycleOwner"),
            new ItemInWorldManager(h.getWorld()));
        var manager = new NetworkManager(false);
        var channel = new EmbeddedChannel(manager);
        manager.setConnectionState(EnumConnectionState.PLAY);
        IsolatedPlayerChannel.verify(manager, channel);
        new NetHandlerPlayServer(server, manager, owner);
        owner.theItemInWorldManager.setGameType(GameType.SURVIVAL);
        h.afterTest(() -> {
            owner.setDead();
            channel.finish();
            Object msg;
            while ((msg = channel.readOutbound()) != null) ReferenceCountUtil.release(msg);
            while ((msg = channel.readInbound()) != null) ReferenceCountUtil.release(msg);
        });
        h.assertFalse(
            server.getConfigurationManager().playerEntityList.contains(owner),
            "Isolated MP must not be represented as an actual network player");
        return owner;
    }

    private static void savePlayer(GameTestHelper h, EntityPlayerMP owner) {
        ((SaveHandler) h.getWorld()
            .getSaveHandler()
            .getSaveHandler()).writePlayerData(owner);
    }

    private static void environment(GameTestHelper h, String phase) {
        h.assertTrue(
            Boolean.getBoolean("tlmnh.devCommands") && phase.equals(System.getProperty("tlmnh.qaCombatLifecyclePhase"))
                && "normal".equals(System.getProperty("horizonqa.world"))
                && "interactive".equals(System.getProperty("horizonqa.mode"))
                && "1".equals(System.getProperty("horizonqa.turbo"))
                && MaidBehaviorConfig.profile() == MaidBrainProfile.ENHANCED
                && DependencyFingerprint.mod("tlmnh")
                    .equals(System.getProperty("tlmnh.qaCombatLifecycleExpectedFingerprint")),
            "Explicit frozen ordinary-world combat lifecycle phase required");
    }

    private static void write(String name, NBTTagCompound data) {
        try {
            String output = System.getProperty("horizonqa.reportDir", "");
            require(!output.isEmpty(), "Dedicated report directory required");
            Path path = Paths.get(output)
                .resolve(name);
            Files.createDirectories(path.getParent());
            try (OutputStream out = Files.newOutputStream(path)) {
                CompressedStreamTools.writeCompressed(data, out);
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot retain native lifecycle evidence " + name, failure);
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }

    public static final class State extends WorldSavedData {

        NBTTagCompound data = new NBTTagCompound();
        boolean loaded;

        public State(String name) {
            super(name);
        }

        @Override
        public void readFromNBT(NBTTagCompound source) {
            require(source.getInteger("Schema") == 1, "Unknown combat lifecycle fixture schema");
            data = (NBTTagCompound) source.copy();
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
