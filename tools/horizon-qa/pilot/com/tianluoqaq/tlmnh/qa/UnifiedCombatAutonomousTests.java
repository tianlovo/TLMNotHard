package com.tianluoqaq.tlmnh.qa;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidHurtTarget;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.misc.MonsterType;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.plan.CombatPlan;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.plan.CombatPlan.Macro;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Kind;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatAttributes;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatEntities;
import com.tianluoqaq.tlmnh.common.combat.MaidItemUseState;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventory;
import com.tianluoqaq.tlmnh.common.life.MaidLifeRules.Activity;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** 原生tick驱动完整ENHANCED根，只布置输入和观察事件，不手工执行攻击或跳跃 */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class UnifiedCombatAutonomousTests {

    private static final int WALL_OPEN_AGE = 60, ARROWS = 16, SHOT_COOLDOWN = 12;
    private static final int MAX_DIAGNOSTIC_FRAMES = 200;
    private static final int MAX_ENV_DECISIONS = 24, MAX_ENV_FULL = 12, MAX_ENV_CANDIDATES = 12;
    private static final float TARGET_HEALTH = 200, EPSILON = .001F;

    private UnifiedCombatAutonomousTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 260, batch = "unified_autonomous_1_exchange_wall")
    public static void actualBrainExchangesUnknownStackAndCannotHurtThroughSealedWall(GameTestHelper h) {
        var unknown = tagged(new ItemStack(Items.stick, 3), "unknown-main");
        var sword = tagged(new ItemStack(Items.iron_sword), "backpack-sword");
        var sentinel = tagged(new ItemStack(Items.feather, 5), "unrelated-slot");
        var unknownBefore = unknown.copy();
        var swordBefore = sword.copy();
        var sentinelBefore = sentinel.copy();
        var f = fixture(h, MaidTask.ATTACK, unknown, 1.5);
        f.maid.getMaidInventory()
            .setInventorySlotContents(0, sword);
        f.maid.getMaidInventory()
            .setInventorySlotContents(2, sentinel);
        var target = target(h, f, 4.8, 4.5);
        wall(h, true);
        var watch = watch(h, f.maid, target);
        boolean[] exchanged = { false }, selectedCombat = { false }, opened = { false };
        int[] firstHitAge = { -1 };
        h.onEachTick("native enhanced root, sealed wall and complete real inventory references", () -> {
            ready(h, f);
            int age = f.maid.ticksExisted;
            trace("exchange", f, watch);
            h.assertTrue(
                count(f.maid, unknown) == 1 && count(f.maid, sword) == 1 && count(f.maid, sentinel) == 1,
                "Autonomous exchange lost, copied or aliased an original stack");
            h.assertTrue(
                ItemStack.areItemStacksEqual(unknown, unknownBefore)
                    && ItemStack.areItemStacksEqual(sentinel, sentinelBefore)
                    && sword.getItem() == swordBefore.getItem()
                    && sword.stackSize == swordBefore.stackSize
                    && ItemStack.areItemStackTagsEqual(sword, swordBefore),
                "Autonomous exchange changed full unknown/sentinel content or sword NBT/count");
            h.assertTrue(
                sword.getItemDamage() == watch.accepted.size(),
                "Vanilla sword wear differs from actual accepted autonomous hits");
            if (!opened[0]) {
                h.assertTrue(
                    target.getHealth() == TARGET_HEALTH && watch.accepted.isEmpty(),
                    "Native enhanced combat damaged a target across the sealed wall");
                if (age >= WALL_OPEN_AGE) {
                    wall(h, false);
                    opened[0] = true;
                }
            }
            if (f.maid.getHeldItem() == sword) {
                exchanged[0] = true;
                h.assertTrue(
                    f.maid.getMaidInventory()
                        .getStackInSlot(0) == unknown,
                    "Autonomous full-stack exchange did not retain the original unknown stack in its source slot");
            }
            selectedCombat[0] |= f.maid.getBrainCoordinator()
                .peekEnhancedCore()
                .selectedKind() == Kind.COMBAT;
            if (!watch.accepted.isEmpty() && firstHitAge[0] < 0) {
                h.assertTrue(
                    opened[0] && age >= WALL_OPEN_AGE && exchanged[0] && target.getHealth() < TARGET_HEALTH,
                    "Actual native damage did not follow the brain's supported weapon exchange and wall opening");
                firstHitAge[0] = age;
                System.out.println(
                    "TLMNH_UNIFIED209_AUTO_EXCHANGE age=" + age
                        + " hits="
                        + watch.accepted.size()
                        + " unknownRef=retained swordWear="
                        + sword.getItemDamage());
            }
        });
        h.succeedWhen(() -> firstHitAge[0] >= 0 && f.maid.ticksExisted >= firstHitAge[0] + 3 && selectedCombat[0]);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 280, batch = "unified_autonomous_2_native_jump")
    public static void actualBrainJumpWaitsForDescentAndPaysOneCriticalHit(GameTestHelper h) {
        var sword = new ItemStack(Items.iron_sword);
        var f = fixture(h, MaidTask.ATTACK, sword, 1.5);
        // 只设置合法实体属性，不指定动作；策略仍须自己选择跳劈并驱动原生JumpHelper
        f.maid.getEntityAttribute(MaidCombatAttributes.ATTACK_SPEED)
            .setBaseValue(1);
        var target = target(h, f, 3.2, 4.5);
        var watch = watch(h, f.maid, target);
        boolean[] jumpPlan = { false }, ascent = { false }, descent = { false }, critical = { false };
        int[] lastReported = { 0 };
        h.onEachTick("native jump trajectory, event damage and no ascending cooldown theft", () -> {
            ready(h, f);
            trace("jump", f, watch);
            var plan = f.maid.getBrainCoordinator()
                .peekEnhancedCore()
                .combat()
                .lastPlan();
            jumpPlan[0] |= plan != null && plan.macro == Macro.JUMP_STRIKE && plan.simulated;
            if (!f.maid.onGround && f.maid.motionY > .001) {
                ascent[0] = true;
                h.assertTrue(
                    f.maid.getCombatCadence()
                        .remainingTicks() == 0,
                    "An ascending ordinary attempt consumed the cadence before the descending strike");
            }
            descent[0] |= ascent[0] && !f.maid.onGround && f.maid.motionY < 0 && f.maid.fallDistance > 0;
            for (var attempt : watch.attempts) h.assertFalse(
                !attempt.onGround && attempt.motionY > 0,
                "Autonomous melee entered Pre during the rising jump phase");
            for (int index = lastReported[0]; index < watch.accepted.size(); index++) {
                var hit = watch.accepted.get(index);
                h.assertTrue(
                    hit.cooldownAtPre == 20 && hit.healthBefore > hit.healthAfter,
                    "Accepted native hit lacked the attack-speed cadence or an actual health reduction");
                if (!hit.onGround && hit.motionY < 0 && hit.fallDistance > 0) {
                    h.assertTrue(
                        ascent[0] && jumpPlan[0] && Math.abs(hit.hurtAmount - hit.physical * 1.5F) < EPSILON,
                        "Falling pose was not preceded by a native brain jump or did not produce one physical critical");
                    critical[0] = true;
                    System.out.println(
                        "TLMNH_UNIFIED209_AUTO_JUMP age=" + f.maid.ticksExisted
                            + " ground="
                            + hit.onGround
                            + " vy="
                            + hit.motionY
                            + " fall="
                            + hit.fallDistance
                            + " physical="
                            + hit.physical
                            + " requested="
                            + hit.hurtAmount
                            + " healthLoss="
                            + (hit.healthBefore - hit.healthAfter));
                }
            }
            lastReported[0] = watch.accepted.size();
            h.assertTrue(
                sword.getItemDamage() == watch.accepted.size(),
                "Autonomous jump accepted-hit count and real sword durability differ");
        });
        h.succeedWhen(() -> jumpPlan[0] && ascent[0] && descent[0] && critical[0]);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 300, batch = "unified_autonomous_3_moving_bow")
    public static void actualBrainDrawsAndHitsMovingTargetWithoutResettingSharedCadence(GameTestHelper h) {
        var bow = new ItemStack(Items.bow);
        var ammunition = new ItemStack(Items.arrow, ARROWS);
        var f = fixture(h, MaidTask.RANGED_ATTACK, bow, 5.5);
        f.maid.setOffhandItem(ammunition);
        f.maid.getEntityAttribute(MaidCombatAttributes.SHOOT_COOLDOWN)
            .setBaseValue(SHOT_COOLDOWN);
        var target = target(h, f, 14.5, 4.5);
        var watch = watch(h, f.maid, target);
        boolean[] drew = { false }, paused = { false }, resumed = { false };
        long[] pauseAt = { -1 }, resumeAt = { -1 };
        double[] previous = { target.posZ }, moved = { 0 }, direction = { 1 };
        h.onEachTick("real moving target, draw lease, native arrows and persistent shared cadence", () -> {
            ready(h, f);
            trace("bow", f, watch);
            moved[0] += Math.abs(target.posZ - previous[0]);
            previous[0] = target.posZ;
            if (target.posZ >= f.z + 5.8) direction[0] = -1;
            else if (target.posZ <= f.z + 3.2) direction[0] = 1;
            // 只给慢速目标输入速度，位移、碰撞、箭飞行与命中均由后续原生tick执行
            target.motionZ = direction[0] * .065;
            var use = f.maid.getItemUseState()
                .peek();
            drew[0] |= use != null && use.getKind() == MaidItemUseState.Kind.BOW
                && f.maid.getItemUseState()
                    .getUsedTicks() >= 5;
            var actor = f.maid.getBrainCoordinator()
                .peekEnhancedCore()
                .actor();
            h.assertTrue(
                watch.arrows.size() == actor.rangedBow()
                    .getShots() && f.maid.getHeldItem() == bow
                    && f.maid.getOffhandItem() == ammunition
                    && ammunition.stackSize == ARROWS - watch.arrows.size()
                    && bow.getItemDamage() == watch.arrows.size(),
                "Real autonomous arrow releases did not settle exactly one arrow and one bow wear each");
            for (int index = 0; index < watch.shotTicks.size(); index++) {
                h.assertTrue(
                    watch.shotCooldown.get(index) == SHOT_COOLDOWN,
                    "Arrow spawn occurred before shared cadence was committed");
                if (index > 0) h.assertTrue(
                    watch.shotTicks.get(index) - watch.shotTicks.get(index - 1) >= SHOT_COOLDOWN,
                    "Actual releases bypassed the shared entity cadence");
            }
            long now = f.maid.worldObj.getTotalWorldTime();
            if (!paused[0] && !watch.arrows.isEmpty()) {
                long remaining = f.maid.getCombatCadence()
                    .remainingTicks();
                h.assertTrue(remaining > 0, "First autonomous shot left no pending shared cadence");
                f.maid.setTask(MaidTask.IDLE);
                h.assertTrue(
                    f.maid.getCombatCadence()
                        .remainingTicks() == remaining,
                    "Real task cancellation reset the shared shot cadence");
                pauseAt[0] = now;
                resumeAt[0] = now + remaining;
                paused[0] = true;
            } else if (paused[0] && !resumed[0]) {
                h.assertTrue(
                    f.maid.getCombatCadence()
                        .remainingTicks() == Math.max(0, resumeAt[0] - now),
                    "Native idle ticks changed the committed world-tick cadence");
                if (now >= pauseAt[0] + 3) {
                    f.maid.setTask(MaidTask.ATTACK);
                    h.assertTrue(
                        f.maid.getCombatCadence()
                            .remainingTicks() == Math.max(0, resumeAt[0] - now),
                        "Actual return to combat reset the pending shared cadence");
                    resumed[0] = true;
                }
            }
        });
        h.succeedWhen(() -> {
            boolean done = resumed[0] && drew[0]
                && moved[0] > 1
                && watch.arrows.size() >= 2
                && watch.arrowHits > 0
                && target.getHealth() < TARGET_HEALTH;
            if (done) System.out.println(
                "TLMNH_UNIFIED209_AUTO_BOW shots=" + watch.shotTicks
                    + " movingDistance="
                    + moved[0]
                    + " arrowHits="
                    + watch.arrowHits
                    + " targetHp="
                    + target.getHealth());
            return done;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 560, batch = "unified_environment_routes")
    public static void nativeCoverExitHalfStepChannelAndGatePermissionUseActualRoot(GameTestHelper h) {
        var diagnosticScope = com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatDiagnosticScope
            .open(h.getWorld());
        h.afterTest(diagnosticScope::close);
        var cachedEnvironment = new CombatCachedDiagnostics.Environment();
        h.afterTest(() -> {
            try {
                cachedEnvironment.finish();
            } finally {
                diagnosticScope.close();
            }
        });
        boolean previousTrace = com.tianluoqaq.tlmnh.common.ai.enhanced.combat.plan.CombatPlanner.traceEnabled();
        com.tianluoqaq.tlmnh.common.ai.enhanced.combat.plan.CombatPlanner.setTraceEnabled(true);
        h.afterTest(
            () -> com.tianluoqaq.tlmnh.common.ai.enhanced.combat.plan.CombatPlanner.setTraceEnabled(previousTrace));
        var f = fixture(h, MaidTask.ATTACK, tagged(new ItemStack(Items.bow), "environment-bow"), 1.5);
        f.maid.setPosition(f.x + 7.5, f.y + 1, f.z + 2.5);
        f.maid.setOffhandItem(new ItemStack(Items.arrow, 32));
        var target = target(h, f, 10.5, 2.5);
        h.setBlock(10, 1, 2, Blocks.stone_slab);
        target.setPosition(f.x + 10.5, f.y + 1.5, f.z + 2.5);
        target.setCurrentItemOrArmor(0, new ItemStack(Items.bow));
        for (int y = 1; y <= 2; y++) h.setBlock(6, y, 2, Blocks.stone);
        h.setBlock(8, 1, 3, Blocks.stone);
        h.setBlock(8, 1, 4, Blocks.stone);
        var incoming = new ArrayList<EntityArrow>();
        h.afterTest(() -> { for (var arrow : incoming) arrow.setDead(); });
        int[] phase = { 0 }, at = { 0 }, traces = { 0 };
        var decisionTrace = new EnvironmentDecisionTrace();
        long[] opensBefore = { 0 };
        CombatSnapshot.Vec[] coverGoal = { null }, shelter = { null };
        boolean[] selectedCover = { false }, movedToCover = { false }, exited = { false }, nativeHalfStep = { false };
        boolean[] woodMoving = { false }, woodMovingUnknown = { false }, woodOpened = { false }, woodPassed = { false };
        TileEntity[] woodTile = { null };
        var woodOrder = new WoodSamplingOrder(h.getWorld(), woodTile);
        FMLCommonHandler.instance()
            .bus()
            .register(woodOrder);
        h.afterTest(
            () -> FMLCommonHandler.instance()
                .bus()
                .unregister(woodOrder));
        h.onEachTick("native cover, observed exit and permission-gated half-slab corridor", () -> {
            if (woodOrder.failure != null) throw woodOrder.failure;
            var brain = f.maid.getBrainCoordinator();
            var core = brain.peekEnhancedCore();
            h.assertTrue(
                f.maid.isEntityAlive() && core != null
                    && !brain.isSuspended()
                    && brain.peekPolicyRuntime()
                        .getProfile() == MaidBrainProfile.ENHANCED
                    && f.maid.getOwner() == f.owner
                    && f.maid.getHomeArea()
                        .activity() == Activity.WORK,
                "Environment scenario left its complete native ENHANCED root");
            int age = f.maid.ticksExisted;
            var plan = core.combat()
                .lastPlan();
            var current = new CombatSnapshot.Vec(f.maid.posX, f.maid.boundingBox.minY, f.maid.posZ);
            cachedEnvironment.take(
                h.getWorld(),
                core,
                plan,
                phase[0],
                age,
                core.perception()
                    .combatReadiness() == null ? (plan == null ? Long.MIN_VALUE : plan.observedAt)
                        : core.perception()
                            .combatReadiness().tick);
            if (traces[0] < 30 && (age < 5 || age % 20 == 0 || age == at[0] + 1)) {
                traces[0]++;
                System.out.println(
                    "TLMNH_COMBAT_ENVIRONMENT phase=" + phase[0]
                        + " root="
                        + core.selectedKind()
                        + " age="
                        + age
                        + " position="
                        + current.x
                        + ","
                        + current.y
                        + ","
                        + current.z
                        + " macro="
                        + (plan == null ? null : plan.macro)
                        + " simulated="
                        + (plan != null && plan.simulated)
                        + " routeNodes="
                        + (plan == null || plan.route == null ? 0 : plan.route.nodes.size())
                        + " exit="
                        + (plan != null && plan.route != null && plan.route.escape != null)
                        + " goal="
                        + (plan == null ? null
                            : plan.destination.x + "," + plan.destination.y + "," + plan.destination.z)
                        + " complete="
                        + (core.combat()
                            .lastSnapshot() != null
                            && core.combat()
                                .lastSnapshot().complete)
                        + " selectedCover="
                        + selectedCover[0]
                        + " coverGoal="
                        + vector(coverGoal[0])
                        + " gateType="
                        + Blocks.fence_gate.getClass()
                            .getName()
                        + " woodState="
                        + (woodTile[0] == null ? null : environmentDoorState(woodTile[0]))
                        + " woodUnknownObserved="
                        + woodMovingUnknown[0]
                        + " woodStart="
                        + woodOrder.startedAt
                        + "/"
                        + woodOrder.movingAtStart
                        + " runner="
                        + core.combat()
                            .lastResult());
            }
            var diagnosticReady = core.perception()
                .combatReadiness();
            long diagnosticTick = Math.max(
                diagnosticReady == null ? Long.MIN_VALUE : diagnosticReady.tick,
                plan == null ? Long.MIN_VALUE : plan.observedAt);
            String decisionSample = decisionTrace.take(
                phase[0],
                age,
                at[0],
                diagnosticTick,
                plan,
                core.combat()
                    .lastResult());
            if (decisionSample != null) {
                var snap = core.combat()
                    .lastSnapshot();
                var ready = diagnosticReady;
                var observed = snap == null ? null : snap.target(plan.targetId);
                StringBuilder detail = new StringBuilder();
                int candidateCount = 0;
                for (var choice : plan.trace.candidates) {
                    if (candidateCount++ >= MAX_ENV_CANDIDATES) break;
                    detail.append("[")
                        .append(choice.macro)
                        .append(" strategy=")
                        .append(choice.strategy)
                        .append(" progress=")
                        .append(choice.progressFeasible)
                        .append(" escape=")
                        .append(choice.escapeProved)
                        .append(" damageSource=")
                        .append(choice.damageSource)
                        .append(" B=")
                        .append(choice.singleSelf)
                        .append(" ownerRB=")
                        .append(choice.ownerRisk)
                        .append("/")
                        .append(choice.singleOwner)
                        .append(" slot=")
                        .append(choice.weaponSlot)
                        .append(" family=")
                        .append(choice.family)
                        .append("@")
                        .append(choice.destination.x)
                        .append(",")
                        .append(choice.destination.y)
                        .append(",")
                        .append(choice.destination.z)
                        .append(" u=")
                        .append(choice.utility)
                        .append(" d=")
                        .append(choice.damage)
                        .append(" r=")
                        .append(choice.risk)
                        .append(" eval=")
                        .append(choice.evaluated)
                        .append(" continuing=")
                        .append(choice.continuing)
                        .append(" retained=")
                        .append(choice.retained)
                        .append(" proofAt=")
                        .append(choice.route == null ? null : choice.route.provedAt)
                        .append(" route=")
                        .append(choice.route == null ? 0 : choice.route.nodes.size())
                        .append(" protection=")
                        .append(choice.route == null ? 0 : choice.route.protection)
                        .append(" fire=")
                        .append(choice.route != null && choice.route.firingLine)
                        .append("]");
                }
                System.out.println(
                    "TLMNH_COMBAT_ENVIRONMENT_DECISION phase=" + phase[0]
                        + " sample="
                        + decisionSample
                        + " phaseAfter="
                        + decisionTrace.phaseAfter
                        + " sampled="
                        + decisionTrace.total
                        + "/full="
                        + decisionTrace.totalFull
                        + " root="
                        + core.selectedKind()
                        + " age="
                        + age
                        + " tick="
                        + plan.observedAt
                        + " full="
                        + plan.trace.full
                        + " granted="
                        + plan.quota.full
                        + " simulated="
                        + plan.simulated
                        + " bodyComplete="
                        + (snap != null && snap.bodyComplete)
                        + " bodyService="
                        + core.combat()
                            .bodyServices()
                        + " serviceResult="
                        + core.combat()
                            .lastServiceResult()
                        + " riskScope="
                        + plan.riskScope
                        + " totalRisk="
                        + plan.diagnosticTotalRisk()
                        + " combatUtility="
                        + plan.diagnosticCombatUtility()
                        + " reobsComparison="
                        + plan.reobservationComparisonComplete
                        + " stage="
                        + plan.stageId
                        + "/"
                        + plan.stageState
                        + "/"
                        + plan.stagePhase
                        + "/"
                        + plan.strategy
                        + "/admitted="
                        + plan.normalProgressAdmitted
                        + "/comparison="
                        + plan.necessaryComparisonComplete
                        + "/firstNative="
                        + plan.firstNativeAcceptanceAt
                        + "/retry="
                        + plan.stageRetryAt
                        + "/reason="
                        + plan.stageReason
                        + " boundary="
                        + vector(plan.boundary)
                        + " nativeNode="
                        + (plan.movementNode == null ? null
                            : plan.movementNode.stage + "/"
                                + plan.movementNode.index
                                + "@"
                                + vector(plan.movementNode.foot))
                        + " nativeFact="
                        + (snap == null || snap.movementFact == null ? null
                            : snap.movementFact.outcome + "/tick="
                                + snap.movementFact.tick
                                + "/stage="
                                + snap.movementFact.node.stage
                                + "/node="
                                + snap.movementFact.node.index
                                + "/path="
                                + snap.movementFact.pathIdentity
                                + "/foot="
                                + vector(snap.movementFact.actualFoot)
                                + "/ground="
                                + snap.movementFact.grounded
                                + "/area="
                                + snap.movementFact.inArea
                                + "/tail="
                                + snap.movementFact.nativeTailMatches
                                + "/retry="
                                + snap.movementFact.retryAt
                                + "/reason="
                                + snap.movementFact.reason
                                + "/pathIndex="
                                + snap.movementFact.nativePathIndex
                                + "/length="
                                + snap.movementFact.nativePathLength
                                + "/nativeTail="
                                + vector(snap.movementFact.nativeTail))
                        + " proposalGoal="
                        + vector(plan.trace.proposalGoal)
                        + " proposalProvedAt="
                        + plan.trace.proposalProvedAt
                        + " intentEligible="
                        + plan.trace.intentEligible
                        + " nativeShots="
                        + core.combat()
                            .releases()
                        + " graph="
                        + plan.trace.terrainVisited
                        + "/"
                        + plan.trace.terrainWork
                        + " blocked="
                        + (ready == null ? null : ready.blockedMask)
                        + " denials="
                        + (ready == null ? null : java.util.Arrays.toString(ready.observationDenials))
                        + " gravityQueriesReuses="
                        + (ready == null ? null : ready.gravityQueries + "/" + ready.gravityReuses)
                        + " currentDenials="
                        + java.util.Arrays.toString(plan.quota.denials())
                        + " coverage="
                        + (ready == null ? null : ready.oldestCoverage + "/" + ready.coverageRound)
                        + " geometryUnknown="
                        + (ready == null ? null : ready.unknownGeometryCells + "/" + ready.budgetGeometryCells)
                        + " rays="
                        + (ready == null ? null
                            : ready.primaryRays + "/" + ready.backgroundRays + "/" + ready.secondaryRays)
                        + " used="
                        + java.util.Arrays.toString(plan.quota.counts())
                        + " grounded="
                        + (snap != null && snap.self.grounded)
                        + " ammunition="
                        + (snap == null ? null : snap.self.ammunition)
                        + " mainDurability="
                        + (snap == null || snap.weapon(-1) == null ? null
                            : snap.weapon(-1).profile.remainingDurability + "/"
                                + snap.weapon(-1).profile.durabilityCost
                                + "/infinity="
                                + snap.weapon(-1).profile.infinity)
                        + " geometryBox="
                        + (snap == null || snap.geometry == null ? null
                            : snap.geometry.x + ","
                                + snap.geometry.y
                                + ","
                                + snap.geometry.z
                                + "/"
                                + snap.geometry.sizeX
                                + ","
                                + snap.geometry.sizeY
                                + ","
                                + snap.geometry.sizeZ)
                        + " drawing="
                        + (snap == null ? null : snap.self.drawTicks)
                        + " self="
                        + (snap == null ? null
                            : snap.self.position.x + "," + snap.self.position.y + "," + snap.self.position.z)
                        + " target="
                        + (observed == null ? null
                            : observed.position.x + "," + observed.position.y + "," + observed.position.z)
                        + " visible="
                        + (observed != null && observed.visible)
                        + " observedAt="
                        + (observed == null ? null : observed.observedAt)
                        + " targetRanged="
                        + (observed != null && observed.ranged)
                        + " targetActual="
                        + target.posX
                        + ","
                        + target.posY
                        + ","
                        + target.posZ
                        + " selfHealth="
                        + (snap == null ? null : snap.self.health)
                        + " selectedCover="
                        + selectedCover[0]
                        + " coverGoal="
                        + vector(coverGoal[0])
                        + " arrows="
                        + (snap == null ? null : snap.projectiles.size())
                        + " geometry="
                        + (snap == null || snap.geometry == null ? null : snap.geometry.observedAt)
                        + " candidates="
                        + detail);
            }
            if (phase[0] == 0) {
                // 只注入真实在飞箭输入，运动/方块相交/伤害由后续原生tick决定
                if (age % 24 == 4 && incoming.size() < 8) {
                    var arrow = new EntityArrow(h.getWorld(), target.posX, target.posY + 1.2, target.posZ);
                    arrow.shootingEntity = target;
                    double dx = f.maid.posX - arrow.posX, dz = f.maid.posZ - arrow.posZ;
                    double length = Math.sqrt(dx * dx + dz * dz);
                    if (length < .1) arrow.setDead();
                    else {
                        arrow.motionX = dx / length * .4;
                        arrow.motionZ = dz / length * .4;
                        arrow.motionY = .06;
                        arrow.setDamage(1);
                        h.assertTrue(
                            h.getWorld()
                                .spawnEntityInWorld(arrow),
                            "Actual environmental threat arrow did not spawn");
                        incoming.add(arrow);
                    }
                }
                if (plan != null && plan.simulated && plan.macro == Macro.COVER_EDGE && plan.route != null) {
                    h.assertTrue(
                        plan.route.escape != null && plan.route.peek != null && plan.route.protection > .2,
                        "Selected cover lacked known exposure/peek/exit evidence");
                    selectedCover[0] = true;
                    coverGoal[0] = plan.destination;
                }
                if (selectedCover[0] && coverGoal[0] != null
                    && current.distance(coverGoal[0]) < .55
                    && current.distance(new CombatSnapshot.Vec(f.x + 7.5, f.y + 1, f.z + 2.5)) > .8) {
                    movedToCover[0] = true;
                    shelter[0] = current;
                    // 下一阶段只改变夹具输入，箭清理仅使用本场实际创建的引用
                    for (var arrow : incoming) arrow.setDead();
                    target.setPosition(f.x + 2.5, f.y + 1, f.z + 2.5);
                    f.maid.setOffhandItem(null);
                    f.maid.getMaidInventory()
                        .setInventorySlotContents(0, tagged(new ItemStack(Items.iron_sword), "environment-exit-sword"));
                    phase[0] = 1;
                    at[0] = age;
                }
                h.assertTrue(age < 180, "Native root never selected and physically reached a proven cover point");
            } else if (phase[0] == 1) {
                if (current.distance(shelter[0]) > .9 && plan != null
                    && plan.route != null
                    && plan.macro != Macro.COVER_EDGE
                    && f.maid.getHeldItem() != null
                    && f.maid.getHeldItem()
                        .getItem() == Items.iron_sword) {
                    exited[0] = true;
                    // 同一场第二种地形：半砖通道和原生栅栏门，不手工调用导航/动作执行器
                    f.maid.setTask(MaidTask.IDLE);
                    for (int x = 1; x < 20; x++)
                        for (int z = 1; z < 8; z++) for (int y = 1; y <= 4; y++) h.setBlock(x, y, z, Blocks.air);
                    for (int x = 2; x <= 10; x++) for (int y = 1; y <= 3; y++) {
                        h.setBlock(x, y, 3, Blocks.stone);
                        h.setBlock(x, y, 5, Blocks.stone);
                    }
                    h.setBlock(7, 1, 4, Blocks.stone_slab);
                    h.setBlock(8, 1, 4, Blocks.stone);
                    h.setBlock(8, 2, 4, Blocks.stone_slab);
                    h.getWorld()
                        .setBlock(f.x + 6, f.y + 1, f.z + 4, Blocks.fence_gate, 1, 2);
                    f.maid.setPosition(f.x + 3.5, f.y + 1, f.z + 4.5);
                    f.maid.motionX = f.maid.motionY = f.maid.motionZ = 0;
                    target.setPosition(f.x + 8.5, f.y + 2.5, f.z + 4.5);
                    target.motionX = target.motionY = target.motionZ = 0;
                    f.maid.getConfigManager()
                        .setOpenDoor(false);
                    f.maid.getConfigManager()
                        .setOpenFenceGate(false);
                    f.maid.setTask(MaidTask.ATTACK);
                    opensBefore[0] = core.actor()
                        .doorOpenRequests();
                    phase[0] = 2;
                    at[0] = age;
                }
                h.assertTrue(
                    age - at[0] < 120,
                    "Native root did not leave the shelter through a real route after changed inputs");
            } else if (phase[0] == 2) {
                h.assertTrue(
                    (h.getWorld()
                        .getBlockMetadata(f.x + 6, f.y + 1, f.z + 4) & 4) == 0
                        && f.maid.posX < f.x + 6.31
                        && core.actor()
                            .doorOpenRequests() == opensBefore[0],
                    "Disabled gate permission opened/crossed the actual barrier");
                if (age - at[0] >= 40) {
                    f.maid.getConfigManager()
                        .setOpenFenceGate(true);
                    phase[0] = 3;
                    at[0] = age;
                }
            } else if (phase[0] == 3) {
                nativeHalfStep[0] |= f.maid.posX > f.x + 7.1 && f.maid.boundingBox.minY > f.y + 1.4;
                if (f.maid.posX > f.x + 7.2 && nativeHalfStep[0]
                    && core.actor()
                        .doorOpenRequests() > opensBefore[0]) {
                    f.maid.setTask(MaidTask.IDLE);
                    h.setBlock(6, 1, 4, Blocks.air);
                    h.setBlock(6, 2, 4, Blocks.air);
                    h.getWorld()
                        .setBlock(f.x + 6, f.y + 1, f.z + 4, Blocks.wooden_door, 0, 2);
                    h.getWorld()
                        .setBlock(f.x + 6, f.y + 2, f.z + 4, Blocks.wooden_door, 8, 2);
                    // 自有目标自然站在高台上；门前仍有真实LOS，不把隐藏当前位置回传给AI
                    for (int y = 1; y <= 4; y++) h.setBlock(8, y, 4, Blocks.stone);
                    h.setBlock(8, 5, 4, Blocks.stone_slab);
                    target.setPosition(f.x + 8.5, f.y + 5.5, f.z + 4.5);
                    target.motionX = target.motionY = target.motionZ = 0;
                    f.maid.setPosition(f.x + 3.5, f.y + 1, f.z + 4.5);
                    f.maid.motionX = f.maid.motionY = f.maid.motionZ = 0;
                    f.maid.getConfigManager()
                        .setOpenDoor(false);
                    f.maid.getConfigManager()
                        .setOpenFenceGate(false);
                    var chunk = h.getWorld()
                        .getChunkFromChunkCoords((f.x + 6) >> 4, (f.z + 4) >> 4);
                    woodTile[0] = chunk.chunkTileEntityMap
                        .get(new ChunkPosition((f.x + 6) & 15, f.y + 1, (f.z + 4) & 15));
                    h.assertTrue(
                        woodTile[0] != null && woodTile[0].getClass()
                            .getName()
                            .equals("net.malisis.doors.door.tileentity.DoorTileEntity")
                            && Blocks.wooden_door.getClass()
                                .getName()
                                .equals("net.malisis.doors.door.block.Door"),
                        "Default installed animated wood door or native bottom TE was replaced/missing");
                    f.maid.setTask(MaidTask.ATTACK);
                    opensBefore[0] = core.actor()
                        .doorOpenRequests();
                    phase[0] = 4;
                    at[0] = age;
                }
                h.assertTrue(age - at[0] < 180, "Permitted native gate/half-slab route did not physically complete");
            } else if (phase[0] == 4) {
                h.assertTrue(
                    environmentDoorState(woodTile[0]).equals("CLOSED") && f.maid.posX < f.x + 6.31
                        && core.actor()
                            .doorOpenRequests() == opensBefore[0],
                    "Disabled wood-door permission opened/crossed the real installed barrier");
                if (age - at[0] >= 40) {
                    f.maid.getConfigManager()
                        .setOpenDoor(true);
                    phase[0] = 5;
                    at[0] = age;
                }
            } else if (phase[0] == 5) {
                String state = environmentDoorState(woodTile[0]);
                woodOpened[0] |= state.equals("OPENED");
                woodMoving[0] |= state.equals("OPENING") || state.equals("CLOSING") || woodOrder.movingAtStart;
                var cached = core.combat()
                    .lastSnapshot();
                long now = h.getWorld()
                    .getTotalWorldTime();
                // snapshot在doors.trigger之前；只用本次World START已moving的前置证据核新采样
                if (woodOrder.provesMovingBefore(cached, now)) {
                    h.assertTrue(
                        (cached.geometry.at(f.x + 6, f.y + 1, f.z + 4) & CombatSnapshot.Geometry.UNKNOWN) != 0
                            && (cached.geometry.at(f.x + 6, f.y + 2, f.z + 4) & CombatSnapshot.Geometry.UNKNOWN) != 0,
                        "Snapshot sampled after a witnessed moving door accepted known geometry");
                    woodMovingUnknown[0] = true;
                }
                if (f.maid.posX > f.x + 7.2 && f.maid.boundingBox.minY > f.y + 1.4
                    && woodOpened[0]
                    && core.actor()
                        .doorOpenRequests() > opensBefore[0]) {
                    woodPassed[0] = true;
                    phase[0] = 6;
                    System.out.println(
                        "TLMNH_COMBAT_ENVIRONMENT_PASS cover=" + selectedCover[0]
                            + " moved="
                            + movedToCover[0]
                            + " exit="
                            + exited[0]
                            + " halfStep="
                            + nativeHalfStep[0]
                            + " woodPassed="
                            + woodPassed[0]
                            + " stableOpenedSeen="
                            + woodOpened[0]
                            + " movingSeen="
                            + woodMoving[0]
                            + " movingUnknownObserved="
                            + woodMovingUnknown[0]
                            + " movingUnknownStatus="
                            + (woodMovingUnknown[0] ? "OBSERVED" : "NOT_EXERCISED"));
                }
                h.assertTrue(age - at[0] < 180, "Permitted actual default wood-door route did not physically complete");
            }
        });
        h.succeedWhen(
            () -> phase[0] == 6 && selectedCover[0]
                && movedToCover[0]
                && exited[0]
                && nativeHalfStep[0]
                && woodPassed[0]);
    }

    /** 只选择已有诊断缓存；各阶段独立保留配额，不读取World或改变场景状态 */
    private static final class EnvironmentDecisionTrace {

        private static final int[] FULL_LIMIT = { 2, 2, 1, 3, 1, 3 }, LIGHT_LIMIT = { 2, 1, 1, 4, 1, 3 };
        private final int[] full = new int[6], light = new int[6], routeNodes = new int[6];
        private final Macro[] macro = new Macro[6];
        private final CombatPlan.Strategy[] strategy = new CombatPlan.Strategy[6];
        private final CombatPlan.Result[] result = new CombatPlan.Result[6];
        private int phase = -1, total, totalFull;
        private long lastTick = Long.MIN_VALUE, phaseAfter = Long.MIN_VALUE, consideredAt = Long.MIN_VALUE;

        String take(int currentPhase, int age, int enteredAge, long cachedTick, CombatPlan plan,
            CombatPlan.Result currentResult) {
            if (currentPhase != phase) {
                phaseAfter = lastTick;
                phase = currentPhase;
            }
            lastTick = Math.max(lastTick, cachedTick);
            if (currentPhase < 0 || currentPhase >= FULL_LIMIT.length
                || plan == null
                || plan.trace == null
                || total >= MAX_ENV_DECISIONS
                || plan.observedAt <= phaseAfter
                || plan.observedAt == consideredAt) return null;
            consideredAt = plan.observedAt;
            int elapsed = age - enteredAge;
            if (!plan.trace.full) {
                int count = light[phase];
                int spacing = phase == 3 ? 40 : phase == 5 ? 60 : 20;
                if (count >= LIGHT_LIMIT[phase]
                    || count > 0 && strategy[phase] == plan.strategy && elapsed < count * spacing) return null;
                strategy[phase] = plan.strategy;
                light[phase]++;
                total++;
                return count == 0 ? "first_light" : "spaced_light";
            }
            int count = full[phase], nodes = plan.route == null ? 0 : plan.route.nodes.size();
            boolean changed = macro[phase] != plan.macro || strategy[phase] != plan.strategy
                || result[phase] != currentResult
                || routeNodes[phase] != nodes;
            if (count >= FULL_LIMIT[phase] || totalFull >= MAX_ENV_FULL) return null;
            if (count == 1 && !changed && elapsed < (phase == 3 || phase == 5 ? 60 : 20)) return null;
            if (count >= 2 && elapsed < 120) return null;
            full[phase]++;
            totalFull++;
            total++;
            macro[phase] = plan.macro;
            strategy[phase] = plan.strategy;
            result[phase] = currentResult;
            routeNodes[phase] = nodes;
            return count == 0 ? "first_full_after_phase" : changed ? "changed_full" : "spaced_full";
        }
    }

    private static String environmentDoorState(TileEntity tile) {
        try {
            Object state = tile.getClass()
                .getMethod("getState")
                .invoke(tile);
            return state instanceof Enum<?>value ? value.name() : "UNKNOWN";
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Actual installed door state API differs", failure);
        }
    }

    /** 单个自有木门的只读前置见证，不补采、不申请战斗配额、不改变门状态 */
    public static final class WoodSamplingOrder {

        private final World world;
        private final TileEntity[] tracked;
        private TileEntity witnessed;
        public long startedAt = Long.MIN_VALUE, endedAt = Long.MIN_VALUE;
        public boolean movingAtStart;
        public IllegalStateException failure;

        public WoodSamplingOrder(World world, TileEntity[] tracked) {
            this.world = world;
            this.tracked = tracked;
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void worldTick(TickEvent.WorldTickEvent event) {
            if (event.world != world) return;
            try {
                if (event.phase == TickEvent.Phase.START) {
                    startedAt = world.getTotalWorldTime();
                    endedAt = Long.MIN_VALUE;
                    witnessed = tracked[0];
                    movingAtStart = false;
                    if (sameLoadedTile(witnessed)) {
                        Object state = witnessed.getClass()
                            .getMethod("getState")
                            .invoke(witnessed);
                        movingAtStart = (Boolean) witnessed.getClass()
                            .getMethod("isMoving")
                            .invoke(witnessed) && state instanceof Enum<?>value
                            && (value.name()
                                .equals("OPENING")
                                || value.name()
                                    .equals("CLOSING"));
                    }
                } else endedAt = world.getTotalWorldTime();
            } catch (ReflectiveOperationException | RuntimeException error) {
                failure = new IllegalStateException("Native wood-door START witness failed", error);
            }
        }

        public boolean provesMovingBefore(CombatSnapshot snapshot, long now) {
            return movingAtStart && witnessed == tracked[0]
                && sameLoadedTile(witnessed)
                && endedAt == now
                && startedAt != Long.MIN_VALUE
                && endedAt > startedAt
                && endedAt - startedAt == 1
                && snapshot != null
                && snapshot.tick > startedAt
                && snapshot.tick <= endedAt
                && snapshot.geometry != null
                && snapshot.geometry.observedAt == snapshot.tick;
        }

        private boolean sameLoadedTile(TileEntity tile) {
            if (tile == null || tile.isInvalid()
                || tile.getWorldObj() != world
                || !world.getChunkProvider()
                    .chunkExists(tile.xCoord >> 4, tile.zCoord >> 4))
                return false;
            var chunk = world.getChunkFromChunkCoords(tile.xCoord >> 4, tile.zCoord >> 4);
            return chunk.chunkTileEntityMap.get(new ChunkPosition(tile.xCoord & 15, tile.yCoord, tile.zCoord & 15))
                == tile;
        }
    }

    private static ModernCoreTests.Fixture fixture(GameTestHelper h, MaidTask task, ItemStack main, double ownerX) {
        var f = new ModernCoreTests.Fixture(h, false, 16, 24, MaidBrainProfile.ENHANCED);
        f.maid.setSchedule(MaidSchedule.ALL);
        f.maid.setTask(task);
        f.maid.setCurrentItemOrArmor(0, main);
        f.owner.setPosition(f.x + ownerX, f.y + 1, f.z + 1.5);
        for (int x = 1; x < 20; x++) for (int z = 1; z < 8; z++) h.setBlock(x, 0, z, Blocks.stone);
        h.assertTrue(
            f.maid.getOwner() == f.owner && f.maid.getHomeArea()
                .activity() == Activity.WORK,
            "Autonomous fixture did not establish the native owner and WORK schedule");
        return f;
    }

    private static void ready(GameTestHelper h, ModernCoreTests.Fixture f) {
        var brain = f.maid.getBrainCoordinator();
        h.assertTrue(
            f.maid.isEntityAlive() && f.maid.getHealth() == 20
                && !brain.isSuspended()
                && brain.peekPolicyRuntime()
                    .isReady()
                && brain.peekPolicyRuntime()
                    .getProfile() == MaidBrainProfile.ENHANCED
                && brain.peekEnhancedCore() != null
                && brain.peekFaithfulBrain() == null
                && brain.getCombatController()
                    .getTicks() == 0
                && f.maid.getHomeArea()
                    .activity() == Activity.WORK,
            "Autonomous scenario faulted, suspended or used a different native brain root");
        h.assertFalse(
            f.maid.getCombatHitReceipt()
                .blocksTransfer(),
            "Autonomous scenario retained an unresolved receipt");
    }

    private static void trace(String scene, ModernCoreTests.Fixture f, HurtWatch watch) {
        int age = f.maid.ticksExisted;
        var core = f.maid.getBrainCoordinator()
            .peekEnhancedCore();
        var combat = core.combat();
        var plan = combat.lastPlan();
        var result = combat.lastResult();
        Macro macro = plan == null ? null : plan.macro;
        boolean changed = !watch.diagnosticStarted || macro != watch.diagnosticMacro
            || result != watch.diagnosticResult;
        if (!changed && age > 4 && age % 40 != 0 || watch.diagnosticFrames >= MAX_DIAGNOSTIC_FRAMES) return;
        watch.diagnosticStarted = true;
        watch.diagnosticMacro = macro;
        watch.diagnosticResult = result;
        watch.diagnosticFrames++;
        // 只读最近实际交给规划器的对象，不在诊断中补观测、提取能力或申请预算
        var snapshot = combat.lastSnapshot();
        var observed = snapshot == null ? null
            : snapshot.target(plan == null ? snapshot.selectedTarget : plan.targetId);
        var weapon = snapshot == null ? null : snapshot.weapon(plan == null ? -1 : plan.weaponSlot);
        System.out.println(
            "TLMNH_UNIFIED209_AUTO_TRACE scene=" + scene
                + " age="
                + age
                + " frame="
                + watch.diagnosticFrames
                + " changed="
                + changed
                + " worldTick="
                + f.maid.worldObj.getTotalWorldTime()
                + " root="
                + core.selectedKind()
                + " plan="
                + (macro == null ? "none" : macro)
                + " result="
                + (result == null ? "none" : result)
                + " jumpCheck="
                + core.actor()
                    .unifiedJumpDiagnostic()
                + " simulated="
                + (plan != null && plan.simulated)
                + " planTick="
                + (plan == null ? "none" : plan.observedAt)
                + " planExpires="
                + (plan == null ? "none" : plan.expiresAt)
                + " expectedDamage="
                + (plan == null ? "none" : plan.expectedDamage)
                + " expectedRisk="
                + (plan == null ? "none" : plan.diagnosticTotalRisk())
                + " utility="
                + (plan == null ? "none" : plan.diagnosticCombatUtility())
                + " snapshotTick="
                + (snapshot == null ? "none" : snapshot.tick)
                + " complete="
                + (snapshot != null && snapshot.complete)
                + " geometry="
                + (snapshot == null || snapshot.geometry == null ? "none"
                    : snapshot.geometry.x + ","
                        + snapshot.geometry.y
                        + ","
                        + snapshot.geometry.z
                        + "/"
                        + snapshot.geometry.sizeX
                        + ","
                        + snapshot.geometry.sizeY
                        + ","
                        + snapshot.geometry.sizeZ)
                + " geometryComplete="
                + (snapshot != null && snapshot.geometry != null && snapshot.geometry.complete)
                + " geometryFresh="
                + (snapshot != null && snapshot.geometry != null && snapshot.geometry.fresh(snapshot.tick))
                + " geometryTick="
                + (snapshot == null || snapshot.geometry == null ? "none" : snapshot.geometry.observedAt)
                + " selfHp="
                + (snapshot == null ? "none" : snapshot.self.health)
                + " ownerHp="
                + (snapshot == null ? "none" : snapshot.self.ownerHealth)
                + " ownerPos="
                + vector(snapshot == null ? null : snapshot.self.ownerPosition)
                + " selfPos="
                + vector(snapshot == null ? null : snapshot.self.position)
                + " selfVelocity="
                + vector(snapshot == null ? null : snapshot.self.velocity)
                + " reach="
                + (snapshot == null ? "none" : snapshot.self.reach)
                + " selfHalfWidth="
                + (snapshot == null ? "none" : snapshot.self.halfWidth)
                + " selfHeight="
                + (snapshot == null ? "none" : snapshot.self.height)
                + " grounded="
                + (snapshot != null && snapshot.self.grounded)
                + " jumpAllowed="
                + (snapshot != null && snapshot.self.jumpAllowed)
                + " stationary="
                + (snapshot != null && snapshot.self.stationary)
                + " equipped="
                + (snapshot != null && snapshot.self.equipped)
                + " cadenceReady="
                + (snapshot != null && snapshot.self.cadenceReady)
                + " cadenceRemaining="
                + (snapshot == null ? "none" : snapshot.self.cadenceRemainingTicks)
                + " attackDelay="
                + (snapshot == null ? "none" : snapshot.self.attackDelay)
                + " target="
                + (observed == null ? "none" : observed.id)
                + " targetHp="
                + (observed == null ? "none" : observed.health)
                + " targetPos="
                + vector(observed == null ? null : observed.position)
                + " targetVelocity="
                + vector(observed == null ? null : observed.velocity)
                + " targetHalfWidth="
                + (observed == null ? "none" : observed.halfWidth)
                + " targetHeight="
                + (observed == null ? "none" : observed.height)
                + " targetRisk="
                + (observed == null ? "none" : observed.estimatedDamage)
                + " targetVisible="
                + (observed != null && observed.visible)
                + " targetSwinging="
                + (observed != null && observed.swinging)
                + " targetRanged="
                + (observed != null && observed.ranged)
                + " targetExplosive="
                + (observed != null && observed.explosive)
                + " targetAttackingOwner="
                + (observed != null && observed.attackingOwner)
                + " lastOwnHit="
                + (observed == null ? "none" : observed.lastOwnHitAt)
                + " weaponSlot="
                + (weapon == null ? "none" : weapon.slot)
                + " weaponFamily="
                + (weapon == null ? "none" : weapon.profile.family)
                + " weaponPhysical="
                + (weapon == null ? "none" : weapon.profile.estimatedMeleePhysical)
                + " criticalMultiplier="
                + (weapon == null ? "none" : weapon.profile.criticalMultiplier)
                + " actualPhysical="
                + f.maid.getEntityAttribute(SharedMonsterAttributes.attackDamage)
                    .getAttributeValue()
                + " actualMaidPos="
                + f.maid.posX
                + ","
                + f.maid.posY
                + ","
                + f.maid.posZ
                + " actualMaidFootY="
                + f.maid.boundingBox.minY
                + " actualGround="
                + f.maid.onGround
                + " actualMotion="
                + f.maid.motionX
                + ","
                + f.maid.motionY
                + ","
                + f.maid.motionZ
                + " actualFall="
                + f.maid.fallDistance
                + " actualTargetPos="
                + watch.target.posX
                + ","
                + watch.target.posY
                + ","
                + watch.target.posZ
                + " actualTargetHp="
                + watch.target.getHealth()
                + " attempts="
                + watch.attempts.size()
                + " accepted="
                + watch.accepted.size()
                + " arrows="
                + watch.arrows.size()
                + " actualCooldown="
                + f.maid.getCombatCadence()
                    .remainingTicks());
    }

    private static String vector(CombatSnapshot.Vec value) {
        return value == null ? "none" : value.x + "," + value.y + "," + value.z;
    }

    private static EntityPig target(GameTestHelper h, ModernCoreTests.Fixture f, double x, double z) {
        var target = new EntityPig(h.getWorld());
        target.getEntityAttribute(SharedMonsterAttributes.maxHealth)
            .setBaseValue(TARGET_HEALTH);
        target.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
            .setBaseValue(0);
        target.getEntityAttribute(SharedMonsterAttributes.knockbackResistance)
            .setBaseValue(1);
        target.setHealth(TARGET_HEALTH);
        h.spawnEntity(target, x, 1, z);
        f.maid.getAttackListData()
            .attackGroups()
            .put(MaidCombatEntities.id(target), MonsterType.HOSTILE);
        h.afterTest(target::setDead);
        return target;
    }

    private static HurtWatch watch(GameTestHelper h, EntityMaid maid, EntityPig target) {
        var watch = new HurtWatch(maid, target);
        MinecraftForge.EVENT_BUS.register(watch);
        h.afterTest(() -> {
            MinecraftForge.EVENT_BUS.unregister(watch);
            for (var arrow : watch.arrows) arrow.setDead();
        });
        return watch;
    }

    private static void wall(GameTestHelper h, boolean solid) {
        for (int y = 1; y < 10; y++) for (int z = 1; z < 8; z++) h.setBlock(3, y, z, solid ? Blocks.stone : Blocks.air);
    }

    private static int count(EntityMaid maid, ItemStack expected) {
        int found = 0;
        for (int slot = 0; slot < MaidInventory.TOTAL_SIZE; slot++) if (maid.getMaidInventory()
            .getStackInSlot(slot) == expected) found++;
        return found;
    }

    private static ItemStack tagged(ItemStack item, String identity) {
        var nested = new NBTTagCompound();
        nested.setString("Identity", identity);
        nested.setIntArray("Payload", new int[] { 209, 7, 34, 36 });
        var tag = new NBTTagCompound();
        tag.setTag("QAUnifiedAutonomous", nested);
        item.setTagCompound(tag);
        return item;
    }

    private static final class Attempt {

        final boolean onGround;
        final double motionY;
        final float fallDistance, physical, healthBefore;
        final long cooldownAtPre;
        float hurtAmount = Float.NaN, healthAfter;

        Attempt(EntityMaid maid, EntityPig target) {
            onGround = maid.onGround;
            motionY = maid.motionY;
            fallDistance = maid.fallDistance;
            physical = (float) maid.getEntityAttribute(SharedMonsterAttributes.attackDamage)
                .getAttributeValue();
            healthBefore = target.getHealth();
            cooldownAtPre = maid.getCombatCadence()
                .remainingTicks();
        }
    }

    public static final class HurtWatch {

        private final EntityMaid maid;
        private final EntityPig target;
        private boolean diagnosticStarted;
        private int diagnosticFrames;
        private Macro diagnosticMacro;
        private CombatPlan.Result diagnosticResult;
        final List<Attempt> attempts = new ArrayList<>(), accepted = new ArrayList<>();
        final List<EntityArrow> arrows = new ArrayList<>();
        final List<Long> shotTicks = new ArrayList<>(), shotCooldown = new ArrayList<>();
        Attempt pending;
        int arrowHits;

        HurtWatch(EntityMaid maid, EntityPig target) {
            this.maid = maid;
            this.target = target;
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void pre(MaidHurtTarget.Pre event) {
            if (event.getMaid() != maid || event.getTarget() != target) return;
            pending = new Attempt(maid, target);
            attempts.add(pending);
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void hurt(LivingHurtEvent event) {
            if (event.entityLiving != target || event.source.getEntity() != maid) return;
            if (event.source.getSourceOfDamage() instanceof EntityArrow actual) {
                for (var arrow : arrows) if (arrow == actual) {
                    arrowHits++;
                    break;
                }
            } else if (pending != null) pending.hurtAmount = event.ammount;
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void post(MaidHurtTarget.Post event) {
            if (event.getMaid() != maid || event.getTarget() != target || !event.isHurt() || pending == null) return;
            pending.healthAfter = target.getHealth();
            accepted.add(pending);
            pending = null;
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void spawn(EntityJoinWorldEvent event) {
            if (!(event.entity instanceof EntityArrow arrow) || arrow.shootingEntity != maid) return;
            arrows.add(arrow);
            shotTicks.add(maid.worldObj.getTotalWorldTime());
            shotCooldown.add(
                maid.getCombatCadence()
                    .remainingTicks());
        }
    }
}
