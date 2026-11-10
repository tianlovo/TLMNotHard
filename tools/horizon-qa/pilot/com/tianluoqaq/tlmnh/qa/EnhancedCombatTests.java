package com.tianluoqaq.tlmnh.qa;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
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
import com.tianluoqaq.tlmnh.common.ai.MaidCombatTargeting;
import com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedCoreBrain;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidCombatActor;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Kind;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.combat.MaidBowAttack;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatEntities;
import com.tianluoqaq.tlmnh.common.combat.MaidItemUseState;
import com.tianluoqaq.tlmnh.common.combat.shield.MaidShieldController;
import com.tianluoqaq.tlmnh.common.combat.shield.MaidShieldItems;
import com.tianluoqaq.tlmnh.common.combat.shield.MaidShieldRules;
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
        var sword = f.maid.getHeldItem();
        var food = new ItemStack(Items.golden_apple, 2, 1);
        var foodNbt = food.writeToNBT(new NBTTagCompound());
        var shieldNbt = shield.writeToNBT(new NBTTagCompound());
        f.maid.getMaidInventory()
            .setInventorySlotContents(0, food);
        EntityPig[] enemy = { null };
        EntityArrow[] arrow = { null };
        int[] phase = { 0 }, hurtAt = { -1 }, defenseAt = { -1 }, mealRestoredAt = { -1 }, launchedAt = { -1 };
        long[] lastBlockSequence = { 0 };
        var trace = new CombatTrace();
        boolean[] ownedDefenseAfterThreat = { false };
        float[] beforeArrow = { -1 };
        helper.succeedWhen(() -> {
            ready(helper, f, core);
            int age = f.maid.ticksExisted;
            trace.capture("meal-shield", f, core, enemy[0], phase[0], "mealRestoredAt=" + mealRestoredAt[0]);
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
                if (mealRestoredAt[0] < 0 && core.selectedKind() == Kind.COMBAT) {
                    // 归位属于首次受击抢占帧，不能等后续合法自疗完成后再拿原食物作比较
                    helper.assertTrue(
                        age > hurtAt[0] && f.maid.hurtTime > 0,
                        "Meal was not preempted during the actual hurt window");
                    helper.assertTrue(
                        !f.maid.getMealController()
                            .isEating(),
                        "Actual combat preemption retained the owned meal lease");
                    helper.assertTrue(f.maid.getHeldItem() == sword, "Meal preemption replaced the original sword ref");
                    var restored = f.maid.getMaidInventory()
                        .getStackInSlot(0);
                    helper.assertTrue(restored == food, "Meal preemption failed to restore the original food ref");
                    helper.assertTrue(
                        foodNbt.equals(restored.writeToNBT(new NBTTagCompound())),
                        "Meal preemption changed full food NBT before completion");
                    helper
                        .assertTrue(f.maid.getOffhandItem() == shield, "Meal preemption lost the original shield ref");
                    helper.assertTrue(
                        shieldNbt.equals(shield.writeToNBT(new NBTTagCompound())),
                        "Meal preemption changed full displaced shield NBT");
                    mealRestoredAt[0] = age;
                }
                if (core.actor()
                    .meleeNativeAccepted() == 0) return false;
                helper.assertTrue(mealRestoredAt[0] >= 0, "Native melee accepted without observed meal preemption");
                helper.assertTrue(
                    core.selectedKind() == Kind.COMBAT && f.maid.isUnifiedCombat()
                        && f.maid.getTask() == MaidTask.ATTACK,
                    "Native melee accepted outside the unified ATTACK combat intent");
                helper.assertTrue(
                    !f.maid.getMealController()
                        .isEating(),
                    "Native melee accepted with an active meal lease");
                helper.assertTrue(f.maid.getOffhandItem() == shield, "Native melee lost the original shield ref");
                helper.assertTrue(enemy[0].getHealth() < 200, "Accepted native melee did not hurt the actual target");
                helper.assertTrue(
                    f.maid.getHeldItem() == sword && sword.getItemDamage() > 0,
                    "Accepted native melee replaced the original sword or skipped finite wear");
                phase[0] = 2;
            }
            if (phase[0] == 2 && age >= hurtAt[0] + 22) {
                if (defenseAt[0] < 0) {
                    // 用真实坐姿命令隔离防御，避免把旧的常驻举盾策略当成新战术前提
                    f.maid.setSitting(true);
                    defenseAt[0] = age;
                    return false;
                }
                if (age <= defenseAt[0]) return false;
                // 只准备正面来箭输入：等待原生限速转身达到实际盾牌角度，不等待举盾或改写yaw
                // 从当前实际敌人方向射入，避免固定+Z射点越出狭小测试格或偏离已移动的女仆
                double dx = enemy[0].posX - f.maid.posX, dz = enemy[0].posZ - f.maid.posZ;
                double distance = Math.sqrt(dx * dx + dz * dz);
                helper.assertTrue(distance > .01, "Incoming projectile direction is degenerate");
                float blockAngle = MaidShieldItems.blockAngle(shield);
                helper.assertTrue(
                    Float.isFinite(blockAngle) && blockAngle > 0 && blockAngle <= 180,
                    "Actual shield provider did not expose a valid frontal block angle");
                float relativeYaw = MaidShieldRules.relativeYaw(
                    enemy[0].posX,
                    enemy[0].posZ,
                    f.maid.posX,
                    f.maid.posZ,
                    f.maid.rotationYaw,
                    () -> {
                        throw new IllegalStateException("Nondegenerate source direction unexpectedly randomized");
                    });
                if (!MaidShieldRules.withinAngle(relativeYaw, blockAngle)) return false;
                double sourceBearing = Math.atan2(dz, dx) * 180 / Math.PI - 90;
                System.out.println(
                    "TLMNH_N_SHIELD_LAUNCH age=" + age
                        + " bodyYaw="
                        + f.maid.rotationYaw
                        + " sourceBearing="
                        + sourceBearing
                        + " relativeYaw="
                        + relativeYaw
                        + " providerAngle="
                        + blockAngle);
                beforeArrow[0] = f.maid.getHealth();
                launchedAt[0] = age;
                var previousBlock = f.maid.getShieldController()
                    .getLastBlock();
                lastBlockSequence[0] = previousBlock == null ? 0 : previousBlock.sequence;
                dx /= distance;
                dz /= distance;
                arrow[0] = new EntityArrow(
                    helper.getWorld(),
                    f.maid.posX + dx * 2.0,
                    f.maid.posY + .9,
                    f.maid.posZ + dz * 2.0);
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
            if (core.actor()
                .shieldBehaviorRunning()) ownedDefenseAfterThreat[0] = true;
            var block = f.maid.getShieldController()
                .getLastBlock();
            if (block != null && block.sequence != lastBlockSequence[0]) {
                lastBlockSequence[0] = block.sequence;
                double sourceBearing = Math.atan2(enemy[0].posZ - f.maid.posZ, enemy[0].posX - f.maid.posX) * 180
                    / Math.PI - 90;
                System.out.println(
                    "TLMNH_N_SHIELD_IMPACT observedAge=" + age
                        + " launchAge="
                        + launchedAt[0]
                        + " outcome="
                        + block.outcome
                        + " bodyYaw="
                        + f.maid.rotationYaw
                        + " sourceBearing="
                        + sourceBearing
                        + " impactRelativeYaw="
                        + block.relativeYaw
                        + " providerAngle="
                        + block.blockAngle
                        + " amount="
                        + block.amount
                        + " requestedWear="
                        + block.requestedWear
                        + " blocked="
                        + block.blocked);
            }
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
                arrow[0].isDead && shield.getItemDamage() > 0
                    && f.maid.getHealth() == beforeArrow[0]
                    && ownedDefenseAfterThreat[0],
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
        var trace = new CombatTrace();
        helper.succeedWhen(() -> {
            ready(helper, f, core);
            int age = f.maid.ticksExisted;
            trace.capture(
                "permissions-memory",
                f,
                core,
                target,
                phase[0],
                "redirects=" + hook.redirects
                    + " invalidations="
                    + hook.invalidations
                    + " dimensionPending="
                    + hook.invalidateDimension
                    + " hiddenAt="
                    + hiddenAt[0]);
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
                        && target.getHealth() == 200,
                    "Hidden target changed its last-seen memory or received hidden damage");
                var plan = core.combat()
                    .lastPlan();
                if (plan != null && target.getUniqueID()
                    .equals(plan.targetId))
                    helper.assertTrue(
                        plan.face != null && plan.face.x == f.x + 8.5,
                        "Hidden tactical plan used current target coordinates instead of observed coordinates");
                var walk = core.actor()
                    .peekWalkTarget();
                if (walk != null) helper.assertTrue(
                    walk.currentBlockPosition().x <= f.x + 8,
                    "Hidden pursuit navigated beyond the last visible target to its hidden live position");
                // 新战斗只执行已知安全首段；障碍前WAIT或侧移合法，不要求直达墙后的旧目标点
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
        // 新增量观测可在首tick看到目标；先排除它，待初始reset结束后建立真实外来租约
        target.setCustomNameTag("MaidNoAttack QA");
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
                target.setCustomNameTag("");
                helper.assertTrue(
                    f.maid.isUnifiedCombat() && f.maid.getTask() == MaidTask.ATTACK,
                    "Old ranged input did not enter the unified production task");
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
        var f = new ModernCoreTests.Fixture(helper, false, 1, 16, MaidBrainProfile.ENHANCED);
        f.maid.setTask(task);
        f.maid.setSchedule(MaidSchedule.ALL);
        f.maid.setCurrentItemOrArmor(0, new ItemStack(Items.iron_sword));
        f.owner.setPosition(f.x + 1.5, f.y + 1, f.z + 1.5);
        for (int x = 1; x < 20; x++) for (int z = 1; z < 8; z++) helper.setBlock(x, 0, z, Blocks.stone);
        return f;
    }

    private static EnhancedCoreBrain select(GameTestHelper helper, ModernCoreTests.Fixture f) {
        helper.assertTrue(
            f.owner.getClass() == EntityPlayerMP.class && MaidCombatTargeting.validOwner(f.maid, f.owner),
            "Actual registered native MP owner required for production combat admission");
        // 完整夹具已选择N；先走真实策略边界，保留旧执行目标不能继承进新N的原断言
        helper.assertTrue(
            f.maid.selectBrainProfileForDevelopment(MaidBrainProfile.LEGACY_PROTOTYPE)
                == MaidPolicyRuntime.SelectionResult.SELECTED,
            "Fresh production strategy boundary failed");
        f.maid.getModernCombatMemory()
            .target(f.owner);
        helper.assertTrue(
            f.maid.selectBrainProfileForDevelopment(MaidBrainProfile.ENHANCED)
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
                && brain.peekPolicyRuntime()
                    .getProfile() == MaidBrainProfile.ENHANCED
                && f.owner.getClass() == EntityPlayerMP.class
                && MaidCombatTargeting.validOwner(f.maid, f.owner)
                && brain.peekFaithfulBrain() == null
                && brain.getFollowController()
                    .getTicks() == 0
                && brain.getCombatController()
                    .getTicks() == 0,
            "Enhanced combat faulted or dispatched another root");
    }

    /** 仅打印既有状态；宏/提交/阶段变化或每20tick一帧，每个用例最多120帧 */
    private static final class CombatTrace {

        private String previous;
        private int lastAt = -20, frames;

        void capture(String label, ModernCoreTests.Fixture f, EnhancedCoreBrain core, EntityPig enemy, int phase,
            String boundary) {
            var plan = core.combat()
                .lastPlan();
            var snapshot = core.combat()
                .lastSnapshot();
            var result = core.combat()
                .lastResult();
            String key = phase + ":"
                + core.selectedKind()
                + ":"
                + (plan == null ? "NONE" : plan.macro)
                + ":"
                + result
                + ":"
                + boundary;
            int age = f.maid.ticksExisted;
            if (frames >= 120 || key.equals(previous) && age - lastAt < 20) return;
            previous = key;
            lastAt = age;
            frames++;
            var selected = snapshot == null ? null : snapshot.target(snapshot.selectedTarget);
            var seen = core.combat()
                .lastSeenPosition();
            var held = f.maid.getHeldItem();
            var stored = f.maid.getMaidInventory()
                .getStackInSlot(0);
            var use = f.maid.getItemUseState()
                .peek();
            System.out.println(
                "TLMNH_N_BOUNDARY_TRACE case=" + label
                    + " age="
                    + age
                    + " phase="
                    + phase
                    + " boundary={"
                    + boundary
                    + "} kind="
                    + core.selectedKind()
                    + " meal="
                    + f.maid.getMealController()
                        .isEating()
                    + " hurtTime="
                    + f.maid.hurtTime
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
                    + " enemy="
                    + (enemy == null ? "null"
                        : enemy.getEntityId() + "/dim="
                            + enemy.dimension
                            + "/hp="
                            + enemy.getHealth()
                            + "/pos="
                            + enemy.posX
                            + ","
                            + enemy.posY
                            + ","
                            + enemy.posZ)
                    + " maidPos="
                    + f.maid.posX
                    + ","
                    + f.maid.posY
                    + ","
                    + f.maid.posZ
                    + " seen="
                    + (seen == null ? "null" : seen.x + "," + seen.y + "," + seen.z)
                    + " shield="
                    + core.actor()
                        .shieldBehaviorRunning()
                    + " use="
                    + (use == null ? "NONE" : use.getKind())
                    + " foodCount="
                    + (stored == null ? 0 : stored.stackSize)
                    + " held="
                    + (held == null ? "null" : held.getItem() + "/wear=" + held.getItemDamage())
                    + " cadence="
                    + f.maid.getCombatCadence()
                        .remainingTicks()
                    + " receipt="
                    + f.maid.getCombatHitReceipt()
                        .stage()
                    + " macro="
                    + (plan == null ? "NONE" : plan.macro)
                    + " result="
                    + result
                    + " simulated="
                    + (plan != null && plan.simulated)
                    + " expected="
                    + (plan == null ? "NONE"
                        : plan.expectedDamage + "/risk=" + plan.diagnosticTotalRisk() + "/scope=" + plan.riskScope)
                    + " snapshot="
                    + (snapshot == null ? "NONE"
                        : "complete=" + snapshot.complete
                            + "/tick="
                            + snapshot.tick
                            + "/selfHP="
                            + snapshot.self.health
                            + "/reach="
                            + snapshot.self.reach
                            + "/remaining="
                            + snapshot.self.cadenceRemainingTicks)
                    + " observedTarget="
                    + (selected == null ? "NONE"
                        : selected.entityId + "/visible="
                            + selected.visible
                            + "/hp="
                            + selected.health
                            + "/lastOwnHit="
                            + selected.lastOwnHitAt
                            + "/pos="
                            + selected.position.x
                            + ","
                            + selected.position.y
                            + ","
                            + selected.position.z));
        }
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
