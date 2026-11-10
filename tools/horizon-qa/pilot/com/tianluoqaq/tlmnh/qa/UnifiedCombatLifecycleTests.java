package com.tianluoqaq.tlmnh.qa;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

import net.minecraft.entity.EntityList;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.passive.EntityPig;
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
import com.github.tartaricacid.touhoulittlemaid.entity.misc.MonsterType;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.gtnewhorizons.horizonqa.internal.GameTestChunkLoader;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.combat.CombatCadence;
import com.tianluoqaq.tlmnh.common.combat.CombatHitReceipt;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatAttributes;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatEntities;
import com.tianluoqaq.tlmnh.common.config.MaidBehaviorConfig;
import com.tianluoqaq.tlmnh.common.development.IsolatedPlayerChannel;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventory;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleData;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleItems;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidSpawnChecks;
import com.tianluoqaq.tlmnh.common.protection.DependencyFingerprint;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.registry.MaidResurrectionContent;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

/** 自主回退与正常收放/另JVM恢复；不暂停世界时钟，不手工执行攻击 */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class UnifiedCombatLifecycleTests {

    public static final String NAME = "TLMNH_QA_CombatLifecycle";
    private static final long PROCESS = ManagementFactory.getRuntimeMXBean()
        .getStartTime();
    private static final String OPAQUE = "QACombatLifecycleOpaque";
    private static final int X = 1538, Y = 191, Z = 1538;
    // Codec接受的既存档输入夹具，不声称来自本轮武器攻击，不改变生产reader或时间
    private static final long SAVED_INPUT_REMAINING = 2400;

    private UnifiedCombatLifecycleTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 360, batch = "combat_lifecycle_weapon_fallback")
    public static void nativeEnhancedRootFallsBackAfterNoAmmoAndActualSwordBreak(GameTestHelper h) {
        var f = new ModernCoreTests.Fixture(h, false, 16, 24, MaidBrainProfile.ENHANCED);
        f.maid.setSchedule(MaidSchedule.ALL);
        f.maid.setTask(MaidTask.ATTACK);
        f.maid.getEntityAttribute(MaidCombatAttributes.ATTACK_SPEED)
            .setBaseValue(1);
        f.owner.setPosition(f.x + 1.5, f.y + 1, f.z + 1.5);
        for (int x = 1; x < 20; x++) for (int z = 1; z < 8; z++) h.setBlock(x, 0, z, Blocks.stone);
        var bow = tagged(new ItemStack(Items.bow, 1, 7), "empty-bow");
        var fragile = tagged(
            new ItemStack(Items.diamond_sword, 1, Items.diamond_sword.getMaxDamage()),
            "last-native-use");
        var reserve = tagged(new ItemStack(Items.stone_sword), "reserve");
        var sentinel = tagged(new ItemStack(Items.feather, 5), "untouched");
        var bowBefore = bow.copy();
        var sentinelBefore = sentinel.copy();
        var fragileBefore = fragile.copy();
        f.maid.setCurrentItemOrArmor(0, bow);
        f.maid.getMaidInventory()
            .setInventorySlotContents(0, fragile);
        f.maid.getMaidInventory()
            .setInventorySlotContents(1, reserve);
        f.maid.getMaidInventory()
            .setInventorySlotContents(2, sentinel);
        var pig = new EntityPig(h.getWorld());
        pig.getEntityAttribute(SharedMonsterAttributes.maxHealth)
            .setBaseValue(200);
        pig.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
            .setBaseValue(0);
        pig.getEntityAttribute(SharedMonsterAttributes.knockbackResistance)
            .setBaseValue(1);
        pig.setHealth(200);
        h.spawnEntity(pig, 3.2, 1, 4.5);
        f.maid.getAttackListData()
            .attackGroups()
            .put(MaidCombatEntities.id(pig), MonsterType.HOSTILE);
        h.afterTest(pig::setDead);
        var watch = new FallbackWatch(f.maid, pig, fragile, reserve);
        MinecraftForge.EVENT_BUS.register(watch);
        h.afterTest(() -> MinecraftForge.EVENT_BUS.unregister(watch));
        long[] firstExchange = { -1 }, reserveExchange = { -1 };
        boolean[] done = { false };
        h.onEachTick("complete Enhanced root, zero arrows, native last-use break and independent reserve", () -> {
            watch.check();
            var brain = f.maid.getBrainCoordinator();
            h.assertTrue(
                !brain.isSuspended() && brain.peekEnhancedCore() != null
                    && brain.peekPolicyRuntime()
                        .getProfile() == MaidBrainProfile.ENHANCED
                    && brain.peekPolicyRuntime()
                        .isReady()
                    && brain.getCombatController()
                        .getTicks() == 0,
                "Fallback left the complete native Enhanced root");
            h.assertTrue(
                count(f.maid, bow) == 1 && count(f.maid, sentinel) == 1
                    && ItemStack.areItemStacksEqual(bow, bowBefore)
                    && ItemStack.areItemStacksEqual(sentinel, sentinelBefore),
                "Fallback lost/aliased or mutated the original unused bow/sentinel");
            h.assertTrue(
                watch.arrows == 0 && f.maid.getBrainCoordinator()
                    .peekEnhancedCore()
                    .combat()
                    .releases() == 0,
                "Zero-ammunition bow fired or consumed native ranged resources");
            long now = h.getWorld()
                .getTotalWorldTime();
            if (f.maid.getHeldItem() == fragile && firstExchange[0] < 0) firstExchange[0] = now;
            if (f.maid.getHeldItem() == reserve && reserveExchange[0] < 0) reserveExchange[0] = now;
            h.assertTrue(
                fragile.stackSize == (watch.fragileAccepted == 0 ? 1 : 0),
                "Last-use sword was lost before an accepted native hit or survived its one paid break");
            h.assertTrue(
                reserve.getItemDamage() == watch.reserveAccepted && count(f.maid, reserve) == 1,
                "Reserve wear differs from accepted native hits or its original stack was copied");
            h.assertTrue(
                ItemStack.areItemStackTagsEqual(fragileBefore, fragile),
                "Native break changed opaque sword NBT");
            if (watch.fragileAccepted == 1 && watch.reserveAccepted == 0) h.assertTrue(
                f.maid.getCombatCadence()
                    .remainingTicks() == Math.max(0, watch.firstCooldown - (now - watch.firstHit)),
                "Actual break or autonomous fallback reset/froze shared cadence");
            h.assertTrue(
                !f.maid.getCombatHitReceipt()
                    .blocksTransfer(),
                "Fallback retained unresolved settlement");
            if (watch.reserveAccepted == 1) {
                h.assertTrue(
                    watch.fragileAccepted == 1 && firstExchange[0] >= 0
                        && reserveExchange[0] >= 0
                        && watch.reserveHit - watch.firstHit >= watch.firstCooldown
                        && fragile.stackSize == 0
                        && count(f.maid, fragile) == 0
                        && Float.isFinite(watch.nativeHealthAtFirstPre)
                        && pig.getHealth() < watch.nativeHealthAtFirstPre,
                    "Two accepted native strikes did not follow no-ammo selection, break and nonresetting cooldown");
                var report = new NBTTagCompound();
                report.setString("Scope", "Complete native Enhanced root; no direct planner/runner/attack calls");
                report.setLong("FirstExchange", firstExchange[0]);
                report.setLong("ReserveExchange", reserveExchange[0]);
                report.setLong("FirstHit", watch.firstHit);
                report.setLong("ReserveHit", watch.reserveHit);
                report.setLong("ActualFirstCooldown", watch.firstCooldown);
                report.setFloat("NativeTargetHealthAtFirstPre", watch.nativeHealthAtFirstPre);
                report.setFloat("NativeTargetMaxHealthAtFirstPre", watch.nativeMaxHealthAtFirstPre);
                report.setFloat("FinalNativeTargetHealth", pig.getHealth());
                report.setInteger("FragileAccepted", watch.fragileAccepted);
                report.setInteger("ReserveAccepted", watch.reserveAccepted);
                report.setBoolean("NativeEquipmentAcknowledgedAtBothPre", watch.acknowledged == 2);
                report.setTag("OriginalBow", bowBefore.writeToNBT(new NBTTagCompound()));
                report.setTag("RetainedBow", bow.writeToNBT(new NBTTagCompound()));
                report.setTag("BrokenOriginalSword", fragile.writeToNBT(new NBTTagCompound()));
                report.setTag("Reserve", reserve.writeToNBT(new NBTTagCompound()));
                report.setTag("FinalSlots", slots(f.maid));
                write("weapon-fallback.dat", report);
                done[0] = true;
            }
        });
        h.succeedWhen(() -> done[0]);
    }

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
            source.getCompoundTag(CombatCadence.TAG)
                .getLong("Remaining") == beforeCapture,
            "Capture reset a nonzero known cadence");
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
                .getCompoundTag(CombatCadence.TAG)
                .getLong("Remaining"),
            "Native disk load did not retain the actual saved nonzero cadence at Join");
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
                "TLMNH_COMBAT_LIFECYCLE_RESUME newJvm=true twoUniqueActors=true slots=12full unknownHeld=true noAttackReplay=true actualNetworkPlayer=false");
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
        maid.setHealth(maid.getMaxHealth());
        maid.setHunger(100);
        maid.setPickup(false);
        maid.setSitting(true);
        maid.setSchedule(MaidSchedule.ALL);
        var input = snapshot(maid);
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
        input.setTag(CombatCadence.TAG, cadence);
        input.removeTag(CombatHitReceipt.TAG);
        if (unknown) {
            var receipt = opaque("receipt");
            receipt.setInteger("Version", 99);
            receipt.setString("Stage", "FUTURE_HELD");
            input.setTag(CombatHitReceipt.TAG, receipt);
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
                && input.getTag(CombatCadence.TAG)
                    .equals(migrated.getTag(CombatCadence.TAG)),
            "Initial production migration already lost opaque task/attack/cadence input");
        if (unknown) h.assertTrue(
            input.getTag(CombatHitReceipt.TAG)
                .equals(migrated.getTag(CombatHitReceipt.TAG))
                && maid.getCombatHitReceipt()
                    .stage() == CombatHitReceipt.Stage.HELD,
            "Initial unknown receipt was cleared or replayed before lifecycle verification");
        else h.assertTrue(
            !migrated.hasKey(CombatHitReceipt.TAG) && maid.getCombatCadence()
                .formatSupported(),
            "Positive known state was not transferable after production migration");
        for (int i = 0; i < values.length; i++) h.assertTrue(
            ItemStack.areItemStacksEqual(
                values[i],
                maid.getMaidInventory()
                    .getStackInSlot(i)),
            "Initial production migration lost complete inventory slot " + i);
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
                && !current.hasKey(CombatHitReceipt.TAG),
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
                && current.getTag(CombatCadence.TAG)
                    .equals(input.getTag(CombatCadence.TAG))
                && current.getTag(CombatHitReceipt.TAG)
                    .equals(input.getTag(CombatHitReceipt.TAG)),
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
            "Native lifecycle changed UUID/owner/complete12 stacks/attack configuration");
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
        NBTTagCompound positiveJoin;
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
                } else negative = maid;
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

    public static final class FallbackWatch {

        final EntityMaid maid;
        final EntityPig target;
        final ItemStack fragile, reserve;
        Throwable failure;
        ItemStack pending;
        int fragileAccepted, reserveAccepted, arrows, acknowledged;
        float nativeHealthAtFirstPre = Float.NaN, nativeMaxHealthAtFirstPre = Float.NaN;
        long firstHit = -1, reserveHit = -1, firstCooldown;

        FallbackWatch(EntityMaid maid, EntityPig target, ItemStack fragile, ItemStack reserve) {
            this.maid = maid;
            this.target = target;
            this.fragile = fragile;
            this.reserve = reserve;
        }

        void check() {
            if (failure != null) throw new IllegalStateException("Native fallback observer failed", failure);
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

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void pre(MaidHurtTarget.Pre event) {
            if (event.getMaid() != maid || event.getTarget() != target) return;
            guarded(() -> {
                if (Float.isNaN(nativeHealthAtFirstPre)) {
                    nativeHealthAtFirstPre = target.getHealth();
                    nativeMaxHealthAtFirstPre = target.getMaxHealth();
                    require(
                        Float.isFinite(nativeHealthAtFirstPre) && nativeHealthAtFirstPre > 0,
                        "First real native Pre had no valid initialized target health");
                }
                pending = maid.getHeldItem();
                require(!event.isCanceled() && (pending == fragile || pending == reserve), "Unexpected fallback Pre");
                require(
                    maid.getEquipmentEpoch()
                        .acknowledged(pending),
                    "Attack preceded the real native equipment phase");
                require(
                    pending == fragile ? fragileAccepted == 0 : fragileAccepted == 1,
                    "Fallback attack used an invalid original weapon order");
            });
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void post(MaidHurtTarget.Post event) {
            if (event.getMaid() != maid || event.getTarget() != target || !event.isHurt()) return;
            guarded(() -> {
                require(pending != null, "Accepted hit lacked its original Pre");
                acknowledged++;
                if (pending == fragile) {
                    require(++fragileAccepted == 1 && fragile.stackSize == 0, "Last native use did not break once");
                    firstHit = maid.worldObj.getTotalWorldTime();
                    firstCooldown = maid.getCombatCadence()
                        .remainingTicks();
                    require(firstCooldown == 20, "Native speed1 strike did not commit the real 20tick cadence");
                } else {
                    require(
                        pending == reserve && ++reserveAccepted == 1 && reserve.getItemDamage() == 1,
                        "Reserve did not settle exactly once");
                    reserveHit = maid.worldObj.getTotalWorldTime();
                }
                pending = null;
            });
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void join(EntityJoinWorldEvent event) {
            if (!event.isCanceled() && event.entity instanceof EntityArrow arrow && arrow.shootingEntity == maid)
                arrows++;
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
        var values = new ItemStack[] { new ItemStack(Items.arrow, 17), new ItemStack(Items.bread, 3),
            new ItemStack(Items.bow, 1, 9), new ItemStack(Items.diamond, 4), new ItemStack(Items.iron_ingot, 5),
            new ItemStack(Items.apple, 6), new ItemStack(Items.iron_sword, 1, 13),
            new ItemStack(Items.golden_apple, 1, 1), new ItemStack(Items.iron_boots, 1, 2),
            new ItemStack(Items.iron_leggings, 1, 3), new ItemStack(Items.iron_chestplate, 1, 4),
            new ItemStack(Items.iron_helmet, 1, 5) };
        for (int i = 0; i < values.length; i++) tagged(values[i], "complete-slot-" + i);
        return values;
    }

    private static NBTTagList slots(EntityMaid maid) {
        var result = new NBTTagList();
        for (int i = 0; i < MaidInventory.TOTAL_SIZE; i++) {
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

    private static int count(EntityMaid maid, ItemStack item) {
        int n = 0;
        for (int i = 0; i < MaidInventory.TOTAL_SIZE; i++) if (maid.getMaidInventory()
            .getStackInSlot(i) == item) n++;
        return n;
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
