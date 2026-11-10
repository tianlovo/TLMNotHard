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
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatSnapshot;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatEntities;
import com.tianluoqaq.tlmnh.common.config.MaidPerceptionConfig;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;

/** Real Enhanced observation, without manual brain ticks or fabricated snapshots. */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class CombatFriendDetectionTests {

    private CombatFriendDetectionTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 240, batch = "combat_friend_detection")
    public static void nativeDistantFriendOcclusionAndRecovery(GameTestHelper helper) {
        var scenario = new Scenario(helper);
        helper.afterTest(scenario::close);
        scenario.setup();
        helper.onEachTick("native distant friend observation", scenario::tick);
        helper.succeedWhen(() -> scenario.phase == Phase.DONE);
    }

    private enum Phase {
        VISIBLE,
        HIDDEN,
        RECOVER,
        DONE
    }

    private static final class Scenario {

        final GameTestHelper helper;
        final List<int[]> floor = new ArrayList<>(), wall = new ArrayList<>(), installedWall = new ArrayList<>();
        ModernCoreTests.Fixture f;
        EntityMaid friend;
        EntityPig enemy;
        Phase phase = Phase.VISIBLE;
        int age, phaseStarted;
        long lastObserved;
        double lastX, lastY, lastZ;

        Scenario(GameTestHelper helper) {
            this.helper = helper;
        }

        void setup() {
            f = new ModernCoreTests.Fixture(helper, false, 16, 8, MaidBrainProfile.ENHANCED);
            int previousBowRange = MaidPerceptionConfig.bowRange;
            helper.afterTest(() -> MaidPerceptionConfig.bowRange = previousBowRange);
            MaidPerceptionConfig.bowRange = 48;
            f.maid.setSchedule(MaidSchedule.ALL);
            f.maid.setTask(MaidTask.ATTACK);
            f.maid.setSitting(true);
            f.maid.setPosition(f.x + 8.5, f.y + 1, f.z + 4.5);
            f.owner.setPosition(f.x + 6.5, f.y + 1, f.z + 4.5);
            f.maid.setCurrentItemOrArmor(0, new ItemStack(Items.bow));
            f.maid.getMaidInventory()
                .setInventorySlotContents(0, new ItemStack(Items.arrow, 24));
            for (int x = 21; x <= 39; x++) for (int z = 2; z <= 6; z++) {
                int ax = f.x + x, az = f.z + z;
                helper.assertTrue(
                    helper.getWorld()
                        .isAirBlock(ax, f.y, az),
                    "Unowned floor is not empty");
            }
            for (int y = 1; y <= 4; y++) for (int z = 2; z <= 6; z++) {
                int ax = f.x + 23, ay = f.y + y, az = f.z + z;
                helper.assertTrue(
                    helper.getWorld()
                        .isAirBlock(ax, ay, az),
                    "Unowned wall position is not empty");
                wall.add(new int[] { ax, ay, az });
            }
            for (int y = 1; y < 10; y++) for (int z = 1; z < 8; z++) helper.setBlock(20, y, z, Blocks.air);
            for (int x = 21; x <= 39; x++) for (int z = 2; z <= 6; z++) {
                int[] p = { f.x + x, f.y, f.z + z };
                helper.assertTrue(
                    helper.getWorld()
                        .setBlock(p[0], p[1], p[2], Blocks.stone, 0, 2),
                    "Could not install owned floor");
                floor.add(p);
            }
            enemy = new EntityPig(helper.getWorld());
            enemy.func_110163_bv();
            enemy.getEntityAttribute(SharedMonsterAttributes.maxHealth)
                .setBaseValue(1000);
            enemy.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
                .setBaseValue(0);
            enemy.getEntityAttribute(SharedMonsterAttributes.knockbackResistance)
                .setBaseValue(1);
            enemy.setHealth(1000);
            enemy.setPosition(f.x + 14.5, f.y + 1, f.z + 4.5);
            helper.assertTrue(
                helper.getWorld()
                    .spawnEntityInWorld(enemy)
                    && helper.getWorld()
                        .getEntityByID(enemy.getEntityId()) == enemy,
                "Enemy registration failed");
            f.maid.getAttackListData()
                .attackGroups()
                .put(MaidCombatEntities.id(enemy), MonsterType.HOSTILE);
            friend = new EntityMaid(helper.getWorld());
            helper.assertTrue(friend.bindTo(f.owner.getUniqueID()), "Friend binding failed");
            friend.setPickup(false);
            friend.setSitting(true);
            friend.setPosition(f.x + 36.5, f.y + 1, f.z + 4.5);
            helper.assertTrue(
                helper.getWorld()
                    .spawnEntityInWorld(friend)
                    && helper.getWorld()
                        .getEntityByID(friend.getEntityId()) == friend,
                "Friend registration failed");
        }

        void tick() {
            age++;
            var brain = f.maid.getBrainCoordinator();
            var core = brain.peekEnhancedCore();
            helper.assertTrue(
                core != null && brain.peekPolicyRuntime()
                    .isReady()
                    && !brain.isSuspended()
                    && brain.peekPolicyRuntime()
                        .getProfile() == MaidBrainProfile.ENHANCED
                    && f.maid.getOwner() == f.owner
                    && f.maid.isEntityAlive()
                    && f.owner.isEntityAlive()
                    && friend.isEntityAlive()
                    && enemy.isEntityAlive()
                    && helper.getWorld()
                        .getEntityByID(friend.getEntityId()) == friend
                    && helper.getWorld()
                        .getEntityByID(enemy.getEntityId()) == enemy,
                "Native friend observation identities invalid");
            CombatSnapshot snapshot = core.combat()
                .lastSnapshot();
            CombatSnapshot.Friend found = null;
            if (snapshot != null && snapshot.tick == helper.getWorld()
                .getTotalWorldTime()) {
                for (var item : snapshot.friends) if (friend.getUniqueID()
                    .equals(item.id)) found = item;
            } else snapshot = null;
            int elapsed = age - phaseStarted;
            switch (phase) {
                case VISIBLE -> {
                    helper.assertTrue(elapsed <= 100, "Visible distant friend was not captured completely");
                    if (snapshot != null && found != null
                        && found.current
                        && found.observedAt == snapshot.tick
                        && snapshot.friendsComplete) {
                        double d = Math.sqrt(f.maid.getDistanceSqToEntity(friend));
                        helper.assertTrue(d >= 25 && d <= 32, "Friend outside intended distant interval");
                        lastObserved = found.observedAt;
                        lastX = found.position.x;
                        lastY = found.position.y;
                        lastZ = found.position.z;
                        for (int[] p : wall) {
                            helper.assertTrue(
                                helper.getWorld()
                                    .isAirBlock(p[0], p[1], p[2]),
                                "Wall position changed");
                            helper.assertTrue(
                                helper.getWorld()
                                    .setBlock(p[0], p[1], p[2], Blocks.stone, 0, 2),
                                "Could not install owned wall");
                            installedWall.add(p);
                        }
                        friend.setPosition(f.x + 38, f.y + 1, f.z + 4.5);
                        advance(Phase.HIDDEN);
                    }
                }
                case HIDDEN -> {
                    if (elapsed >= 3) {
                        helper.assertTrue(snapshot != null, "Hidden friend has no current snapshot at 3 ticks");
                        helper.assertTrue(
                            found != null && !found.current
                                && found.observedAt == lastObserved
                                && found.position.x == lastX
                                && found.position.y == lastY
                                && found.position.z == lastZ
                                && !snapshot.friendsComplete,
                            "Hidden friend lost its known position or appeared complete");
                        for (int[] p : installedWall) {
                            helper.assertTrue(
                                helper.getWorld()
                                    .getBlock(p[0], p[1], p[2]) == Blocks.stone,
                                "Owned wall changed");
                            helper.getWorld()
                                .setBlockToAir(p[0], p[1], p[2]);
                        }
                        installedWall.clear();
                        advance(Phase.RECOVER);
                    }
                }
                case RECOVER -> {
                    helper.assertTrue(elapsed <= 80, "Visible friend did not recover");
                    if (snapshot != null && found != null
                        && found.current
                        && found.observedAt > lastObserved
                        && found.position.x == f.x + 38
                        && snapshot.friendsComplete) advance(Phase.DONE);
                }
                case DONE -> {}
            }
            if (f.owner.isEntityAlive()) f.owner.onUpdateEntity();
        }

        void advance(Phase next) {
            System.out.println("TLMNH_FRIEND_DETECTION pass=" + phase + " tick=" + age);
            phase = next;
            phaseStarted = age;
        }

        void close() {
            if (friend != null) {
                friend.setDead();
                if (helper.getWorld()
                    .getEntityByID(friend.getEntityId()) == friend)
                    helper.getWorld()
                        .removePlayerEntityDangerously(friend);
            }
            if (enemy != null) {
                enemy.setDead();
                if (helper.getWorld()
                    .getEntityByID(enemy.getEntityId()) == enemy)
                    helper.getWorld()
                        .removePlayerEntityDangerously(enemy);
            }
            for (int[] p : installedWall) {
                helper.assertTrue(
                    helper.getWorld()
                        .getBlock(p[0], p[1], p[2]) == Blocks.stone,
                    "Owned wall changed during friend test");
                helper.getWorld()
                    .setBlockToAir(p[0], p[1], p[2]);
            }
            for (int[] p : floor) {
                helper.assertTrue(
                    helper.getWorld()
                        .getBlock(p[0], p[1], p[2]) == Blocks.stone,
                    "Owned floor changed during friend test");
                helper.getWorld()
                    .setBlockToAir(p[0], p[1], p[2]);
            }
            System.out.println("TLMNH_FRIEND_DETECTION cleanup=true phase=" + phase + " ticks=" + age);
        }
    }
}
