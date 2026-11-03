package com.tianluoqaq.tlmnh.qa;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import net.minecraft.entity.Entity;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.DamageSource;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.misc.DefaultMonsterType;
import com.github.tartaricacid.touhoulittlemaid.entity.misc.MonsterType;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.ai.enhanced.EnhancedCoreBrain;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidIntentFrame.Kind;
import com.tianluoqaq.tlmnh.common.combat.MaidBowAttack;
import com.tianluoqaq.tlmnh.common.combat.MaidItemUseState;
import com.tianluoqaq.tlmnh.common.development.DevelopmentArtifactIdentity;
import com.tianluoqaq.tlmnh.common.development.IsolatedPlayerChannel;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventory;
import com.tianluoqaq.tlmnh.common.life.meal.MaidFoodProfile;

/** 完整工厂根的代表入口；不替换决策回调、不手动tick、不重置随机源。 */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class CompleteBehaviorEntryTests {

    private CompleteBehaviorEntryTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 330, batch = "complete_entry_f_meal_nbt")
    public static void faithfulNativeNbtQueriesPreserveNaturalHandAndBackpackMealCompletion(GameTestHelper h)
        throws Exception {
        var s = new Scene(h, "faithful-meal-native-nbt", MaidBrainProfile.FAITHFUL_TLM);
        var f = s.f;
        var inv = f.maid.getMaidInventory();
        f.maid.setSitting(true);
        f.maid.setHealth(10);
        var main = new ItemStack(Items.iron_sword);
        main.setStackDisplayName("native-query-main");
        var off = new ItemStack(Items.diamond, 2);
        off.setStackDisplayName("native-query-displaced");
        ItemStack[] food = { new ItemStack(Items.mushroom_stew) };
        food[0].setStackDisplayName("native-query-soup-0");
        var nutrition = MaidFoodProfile.inspect(food[0]);
        h.assertTrue(nutrition != null && nutrition.duration > 1, "Actual native soup profile required");
        inv.setInventorySlotContents(MaidInventory.MAIN_HAND, main);
        inv.setInventorySlotContents(MaidInventory.OFF_HAND, food[0]);
        s.report
            .addProperty("mealInput", "natural full F; direct offhand, borrowed empty main, borrowed occupied offhand");
        s.report.addProperty(
            "serializationScope",
            "two actual native writes per active-use sample; unregistered reload copies only inspect saved logical slots");
        s.report.addProperty("actualUseDuration", nutrition.duration);
        s.bindInputs();
        s.report.add(
            "mealArtifact",
            DevelopmentArtifactIdentity.capture(
                com.tianluoqaq.tlmnh.common.life.meal.MaidMealController.class,
                MaidItemUseState.class,
                MaidInventory.class,
                com.tianluoqaq.tlmnh.common.protection.ProtectedItemCodec.class));
        int[] phase = { 0 }, started = { -1 }, finished = { -1 }, writes = { 0 };
        MaidItemUseState.Lease[] owned = { null };
        h.succeedWhen(() -> {
            var row = s.sample(phase[0]);
            int age = f.maid.ticksExisted;
            row.addProperty("soup", count(f, Items.mushroom_stew));
            row.addProperty("bowls", count(f, Items.bowl));
            if (phase[0] == 3) {
                h.assertTrue(
                    count(f, Items.mushroom_stew) == 0 && count(f, Items.bowl) == 3
                        && inv.getStackInSlot(MaidInventory.MAIN_HAND) == main
                        && inv.getStackInSlot(MaidInventory.OFF_HAND) == off,
                    "Later natural tick repeated settlement or changed either restored hand");
                return s.finishWhen(age >= finished[0] + 4);
            }
            if (f.maid.getMealController()
                .isEating()) {
                if (started[0] < 0) {
                    started[0] = age;
                    owned[0] = f.maid.getItemUseState()
                        .peek();
                    s.event("natural meal started, phase=" + phase[0]);
                }
                int hand = phase[0] == 1 ? MaidInventory.MAIN_HAND : MaidInventory.OFF_HAND;
                h.assertTrue(
                    inv.getStackInSlot(hand) == food[0] && count(f, Items.bowl) == phase[0],
                    "Natural hand use consumed early or replaced its finite food");
                int used = f.maid.getItemUseState()
                    .getUsedTicks();
                float health = f.maid.getHealth();
                for (int copy = 0; copy < 2; copy++) {
                    var saved = new NBTTagCompound();
                    f.maid.writeToNBT(saved);
                    writes[0]++;
                    h.assertTrue(
                        f.maid.getMealController()
                            .isEating()
                            && f.maid.getItemUseState()
                                .peek() == owned[0]
                            && f.maid.getItemUseState()
                                .getUsedTicks() == used
                            && f.maid.hasPose(MaidPose.USING_ITEM)
                            && f.maid.hasPose(MaidPose.USING_OFFHAND) == (hand == MaidInventory.OFF_HAND)
                            && f.maid.getHealth() == health
                            && inv.getStackInSlot(hand) == food[0]
                            && (phase[0] == 0 || inv.getStackInSlot(0) == (phase[0] == 1 ? null : off)),
                        "Native NBT query changed live lease, timer, pose, health or borrowed inventory");
                    if (age == started[0] && copy == 0) {
                        s.event("native serialized logical view, phase=" + phase[0])
                            .add("typedNBT", SleepAttributePortSnapshot.binaryNbt((NBTTagCompound) saved.copy()));
                        var restored = new com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid(
                            h.getWorld());
                        restored.readFromNBT(saved);
                        var loaded = restored.getMaidInventory();
                        h.assertTrue(
                            h.getWorld()
                                .getEntityByID(restored.getEntityId()) != restored
                                && restored.getItemUseState()
                                    .peek() == null,
                            "Reload-copy inspection must not create another updating actor or use lease");
                        h.assertTrue(
                            ItemStack.areItemStacksEqual(loaded.getStackInSlot(phase[0] == 0 ? hand : 0), food[0])
                                && ItemStack.areItemStacksEqual(
                                    loaded.getStackInSlot(MaidInventory.MAIN_HAND),
                                    phase[0] == 1 ? null : main)
                                && ItemStack.areItemStacksEqual(
                                    loaded.getStackInSlot(MaidInventory.OFF_HAND),
                                    phase[0] == 0 ? food[0] : off),
                            "Reloaded snapshot failed complete logical food/source/hand restoration");
                        restored.getBrainCoordinator()
                            .setSuspended(true);
                    }
                }
                row.addProperty("nativeWrites", writes[0]);
                return false;
            }
            if (started[0] < 0) return false;
            h.assertTrue(
                age - started[0] + 1 == nutrition.duration && count(f, Items.mushroom_stew) == 0
                    && count(f, Items.bowl) == phase[0] + 1
                    && f.maid.getItemUseState()
                        .peek() == null
                    && inv.getStackInSlot(MaidInventory.MAIN_HAND) == (phase[0] == 1 ? null : main)
                    && inv.getStackInSlot(MaidInventory.OFF_HAND) == (phase[0] == 0 ? null : off),
                "Native queries interrupted natural duration or finite food/container settlement");
            s.event("natural meal completed once, phase=" + phase[0]);
            phase[0]++;
            if (phase[0] == 3) {
                finished[0] = age;
                s.report.addProperty("nativeWrites", writes[0]);
                return false;
            }
            // 改变下一次真实库存输入，既有碗只换槽保存，不重置根或任何随机/检查计数器
            inv.setInventorySlotContents(phase[0], inv.getStackInSlot(0));
            if (phase[0] == 1) {
                inv.setInventorySlotContents(3, main);
                inv.setInventorySlotContents(MaidInventory.MAIN_HAND, null);
                inv.setInventorySlotContents(MaidInventory.OFF_HAND, off);
            } else {
                inv.setInventorySlotContents(MaidInventory.MAIN_HAND, inv.getStackInSlot(3));
                inv.setInventorySlotContents(3, null);
            }
            food[0] = new ItemStack(Items.mushroom_stew);
            food[0].setStackDisplayName("native-query-soup-" + phase[0]);
            inv.setInventorySlotContents(0, food[0]);
            f.maid.setHealth(10);
            started[0] = -1;
            owned[0] = null;
            s.event("next finite meal input prepared, phase=" + phase[0]);
            return false;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 150, batch = "complete_entry_f_meal")
    public static void faithfulMealInventoryCancellationPreservesFoodThenNaturallyFinishesOneContainer(GameTestHelper h)
        throws Exception {
        var s = new Scene(h, "faithful-meal", MaidBrainProfile.FAITHFUL_TLM);
        var f = s.f;
        f.maid.setSitting(true);
        f.maid.setHealth(10);
        var main = new ItemStack(Items.stick, 3);
        main.setStackDisplayName("193-main");
        var off = new ItemStack(Items.diamond, 2);
        off.setStackDisplayName("193-off");
        var food = new ItemStack(Items.mushroom_stew);
        food.setStackDisplayName("193-one-real-soup");
        var foodBefore = food.copy();
        var profile = MaidFoodProfile.inspect(food);
        h.assertTrue(
            profile != null && profile.total > 0 && profile.duration > 1,
            "Actual soup nutrition and native use duration required");
        var inv = f.maid.getMaidInventory();
        inv.setInventorySlotContents(MaidInventory.MAIN_HAND, main);
        inv.setInventorySlotContents(MaidInventory.OFF_HAND, off);
        inv.setInventorySlotContents(0, food);
        s.report.addProperty("mealInput", "sitting=true, ALL schedule, health=10, one backpack soup, occupied hands");
        s.report.addProperty("actualNutrition", profile.total);
        s.report.addProperty("actualUseDuration", profile.duration);
        s.report.addProperty(
            "healthVerdict",
            "actual start/self-care rolls recorded; no fixed probabilistic outcome asserted");
        s.bindInputs();
        int[] phase = { 0 }, restarted = { -1 }, finished = { -1 };
        boolean[] cancelled = { false };
        h.succeedWhen(() -> {
            var row = s.sample(phase[0]);
            int age = f.maid.ticksExisted;
            row.addProperty("soup", count(f, Items.mushroom_stew));
            row.addProperty("bowl", count(f, Items.bowl));
            h.assertTrue(
                count(f, Items.stick) == 3 && count(f, Items.diamond) == 2,
                "Natural meal changed unrelated finite items");
            if (phase[0] == 0) {
                if (!f.maid.getMealController()
                    .isEating()) return false;
                assertMealLease(h, f, main, off, food);
                h.assertTrue(
                    count(f, Items.mushroom_stew) == 1 && count(f, Items.bowl) == 0,
                    "First natural start consumed food before its duration");
                // 菜单可交换的真实库存输入；不暂停根、不调用cancel或重置检查计数器。
                inv.setInventorySlotContents(MaidInventory.OFF_HAND, off);
                inv.setInventorySlotContents(0, food);
                s.event("controlled inventory swap returns borrowed food to backpack and restores original off hand");
                h.assertTrue(
                    !f.maid.getMealController()
                        .isEating() && inv.getStackInSlot(MaidInventory.MAIN_HAND) == main
                        && inv.getStackInSlot(MaidInventory.OFF_HAND) == off
                        && ItemStack.areItemStacksEqual(foodBefore, inv.getStackInSlot(0))
                        && count(f, Items.bowl) == 0,
                    "Inventory revocation lost food, created a container or changed either restored hand");
                phase[0] = 1;
                return false;
            }
            if (phase[0] == 1) {
                if (!f.maid.getMealController()
                    .isEating()) {
                    h.assertTrue(
                        f.maid.getItemUseState()
                            .peek() == null && inv.getStackInSlot(MaidInventory.MAIN_HAND) == main
                            && inv.getStackInSlot(MaidInventory.OFF_HAND) == off
                            && ItemStack.areItemStacksEqual(foodBefore, inv.getStackInSlot(0))
                            && count(f, Items.bowl) == 0,
                        "Natural cancellation overwrote the independent swap or consumed the revoked food");
                    cancelled[0] = true;
                    return false;
                }
                h.assertTrue(cancelled[0], "No natural cancellation boundary was observed before the later restart");
                assertMealLease(h, f, main, off, food);
                restarted[0] = age;
                phase[0] = 2;
                s.event("unchanged complete F naturally rechecks and restarts the conserved food");
                return false;
            }
            if (phase[0] == 2) {
                if (f.maid.getMealController()
                    .isEating()) {
                    h.assertTrue(
                        count(f, Items.mushroom_stew) == 1 && count(f, Items.bowl) == 0,
                        "Natural restart consumed early or duplicated a container");
                    return false;
                }
                h.assertTrue(
                    age - restarted[0] + 1 == profile.duration && count(f, Items.mushroom_stew) == 0
                        && count(f, Items.bowl) == 1
                        && inv.getStackInSlot(MaidInventory.MAIN_HAND) == main
                        && inv.getStackInSlot(MaidInventory.OFF_HAND) == off
                        && f.maid.getItemUseState()
                            .peek() == null,
                    "Natural completion changed the native duration, hands or one-container settlement");
                finished[0] = age;
                phase[0] = 3;
                s.event("one soup finished and one bowl returned");
            }
            h.assertTrue(
                count(f, Items.mushroom_stew) == 0 && count(f, Items.bowl) == 1
                    && inv.getStackInSlot(MaidInventory.MAIN_HAND) == main
                    && inv.getStackInSlot(MaidInventory.OFF_HAND) == off,
                "A later natural tick repeated food/container settlement or overwrote restored hands");
            return s.finishWhen(age >= finished[0] + 4);
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 200, batch = "complete_entry_n_combat")
    public static void enhancedNeutralOwnerDefenseCompetesNaturallyThenIdleRevokesCombat(GameTestHelper h)
        throws Exception {
        var s = new Scene(h, "enhanced-owner-defense", MaidBrainProfile.ENHANCED);
        var f = s.f;
        f.maid.setTask(MaidTask.ATTACK);
        var weapon = new ItemStack(Items.iron_sword);
        f.maid.setCurrentItemOrArmor(0, weapon);
        var attacker = pig(s, 12.5, 4.5);
        var competitor = pig(s, 10, 5.5);
        h.assertTrue(
            DefaultMonsterType.getMonsterType(attacker) == MonsterType.NEUTRAL
                && DefaultMonsterType.getMonsterType(competitor) == MonsterType.NEUTRAL
                && f.maid.getAttackListData()
                    .attackGroups()
                    .isEmpty(),
            "Owner defense requires real neutral inputs without a hostile classification override");
        s.report.addProperty(
            "combatInput",
            "native persistent stationary pigs, health=200, speed=0, knockback=1; no hostile override");
        s.report.addProperty(
            "ownerDefenseInput",
            "actual owner.attackEntityFrom then owner.attackTargetEntityWithCurrentItem; controlled server inputs, not player packets");
        s.report.addProperty("ownerSpawnProtectionAtSetup", playerSpawnProtection(f.owner));
        s.report.addProperty(
            "ownerHurtReadiness",
            "wait for the actual native field_147101_bU timer to expire through normal updates; no timer writes or manual player ticks");
        s.report.add("attackerInitial", entity(attacker));
        s.report.add("competitorInitial", entity(competitor));
        s.bindInputs();
        int[] phase = { 0 }, exited = { -1 }, finalWear = { -1 };
        float[] attackerExitHealth = { -1 }, competitorExitHealth = { -1 }, afterOwnerHit = { -1 };
        long[] beforeCompetitionAccepted = { -1 };
        h.succeedWhen(() -> {
            var row = s.sample(phase[0]);
            var core = s.enhanced();
            int age = f.maid.ticksExisted;
            row.addProperty("attackerHealth", attacker.getHealth());
            row.addProperty("competitorHealth", competitor.getHealth());
            row.addProperty("attackerX", attacker.posX - f.x);
            row.addProperty("attackerZ", attacker.posZ - f.z);
            row.addProperty("competitorX", competitor.posX - f.x);
            row.addProperty("competitorZ", competitor.posZ - f.z);
            int spawnProtection = playerSpawnProtection(f.owner);
            row.addProperty("ownerSpawnProtection", spawnProtection);
            row.addProperty("ownerAge", f.owner.ticksExisted);
            row.addProperty("ownerHurtResistantTime", f.owner.hurtResistantTime);
            row.addProperty("ownerInvulnerable", f.owner.isEntityInvulnerable());
            row.addProperty("ownerDisableDamage", f.owner.capabilities.disableDamage);
            row.addProperty(
                "meleeAccepted",
                core.actor()
                    .meleeNativeAccepted());
            row.addProperty("weaponWear", weapon.getItemDamage());
            h.assertTrue(
                h.getWorld()
                    .getEntityByID(attacker.getEntityId()) == attacker
                    && h.getWorld()
                        .getEntityByID(competitor.getEntityId()) == competitor,
                "Controlled neutral actor left or was replaced in the actual world");
            if (phase[0] == 0) {
                h.assertTrue(
                    attacker.getHealth() == 200 && competitor.getHealth() == 200
                        && weapon.getItemDamage() == 0
                        && core.combat()
                            .target() == null,
                    "Complete N attacked an unprovoked neutral input");
                if (age < 6 || spawnProtection > 0) return false;
                float health = f.owner.getHealth();
                boolean accepted = f.owner.attackEntityFrom(DamageSource.causeMobDamage(attacker), 1);
                row.addProperty("ownerHurtAccepted", accepted);
                row.addProperty("ownerHealthAfterHurt", f.owner.getHealth());
                row.addProperty("ownerRetaliatesAgainstAttacker", f.owner.getAITarget() == attacker);
                h.assertTrue(
                    accepted && f.owner.getHealth() < health && f.owner.getAITarget() == attacker,
                    "Actual native owner hurt did not establish the original neutral-defense predicate: " + row);
                s.event("actual owner hurt makes only the attacker legal")
                    .add("ownerAfterHurt", entity(f.owner));
                phase[0] = 1;
                return false;
            }
            if (phase[0] == 1) {
                h.assertTrue(competitor.getHealth() == 200, "Closer unprovoked neutral stole owner-defense targeting");
                if (core.actor()
                    .meleeNativeAccepted() == 0) return false;
                h.assertTrue(
                    core.combat()
                        .target() == attacker && attacker.getHealth() < 200
                        && weapon.getItemDamage() == core.actor()
                            .meleeNativeAccepted(),
                    "Complete N did not naturally approach, defend the owner and pay one wear per accepted melee");
                attacker.setPosition(f.maid.posX + 5, f.maid.posY, f.maid.posZ);
                competitor.setPosition(f.maid.posX + 1, f.maid.posY, f.maid.posZ);
                f.owner.setPosition(f.maid.posX, f.maid.posY, f.maid.posZ + 2);
                f.owner.inventory.mainInventory[f.owner.inventory.currentItem] = new ItemStack(Items.stick);
                float before = competitor.getHealth();
                f.owner.attackTargetEntityWithCurrentItem(competitor);
                h.assertTrue(
                    competitor.getHealth() < before && f.owner.getLastAttacker() == competitor
                        && f.owner.getAITarget() == attacker,
                    "Actual native owner attack did not leave two legal neutral candidates");
                afterOwnerHit[0] = competitor.getHealth();
                beforeCompetitionAccepted[0] = core.actor()
                    .meleeNativeAccepted();
                var input = s.event("owner attacks the closer competitor; original attacker moved five blocks away");
                input.add("ownerAfterAttack", entity(f.owner));
                input.add("attackerAfterMove", entity(attacker));
                input.add("competitorAfterOwnerAttack", entity(competitor));
                phase[0] = 2;
                return false;
            }
            if (phase[0] == 2) {
                if (core.combat()
                    .target() != competitor || competitor.getHealth() >= afterOwnerHit[0]
                    || core.actor()
                        .meleeNativeAccepted() <= beforeCompetitionAccepted[0])
                    return false;
                h.assertTrue(
                    weapon.getItemDamage() == core.actor()
                        .meleeNativeAccepted()
                        && core.actor()
                            .meleeNativeAccepted() >= 2,
                    "Natural target competition skipped accepted melee or duplicated finite wear");
                finalWear[0] = weapon.getItemDamage();
                attackerExitHealth[0] = attacker.getHealth();
                competitorExitHealth[0] = competitor.getHealth();
                f.maid.setTask(MaidTask.IDLE);
                h.assertTrue(
                    f.maid.getModernCombatMemory()
                        .target() == null
                        && f.maid.getItemUseState()
                            .peek() == null
                        && f.maid.getHeldItem() == weapon,
                    "Normal Idle task boundary retained an attack target/lease or replaced the actual weapon");
                exited[0] = age;
                phase[0] = 3;
                s.event("normal task change to Idle revokes combat");
                return false;
            }
            h.assertTrue(
                s.enhanced()
                    .combat()
                    .target() == null && weapon.getItemDamage() == finalWear[0]
                    && attacker.getHealth() == attackerExitHealth[0]
                    && competitor.getHealth() == competitorExitHealth[0],
                "Complete Idle root resumed an old attack or paid further wear");
            return s.finishWhen(age >= exited[0] + 6);
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 260, batch = "complete_entry_n_ambient")
    public static void enhancedObservesNativeOwnerThenOcclusionRetiresLookAndIdleWalkMovesNaturally(GameTestHelper h)
        throws Exception {
        var s = new Scene(h, "enhanced-ambient", MaidBrainProfile.ENHANCED);
        var f = s.f;
        f.owner.setPosition(f.x + 12.5, f.y + 1, f.z + 4.5);
        var main = new ItemStack(Items.stick, 3);
        var off = new ItemStack(Items.diamond, 2);
        f.maid.setCurrentItemOrArmor(0, main);
        f.maid.setOffhandItem(off);
        s.report.addProperty(
            "ambientInput",
            "Idle, full health, no food/drops; native owner four blocks away, no tempting held item");
        s.bindInputs();
        int[] phase = { 0 }, blockedAt = { -1 };
        double[] start = { 0, 0 };
        h.succeedWhen(() -> {
            var row = s.sample(phase[0]);
            var core = s.enhanced();
            int age = f.maid.ticksExisted;
            row.addProperty(
                "looks",
                core.ambient()
                    .lookStarts());
            row.addProperty(
                "walks",
                core.ambient()
                    .walkStarts());
            row.addProperty(
                "landSamples",
                core.ambient()
                    .samples());
            h.assertTrue(
                f.maid.getHeldItem() == main && f.maid.getOffhandItem() == off,
                "Ambient entry changed the real hands");
            if (phase[0] == 0) {
                if (core.selectedKind() != Kind.OBSERVE || core.ambient()
                    .watching() != f.owner) return false;
                h.assertTrue(
                    core.actor()
                        .hasLookTarget(),
                    "Natural owner observation did not own LOOK");
                for (int y = 1; y <= 4; y++) for (int z = 1; z < 8; z++) h.setBlock(10, y, z, Blocks.stone);
                h.assertTrue(!f.maid.canEntityBeSeen(f.owner), "Actual wall did not hide the observed owner");
                blockedAt[0] = age;
                phase[0] = 1;
                s.event("actual wall hides the naturally observed native owner");
                return false;
            }
            if (age <= blockedAt[0]) return false;
            if (phase[0] == 1) {
                h.assertTrue(
                    core.ambient()
                        .watching() == null && core.selectedKind() != Kind.OBSERVE
                        && !core.actor()
                            .hasLookTarget(),
                    "Complete N retained an occluded ambient actor or stale LOOK");
                start[0] = f.maid.posX;
                start[1] = f.maid.posZ;
                phase[0] = 2;
                return false;
            }
            var point = core.ambient()
                .destination();
            double dx = f.maid.posX - start[0], dz = f.maid.posZ - start[1];
            return s.finishWhen(
                core.selectedKind() == Kind.WANDER && point != null
                    && core.ambient()
                        .walkStarts() > 0
                    && f.navigation.searchCount() > 0
                    && !f.navigation.noPath()
                    && dx * dx + dz * dz > .04);
        });
    }

    private static void assertMealLease(GameTestHelper h, ModernCoreTests.Fixture f, ItemStack main, ItemStack off,
        ItemStack food) {
        var use = f.maid.getItemUseState()
            .peek();
        var inv = f.maid.getMaidInventory();
        h.assertTrue(
            use != null && use.getKind() == MaidItemUseState.Kind.FOOD
                && use.getHand() == MaidItemUseState.Hand.OFF
                && inv.getStackInSlot(MaidInventory.MAIN_HAND) == main
                && inv.getStackInSlot(MaidInventory.OFF_HAND) == food
                && inv.getStackInSlot(0) == off,
            "Complete F did not naturally borrow the fallback hand from the real backpack");
    }

    private static EntityPig pig(Scene s, double x, double z) {
        var pig = new EntityPig(s.h.getWorld());
        pig.func_110163_bv();
        pig.getEntityAttribute(SharedMonsterAttributes.maxHealth)
            .setBaseValue(200);
        pig.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
            .setBaseValue(0);
        pig.getEntityAttribute(SharedMonsterAttributes.knockbackResistance)
            .setBaseValue(1);
        pig.setHealth(200);
        s.h.spawnEntity(pig, x, 1, z);
        s.h.afterTest(pig::setDead);
        return pig;
    }

    private static int count(ModernCoreTests.Fixture f, Item item) {
        int count = 0;
        var inventory = f.maid.getMaidInventory();
        for (int slot = 0; slot < inventory.getSizeInventory(); slot++) {
            var stack = inventory.getStackInSlot(slot);
            if (stack != null && stack.getItem() == item) count += stack.stackSize;
        }
        return count;
    }

    private static int playerSpawnProtection(EntityPlayerMP player) {
        return ((Integer) cpw.mods.fml.relauncher.ReflectionHelper
            .getPrivateValue(EntityPlayerMP.class, player, "field_147101_bU")).intValue();
    }

    private static JsonObject entity(Entity entity) {
        var tag = new NBTTagCompound();
        entity.writeToNBT(tag);
        var result = new JsonObject();
        result.addProperty(
            "class",
            entity.getClass()
                .getName());
        result.addProperty(
            "uuid",
            entity.getUniqueID()
                .toString());
        result.addProperty("entityId", entity.getEntityId());
        result.addProperty("registered", entity.worldObj.getEntityByID(entity.getEntityId()) == entity);
        result.addProperty("x", entity.posX);
        result.addProperty("y", entity.posY);
        result.addProperty("z", entity.posZ);
        result.addProperty("width", entity.width);
        result.addProperty("height", entity.height);
        result.addProperty("eyeHeight", entity.getEyeHeight());
        result.addProperty("rawNBT", tag.toString());
        result.add("typedNBT", SleepAttributePortSnapshot.binaryNbt((NBTTagCompound) tag.copy()));
        if (entity instanceof EntityPlayerMP player) {
            result.addProperty(
                "gameMode",
                player.theItemInWorldManager.getGameType()
                    .getID());
            result.addProperty(
                "globalPlayerList",
                MinecraftServer.getServer()
                    .getConfigurationManager().playerEntityList.contains(player));
            result.addProperty("worldPlayerList", entity.worldObj.playerEntities.contains(player));
        }
        return result;
    }

    private static final class Scene {

        final GameTestHelper h;
        final String name;
        final MaidBrainProfile profile;
        final ModernCoreTests.Fixture f;
        final JsonObject report = new JsonObject();
        final JsonArray frames = new JsonArray(), events = new JsonArray();
        boolean complete;
        double previousX, previousY, previousZ;

        Scene(GameTestHelper h, String name, MaidBrainProfile profile) {
            this.h = h;
            this.name = name;
            this.profile = profile;
            f = new ModernCoreTests.Fixture(h, false, 1, 24, profile);
            f.maid.setSchedule(MaidSchedule.ALL);
            f.maid.setPosition(f.x + 8.5, f.y + 1, f.z + 4.5);
            f.owner.setPosition(f.x + 8.5, f.y + 1, f.z + 7);
            f.owner.inventory.mainInventory[f.owner.inventory.currentItem] = null;
            previousX = f.maid.posX;
            previousY = f.maid.posY;
            previousZ = f.maid.posZ;
            report.addProperty("schema", "complete-behavior-entry-v1");
            report.addProperty("scene", name);
            report.addProperty("profile", profile.name());
            report.addProperty(
                "scope",
                "complete factory F/N natural server/entity updates; controlled terrain and native owner inputs");
            report.addProperty(
                "ownerRegistration",
                "SCM.initializeConnectionToPlayer + normal FML login; isolated PLAY memory connection");
            report.addProperty("realNetworkLogin", false);
            report.addProperty("randomSourcesReset", false);
            report.addProperty("directBehaviorTick", false);
            report.addProperty("complete14ClassBenchmarkAccepted", false);
            report.addProperty("statisticalAdmission", "NOT_EVALUATED; user delta not specified");
            report
                .addProperty("crossEngineInitialNbtEquivalence", "NOT_PROVEN; this is one-side normal-entry evidence");
            report.add("frames", frames);
            report.add("inputEvents", events);
            h.afterTest(this::save);
        }

        void bindInputs() throws Exception {
            var flight = profile == MaidBrainProfile.ENHANCED ? enhanced().actor()
                .rangedBow()
                .flightProfile()
                : f.maid.getBrainCoordinator()
                    .peekFaithfulBrain()
                    .getActor()
                    .rangedBow()
                    .flightProfile();
            h.assertTrue(
                flight == (profile == MaidBrainProfile.ENHANCED
                    ? MaidBowAttack.ModernFlightProfile.NATIVE_MOTION_COMPENSATION
                    : MaidBowAttack.ModernFlightProfile.SOURCE_ORIGINAL),
                "Complete default factory flight contract differs");
            report.addProperty("factoryFlightProfile", flight.name());
            report.add(
                "productionArtifact",
                DevelopmentArtifactIdentity.capture(
                    com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid.class,
                    com.tianluoqaq.tlmnh.common.ai.faithful.MaidFaithfulBrain.class,
                    EnhancedCoreBrain.class));
            report.add(
                "observerArtifact",
                DevelopmentArtifactIdentity.capture(
                    CompleteBehaviorEntryTests.class,
                    Scene.class,
                    ModernCoreTests.Fixture.class,
                    SleepAttributePortSnapshot.class));
            report.add("connectionArtifact", DevelopmentArtifactIdentity.capture(IsolatedPlayerChannel.class));
            var before = new NBTTagCompound();
            f.maid.writeToNBT(before);
            before = (NBTTagCompound) before.copy();
            report.add("maidInitial", entity(f.maid));
            report.add("maidMappedAttributes", SleepAttributePortSnapshot.capture(f.maid));
            var after = new NBTTagCompound();
            f.maid.writeToNBT(after);
            h.assertTrue(before.equals(after), "Initial input recording changed complete maid NBT");
            report.add("maidInitialBindingBefore", SleepAttributePortSnapshot.binaryNbt(before));
            report.add("maidInitialBindingAfter", SleepAttributePortSnapshot.binaryNbt((NBTTagCompound) after.copy()));
            report.add("ownerInitial", entity(f.owner));
            report.addProperty("sameSideInitialStructureBound", true);
            report.addProperty(
                "worldTime",
                h.getWorld()
                    .getWorldTime());
            report.addProperty(
                "provider",
                h.getWorld().provider.getClass()
                    .getName());
            report.addProperty("dimension", h.getWorld().provider.dimensionId);
            report.addProperty(
                "canSpawnAnimals",
                MinecraftServer.getServer()
                    .getCanSpawnAnimals());
        }

        EnhancedCoreBrain enhanced() {
            return f.maid.getBrainCoordinator()
                .peekEnhancedCore();
        }

        JsonObject sample(int phase) {
            var brain = f.maid.getBrainCoordinator();
            var runtime = brain.peekPolicyRuntime();
            h.assertTrue(
                f.maid.isEntityAlive() && runtime != null
                    && runtime.isReady()
                    && runtime.getProfile() == profile
                    && !brain.isSuspended()
                    && (profile == MaidBrainProfile.ENHANCED ? enhanced() != null && brain.peekFaithfulBrain() == null
                        : brain.peekFaithfulBrain() != null && enhanced() == null)
                    && brain.getFollowController()
                        .getTicks() == 0
                    && brain.getCombatController()
                        .getTicks() == 0,
                "Complete factory root was replaced, suspended, faulted or delegated to legacy behavior");
            double dx = f.maid.posX - previousX, dy = f.maid.posY - previousY, dz = f.maid.posZ - previousZ;
            h.assertTrue(
                dx * dx + dy * dy + dz * dz < 1 && h.getWorld()
                    .func_147461_a(f.maid.boundingBox.contract(.001, .001, .001))
                    .isEmpty(),
                "Complete behavior entry moved discontinuously or clipped through the input terrain");
            previousX = f.maid.posX;
            previousY = f.maid.posY;
            previousZ = f.maid.posZ;
            var row = new JsonObject();
            row.addProperty("age", f.maid.ticksExisted);
            row.addProperty(
                "worldTick",
                h.getWorld()
                    .getTotalWorldTime());
            row.addProperty("phase", phase);
            row.addProperty("x", f.maid.posX - f.x);
            row.addProperty("y", f.maid.posY - f.y);
            row.addProperty("z", f.maid.posZ - f.z);
            row.addProperty("health", f.maid.getHealth());
            row.addProperty("ownerHealth", f.owner.getHealth());
            row.addProperty("ownerX", f.owner.posX - f.x);
            row.addProperty("ownerY", f.owner.posY - f.y);
            row.addProperty("ownerZ", f.owner.posZ - f.z);
            row.addProperty(
                "eating",
                f.maid.getMealController()
                    .isEating());
            row.addProperty("sleeping", f.maid.hasPose(MaidPose.SLEEPING));
            row.addProperty("searches", f.navigation.searchCount());
            row.addProperty("noPath", f.navigation.noPath());
            row.addProperty(
                "selected",
                profile == MaidBrainProfile.ENHANCED ? String.valueOf(enhanced().selectedKind())
                    : String.valueOf(
                        brain.peekFaithfulBrain()
                            .getActivities()
                            .active()));
            frames.add(row);
            if (f.maid.ticksExisted % 20 == 0) System.out.println("TLMNH_COMPLETE_ENTRY " + name + " " + row);
            return row;
        }

        JsonObject event(String input) {
            var row = new JsonObject();
            row.addProperty("age", f.maid.ticksExisted);
            row.addProperty("input", input);
            events.add(row);
            System.out.println("TLMNH_COMPLETE_ENTRY_INPUT " + name + " " + row);
            return row;
        }

        boolean finishWhen(boolean condition) {
            if (condition) complete = true;
            return condition;
        }

        void save() {
            report.addProperty("observationComplete", complete);
            // 普通轨迹只采实时事实；专门的NBT查询回归显式记录其序列化输入与结果
            try {
                var dir = Paths.get(System.getProperty("horizonqa.reportDir", "horizonqa-report"));
                Files.createDirectories(dir);
                Files.write(
                    dir.resolve("complete-behavior-entry-" + name + ".json"),
                    new Gson().toJson(report)
                        .getBytes(StandardCharsets.UTF_8));
            } catch (Exception failure) {
                throw new IllegalStateException("Cannot retain complete behavior entry observations", failure);
            }
        }
    }
}
