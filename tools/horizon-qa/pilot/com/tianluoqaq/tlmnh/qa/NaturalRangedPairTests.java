package com.tianluoqaq.tlmnh.qa;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.entity.passive.EntityWolf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.potion.PotionEffect;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.WorldSettings.GameType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.misc.MonsterType;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidFaithfulFlightProfile;
import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.ai.telemetry.MaidTelemetryEvent;
import com.tianluoqaq.tlmnh.common.combat.MaidArrowImpactState;
import com.tianluoqaq.tlmnh.common.combat.MaidArrowSourceState;
import com.tianluoqaq.tlmnh.common.combat.MaidBowAttack;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatEntities;
import com.tianluoqaq.tlmnh.common.combat.MaidItemUseState;
import com.tianluoqaq.tlmnh.common.development.DeterministicMaidSpawn;
import com.tianluoqaq.tlmnh.common.development.DevelopmentArtifactIdentity;
import com.tianluoqaq.tlmnh.common.development.IsolatedPlayerChannel;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.compat.galacticraftcore.common.physics.NativeArrowGravity;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

/** 三组完整根配对：观察自然拉弓/箭更新，受控目标输入不向生产代码提供未来轨迹 */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class NaturalRangedPairTests {

    private static final Map<String, Collector> FIRST = new HashMap<>();

    private NaturalRangedPairTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 250, batch = "ranged_pair_motion_fr")
    public static void faithfulNativeMotionInputs(GameTestHelper h) {
        run(h, "motion", false);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 250, batch = "ranged_pair_motion_n")
    public static void enhancedNativeMotionInputs(GameTestHelper h) {
        run(h, "motion", true);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 250, batch = "ranged_pair_safety_fr")
    public static void faithfulNativeWallAndFriendlyCrossings(GameTestHelper h) {
        run(h, "safety", false);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 250, batch = "ranged_pair_safety_n")
    public static void enhancedNativeWallAndFriendlyCrossings(GameTestHelper h) {
        run(h, "safety", true);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 300, batch = "ranged_pair_withdraw_fr")
    public static void faithfulNativeResourceWithdrawal(GameTestHelper h) {
        run(h, "withdraw", false);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 300, batch = "ranged_pair_withdraw_n")
    public static void enhancedNativeResourceWithdrawal(GameTestHelper h) {
        run(h, "withdraw", true);
    }

    private static void run(GameTestHelper h, String scene, boolean enhanced) {
        var c = new Collector(h, scene, enhanced);
        h.afterTest(c::close);
        try {
            c.setup();
        } catch (Exception error) {
            throw new IllegalStateException("Cannot prepare native paired ranged scene", error);
        }
        h.onEachTick("natural paired ranged collection", () -> { if (c.failure != null) throw c.failure; });
        h.succeedWhen(() -> c.complete);
    }

    public static final class Collector {

        final GameTestHelper h;
        final String scene, arm;
        final MaidBrainProfile profile;
        final JsonObject report = new JsonObject();
        final JsonArray frames = new JsonArray(), inputs = new JsonArray(), shots = new JsonArray(),
            damage = new JsonArray();
        final List<Flight> flights = new ArrayList<>();
        ModernCoreTests.Fixture f;
        EntityPlayerMP owner;
        EmbeddedChannel channel;
        EntityPig target;
        EntityWolf friend;
        MaidBowAttack bow;
        MaidPolicyRuntime runtime;
        ItemStack weapon = new ItemStack(Items.bow), ammunition = new ItemStack(Items.arrow, 24), heldAmmo, heldBow;
        ItemStack heldAmmoSnapshot, heldBowSnapshot;
        JsonObject conditions, targetInput, ownerInput;
        Flight crossing;
        int frame, livingTicks, age, phase, phaseAt, crossings, damageThisFrame;
        long boundaryShots;
        String queued = "";
        boolean complete, closed, wall;
        boolean nativeLoginComplete;
        EnumDifficulty difficulty;
        RuntimeException failure;

        Collector(GameTestHelper h, String scene, boolean enhanced) {
            this.h = h;
            this.scene = scene;
            arm = enhanced ? "N" : "F-R";
            profile = enhanced ? MaidBrainProfile.ENHANCED : MaidBrainProfile.FAITHFUL_TLM;
            report.addProperty("schema", "natural-ranged-pair-v1");
            report.addProperty("scene", scene);
            if (scene.equals("safety")) report.addProperty(
                "shooterInput",
                "initial sitting=true; complete factory root uses normal RIDE_WORK/stationary ranged; no root suspension or direct bow calls");
            report.addProperty("arm", arm);
            report.addProperty(
                "scope",
                "controlled target positions; complete natural roots, native arrows; no future target input access");
            report.addProperty("realNetworkLogin", false);
            report.addProperty("randomSourcesReset", false);
            report.addProperty(
                "targetInput",
                "actual EntityPig with controlled positions, movement base 0, knockback base 1 and health 2000; mod attributes/effects retained");
            report.addProperty(
                "withdrawalInput",
                "reactive per-arm draw boundaries, not an identical absolute-tick script");
            report.addProperty("statisticalAdmission", false);
            report.addProperty("complete14ClassBenchmarkAccepted", false);
            report.addProperty("mathematicalVerdict", "NOT_EVALUATED; delta not specified");
            report.addProperty("fullInitialNbtEquivalence", "NOT_PROVEN; all raw NBT retained");
            report.add("frames", frames);
            report.add("inputEvents", inputs);
            report.add("shots", shots);
            report.add("damageEvents", damage);
        }

        void setup() throws Exception {
            f = new ModernCoreTests.Fixture(h, false, 1, 24);
            difficulty = h.getWorld().difficultySetting;
            h.getWorld().difficultySetting = EnumDifficulty.HARD;
            f.maid.setPosition(f.x + 8.5, f.y + 1, f.z + 4.5);
            f.maid.setTask(MaidTask.RANGED_ATTACK);
            f.maid.setSchedule(MaidSchedule.ALL);
            f.maid.setPickup(false);
            // 安全场景固定正常坐姿射击输入，仍由完整根的RIDE_WORK/stationary入口执行
            if (scene.equals("safety")) f.maid.setSitting(true);
            if (scene.equals("safety")) weapon.addEnchantment(Enchantment.flame, 1);
            f.maid.setCurrentItemOrArmor(0, weapon);
            f.maid.setOffhandItem(null);
            f.maid.getMaidInventory()
                .setInventorySlotContents(0, ammunition);
            DeterministicMaidSpawn.freezeInitialState(f.maid);
            h.assertTrue(
                f.maid.getHomeArea()
                    .setEnabled(true),
                "Controlled ranged home input rejected");
            nativeOwner();
            target = new EntityPig(h.getWorld());
            target.getEntityAttribute(SharedMonsterAttributes.maxHealth)
                .setBaseValue(2000);
            target.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
                .setBaseValue(0);
            target.getEntityAttribute(SharedMonsterAttributes.knockbackResistance)
                .setBaseValue(1);
            target.setHealth(2000);
            report.add("targetBeforeJoin", entity(target, true));
            h.spawnEntity(target, 17.5, 1, 4.5);
            friend = new EntityWolf(h.getWorld());
            friend.setTamed(true);
            friend.func_152115_b(
                owner.getUniqueID()
                    .toString());
            friend.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
                .setBaseValue(0);
            friend.setHealth(20);
            h.spawnEntity(friend, 1.5, 1, 1.5);
            f.maid.getAttackListData()
                .attackGroups()
                .put(MaidCombatEntities.id(target), MonsterType.HOSTILE);
            var selected = profile == MaidBrainProfile.ENHANCED ? f.maid.selectBrainProfileForDevelopment(profile)
                : f.maid.selectBrainProfileForDevelopment(profile, MaidFaithfulFlightProfile.NATIVE_STATIC_CONTROL);
            h.assertTrue(
                selected == MaidPolicyRuntime.SelectionResult.SELECTED,
                "Complete ranged factory rejected the declared arm");
            runtime = f.maid.getBrainCoordinator()
                .peekPolicyRuntime();
            bow = actor().rangedBow();
            h.assertTrue(
                bow.flightProfile() == (profile == MaidBrainProfile.ENHANCED
                    ? MaidBowAttack.ModernFlightProfile.NATIVE_MOTION_COMPENSATION
                    : MaidBowAttack.ModernFlightProfile.NATIVE_STATIC_COMPENSATION),
                "Factory flight contract differs");
            report.add(
                "productionArtifact",
                DevelopmentArtifactIdentity.capture(
                    com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid.class,
                    com.tianluoqaq.tlmnh.common.ai.faithful.MaidFaithfulBrain.class,
                    com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedCoreBrain.class,
                    MaidBowAttack.class,
                    com.tianluoqaq.tlmnh.common.combat.ObservedTargetMotion.class));
            report.add(
                "observerArtifact",
                DevelopmentArtifactIdentity.capture(
                    NaturalRangedPairTests.class,
                    Collector.class,
                    Flight.class,
                    ModernCoreTests.Fixture.class,
                    SleepAttributePortSnapshot.class));
            report.add(
                "spawnInputArtifact",
                DevelopmentArtifactIdentity.capture(DeterministicMaidSpawn.class, IsolatedPlayerChannel.class));
            var initial = new NBTTagCompound();
            f.maid.writeToNBT(initial);
            initial = (NBTTagCompound) initial.copy();
            report.add("maidInitialBindingBefore", SleepAttributePortSnapshot.binaryNbt(initial));
            report.add("maidInitial", entity(f.maid, true));
            report.add("maidMappedAttributes", SleepAttributePortSnapshot.capture(f.maid));
            var after = new NBTTagCompound();
            f.maid.writeToNBT(after);
            report.add("maidInitialBindingAfter", SleepAttributePortSnapshot.binaryNbt((NBTTagCompound) after.copy()));
            h.assertTrue(initial.equals(after), "Initial native NBT changed while recording actual ranged inputs");
            report.addProperty("sameSideMaidInitialStructureBound", true);
            report.add("targetInitial", entity(target, true));
            report.add("ownerInitial", entity(owner, true));
            report.add("friendInitial", entity(friend, true));
            report.addProperty(
                "ownerRegistration",
                "SCM.initializeConnectionToPlayer including normal FML login; isolated PLAY connection");
            conditions = new JsonObject();
            conditions.add("target", physicalConditions(target));
            conditions.add("maid", physicalConditions(f.maid));
            conditions.addProperty("sitting", f.maid.isSitting());
            conditions.addProperty("nativeGravity", (double) .05F);
            conditions.addProperty("nativeDrag", (double) .99F);
            conditions.add("weapon", item(weapon));
            conditions.add("ammunition", item(ammunition));
            var environment = new JsonObject();
            environment.addProperty(
                "providerClass",
                h.getWorld().provider.getClass()
                    .getName());
            environment.addProperty("dimension", h.getWorld().provider.dimensionId);
            environment.addProperty("difficulty", h.getWorld().difficultySetting.name());
            environment.addProperty(
                "dayTime",
                h.getWorld()
                    .getWorldTime());
            environment.addProperty(
                "raining",
                h.getWorld()
                    .isRaining());
            environment.addProperty(
                "thundering",
                h.getWorld()
                    .isThundering());
            environment.addProperty(
                "canSpawnAnimals",
                MinecraftServer.getServer()
                    .getCanSpawnAnimals());
            report.add("environmentInitial", environment);
            report.add("comparedInitialConditions", conditions);
            f.maid.getBrainCoordinator()
                .getTelemetry()
                .start("ranged-" + scene + "-" + arm, 8192, 1024);
            FMLCommonHandler.instance()
                .bus()
                .register(this);
            MinecraftForge.EVENT_BUS.register(this);
        }

        private void nativeOwner() {
            var server = MinecraftServer.getServer();
            h.getWorld().playerEntities.remove(f.owner);
            server.getConfigurationManager().playerEntityList.remove(f.owner);
            f.owner.setDead();
            owner = new EntityPlayerMP(
                server,
                h.getWorld(),
                new GameProfile(f.owner.getUniqueID(), "RangedOwner"),
                new ItemInWorldManager(h.getWorld()));
            var manager = new NetworkManager(false);
            channel = new EmbeddedChannel(manager);
            manager.setConnectionState(EnumConnectionState.PLAY);
            var handler = new NetHandlerPlayServer(server, manager, owner);
            IsolatedPlayerChannel.verify(manager, channel);
            owner.setPosition(f.x + 8.5, f.y + 1, f.z + 7);
            h.assertTrue(
                server.worldServerForDimension(owner.dimension) == h.getWorld(),
                "Native owner world mismatch");
            server.getConfigurationManager()
                .initializeConnectionToPlayer(manager, owner, handler);
            nativeLoginComplete = true;
            IsolatedPlayerChannel.verify(manager, channel);
            owner.setGameType(GameType.SURVIVAL);
            owner.capabilities.disableDamage = false;
            owner.capabilities.allowFlying = owner.capabilities.isFlying = true;
            owner.setHealth(owner.getMaxHealth());
            owner.setPosition(f.x + 8.5, f.y + 1, f.z + 7);
            owner.motionX = owner.motionY = owner.motionZ = 0;
            h.assertTrue(
                owner.getClass() == EntityPlayerMP.class && f.maid.getOwner() == owner
                    && server.getConfigurationManager().playerEntityList.contains(owner)
                    && h.getWorld()
                        .getEntityByID(owner.getEntityId()) == owner,
                "Exact native owner registration failed");
        }

        ModernFollowCoreActor actor() {
            var brain = f.maid.getBrainCoordinator();
            return profile == MaidBrainProfile.ENHANCED ? brain.peekEnhancedCore()
                .actor()
                : brain.peekFaithfulBrain()
                    .getActor();
        }

        @SubscribeEvent
        public void living(LivingEvent.LivingUpdateEvent e) {
            if (e.entityLiving == f.maid) livingTicks++;
        }

        @SubscribeEvent
        public void tick(TickEvent.WorldTickEvent e) {
            if (closed || complete || failure != null || e.world != h.getWorld()) return;
            try {
                if (e.phase == TickEvent.Phase.START) applyInputs();
                else observe();
            } catch (Exception | LinkageError | AssertionError error) {
                com.tianluoqaq.tlmnh.common.protection.IntegrationGuard.throwIfFatal(error);
                failure = new IllegalStateException(
                    "Natural ranged pair failed scene=" + scene + " arm=" + arm + " frame=" + frame,
                    error);
                report.addProperty("error", failure + " cause=" + error);
            }
        }

        private void applyInputs() {
            owner.setPosition(f.x + 8.5, f.y + 1, f.z + 7);
            owner.motionX = owner.motionY = owner.motionZ = 0;
            owner.fallDistance = 0;
            if (target.isEntityAlive()) {
                double z = 4.5, y = 1;
                if (scene.equals("motion")) {
                    if (frame >= 30 && frame < 90) z += 1.7 * Math.sin((frame - 30) * .12);
                    else if (frame >= 90 && frame < 150) z += 1.7 * Math.sin((frame - 90) * (frame - 90) * .003);
                    else if (frame >= 150) y += Math.max(0, 1.3 * Math.sin((frame - 150) * Math.PI / 12));
                }
                target.setPosition(f.x + 17.5, f.y + y, f.z + z);
                target.motionX = target.motionY = target.motionZ = 0;
            }
            if (scene.equals("safety")) {
                if (frame == 60 || frame == 105) {
                    wall = frame == 60;
                    h.assertTrue(f.maid.posX < f.x + 13, "Wall input no longer separates shooter and target");
                    for (int y = 1; y < 10; y++)
                        for (int z = 1; z < 8; z++) h.setBlock(13, y, z, wall ? Blocks.stone : Blocks.air);
                    input(wall ? "wall_close" : "wall_open");
                }
                if (frame >= 120 && crossings < 2) crossFriendly();
            }
            if (!queued.isEmpty()) {
                String action = queued;
                queued = "";
                boundaryShots = bow.getShots();
                phaseAt = frame;
                if (action.equals("remove_ammo")) {
                    heldAmmo = f.maid.getMaidInventory()
                        .getStackInSlot(0);
                    heldAmmoSnapshot = heldAmmo.copy();
                    f.maid.getMaidInventory()
                        .setInventorySlotContents(0, null);
                    phase = 1;
                } else if (action.equals("restore_ammo")) {
                    f.maid.getMaidInventory()
                        .setInventorySlotContents(0, heldAmmo);
                    heldAmmo = null;
                    phase = 2;
                } else if (action.equals("remove_weapon")) {
                    heldBow = f.maid.getHeldItem();
                    heldBowSnapshot = heldBow.copy();
                    f.maid.setCurrentItemOrArmor(0, new ItemStack(Items.stick));
                    phase = 3;
                } else if (action.equals("restore_weapon")) {
                    f.maid.setCurrentItemOrArmor(0, heldBow);
                    heldBow = null;
                    phase = 4;
                } else if (action.equals("remove_target")) {
                    target.setDead();
                    phase = 5;
                }
                input(action);
            }
            targetInput = entity(target, false);
            ownerInput = entity(owner, false);
        }

        private void crossFriendly() {
            if (crossing == null) for (var flight : flights) if (!flight.arrow.isDead && !grounded(flight.arrow)
                && !flight.usedForCrossing
                && flight.arrow.ticksExisted >= 1
                && flight.arrow.posX < target.posX - 1) {
                    crossing = flight;
                    flight.usedForCrossing = true;
                    flight.ignoredBeforeCrossing = ((MaidArrowImpactState) flight.arrow).tlmnh$ignoredImpacts();
                    h.assertTrue(flight.arrow.isBurning(), "Safety crossing requires an actual flaming native arrow");
                    crossing.row.addProperty("friendlyCrossing", crossings == 0 ? "same_owner_pet" : "native_owner");
                    input("cross_" + (crossings == 0 ? "pet" : "owner"));
                    break;
                }
            if (crossing == null) return;
            var a = crossing.arrow;
            if (((MaidArrowImpactState) a).tlmnh$ignoredImpacts() > crossing.ignoredBeforeCrossing) {
                h.assertTrue(
                    friend.getHealth() == 20 && owner.getHealth() == owner.getMaxHealth()
                        && !friend.isBurning()
                        && !owner.isBurning(),
                    "Natural friendly crossing harmed an allied actor");
                friend.setPosition(f.x + 1.5, f.y + 1, f.z + 1.5);
                crossings++;
                input("confirmed_cross_" + (crossings == 1 ? "pet" : "owner"));
                crossing = null;
                return;
            }
            h.assertTrue(
                !a.isDead && !grounded(a),
                "Controlled friend never intersected its selected live native arrow");
            EntityLivingBase ally = crossings == 0 ? friend : owner;
            h.assertTrue(
                !ally.isEntityInvulnerable() && (!(ally instanceof EntityPlayerMP p) || !p.capabilities.disableDamage),
                "Friendly crossing used an immune actor");
            if (ally == owner) {
                int immunity = ((Integer) cpw.mods.fml.relauncher.ReflectionHelper
                    .getPrivateValue(EntityPlayerMP.class, owner, "field_147101_bU")).intValue();
                crossing.row.addProperty("actualPlayerSpawnProtectionTicks", immunity);
                h.assertTrue(immunity <= 0, "Native owner is still protected by its actual spawn timer");
            }
            ally.setPosition(
                a.posX + a.motionX * .5,
                a.posY + a.motionY * .5 - ally.height * .5,
                a.posZ + a.motionZ * .5);
            ally.motionX = ally.motionY = ally.motionZ = 0;
        }

        private void observe() {
            var brain = f.maid.getBrainCoordinator();
            h.assertTrue(
                f.maid.isEntityAlive() && f.maid.ticksExisted == age + 1
                    && livingTicks == 1
                    && brain.peekPolicyRuntime() == runtime
                    && runtime.isReady()
                    && runtime.getProfile() == profile
                    && f.maid.getOwner() == owner
                    && brain.getFollowController()
                        .getTicks() == 0
                    && brain.getCombatController()
                        .getTicks() == 0,
                "Natural full root, native owner or tick phase changed");
            age = f.maid.ticksExisted;
            livingTicks = 0;
            h.assertTrue(bow == actor().rangedBow(), "Observed release actuator was replaced");
            var row = new JsonObject();
            row.addProperty("frame", frame);
            row.addProperty("age", age);
            row.addProperty("phase", phase);
            row.addProperty("wall", wall);
            row.add("maid", entity(f.maid, false));
            row.add("target", entity(target, false));
            row.add("owner", entity(owner, false));
            row.add("targetInputBeforeWorldUpdate", targetInput);
            row.add("ownerInputBeforeWorldUpdate", ownerInput);
            row.addProperty("targetVisible", target.isEntityAlive() && f.maid.canEntityBeSeen(target));
            row.addProperty("targetSelected", f.maid.getAttackTarget() == target);
            row.addProperty(
                "canObserveShotOpportunity",
                target.isEntityAlive() && f.maid.getHeldItem() == weapon
                    && MaidBowAttack.modernArrowSlot(f.maid) >= 0
                    && f.maid.canEntityBeSeen(target));
            row.addProperty(
                "opportunityScope",
                "observed weapon/ammo/LOS/target facts; not invented policy release permission");
            row.addProperty(
                "usedTicks",
                f.maid.getItemUseState()
                    .getUsedTicks());
            row.addProperty(
                "bowStatus",
                bow.getStatus()
                    .name());
            row.addProperty("rejectedShots", bow.getRejectedShots());
            row.addProperty("confirmedShots", bow.getShots());
            row.addProperty("inventoryArrows", arrowCount());
            row.addProperty("externallyHeldArrows", heldAmmo == null ? 0 : heldAmmo.stackSize);
            row.addProperty("bowWear", weapon.getItemDamage());
            var airborne = new JsonArray();
            for (var flight : flights) {
                flight.observe(damageThisFrame);
                if (!flight.arrow.isDead) airborne.add(flight.state());
            }
            row.add("arrows", airborne);
            frames.add(row);
            h.assertTrue(
                arrowCount() + (heldAmmo == null ? 0 : heldAmmo.stackSize) + bow.getShots() == 24
                    && weapon.getItemDamage() == bow.getShots(),
                "Finite ammunition or original bow wear lost conservation");
            if (wall) h.assertTrue(
                flights.stream()
                    .noneMatch(v -> v.spawnFrame >= 60 && v.spawnFrame < 105),
                "A new native arrow was published through the actual wall");
            if (scene.equals("withdraw")) withdrawal();
            damageThisFrame = 0;
            drain();
            if (++frame == (scene.equals("withdraw") ? 280 : 220)) finish();
        }

        private void withdrawal() {
            boolean drawing = f.maid.getItemUseState()
                .peek() != null
                && f.maid.getItemUseState()
                    .peek()
                    .getKind() == MaidItemUseState.Kind.BOW
                && f.maid.getItemUseState()
                    .getUsedTicks() >= 3;
            if (phase == 0 && drawing) queued = "remove_ammo";
            else if (phase == 1) {
                h.assertTrue(
                    bow.getShots() == boundaryShots && ItemStack.areItemStacksEqual(heldAmmoSnapshot, heldAmmo),
                    "Ammo withdrawal released a phantom arrow or rewrote external original ammunition");
                if (frame - phaseAt >= 20) queued = "restore_ammo";
            } else if (phase == 2 && bow.getShots() > boundaryShots && drawing) queued = "remove_weapon";
            else if (phase == 3) {
                h.assertTrue(
                    bow.getShots() == boundaryShots && ItemStack.areItemStacksEqual(heldBowSnapshot, heldBow),
                    "Weapon withdrawal released a phantom arrow or rewrote the external original bow");
                if (frame - phaseAt >= 20) queued = "restore_weapon";
            } else if (phase == 4 && bow.getShots() > boundaryShots) queued = "remove_target";
            else if (phase == 5)
                h.assertTrue(bow.getShots() == boundaryShots, "A removed target retained release permission");
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void spawned(EntityJoinWorldEvent e) {
            if (!(e.entity instanceof EntityArrow a) || a.shootingEntity != f.maid) return;
            var flight = new Flight(this, a);
            flights.add(flight);
            shots.add(flight.row);
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void hurt(LivingHurtEvent e) {
            if (e.entityLiving != target && e.entityLiving != friend && e.entityLiving != owner) return;
            if (e.entityLiving == target) damageThisFrame++;
            var row = new JsonObject();
            row.addProperty("frame", frame);
            row.addProperty(
                "victim",
                e.entityLiving.getUniqueID()
                    .toString());
            row.addProperty("amount", e.ammount);
            row.addProperty("canceled", e.isCanceled());
            row.addProperty("healthBefore", e.entityLiving.getHealth());
            Entity source = e.source.getSourceOfDamage();
            row.addProperty(
                "source",
                source == null ? ""
                    : source.getUniqueID()
                        .toString());
            row.addProperty("evidence", "LivingHurt candidate; paired END health delta retained separately");
            damage.add(row);
            for (var flight : flights) if (source == flight.arrow && e.entityLiving == target && !e.isCanceled()) {
                flight.pendingHealth = target.getHealth();
                flight.row.addProperty("hurtCandidateFrame", frame);
            }
            if ((e.entityLiving == friend || e.entityLiving == owner) && source instanceof EntityArrow a
                && a.shootingEntity == f.maid) h.fail("Maid arrow reached an allied LivingHurt gate");
        }

        private int arrowCount() {
            int count = 0;
            var inventory = f.maid.getMaidInventory();
            for (int i = 0; i < inventory.getSizeInventory(); i++) {
                var stack = inventory.getStackInSlot(i);
                if (stack != null && stack.getItem() == Items.arrow) count += stack.stackSize;
            }
            return count;
        }

        private void finish() {
            h.assertTrue(bow.getShots() > 0 && !flights.isEmpty(), "Natural root produced no actual arrow");
            if (scene.equals("safety")) h.assertTrue(
                !wall && crossings == 2,
                "Actual wall reopening and both pet/player in-flight crossings did not complete");
            if (scene.equals("withdraw")) h.assertTrue(
                phase == 5 && frame - phaseAt >= 20 && heldAmmo == null && heldBow == null,
                "Natural ammo/weapon withdrawal, resume and target invalidation sequence did not complete");
            var telemetry = f.maid.getBrainCoordinator()
                .getTelemetry();
            var snapshot = telemetry.snapshot();
            h.assertTrue(
                telemetry.isEnabled() && snapshot.droppedEvents == 0
                    && snapshot.droppedCandidates == 0
                    && snapshot.rejectedEvents == 0
                    && telemetry.getProjectileBindingsDropped() == 0,
                "Natural release opportunity/confirmation telemetry was incomplete");
            var releases = new JsonArray();
            for (var event : snapshot.events) if (event.kind == MaidTelemetryEvent.Kind.RELEASE_ATTEMPT
                || event.kind == MaidTelemetryEvent.Kind.RELEASE_CONFIRMED) releases.add(new Gson().toJsonTree(event));
            report.add("releaseEvents", releases);
            report.add("telemetryCounters", new Gson().toJsonTree(snapshot.counters));
            report.addProperty("confirmedShots", bow.getShots());
            report.addProperty("rejectedShots", bow.getRejectedShots());
            report.add("targetFinal", entity(target, true));
            report.addProperty("collectedFramesComplete", true);
            var other = FIRST.remove(scene);
            if (other == null) {
                FIRST.put(scene, this);
                report.addProperty("pairedInputCheck", "WAITING_OTHER_ARM");
            } else {
                boolean equal = conditions.equals(other.conditions);
                report.addProperty(
                    "pairedInputCheck",
                    equal ? "EXACT_RECORDED_PHYSICAL_CONDITIONS" : "DIFFERENT_RECORDED_CONDITIONS");
                other.report.addProperty(
                    "pairedInputCheck",
                    equal ? "EXACT_RECORDED_PHYSICAL_CONDITIONS" : "DIFFERENT_RECORDED_CONDITIONS");
                report.add("otherArmRecordedConditions", other.conditions);
                other.report.add("otherArmRecordedConditions", conditions);
                other.save();
                h.assertTrue(
                    equal,
                    "Actual mod attributes/effects/dimensions differ between the paired arms; raw evidence preserved");
            }
            complete = true;
            save();
        }

        private void input(String name) {
            var row = new JsonObject();
            row.addProperty("frame", frame);
            row.addProperty("action", name);
            row.addProperty("phase", phase);
            row.addProperty("shotsBefore", bow.getShots());
            row.add("target", entity(target, true));
            inputs.add(row);
        }

        private JsonObject entity(Entity e, boolean full) {
            var row = new JsonObject();
            row.addProperty(
                "class",
                e.getClass()
                    .getName());
            row.addProperty(
                "uuid",
                e.getUniqueID()
                    .toString());
            row.addProperty("id", e.getEntityId());
            row.addProperty("age", e.ticksExisted);
            row.addProperty("dimension", e.dimension);
            row.addProperty(
                "providerClass",
                e.worldObj.provider.getClass()
                    .getName());
            row.addProperty("alive", e.isEntityAlive());
            row.addProperty(
                "registered",
                h.getWorld()
                    .getEntityByID(e.getEntityId()) == e);
            row.add("position", vector(e.posX - f.x, e.posY - f.y, e.posZ - f.z));
            row.add("motion", vector(e.motionX, e.motionY, e.motionZ));
            if (e instanceof EntityLivingBase living) {
                row.addProperty("health", living.getHealth());
                row.addProperty("absorption", living.getAbsorptionAmount());
                row.add("physicalConditions", physicalConditions(living));
            }
            if (e instanceof EntityPlayerMP player) {
                row.addProperty(
                    "mode",
                    player.theItemInWorldManager.getGameType()
                        .getName());
                row.addProperty("flying", player.capabilities.isFlying);
                row.addProperty("mayFly", player.capabilities.allowFlying);
                row.addProperty("disableDamage", player.capabilities.disableDamage);
                row.addProperty("entityInvulnerable", player.isEntityInvulnerable());
                row.addProperty("sneaking", player.isSneaking());
                row.addProperty("invisible", player.isInvisible());
                row.addProperty("worldPlayerList", h.getWorld().playerEntities.contains(player));
                row.addProperty(
                    "connectedPlayerRegistry",
                    MinecraftServer.getServer()
                        .getConfigurationManager().playerEntityList.contains(player));
                row.addProperty("nativeHandlerPresent", player.playerNetServerHandler != null);
                row.addProperty("nativeLoginInitializationComplete", nativeLoginComplete);
                row.addProperty("memoryChannelOpen", channel != null && channel.isOpen());
                row.addProperty("realNetworkLogin", false);
            }
            if (full) {
                var raw = new NBTTagCompound();
                e.writeToNBT(raw);
                row.add("fullNbt", SleepAttributePortSnapshot.binaryNbt((NBTTagCompound) raw.copy()));
            }
            return row;
        }

        private void drain() {
            if (channel == null) return;
            Object p;
            while ((p = channel.readOutbound()) != null) ReferenceCountUtil.release(p);
            while ((p = channel.readInbound()) != null) ReferenceCountUtil.release(p);
        }

        private void save() {
            try {
                Files.write(
                    Paths.get(
                        System.getProperty("horizonqa.reportDir"),
                        "natural-ranged-" + scene + "-" + arm + ".json"),
                    new Gson().toJson(report)
                        .getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new IllegalStateException("Cannot retain natural ranged evidence", e);
            }
        }

        void close() {
            if (closed) return;
            closed = true;
            FMLCommonHandler.instance()
                .bus()
                .unregister(this);
            MinecraftForge.EVENT_BUS.unregister(this);
            try {
                report.addProperty("observationComplete", complete && failure == null);
                if (f != null && bow != null) report.add("finalMaid", entity(f.maid, true));
                save();
            } finally {
                for (var flight : flights) flight.arrow.setDead();
                if (target != null) target.setDead();
                if (friend != null) friend.setDead();
                if (owner != null) {
                    var server = MinecraftServer.getServer();
                    if (server.getConfigurationManager().playerEntityList.contains(owner))
                        server.getConfigurationManager()
                            .playerLoggedOut(owner);
                    h.getWorld()
                        .removePlayerEntityDangerously(owner);
                    owner.setDead();
                }
                if (channel != null) {
                    channel.finish();
                    drain();
                }
                if (difficulty != null) h.getWorld().difficultySetting = difficulty;
            }
        }
    }

    static final class Flight {

        final Collector c;
        final EntityArrow arrow;
        final JsonObject row = new JsonObject();
        final int spawnFrame;
        final float gravity;
        double x, y, z, vx, vy, vz;
        int age, physicsSamples;
        int ignoredBeforeCrossing;
        boolean usedForCrossing;
        Float pendingHealth;

        Flight(Collector c, EntityArrow a) {
            this.c = c;
            arrow = a;
            spawnFrame = c.frame;
            gravity = NativeArrowGravity.forArrow(a);
            c.h.assertTrue(
                a.getClass() == EntityArrow.class && a instanceof MaidArrowSourceState
                    && a instanceof MaidArrowImpactState
                    && !a.getIsCritical()
                    && c.bow.getLastPhysics() != null
                    && c.bow.getLastPhysics().gravity == gravity
                    && c.bow.getLastPhysics().drag == (double) .99F
                    && gravity == .05F,
                "Natural paired release changed native arrow physics/class");
            var source = (MaidArrowSourceState) a;
            c.h.assertTrue(source.tlmnh$source() instanceof NBTTagCompound, "Exact source record missing");
            var tag = (NBTTagCompound) source.tlmnh$source();
            c.h.assertTrue(
                tag.getString("Shooter")
                    .equals(
                        c.f.maid.getUniqueID()
                            .toString())
                    && tag.getString("Owner")
                        .equals(
                            c.owner.getUniqueID()
                                .toString())
                    && tag.getInteger("Dimension") == c.h.getWorld().provider.dimensionId,
                "Arrow source identity differs");
            row.addProperty(
                "uuid",
                a.getUniqueID()
                    .toString());
            row.addProperty("spawnFrame", spawnFrame);
            row.addProperty("gravity", gravity);
            row.addProperty("drag", (double) .99F);
            row.add("launch", c.entity(a, true));
            row.add("source", SleepAttributePortSnapshot.binaryNbt((NBTTagCompound) tag.copy()));
            var solution = c.bow.getLastSolution();
            row.addProperty("predictedFlightTicks", solution.flightTicks);
            row.add(
                "predictedTarget",
                vector(
                    solution.predictedTarget.x - c.f.x,
                    solution.predictedTarget.y - c.f.y,
                    solution.predictedTarget.z - c.f.z));
            remember();
        }

        void observe(int targetDamageEvents) {
            row.addProperty(
                "confirmed",
                c.f.maid.getBrainCoordinator()
                    .getTelemetry()
                    .isConfirmedProjectile(arrow));
            if (!arrow.isDead && !grounded(arrow) && arrow.ticksExisted == age + 1) {
                c.h.assertTrue(
                    !arrow.isInWater() && Math.abs(arrow.posX - x - vx) < 1E-9
                        && Math.abs(arrow.posY - y - vy) < 1E-9
                        && Math.abs(arrow.posZ - z - vz) < 1E-9
                        && Math.abs(arrow.motionX - vx * (double) .99F) < 1E-12
                        && Math.abs(arrow.motionY - (vy * (double) .99F - gravity)) < 1E-12
                        && Math.abs(arrow.motionZ - vz * (double) .99F) < 1E-12,
                    "Actual natural arrow physics changed");
                physicsSamples++;
                row.addProperty("nativePhysicsSamples", physicsSamples);
            }
            if (pendingHealth != null) {
                row.addProperty("healthBeforeCandidate", pendingHealth);
                row.addProperty("healthAfterFrame", c.target.getHealth());
                row.addProperty("hit", c.target.getHealth() < pendingHealth && targetDamageEvents == 1);
                row.addProperty(
                    "hitEvidence",
                    targetDamageEvents == 1 ? "isolated LivingHurt plus same-frame health delta"
                        : "ambiguous multiple damage candidates; no confirmed hit assigned");
                pendingHealth = null;
            }
            row.addProperty("dead", arrow.isDead);
            row.addProperty("ignoredFriendlyImpacts", ((MaidArrowImpactState) arrow).tlmnh$ignoredImpacts());
            row.add("lastObserved", state());
            remember();
        }

        JsonObject state() {
            var r = new JsonObject();
            r.addProperty(
                "uuid",
                arrow.getUniqueID()
                    .toString());
            r.addProperty("age", arrow.ticksExisted);
            r.addProperty("inGround", grounded(arrow));
            r.addProperty("registered", arrow.worldObj.getEntityByID(arrow.getEntityId()) == arrow);
            r.addProperty("sourceStopped", ((MaidArrowSourceState) arrow).tlmnh$sourceStopped());
            r.add("position", vector(arrow.posX - c.f.x, arrow.posY - c.f.y, arrow.posZ - c.f.z));
            r.add("velocity", vector(arrow.motionX, arrow.motionY, arrow.motionZ));
            return r;
        }

        private void remember() {
            x = arrow.posX;
            y = arrow.posY;
            z = arrow.posZ;
            vx = arrow.motionX;
            vy = arrow.motionY;
            vz = arrow.motionZ;
            age = arrow.ticksExisted;
        }
    }

    private static JsonObject physicalConditions(EntityLivingBase e) {
        var result = new JsonObject();
        result.addProperty(
            "class",
            e.getClass()
                .getName());
        result.addProperty("width", e.width);
        result.addProperty("height", e.height);
        result.addProperty("eyeHeight", e.getEyeHeight());
        var attributes = new ArrayList<IAttributeInstance>();
        for (Object value : e.getAttributeMap()
            .getAllAttributes()) attributes.add((IAttributeInstance) value);
        attributes.sort(
            Comparator.comparing(
                v -> v.getAttribute()
                    .getAttributeUnlocalizedName()));
        var list = new JsonArray();
        for (var instance : attributes) {
            var row = new JsonObject();
            row.addProperty(
                "name",
                instance.getAttribute()
                    .getAttributeUnlocalizedName());
            row.addProperty("base", instance.getBaseValue());
            row.addProperty("value", instance.getAttributeValue());
            var modifiers = new ArrayList<AttributeModifier>(instance.func_111122_c());
            modifiers.sort(
                Comparator.comparing(
                    v -> v.getID()
                        .toString()));
            var mods = new JsonArray();
            for (var modifier : modifiers) {
                var m = new JsonObject();
                m.addProperty(
                    "uuid",
                    modifier.getID()
                        .toString());
                m.addProperty("name", modifier.getName());
                m.addProperty("amount", modifier.getAmount());
                m.addProperty("operation", modifier.getOperation());
                m.addProperty("persistent", modifier.isSaved());
                mods.add(m);
            }
            row.add("modifiers", mods);
            list.add(row);
        }
        result.add("attributes", list);
        var effects = new ArrayList<PotionEffect>();
        for (Object value : e.getActivePotionEffects()) effects.add((PotionEffect) value);
        effects.sort(Comparator.comparingInt(PotionEffect::getPotionID));
        var potions = new JsonArray();
        for (var effect : effects) {
            var tag = new NBTTagCompound();
            effect.writeCustomPotionEffectToNBT(tag);
            potions.add(SleepAttributePortSnapshot.binaryNbt(tag));
        }
        result.add("activePotionEffects", potions);
        return result;
    }

    private static JsonArray vector(double x, double y, double z) {
        var a = new JsonArray();
        a.add(new JsonPrimitive(x));
        a.add(new JsonPrimitive(y));
        a.add(new JsonPrimitive(z));
        return a;
    }

    private static JsonObject item(ItemStack stack) {
        var tag = new NBTTagCompound();
        stack.writeToNBT(tag);
        return SleepAttributePortSnapshot.binaryNbt(tag);
    }

    private static boolean grounded(EntityArrow arrow) {
        var tag = new NBTTagCompound();
        arrow.writeEntityToNBT(tag);
        return tag.getBoolean("inGround");
    }
}
