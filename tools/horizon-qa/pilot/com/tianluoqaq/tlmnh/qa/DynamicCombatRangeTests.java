package com.tianluoqaq.tlmnh.qa;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.misc.MonsterType;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatSnapshot;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatEntities;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;

/** Actual complete-root acquisition, retention, release and reacquisition boundaries. */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class DynamicCombatRangeTests {

    private DynamicCombatRangeTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 240, batch = "dynamic_combat_range")
    public static void nativeOwnerAnchoredAcquisitionRetentionAndRelease(GameTestHelper helper) {
        var scenario = new Scenario(helper);
        helper.afterTest(scenario::close);
        scenario.setup();
        helper.onEachTick("native dynamic combat range lifecycle", scenario::tick);
        helper.succeedWhen(() -> scenario.phase == Phase.DONE);
    }

    private enum Phase {
        ACQUIRE,
        RETAIN,
        RELEASE,
        REJECT_REACQUIRE,
        REACQUIRE,
        DONE
    }

    private static final class Scenario {

        private final GameTestHelper helper;
        private final List<int[]> extraFloor = new ArrayList<>();
        private ModernCoreTests.Fixture fixture;
        private EntityPig target;
        private Phase phase = Phase.ACQUIRE;
        private int age, phaseStarted;
        private double ownerX, laneZ;

        private Scenario(GameTestHelper helper) {
            this.helper = helper;
        }

        private void setup() {
            fixture = new ModernCoreTests.Fixture(helper, false, 16, 8, MaidBrainProfile.ENHANCED);
            fixture.maid.setSchedule(MaidSchedule.ALL);
            fixture.maid.setTask(MaidTask.ATTACK);
            fixture.maid.setCurrentItemOrArmor(0, new ItemStack(Items.iron_sword));
            ownerX = fixture.x + 6.5;
            laneZ = fixture.z + 4.5;
            fixture.owner.setPosition(ownerX, fixture.y + 1, laneZ);
            fixture.maid.setPosition(fixture.x + 8.5, fixture.y + 1, laneZ);
            // Open only our fixture wall, then add an owned extension in the fresh void world.
            for (int y = 1; y < 10; y++) for (int z = 1; z < 8; z++)
                helper.setBlock(20, y, z, Blocks.air);
            for (int x = 21; x <= 34; x++) for (int z = 1; z < 8; z++) {
                int absoluteX = fixture.x + x, absoluteZ = fixture.z + z;
                helper.assertTrue(helper.getWorld().isAirBlock(absoluteX, fixture.y, absoluteZ),
                    "Fresh extension floor contains an unowned block");
                extraFloor.add(new int[] { absoluteX, fixture.y, absoluteZ });
                helper.getWorld().setBlock(absoluteX, fixture.y, absoluteZ, Blocks.stone, 0, 2);
            }
            target = new EntityPig(helper.getWorld());
            target.func_110163_bv();
            target.getEntityAttribute(SharedMonsterAttributes.maxHealth).setBaseValue(1000);
            target.getEntityAttribute(SharedMonsterAttributes.movementSpeed).setBaseValue(0);
            target.setHealth(1000);
            positionTarget(19.68);
            helper.assertTrue(helper.getWorld().spawnEntityInWorld(target)
                && helper.getWorld().getEntityByID(target.getEntityId()) == target,
                "Native persistent range target did not register");
            fixture.maid.getAttackListData().attackGroups()
                .put(MaidCombatEntities.id(target), MonsterType.HOSTILE);
            log("SETUP");
        }

        private void tick() {
            age++;
            var brain = fixture.maid.getBrainCoordinator();
            var core = brain.peekEnhancedCore();
            helper.assertTrue(core != null && !brain.isSuspended()
                && brain.peekPolicyRuntime().isReady()
                && brain.peekPolicyRuntime().getProfile() == MaidBrainProfile.ENHANCED
                && brain.peekFaithfulBrain() == null && fixture.maid.getTask() == MaidTask.ATTACK
                && fixture.maid.getOwner() == fixture.owner && fixture.maid.isEntityAlive()
                && fixture.owner.isEntityAlive() && target.isEntityAlive()
                && helper.getWorld().getEntityByID(target.getEntityId()) == target,
                "Dynamic range test left its native complete-root identities");
            boolean selected = core.combat().target() == target
                && fixture.maid.getModernCombatMemory().target() == target;
            CombatSnapshot snapshot = core.combat().lastSnapshot();
            CombatSnapshot.Target observed = snapshot == null ? null : snapshot.target(target.getUniqueID());
            boolean current = snapshot != null && snapshot.tick == helper.getWorld().getTotalWorldTime()
                && observed != null && observed.visible && observed.observedAt == snapshot.tick;
            int phaseAge = age - phaseStarted;
            switch (phase) {
                case ACQUIRE -> {
                    helper.assertTrue(phaseAge <= 80, "Native 19.68-block acquisition never completed");
                    if (selected && current && observed.position.x == ownerX + 19.68) {
                        advance(Phase.RETAIN);
                        positionTarget(22.5);
                    }
                }
                case RETAIN -> {
                    helper.assertTrue(phaseAge <= 40, "Selected target was not retained at 22.5 blocks");
                    if (selected && current && observed.position.x == ownerX + 22.5) {
                        advance(Phase.RELEASE);
                        positionTarget(24.5);
                    }
                }
                case RELEASE -> {
                    helper.assertTrue(phaseAge <= 16, "Target beyond retention range was not released");
                    if (!selected && core.combat().target() != target
                        && fixture.maid.getModernCombatMemory().target() != target) {
                        advance(Phase.REJECT_REACQUIRE);
                        positionTarget(22.5);
                    }
                }
                case REJECT_REACQUIRE -> {
                    helper.assertTrue(core.combat().target() != target
                        && fixture.maid.getModernCombatMemory().target() != target,
                        "Cancelled target reused retention privilege for reacquisition");
                    if (phaseAge >= 20) {
                        advance(Phase.REACQUIRE);
                        positionTarget(19.68);
                    }
                }
                case REACQUIRE -> {
                    helper.assertTrue(phaseAge <= 80, "Target did not reacquire after returning to acquisition range");
                    if (selected && current && observed.position.x == ownerX + 19.68) advance(Phase.DONE);
                }
                case DONE -> { }
            }
            if (age % 20 == 0) log("FRAME");
            if (fixture.owner.isEntityAlive()) fixture.owner.onUpdateEntity();
        }

        private void positionTarget(double ownerDistance) {
            target.setPosition(ownerX + ownerDistance, fixture.y + 1, laneZ);
            target.motionX = target.motionY = target.motionZ = 0;
        }

        private void advance(Phase next) {
            log("PASS_" + phase);
            phase = next;
            phaseStarted = age;
        }

        private void log(String event) {
            System.out.println("TLMNH_DYNAMIC_RANGE event=" + event + " phase=" + phase + " actorTick=" + age
                + " worldTick=" + helper.getWorld().getTotalWorldTime() + " ownerX=" + ownerX
                + " maidX=" + fixture.maid.posX + " targetX=" + target.posX
                + " targetUuid=" + target.getUniqueID());
        }

        private void close() {
            if (target != null) {
                target.setDead();
                if (helper.getWorld().getEntityByID(target.getEntityId()) == target)
                    helper.getWorld().removePlayerEntityDangerously(target);
            }
            for (int[] point : extraFloor) {
                helper.assertTrue(helper.getWorld().getBlock(point[0], point[1], point[2]) == Blocks.stone,
                    "Owned extension block identity changed during the range test");
                helper.getWorld().setBlockToAir(point[0], point[1], point[2]);
            }
            System.out.println("TLMNH_DYNAMIC_RANGE cleanup=true phase=" + phase + " ticks=" + age);
        }
    }
}
