package com.tianluoqaq.tlmnh.qa;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.item.EntityMinecartEmpty;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.misc.MonsterType;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidCombatActor;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatAttributes;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatEntities;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class ModernRangedTests {

    private ModernRangedTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 260, batch = "modern_ranged_native")
    public static void naturalDistantShotsUseSharedClockAttributesHandsAndInfinity(GameTestHelper helper) {
        var f = fixture(helper);
        var target = target(helper, f, 18.5);
        var hook = hook(helper, f);
        boolean[] adjusted = { false }, done = { false };
        helper.onEachTick("natural ranged selection, real native arrow and finite resources", () -> {
            f.check();
            var bow = f.core.getActor()
                .rangedBow();
            if (hook.ages.size() >= 3 && !adjusted[0]) {
                helper.assertTrue(hook.firstDistance > 12, "Prototype range12 still gated modern shot");
                helper.assertTrue(
                    hook.ages.get(1) - hook.ages.get(0) == 22 && hook.ages.get(2) - hook.ages.get(1) == 22,
                    "Source draw20 and cooldown2 did not produce interval22: " + hook.ages);
                helper.assertTrue(
                    f.maid.getOffhandItem().stackSize == 13 && f.maid.getMaidInventory()
                        .getStackInSlot(0).stackSize == 8
                        && f.maid.getHeldItem()
                            .getItemDamage() == 3,
                    "Basic arrow hand-first consumption or finite bow wear differs");
                helper.assertTrue(
                    target.getHealth() < 200 && bow.getShots() == 3,
                    "Native arrows did not actually damage target");
                f.maid.getEntityAttribute(MaidCombatAttributes.SHOOT_COOLDOWN)
                    .setBaseValue(5);
                f.maid.getEntityAttribute(MaidCombatAttributes.USE_ITEM_SPEED)
                    .setBaseValue(2);
                f.maid.getHeldItem()
                    .addEnchantment(Enchantment.infinity, 1);
                adjusted[0] = true;
            }
            if (adjusted[0] && hook.ages.size() >= 5) {
                helper.assertTrue(
                    hook.ages.get(4) - hook.ages.get(3) == 15,
                    "Source use-speed2/draw10/cooldown5 interval differs: " + hook.ages);
                helper.assertTrue(
                    f.maid.getOffhandItem().stackSize == 13 && f.maid.getHeldItem()
                        .getItemDamage() == 5 && bow.getShots() == 5 && hook.arrows.get(4).canBePickedUp == 2,
                    "Infinity consumed ammo, skipped wear or allowed survival pickup");
                helper.assertTrue(
                    !hook.arrows.get(4)
                        .getIsCritical(),
                    "Native baseline acquired prototype critical bonus");
                done[0] = true;
                System.out.println(
                    "TLMNH_RANGED112_NATIVE_PASS ages=" + hook.ages
                        + " firstDistance="
                        + hook.firstDistance
                        + " hp="
                        + target.getHealth());
            }
        });
        helper.succeedWhen(() -> done[0]);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 200, batch = "modern_ranged_cancel")
    public static void actualSpawnRevocationKeepsResourcesRejectsOldViewAndResumes(GameTestHelper helper) {
        var f = fixture(helper);
        var target = target(helper, f, 9.5);
        var hook = hook(helper, f);
        hook.revoke = true;
        MaidCombatActor.Target[] old = { null };
        int[] phase = { 0 }, changed = { -1 };
        boolean[] done = { false };
        helper.onEachTick("actual arrow spawn callback revokes policy", () -> {
            int age = f.maid.ticksExisted;
            var actor = f.core.getActor();
            if (phase[0] == 0 && hook.arrows.isEmpty() || phase[0] >= 2) f.check();
            if (phase[0] == 0 && f.maid.getAttackTarget() != null && hook.arrows.isEmpty())
                old[0] = actor.attackTarget();
            if (phase[0] == 0 && !hook.arrows.isEmpty()) {
                helper.assertTrue(
                    old[0] != null && hook.arrows.get(0).isDead
                        && actor.rangedBow()
                            .getShots() == 0
                        && f.maid.getOffhandItem().stackSize == 16
                        && f.maid.getHeldItem()
                            .getItemDamage() == 0
                        && f.maid.getItemUseState()
                            .peek() == null,
                    "Revoked spawn left live arrow, consumed resources or leaked use");
                changed[0] = age;
                phase[0] = 1;
            } else if (phase[0] == 1 && age >= changed[0] + 3) {
                hook.revoke = false;
                f.maid.getBrainCoordinator()
                    .setSuspended(false);
                changed[0] = age;
                phase[0] = 2;
            } else if (phase[0] == 2 && age > changed[0]) {
                boolean rejected = false;
                try {
                    actor.performRangedAttack(old[0], 1);
                } catch (IllegalArgumentException | IllegalStateException expected) {
                    rejected = true;
                }
                helper.assertTrue(rejected && hook.arrows.size() == 1, "Stale ranged view obtained new authority");
                phase[0] = 3;
            } else if (phase[0] == 3 && actor.rangedBow()
                .getShots() > 0 && target.getHealth() < 200) {
                    f.check();
                    helper.assertTrue(
                        f.maid.getOffhandItem().stackSize == 15 && f.maid.getHeldItem()
                            .getItemDamage() == 1,
                        "Resume did not settle exactly one real shot");
                    done[0] = true;
                    System.out.println("TLMNH_RANGED112_CANCEL_PASS age=" + age + " attempts=" + hook.ages);
                }
        });
        helper.succeedWhen(() -> done[0]);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 180, batch = "modern_ranged_ride")
    public static void actualMinecartKeepsRideShootWithoutGroundWalkOrStrafe(GameTestHelper helper) {
        var f = fixture(helper);
        var target = target(helper, f, 9.5);
        var cart = new EntityMinecartEmpty(helper.getWorld());
        // 原生矿车坐高为-0.3，出生先留出净空，落地后按真实碰撞确定乘客高度
        helper.spawnEntity(cart, 1.5, 1.4, 4.5);
        helper.afterTest(() -> {
            f.maid.mountEntity(null);
            cart.setDead();
        });
        f.maid.mountEntity(cart);
        var hook = hook(helper, f);
        boolean[] done = { false };
        helper.onEachTick("real minecart and source ride shoot behavior", () -> {
            f.check();
            if (f.core.getActor()
                .rangedBow()
                .getShots() >= 2 && target.getHealth() < 200) {
                helper.assertTrue(
                    f.maid.ridingEntity == cart && f.navigation.noPath()
                        && !f.core.getActor()
                            .hasRangedWalkTarget()
                        && f.maid.moveStrafing == 0,
                    "Ride task dismounted or installed ground movement");
                helper.assertTrue(
                    f.maid.getHeldItem()
                        .getItemDamage() == 2 && f.maid.getOffhandItem().stackSize == 14,
                    "Mounted release bypassed resource settlement");
                done[0] = true;
                System.out.println("TLMNH_RANGED112_RIDE_PASS age=" + f.maid.ticksExisted + " shots=" + hook.ages);
            }
        });
        helper.succeedWhen(() -> done[0]);
    }

    private static ModernCoreTests.Fixture fixture(GameTestHelper helper) {
        var f = new ModernCoreTests.Fixture(helper, false, 1, 24);
        f.maid.setSchedule(MaidSchedule.ALL);
        f.maid.setTask(MaidTask.RANGED_ATTACK);
        f.maid.setCurrentItemOrArmor(0, new ItemStack(Items.bow));
        f.maid.setOffhandItem(new ItemStack(Items.arrow, 16));
        f.maid.getMaidInventory()
            .setInventorySlotContents(0, new ItemStack(Items.arrow, 8));
        for (int x = 1; x < 20; x++) for (int z = 1; z < 8; z++) helper.setBlock(x, 0, z, Blocks.stone);
        return f;
    }

    private static EntityPig target(GameTestHelper helper, ModernCoreTests.Fixture f, double x) {
        var pig = new EntityPig(helper.getWorld());
        pig.getEntityAttribute(SharedMonsterAttributes.maxHealth)
            .setBaseValue(200);
        pig.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
            .setBaseValue(0);
        pig.getEntityAttribute(SharedMonsterAttributes.knockbackResistance)
            .setBaseValue(1);
        pig.setHealth(200);
        helper.spawnEntity(pig, x, 1, 4.5);
        helper.afterTest(pig::setDead);
        f.maid.getAttackListData()
            .attackGroups()
            .put(MaidCombatEntities.id(pig), MonsterType.HOSTILE);
        f.owner.setPosition(f.x + x, f.y + 1, f.z + 5.5);
        return pig;
    }

    private static ArrowHook hook(GameTestHelper helper, ModernCoreTests.Fixture f) {
        var hook = new ArrowHook(f);
        MinecraftForge.EVENT_BUS.register(hook);
        helper.afterTest(() -> {
            MinecraftForge.EVENT_BUS.unregister(hook);
            for (var arrow : hook.arrows) arrow.setDead();
        });
        return hook;
    }

    public static final class ArrowHook {

        private final ModernCoreTests.Fixture f;
        final List<Integer> ages = new ArrayList<>();
        final List<EntityArrow> arrows = new ArrayList<>();
        boolean revoke;
        float firstDistance;

        ArrowHook(ModernCoreTests.Fixture f) {
            this.f = f;
        }

        @SubscribeEvent
        public void spawn(EntityJoinWorldEvent event) {
            if (!(event.entity instanceof EntityArrow arrow) || arrow.shootingEntity != f.maid) return;
            if (ages.isEmpty()) firstDistance = f.maid.getDistanceToEntity(f.maid.getAttackTarget());
            ages.add(f.maid.ticksExisted);
            arrows.add(arrow);
            if (revoke) f.maid.getBrainCoordinator()
                .setSuspended(true);
        }
    }
}
