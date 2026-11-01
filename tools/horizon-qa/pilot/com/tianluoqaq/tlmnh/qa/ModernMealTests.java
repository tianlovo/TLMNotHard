package com.tianluoqaq.tlmnh.qa;

import java.util.Random;

import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidRequestItemEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.favorability.Type;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidActivityState.Activity;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyContext.Token;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.combat.MaidItemUseState;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventory;
import com.tianluoqaq.tlmnh.common.life.meal.MaidFoodProfile;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class ModernMealTests {

    private ModernMealTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 65, batch = "modern_meal_hand_ride")
    public static void healingAndRideWorkMealShareMainHandUseAndReturnOneContainer(GameTestHelper helper)
        throws ReflectiveOperationException {
        var f = fixture(helper, 10000);
        var food = new ItemStack(Items.mushroom_stew);
        var nutrition = MaidFoodProfile.inspect(food);
        helper.assertTrue(nutrition != null && nutrition.total > 0, "Actual supported soup profile required");
        f.maid.getMaidInventory()
            .setInventorySlotContents(MaidInventory.MAIN_HAND, food);
        f.maid.getMaidInventory()
            .setInventorySlotContents(MaidInventory.OFF_HAND, new ItemStack(Items.stick));
        f.maid.getMaidInventory()
            .setInventorySlotContents(0, new ItemStack(Items.cooked_beef, 2));
        f.maid.setHealth(14);
        // 原生船在干地上的负骑乘偏移会把乘客脚放进地板，使用真实封闭水池而非改身体碰撞断言
        for (int x = 2; x <= 7; x++) for (int z = 2; z <= 7; z++) for (int y = 1; y <= 3; y++)
            helper.setBlock(x, y, z, x == 2 || x == 7 || z == 2 || z == 7 ? Blocks.glass : Blocks.water);
        // GTNH的EFR会取消旧EntityBoat生成并替换，直接使用本整包已安装的新船
        var boat = (Entity) Class.forName("ganymedes01.etfuturum.entities.EntityNewBoat")
            .getConstructor(World.class)
            .newInstance(helper.getWorld());
        helper.spawnEntity(boat, 4.5, 4, 4.5);
        f.maid.setPosition(f.x + 4.5, f.y + 4, f.z + 4.5);
        f.maid.mountEntity(boat);
        helper.afterTest(() -> {
            if (f.maid.ridingEntity == boat) f.maid.mountEntity(null);
            try {
                var seat = (Entity) boat.getClass()
                    .getMethod("getSeat")
                    .invoke(boat);
                if (seat != null) {
                    seat.setDead();
                    helper.getWorld()
                        .removePlayerEntityDangerously(seat);
                }
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Owned EFR seat cleanup failed", failure);
            }
            boat.setDead();
            helper.getWorld()
                .removePlayerEntityDangerously(boat);
        });
        float healed = Math.min(20, 14 + Math.max(nutrition.total / 5, 1));
        boolean[] shared = { false }, done = { false };
        helper.onEachTick("source heal/core and ride work meal start effects with one native use", () -> {
            f.check(healed);
            int age = f.maid.ticksExisted;
            helper.assertTrue(
                f.maid.ridingEntity == boat && boat.riddenByEntity == f.maid,
                "Mounted meal fixture lost its actual riding relation");
            helper.assertTrue(count(f, Items.cooked_beef) == 2, "Backpack meal won over edible main hand");
            if (age == 1) {
                var use = f.maid.getItemUseState()
                    .peek();
                helper.assertTrue(
                    use != null && use.getHand() == MaidItemUseState.Hand.MAIN
                        && use.getKind() == MaidItemUseState.Kind.FOOD
                        && f.core.getActivities()
                            .active() == Activity.RIDE_WORK
                        && !f.maid.getFavorabilityManager()
                            .canAdd(Type.WORK_MEAL.getTypeName()),
                    "Heal and RIDE_WORK did not both apply their independent start effects");
                shared[0] = true;
            }
            if (age < nutrition.duration) helper.assertTrue(
                count(f, Items.mushroom_stew) == 1 && count(f, Items.bowl) == 0
                    && f.maid.getMealController()
                        .isEating(),
                "Shared meal consumed early or cancelled while riding");
            if (age == nutrition.duration) {
                helper.assertTrue(
                    count(f, Items.mushroom_stew) == 0 && count(f, Items.bowl) == 1
                        && count(f, Items.stick) == 1
                        && !f.maid.getMealController()
                            .isEating()
                        && f.maid.getItemUseState()
                            .peek() == null,
                    "Shared start reset the duration or duplicated/lost meal/container");
                done[0] = true;
                System.out.println("TLMNH_MEAL106_HAND_RIDE_PASS tick=" + age + " health=" + healed);
            }
        });
        helper.succeedWhen(() -> shared[0] && done[0]);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 260, batch = "modern_meal_rate_sleep")
    public static void missingHealthThresholdAndSleepKeepOriginalCheckRateThenRestoreBothHands(GameTestHelper helper) {
        var f = fixture(helper, 1);
        helper.getWorld()
            .setWorldTime(18000);
        f.maid.setHealth(19);
        var sword = new ItemStack(Items.diamond_sword);
        var stick = new ItemStack(Items.stick);
        var inv = f.maid.getMaidInventory();
        inv.setInventorySlotContents(MaidInventory.MAIN_HAND, sword);
        inv.setInventorySlotContents(MaidInventory.OFF_HAND, stick);
        inv.setInventorySlotContents(0, new ItemStack(Items.mushroom_stew));
        inv.setInventorySlotContents(1, new ItemStack(Items.rotten_flesh));
        int[] started = { -1 };
        float[] healed = { Float.NaN };
        boolean[] done = { false };
        helper.onEachTick("missing two health, sleeping, independent source check counter and backpack swap", () -> {
            int age = f.maid.ticksExisted;
            if (age <= 2) {
                f.check(19);
                helper.assertTrue(
                    !f.maid.getMealController()
                        .isEating()
                        && f.core.getActivities()
                            .active() == Activity.REST,
                    "Missing one health or REST incorrectly started a meal");
                if (age == 2) {
                    f.maid.setHealth(18);
                    f.maid.setPose(MaidPose.SLEEPING, true);
                }
                return;
            }
            if (age <= 110) {
                f.check(18);
                helper.assertTrue(
                    f.maid.hasPose(MaidPose.SLEEPING) && !f.maid.getMealController()
                        .isEating() && count(f, Items.mushroom_stew) == 1,
                    "Sleeping source check consumed food or woke itself");
                if (age == 110) f.maid.setPose(MaidPose.SLEEPING, false);
                return;
            }
            boolean eating = f.maid.getMealController()
                .isEating();
            if (started[0] < 0 && eating) {
                started[0] = age;
                healed[0] = allowedHealingHealth(f, 18);
                var use = f.maid.getItemUseState()
                    .peek();
                helper.assertTrue(
                    age <= 210 && use != null
                        && use.getHand() == MaidItemUseState.Hand.OFF
                        && inv.getStackInSlot(MaidInventory.MAIN_HAND) == sword
                        && inv.getStackInSlot(0) == stick,
                    "Delayed heal missed source check window or did not swap the fallback off hand");
            }
            f.check(started[0] < 0 ? 18 : healed[0]);
            helper.assertTrue(count(f, Items.rotten_flesh) == 1, "Blocked meal was consumed");
            if (started[0] > 0 && !eating) {
                helper.assertTrue(
                    count(f, Items.mushroom_stew) == 0 && count(f, Items.bowl) == 1
                        && inv.getStackInSlot(MaidInventory.MAIN_HAND) == sword
                        && inv.getStackInSlot(MaidInventory.OFF_HAND) == stick,
                    "Backpack completion did not restore both original hands/container");
                done[0] = true;
                System.out.println("TLMNH_MEAL106_RATE_SLEEP_PASS tick=" + age + " started=" + started[0]);
            }
        });
        helper.succeedWhen(() -> done[0]);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 85, batch = "modern_meal_request_cancel")
    public static void requestReceiptCannotCreateFoodAndCallbackCancellationPreservesTransferredInventory(
        GameTestHelper helper) {
        var f = fixture(helper, 1);
        helper.getWorld()
            .setWorldTime(12000);
        f.maid.setSitting(true);
        f.maid.setHealth(14);
        var provider = new MealSupply(f);
        MinecraftForge.EVENT_BUS.register(provider);
        helper.afterTest(() -> MinecraftForge.EVENT_BUS.unregister(provider));
        int[] phase = { 0 }, resumed = { -1 };
        float[] healed = { Float.NaN };
        boolean[] done = { false };
        helper.onEachTick("request receipt is not a stack grant, real transfer and callback cancellation", () -> {
            int age = f.maid.ticksExisted;
            if (phase[0] == 0) {
                f.check(14);
                helper.assertTrue(
                    provider.requests == 1 && count(f, Items.mushroom_stew) == 0
                        && f.maid.getItemUseState()
                            .peek() == null,
                    "Receipt-only response minted food or started use without an inventory transfer");
                provider.transfer = true;
                // 显式策略重置是输入，令下一次检查立即观察真实供给并在回调内取消
                f.maid.getBrainCoordinator()
                    .setSuspended(true);
                f.maid.getBrainCoordinator()
                    .setSuspended(false);
                phase[0] = 1;
            } else if (phase[0] == 1) {
                f.check(14);
                helper.assertTrue(
                    provider.requests == 2 && provider.reserve == 1
                        && f.maid.getBrainCoordinator()
                            .isSuspended()
                        && count(f, Items.mushroom_stew) == 1
                        && f.maid.getItemUseState()
                            .peek() == null
                        && f.navigation.noPath(),
                    "Cancelled request continued using food or lost the independently transferred inventory");
                var stick = new ItemStack(Items.stick);
                f.maid.getMaidInventory()
                    .setInventorySlotContents(MaidInventory.OFF_HAND, stick);
                var foreign = f.maid.getItemUseState()
                    .tryAcquire(new Object(), stick, MaidItemUseState.Hand.OFF, MaidItemUseState.Kind.FOOD, () -> true);
                helper.assertTrue(foreign != null, "Foreign successor use required");
                f.core.getActor()
                    .cancelOwnedResources();
                helper.assertTrue(
                    f.maid.getItemUseState()
                        .peek() == foreign && count(f, Items.mushroom_stew) == 1,
                    "Retired actor cancelled a successor or rewrote independent inventory");
                f.maid.getItemUseState()
                    .release(foreign);
                f.maid.getBrainCoordinator()
                    .setSuspended(false);
                resumed[0] = age;
                phase[0] = 2;
            } else {
                if (Float.isNaN(healed[0])) {
                    helper.assertTrue(
                        f.maid.getMealController()
                            .isEating(),
                        "Resumed source meal did not start");
                    healed[0] = allowedHealingHealth(f, 14);
                }
                f.check(healed[0]);
                helper.assertTrue(
                    provider.requests == 2 && provider.reserve == 1,
                    "Available backpack food triggered another external request");
                if (age > resumed[0] + 2 && !f.maid.getMealController()
                    .isEating()) {
                    helper.assertTrue(
                        count(f, Items.mushroom_stew) == 0 && count(f, Items.bowl) == 1 && count(f, Items.stick) == 1,
                        "Resumed source meal did not conserve supply, container and displaced hand");
                    done[0] = true;
                    System.out
                        .println("TLMNH_MEAL106_REQUEST_CANCEL_PASS tick=" + age + " requests=" + provider.requests);
                }
            }
        });
        helper.succeedWhen(() -> done[0]);
    }

    public static final class MealSupply {

        private final ModernCoreTests.Fixture f;
        int requests, reserve = 2;
        boolean transfer;

        MealSupply(ModernCoreTests.Fixture f) {
            this.f = f;
        }

        @SubscribeEvent
        public void requested(MaidRequestItemEvent event) {
            if (event.getMaid() != f.maid) return;
            requests++;
            f.helper.assertTrue(
                event.getMaxCount() == -1 && event.getItemFilter()
                    .test(new ItemStack(Items.mushroom_stew))
                    && !event.getItemFilter()
                        .test(new ItemStack(Items.rotten_flesh)),
                "Request filter/count differs");
            var supplied = new ItemStack(Items.mushroom_stew);
            event.setRequestedItem(supplied.copy());
            event.setCanceled(true);
            if (transfer) {
                f.helper.assertTrue(
                    reserve > 0 && f.maid.getMaidInventory()
                        .insertAvailable(supplied, false) == null,
                    "Controlled supply did not transfer one real item");
                reserve--;
                f.maid.getBrainCoordinator()
                    .setSuspended(true);
            }
        }
    }

    private static ModernCoreTests.Fixture fixture(GameTestHelper helper, double displacement) {
        var f = new ModernCoreTests.Fixture(helper, false, displacement, 16);
        f.maid.setSchedule(MaidSchedule.DAY);
        f.owner.setPosition(f.x + 4.5, f.y + 1, f.z + 1.5);
        // 固定真实RNG输入以区别meal开始回血与全实体每tick随机自愈，不改营养或生产概率
        long seed = 0;
        while (true) {
            var expected = new Random(seed);
            expected.nextInt(50);
            if (expected.nextInt(5) == 0) break;
            seed++;
        }
        final long mealSeed = seed;
        System.out.println("TLMNH_MEAL106_RNG_INPUT mealSeed=" + mealSeed + " postBrainSelfCareSeed=0");
        f.maid.getBrainCoordinator()
            .setAfterNavigationActionsForDevelopment(new MaidPolicyRuntime.AfterNavigationActions() {

                public void tick(Token token) {
                    f.maid.getRNG()
                        .setSeed(mealSeed);
                    f.core.tick(token);
                    f.maid.getRNG()
                        .setSeed(0);
                }

                public void afterLivingUpdate(Token token) {
                    f.core.afterLivingUpdate(token);
                }

                public void cancel() {
                    f.core.cancel();
                }
            });
        return f;
    }

    private static float allowedHealingHealth(ModernCoreTests.Fixture f, float before) {
        var profile = MaidFoodProfile.inspect(new ItemStack(Items.mushroom_stew));
        f.helper.assertTrue(profile != null, "Supported actual soup profile required");
        float after = Math.min(f.maid.getMaxHealth(), before + Math.max(profile.total / 5, 1));
        float actual = f.maid.getHealth();
        f.helper.assertTrue(
            actual == after || profile.total < 5 && actual == before,
            "Meal health did not match one of the original nutrition/probability outcomes");
        System.out.println("TLMNH_MEAL106_SOUP_ROLL total=" + profile.total + " before=" + before + " after=" + actual);
        return actual;
    }

    private static int count(ModernCoreTests.Fixture f, Item item) {
        int result = 0;
        var inv = f.maid.getMaidInventory();
        for (int slot = 0; slot < inv.getSizeInventory(); slot++) {
            var stack = inv.getStackInSlot(slot);
            if (stack != null && stack.getItem() == item) result += stack.stackSize;
        }
        return result;
    }
}
