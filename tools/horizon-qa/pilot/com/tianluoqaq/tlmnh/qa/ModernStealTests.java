package com.tianluoqaq.tlmnh.qa;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.BlockEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidStealEdibleUseTask;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.edible.MaidEdibleBlockAction;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventory;
import com.tianluoqaq.tlmnh.common.registry.MaidLifeContent;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class ModernStealTests {

    private ModernStealTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 2300, batch = "modern_steal_natural")
    public static void naturalStealPlaceStealKeepsNineHundredTickSearchDelayAndOneCakePayment(GameTestHelper helper) {
        var f = fixture(helper, true);
        helper.setBlock(5, 1, 4, MaidLifeContent.snackCabinet);
        helper.setBlock(5, 2, 4, Blocks.cake, 5);
        f.maid.getMaidInventory()
            .setInventorySlotContents(0, new ItemStack(Items.cake));
        int[] first = { -1 }, placed = { -1 };
        boolean[] route = { false }, done = { false };
        helper.onEachTick("natural original two-stage tasks with actual full search cooldowns", () -> {
            f.check();
            int age = f.maid.ticksExisted;
            var actor = f.core.getActor();

            if (!f.navigation.noPath()) route[0] = true;
            if (first[0] < 0 && actor.cakeBites() == 1) {
                helper.assertTrue(route[0] && f.maid.posX > f.x + 2, "First bite bypassed the real modern route");
                helper.assertTrue(
                    helper.getWorld()
                        .getBlock(f.x + 5, f.y + 2, f.z + 4) == Blocks.air && actor.workTarget() == null,
                    "Target-native sixth bite did not remove cake and clear work memory");
                helper.assertTrue(
                    actor.edibleAction() == MaidEdibleBlockAction.TRY_STEAL,
                    "Initial action was not steal");
                first[0] = age;
            }
            if (first[0] >= 0 && placed[0] < 0) {
                if (age - first[0] <= 900)
                    helper.assertTrue(actor.cakePlacements() == 0, "Successful search cooldown shortened");
                if (actor.cakePlacements() == 1) {
                    helper.assertTrue(
                        age - first[0] >= 901 && helper.getWorld()
                            .getBlock(f.x + 5, f.y + 2, f.z + 4) == Blocks.cake
                            && helper.getWorld()
                                .getBlockMetadata(f.x + 5, f.y + 2, f.z + 4) == 0
                            && f.maid.getMaidInventory()
                                .getStackInSlot(0) == null
                            && actor.edibleAction() == MaidEdibleBlockAction.TRY_PLACE,
                        "Placement did not consume exactly one real backpack cake after the full interval");
                    placed[0] = age;
                }
            }
            if (placed[0] >= 0 && actor.cakeBites() == 2) {
                helper.assertTrue(
                    age - placed[0] >= 901 && actor.cakePlacements() == 1
                        && helper.getWorld()
                            .getBlockMetadata(f.x + 5, f.y + 2, f.z + 4) == 1
                        && actor.edibleAction() == MaidEdibleBlockAction.TRY_STEAL
                        && actor.workTarget() == null
                        && actor.peekWalkTarget() == null,
                    "Place/steal alternation, second cooldown or native portions changed");
                done[0] = true;
                System.out
                    .println("TLMNH_STEAL109_NATURAL_PASS first=" + first[0] + " placed=" + placed[0] + " tick=" + age);
            }
        });
        helper.succeedWhen(() -> done[0]);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 100, batch = "modern_steal_memory")
    public static void changedWalkClearsFarWorkMemoryAndCancelRejectsOldControlWithStrictOwnerBoundary(
        GameTestHelper helper) {
        var f = fixture(helper, true);
        helper.setBlock(5, 1, 4, MaidLifeContent.snackCabinet);
        helper.setBlock(5, 2, 4, Blocks.cake);
        int[] phase = { 0 }, changed = { 0 };
        boolean[] done = { false };
        helper.onEachTick("live work target replacement, cancellation and owner block-center scope", () -> {
            f.check();
            var actor = f.core.getActor();
            int age = f.maid.ticksExisted;

            if (phase[0] == 0 && age >= 3 && !f.navigation.noPath() && actor.workTarget() != null) {
                helper.assertTrue(
                    actor.distanceToBlockCenterSquared(actor.workTarget()) > 4,
                    "Work replacement was already in use range");
                helper.setBlock(5, 1, 4, Blocks.stone);
                actor.setPositionWalkAndLook(new Point(f.x + 12, f.y + 1, f.z + 4), .6F, 0);
                changed[0] = age;
                phase[0] = 1;
            } else if (phase[0] == 1 && age > changed[0]) {
                helper.assertTrue(
                    actor.workTarget() == null && actor.cakeBites() == 0,
                    "Changed WALK left stale far work memory or ate off a removed cabinet");
                var food = new Point(f.x + 5, f.y + 2, f.z + 4);
                f.owner.setPosition(food.x + .5 + 8, food.y + .5, food.z + .5);
                helper.assertFalse(actor.workPointNearOwner(food), "Exact eight-block owner boundary accepted");
                f.owner.setPosition(food.x + .5 + 7.999, food.y + .5, food.z + .5);
                helper.assertTrue(actor.workPointNearOwner(food), "Inside owner boundary rejected");
                f.maid.getBrainCoordinator()
                    .setSuspended(true);
                helper.assertTrue(
                    actor.peekWalkTarget() == null && f.navigation.noPath(),
                    "Cancelled work retained route");
                boolean expired = false;
                try {
                    actor.consumeCake(food);
                } catch (IllegalStateException expected) {
                    expired = true;
                }
                helper.assertTrue(expired && actor.cakeBites() == 0, "Retired actor could still mutate food");
                done[0] = true;
                System.out.println("TLMNH_STEAL109_MEMORY_PASS tick=" + age);
            }
        });
        helper.succeedWhen(() -> done[0]);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 65, batch = "modern_steal_event")
    public static void realPlaceEventCancellationAndPolicyRevocationKeepHandsAndBackpackThenFreshPaymentSucceeds(
        GameTestHelper helper) {
        var f = fixture(helper);
        f.maid.setSitting(true); // 隔离使用边界，自动RIDE_WORK不注册偷吃搜索
        helper.setBlock(2, 1, 4, MaidLifeContent.snackCabinet);
        var inv = f.maid.getMaidInventory();
        var main = new ItemStack(Items.cake);
        var off = new ItemStack(Items.cake);
        var bag = new ItemStack(Items.cake);
        inv.setInventorySlotContents(MaidInventory.MAIN_HAND, main);
        inv.setInventorySlotContents(MaidInventory.OFF_HAND, off);
        inv.setInventorySlotContents(0, bag);
        var hook = new PlaceHook(f);
        MinecraftForge.EVENT_BUS.register(hook);
        helper.afterTest(() -> MinecraftForge.EVENT_BUS.unregister(hook));
        var use = new MaidStealEdibleUseTask(2);
        var food = new Point(f.x + 2, f.y + 2, f.z + 4);
        int[] phase = { 0 }, changed = { 0 };
        boolean[] done = { false };
        helper.onEachTick("real Forge placement callback and source hand-first inventory payment", () -> {
            if (phase[0] != 2) f.check();
            int age = f.maid.ticksExisted;
            var actor = f.core.getActor();
            if (phase[0] == 0 && age >= 3) {
                hook.mode = 1;
                prepare(actor, food);
                helper
                    .assertTrue(use.checkExtraStartConditions(actor), "Near use fixture is not within source distance");
                use.start(actor);
                helper.assertTrue(
                    hook.events == 1 && helper.getWorld()
                        .getBlock(f.x + 2, f.y + 2, f.z + 4) == Blocks.air
                        && actor.cakePlacements() == 0
                        && inv.getStackInSlot(MaidInventory.MAIN_HAND) == main
                        && main.stackSize == 1
                        && inv.getStackInSlot(MaidInventory.OFF_HAND) == off
                        && inv.getStackInSlot(0) == bag,
                    "Cancelled PlaceEvent changed finite resources");
                changed[0] = age;
                phase[0] = 1;
            } else if (phase[0] == 1 && age > changed[0]) {
                hook.mode = 2;
                prepare(actor, food);
                use.start(actor);
                helper.assertTrue(
                    hook.events == 2 && helper.getWorld()
                        .getBlock(f.x + 2, f.y + 2, f.z + 4) == Blocks.air
                        && actor.cakePlacements() == 0
                        && main.stackSize == 1
                        && inv.getStackInSlot(MaidInventory.MAIN_HAND) == main,
                    "Revoked policy paid or left an unpaid native cake");
                changed[0] = age;
                phase[0] = 2;
            } else if (phase[0] == 2 && age > changed[0]) {
                hook.mode = 0;
                f.maid.getBrainCoordinator()
                    .setSuspended(false);
                changed[0] = age;
                phase[0] = 3;
            } else if (phase[0] == 3 && age > changed[0]) {
                prepare(actor, food);
                use.start(actor);
                helper.assertTrue(
                    hook.events == 3 && helper.getWorld()
                        .getBlock(f.x + 2, f.y + 2, f.z + 4) == Blocks.cake
                        && actor.cakePlacements() == 1
                        && inv.getStackInSlot(MaidInventory.MAIN_HAND) == null
                        && inv.getStackInSlot(MaidInventory.OFF_HAND) == off
                        && off.stackSize == 1
                        && inv.getStackInSlot(0) == bag
                        && bag.stackSize == 1,
                    "Fresh native placement did not pay exactly the first source hand");
                done[0] = true;
                System.out.println("TLMNH_STEAL109_EVENT_PASS tick=" + age);
            }
        });
        helper.succeedWhen(() -> done[0]);
    }

    public static final class PlaceHook {

        private final ModernCoreTests.Fixture f;
        int mode, events;

        PlaceHook(ModernCoreTests.Fixture f) {
            this.f = f;
        }

        @SubscribeEvent
        public void placing(BlockEvent.PlaceEvent event) {
            if (event.world != f.maid.worldObj || event.x != f.x + 2 || event.y != f.y + 2 || event.z != f.z + 4)
                return;
            events++;
            if (mode == 1) event.setCanceled(true);
            else if (mode == 2) f.maid.getBrainCoordinator()
                .setSuspended(true);
        }
    }

    private static void prepare(com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor actor, Point food) {
        actor.setEdibleAction(MaidEdibleBlockAction.TRY_PLACE);
        actor.setWorkTarget(food);
    }

    private static ModernCoreTests.Fixture fixture(GameTestHelper helper) {
        return fixture(helper, false);
    }

    private static ModernCoreTests.Fixture fixture(GameTestHelper helper, boolean settleBeforeStanding) {
        var f = new ModernCoreTests.Fixture(helper, false, 1, 16);
        f.maid.setSchedule(MaidSchedule.ALL);
        f.owner.setPosition(f.x + 3.5, f.y + 1, f.z + 1.5);
        for (int x = 1; x < 20; x++) for (int z = 1; z < 8; z++) helper.setBlock(x, 0, z, Blocks.stone);
        if (settleBeforeStanding) {
            f.maid.setSitting(true);
            helper.onEachTick("settle on the real floor before natural standing command", () -> {
                if (f.maid.ticksExisted == 5) {
                    helper.assertTrue(
                        f.maid.onGround && f.maid.posY == f.y + 1,
                        "Natural fixture did not actually settle on the solid floor");
                    f.maid.setSitting(false);
                    System.out.println("TLMNH_STEAL109_REAL_STAND tick=5 onGround=" + f.maid.onGround);
                }
            });
        }
        return f;
    }

}
