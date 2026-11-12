package com.tianluoqaq.tlmnh.qa;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.entity.Entity;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.entity.passive.EntityCow;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.DamageSource;
import net.minecraftforge.oredict.OreDictionary;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.gtnewhorizons.horizonqa.internal.GameTestChunkLoader;
import com.tianluoqaq.tlmnh.common.combat.MaidHealingAxeCombat;
import com.tianluoqaq.tlmnh.common.combat.weapon.MaidWeaponCatalog;
import com.tianluoqaq.tlmnh.common.compat.CompatServices;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventoryLayout;
import com.tianluoqaq.tlmnh.common.life.MaidSurvivalController;
import com.tianluoqaq.tlmnh.common.life.MaidSurvivalInventory;
import com.tianluoqaq.tlmnh.common.life.space.MaidSpaceSurvivalController;
import com.tianluoqaq.tlmnh.common.life.space.spi.SpaceSurvivalCompatProvider.AtmosphereSample;

/** 使用整合包真实物品、服务端物理与库存结算验证生存适配 */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class SurvivalEquipmentNativeTests {

    private SurvivalEquipmentNativeTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 110, batch = "survival_equipment")
    public static void fourFlightFamiliesFollowAirborneOwnerWithoutChangingEnergyOrFuel(GameTestHelper h) {
        var f = setup(h);
        f.owner.setPosition(f.x + 12.5, f.y + 25, f.z + 4.5);
        f.owner.capabilities.isFlying = true;
        f.owner.onGround = false;
        String[] classes = { "com.darkona.adventurebackpack.item.ItemCopterPack",
            "ic2.core.item.armor.ItemArmorJetpackElectric", "gravisuite.ItemAdvancedJetPack",
            "gravisuite.ItemAdvancedNanoChestPlate", "gravisuite.ItemGraviChestPlate" };
        List<EntityMaid> maids = new ArrayList<>();
        List<ItemStack> before = new ArrayList<>();
        for (int index = 0; index < classes.length; index++) {
            var maid = maid(h, f);
            var stack = item(h, classes[index], -1);
            maid.setCurrentItemOrArmor(3, stack);
            before.add(
                maid.getEquipmentInSlot(3)
                    .copy());
            h.spawnEntity(maid, 4.5 + index * 2, 8, 4.5);
            maids.add(maid);
        }
        var unequipped = maid(h, f);
        h.spawnEntity(unequipped, 3.5, 8, 2.5);
        int[] ticks = { 0 };
        h.onEachTick("real zero-cost three-dimensional flight", () -> {
            if (++ticks[0] < 85) return;
            for (int index = 0; index < maids.size(); index++) {
                var maid = maids.get(index);
                h.assertTrue(
                    maid.posY > f.y + 20 && maid.getDistanceSqToEntity(f.owner) < 40,
                    "Equipped maid failed actual airborne follow: " + classes[index]);
                h.assertTrue(maid.getHealth() == maid.getMaxHealth(), "Flight caused fall injury");
                h.assertTrue(
                    ItemStack.areItemStacksEqual(before.get(index), maid.getEquipmentInSlot(3)),
                    "Flight consumed fuel/EU or changed provider NBT: " + classes[index]);
            }
            h.assertTrue(unequipped.posY < f.y + 4, "Unequipped maid acquired flight");
            h.succeed();
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 50, batch = "survival_equipment")
    public static void realNanoAndQuantumArmorApplyNativeAbsorptionAndDrainEu(GameTestHelper h) {
        var f = setup(h);
        var attacker = new EntityZombie(h.getWorld());
        f.owned.add(attacker);
        var physical = DamageSource.causeMobDamage(attacker);
        h.assertTrue(
            !physical.isUnblockable() && DamageSource.generic.isUnblockable(),
            "Native damage-source profiles changed");
        for (String type : new String[] { "ic2.core.item.armor.ItemArmorNanoSuit",
            "ic2.core.item.armor.ItemArmorQuantumSuit" }) {
            var maid = maid(h, f);
            var reference = maid(h, f);
            var nativeArmor = new ItemStack[4];
            double before = 0;
            for (int slot = 1; slot <= 4; slot++) {
                var stack = item(h, type, 4 - slot);
                charge(stack, 1000000);
                maid.setCurrentItemOrArmor(slot, stack);
                before += charge(maid.getEquipmentInSlot(slot));
                nativeArmor[slot - 1] = maid.getEquipmentInSlot(slot)
                    .copy();
            }
            h.assertTrue(before == 4000000, "Real electric suit did not receive the exact fixture charge");
            float nativeRemaining = net.minecraftforge.common.ISpecialArmor.ArmorProperties
                .ApplyArmor(reference, nativeArmor, physical, 10);
            double nativeAfter = 0;
            for (var piece : nativeArmor) nativeAfter += charge(piece);
            h.assertTrue(
                nativeAfter < before && nativeRemaining < 10,
                "Native IC2/Forge reference did not absorb and pay EU");
            float health = maid.getHealth();
            h.assertTrue(maid.attackEntityFrom(physical, 10), "Native incoming damage was rejected");
            double after = 0;
            for (int slot = 1; slot <= 4; slot++) {
                double actual = charge(maid.getEquipmentInSlot(slot));
                h.assertTrue(
                    actual == charge(nativeArmor[slot - 1]),
                    "Native per-slot EU payment differs: " + type + "/" + slot);
                after += actual;
            }
            h.assertTrue(
                after == nativeAfter && maid.getHealth() == health - nativeRemaining,
                "Maid damage/EU differs from the actual IC2/Forge settlement: " + type);
            h.assertTrue(after < before, "Native armor absorbed without EU payment: " + type);
            if (type.endsWith("QuantumSuit"))
                h.assertTrue(maid.getHealth() == health, "Full quantum suit leaked physical damage");
            else h.assertTrue(
                maid.getHealth() < health && maid.getHealth() > health - 3,
                "Nano suit failed its partial absorption");
            System.out.println(
                "TLMNH_SURVIVAL_ARMOR type=" + type
                    + " source="
                    + physical.getDamageType()
                    + " damage=10 nativeRemaining="
                    + nativeRemaining
                    + " energyBefore="
                    + before
                    + " energyAfter="
                    + after);
        }
        var bypassed = maid(h, f);
        double bypassBefore = 0;
        for (int slot = 1; slot <= 4; slot++) {
            var piece = item(h, "ic2.core.item.armor.ItemArmorNanoSuit", 4 - slot);
            charge(piece, 1000000);
            bypassed.setCurrentItemOrArmor(slot, piece);
            bypassBefore += charge(bypassed.getEquipmentInSlot(slot));
        }
        float bypassHealth = bypassed.getHealth();
        h.assertTrue(bypassed.attackEntityFrom(DamageSource.generic, 10), "Native bypass damage was rejected");
        double bypassAfter = 0;
        for (int slot = 1; slot <= 4; slot++) bypassAfter += charge(bypassed.getEquipmentInSlot(slot));
        h.assertTrue(
            bypassAfter == bypassBefore && bypassed.getHealth() == bypassHealth - 10,
            "Nano suit changed native unblockable damage or charged EU for zero absorption");
        var unarmored = maid(h, f);
        float before = unarmored.getHealth();
        h.assertTrue(
            unarmored.attackEntityFrom(physical, 10) && unarmored.getHealth() == before - 10,
            "Unarmored physical damage changed");
        h.succeed();
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 50, batch = "survival_equipment")
    public static void quantumHelmetRefillsAirCuresDebuffsAndConsumesNestedTinCans(GameTestHelper h) {
        var f = setup(h);
        var maid = maid(h, f);
        var helmet = item(h, "ic2.core.item.armor.ItemArmorQuantumSuit", 0);
        charge(helmet, 1000000);
        maid.setCurrentItemOrArmor(4, helmet);
        maid.setAir(0);
        maid.addPotionEffect(new PotionEffect(Potion.poison.id, 200, 1));
        maid.addPotionEffect(new PotionEffect(Potion.wither.id, 200, 0));
        Potion radiation = radiation();
        maid.addPotionEffect(new PotionEffect(radiation.id, 200, 0));
        maid.setHunger(16);
        maid.getHungerState()
            .setSaturation(0);
        var bag = item(h, "com.rwtema.extrautils.item.ItemGoldenBag", -1);
        var cans = item(h, "ic2.core.item.ItemTinCan", -1);
        cans.stackSize = 4;
        h.assertTrue(
            CompatServices.backpacks()
                .insertIntoBackpack(bag, cans, false) == null,
            "Real can fixture did not enter nested backpack");
        maid.getMaidInventory()
            .setInventorySlotContents(0, bag);
        double energyBefore = charge(maid.getEquipmentInSlot(4));
        var access = new MaidSurvivalController(maid);
        h.assertTrue(
            CompatServices.specialArmor()
                .tickSpecialArmor(maid, null, null, null, maid.getEquipmentInSlot(4), access),
            "Quantum helmet provider made no changes");
        h.assertTrue(
            maid.getAir() == 200 && !maid.isPotionActive(Potion.poison)
                && !maid.isPotionActive(Potion.wither)
                && !maid.isPotionActive(radiation),
            "Quantum breathing or cure effects missing");
        h.assertTrue(
            maid.getHunger() == 20 && maid.getHungerState()
                .getSaturation() == 20,
            "Native batched can nutrition was not preserved");
        h.assertTrue(charge(maid.getEquipmentInSlot(4)) < energyBefore, "Quantum special effects consumed no EU");
        h.assertTrue(
            MaidSurvivalInventory.extract(
                maid,
                stack -> stack.getItem()
                    .getClass()
                    .getName()
                    .equals("ic2.core.item.ItemTinCan"),
                4,
                true) == null,
            "Filled cans remained after feeding");
        h.succeed();
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 50, batch = "survival_equipment")
    public static void fullRawInventoryEatsNestedFoodOnceAndReturnsBothHeldItems(GameTestHelper h) {
        var f = setup(h);
        var maid = maid(h, f);
        maid.setHunger(10);
        maid.getHungerState()
            .setSaturation(0);
        var inv = maid.getMaidInventory();
        for (int slot = 0; slot < MaidInventoryLayout.BACKPACK_SIZE; slot++)
            inv.setInventorySlotContents(slot, new ItemStack(Blocks.cobblestone, 64));
        var bag = item(h, "com.rwtema.extrautils.item.ItemGoldenBag", -1);
        var bread = new ItemStack(Items.bread, 3);
        var nutrition = com.tianluoqaq.tlmnh.common.life.meal.MaidFoodProfile.inspect(bread);
        h.assertTrue(nutrition != null && nutrition.hunger > 0, "Real bread nutrition unavailable");
        CompatServices.backpacks()
            .insertIntoBackpack(bag, bread, false);
        CompatServices.backpacks()
            .insertIntoBackpack(bag, new ItemStack(Items.stick, 63), false);
        inv.setInventorySlotContents(0, bag);
        maid.setCurrentItemOrArmor(0, new ItemStack(Items.iron_sword));
        maid.setOffhandItem(new ItemStack(Items.stick));
        var main = maid.getHeldItem()
            .copy();
        var off = maid.getOffhandItem()
            .copy();
        var meal = maid.getMealController();
        h.assertTrue(meal.tryStartMeal(false, () -> true), "Full raw slots prevented nested food checkout");
        h.assertTrue(meal.tryStartMeal(false, () -> true), "Same active meal was not retained");
        h.assertTrue(maid.getHunger() == 10, "Meal granted nutrition before consuming an item");
        for (int tick = 0; tick < 80 && meal.isEating(); tick++) meal.tick();
        h.assertTrue(
            !meal.isEating() && maid.getHunger() == Math.min(20, 10 + nutrition.hunger),
            "Nested meal did not commit its nutrition exactly once");
        h.assertTrue(
            ItemStack.areItemStacksEqual(main, maid.getHeldItem())
                && ItemStack.areItemStacksEqual(off, maid.getOffhandItem()),
            "Nested meal failed to return held items");
        var remaining = MaidSurvivalInventory.extract(maid, stack -> stack.getItem() == Items.bread, 64, true);
        h.assertTrue(remaining != null && remaining.stackSize == 2, "Nested meal did not consume exactly one bread");
        h.succeed();
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 70, batch = "survival_equipment")
    public static void healingAxeUsesActualOffhandClockAndUndeadAttackWithoutDurability(GameTestHelper h) {
        var f = setup(h);
        var maid = maid(h, f);
        var axe = item(h, "com.rwtema.extrautils.item.ItemHealingAxe", -1);
        h.assertTrue(
            MaidWeaponCatalog.inspect(axe)
                .melee(),
            "Healing axe was not admitted");
        maid.setCurrentItemOrArmor(0, axe);
        var zombie = new EntityZombie(h.getWorld());
        var cow = new EntityCow(h.getWorld());
        var villager = new EntityZombie(h.getWorld());
        villager.setVillager(true);
        f.owned.add(zombie);
        f.owned.add(cow);
        f.owned.add(villager);
        var original = maid.getHeldItem()
            .copy();
        h.assertTrue(
            zombie.getTotalArmorValue() == 2 && zombie.getActivePotionEffects()
                .isEmpty(),
            "Native zombie armor/effect fixture changed");
        var attackObserver = new NativeAttackObserver(zombie);
        float zombieHealth = zombie.getHealth();
        boolean accepted;
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(attackObserver);
        try {
            accepted = MaidHealingAxeCombat.execute(maid, zombie);
        } finally {
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(attackObserver);
        }
        h.assertTrue(
            accepted && attackObserver.calls == 1
                && attackObserver.submitted == 16
                && attackObserver.source != null
                && attackObserver.source.getEntity() == maid
                && attackObserver.source.getDamageType()
                    .equals("mob")
                && !attackObserver.source.isUnblockable(),
            "Undead attack did not submit exactly sixteen attributed native mob damage");
        float nativeDamage = 16.0F * (25 - zombie.getTotalArmorValue()) / 25.0F;
        h.assertTrue(
            zombie.getHealth() == zombieHealth - nativeDamage,
            "Undead attack bypassed native zombie armor settlement");
        System.out.println(
            "TLMNH_SURVIVAL_AXE submitted=" + attackObserver.submitted
                + " zombieArmor="
                + zombie.getTotalArmorValue()
                + " nativeDamage="
                + nativeDamage
                + " before="
                + zombieHealth
                + " after="
                + zombie.getHealth());
        float health = cow.getHealth();
        MaidHealingAxeCombat.execute(maid, cow);
        h.assertTrue(cow.getHealth() == health, "Non-undead healing axe changed health");
        MaidHealingAxeCombat.execute(maid, villager);
        h.assertTrue(villager.isConverting(), "Zombie villager did not enter provider-equivalent conversion");
        h.assertTrue(
            maid.getHungerState()
                .getExhaustion() == 30,
            "Attack exhaustion differs from provider");
        h.assertTrue(ItemStack.areItemStacksEqual(original, maid.getHeldItem()), "Healing axe consumed durability");
        maid.setCurrentItemOrArmor(0, null);
        maid.setOffhandItem(axe);
        maid.setHunger(10);
        maid.getHungerState()
            .setSaturation(0);
        h.onEachTick("real provider offhand clock", () -> {
            if (h.getWorld()
                .getTotalWorldTime() % 40 != 0) return;
            h.assertTrue(
                CompatServices.healingAxe()
                    .tickOffhandPassiveHunger(h.getWorld(), maid.getOffhandItem(), maid.getHungerState()::addStats),
                "True offhand did not trigger provider clock");
            h.assertTrue(
                maid.getHunger() == 11 && Math.abs(
                    maid.getHungerState()
                        .getSaturation() - 0.4F)
                    < 0.0001F,
                "Offhand nutrition differs from provider");
            h.succeed();
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 50, batch = "survival_equipment")
    public static void nestedHotIngotsStaySafeAndBareBurnsDropWithPickupGuard(GameTestHelper h) {
        var f = setup(h);
        var maid = maid(h, f);
        var hot = hotIngot(h);
        hot.setTagCompound(new NBTTagCompound());
        hot.getTagCompound()
            .setString("ClaimMarker", f.owner.getUniqueID() + "/hot");
        f.itemCallback = item -> {};
        var bag = item(h, "com.rwtema.extrautils.item.ItemGoldenBag", -1);
        h.assertTrue(
            CompatServices.backpacks()
                .insertIntoBackpack(bag, hot, false) == null,
            "Hot ingot fixture did not enter backpack");
        maid.getMaidInventory()
            .setInventorySlotContents(0, bag);
        float health = maid.getHealth();
        for (int pulse = 0; pulse < 3; pulse++) maid.getHotItemHazard()
            .evaluateNow(maid, CompatServices.hotItemHazard());
        h.assertTrue(maid.getHealth() == health, "Nested hot ingot burned maid");
        maid.getMaidInventory()
            .setInventorySlotContents(35, hot.copy());
        for (int pulse = 0; pulse < 3; pulse++) {
            maid.hurtResistantTime = 0;
            maid.getHotItemHazard()
                .evaluateNow(maid, CompatServices.hotItemHazard());
        }
        h.assertTrue(
            maid.getHealth() < health && maid.getMaidInventory()
                .getStackInSlot(35) == null,
            "Three true bare burns did not drop expanded-slot hot ingot");
        h.assertTrue(
            maid.getHotItemHazard()
                .getPickupCooldownTicks() > 0,
            "Hot drop did not arm pickup guard");
        maid.getMaidInventory()
            .setInventorySlotContents(0, null);
        h.assertTrue(
            maid.getHotItemHazard()
                .shouldBlockBareHotPickup(maid, hot, CompatServices.hotItemHazard()),
            "Bare hot pickup bypassed cooldown");
        maid.getMaidInventory()
            .setInventorySlotContents(0, bag);
        h.assertTrue(
            !maid.getHotItemHazard()
                .shouldBlockBareHotPickup(maid, hot, CompatServices.hotItemHazard()),
            "Safe direct-to-backpack pickup was blocked");
        h.succeed();
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 90, batch = "survival_equipment")
    public static void actualFallingMaidUsesNestedWaterBucketAndRecoversOneBucket(GameTestHelper h) {
        var f = setup(h);
        var maid = maid(h, f);
        var bag = item(h, "com.rwtema.extrautils.item.ItemGoldenBag", -1);
        h.assertTrue(
            CompatServices.backpacks()
                .insertIntoBackpack(bag, new ItemStack(Items.water_bucket), false) == null,
            "Water bucket did not enter nested backpack");
        maid.getMaidInventory()
            .setInventorySlotContents(0, bag);
        h.spawnEntity(maid, 6.5, 10, 4.5);
        maid.motionY = -1.1;
        maid.fallDistance = 8;
        boolean[] deployed = { false };
        int[] ticks = { 0 };
        h.onEachTick("actual fall and water source recovery", () -> {
            deployed[0] |= h.getWorld()
                .getBlock(f.x + 6, f.y + 1, f.z + 4)
                .getMaterial() == net.minecraft.block.material.Material.water;
            if (++ticks[0] < 55) return;
            h.assertTrue(deployed[0], "Real falling maid never deployed water");
            h.assertTrue(maid.getHealth() == maid.getMaxHealth(), "Water rescue did not prevent fall damage");
            var bucket = MaidSurvivalInventory.extract(maid, stack -> stack.getItem() == Items.water_bucket, 2, true);
            h.assertTrue(
                bucket != null && bucket.stackSize == 1,
                "Water rescue did not return exactly one filled bucket");
            h.assertTrue(
                MaidSurvivalInventory.extract(maid, stack -> stack.getItem() == Items.bucket, 1, true) == null,
                "Water rescue duplicated an empty bucket");
            h.succeed();
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 80, batch = "survival_equipment")
    public static void realOxygenEquipmentDrainsTanksAndThermalLiningUsesFourParts(GameTestHelper h) {
        var f = setup(h);
        var maid = maid(h, f);
        var provider = CompatServices.spaceSurvival();
        h.assertTrue(provider.isAvailable(), "Real Galacticraft provider unavailable on dedicated server");
        var mask = item(h, "micdoodle8.mods.galacticraft.core.items.ItemOxygenMask", -1);
        var gear = item(h, "micdoodle8.mods.galacticraft.core.items.ItemOxygenGear", -1);
        var tank = item(h, "micdoodle8.mods.galacticraft.core.items.ItemOxygenTank", -1);
        tank.setItemDamage(0);
        List<ItemStack> oxygen = Arrays.asList(mask, gear, tank);
        int damage = tank.getItemDamage();
        var result = provider.checkAndDrainOxygen(maid, oxygen, 1);
        h.assertTrue(
            result.validSetup && tank.getItemDamage() == damage + 1,
            "Real oxygen setup did not consume one unit on provider drain phase");
        h.assertTrue(
            !provider.checkAndDrainOxygen(maid, Arrays.asList(gear, tank), 1).validSetup,
            "Mask-free oxygen setup was accepted");
        var thermal = item(h, "micdoodle8.mods.galacticraft.planets.asteroids.items.ItemThermalPadding", -1);
        List<ItemStack> lining = new ArrayList<>();
        for (int part = 0; part < 4; part++) {
            var stack = thermal.copy();
            stack.setItemDamage(part);
            lining.add(stack);
        }
        h.assertTrue(
            provider.thermalPieceCount(lining) == 4 && provider.computeThermalStrength(lining) > 0,
            "Real four-part thermal lining was not recognized");
        var secondTier = item(h, "galaxyspace.core.item.ItemThermalPaddingTier2", -1);
        List<ItemStack> secondTierLining = new ArrayList<>();
        for (int part = 0; part < 4; part++) {
            var stack = secondTier.copy();
            stack.setItemDamage(part);
            secondTierLining.add(stack);
        }
        h.assertTrue(
            provider.computeThermalStrength(secondTierLining) == 2,
            "Real GalaxySpace second-tier thermal lining was not recognized");
        var exposed = maid(h, f);
        var insulated = maid(h, f);
        var exposedState = new MaidSpaceSurvivalController();
        var insulatedState = new MaidSpaceSurvivalController();
        var hotAtmosphere = new AtmosphereSample(true, true, true, 8);
        for (int tick = 1; tick <= 1600; tick++) {
            exposedState.tickWithExplicitBaubles(exposed, new ArrayList<ItemStack>(), hotAtmosphere, provider, tick);
            insulatedState.tickWithExplicitBaubles(insulated, secondTierLining, hotAtmosphere, provider, tick);
        }
        h.assertTrue(
            exposedState.getThermalLevel() == 22 && exposed.getHealth() < exposed.getMaxHealth(),
            "Unprotected atmosphere did not reach real thermal damage");
        h.assertTrue(
            Math.abs(insulatedState.getThermalLevel()) <= 2 && insulated.getHealth() == insulated.getMaxHealth(),
            "Second-tier lining did not normalize thermal level or prevent thermal damage");
        lining.remove(3);
        h.assertTrue(provider.computeThermalStrength(lining) == 0, "Incomplete thermal lining got full set strength");
        var state = new MaidSpaceSurvivalController();
        for (int tick = 1; tick <= provider.suffocationCooldown(); tick++) state.tickWithExplicitBaubles(
            maid,
            new ArrayList<ItemStack>(),
            new AtmosphereSample(true, false, true, 0),
            provider,
            tick);
        // The controller still queries real oxygen blocks; an unsealed test cell must not bypass suffocation.
        h.assertTrue(maid.getHealth() < maid.getMaxHealth(), "Missing oxygen caused no real GC suffocation damage");
        h.succeed();
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 90, batch = "survival_equipment")
    public static void actualFallingMaidUsesEtFuturumBoatAndRecoversOnlyItsOwnEmptyBoat(GameTestHelper h) {
        var f = setup(h);
        var maid = maid(h, f);
        var legacy = maid(h, f);
        legacy.getMaidInventory()
            .setInventorySlotContents(0, new ItemStack(Items.boat));
        legacy.setPosition(f.x + 6.5, f.y + 3, f.z + 4.5);
        legacy.motionY = -1.1;
        legacy.fallDistance = 8;
        legacy.getFallRescue()
            .tick(
                legacy,
                false,
                CompatServices.specialArmor(),
                CompatServices.spaceSurvival(),
                CompatServices.fallRescueBoat());
        h.assertTrue(
            legacy.ridingEntity != null && legacy.ridingEntity.getClass()
                .getName()
                .equals("ganymedes01.etfuturum.entities.EntityNewBoat"),
            "Vanilla boat did not use the registered GTNH replacement directly");
        f.owned.add(legacy.ridingEntity);
        legacy.getFallRescue()
            .finishBeforeTransfer(legacy, CompatServices.fallRescueBoat());
        var legacyReturn = MaidSurvivalInventory.extract(legacy, stack -> stack.getItem() == Items.boat, 2, true);
        h.assertTrue(
            legacyReturn != null && legacyReturn.stackSize == 1,
            "Vanilla replacement duplicated its boat refund");
        var boat = item(h, "ganymedes01.etfuturum.items.ItemNewBoat", -1);
        maid.getMaidInventory()
            .setInventorySlotContents(0, boat);
        h.spawnEntity(maid, 6.5, 8, 4.5);
        maid.motionY = -1.1;
        maid.fallDistance = 8;
        boolean[] boarded = { false };
        int[] ticks = { 0 };
        h.onEachTick("actual Et Futurum landing and owned boat recovery", () -> {
            Entity vehicle = maid.ridingEntity;
            if (vehicle != null) {
                boarded[0] = true;
                if (!f.owned.contains(vehicle)) f.owned.add(vehicle);
            }
            if (++ticks[0] < 50) return;
            h.assertTrue(boarded[0] && maid.ridingEntity == null, "Rescue boat was not boarded and released");
            h.assertTrue(maid.getHealth() == maid.getMaxHealth(), "Real boat rescue caused fall injury");
            var returned = MaidSurvivalInventory.extract(
                maid,
                stack -> CompatServices.fallRescueBoat()
                    .isCustomBoatItem(stack),
                2,
                true);
            h.assertTrue(
                returned != null && returned.stackSize == 1 && returned.isItemEqual(boat),
                "Rescue did not return exactly one original boat variant");
            maid.getFallRescue()
                .finishBeforeTransfer(maid, CompatServices.fallRescueBoat());
            var again = MaidSurvivalInventory.extract(
                maid,
                stack -> CompatServices.fallRescueBoat()
                    .isCustomBoatItem(stack),
                2,
                true);
            h.assertTrue(again != null && again.stackSize == 1, "Repeated cleanup duplicated a boat");
            h.succeed();
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 40, batch = "survival_equipment")
    public static void deniedWaterPlacementKeepsNestedBucketAndExistingWorldBlock(GameTestHelper h) {
        var f = setup(h);
        var maid = maid(h, f);
        var bag = item(h, "com.rwtema.extrautils.item.ItemGoldenBag", -1);
        CompatServices.backpacks()
            .insertIntoBackpack(bag, new ItemStack(Items.water_bucket), false);
        maid.getMaidInventory()
            .setInventorySlotContents(0, bag);
        maid.setPosition(f.x + 6.5, f.y + 3, f.z + 4.5);
        maid.motionY = -1.1;
        maid.fallDistance = 8;
        var handler = new PlacementDenialObserver(f.owner, f.x + 6, f.y + 1, f.z + 4);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(handler);
        try {
            maid.getFallRescue()
                .tick(
                    maid,
                    false,
                    CompatServices.specialArmor(),
                    CompatServices.spaceSurvival(),
                    CompatServices.fallRescueBoat());
        } finally {
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(handler);
        }
        h.assertTrue(
            h.getWorld()
                .isAirBlock(f.x + 6, f.y + 1, f.z + 4),
            "Canceled rescue left water behind");
        var retained = MaidSurvivalInventory.extract(maid, stack -> stack.getItem() == Items.water_bucket, 1, true);
        h.assertTrue(retained != null, "Canceled rescue consumed its nested bucket");
        h.assertTrue(maid.fallDistance == 8, "Canceled rescue granted fall immunity");
        h.succeed();
    }

    public static final class NativeAttackObserver {

        private final Entity target;
        private int calls;
        private float submitted = Float.NaN;
        private DamageSource source;

        public NativeAttackObserver(Entity target) {
            this.target = target;
        }

        @cpw.mods.fml.common.eventhandler.SubscribeEvent(
            priority = cpw.mods.fml.common.eventhandler.EventPriority.HIGHEST,
            receiveCanceled = true)
        public void onAttack(net.minecraftforge.event.entity.living.LivingAttackEvent event) {
            if (event.entityLiving != target) return;
            calls++;
            submitted = event.ammount;
            source = event.source;
        }
    }

    public static final class PlacementDenialObserver {

        private final net.minecraft.entity.player.EntityPlayer owner;
        private final int x;
        private final int y;
        private final int z;

        public PlacementDenialObserver(net.minecraft.entity.player.EntityPlayer owner, int x, int y, int z) {
            this.owner = owner;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @cpw.mods.fml.common.eventhandler.SubscribeEvent
        public void onPlace(net.minecraftforge.event.world.BlockEvent.PlaceEvent event) {
            if (event.player == owner && event.x == x && event.y == y && event.z == z) event.setCanceled(true);
        }
    }

    private static LifecycleConservationTests.Fixture setup(GameTestHelper h) {
        var f = new LifecycleConservationTests.Fixture(h);
        var chunks = new GameTestChunkLoader();
        h.afterTest(chunks::releaseAll);
        try {
            chunks.forceChunksStrict(h.getWorld(), f.x - 16, f.y, f.z - 16, f.x + 40, f.y + 40, f.z + 24);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
        h.getWorld().playerEntities.add(f.owner);
        h.afterTest(() -> h.getWorld().playerEntities.remove(f.owner));
        f.owner.setPosition(f.x + 10.5, f.y + 1, f.z + 4.5);
        return f;
    }

    private static EntityMaid maid(GameTestHelper h, LifecycleConservationTests.Fixture f) {
        var maid = new EntityMaid(h.getWorld());
        f.owned.add(maid);
        h.assertTrue(maid.bindTo(f.owner.getUniqueID()), "Maid owner binding failed");
        maid.setPosition(f.x + 6.5, f.y + 1, f.z + 4.5);
        maid.getHomeArea()
            .setEnabled(false);
        return maid;
    }

    private static ItemStack item(GameTestHelper h, String name, int armorType) {
        for (Object value : Item.itemRegistry) {
            if (!(value instanceof Item item) || !item.getClass()
                .getName()
                .equals(name)) continue;
            if (armorType >= 0 && (!(item instanceof ItemArmor armor) || armor.armorType != armorType)) continue;
            return new ItemStack(item);
        }
        throw new IllegalStateException("Required real provider item absent: " + name + "/" + armorType);
    }

    private static ItemStack hotIngot(GameTestHelper h) {
        for (String ore : OreDictionary.getOreNames())
            if (ore.startsWith("ingotHot")) for (ItemStack stack : OreDictionary.getOres(ore))
                if (stack != null && stack.getItemDamage() != OreDictionary.WILDCARD_VALUE
                    && CompatServices.hotItemHazard()
                        .inspectItemHeatDamage(stack) > 0) {
                            var one = stack.copy();
                            one.stackSize = 1;
                            return one;
                        }
        throw new IllegalStateException("No real hot ingot registered");
    }

    private static Object manager() throws Throwable {
        Class<?> manager = Class.forName("ic2.api.item.IElectricItemManager");
        return MethodHandles.publicLookup()
            .findStaticGetter(Class.forName("ic2.api.item.ElectricItem"), "manager", manager)
            .invoke();
    }

    private static Potion radiation() {
        try {
            Class<?> type = Class.forName("ic2.core.IC2Potion");
            return (Potion) MethodHandles.publicLookup()
                .findStaticGetter(type, "radiation", type)
                .invoke();
        } catch (Throwable failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void charge(ItemStack stack, double amount) {
        try {
            MethodHandles.publicLookup()
                .findVirtual(
                    Class.forName("ic2.api.item.IElectricItemManager"),
                    "charge",
                    MethodType.methodType(
                        double.class,
                        ItemStack.class,
                        double.class,
                        int.class,
                        boolean.class,
                        boolean.class))
                .invoke(manager(), stack, amount, Integer.MAX_VALUE, true, false);
        } catch (Throwable failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static double charge(ItemStack stack) {
        try {
            return (double) MethodHandles.publicLookup()
                .findVirtual(
                    Class.forName("ic2.api.item.IElectricItemManager"),
                    "getCharge",
                    MethodType.methodType(double.class, ItemStack.class))
                .invoke(manager(), stack);
        } catch (Throwable failure) {
            throw new IllegalStateException(failure);
        }
    }
}
