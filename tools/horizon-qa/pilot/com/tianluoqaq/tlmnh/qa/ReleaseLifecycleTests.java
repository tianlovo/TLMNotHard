package com.tianluoqaq.tlmnh.qa;

import java.lang.management.ManagementFactory;
import java.util.Objects;
import java.util.UUID;

import net.minecraft.entity.EntityList;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.ContainerWorkbench;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.util.DamageSource;
import net.minecraft.world.WorldSavedData;
import net.minecraft.world.WorldSettings.GameType;
import net.minecraft.world.storage.SaveHandler;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.gtnewhorizons.horizonqa.internal.GameTestChunkLoader;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.config.MaidBehaviorConfig;
import com.tianluoqaq.tlmnh.common.development.IsolatedPlayerChannel;
import com.tianluoqaq.tlmnh.common.inventory.container.ContainerMaid;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleData;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidShrineTile;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidTombstoneEntity;
import com.tianluoqaq.tlmnh.common.lifecycle.item.MaidLifecycleItems;
import com.tianluoqaq.tlmnh.common.lifecycle.world.MaidSpawnChecks;
import com.tianluoqaq.tlmnh.common.protection.DependencyFingerprint;
import com.tianluoqaq.tlmnh.common.registry.MaidResurrectionContent;

import gregtech.api.enums.ItemList;
import gregtech.api.util.GTModHandler;
import gregtech.api.util.GTOreDictUnificator;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

/** A bounded ordinary-world chain and another JVM; the owner is an isolated server MP, not a real client. */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa", "gregtech", "dreamcraft" })
public final class ReleaseLifecycleTests {

    public static final String NAME = "TLMNH_QA_ReleaseLifecycle";
    private static final String FROZEN = "TLMNH_QA_UpgradeFrozen";
    private static final long PROCESS = ManagementFactory.getRuntimeMXBean()
        .getStartTime();

