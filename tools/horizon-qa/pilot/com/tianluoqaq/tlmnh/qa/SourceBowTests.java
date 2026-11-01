package com.tianluoqaq.tlmnh.qa;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.SharedMonsterAttributes;
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
import com.tianluoqaq.tlmnh.common.combat.MaidBowAttack.ModernFlightProfile;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatEntities;
import com.tianluoqaq.tlmnh.common.combat.MaidGravityFreeArrow;
import com.tianluoqaq.tlmnh.common.combat.SourceBowShot;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class SourceBowTests {

    private SourceBowTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 30, batch = "source_bow_physics")
    public static void sourceArrowChangesOnlyItsGravityAndWaterDrag(GameTestHelper helper) {
        var world = helper.getWorld();
        EntityArrow[] arrows = { new MaidGravityFreeArrow(world), new EntityArrow(world),
            new MaidGravityFreeArrow(world), new EntityArrow(world) };
        for (int x = 1; x < 12; x++) for (int y = 2; y <= 6; y++) {
            helper.setBlock(x, y, 5, Blocks.water);
            helper.setBlock(x, y, 7, Blocks.water);
        }
        for (int i = 0; i < arrows.length; i++) {
            var arrow = arrows[i];
            helper.spawnEntity(arrow, 2.5, i < 2 ? 7 : 4, 1.5 + 2 * i);
            arrow.motionX = .4;
            arrow.motionY = .2;
            arrow.motionZ = .1;
            helper.afterTest(arrow::setDead);
        }
        double[][] previous = new double[4][];
        int[] samples = { 0 };
        helper.onEachTick("real air/water source arrows and unchanged native arrows", () -> {
            if (arrows[0].ticksExisted < 1) return;
            for (int i = 0; i < arrows.length; i++) {
                var arrow = arrows[i];
                helper.assertTrue(!arrow.isDead && arrow.isInWater() == (i >= 2), "Arrow left its physical medium");
                var before = previous[i];
                if (before != null) {
                    double drag = (double) (i < 2 ? .99F : i == 2 ? .6F : .8F);
                    double gravity = i % 2 == 0 ? 0 : (double) .05F;
                    helper.assertTrue(
                        Math.abs(arrow.posX - before[0] - before[3]) < 1E-10
                            && Math.abs(arrow.posY - before[1] - before[4]) < 1E-10
                            && Math.abs(arrow.posZ - before[2] - before[5]) < 1E-10
                            && Math.abs(arrow.motionX - before[3] * drag) < 1E-12
                            && Math.abs(arrow.motionY - (before[4] * drag - gravity)) < 1E-12
                            && Math.abs(arrow.motionZ - before[5] * drag) < 1E-12,
                        "Actual source/native arrow update order, gravity or drag differs for arrow " + i);
                }
                previous[i] = new double[] { arrow.posX, arrow.posY, arrow.posZ, arrow.motionX, arrow.motionY,
                    arrow.motionZ };
            }
            samples[0]++;
        });
        helper.succeedWhen(() -> samples[0] >= 4);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 210, batch = "source_bow_natural")
    public static void naturalSourceProfileUsesOriginalLaunchAndFiniteInfinityResources(GameTestHelper helper) {
        var f = new ModernCoreTests.Fixture(helper, false, 1, 24);
        f.maid.setSchedule(MaidSchedule.ALL);
        f.maid.setTask(MaidTask.RANGED_ATTACK);
        var finiteBow = new ItemStack(Items.bow);
        f.maid.setCurrentItemOrArmor(0, finiteBow);
        f.maid.setOffhandItem(new ItemStack(Items.arrow, 16));
        f.maid.getMaidInventory()
            .setInventorySlotContents(0, new ItemStack(Items.arrow, 8));
        f.core.getActor()
            .rangedBow()
            .setFlightProfileForDevelopment(ModernFlightProfile.SOURCE_ORIGINAL);
        for (int x = 1; x < 20; x++) for (int z = 1; z < 8; z++) helper.setBlock(x, 0, z, Blocks.stone);
        var pig = new EntityPig(helper.getWorld());
        pig.getEntityAttribute(SharedMonsterAttributes.maxHealth)
            .setBaseValue(200);
        pig.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
            .setBaseValue(0);
        pig.getEntityAttribute(SharedMonsterAttributes.knockbackResistance)
            .setBaseValue(1);
        pig.setHealth(200);
        helper.spawnEntity(pig, 17.5, 1, 4.5);
        helper.afterTest(pig::setDead);
        f.owner.setPosition(f.x + 17.5, f.y + 1, f.z + 5.5);
        f.maid.getAttackListData()
            .attackGroups()
            .put(MaidCombatEntities.id(pig), MonsterType.HOSTILE);
        var hook = new SourceHook(helper, f, pig);
        MinecraftForge.EVENT_BUS.register(hook);
        helper.afterTest(() -> {
            MinecraftForge.EVENT_BUS.unregister(hook);
            for (var arrow : hook.arrows) arrow.setDead();
        });
        var infiniteBow = new ItemStack(Items.bow);
        infiniteBow.addEnchantment(Enchantment.infinity, 1);
        infiniteBow.addEnchantment(Enchantment.power, 2);
        infiniteBow.addEnchantment(Enchantment.flame, 1);
        boolean[] infinite = { false }, done = { false };
        helper.onEachTick("natural Source launch, original noise/damage and actual resource settlement", () -> {
            f.check();
            var bow = f.core.getActor()
                .rangedBow();
            if (!infinite[0] && bow.getShots() >= 2) {
                helper.assertTrue(
                    finiteBow.getItemDamage() == 2 && f.maid.getOffhandItem().stackSize == 14
                        && f.maid.getMaidInventory()
                            .getStackInSlot(0).stackSize == 8,
                    "Finite source releases did not settle hand-first resources exactly");
                f.maid.setCurrentItemOrArmor(0, infiniteBow);
                infinite[0] = true;
            }
            if (infinite[0] && bow.getShots() >= 3 && pig.getHealth() < 200) {
                helper.assertTrue(
                    hook.arrows.size() == 3 && f.maid.getOffhandItem().stackSize == 14
                        && infiniteBow.getItemDamage() == 1
                        && finiteBow.getItemDamage() == 2
                        && bow.getLastSolution() == null,
                    "Infinity source release consumed ammunition or used a ballistic solver");
                helper.assertTrue(
                    hook.arrows.get(0).canBePickedUp == 1 && hook.arrows.get(2).canBePickedUp == 0
                        && hook.arrows.get(2)
                            .isBurning(),
                    "Source finite/Infinity pickup or Flame differs");
                done[0] = true;
                System.out
                    .println("TLMNH_SOURCE116_NATURAL_PASS age=" + f.maid.ticksExisted + " releases=" + hook.ages);
            }
        });
        helper.succeedWhen(() -> done[0]);
    }

    public static final class SourceHook {

        final GameTestHelper helper;
        final ModernCoreTests.Fixture fixture;
        final EntityPig target;
        final List<EntityArrow> arrows = new ArrayList<>();
        final List<Integer> ages = new ArrayList<>();

        SourceHook(GameTestHelper helper, ModernCoreTests.Fixture fixture, EntityPig target) {
            this.helper = helper;
            this.fixture = fixture;
            this.target = target;
        }

        @SubscribeEvent
        public void spawn(EntityJoinWorldEvent event) {
            if (!(event.entity instanceof EntityArrow arrow) || arrow.shootingEntity != fixture.maid) return;
            float distance = fixture.maid.getDistanceToEntity(target);
            double nominal = SourceBowShot.speed(distance);
            double speed = Math
                .sqrt(arrow.motionX * arrow.motionX + arrow.motionY * arrow.motionY + arrow.motionZ * arrow.motionZ);
            double attack = fixture.maid.getEntityAttribute(SharedMonsterAttributes.attackDamage)
                .getBaseValue();
            double power = arrows.size() < 2 ? 0 : 1.5;
            double mean = (2 + fixture.maid.worldObj.difficultySetting.getDifficultyId() * .11 + power)
                * (float) (attack / 2);
            helper.assertTrue(
                arrow instanceof MaidGravityFreeArrow && !arrow.getIsCritical()
                    && Math.abs(speed - nominal) < nominal * .031
                    && Math.abs(arrow.getDamage() - mean) <= .57425 * (float) (attack / 2)
                    && arrow.posY == fixture.maid.posY + fixture.maid.getEyeHeight() - (double) .1F,
                "Source launch used native fixed speed, wrong origin, critical bonus or base damage");
            arrows.add(arrow);
            ages.add(fixture.maid.ticksExisted);
        }
    }
}
