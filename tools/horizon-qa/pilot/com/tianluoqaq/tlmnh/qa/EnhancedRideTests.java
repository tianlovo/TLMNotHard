package com.tianluoqaq.tlmnh.qa;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.item.EntityMinecartEmpty;
import net.minecraft.entity.passive.EntityChicken;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidHurtTarget;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.misc.MonsterType;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedCoreBrain;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Kind;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatEntities;
import com.tianluoqaq.tlmnh.common.combat.MaidItemUseState;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class EnhancedRideTests {

    private EnhancedRideTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 220, batch = "enhanced_ride_boat")
    public static void actualEfrCapacityApproachPassengerSeatAndRecentOwnerDismount(GameTestHelper helper) {
        var v = new ModernVehicleTests.Fixture(helper);
        var f = v.f;
        var chicken = new EntityChicken(helper.getWorld());
        chicken.tasks.taskEntries.clear();
        helper.spawnEntity(chicken, 14.5, 2, 4.5);
        f.maid.setSitting(true);
        helper.afterTest(() -> {
            chicken.mountEntity(null);
            chicken.setDead();
        });
        var core = select(helper, f);
        int[] phase = { -1 }, changed = { -1 };
        boolean[] route = { false };
        helper.succeedWhen(() -> {
            ready(helper, f, core);
            int age = f.maid.ticksExisted;
            if (phase[0] == -1) {
                if (age < 4 || v.seat() == null) return false;
                v.add(chicken);
                helper
                    .assertTrue(v.seat().riddenByEntity == chicken, "Real seat was not occupied before capacity test");
                f.maid.setSitting(false);
                changed[0] = age;
                phase[0] = 0;
                return false;
            }
            if (phase[0] == 0) {
                helper.assertTrue(
                    f.maid.ridingEntity == null && v.seat().riddenByEntity == chicken,
                    "Full real seat was stolen");
                if (age < changed[0] + 5) return false;
                chicken.mountEntity(null);
                chicken.setDead();
                phase[0] = 1;
                return false;
            }
            if (phase[0] == 1) {
                route[0] |= core.selectedKind() == Kind.VEHICLE && !f.navigation.noPath();
                if (f.maid.ridingEntity == null) return false;
                v.assertPassenger(core.actor());
                helper.assertTrue(
                    route[0] && f.navigation.noPath()
                        && core.actor()
                            .peekWalkTarget() == null,
                    "No vehicle route preceded boarding or ground route survived it");
                changed[0] = age;
                phase[0] = 2;
                return false;
            }
            if (age <= changed[0] + 3) return false;
            if (phase[0] == 2) {
                v.assertPassenger(core.actor());
                f.owner.mountEntity(null);
                f.owner.setPosition(v.boat.posX + 1, f.maid.posY, v.boat.posZ);
                changed[0] = age;
                phase[0] = 3;
                return false;
            }
            helper.assertTrue(
                f.maid.ridingEntity == null && f.owner.ridingEntity == null,
                "Nearby passenger did not follow actual recent owner dismount");
            return true;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 180, batch = "enhanced_ride_bow")
    public static void minecartShotRejectsSpawnDismountThenPaysOneNativeHitWithoutGroundMotion(GameTestHelper helper) {
        var f = fixture(helper, MaidTask.RANGED_ATTACK);
        f.maid.setCurrentItemOrArmor(0, new ItemStack(Items.bow));
        f.maid.getMaidInventory()
            .setInventorySlotContents(0, new ItemStack(Items.arrow, 8));
        var cart = new EntityMinecartEmpty(helper.getWorld());
        helper.spawnEntity(cart, 1.5, 1.4, 4.5);
        helper.afterTest(() -> {
            f.maid.mountEntity(null);
            cart.setDead();
        });
        f.maid.mountEntity(cart);
        var target = pig(helper, f, 9.5, 4.5);
        var core = select(helper, f);
        var hook = new DismountCallbacks(f);
        MinecraftForge.EVENT_BUS.register(hook);
        helper.afterTest(() -> {
            MinecraftForge.EVENT_BUS.unregister(hook);
            for (var a : hook.arrows) a.setDead();
        });
        boolean[] rejected = { false };
        helper.succeedWhen(() -> {
            ready(helper, f, core);
            var bow = core.actor()
                .rangedBow();
            helper.assertTrue(
                f.navigation.noPath() && core.actor()
                    .peekWalkTarget() == null && f.maid.moveStrafing == 0,
                "Passenger created ground walk/strafe");
            if (f.maid.ticksExisted == 5 || f.maid.ticksExisted == 15) cart.rotationYaw += 45;
            if (!rejected[0] && !hook.arrows.isEmpty()) {
                helper.assertTrue(
                    hook.arrows.get(0).isDead && f.maid.ridingEntity == null
                        && bow.getShots() == 0
                        && f.maid.getMaidInventory()
                            .getStackInSlot(0).stackSize == 8
                        && f.maid.getHeldItem()
                            .getItemDamage() == 0
                        && f.maid.getItemUseState()
                            .peek() == null,
                    "Spawn callback mount change consumed ammo/wear or kept arrow");
                f.maid.mountEntity(cart);
                rejected[0] = true;
                return false;
            }
            if (!rejected[0] || bow.getShots() == 0 || target.getHealth() >= 200) return false;
            helper.assertTrue(
                f.maid.ridingEntity == cart && bow.getShots() == 1
                    && hook.arrows.size() == 2
                    && hook.arrows.get(1)
                        .getClass() == EntityArrow.class
                    && f.maid.getMaidInventory()
                        .getStackInSlot(0).stackSize == 7
                    && f.maid.getHeldItem()
                        .getItemDamage() == 1,
                "Fresh mounted action did not produce one finite native hit");
            return true;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 150, batch = "enhanced_ride_melee")
    public static void seatedMeleeNeverChasesAndHurtPreSeatChangeRejectsThenFreshActionHits(GameTestHelper helper) {
        var f = fixture(helper, MaidTask.ATTACK);
        f.maid.setCurrentItemOrArmor(0, new ItemStack(Items.iron_sword));
        f.maid.setOffhandItem(
            new ItemStack(
                (net.minecraft.item.Item) net.minecraft.item.Item.itemRegistry.getObject("battlegear2:shield.wood")));
        f.maid.setSitting(true);
        var target = pig(helper, f, 8.5, 4.5);
        var core = select(helper, f);
        var hook = new DismountCallbacks(f);
        MinecraftForge.EVENT_BUS.register(hook);
        helper.afterTest(() -> MinecraftForge.EVENT_BUS.unregister(hook));
        int[] phase = { 0 }, changed = { -1 };
        helper.succeedWhen(() -> {
            ready(helper, f, core);
            int age = f.maid.ticksExisted;
            helper.assertTrue(
                f.navigation.noPath() && core.actor()
                    .peekWalkTarget() == null,
                "Seated melee pursued a target");
            if (phase[0] == 0) {
                if (age < 25 || core.combat()
                    .target() != target) return false;
                helper.assertTrue(
                    target.getHealth() == 200 && f.maid.getHeldItem()
                        .getItemDamage() == 0,
                    "Out-of-reach seated attack hurt the target");
                target.setPosition(f.maid.posX, f.maid.posY, f.maid.posZ + 2);
                phase[0] = 1;
                return false;
            }
            if (phase[0] == 1) {
                if (hook.meleeChanges == 0) return false;
                helper.assertTrue(
                    !f.maid.isSitting() && target.getHealth() == 200
                        && f.maid.getHeldItem()
                            .getItemDamage() == 0
                        && core.actor()
                            .meleeNativeAccepted() == 0,
                    "Hurt Pre seat change retained old melee authority");
                f.maid.setSitting(true);
                changed[0] = age;
                phase[0] = 2;
                return false;
            }
            if (age <= changed[0] || core.actor()
                .meleeNativeAccepted() == 0) return false;
            helper.assertTrue(
                f.maid.isSitting() && target.getHealth() < 200
                    && f.maid.getHeldItem()
                        .getItemDamage() > 0,
                "Fresh seated melee failed finite hurt/wear");
            f.maid.setTask(MaidTask.IDLE);
            helper.assertTrue(
                core.combat()
                    .target() == null
                    && f.maid.getItemUseState()
                        .peek() == null,
                "Task change kept mounted combat resources");
            return true;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 140, batch = "enhanced_ride_meal")
    public static void seatedWorkMealPaysOnceThenHealingRestartsOwnedLeaseAndHiddenOwnerStopsBeg(
        GameTestHelper helper) {
        var f = fixture(helper, MaidTask.IDLE);
        var sword = new ItemStack(Items.iron_sword);
        f.maid.setCurrentItemOrArmor(0, sword);
        f.maid.getMaidInventory()
            .setInventorySlotContents(0, new ItemStack(Items.golden_apple, 2, 1));
        f.owner.inventory.setInventorySlotContents(f.owner.inventory.currentItem, new ItemStack(Items.cake));
        f.maid.setSitting(true);
        var core = select(helper, f);
        int[] phase = { 0 }, changed = { -1 };
        MaidItemUseState.Lease[] old = { null };
        helper.succeedWhen(() -> {
            ready(helper, f, core);
            int age = f.maid.ticksExisted;
            helper.assertTrue(
                f.navigation.noPath() && core.actor()
                    .peekWalkTarget() == null,
                "Stationary care installed travel");
            if (phase[0] == 0) {
                if (age < 4 || !f.maid.getMealController()
                    .isEating()) return false;
                helper.assertTrue(
                    core.selectedKind() == Kind.MEAL && f.maid.hasPose(MaidPose.BEGGING),
                    "Ordinary work meal or fresh owner temptation was absent");
                phase[0] = 1;
                return false;
            }
            if (phase[0] == 1) {
                if (f.maid.getMealController()
                    .isEating()) return false;
                helper.assertTrue(
                    f.maid.getHealth() == 20 && countApples(f) == 1 && f.maid.getHeldItem() == sword,
                    "Completed work meal failed one payment or original hand restore");
                f.maid.setHealth(10);
                phase[0] = 2;
                return false;
            }
            if (phase[0] == 2) {
                if (core.selectedKind() != Kind.HEAL || !f.maid.getMealController()
                    .isEating()) return false;
                old[0] = f.maid.getItemUseState()
                    .peek();
                f.maid.setSitting(false);
                changed[0] = age;
                phase[0] = 3;
                return false;
            }
            if (age <= changed[0]) return false;
            if (phase[0] == 3) {
                helper.assertTrue(
                    f.maid.getMealController()
                        .isEating()
                        && !f.maid.getItemUseState()
                            .isCurrent(old[0])
                        && f.maid.getItemUseState()
                            .peek() != old[0]
                        && countApples(f) == 1,
                    "Sit boundary retained old meal lease or lost food during cancel/restart");
                f.maid.setSitting(true);
                f.owner.setPosition(f.x + 6.5, f.y + 1, f.z + 4.5);
                for (int y = 1; y <= 6; y++) for (int z = 1; z < 8; z++) helper.setBlock(4, y, z, Blocks.stone);
                changed[0] = age;
                phase[0] = 4;
                return false;
            }
            helper.assertTrue(
                !f.maid.canEntityBeSeen(f.owner) && !f.maid.hasPose(MaidPose.BEGGING),
                "Hidden owner retained temptation pose");
            return true;
        });
    }

    private static int countApples(ModernCoreTests.Fixture f) {
        int count = 0;
        for (int i = 0; i < f.maid.getMaidInventory()
            .getSizeInventory(); i++) {
            var s = f.maid.getMaidInventory()
                .getStackInSlot(i);
            if (s != null && s.getItem() == Items.golden_apple && s.getItemDamage() == 1) count += s.stackSize;
        }
        return count;
    }

    private static ModernCoreTests.Fixture fixture(GameTestHelper helper, MaidTask task) {
        var f = new ModernCoreTests.Fixture(helper, false, 1, 16);
        f.maid.setSchedule(MaidSchedule.ALL);
        f.maid.setTask(task);
        f.owner.setPosition(f.x + 1.5, f.y + 1, f.z + 1.5);
        for (int x = 1; x < 20; x++) for (int z = 1; z < 8; z++) helper.setBlock(x, 0, z, Blocks.stone);
        return f;
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

    private static EnhancedCoreBrain select(GameTestHelper helper, ModernCoreTests.Fixture f) {
        helper.assertTrue(
            f.maid.selectBrainProfileForDevelopment(MaidBrainProfile.ENHANCED_CORE_COMPONENT)
                == MaidPolicyRuntime.SelectionResult.SELECTED,
            "Enhanced ride selection failed");
        return f.maid.getBrainCoordinator()
            .peekEnhancedCore();
    }

    private static void ready(GameTestHelper helper, ModernCoreTests.Fixture f, EnhancedCoreBrain core) {
        var b = f.maid.getBrainCoordinator();
        helper.assertTrue(
            f.maid.isEntityAlive() && b.peekEnhancedCore() == core
                && b.peekPolicyRuntime()
                    .isReady()
                && b.peekFaithfulBrain() == null
                && b.getFollowController()
                    .getTicks() == 0
                && b.getCombatController()
                    .getTicks() == 0,
            "Enhanced ride faulted or used another root");
    }

    public static final class DismountCallbacks {

        final ModernCoreTests.Fixture f;
        final List<EntityArrow> arrows = new ArrayList<>();
        int meleeChanges;

        DismountCallbacks(ModernCoreTests.Fixture f) {
            this.f = f;
        }

        @SubscribeEvent
        public void spawning(EntityJoinWorldEvent event) {
            if (event.entity instanceof EntityArrow a && a.shootingEntity == f.maid) {
                arrows.add(a);
                if (arrows.size() == 1) f.maid.mountEntity(null);
            }
        }

        @SubscribeEvent
        public void hitting(MaidHurtTarget.Pre event) {
            if (event.getMaid() == f.maid && meleeChanges == 0) {
                meleeChanges++;
                f.maid.setSitting(false);
            }
        }
    }
}