    private ReleaseLifecycleTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 40, batch = "release_lifecycle_0_core")
    public static void nativeCoreInteractionsRetainTheirIndependentResult(GameTestHelper helper) {
        environment(helper, "setup");
        com.tianluoqaq.tlmnh.common.development.MaidCoreInteractionProbe
            .run((net.minecraft.world.WorldServer) helper.getWorld());
        helper.succeed();
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 1900, batch = "release_lifecycle_1_setup")
    public static void setupFiniteMaterialsDeathShrineAndCaptureForNativeRestart(GameTestHelper helper)
        throws Exception {
        environment(helper, "setup");
        helper
            .assertTrue(helper.getWorld().mapStorage.loadData(State.class, NAME) == null, "New private world required");
        // Set this before the reused fixture captures its cleanup policy; its last native tick must retain animals.
        MinecraftServer.getServer()
            .setCanSpawnAnimals(true);
        var route = new Route(helper);
        route.prepare();
        helper.onEachTick("one native steam charge and one LV assembly, then lifecycle", route::tick);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 80, batch = "release_lifecycle_2_resume")
    public static void resumeNativeSavedMaidWithOneIdentityAndCompletePayload(GameTestHelper helper) throws Exception {
        environment(helper, "resume");
        MinecraftServer.getServer()
            .setCanSpawnAnimals(true);
        var state = (State) helper.getWorld().mapStorage.loadData(State.class, NAME);
        helper.assertTrue(
            state != null && state.loaded
                && state.data.getLong("SetupProcess") != PROCESS
                && state.data.getString("Phase")
                    .equals("setup"),
            "Native disk checkpoint from a different JVM required");
        force(helper, state.data.getInteger("X"), state.data.getInteger("Z"));
        var id = UUID.fromString(state.data.getString("Revived"));
        var maid = one(helper, id);
        helper.assertTrue(
            liveOwned(helper, state.data.getString("Owner")) == 1 && maid.func_152113_b()
                .equals(state.data.getString("Owner")),
            "Owner or unique final maid changed");
        // Compare every native field except explicit native tick/placement transients; retain the complete raw
        // snapshots.
        helper.assertTrue(
            stable(state.data.getCompoundTag("FinalMaid")).equals(stable(snapshot(maid))),
            "Native restart changed a complete non-transient maid field");
        var ledger = ledger(helper);
        helper.assertTrue(
            state.data.getCompoundTag("FinalLedger")
                .equals(ledger),
            "Complete schema9 ledger changed across normal restart");
        var owner = player(helper, UUID.fromString(state.data.getString("Owner")));
        ((SaveHandler) helper.getWorld()
            .getSaveHandler()
            .getSaveHandler()).readPlayerData(owner);
        helper.assertTrue(
            state.data.getTagList("PlayerInventory", 10)
                .equals(owner.inventory.writeToNBT(new NBTTagList()))
                && owner.inventory.currentItem == state.data.getInteger("Selected")
                && owner.getHealth() == .25F,
            "Native player.dat inventory, selected slot or paid health changed");
        helper.assertTrue(
            !MinecraftServer.getServer()
                .getConfigurationManager().playerEntityList.contains(owner),
            "This fixture scope requires an isolated MP, not a claimed network login");
        owner.setPosition(maid.posX + 2, maid.posY, maid.posZ);
        var originalInventory = (NBTTagList) owner.inventory.writeToNBT(new NBTTagList())
            .copy();
        var original = owner.getCurrentEquippedItem();
        owner.inventory.mainInventory[owner.inventory.currentItem] = ItemStack
            .loadItemStackFromNBT(state.data.getCompoundTag("ConsumedCapture"));
        click(helper, owner, state.data.getInteger("X") + 2, 191, state.data.getInteger("Z"));
        helper.assertTrue(
            liveOwned(helper, state.data.getString("Owner")) == 1
                && ItemStack.areItemStacksEqual(
                    owner.getCurrentEquippedItem(),
                    ItemStack.loadItemStackFromNBT(state.data.getCompoundTag("ConsumedCapture")))
                && ledger(helper).equals(ledger),
            "Consumed capture credential replay changed final actor or ledger");
        owner.inventory.mainInventory[owner.inventory.currentItem] = original;
        helper.assertTrue(
            originalInventory.equals(owner.inventory.writeToNBT(new NBTTagList())),
            "Controlled duplicate replay changed player inventory");
        ((SaveHandler) helper.getWorld()
            .getSaveHandler()
            .getSaveHandler()).writePlayerData(owner);
        // Test methods run at START, whereas HQA finishes at END. Snapshot after the intervening native living tick.
        boolean[] complete = { false };
        helper.onEachTick("native resume final END snapshot", () -> {
            if (complete[0]) return;
            complete[0] = true;
            state.data.setString("Phase", "resume");
            state.data.setLong("ResumeProcess", PROCESS);
            state.data.setTag("ResumedMaid", snapshot(maid));
            state.data.setLong(
                "ResumedWorldTick",
                helper.getWorld()
                    .getTotalWorldTime());
            state.markDirty();
            System.out.println(
                "TLMNH_RELEASE_LIFECYCLE_RESUME_PASS schema=9 newJvm=true originalCapturedUuid=true fullNativePayload=true playerDat=true realClient=false");
            helper.succeed();
        });
    }

    private static final class Route {

        final GameTestHelper h;
        final ProgressionTests.Fixture f;
        final ProgressionTests.NativeMachine steam, assembler;
        final State state = new State(NAME);
        final int x = 1282, z = 1282, y = 191;
        int age, phase, at;
        long initialSteam, initialEu, initialBatteryCharge, releasedAtWorldTick;
        boolean steamProgress, assemblyProgress;
        EntityMaid maid;
        EntityXPOrb inputOrb;
        UUID death, capture;
        MaidShrineTile shrine;
        ItemStack[] equipment;
        ItemStack battery;

        Route(GameTestHelper h) throws Exception {
            this.h = h;
            f = new ProgressionTests.Fixture(h, false);
            steam = new ProgressionTests.NativeMachine(f, 5, ItemList.Machine_Bronze_AlloySmelter);
            assembler = new ProgressionTests.NativeMachine(f, 8, ItemList.Machine_LV_Assembler);
            f.hand(null);
            force(h, x, z);
            for (int a = x - 2; a <= x + 10; a++) for (int b = z - 3; b <= z + 3; b++) {
                h.assertTrue(
                    h.getWorld()
                        .isAirBlock(a, y, b),
                    "New high platform must not replace an existing world structure");
                h.getWorld()
                    .setBlock(a, y, b, Blocks.stone);
            }
        }

        void prepare() {
            state.data.setInteger("Schema", 1);
            state.data.setInteger("X", x);
            state.data.setInteger("Z", z);
            state.data.setLong("SetupProcess", PROCESS);
            state.data.setString(
                "Owner",
                f.owner.getUniqueID()
                    .toString());
            state.data.setString(
                "MaterialBoundary",
                "197 proven workshop intermediate stock; finite stored steam and one 20000EU LV battery, no finished core or shrine seeded");
            var bench = new ContainerWorkbench(f.owner.inventory, h.getWorld(), f.x + 2, f.y + 1, f.z + 2);
            h.setBlock(2, 1, 2, Blocks.crafting_table);
            f.owner.setPosition(f.x + 3.5, f.y + 1, f.z + 2.5);
            h.assertTrue(bench.canInteractWith(f.owner), "Native workbench unavailable");
            f.owner.openContainer = bench;
            var steel = GTOreDictUnificator.get("plateSteel", 1);
            var bronze = GTOreDictUnificator.get("plateBronze", 1);
            var lapis = GTOreDictUnificator.get("gemLapis", 1);
            var grid = new ItemStack[] { lapis, steel, lapis, steel, bronze, steel, bronze,
                new ItemStack(Items.redstone), bronze };
            for (int i = 0; i < grid.length; i++) bench.craftMatrix.setInventorySlotContents(i, grid[i].copy());
            bench.slotClick(0, 0, 0, f.owner);
            var frame = f.owner.inventory.getItemStack();
            h.assertTrue(
                frame != null && frame.getItem() == MaidResurrectionContent.EMPTY_SLAB && frame.stackSize == 1,
                "Native material craft did not produce exactly one empty frame");
            for (int i = 0; i < 9; i++)
                h.assertTrue(bench.craftMatrix.getStackInSlot(i) == null, "Frame material was not consumed");
            f.owner.inventory.setItemStack(null);
            bench.onContainerClosed(f.owner);
            f.owner.openContainer = f.owner.inventoryContainer;
            steam.inputs(frame, ItemList.Food_Baked_Cake.get(1));
            assembler.inputs(
                GTOreDictUnificator.get("plateSteel", 4),
                GTOreDictUnificator.get("gemLapis", 1),
                GTOreDictUnificator.get("circuitBasic", 2),
                ItemList.Hull_LV.get(1),
                new ItemStack(Blocks.planks));
            h.assertTrue(
                steam.machine.maxSteamStore() == 16000 && assembler.machine.maxEUStore() == 2048,
                "Actual native bronze steam or LV EU capacity changed");
            h.assertTrue(
                steam.base.setStoredSteam(16000) && assembler.base.setStoredEU(0),
                "Finite native buffers unavailable");
            battery = ItemList.Battery_RE_LV_Lithium.get(1);
            h.assertTrue(
                GTModHandler.getElectricItemCharge(battery)
                    .get()[0] == 0,
                "Finite LV battery must start uncharged");
            GTModHandler.chargeElectricItem(battery, 20000, 1, true, false);
            h.assertTrue(
                GTModHandler.getElectricItemCharge(battery)
                    .get()[0] == 20000,
                "Native one-time charge did not prepare exactly 20000EU");
            assembler.machine.setInventorySlotContents(assembler.machine.dechargerSlotStartIndex(), battery);
            battery = assembler.machine.getStackInSlot(assembler.machine.dechargerSlotStartIndex());
            h.assertTrue(
                battery != null && GTModHandler.getElectricItemCharge(battery)
                    .get()[0] == 20000,
                "Native decharger slot did not retain the finite charged battery");
            assembler.base.markInventoryBeenModified();
            initialBatteryCharge = (long) GTModHandler.getElectricItemCharge(battery)
                .get()[0];
            initialSteam = steam.base.getStoredSteam();
            initialEu = assembler.base.getStoredEU();
            state.data.setLong("InitialSteam", initialSteam);
            state.data.setLong("InitialEu", initialEu);
            state.data.setLong("InitialBatteryCharge", initialBatteryCharge);
            state.data.setTag("InitialBattery", battery.writeToNBT(new NBTTagCompound()));
            h.getWorld().mapStorage.setData(NAME, state);
        }

        void tick() {
            age++;
            if (phase == 0) {
                steamProgress |= steam.machine.mProgresstime > 0;
                assemblyProgress |= assembler.machine.mProgresstime > 0;
                if (steam.machine.mMaxProgresstime > 0) h.assertTrue(
                    steam.machine.mMaxProgresstime == 1200 && steam.machine.mEUt == 8,
                    "Native bronze charging power or doubled duration changed");
                if (assembler.machine.mMaxProgresstime > 0) h.assertTrue(
                    assembler.machine.mMaxProgresstime == 400 && assembler.machine.mEUt == 30,
                    "Native LV shrine cost changed");
                if (steam.output() == null || assembler.output() == null) return;
                long finalCharge = (long) GTModHandler.getElectricItemCharge(battery)
                    .get()[0];
                long finalEu = assembler.base.getStoredEU();
                long transferLoss = initialBatteryCharge + initialEu - finalCharge - finalEu - 12000;
                h.assertTrue(
                    steamProgress && assemblyProgress
                        && initialSteam - steam.base.getStoredSteam() == 9600
                        && finalCharge < initialBatteryCharge
                        && finalEu <= assembler.machine.maxEUStore()
                        && transferLoss >= 0
                        && transferLoss <= age + 1L,
                    "Finite native steam/battery balance did not fund exactly 9600 internal steam units and 12000 recipe EU plus bounded native transfer loss");
                state.data.setLong("FinalSteam", steam.base.getStoredSteam());
                state.data.setLong("FinalEu", finalEu);
                state.data.setLong("FinalBatteryCharge", finalCharge);
                state.data.setLong("NativeBatteryTransferLoss", transferLoss);
                state.data.setTag("FinalBattery", battery.writeToNBT(new NBTTagCompound()));
                steam.assertInputs();
                assembler.assertInputs();
                var core = steam.takeOutput();
                var shrineItem = assembler.takeOutput();
                h.assertTrue(
                    core.getItem() == MaidResurrectionContent.MAID_CORE && core.stackSize == 1
                        && shrineItem.getItem()
                            == net.minecraft.item.Item.getItemFromBlock(MaidResurrectionContent.SHRINE)
                        && shrineItem.stackSize == 1,
                    "Wrong native industrial outputs");
                state.data.setInteger("IndustrialTicks", age);
                f.owner.setPosition(x + 1.5, y + 1, z + 1.5);
                f.hand(core);
                click(h, f.owner, x, y, z);
                h.assertTrue(
                    f.owner.getCurrentEquippedItem()
                        .getItem() == MaidResurrectionContent.EMPTY_SLAB
                        && liveOwned(
                            h,
                            f.owner.getUniqueID()
                                .toString())
                            == 1,
                    "Processed core did not legally release one owned maid and one frame");
                maid = owned(
                    h,
                    f.owner.getUniqueID()
                        .toString());
                state.data.setString(
                    "Acquired",
                    maid.getUniqueID()
                        .toString());
                maid.setPosition(x + .5, y + 1, z + .5);
                maid.setSitting(true);
                maid.getBrainCoordinator()
                    .setSuspended(true);
                maid.setPickup(false);
                maid.setModelId("geckolib:winefox_hanfu");
                maid.setSoundPackId("littlemaid_peco");
                h.assertTrue(
                    maid.getSoundPackId()
                        .equals("littlemaid_peco"),
                    "Actual built-in PECO sound pack was not selected");
                maid.setCustomNameTag("release-native-lifecycle");
                maid.getEntityData()
                    .setIntArray("ReleaseLifecyclePayload", new int[] { 208, 9, 37 });
                f.owner.inventory.mainInventory[1] = f.owner.getCurrentEquippedItem();
                f.hand(shrineItem);
                click(h, f.owner, x + 6, y, z);
                shrine = (MaidShrineTile) h.getWorld()
                    .getTileEntity(x + 6, y + 1, z);
                h.assertTrue(
                    shrine != null && h.getWorld()
                        .getBlock(x + 6, y + 1, z) == MaidResurrectionContent.SHRINE
                        && shrineItem.stackSize == 0
                        && f.owner.getCurrentEquippedItem() == null,
                    "Assembled shrine did not place through native ItemBlock");
                equipment = equipment();
                equip(equipment);
                maid.setPickupExperience(true);
                inputOrb = new EntityXPOrb(h.getWorld(), maid.posX, maid.posY, maid.posZ, 37);
                h.assertTrue(
                    h.getWorld()
                        .spawnEntityInWorld(inputOrb),
                    "Actual XP source did not register");
                phase = 1;
                at = age;
            } else if (phase == 1 && age - at >= 5) {
                maid.pickupXPOrb(inputOrb);
                h.assertTrue(
                    maid.getExperience() == 37 && inputOrb.isDead,
                    "Actual spawned XP was not transferred exactly once");
                maid.setPickup(false);
                state.data.setTag("BeforeDeath", snapshot(maid));
                h.assertTrue(
                    maid.attackEntityFrom(DamageSource.outOfWorld, 10000F) && !maid.isEntityAlive(),
                    "Native lethal damage did not reach ordinary death");
                death = MaidLifecycleData.get(h.getWorld())
                    .deathToken(maid.getUniqueID());
                var deathEntry = entry(ledger(h), death);
                h.assertTrue(
                    deathEntry.getCompoundTag("DeathIngress")
                        .getString("Stage")
                        .equals("COMMITTED") && maid.getExperience() == 0,
                    "Native death source did not commit its complete debit: stage="
                        + deathEntry.getCompoundTag("DeathIngress")
                            .getString("Stage")
                        + " sourceXp="
                        + maid.getExperience());
                h.assertTrue(
                    death != null && deathEntry.getInteger("DeathExperience") == 37
                        && deathEntry.getTagList("TombstoneItems", 10)
                            .tagCount() == 13
                        && !deathEntry.getCompoundTag("Identity")
                            .hasKey("MaidInventory")
                        && !deathEntry.getCompoundTag("Identity")
                            .hasKey("MaidExperience")
                        && !deathEntry.getCompoundTag("Identity")
                            .hasKey("Equipment")
                        && !deathEntry.getCompoundTag("Identity")
                            .hasKey("TLMNHOffhand")
                        && maid.getExperience() == 0,
                    "Death did not separate twelve items, one film and native XP from film identity");
                state.data.setString("DeathToken", death.toString());
                state.data.setTag("DeathEntry", deathEntry.copy());
                phase = 2;
                at = age;
            } else if (phase == 2 && age - at >= 41) {
                h.assertTrue(!MaidSpawnChecks.present(maid), "Native death update did not remove the original actor");
                MaidTombstoneEntity grave = null;
                for (Object raw : h.getWorld().loadedEntityList.toArray())
                    if (raw instanceof MaidTombstoneEntity candidate && death.equals(candidate.getToken())) {
                        h.assertTrue(grave == null, "Duplicate death grave");
                        grave = candidate;
                    }
                h.assertTrue(grave != null, "Missing actual death grave");
                f.owner.setPosition(grave.posX + 1, grave.posY, grave.posZ);
                h.assertTrue(
                    grave.interactFirst(f.owner) && MaidLifecycleData.get(h.getWorld())
                        .tombstoneEmpty(death),
                    "Owner could not collect actual grave once");
                handItem(MaidResurrectionContent.FILM);
                f.owner.setPosition(shrine.xCoord + 1.5, shrine.yCoord, shrine.zCoord + 1.5);
                activateShrine();
                h.assertTrue(
                    shrine.getFilm() != null && death.equals(MaidLifecycleItems.token(shrine.getFilm()))
                        && f.owner.getCurrentEquippedItem() == null,
                    "Actual film did not insert once");
                h.assertTrue(f.owner.getHealth() == 20, "Survival owner paid health before revival");
                activateShrine();
                h.assertTrue(
                    shrine.getFilm() == null && f.owner.getHealth() == .25F
                        && liveOwned(
                            h,
                            f.owner.getUniqueID()
                                .toString())
                            == 1,
                    "Native LV shrine did not consume film and charge actual survival life");
                maid = owned(
                    h,
                    f.owner.getUniqueID()
                        .toString());
                h.assertTrue(
                    !maid.getUniqueID()
                        .toString()
                        .equals(state.data.getString("Acquired"))
                        && maid.getExperience() == 0
                        && maid.getMaidInventory()
                            .writeBackpack()
                            .tagCount() == 0
                        && maid.getHeldItem() == null
                        && maid.getOffhandItem() == null,
                    "Film incorrectly restored death inventory/XP or reused removed UUID");
                for (int i = 0; i < maid.getMaidInventory()
                    .getSizeInventory(); i++)
                    h.assertTrue(
                        maid.getMaidInventory()
                            .getStackInSlot(i) == null,
                        "Film duplicated original death equipment slot " + i);
                state.data.setString(
                    "Revived",
                    maid.getUniqueID()
                        .toString());
                maid.setPosition(x + 3.5, y + 1, z + .5);
                maid.setSitting(true);
                maid.getBrainCoordinator()
                    .setSuspended(true);
                maid.setPickup(false);
                reequip();
                maid.setPickupExperience(true);
                phase = 3;
                at = age;
            } else if (phase == 3 && age - at >= 5) {
                for (Object raw : h.getWorld().loadedEntityList.toArray())
                    if (raw instanceof EntityXPOrb orb && !orb.isDead && orb.getDistanceSq(x + .5, y + 1, z + .5) < 100)
                        maid.pickupXPOrb(orb);
                h.assertTrue(
                    maid.getExperience() == 37 && MaidLifecycleData.get(h.getWorld())
                        .remainingDeathExperience(death) == 0,
                    "Separate actual death XP did not return once through native pickup");
                maid.setPickup(false);
                maid.getEntityData()
                    .setBoolean(FROZEN, true);
                state.data.setTag("BeforeCapture", snapshot(maid));
                handItem(MaidResurrectionContent.EMPTY_SLAB);
                f.owner.setPosition(maid.posX + 1, maid.posY, maid.posZ);
                h.assertTrue(maid.interact(f.owner), "Ordinary empty-frame maid interaction failed");
                var filled = f.owner.getCurrentEquippedItem();
                capture = MaidLifecycleItems.token(filled);
                h.assertTrue(
                    filled.getItem() == MaidResurrectionContent.FILLED_SLAB && capture != null
                        && !MaidSpawnChecks.present(maid),
                    "Actual capture did not exchange one frame for one stored maid");
                state.data.setString("CaptureToken", capture.toString());
                state.data.setTag("ConsumedCapture", filled.writeToNBT(new NBTTagCompound()));
                state.data.setTag(
                    "CapturedSource",
                    MaidLifecycleData.get(h.getWorld())
                        .capture(capture)
                        .getCompoundTag("Source")
                        .copy());
                phase = 4;
                at = age;
            } else if (phase == 4 && age - at >= 21) {
                click(h, f.owner, x + 2, y, z);
                maid = one(h, UUID.fromString(state.data.getString("Revived")));
                h.assertTrue(
                    f.owner.getCurrentEquippedItem()
                        .getItem() == MaidResurrectionContent.EMPTY_SLAB
                        && capturedPayload(state.data.getCompoundTag("CapturedSource"))
                            .equals(capturedPayload(snapshot(maid))),
                    "Full captured native source changed during ordinary release");
                maid.setSitting(true);
                maid.getBrainCoordinator()
                    .setSuspended(true);
                releasedAtWorldTick = h.getWorld()
                    .getTotalWorldTime();
                state.data.setInteger(
                    "ReleasedDeathCooldown",
                    snapshot(maid).getCompoundTag("FavorabilityManagerCounter")
                        .getInteger("Death"));
                phase = 5;
                at = age;
            } else if (phase == 5 && age - at >= 5) {
                h.assertTrue(
                    liveOwned(
                        h,
                        f.owner.getUniqueID()
                            .toString())
                        == 1 && maid.getExperience() == 37,
                    "Final live maid is not unique or lost XP");
                long naturalTicks = h.getWorld()
                    .getTotalWorldTime() - releasedAtWorldTick;
                int counterDelta = state.data.getInteger("ReleasedDeathCooldown")
                    - snapshot(maid).getCompoundTag("FavorabilityManagerCounter")
                        .getInteger("Death");
                h.assertTrue(
                    counterDelta > 0 && Math.abs(counterDelta - naturalTicks) <= 1,
                    "Production Death cooldown did not continue through the actual post-release living ticks");
                state.data.setTag("FinalMaid", snapshot(maid));
                state.data.setLong(
                    "FinalWorldTick",
                    h.getWorld()
                        .getTotalWorldTime());
                state.data.setTag("FinalLedger", ledger(h));
                state.data.setTag(
                    "PlayerInventory",
                    f.owner.inventory.writeToNBT(new NBTTagList())
                        .copy());
                state.data.setInteger("Selected", f.owner.inventory.currentItem);
                state.data.setString("Phase", "setup");
                state.markDirty();
                ((SaveHandler) h.getWorld()
                    .getSaveHandler()
                    .getSaveHandler()).writePlayerData(f.owner);
                System.out.println(
                    "TLMNH_RELEASE_LIFECYCLE_SETUP_PASS schema=9 finiteIndustryTicks="
                        + state.data.getInteger("IndustrialTicks")
                        + " nativeLethalDamage=true filmInventorySeparated=true survivalHealth=0.25 captureFullNbt=true realClient=false");
                phase = 6;
                h.succeed();
            }
        }

        void equip(ItemStack[] items) {
            f.owner.setPosition(maid.posX + 1, maid.posY, maid.posZ);
            var container = new ContainerMaid(f.owner.inventory, maid);
            f.owner.openContainer = container;
            h.assertTrue(container.canModifyWith(f.owner), "Native maid container permission failed");
            for (int i = 0; i < items.length; i++) {
                f.owner.inventory.setItemStack(items[i].copy());
                container.slotClick(i, 0, 0, f.owner);
                h.assertTrue(
                    f.owner.inventory.getItemStack() == null && ItemStack.areItemStacksEqual(
                        items[i],
                        maid.getMaidInventory()
                            .getStackInSlot(i)),
                    "Native maid slot rejected complete equipment " + i);
            }
            container.onContainerClosed(f.owner);
            f.owner.openContainer = f.owner.inventoryContainer;
        }

        void reequip() {
            f.owner.setPosition(maid.posX + 1, maid.posY, maid.posZ);
            var container = new ContainerMaid(f.owner.inventory, maid);
            f.owner.openContainer = container;
            for (int slot = 0; slot < equipment.length; slot++) {
                int found = -1;
                for (int i = 0; i < f.owner.inventory.mainInventory.length; i++)
                    if (ItemStack.areItemStacksEqual(equipment[slot], f.owner.inventory.mainInventory[i])) {
                        h.assertTrue(found == -1, "Death material duplicated in owner inventory");
                        found = i;
                    }
                h.assertTrue(found >= 0, "Actual grave did not return full original equipment " + slot);
                var actual = f.owner.inventory.mainInventory[found];
                f.owner.inventory.mainInventory[found] = null;
                f.owner.inventory.setItemStack(actual);
                container.slotClick(slot, 0, 0, f.owner);
                h.assertTrue(
                    f.owner.inventory.getItemStack() == null && ItemStack.areItemStacksEqual(
                        equipment[slot],
                        maid.getMaidInventory()
                            .getStackInSlot(slot)),
                    "Grave material did not reequip through native slot " + slot);
            }
            container.onContainerClosed(f.owner);
            f.owner.openContainer = f.owner.inventoryContainer;
        }

        void handItem(net.minecraft.item.Item item) {
            for (int i = 0; i < f.owner.inventory.mainInventory.length; i++) {
                var stack = f.owner.inventory.mainInventory[i];
                if (stack != null && stack.getItem() == item) {
                    var old = f.owner.getCurrentEquippedItem();
                    f.owner.inventory.mainInventory[i] = old;
                    f.owner.inventory.mainInventory[f.owner.inventory.currentItem] = stack;
                    return;
                }
            }
            h.assertTrue(false, "Missing actual player item " + item);
        }

        void activateShrine() {
            h.assertTrue(
                h.getWorld()
                    .getBlock(shrine.xCoord, shrine.yCoord, shrine.zCoord)
                    .onBlockActivated(
                        h.getWorld(),
                        shrine.xCoord,
                        shrine.yCoord,
                        shrine.zCoord,
                        f.owner,
                        2,
                        .5F,
                        .5F,
                        .5F),
                "Native shrine activation failed");
        }
    }

    private static ItemStack[] equipment() {
        var result = new ItemStack[] { new ItemStack(Items.arrow, 17), new ItemStack(Items.bread, 3),
            new ItemStack(Items.bow, 1, 9), new ItemStack(Items.diamond, 4), new ItemStack(Items.iron_ingot, 5),
            new ItemStack(Items.apple, 6), new ItemStack(Items.iron_sword, 1, 13),
            new ItemStack(Items.golden_apple, 1, 1), new ItemStack(Items.iron_boots, 1, 2),
            new ItemStack(Items.iron_leggings, 1, 3), new ItemStack(Items.iron_chestplate, 1, 4),
            new ItemStack(Items.iron_helmet, 1, 5) };
        for (int i = 0; i < result.length; i++) {
            result[i].setStackDisplayName("release-slot-" + i);
            result[i].getTagCompound()
                .setIntArray("OpaqueLifecyclePayload", new int[] { i, 208, 9 });
        }
        return result;
    }

    private static void environment(GameTestHelper h, String phase) {
        h.assertTrue(
            phase.equals(System.getProperty("tlmnh.qaReleaseLifecyclePhase"))
                && "normal".equals(System.getProperty("horizonqa.world"))
                && "interactive".equals(System.getProperty("horizonqa.mode"))
                && "1".equals(System.getProperty("horizonqa.turbo"))
                && Boolean.getBoolean("tlmnh.qaLifecycleFreeze"),
            "Explicit native ordinary-world phase required");
        h.assertTrue(
            MaidBehaviorConfig.profile() == MaidBrainProfile.ENHANCED,
            "Ordinary Enhanced startup config required");
        h.assertTrue(
            DependencyFingerprint.mod("tlmnh")
                .equals(System.getProperty("tlmnh.qaReleaseExpectedFingerprint")),
            "Loaded candidate differs from frozen selected JAR");
        h.assertTrue(ledger(h).getInteger("Schema") == 9, "Final schema9 writer required");
    }

    private static void force(GameTestHelper h, int x, int z) throws Exception {
        var chunks = new GameTestChunkLoader();
        chunks.forceChunksStrict(h.getWorld(), x - 16, 0, z - 16, x + 24, 255, z + 16);
        h.afterTest(chunks::releaseAll);
    }

    private static void click(GameTestHelper h, EntityPlayerMP owner, int x, int y, int z) {
        h.assertTrue(
            owner.theItemInWorldManager
                .activateBlockOrUseItem(owner, h.getWorld(), owner.getCurrentEquippedItem(), x, y, z, 1, .5F, 1, .5F),
            "Native block-top ItemInWorldManager interaction failed");
        // Direct ItemInWorldManager calls omit the normal C08 handler's post-use zero-count slot cleanup.
        var remaining = owner.getCurrentEquippedItem();
        if (remaining != null && remaining.stackSize == 0)
            owner.inventory.mainInventory[owner.inventory.currentItem] = null;
    }

    private static EntityMaid owned(GameTestHelper h, String owner) {
        EntityMaid result = null;
        for (Object raw : h.getWorld().loadedEntityList.toArray())
            if (raw instanceof EntityMaid maid && maid.isEntityAlive() && owner.equals(maid.func_152113_b())) {
                h.assertTrue(result == null, "Multiple live owner maids");
                result = maid;
            }
        h.assertTrue(result != null, "No live owned maid");
        return result;
    }

    private static int liveOwned(GameTestHelper h, String owner) {
        int count = 0;
        for (var world : MinecraftServer.getServer().worldServers)
            if (world != null) for (Object raw : world.loadedEntityList.toArray())
                if (raw instanceof EntityMaid maid && maid.isEntityAlive() && owner.equals(maid.func_152113_b()))
                    count++;
        return count;
    }

    private static EntityMaid one(GameTestHelper h, UUID id) {
        EntityMaid result = null;
        for (var world : MinecraftServer.getServer().worldServers)
            if (world != null) for (Object raw : world.loadedEntityList.toArray())
                if (raw instanceof EntityMaid maid && id.equals(maid.getUniqueID())) {
                    h.assertTrue(
                        result == null && MaidSpawnChecks.present(maid) && maid.isEntityAlive(),
                        "UUID duplicated, dead or not registered");
                    result = maid;
                }
        h.assertTrue(result != null, "Saved captured UUID did not load natively");
        return result;
    }

    private static NBTTagCompound snapshot(EntityMaid maid) {
        var result = new NBTTagCompound();
        result.setString("id", EntityList.getEntityString(maid));
        maid.writeToNBT(result);
        return (NBTTagCompound) result.copy();
    }

    private static NBTTagCompound stable(NBTTagCompound source) {
        var result = (NBTTagCompound) source.copy();
        for (String field : new String[] { "Pos", "Motion", "Rotation", "OnGround", "FallDistance", "HurtTime",
            "DeathTime", "AttackTime", "HurtByTimestamp", "Air", "Fire", "PortalCooldown", "Dimension" })
            result.removeTag(field);
        if (result.hasKey("ForgeData", 10)) result.getCompoundTag("ForgeData")
            .removeTag("TLMNHCaptureReleased");
        return result;
    }

    private static NBTTagCompound capturedPayload(NBTTagCompound source) {
        var result = (NBTTagCompound) source.copy();
        for (String field : new String[] { "Pos", "Motion", "Rotation", "Dimension" }) result.removeTag(field);
        if (result.hasKey("ForgeData", 10)) result.getCompoundTag("ForgeData")
            .removeTag("TLMNHCaptureReleased");
        return result;
    }

    private static NBTTagCompound ledger(GameTestHelper h) {
        var result = new NBTTagCompound();
        MaidLifecycleData.get(h.getWorld())
            .writeToNBT(result);
        return result;
    }

    private static NBTTagCompound entry(NBTTagCompound ledger, UUID token) {
        var entries = ledger.getTagList("Entries", 10);
        for (int i = 0; i < entries.tagCount(); i++) if (entries.getCompoundTagAt(i)
            .getString("Token")
            .equals(Objects.toString(token))) return entries.getCompoundTagAt(i);
        return new NBTTagCompound();
    }

    private static EntityPlayerMP player(GameTestHelper h, UUID id) {
        var server = MinecraftServer.getServer();
        var owner = new EntityPlayerMP(
            server,
            h.getWorld(),
            new GameProfile(id, "[QA-Lifecycle]"),
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
            Object message;
            while ((message = channel.readOutbound()) != null) ReferenceCountUtil.release(message);
            while ((message = channel.readInbound()) != null) ReferenceCountUtil.release(message);
        });
        return owner;
    }

    public static final class State extends WorldSavedData {

        NBTTagCompound data = new NBTTagCompound();
        boolean loaded;

        public State(String name) {
            super(name);
        }

        @Override
        public void readFromNBT(NBTTagCompound source) {
            if (source.getInteger("Schema") != 1) throw new IllegalStateException("Unknown release fixture schema");
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
