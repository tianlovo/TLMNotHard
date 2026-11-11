package com.tianluoqaq.tlmnh.qa;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.monster.EntitySkeleton;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.pathfinding.PathEntity;
import net.minecraft.util.MathHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.misc.MonsterType;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedCoreBrain;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatObservation;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.plan.CombatPlan;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.plan.CombatPlan.Macro;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatTerrain;
import com.tianluoqaq.tlmnh.common.ai.faithful.follow.FollowMoveActor.WalkTarget;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidPathNavigator;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatEntities;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.life.MaidLifeRules.Activity;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** QA-only proof of an observed hostile arrow followed by native evasive movement. */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class KnownArrowEvasionTests {

    private static final int DEADLINE = 240;
    private static final double ARROW_PAST_X = 1.25;
    private static final Gson TRACE_JSON = new GsonBuilder().serializeSpecialFloatingPointValues()
        .create();

    private KnownArrowEvasionTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = DEADLINE + 40, batch = "unified_combat_internal_dodge")
    public static void nativeKnownArrowIsEvadedByAuthorizedMovement(GameTestHelper h) {
        var f = new ModernCoreTests.Fixture(h, false, 16, 24, MaidBrainProfile.ENHANCED);
        f.maid.setSchedule(MaidSchedule.ALL);
        f.maid.setTask(MaidTask.ATTACK);
        f.maid.setCurrentItemOrArmor(0, new ItemStack(Items.bow));
        f.maid.getMaidInventory()
            .setInventorySlotContents(0, new ItemStack(Items.arrow, 24));
        // Keep the maid on the native skeleton firing line while the owner stands clear
        // of that lane. The skeleton still chooses its target through native bow AI.
        f.maid.setPosition(f.x + 8.5, f.y + 1, f.z + 4.5);
        f.owner.setPosition(f.x + 9.5, f.y + 1, f.z + 1.5);
        for (int x = 1; x < 20; x++) for (int z = 1; z < 8; z++) h.setBlock(x, 0, z, Blocks.stone);
        h.getWorld()
            .setWorldTime(18000);

        var skeleton = new EntitySkeleton(h.getWorld());
        // Keep this native bow input registered through SpecialMobs replacement ticks.
        skeleton.func_110163_bv();
        // Fixed initial health keeps the native shooter alive through the bounded proof window.
        skeleton.getEntityAttribute(net.minecraft.entity.SharedMonsterAttributes.maxHealth)
            .setBaseValue(200);
        skeleton.setHealth(200);
        skeleton.setPosition(f.x + 15.5, f.y + 1, f.z + 4.5);
        skeleton.setCurrentItemOrArmor(0, new ItemStack(Items.bow));
        skeleton.setAttackTarget(f.maid);
        h.assertTrue(
            h.getWorld()
                .spawnEntityInWorld(skeleton),
            "Native skeleton spawn failed");
        h.assertTrue(
            skeleton.attackEntityFrom(net.minecraft.util.DamageSource.causeMobDamage(f.maid), 1),
            "Native retaliation input was not accepted");
        f.maid.getAttackListData()
            .attackGroups()
            .put(MaidCombatEntities.id(skeleton), MonsterType.HOSTILE);
        h.assertTrue(
            f.maid.getOwner() == f.owner && f.maid.getHomeArea()
                .activity() == Activity.WORK,
            "Fixture did not establish the real owner and WORK schedule");

        // Diagnostic only: restore the exact prior global setting even after a failing test.
        boolean previousPlannerTrace = com.tianluoqaq.tlmnh.common.ai.enhanced.combat.plan.CombatPlanner.traceEnabled();
        h.afterTest(
            () -> com.tianluoqaq.tlmnh.common.ai.enhanced.combat.plan.CombatPlanner
                .setTraceEnabled(previousPlannerTrace));
        com.tianluoqaq.tlmnh.common.ai.enhanced.combat.plan.CombatPlanner.setTraceEnabled(true);
        var trace = new Trace(h.getWorld().provider.dimensionId, f.maid, f.owner, skeleton);
        MinecraftForge.EVENT_BUS.register(trace);
        h.afterTest(() -> {
            MinecraftForge.EVENT_BUS.unregister(trace);
            trace.close();
            if (skeleton.isEntityAlive()) skeleton.setDead();
        });

        int[] age = { 0 };
        h.onEachTick("native hostile bow, known arrow slice, C decision, and runner movement", () -> {
            age[0]++;
            if (age[0] > DEADLINE) {
                trace.requireCompleted();
                return;
            }
            var brain = f.maid.getBrainCoordinator();
            var core = brain.peekEnhancedCore();
            h.assertTrue(
                f.maid.isEntityAlive() && core != null
                    && !brain.isSuspended()
                    && brain.peekPolicyRuntime()
                        .isReady()
                    && brain.peekPolicyRuntime()
                        .getProfile() == MaidBrainProfile.ENHANCED
                    && brain.peekFaithfulBrain() == null
                    && f.maid.getTask() == MaidTask.ATTACK
                    && f.maid.getOwner() == f.owner
                    && f.maid.getHomeArea()
                        .activity() == Activity.WORK
                    && f.maid.worldObj == f.owner.worldObj
                    && f.maid.worldObj == skeleton.worldObj
                    && f.maid.dimension == f.owner.dimension
                    && f.maid.dimension == skeleton.dimension
                    && f.owner.getDistanceSqToEntity(skeleton) <= 256
                    && h.getWorld()
                        .getEntityByID(skeleton.getEntityId()) == skeleton
                    && h.getWorld()
                        .getEntityByID(f.owner.getEntityId()) == f.owner,
                "Combat left the complete Enhanced root or fixture actor world identity");
            trace.tick(
                f.maid,
                f.owner,
                skeleton,
                core,
                core.combat()
                    .lastSnapshot(),
                core.combat()
                    .lastPlan(),
                core.combat()
                    .lastResult());
            if (f.owner.isEntityAlive()) f.owner.onUpdateEntity();
            if (trace.completed) h.succeed();
        });
        h.succeedWhen(() -> trace.completed);
    }

    public static final class Trace {

        private final int dimension;
        private final EntityMaid maid;
        private final net.minecraft.entity.player.EntityPlayerMP owner;
        private final EntitySkeleton skeleton;
        private final List<EntityArrow> arrows = new ArrayList<>();
        private final List<Sample> samples = new ArrayList<>();
        private final Map<Integer, Double> authorizedProgressByTick = new HashMap<>();
        private final Map<UUID, Long> observedArrowSnapshots = new HashMap<>();
        private UUID observedArrow;
        private UUID selectedArrow;
        private Macro selectedMacro;
        private CombatPlan.Result runnerResult;
        private long selectedSnapshotTick = Long.MIN_VALUE, selectedDecisionId = Long.MIN_VALUE;
        private long selectedProjectileObservedAt = Long.MIN_VALUE;
        private double selectedX, selectedZ, movementStartX, movementStartZ;
        private double stationaryMaidX, stationaryMaidY, stationaryMaidZ;
        private double stationaryHalfWidth, stationaryHeight, selectedArrowRadius, destinationDistanceAtPlan;
        private int selectedAt = -1, movedAt = -1, hitRequests, acceptedHits;
        private final Map<UUID, Integer> arrowHitRequests = new HashMap<>();
        private final Map<UUID, Integer> arrowAcceptedHits = new HashMap<>();
        private final Map<UUID, Integer> ownerArrowHitRequests = new HashMap<>();
        private final Map<UUID, Integer> ownerArrowAcceptedHits = new HashMap<>();
        private final float initialOwnerHealth;
        private String currentGate = "NATIVE_ARROW_NOT_JOINED";
        private String lastDefensiveResult = "NOT_SEEN";
        private boolean completed, closed, ownerHealthLost, chainBroken;
        private String brokenReason = "NONE";
        private long crossingTick = Long.MIN_VALUE, previousWorldTick = Long.MIN_VALUE;
        private long previousDecisionId = Long.MIN_VALUE;
        private long selectedEntityEpoch, selectedObservationEpoch, selectedEquipmentEpoch;
        private boolean previousMovementAuthorized;
        private double previousMaidX, previousMaidZ, previousDestinationX, previousDestinationZ;
        private double authorizedProgress, authorizedDistance;
        private int authorizedIntervals;
        private PathEntity previousAuthorizedPath;

        Trace(int dimension, EntityMaid maid, net.minecraft.entity.player.EntityPlayerMP owner,
            EntitySkeleton skeleton) {
            this.dimension = dimension;
            this.maid = maid;
            this.owner = owner;
            this.skeleton = skeleton;
            this.initialOwnerHealth = owner.getHealth();
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void joined(EntityJoinWorldEvent event) {
            if (!(event.entity instanceof EntityArrow arrow) || event.world != maid.worldObj
                || arrow.shootingEntity != skeleton) return;
            arrows.add(arrow);
            System.out.println(
                "TLMNH_DODGE_ARROW_SPAWN dim=" + dimension
                    + " uuid="
                    + arrow.getUniqueID()
                    + " shooter="
                    + (arrow.shootingEntity == skeleton)
                    + " tick="
                    + event.world.getTotalWorldTime()
                    + " position="
                    + arrow.posX
                    + ","
                    + arrow.posY
                    + ","
                    + arrow.posZ);
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void attack(LivingAttackEvent event) {
            if (event.entityLiving == maid && event.source.getSourceOfDamage() instanceof EntityArrow arrow
                && arrows.contains(arrow)) {
                arrowHitRequests.merge(arrow.getUniqueID(), 1, Integer::sum);
                hitRequests++;
                System.out.println(
                    "TLMNH_DODGE_ARROW_ATTACK uuid=" + arrow.getUniqueID()
                        + " canceled="
                        + event.isCanceled()
                        + " tick="
                        + maid.worldObj.getTotalWorldTime()
                        + " hp="
                        + maid.getHealth()
                        + " damageSource="
                        + event.source);
            }
            if (event.entityLiving == owner && event.source.getSourceOfDamage() instanceof EntityArrow arrow
                && arrows.contains(arrow)) {
                ownerArrowHitRequests.merge(arrow.getUniqueID(), 1, Integer::sum);
                System.out.println(
                    "TLMNH_DODGE_OWNER_ARROW_ATTACK uuid=" + arrow.getUniqueID()
                        + " canceled="
                        + event.isCanceled()
                        + " tick="
                        + owner.worldObj.getTotalWorldTime()
                        + " hp="
                        + owner.getHealth());
            }
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void hurt(LivingHurtEvent event) {
            if (event.entityLiving == maid && event.source.getSourceOfDamage() instanceof EntityArrow arrow
                && arrows.contains(arrow)) {
                arrowAcceptedHits.merge(arrow.getUniqueID(), 1, Integer::sum);
                acceptedHits++;
                System.out.println(
                    "TLMNH_DODGE_ARROW_HURT uuid=" + arrow.getUniqueID()
                        + " tick="
                        + maid.worldObj.getTotalWorldTime()
                        + " amount="
                        + event.ammount
                        + " hp="
                        + maid.getHealth());
            }
            if (event.entityLiving == owner && event.source.getSourceOfDamage() instanceof EntityArrow arrow
                && arrows.contains(arrow)) {
                ownerArrowAcceptedHits.merge(arrow.getUniqueID(), 1, Integer::sum);
                System.out.println(
                    "TLMNH_DODGE_OWNER_ARROW_HURT uuid=" + arrow.getUniqueID()
                        + " tick="
                        + owner.worldObj.getTotalWorldTime()
                        + " amount="
                        + event.ammount
                        + " hp="
                        + owner.getHealth());
            }
        }

        void tick(EntityMaid maid, net.minecraft.entity.player.EntityPlayerMP owner, EntitySkeleton skeleton,
            EnhancedCoreBrain core, CombatSnapshot snapshot, CombatPlan plan, CombatPlan.Result result) {
            int tick = Math.max(0, maid.ticksExisted);
            long worldTick = maid.worldObj.getTotalWorldTime();
            ownerHealthLost |= owner.getHealth() < initialOwnerHealth;
            MovementAuthorization authorization = movementAuthorization(core, snapshot, plan, result, worldTick);
            System.out.println(
                "TLMNH_DODGE_FRAME worldTick=" + worldTick
                    + " actorTick="
                    + tick
                    + " maidPosition="
                    + maid.posX
                    + ","
                    + maid.posY
                    + ","
                    + maid.posZ
                    + " maidMotion="
                    + maid.motionX
                    + ","
                    + maid.motionY
                    + ","
                    + maid.motionZ
                    + " maidHurtTime="
                    + maid.hurtTime
                    + " ownerPosition="
                    + owner.posX
                    + ","
                    + owner.posY
                    + ","
                    + owner.posZ
                    + " skeletonPosition="
                    + skeleton.posX
                    + ","
                    + skeleton.posY
                    + ","
                    + skeleton.posZ
                    + " ownerHealthLost="
                    + ownerHealthLost
                    + " authorization="
                    + authorization.reason
                    + " walkIdentity="
                    + authorization.walkIdentity
                    + " nativePathIdentity="
                    + authorization.pathIdentity
                    + " previousPathIdentity="
                    + identity(previousAuthorizedPath)
                    + " snapshot="
                    + TRACE_JSON.toJson(snapshot)
                    + " plan="
                    + TRACE_JSON.toJson(plan)
                    + " result="
                    + result);
            // plan.trace is captured by production during the same decision; no extra quota/world query.
            if (snapshot != null && plan != null && plan.trace != null && !snapshot.projectiles.isEmpty()) {
                int candidateIndex = 0;
                for (var candidate : plan.trace.candidates) {
                    String fallbackRiskEnd = plan.trace.full ? "NOT_FALLBACK"
                        : candidate.continuing ? "CONTINUING_NEXT_STEP_NOT_EXPOSED"
                            : candidate.destination == null ? "NO_DESTINATION"
                                : TRACE_JSON.toJson(candidate.destination);
                    System.out.println(
                        "TLMNH_DODGE_CANDIDATE_TRACE worldTick=" + worldTick
                            + " index="
                            + candidateIndex++
                            + " candidate="
                            + TRACE_JSON.toJson(candidate)
                            + " plannedDestination="
                            + fallbackRiskEnd
                            + " fallbackModel=REACHABLE_2_TICK_SEGMENTS"
                            + " bodyStart="
                            + TRACE_JSON.toJson(snapshot.self.position)
                            + " bodySpeedPerTick="
                            + snapshot.self.movementPerTick
                            + " selectedMacro="
                            + plan.macro
                            + " selectedSimulated="
                            + plan.simulated
                            + " full="
                            + plan.trace.full
                            + " quota="
                            + TRACE_JSON.toJson(plan.quota));
                }
            }
            if (selectedAt >= 0 && !chainBroken) {
                if (ownerHealthLost || !maid.isEntityAlive()
                    || !owner.isEntityAlive()
                    || !skeleton.isEntityAlive()
                    || !visibleCurrentTarget(snapshot, worldTick)) breakChain("ACTOR_OR_VISIBLE_TARGET_WINDOW_LOST");
                else if (movedAt < 0 && (!authorization.authorized || !sameSelectedDefense(plan))) {
                    // An unexecuted proposal is not a motion interval. Preserve it in the trace, then
                    // bind a fresh current defense only when absolutely no movement was credited or seen.
                    if (authorization.authorized && selectedArrowStillLive()
                        && authorizedIntervals == 0
                        && authorizedDistance == 0
                        && authorizedProgress == 0
                        && Math.hypot(maid.posX - movementStartX, maid.posZ - movementStartZ) < 1.0E-6
                        && plan.entityEpoch == selectedEntityEpoch
                        && plan.observationEpoch == selectedObservationEpoch
                        && plan.equipmentEpoch == selectedEquipmentEpoch
                        && arrowHitRequests.getOrDefault(selectedArrow, 0) == 0
                        && arrowAcceptedHits.getOrDefault(selectedArrow, 0) == 0
                        && ownerArrowHitRequests.getOrDefault(selectedArrow, 0) == 0
                        && ownerArrowAcceptedHits.getOrDefault(selectedArrow, 0) == 0) {
                        System.out.println(
                            "TLMNH_DODGE_UNMOVED_PROPOSAL_RETIRED arrow=" + selectedArrow
                                + " oldMacro="
                                + selectedMacro
                                + " newMacro="
                                + plan.macro
                                + " tick="
                                + tick
                                + " creditedDistance="
                                + authorizedDistance
                                + " creditedProgress="
                                + authorizedProgress);
                        selectedAt = -1;
                        selectedArrow = null;
                        previousMovementAuthorized = false;
                        authorizedProgressByTick.clear();
                    } else breakChain("DEFENSIVE_MOVEMENT_AUTHORIZATION_LOST:" + authorization.reason);
                }
            }
            System.out.println(
                "TLMNH_DODGE_STATE actorTick=" + tick
                    + " worldTick="
                    + maid.worldObj.getTotalWorldTime()
                    + " maidHp="
                    + maid.getHealth()
                    + " ownerHp="
                    + owner.getHealth()
                    + " nativeTarget="
                    + (skeleton.getAttackTarget() == null ? "none"
                        : skeleton.getAttackTarget()
                            .getUniqueID())
                    + " skeletonHp="
                    + skeleton.getHealth()
                    + " targetAlive="
                    + skeleton.isEntityAlive()
                    + " task="
                    + maid.getTask()
                    + " profile="
                    + maid.getBrainCoordinator()
                        .peekPolicyRuntime()
                        .getProfile()
                    + " held="
                    + CombatObservation.fingerprint(maid.getHeldItem())
                    + " offhand="
                    + CombatObservation.fingerprint(maid.getOffhandItem())
                    + " inventory="
                    + inventoryFingerprint(maid));
            for (EntityArrow arrow : arrows) {
                Sample sample = new Sample(tick, arrow);
                samples.add(sample);
                System.out.println(
                    "TLMNH_DODGE_ARROW_SAMPLE actorTick=" + tick
                        + " worldTick="
                        + maid.worldObj.getTotalWorldTime()
                        + " uuid="
                        + sample.arrow
                        + " x="
                        + sample.x
                        + " y="
                        + sample.y
                        + " z="
                        + sample.z
                        + " motion="
                        + sample.motionX
                        + ","
                        + sample.motionY
                        + ","
                        + sample.motionZ
                        + " dead="
                        + sample.dead);
            }
            if (knownThreatSlice(snapshot, worldTick)) {
                for (CombatSnapshot.Projectile projectile : snapshot.projectiles) {
                    EntityArrow arrow = nativeArrow(projectile.id);
                    long age = snapshot.tick - projectile.observedAt;
                    if (arrow == null || arrow.isDead || age < 0 || age > 2) continue;
                    if (observedArrowSnapshots.putIfAbsent(projectile.id, snapshot.tick) == null) {
                        if (observedArrow == null) observedArrow = projectile.id;
                        System.out.println(
                            "TLMNH_DODGE_ARROW_OBSERVED uuid=" + projectile.id
                                + " snapshotTick="
                                + snapshot.tick
                                + " actorTick="
                                + tick
                                + " observedAt="
                                + projectile.observedAt
                                + " age="
                                + age
                                + " complete="
                                + snapshot.complete
                                + " position="
                                + projectile.position.x
                                + ","
                                + projectile.position.y
                                + ","
                                + projectile.position.z
                                + " velocity="
                                + projectile.velocity.x
                                + ","
                                + projectile.velocity.y
                                + ","
                                + projectile.velocity.z);
                    }
                }
            }
            if (selectedAt < 0) {
                currentGate = gateBeforeSelection(snapshot, plan, worldTick);
                if (!ownerHealthLost && knownThreatSlice(snapshot, worldTick)
                    && plan != null
                    && plan.observedAt == worldTick
                    && plan.expiresAt >= worldTick
                    && skeleton.getUniqueID()
                        .equals(plan.targetId)
                    && plan.destination != null
                    && defensive(plan.macro)
                    && Double.isFinite(plan.destination.x)
                    && Double.isFinite(plan.destination.z)
                    && skeleton.isEntityAlive()
                    && owner.isEntityAlive()
                    && snapshot.target(skeleton.getUniqueID()) != null
                    && snapshot.target(skeleton.getUniqueID()).visible
                    && snapshot.target(skeleton.getUniqueID())
                        .fresh(snapshot.tick)) {
                    for (CombatSnapshot.Projectile projectile : snapshot.projectiles) {
                        EntityArrow arrow = nativeArrow(projectile.id);
                        if (arrow == null || arrow.isDead
                            || !observedArrowSnapshots.containsKey(projectile.id)
                            || snapshot.tick - projectile.observedAt < 0
                            || snapshot.tick - projectile.observedAt > 2
                            || arrowHitRequests.getOrDefault(projectile.id, 0) > 0
                            || arrowAcceptedHits.getOrDefault(projectile.id, 0) > 0
                            || !predictedToEnterStationaryMaid(snapshot, projectile, maid)) continue;
                        lastDefensiveResult = String.valueOf(result);
                        currentGate = "DEFENSIVE_PLAN_RUNNER_" + lastDefensiveResult;
                        if (result != CombatPlan.Result.RUNNING || !authorization.authorized) {
                            currentGate = "DEFENSIVE_MOVEMENT_NOT_AUTHORIZED:" + authorization.reason;
                            continue;
                        }
                        selectedAt = tick;
                        selectedSnapshotTick = snapshot.tick;
                        selectedDecisionId = plan.decisionId;
                        selectedEntityEpoch = plan.entityEpoch;
                        selectedObservationEpoch = plan.observationEpoch;
                        selectedEquipmentEpoch = plan.equipmentEpoch;
                        selectedProjectileObservedAt = projectile.observedAt;
                        selectedArrow = projectile.id;
                        selectedMacro = plan.macro;
                        runnerResult = result;
                        selectedX = plan.destination.x;
                        selectedZ = plan.destination.z;
                        movementStartX = maid.posX;
                        movementStartZ = maid.posZ;
                        stationaryMaidX = snapshot.self.position.x;
                        stationaryMaidY = snapshot.self.position.y;
                        stationaryMaidZ = snapshot.self.position.z;
                        stationaryHalfWidth = snapshot.self.halfWidth;
                        stationaryHeight = snapshot.self.height;
                        selectedArrowRadius = projectile.radius;
                        destinationDistanceAtPlan = Math.hypot(selectedX - movementStartX, selectedZ - movementStartZ);
                        currentGate = "NATIVE_MOVEMENT_TOWARD_PLAN_DESTINATION";
                        System.out.println(
                            "TLMNH_DODGE_PLAN arrow=" + selectedArrow
                                + " snapshotTick="
                                + selectedSnapshotTick
                                + " projectileObservedAt="
                                + selectedProjectileObservedAt
                                + " decisionId="
                                + selectedDecisionId
                                + " actorTick="
                                + tick
                                + " macro="
                                + selectedMacro
                                + " destination="
                                + selectedX
                                + ","
                                + selectedZ
                                + " distanceAtPlan="
                                + destinationDistanceAtPlan
                                + " runner="
                                + runnerResult
                                + " targetAlive="
                                + skeleton.isEntityAlive()
                                + " targetVisible=true stationaryCollision=true");
                        break;
                    }
                }
            }
            if (selectedAt >= 0 && !chainBroken
                && tick > selectedAt
                && selectedArrowStillLive()
                && previousMovementAuthorized
                && previousWorldTick + 1 == worldTick
                && authorization.authorized
                && sameSelectedDefense(plan)) {
                double dx = maid.posX - previousMaidX, dz = maid.posZ - previousMaidZ;
                double toX = previousDestinationX - previousMaidX, toZ = previousDestinationZ - previousMaidZ;
                double progress = Math.hypot(toX, toZ)
                    - Math.hypot(previousDestinationX - maid.posX, previousDestinationZ - maid.posZ);
                // Do not credit knockback or an interval lacking defensive native-path ownership.
                if (maid.hurtTime == 0 && dx * toX + dz * toZ > 0 && progress > 0) {
                    authorizedDistance += Math.hypot(dx, dz);
                    authorizedProgress += progress;
                    authorizedIntervals++;
                    authorizedProgressByTick.put(tick, authorizedProgress);
                }
                System.out.println(
                    "TLMNH_DODGE_AUTHORIZED_INTERVAL worldTick=" + worldTick
                        + " fromDecision="
                        + previousDecisionId
                        + " toDecision="
                        + plan.decisionId
                        + " previousPath="
                        + identity(previousAuthorizedPath)
                        + " currentPath="
                        + authorization.pathIdentity
                        + " previousDestination="
                        + previousDestinationX
                        + ","
                        + previousDestinationZ
                        + " currentDestination="
                        + plan.destination.x
                        + ","
                        + plan.destination.z
                        + " dx="
                        + dx
                        + " dz="
                        + dz
                        + " progress="
                        + progress
                        + " hurtTime="
                        + maid.hurtTime
                        + " creditedDistance="
                        + authorizedDistance
                        + " creditedProgress="
                        + authorizedProgress);
                if (movedAt < 0 && authorizedDistance >= .45
                    && authorizedProgress >= .25
                    && movedTowardDestination(maid.posX, maid.posZ)) {
                    movedAt = tick;
                    currentGate = "ARROW_CROSSING_STATIONARY_BODY_CORRIDOR";
                    System.out.println(
                        "TLMNH_DODGE_MOVED arrow=" + selectedArrow
                            + " planTick="
                            + selectedAt
                            + " tick="
                            + tick
                            + " macro="
                            + selectedMacro
                            + " from="
                            + movementStartX
                            + ","
                            + movementStartZ
                            + " to="
                            + maid.posX
                            + ","
                            + maid.posZ
                            + " destination="
                            + selectedX
                            + ","
                            + selectedZ
                            + " distanceNow="
                            + Math.hypot(selectedX - maid.posX, selectedZ - maid.posZ)
                            + " authorizedIntervals="
                            + authorizedIntervals);
                }
            }
            if (selectedAt >= 0 && movedAt >= 0
                && !chainBroken
                && !ownerHealthLost
                && maid.isEntityAlive()
                && owner.isEntityAlive()
                && skeleton.isEntityAlive()
                && visibleCurrentTarget(snapshot, worldTick)
                && arrowCrossedStationaryBody(selectedArrow)
                && arrowHitRequests.getOrDefault(selectedArrow, 0) == 0
                && arrowAcceptedHits.getOrDefault(selectedArrow, 0) == 0
                && ownerArrowHitRequests.getOrDefault(selectedArrow, 0) == 0
                && ownerArrowAcceptedHits.getOrDefault(selectedArrow, 0) == 0) {
                completed = true;
                System.out.println(
                    "TLMNH_DODGE_PASS arrow=" + selectedArrow
                        + " planTick="
                        + selectedAt
                        + " snapshotTick="
                        + selectedSnapshotTick
                        + " decisionId="
                        + selectedDecisionId
                        + " moveTick="
                        + movedAt
                        + " crossingTick="
                        + crossingTick
                        + " macro="
                        + selectedMacro
                        + " method=KNOWN_ARROW_AUTHORIZED_MOVEMENT_V4 maidHp="
                        + maid.getHealth()
                        + " hpLossAllowed=true ownerHealthLost="
                        + ownerHealthLost
                        + " hitRequests="
                        + hitRequests
                        + " acceptedArrowHits="
                        + acceptedHits
                        + " sameArrowAttack="
                        + arrowHitRequests.getOrDefault(selectedArrow, 0)
                        + " sameArrowHurt="
                        + arrowAcceptedHits.getOrDefault(selectedArrow, 0)
                        + " skeletonAlive="
                        + skeleton.isEntityAlive()
                        + " samples="
                        + samples.size());
            }
            previousWorldTick = worldTick;
            previousMaidX = maid.posX;
            previousMaidZ = maid.posZ;
            previousMovementAuthorized = selectedAt >= 0 && !chainBroken
                && authorization.authorized
                && sameSelectedDefense(plan);
            previousAuthorizedPath = previousMovementAuthorized ? authorization.path : null;
            previousDecisionId = plan == null ? Long.MIN_VALUE : plan.decisionId;
            previousDestinationX = plan == null || plan.destination == null ? Double.NaN : plan.destination.x;
            previousDestinationZ = plan == null || plan.destination == null ? Double.NaN : plan.destination.z;
        }

        private void breakChain(String reason) {
            chainBroken = true;
            brokenReason = reason;
            currentGate = reason;
            System.out.println("TLMNH_DODGE_CHAIN_BROKEN arrow=" + selectedArrow + " reason=" + reason);
        }

        /** Local evidence only; incomplete background/projectile coverage stays explicitly unknown. */
        private boolean knownThreatSlice(CombatSnapshot snapshot, long now) {
            return snapshot != null && snapshot.tick == now
                && snapshot.self != null
                && snapshot.friendsComplete
                && snapshot.geometry != null
                && snapshot.geometry.complete
                && snapshot.geometry.fresh(now)
                && visibleCurrentTarget(snapshot, now);
        }

        private boolean visibleCurrentTarget(CombatSnapshot snapshot, long now) {
            if (snapshot == null || snapshot.tick != now) return false;
            CombatSnapshot.Target target = snapshot.target(skeleton.getUniqueID());
            return target != null && target.entityId == skeleton.getEntityId()
                && target.visible
                && target.observedAt == now
                && target.fresh(now)
                && skeleton.getUniqueID()
                    .equals(snapshot.selectedTarget);
        }

        private boolean sameSelectedDefense(CombatPlan plan) {
            return plan != null && plan.destination != null
                && plan.macro == selectedMacro
                && skeleton.getUniqueID()
                    .equals(plan.targetId)
                && plan.targetEntityId == skeleton.getEntityId()
                && plan.entityEpoch == selectedEntityEpoch
                && plan.observationEpoch == selectedObservationEpoch
                && plan.equipmentEpoch == selectedEquipmentEpoch;
        }

        /** Deliberately accepts only the single-destination slice; never guesses a runner route cursor. */
        private MovementAuthorization movementAuthorization(EnhancedCoreBrain core, CombatSnapshot snapshot,
            CombatPlan plan, CombatPlan.Result result, long now) {
            if (snapshot == null || snapshot.tick != now
                || plan == null
                || plan.observedAt != now
                || now > plan.expiresAt
                || plan.entityEpoch != snapshot.entityEpoch
                || plan.observationEpoch != snapshot.observationEpoch
                || plan.equipmentEpoch != snapshot.equipmentEpoch)
                return new MovementAuthorization("STALE_SNAPSHOT_OR_PLAN");
            if (!visibleCurrentTarget(snapshot, now) || !skeleton.getUniqueID()
                .equals(plan.targetId)
                || plan.targetEntityId != skeleton.getEntityId()
                || !defensive(plan.macro)
                || result != CombatPlan.Result.RUNNING
                || plan.destination == null) return new MovementAuthorization("NOT_CURRENT_DEFENSIVE_RUNNING_TARGET");
            var actor = core.actor();
            if (!actor.isCurrent()) return new MovementAuthorization("ACTOR_FRAME_NOT_CURRENT");
            WalkTarget walk = actor.peekWalkTarget();
            if (walk == null) return new MovementAuthorization("WALK_TARGET_ABSENT");
            var point = walk.currentBlockPosition();
            if (point.x != MathHelper.floor_double(plan.destination.x)
                || point.y != MathHelper.floor_double(plan.destination.y + .5)
                || point.z != MathHelper.floor_double(plan.destination.z))
                return new MovementAuthorization("WALK_IS_NOT_PLAN_SINGLE_DESTINATION");
            var navigator = (MaidPathNavigator) maid.getNavigator();
            PathEntity raw = navigator.getPath();
            var memory = actor.peekPathMemory();
            if (raw == null || raw.isFinished()
                || raw != actor.peekOwnedPath()
                || memory == null
                || memory.getIdentity() != raw
                || !navigator.isBrainTargetOwner(actor))
                return new MovementAuthorization("NATIVE_PATH_NOT_OWNED_BY_DEFENSIVE_WALK");
            var end = raw.getFinalPathPoint();
            if (end == null || end.xCoord != point.x || end.yCoord != point.y || end.zCoord != point.z)
                return new MovementAuthorization("NATIVE_PATH_END_DIFFERS_FROM_DEFENSIVE_WALK");
            return new MovementAuthorization(walk, raw);
        }

        private static String identity(Object value) {
            return value == null ? "null"
                : value.getClass()
                    .getName() + "@"
                    + System.identityHashCode(value);
        }

        private static final class MovementAuthorization {

            final boolean authorized;
            final String reason, walkIdentity, pathIdentity;
            final PathEntity path;

            MovementAuthorization(String reason) {
                authorized = false;
                this.reason = reason;
                walkIdentity = pathIdentity = "NOT_BOUND";
                path = null;
            }

            MovementAuthorization(WalkTarget walk, PathEntity path) {
                authorized = true;
                reason = "CURRENT_DEFENSIVE_SINGLE_DESTINATION_NATIVE_PATH";
                walkIdentity = identity(walk);
                pathIdentity = identity(path);
                this.path = path;
            }
        }

        private boolean selectedArrowStillLive() {
            for (EntityArrow arrow : arrows) if (arrow.getUniqueID()
                .equals(selectedArrow) && !arrow.isDead) return true;
            return false;
        }

        private EntityArrow nativeArrow(UUID id) {
            for (EntityArrow arrow : arrows) if (arrow.getUniqueID()
                .equals(id) && arrow.shootingEntity == skeleton) return arrow;
            return null;
        }

        private static String inventoryFingerprint(EntityMaid maid) {
            StringBuilder value = new StringBuilder();
            var inventory = maid.getMaidInventory();
            for (int slot = 0; slot < inventory.getSizeInventory(); slot++) {
                if (slot > 0) value.append('|');
                value.append(CombatObservation.fingerprint(inventory.getStackInSlot(slot)));
            }
            return value.toString();
        }

        private String gateBeforeSelection(CombatSnapshot snapshot, CombatPlan plan, long now) {
            if (arrows.isEmpty()) return "NATIVE_ARROW_NOT_JOINED";
            if (!knownThreatSlice(snapshot, now)) return "CURRENT_KNOWN_THREAT_SLICE_NOT_READY";
            if (observedArrowSnapshots.isEmpty()) return "NATIVE_ARROW_NOT_IN_CURRENT_COMPLETE_SNAPSHOT";
            if (!skeleton.isEntityAlive() || !owner.isEntityAlive()) return "LIVE_TARGET_OR_OWNER_LOST";
            if (plan == null || snapshot.tick != plan.observedAt || now > plan.expiresAt)
                return "CURRENT_PLAN_NOT_READY";
            if (!skeleton.getUniqueID()
                .equals(plan.targetId)) return "CURRENT_PLAN_TARGET_MISMATCH";
            if (!defensive(plan.macro)) return "NO_DEFENSIVE_PLAN";
            return "NO_FRESH_KNOWN_ARROW_PREDICTED_TO_HIT_STATIONARY_MAID";
        }

        private boolean movedTowardDestination(double x, double z) {
            double dx = x - movementStartX, dz = z - movementStartZ;
            double toX = selectedX - movementStartX, toZ = selectedZ - movementStartZ;
            double dot = dx * toX + dz * toZ;
            double distanceNow = Math.hypot(selectedX - x, selectedZ - z);
            return Math.hypot(dx, dz) >= .45 && dot > .05 && distanceNow <= destinationDistanceAtPlan - .25;
        }

        private boolean arrowCrossedStationaryBody(UUID id) {
            Sample previous = null;
            for (Sample sample : samples) {
                if (!sample.arrow.equals(id) || sample.tick < selectedAt) continue;
                if (previous != null && sample.tick == previous.tick + 1
                    && sample.tick > selectedAt
                    && authorizedProgressByTick.getOrDefault(sample.tick, 0.0) > 0
                    && !previous.dead
                    && crossingTick == Long.MIN_VALUE) {
                    CombatSnapshot.Vec from = new CombatSnapshot.Vec(
                        previous.x - stationaryMaidX,
                        previous.y - stationaryMaidY,
                        previous.z - stationaryMaidZ);
                    CombatSnapshot.Vec to = new CombatSnapshot.Vec(
                        sample.x - stationaryMaidX,
                        sample.y - stationaryMaidY,
                        sample.z - stationaryMaidZ);
                    double crossing = CombatTerrain.entry(
                        from,
                        to,
                        -stationaryHalfWidth - selectedArrowRadius,
                        -selectedArrowRadius,
                        -stationaryHalfWidth - selectedArrowRadius,
                        stationaryHalfWidth + selectedArrowRadius,
                        stationaryHeight + selectedArrowRadius,
                        stationaryHalfWidth + selectedArrowRadius);
                    if (Double.isFinite(crossing)) {
                        crossingTick = sample.tick;
                        System.out.println(
                            "TLMNH_DODGE_CROSSING arrow=" + id
                                + " fromTick="
                                + previous.tick
                                + " crossingTick="
                                + crossingTick
                                + " entry="
                                + crossing);
                    }
                }
                if (crossingTick != Long.MIN_VALUE && sample.tick >= crossingTick
                    && sample.x <= stationaryMaidX - ARROW_PAST_X) return true;
                previous = sample;
            }
            return false;
        }

        /** Project the snapshot's native arrow from its observed age into the stationary body volume. */
        private static boolean predictedToEnterStationaryMaid(CombatSnapshot snapshot,
            CombatSnapshot.Projectile projectile, EntityMaid maid) {
            if (snapshot.self == null || snapshot.self.position == null
                || Math.abs(snapshot.self.position.x - maid.posX) > .25
                || Math.abs(snapshot.self.position.z - maid.posZ) > .25) return false;
            long observedAge = snapshot.tick - projectile.observedAt;
            if (observedAge < 0 || observedAge > 2) return false;
            CombatSnapshot.Vec body = snapshot.self.position;
            for (int age = 0; age < 40; age++) {
                CombatSnapshot.Vec a = projectilePosition(projectile, (int) observedAge + age);
                CombatSnapshot.Vec b = projectilePosition(projectile, (int) observedAge + age + 1);
                double hit = CombatTerrain.entry(
                    a.subtract(body),
                    b.subtract(body),
                    -snapshot.self.halfWidth - projectile.radius,
                    -projectile.radius,
                    -snapshot.self.halfWidth - projectile.radius,
                    snapshot.self.halfWidth + projectile.radius,
                    snapshot.self.height + projectile.radius,
                    snapshot.self.halfWidth + projectile.radius);
                if (Double.isFinite(hit)) return true;
            }
            return false;
        }

        private static CombatSnapshot.Vec projectilePosition(CombatSnapshot.Projectile projectile, int elapsed) {
            double time = Math.max(0, elapsed), drag = projectile.drag;
            if (Math.abs(1 - drag) < 1.0E-9) return projectile.position.add(projectile.velocity.scale(time))
                .add(new CombatSnapshot.Vec(0, -projectile.gravity * time * Math.max(0, time - 1) / 2, 0));
            double series = (1 - Math.pow(drag, time)) / (1 - drag);
            return projectile.position.add(projectile.velocity.scale(series))
                .add(new CombatSnapshot.Vec(0, -projectile.gravity * (time - series) / (1 - drag), 0));
        }

        void requireCompleted() {
            if (!completed) throw new AssertionError(
                "No complete natural arrow-dodge chain: arrows=" + arrows.size()
                    + " firstObserved="
                    + observedArrow
                    + " observedArrowCount="
                    + observedArrowSnapshots.size()
                    + " selectedArrow="
                    + selectedArrow
                    + " plan="
                    + selectedMacro
                    + " runner="
                    + runnerResult
                    + " lastDefensiveResult="
                    + lastDefensiveResult
                    + " selectedAt="
                    + selectedAt
                    + " snapshotTick="
                    + selectedSnapshotTick
                    + " decisionId="
                    + selectedDecisionId
                    + " movedAt="
                    + movedAt
                    + " gate="
                    + currentGate
                    + " destination="
                    + selectedX
                    + ","
                    + selectedZ
                    + " hitRequests="
                    + hitRequests
                    + " acceptedHits="
                    + acceptedHits
                    + " samples="
                    + samples.size()
                    + " crossingTick="
                    + crossingTick
                    + " ownerHealthLost="
                    + ownerHealthLost
                    + " chainBroken="
                    + chainBroken
                    + " brokenReason="
                    + brokenReason
                    + " authorizedIntervals="
                    + authorizedIntervals);
        }

        void close() {
            if (closed) return;
            closed = true;
            for (EntityArrow arrow : arrows) if (!arrow.isDead) arrow.setDead();
            System.out.println(
                "TLMNH_DODGE_CLEANUP arrows=" + arrows.size()
                    + " hitRequests="
                    + hitRequests
                    + " acceptedHits="
                    + acceptedHits
                    + " completed="
                    + completed);
        }

        private static boolean defensive(Macro macro) {
            return macro == Macro.STRAFE_LEFT || macro == Macro.STRAFE_RIGHT
                || macro == Macro.RETREAT
                || macro == Macro.COVER_EDGE;
        }
    }

    private static final class Sample {

        final int tick;
        final UUID arrow;
        final double x, y, z, motionX, motionY, motionZ;
        final boolean dead;

        Sample(int tick, EntityArrow arrow) {
            this.tick = tick;
            this.arrow = arrow.getUniqueID();
            x = arrow.posX;
            y = arrow.posY;
            z = arrow.posZ;
            motionX = arrow.motionX;
            motionY = arrow.motionY;
            motionZ = arrow.motionZ;
            dead = arrow.isDead;
        }
    }
}
