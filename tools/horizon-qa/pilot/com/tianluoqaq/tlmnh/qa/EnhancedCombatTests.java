package com.tianluoqaq.tlmnh.qa;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.DamageSource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidHurtTarget;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.misc.MonsterType;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.api.event.MaidAttackTargetEvent;
import com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedCoreBrain;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidCombatActor;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Kind;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.combat.MaidBowAttack;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatEntities;
import com.tianluoqaq.tlmnh.common.combat.MaidItemUseState;
import com.tianluoqaq.tlmnh.common.combat.shield.MaidShieldController;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class EnhancedCombatTests {

    private EnhancedCombatTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 180, batch = "enhanced_combat_meal_shield")
    public static void actualHurtPreemptsOwnedMealMeleePaysWearShieldBlocksAndTaskChangeReleases(
        GameTestHelper helper) {
        var f = fixture(helper, MaidTask.ATTACK);
        var core = select(helper, f);
        var shield = shield(helper);
        f.maid.setOffhandItem(shield);
        f.maid.setHealth(10);
        var food = new ItemStack(Items.golden_apple, 2, 1);
        f.maid.getMaidInventory()
            .setInventorySlotContents(0, food.copy());
        EntityPig[] enemy = { null };
        EntityArrow[] arrow = { null };
        int[] phase = { 0 }, hurtAt = { -1 };
        float[] beforeArrow = { -1 };
        helper.succeedWhen(() -> {
            ready(helper, f, core);
            int age = f.maid.ticksExisted;
            if (age <= 5 || age % 20 == 0) System.out.println(
                "TLMNH_N_MELEE_TRACE age=" + age
                    + " phase="
                    + phase[0]
                    + " kind="
                    + core.selectedKind()
                    + " meal="
                    + f.maid.getMealController()
                        .isEating()
                    + " hp="
                    + f.maid.getHealth()
                    + " attempts="
                    + core.actor()
                        .meleeAttempts()
                    + " accepted="
                    + core.actor()
                        .meleeNativeAccepted()
                    + " target="
                    + (core.combat()
                        .target() == null ? "null"
                            : core.combat()
                                .target()
                                .getEntityId())
                    + " enemyHp="
                    + (enemy[0] == null ? -1 : enemy[0].getHealth())
                    + " shield="
                    + core.actor()
                        .shieldBehaviorRunning()
                    + " use="
                    + f.maid.getItemUseState()
                        .peek()
                    + " wear="
                    + f.maid.getHeldItem()
                        .getItemDamage());
            if (phase[0] == 0) {
                if (age < 4 || !f.maid.getMealController()
                    .isEating()) return false;
                enemy[0] = pig(helper, f, 1.5, 6.5);
                helper.assertTrue(
                    f.maid.attackEntityFrom(DamageSource.causeMobDamage(enemy[0]), 1),
                    "Real hurt input failed");
                hurtAt[0] = age;
                phase[0] = 1;
                return false;
            }
            if (phase[0] == 1) {
                if (core.actor()
                    .meleeNativeAccepted() == 0) return false;
                helper.assertTrue(
                    core.selectedKind() == Kind.MELEE && !f.maid.getMealController()
                        .isEating()
                        && ItemStack.areItemStacksEqual(
                            food,
                            f.maid.getMaidInventory()
                                .getStackInSlot(0))
                        && f.maid.getOffhandItem() == shield
                        && enemy[0].getHealth() < 200
                        && f.maid.getHeldItem()
                            .getItemDamage() > 0,
                    "Melee failed to preempt/restore meal, hurt actual target or pay finite wear");
                phase[0] = 2;
            }
            if (phase[0] == 2 && age >= hurtAt[0] + 22
                && core.actor()
                    .shieldBehaviorRunning()) {
                beforeArrow[0] = f.maid.getHealth();
                // 从当前实际敌人方向射入，避免固定+Z射点越出狭小测试格或偏离已移动的女仆
                double dx = enemy[0].posX - f.maid.posX, dz = enemy[0].posZ - f.maid.posZ;
                double distance = Math.sqrt(dx * dx + dz * dz);
                helper.assertTrue(distance > .01, "Incoming projectile direction is degenerate");
                dx /= distance;
                dz /= distance;
                arrow[0] = new EntityArrow(
                    helper.getWorld(),
                    f.maid.posX + dx * 1.4,
                    f.maid.posY + .9,
                    f.maid.posZ + dz * 1.4);
                arrow[0].shootingEntity = enemy[0];
                arrow[0].motionX = -dx * .6;
                arrow[0].motionZ = -dz * .6;
                arrow[0].setDamage(4);
                helper.assertTrue(
                    helper.getWorld()
                        .spawnEntityInWorld(arrow[0]),
                    "Actual incoming arrow did not spawn");
                helper.afterTest(arrow[0]::setDead);
                phase[0] = 3;
                return false;
            }
            if (phase[0] != 3) return false;
            var block = f.maid.getShieldController()
                .getLastBlock();
            if (age % 20 == 0) System.out.println(
                "TLMNH_N_SHIELD_TRACE age=" + age
                    + " outcome="
                    + (block == null ? "null" : block.outcome)
                    + " arrowDead="
                    + arrow[0].isDead
                    + " arrow="
                    + arrow[0].posX
                    + ","
                    + arrow[0].posY
                    + ","
                    + arrow[0].posZ
                    + " maid="
                    + f.maid.posX
                    + ","
                    + f.maid.posY
                    + ","
                    + f.maid.posZ
                    + " enemy="
                    + enemy[0].posX
                    + ","
                    + enemy[0].posZ
                    + " yaw="
                    + f.maid.rotationYaw);
            if (block == null || block.outcome != MaidShieldController.Outcome.BLOCKED) return false;
            helper.assertTrue(
                arrow[0].isDead && shield.getItemDamage() > 0 && f.maid.getHealth() == beforeArrow[0],
                "Real shield block skipped projectile, health or durability evidence");
            var old = core.actor()
                .attackTarget();
            f.maid.setTask(MaidTask.IDLE);
            helper.assertTrue(
                core.combat()
                    .target() == null
                    && f.maid.getModernCombatMemory()
                        .target() == null
                    && f.maid.getItemUseState()
                        .peek() == null
                    && !f.maid.getShieldController()
                        .getSnapshot().activeTaskRunning,
                "Task boundary retained combat target, item lease or shield");
            boolean refused = false;
            try {
                core.actor()
                    .hurtTarget(old);
            } catch (IllegalArgumentException | IllegalStateException expected) {
                refused = true;
            }
            helper.assertTrue(refused, "Old melee capability survived task boundary");
            return true;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 240, batch = "enhanced_combat_permissions_memory")
    public static void redirectedPlayerAndPostPreDimensionAreRejectedThenHiddenPursuitUsesExpiringSnapshot(
        GameTestHelper helper) {
        var f = fixture(helper, MaidTask.ATTACK);
        var core = select(helper, f);
        var target = pig(helper, f, 2.8, 4.5);
        var friendly = pig(helper, f, 1.5, 2.5);
        friendly.setCustomNameTag("MaidNoAttack QA");
        var hook = new TargetBoundary(f, target);
        MinecraftForge.EVENT_BUS.register(hook);
        helper.afterTest(() -> {
            target.dimension = helper.getWorld().provider.dimensionId;
            MinecraftForge.EVENT_BUS.unregister(hook);
        });
        int[] phase = { 0 }, changed = { -1 }, hiddenAt = { -1 };
        helper.succeedWhen(() -> {
            ready(helper, f, core);
            int age = f.maid.ticksExisted;
            helper.assertTrue(
                f.owner.getHealth() == 20 && friendly.getHealth() == 200,
                "Player or excluded named target was damaged");
            if (phase[0] == 0) {
                if (hook.redirects == 0) return false;
                helper.assertTrue(
                    core.actor()
                        .meleeNativeAccepted() == 0 && target.getHealth() == 200,
                    "Illegal target event redirection still damaged target");
                hook.redirect = false;
                hook.invalidateDimension = true;
                phase[0] = 1;
                return false;
            }
            if (phase[0] == 1) {
                if (hook.invalidations == 0) return false;
                helper.assertTrue(
                    target.getHealth() == 200 && f.maid.getHeldItem()
                        .getItemDamage() == 0,
                    "Post-Pre dimension revocation failed to stop native damage or wear");
                changed[0] = age;
                phase[0] = 2;
                return false;
            }
            if (age <= changed[0]) return false;
            if (phase[0] == 2) {
                helper.assertTrue(
                    core.combat()
                        .target() == null,
                    "Illegal dimension retained combat authority");
                target.dimension = helper.getWorld().provider.dimensionId;
                target.setPosition(f.x + 8.5, f.y + 1, f.z + 4.5);
                phase[0] = 3;
                return false;
            }
            if (phase[0] == 3) {
                if (core.combat()
                    .target() != target) return false;
                helper.assertTrue(
                    core.combat()
                        .lastSeenPosition().x == f.x + 8,
                    "New visible observation was not recorded");
                for (int y = 1; y <= 6; y++) for (int z = 1; z < 8; z++) helper.setBlock(4, y, z, Blocks.stone);
                target.setPosition(f.x + 14.5, f.y + 1, f.z + 4.5);
                hiddenAt[0] = age;
                phase[0] = 4;
                return false;
            }
            if (age < hiddenAt[0] + 5) return false;
            if (age <= hiddenAt[0] + 90) {
                helper.assertTrue(
                    core.combat()
                        .lastSeenPosition() != null
                        && core.combat()
                            .lastSeenPosition().x == f.x + 8
                        && core.actor()
                            .peekWalkTarget() != null
                        && core.actor()
                            .peekWalkTarget()
                            .currentBlockPosition().x == f.x + 8
                        && target.getHealth() == 200,
                    "Hidden target used live position instead of last-seen snapshot");
                return false;
            }
            if (age <= hiddenAt[0] + 102) return false;
            helper.assertTrue(
                core.combat()
                    .target() == null
                    && f.maid.getModernCombatMemory()
                        .target() == null,
                "Expired hidden observation retained attack target");
            return true;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 200, batch = "enhanced_combat_ranged_ownership")
    public static void foreignUseIsPreservedSpawnRevocationConservesAmmoAndMovingShotUsesNativeLead(
        GameTestHelper helper) {
        var f = fixture(helper, MaidTask.RANGED_ATTACK);
        f.maid.setCurrentItemOrArmor(0, new ItemStack(Items.bow));
        f.maid.setOffhandItem(shield(helper));
        f.maid.getMaidInventory()
            .setInventorySlotContents(0, new ItemStack(Items.arrow, 8));
        var core = select(helper, f);
        var target = pig(helper, f, 9.5, 4.5);
        MaidItemUseState.Lease[] foreign = { null };
        var hook = new ArrowBoundary(f, core, target);
        MinecraftForge.EVENT_BUS.register(hook);
        helper.afterTest(() -> {
            MinecraftForge.EVENT_BUS.unregister(hook);
            for (var arrow : hook.arrows) arrow.setDead();
        });
        int[] phase = { 0 }, changed = { -1 };
        MaidCombatActor.Target[] oldView = { null };
        helper.succeedWhen(() -> {
            ready(helper, f, core);
            int age = f.maid.ticksExisted;
            var bow = core.actor()
                .rangedBow();
            if (foreign[0] == null && phase[0] == 0) {
                if (age < 4) return false;
                // 初次实体/主人登记会执行全策略reset，在其完成后建立场景内的外部使用者
                foreign[0] = f.maid.getItemUseState()
                    .tryAcquire(
                        new Object(),
                        f.maid.getOffhandItem(),
                        MaidItemUseState.Hand.OFF,
                        MaidItemUseState.Kind.SHIELD,
                        () -> true);
                helper.assertTrue(foreign[0] != null, "Foreign use owner did not acquire shared service");
            }
            if (age <= 5 || age % 20 == 0 || age == 25) System.out.println(
                "TLMNH_N_RANGED_TRACE age=" + age
                    + " phase="
                    + phase[0]
                    + " kind="
                    + core.selectedKind()
                    + " foreign="
                    + (f.maid.getItemUseState()
                        .peek() == foreign[0])
                    + " use="
                    + f.maid.getItemUseState()
                        .peek()
                    + " shots="
                    + bow.getShots()
                    + " target="
                    + (core.combat()
                        .target() == target)
                    + " wear="
                    + f.maid.getHeldItem()
                        .getItemDamage());
            if (phase[0] == 0) {
                if (age < 25) return false;
                helper.assertTrue(
                    core.combat()
                        .target() == target
                        && f.maid.getItemUseState()
                            .peek() == foreign[0]
                        && bow.getShots() == 0
                        && f.maid.getHeldItem()
                            .getItemDamage() == 0,
                    "Combat cancelled a foreign use or drew/released without its own lease");
                target.setCustomNameTag("MaidNoAttack QA");
                changed[0] = age;
                phase[0] = 1;
                return false;
            }
            if (age <= changed[0]) return false;
            if (phase[0] == 1) {
                helper.assertTrue(
                    core.combat()
                        .target() == null
                        && f.maid.getItemUseState()
                            .peek() == foreign[0],
                    "Ordinary target cancellation stole foreign item use");
                f.maid.getItemUseState()
                    .release(foreign[0]);
                target.setCustomNameTag("");
                phase[0] = 2;
                return false;
            }
            target.setPosition(target.posX, target.posY, target.posZ + .025);
            if (phase[0] == 2) {
                if (core.combat()
                    .target() != null && hook.arrows.isEmpty())
                    oldView[0] = core.actor()
                        .attackTarget();
                if (hook.arrows.isEmpty()) return false;
                helper.assertTrue(
                    hook.arrows.get(0).isDead && bow.getShots() == 0
                        && f.maid.getMaidInventory()
                            .getStackInSlot(0).stackSize == 8
                        && f.maid.getHeldItem()
                            .getItemDamage() == 0
                        && f.maid.getItemUseState()
                            .peek() == null,
                    "Revoked real spawn retained arrow, consumed ammo/wear or leaked use");
                boolean refused = false;
                try {
                    core.actor()
                        .fireRangedAttackForPolicy(oldView[0], 1, () -> true);
                } catch (IllegalArgumentException | IllegalStateException expected) {
                    refused = true;
                }
                helper.assertTrue(refused, "Old ranged capability survived revoked spawn");
                hook.revoke = false;
                f.maid.getBrainCoordinator()
                    .setSuspended(false);
                phase[0] = 3;
                return false;
            }
            if (bow.getShots() < 1 || target.getHealth() >= 200) return false;
            helper.assertTrue(
                bow.getShots() == 1 && f.maid.getMaidInventory()
                    .getStackInSlot(0).stackSize == 7
                    && f.maid.getHeldItem()
                        .getItemDamage() == 1
                    && hook.arrows.size() == 2
                    && hook.arrows.get(1)
                        .getClass() == EntityArrow.class
                    && bow.flightProfile() == MaidBowAttack.ModernFlightProfile.NATIVE_MOTION_COMPENSATION
                    && hook.leads.get(1) > .01,
                "Accepted moving native shot did not lead, hit or settle one finite payment");
            return true;
        });
    }

    private static ModernCoreTests.Fixture fixture(GameTestHelper helper, MaidTask task) {
        var f = new ModernCoreTests.Fixture(helper, false, 1, 16);
        f.maid.setTask(task);
        f.maid.setSchedule(MaidSchedule.ALL);
        f.maid.setCurrentItemOrArmor(0, new ItemStack(Items.iron_sword));
        f.owner.setPosition(f.x + 1.5, f.y + 1, f.z + 1.5);
        for (int x = 1; x < 20; x++) for (int z = 1; z < 8; z++) helper.setBlock(x, 0, z, Blocks.stone);
        return f;
    }

    private static EnhancedCoreBrain select(GameTestHelper helper, ModernCoreTests.Fixture f) {
        f.maid.getModernCombatMemory()
            .target(f.owner);
        helper.assertTrue(
            f.maid.selectBrainProfileForDevelopment(MaidBrainProfile.ENHANCED_CORE_COMPONENT)
                == MaidPolicyRuntime.SelectionResult.SELECTED,
            "Actual enhanced combat selection failed");
        helper.assertTrue(
            f.maid.getModernCombatMemory()
                .target() == null,
            "N factory inherited prior execution target");
        return f.maid.getBrainCoordinator()
            .peekEnhancedCore();
    }

    private static EntityPig pig(GameTestHelper helper, ModernCoreTests.Fixture f, double x, double z) {
        var pig = new EntityPig(helper.getWorld());
        pig.getEntityAttribute(SharedMonsterAttributes.maxHealth)
            .setBaseValue(200);
        pig.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
            .setBaseValue(0);
        pig.getEntityAttribute(SharedMonsterAttributes.knockbackResistance)
            .setBaseValue(1);
        pig.setHealth(200);
        helper.spawnEntity(pig, x, 1, z);
        f.maid.getAttackListData()
            .attackGroups()
            .put(MaidCombatEntities.id(pig), MonsterType.HOSTILE);
        helper.afterTest(pig::setDead);
        return pig;
    }

    private static ItemStack shield(GameTestHelper helper) {
        var item = (Item) Item.itemRegistry.getObject("battlegear2:shield.wood");
        helper.assertTrue(item != null, "Formal shield missing");
        return new ItemStack(item);
    }

    private static void ready(GameTestHelper helper, ModernCoreTests.Fixture f, EnhancedCoreBrain core) {
        var brain = f.maid.getBrainCoordinator();
        helper.assertTrue(
            f.maid.isEntityAlive() && brain.peekEnhancedCore() == core
                && brain.peekPolicyRuntime()
                    .isReady()
                && brain.peekFaithfulBrain() == null
                && brain.getFollowController()
                    .getTicks() == 0
                && brain.getCombatController()
                    .getTicks() == 0,
            "Enhanced combat faulted or dispatched another root");
    }

    public static final class TargetBoundary {

        final ModernCoreTests.Fixture f;
        final EntityPig target;
        boolean redirect = true, invalidateDimension;
        int redirects, invalidations;

        TargetBoundary(ModernCoreTests.Fixture f, EntityPig target) {
            this.f = f;
            this.target = target;
        }

        @SubscribeEvent
        public void selecting(MaidAttackTargetEvent event) {
            if (event.getMaid() == f.maid && redirect) {
                redirects++;
                event.setNewTarget(f.owner);
            }
        }

        @SubscribeEvent
        public void hurting(MaidHurtTarget.Pre event) {
            if (event.getMaid() == f.maid && invalidateDimension) {
                invalidateDimension = false;
                invalidations++;
                target.dimension = f.maid.dimension + 1;
            }
        }
    }

    public static final class ArrowBoundary {

        final ModernCoreTests.Fixture f;
        final EnhancedCoreBrain core;
        final EntityPig target;
        final List<EntityArrow> arrows = new ArrayList<>();
        final List<Double> leads = new ArrayList<>();
        boolean revoke = true;

        ArrowBoundary(ModernCoreTests.Fixture f, EnhancedCoreBrain core, EntityPig target) {
            this.f = f;
            this.core = core;
            this.target = target;
        }

        @SubscribeEvent
        public void spawning(EntityJoinWorldEvent event) {
            if (event.entity instanceof EntityArrow arrow && arrow.shootingEntity == f.maid) {
                arrows.add(arrow);
                var solution = core.actor()
                    .rangedBow()
                    .getLastSolution();
                leads.add(solution == null ? 0 : solution.predictedTarget.z - target.posZ);
                if (revoke) f.maid.getBrainCoordinator()
                    .setSuspended(true);
            }
        }
    }
}
