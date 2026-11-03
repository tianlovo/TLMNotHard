package com.tianluoqaq.tlmnh.qa;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.ContainerWorkbench;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.world.WorldSettings.GameType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.oredict.OreDictionary;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.common.config.MaidAcquisitionConfig;
import com.tianluoqaq.tlmnh.common.development.IsolatedPlayerChannel;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleData;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleEvents;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleItems;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleProbe;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleService;
import com.tianluoqaq.tlmnh.common.progression.MaidProgressionRecipes;
import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.registry.MaidLifeContent;
import com.tianluoqaq.tlmnh.common.registry.MaidResurrectionContent;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.registry.GameRegistry;
import cpw.mods.fml.relauncher.Side;
import gregtech.api.enums.ItemList;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEBasicMachine;
import gregtech.api.recipe.RecipeMaps;
import gregtech.api.util.GTOreDictUnificator;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa", "gregtech", "dreamcraft" })
public final class ProgressionTests {

    private ProgressionTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 40, batch = "progression_crafting")
    public static void workbenchConsumesFrameBedCabinetAndRejectsFreshCoreAndShrineBypasses(GameTestHelper helper) {
        var f = new Fixture(helper);
        helper.assertTrue(
            MaidProgressionRecipes.registered()
                && IntegrationGuard.available(Side.SERVER, ExternalFeature.PROGRESSION_RECIPES),
            "Actual progression registration failed");
        var steel = ore("plateSteel");
        var bronze = ore("plateBronze");
        var lapis = ore("gemLapis");
        var wood = ore("plankWood");
        var cake = ItemList.Food_Baked_Cake.get(1);
        var frame = new ItemStack[] { lapis, steel, lapis, steel, bronze, steel, bronze, new ItemStack(Items.redstone),
            bronze };
        craft(helper, f, frame, MaidResurrectionContent.EMPTY_SLAB);
        var earlier = frame.clone();
        earlier[1] = earlier[3] = earlier[5] = ore("plateIron");
        rejects(helper, f, earlier, MaidResurrectionContent.EMPTY_SLAB);
        var oldCore = frame.clone();
        oldCore[4] = cake;
        rejects(helper, f, oldCore, MaidResurrectionContent.MAID_CORE);
        var circuit = ore("circuitBasic");
        var shrine = new ItemStack[] { steel, lapis, steel, circuit, ItemList.Hull_LV.get(1), circuit, steel, wood,
            steel };
        rejects(helper, f, shrine, Item.getItemFromBlock(MaidResurrectionContent.SHRINE));
        var lowerHull = shrine.clone();
        lowerHull[4] = ItemList.Hull_ULV.get(1);
        rejects(helper, f, lowerHull, Item.getItemFromBlock(MaidResurrectionContent.SHRINE));
        var wool = ore("blockWool");
        craft(
            helper,
            f,
            new ItemStack[] { wool, wool, wool, steel, new ItemStack(Items.bed), steel, wood, wood, wood },
            MaidLifeContent.maidBedItem);
        var beds = OreDictionary.getOres("bedWood");
        var lookedUp = GameRegistry.findItem("etfuturum", "white_bed");
        var registryObservation = new com.google.gson.JsonObject();
        registryObservation.addProperty(
            "collectionClass",
            beds.getClass()
                .getName());
        registryObservation.addProperty("nativeCount", beds.size());
        registryObservation.addProperty(
            "inheritedStreamCount",
            beds.stream()
                .count());
        registryObservation.add("namedLookup", bedItemObservation(lookedUp));
        var members = new com.google.gson.JsonArray();
        for (var member : beds) {
            var observed = bedItemObservation(member.getItem());
            observed.addProperty("metadata", member.getItemDamage());
            observed.addProperty("sameNamedLookup", member.getItem() == lookedUp);
            members.add(observed);
        }
        registryObservation.add("bedWood", members);
        System.out.println("TLMNH_PROGRESS_COLORED_BED_REGISTRY " + registryObservation);
        // All 15 EFR colors use this native ItemBlock class. Resolve the actual ore member, not a name lookup fallback.
        // This Java-7 wrapper delegates iteration but inherits an empty ArrayList spliterator.
        ItemStack coloredBed = null;
        for (var member : beds) if (member.getItem()
            .getClass()
            .getName()
            .equals("ganymedes01.etfuturum.blocks.itemblocks.ItemBlockDyedBed")) {
                coloredBed = member.copy();
                break;
            }
        helper.assertTrue(coloredBed != null, "Native Et Futurum colored bed class is absent from bedWood=" + beds);
        coloredBed.stackSize = 1;
        var selectedItem = coloredBed.getItem();
        int nativeId = Item.getIdFromItem(selectedItem);
        helper.assertTrue(
            nativeId > 0 && Item.getItemById(nativeId) == selectedItem,
            "Actual ore bed has no exact native registry ID round trip");
        var nativeNbt = coloredBed.writeToNBT(new net.minecraft.nbt.NBTTagCompound());
        helper.assertTrue(
            ItemStack.areItemStacksEqual(coloredBed, ItemStack.loadItemStackFromNBT(nativeNbt)),
            "Actual ore bed did not survive native inventory NBT round trip");
        var bedInput = bedItemObservation(selectedItem);
        bedInput.addProperty("metadata", coloredBed.getItemDamage());
        System.out.println("TLMNH_PROGRESS_COLORED_BED_INPUT " + bedInput);
        craft(
            helper,
            f,
            new ItemStack[] { wool, wool, wool, steel, coloredBed, steel, wood, wood, wood },
            MaidLifeContent.maidBedItem);
        System.out.println("TLMNH_PROGRESS_COLORED_BED_CRAFT_PASS " + bedInput);
        craft(
            helper,
            f,
            new ItemStack[] { wood, wood, wood, steel, new ItemStack(Blocks.chest), steel, wood, wood, wood },
            Item.getItemFromBlock(MaidLifeContent.snackCabinet));
        helper.succeedWhen(() -> true);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 2800, batch = "progression_machines")
    public static void nativeSteamChargesReturnedFrameTwiceAndLvAssemblerConsumesShrineMaterials(
        GameTestHelper helper) {
        var f = new Fixture(helper);
        var steam = new NativeMachine(f, 5, ItemList.Machine_Bronze_AlloySmelter);
        var assembler = new NativeMachine(f, 8, ItemList.Machine_LV_Assembler);
        helper.assertTrue(
            steam.machine.isSteampowered() && !assembler.machine.isSteampowered(),
            "Machine fixture did not place the registered steam/LV machines");
        var frame = new ItemStack(MaidResurrectionContent.EMPTY_SLAB);
        var cake = ItemList.Food_Baked_Cake.get(1);
        var inputs = new ItemStack[] { GTOreDictUnificator.get("plateSteel", 4), GTOreDictUnificator.get("gemLapis", 1),
            GTOreDictUnificator.get("circuitBasic", 2), ItemList.Hull_LV.get(1), new ItemStack(Blocks.planks, 1, 5) };
        var charge = RecipeMaps.alloySmelterRecipes.findRecipeQuery()
            .items(frame, cake)
            .voltage(8)
            .find();
        var shrine = RecipeMaps.assemblerRecipes.findRecipeQuery()
            .items(inputs)
            .voltage(32)
            .find();
        helper.assertTrue(
            charge != null && charge.mDuration == 600
                && charge.mEUt == 8
                && charge.mOutputs[0].getItem() == MaidResurrectionContent.MAID_CORE,
            "Registered steam charging recipe has the wrong cost or output");
        helper.assertTrue(
            shrine != null && shrine.mDuration == 400
                && shrine.mEUt == 30
                && shrine.mFluidInputs.length == 0
                && shrine.mOutputs[0].getItem() == Item.getItemFromBlock(MaidResurrectionContent.SHRINE),
            "Registered LV shrine recipe has the wrong cost or output");
        helper.assertTrue(
            RecipeMaps.assemblerRecipes.findRecipeQuery()
                .items(inputs)
                .voltage(8)
                .find() == null,
            "ULV voltage bypassed the LV shrine machine gate");
        assertShrineAlternatives(helper, inputs);
        steam.inputs(frame);
        var lowerHull = inputs.clone();
        lowerHull[3] = ItemList.Hull_ULV.get(1);
        assembler.inputs(lowerHull);
        // Only energy supply is seeded. Native world ticks own recipe discovery, input consumption and progress.
        int[] age = { 0 }, phase = { 0 }, missingCakeSince = { 0 };
        boolean[] shrineTaken = { false };
        helper.onEachTick("registered steam/LV processing and reusable frame consumption", () -> {
            age[0]++;
            steam.energy();
            assembler.energy();
            if (phase[0] == 0) {
                helper.assertTrue(
                    steam.machine.mMaxProgresstime == 0 && assembler.machine.mMaxProgresstime == 0,
                    "Missing cake or ULV hull started a forbidden machine recipe");
                steam.assertInputs(frame);
                assembler.assertInputs(lowerHull);
                if (age[0] < 60) return;
                steam.input(1, cake);
                assembler.input(3, inputs[3]);
                phase[0] = 1;
            }
            if (!shrineTaken[0] && assembler.output() != null) {
                var output = assembler.takeOutput();
                helper.assertTrue(
                    output.getItem() == Item.getItemFromBlock(MaidResurrectionContent.SHRINE) && output.stackSize == 1,
                    "Native LV assembly returned the wrong output count");
                assembler.assertInputs();
                helper.assertTrue(
                    assembler.sawProgress && assembler.spentEnergy,
                    "Shrine appeared without native processing and energy consumption");
                shrineTaken[0] = true;
            }
            if ((phase[0] == 1 || phase[0] == 3) && steam.output() != null) {
                var output = steam.takeOutput();
                helper.assertTrue(
                    output.getItem() == MaidResurrectionContent.MAID_CORE && output.stackSize == 1,
                    "Native steam charge returned the wrong core count");
                steam.assertInputs();
                helper.assertTrue(
                    steam.sawProgress && steam.spentEnergy,
                    "Core appeared without native processing and steam consumption");
                f.hand(output);
                f.click(phase[0] == 1 ? 12 : 16);
                helper.assertTrue(
                    f.owner.getCurrentEquippedItem() != null && f.owner.getCurrentEquippedItem()
                        .getItem() == MaidResurrectionContent.EMPTY_SLAB
                        && f.owner.getCurrentEquippedItem().stackSize == 1
                        && f.maids()
                            .size() == (phase[0] == 1 ? 1 : 2),
                    "Processed core did not release exactly one maid and one reusable frame");
                for (var maid : f.maids()) maid.setSitting(true);
                if (phase[0] == 3) {
                    phase[0] = 4;
                } else {
                    var returned = f.owner.getCurrentEquippedItem()
                        .copy();
                    f.hand(null);
                    steam.inputs(returned);
                    missingCakeSince[0] = age[0];
                    phase[0] = 2;
                    steam.sawProgress = steam.spentEnergy = false;
                }
            }
            if (phase[0] == 2) {
                helper.assertTrue(
                    steam.machine.mMaxProgresstime == 0 && steam.output() == null,
                    "Returned frame charged again without another cake");
                steam.assertInputs(frame);
                if (age[0] - missingCakeSince[0] >= 60) {
                    steam.input(1, cake);
                    phase[0] = 3;
                }
            }
            if (phase[0] == 4) steam.assertInputs();
        });
        helper.succeedWhen(() -> phase[0] == 4 && shrineTaken[0]);
    }

    private static void assertShrineAlternatives(GameTestHelper helper, ItemStack[] inputs) {
        String[] ores = { "plateSteel", "gemLapis", "circuitBasic", null, "plankWood" };
        for (int slot = 0; slot < ores.length; slot++) {
            if (ores[slot] == null) continue;
            for (var alternative : OreDictionary.getOres(ores[slot])) {
                var concrete = alternative.copy();
                if (concrete.getItemDamage() == OreDictionary.WILDCARD_VALUE) concrete.setItemDamage(0);
                concrete.stackSize = inputs[slot].stackSize;
                var substituted = new ItemStack[inputs.length];
                for (int i = 0; i < inputs.length; i++) substituted[i] = inputs[i].copy();
                substituted[slot] = concrete;
                var recipe = RecipeMaps.assemblerRecipes.findRecipeQuery()
                    .items(substituted)
                    .voltage(32)
                    .find();
                helper.assertTrue(
                    recipe != null
                        && recipe.mOutputs[0].getItem() == Item.getItemFromBlock(MaidResurrectionContent.SHRINE)
                        && recipe.isRecipeInputEqual(true, null, substituted),
                    "Native shrine matching rejected ore alternative " + ores[slot] + ": " + concrete);
                for (var consumed : substituted) helper.assertTrue(
                    consumed == null || consumed.stackSize == 0,
                    "Native shrine alternative did not consume its exact required count");
            }
        }
        var insufficient = new ItemStack[inputs.length];
        for (int i = 0; i < inputs.length; i++) insufficient[i] = inputs[i].copy();
        insufficient[2].stackSize = 1;
        helper.assertTrue(
            RecipeMaps.assemblerRecipes.findRecipeQuery()
                .items(insufficient)
                .voltage(32)
                .find() == null,
            "One basic circuit bypassed the required pair");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 40, batch = "progression_acquisition")
    public static void blockedCoreRetainsBindingCopiesCannotRedeemAndTwoCraftsDoNotConsumeFreeGift(
        GameTestHelper helper) {
        var f = new Fixture(helper);
        for (int i = 0; i < f.owner.inventory.mainInventory.length; i++)
            f.owner.inventory.mainInventory[i] = new ItemStack(Items.stick, 64);
        var core = new ItemStack(MaidResurrectionContent.MAID_CORE);
        core.setTagInfo("CustomPayload", new net.minecraft.nbt.NBTTagString("paid-core-data"));
        f.hand(core);
        helper.setBlock(5, 1, 4, Blocks.stone);
        f.click(5);
        var bound = f.owner.getCurrentEquippedItem();
        var token = MaidLifecycleItems.token(bound);
        var ledger = MaidLifecycleData.get(helper.getWorld());
        helper.assertTrue(
            bound != core && bound.getItem() == MaidResurrectionContent.INITIAL_SLAB
                && token != null
                && ledger.initialKind(token) == MaidLifecycleData.Kind.ACQUIRED
                && ledger.owner(token, MaidLifecycleData.Kind.ACQUIRED)
                    .equals(f.owner.getUniqueID())
                && bound.getTagCompound()
                    .getString("CustomPayload")
                    .equals("paid-core-data")
                && f.maids()
                    .isEmpty(),
            "Blocked/full-inventory core lost its bound credential or original payload");
        var copy = bound.copy();
        f.stranger.inventory.mainInventory[0] = copy.copy();
        helper.assertTrue(
            MaidLifecycleService.releaseInitial(f.stranger, f.stranger.getCurrentEquippedItem(), f.x + 5, f.y, f.z + 4)
                == MaidLifecycleService.Result.WRONG_OWNER,
            "Another player redeemed the crafted credential");
        helper.setBlock(5, 1, 4, Blocks.air);
        f.click(5);
        helper.assertTrue(
            f.owner.getCurrentEquippedItem()
                .getItem() == MaidResurrectionContent.EMPTY_SLAB
                && f.maids()
                    .size() == 1
                && f.maids()
                    .get(0)
                    .isOwner(f.owner),
            "Paid credential did not release exactly one owned maid");
        f.hand(copy);
        helper.assertTrue(
            MaidLifecycleService.releaseInitial(f.owner, copy, f.x + 9, f.y, f.z + 4)
                == MaidLifecycleService.Result.INVALID_CREDENTIAL
                && f.maids()
                    .size() == 1,
            "Copied crafted credential spawned twice");
        f.hand(new ItemStack(MaidResurrectionContent.MAID_CORE));
        f.click(9);
        helper.assertTrue(
            f.maids()
                .size() == 2
                && f.owner.getCurrentEquippedItem()
                    .getItem() == MaidResurrectionContent.EMPTY_SLAB,
            "Second separately crafted core reused the first right");
        var gift = ledger.initialToken(f.owner.getUniqueID());
        helper.assertTrue(
            !ledger.delivered(gift) && !MaidLifecycleEvents.giveInitial(f.owner),
            "Crafting consumed or bypassed the disabled free gift");
        MaidAcquisitionConfig.giveInitialSlab = true;
        helper.assertFalse(
            MaidLifecycleEvents.giveInitial(f.owner),
            "Full inventory dropped or overwrote a free credential");
        f.owner.inventory.mainInventory[6] = null;
        helper.assertTrue(
            MaidLifecycleEvents.giveInitial(f.owner) && !MaidLifecycleEvents.giveInitial(f.owner)
                && gift.equals(MaidLifecycleItems.token(f.owner.inventory.mainInventory[6])),
            "Explicit free gift was not independently delivered once after space became available");
        helper.succeedWhen(() -> true);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 40, batch = "progression_callback")
    public static void spawnReentryAndHandChangePreserveCredentialThenRetrySucceeds(GameTestHelper helper) {
        var f = new Fixture(helper);
        f.owner.inventory.mainInventory[1] = new ItemStack(Items.diamond);
        f.hand(new ItemStack(MaidResurrectionContent.MAID_CORE));
        var mutation = new JoinMutation(f);
        MinecraftForge.EVENT_BUS.register(mutation);
        try {
            f.click(5);
        } finally {
            MinecraftForge.EVENT_BUS.unregister(mutation);
        }
        helper.assertTrue(
            mutation.calls == 1 && mutation.reentry == MaidLifecycleService.Result.INVALID_CREDENTIAL
                && f.owner.inventory.currentItem == 1
                && f.owner.getCurrentEquippedItem()
                    .getItem() == Items.diamond
                && f.maids()
                    .isEmpty(),
            "Spawn callback retained a duplicate actor or overwrote the successor hand");
        var bound = f.owner.inventory.mainInventory[0];
        helper.assertTrue(
            bound != null && bound.getItem() == MaidResurrectionContent.INITIAL_SLAB
                && MaidLifecycleItems.token(bound) != null,
            "Cancelled core conversion lost its original credential");
        f.hand(bound);
        f.click(5);
        helper.assertTrue(
            f.maids()
                .size() == 1
                && f.owner.getCurrentEquippedItem()
                    .getItem() == MaidResurrectionContent.EMPTY_SLAB,
            "Rolled-back paid right could not complete exactly once");
        helper.succeedWhen(() -> true);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 40, batch = "progression_config")
    public static void realForgeConfigDefaultsDisableGiftAndPreserveAllExplicitChoices(GameTestHelper helper)
        throws IOException {
        var file = File.createTempFile("tlmnh-qa-acquisition-", ".cfg", new File("."));
        var actual = new File(
            Loader.instance()
                .getConfigDir(),
            "tlmnh.cfg");
        helper.afterTest(() -> {
            MaidAcquisitionConfig.load(actual);
            if (!file.delete()) throw new IllegalStateException("Could not remove owned temporary config");
        });
        MaidAcquisitionConfig.load(file);
        helper.assertTrue(
            !MaidAcquisitionConfig.giveInitialSlab && !MaidAcquisitionConfig.naturalSpawning,
            "Fresh defaults bypassed steam acquisition");
        for (boolean natural : new boolean[] { false, true }) for (boolean gift : new boolean[] { false, true }) {
            var config = new Configuration(file);
            config.load();
            config.get("acquisition", "giveInitialSlab", false)
                .set(gift);
            config.get("acquisition", "naturalSpawning", false)
                .set(natural);
            config.save();
            MaidAcquisitionConfig.load(file);
            helper.assertTrue(
                MaidAcquisitionConfig.giveInitialSlab == gift && MaidAcquisitionConfig.naturalSpawning == natural,
                "Existing independent acquisition choices were overwritten");
        }
        helper.succeedWhen(() -> true);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 40, batch = "progression_legacy_lifecycle")
    public static void existingGiftDeathInventoryAndShrineRollbackSurviveLedgerUpgrade(GameTestHelper helper) {
        MaidLifecycleProbe.run(helper.getWorld());
        helper.succeedWhen(() -> true);
    }

    private static ItemStack ore(String name) {
        // 选择可实际持有的原版变体，不将OreDictionary中的通配metadata放进工作台
        if (name.equals("blockWool")) return new ItemStack(Blocks.wool, 1, 0);
        if (name.equals("plankWood")) return new ItemStack(Blocks.planks, 1, 0);
        var result = OreDictionary.getOres(name)
            .get(0)
            .copy();
        result.stackSize = 1;
        return result;
    }

    private static com.google.gson.JsonObject bedItemObservation(Item item) {
        var result = new com.google.gson.JsonObject();
        if (item == null) {
            result.addProperty("present", false);
            return result;
        }
        result.addProperty("present", true);
        var rawName = Item.itemRegistry.getNameForObject(item);
        int id = Item.getIdFromItem(item);
        result.addProperty("rawName", rawName);
        result.addProperty(
            "itemClass",
            item.getClass()
                .getName());
        result.addProperty("nativeId", id);
        result.addProperty("identity", System.identityHashCode(item));
        result.addProperty("idRoundTrip", id > 0 && Item.getItemById(id) == item);
        result.addProperty("oldNamespacePredicate", rawName != null && rawName.startsWith("etfuturum:"));
        return result;
    }

    private static ContainerWorkbench workbench(Fixture f, ItemStack[] grid) {
        f.helper.setBlock(2, 1, 2, Blocks.crafting_table);
        f.owner.setPosition(f.x + 3.5, f.y + 1, f.z + 2.5);
        var container = new ContainerWorkbench(f.owner.inventory, f.helper.getWorld(), f.x + 2, f.y + 1, f.z + 2);
        f.helper.assertTrue(container.canInteractWith(f.owner), "Native workbench permission or distance was invalid");
        f.owner.openContainer = container;
        for (int i = 0; i < grid.length; i++) container.craftMatrix.setInventorySlotContents(i, grid[i].copy());
        return container;
    }

    private static void craft(GameTestHelper helper, Fixture f, ItemStack[] grid, Item expected) {
        for (var stack : grid) helper.assertTrue(
            stack.getItemDamage() != OreDictionary.WILDCARD_VALUE,
            "Crafting fixture used a wildcard instead of a concrete obtainable variant");
        var container = workbench(f, grid);
        var result = container.getSlot(0)
            .getStack();
        if (result == null || result.getItem() != expected || result.stackSize != 1)
            helper.assertTrue(false, recipeDiagnostic(f, container, expected, result));
        f.owner.inventory.setItemStack(null);
        container.slotClick(0, 0, 0, f.owner);
        var taken = f.owner.inventory.getItemStack();
        helper.assertTrue(
            taken != null && taken.getItem() == expected && taken.stackSize == 1,
            "Native workbench did not deliver one output to the cursor");
        for (int i = 0; i < 9; i++) helper.assertTrue(
            container.craftMatrix.getStackInSlot(i) == null,
            "Native craft did not consume exactly one of every input");
        f.owner.inventory.setItemStack(null);
        helper.assertTrue(
            f.owner.inventory.addItemStackToInventory(taken),
            "Crafted output did not fit the empty test inventory");
        container.onContainerClosed(f.owner);
        f.owner.openContainer = f.owner.inventoryContainer;
    }

    private static void rejects(GameTestHelper helper, Fixture f, ItemStack[] grid, Item forbidden) {
        var container = workbench(f, grid);
        var output = CraftingManager.getInstance()
            .findMatchingRecipe(container.craftMatrix, helper.getWorld());
        helper.assertTrue(
            output == null || output.getItem() != forbidden,
            "Earlier-tier substitution bypassed progression");
        for (int i = 0; i < 9; i++) container.craftMatrix.setInventorySlotContents(i, null);
        container.onContainerClosed(f.owner);
        f.owner.openContainer = f.owner.inventoryContainer;
    }

    private static String recipeDiagnostic(Fixture f, ContainerWorkbench container, Item expected, ItemStack result) {
        var text = new StringBuilder("Expected progression output ")
            .append(Item.itemRegistry.getNameForObject(expected))
            .append("; container=")
            .append(result)
            .append("; direct=")
            .append(
                CraftingManager.getInstance()
                    .findMatchingRecipe(container.craftMatrix, f.helper.getWorld()))
            .append("; grid=");
        for (int i = 0; i < 9; i++) text.append(container.craftMatrix.getStackInSlot(i))
            .append(';');
        int found = 0;
        for (var recipe : CraftingManager.getInstance()
            .getRecipeList()) {
            var output = recipe.getRecipeOutput();
            if (output == null || output.getItem() != expected) continue;
            found++;
            if (text.length() > 9000) continue;
            text.append(" recipe=")
                .append(
                    recipe.getClass()
                        .getName())
                .append(" match=")
                .append(recipe.matches(container.craftMatrix, f.helper.getWorld()));
            if (recipe instanceof net.minecraftforge.oredict.ShapedOreRecipe shaped) text.append(" input=")
                .append(java.util.Arrays.deepToString(shaped.getInput()));
        }
        text.append(" matching-output-recipes=")
            .append(found);
        return text.substring(0, Math.min(text.length(), 12000));
    }

    public static final class JoinMutation {

        final Fixture f;
        int calls;
        MaidLifecycleService.Result reentry;

        JoinMutation(Fixture f) {
            this.f = f;
        }

        @SubscribeEvent
        public void joined(EntityJoinWorldEvent event) {
            if (event.world != f.helper.getWorld() || !(event.entity instanceof EntityMaid)) return;
            calls++;
            reentry = MaidLifecycleService
                .releaseInitial(f.owner, f.owner.getCurrentEquippedItem(), f.x + 5, f.y, f.z + 4);
            f.owner.inventory.currentItem = 1;
        }
    }

    static final class NativeMachine {

        final Fixture fixture;
        final BaseMetaTileEntity base;
        final MTEBasicMachine machine;
        boolean sawProgress, spentEnergy;
        long previousEnergy = -1;

        NativeMachine(Fixture fixture, int blockX, ItemList item) {
            this.fixture = fixture;
            var placed = item.get(1);
            fixture.hand(placed.copy());
            fixture.click(blockX);
            var tile = fixture.helper.getWorld()
                .getTileEntity(fixture.x + blockX, fixture.y + 1, fixture.z + 4);
            fixture.helper.assertTrue(tile instanceof BaseMetaTileEntity, "Native GT item did not place a base tile");
            base = (BaseMetaTileEntity) tile;
            fixture.helper.assertTrue(
                base.getMetaTileID() == placed.getItemDamage() && base.getMetaTileEntity() instanceof MTEBasicMachine,
                "Native GT placement selected the wrong registered machine");
            machine = (MTEBasicMachine) base.getMetaTileEntity();
            base.setFrontFacing(ForgeDirection.NORTH);
            base.enableTicking();
            fixture.helper.afterTest(() -> {
                var world = fixture.helper.getWorld();
                if (world.getTileEntity(base.xCoord, base.yCoord, base.zCoord) != base) return;
                java.util.Arrays.fill(machine.mInventory, null);
                java.util.Arrays.fill(machine.mOutputItems, null);
                world.setBlockToAir(base.xCoord, base.yCoord, base.zCoord);
            });
        }

        void inputs(ItemStack... inputs) {
            fixture.helper
                .assertTrue(inputs.length <= machine.mInputSlotCount, "Machine has too few native input slots");
            for (int i = 0; i < machine.mInputSlotCount; i++)
                machine.mInventory[machine.getInputSlot() + i] = i < inputs.length ? inputs[i].copy() : null;
            base.markInventoryBeenModified();
            base.enableTicking();
        }

        void assertInputs(ItemStack... expected) {
            for (int i = 0; i < machine.mInputSlotCount; i++) {
                var actual = machine.mInventory[machine.getInputSlot() + i];
                fixture.helper.assertTrue(
                    ItemStack.areItemStacksEqual(actual, i < expected.length ? expected[i] : null),
                    "Native machine consumed the wrong material/count at input " + i);
            }
        }

        void input(int index, ItemStack stack) {
            fixture.helper
                .assertTrue(index >= 0 && index < machine.mInputSlotCount, "Invalid native machine input slot");
            machine.mInventory[machine.getInputSlot() + index] = stack.copy();
            base.markInventoryBeenModified();
            base.enableTicking();
        }

        void energy() {
            long stored = machine.isSteampowered() ? base.getStoredSteam() : base.getStoredEU();
            if (previousEnergy >= 0 && stored < previousEnergy) spentEnergy = true;
            sawProgress |= machine.mMaxProgresstime > 0 && machine.mProgresstime > 0;
            fixture.helper.assertTrue(
                machine.isSteampowered() ? base.setStoredSteam(16000) : base.setStoredEU(machine.maxEUStore()),
                "Could not seed the real machine energy buffer");
            previousEnergy = machine.isSteampowered() ? base.getStoredSteam() : base.getStoredEU();
        }

        ItemStack output() {
            return machine.mInventory[machine.getOutputSlot()];
        }

        ItemStack takeOutput() {
            var result = output().copy();
            machine.mInventory[machine.getOutputSlot()] = null;
            base.markInventoryBeenModified();
            return result;
        }
    }

    static final class Fixture {

        final GameTestHelper helper;
        final int x, y, z;
        final EntityPlayerMP owner, stranger;
        final HashSet<Entity> original = new HashSet<>();

        Fixture(GameTestHelper helper) {
            this(helper, true);
        }

        Fixture(GameTestHelper helper, boolean cleanupSpawned) {
            this.helper = helper;
            var origin = helper.absolute(0, 0, 0);
            x = origin.x();
            y = origin.y();
            z = origin.z();
            original.addAll(helper.getWorld().loadedEntityList);
            boolean oldGift = MaidAcquisitionConfig.giveInitialSlab;
            boolean animals = MinecraftServer.getServer()
                .getCanSpawnAnimals();
            MaidAcquisitionConfig.giveInitialSlab = false;
            MinecraftServer.getServer()
                .setCanSpawnAnimals(true);
            helper.afterTest(() -> {
                MaidAcquisitionConfig.giveInitialSlab = oldGift;
                MinecraftServer.getServer()
                    .setCanSpawnAnimals(animals);
                if (cleanupSpawned) for (var entity : new ArrayList<>(helper.getWorld().loadedEntityList)) {
                    if (!original.contains(entity) && (entity instanceof EntityMaid || entity instanceof EntityItem)) {
                        entity.setDead();
                        helper.getWorld()
                            .removePlayerEntityDangerously((Entity) entity);
                    }
                }
            });
            for (int a = 1; a < 20; a++) for (int c = 1; c < 8; c++) helper.setBlock(a, 0, c, Blocks.stone);
            owner = player(helper);
            stranger = player(helper);
            owner.setPosition(x + 10.5, y + 1, z + 4.5);
            stranger.setPosition(owner.posX, owner.posY, owner.posZ);
        }

        private static EntityPlayerMP player(GameTestHelper helper) {
            var server = MinecraftServer.getServer();
            var player = new EntityPlayerMP(
                server,
                helper.getWorld(),
                new GameProfile(UUID.randomUUID(), "[QA-Progress]"),
                new ItemInWorldManager(helper.getWorld()));
            var manager = new NetworkManager(false);
            var channel = new EmbeddedChannel(manager);
            manager.setConnectionState(EnumConnectionState.PLAY);
            IsolatedPlayerChannel.verify(manager, channel);
            new NetHandlerPlayServer(server, manager, player);
            player.theItemInWorldManager.setGameType(GameType.SURVIVAL);
            helper.afterTest(() -> {
                player.setDead();
                channel.finish();
                Object message;
                while ((message = channel.readOutbound()) != null) ReferenceCountUtil.release(message);
                while ((message = channel.readInbound()) != null) ReferenceCountUtil.release(message);
            });
            return player;
        }

        void hand(ItemStack stack) {
            owner.inventory.currentItem = 0;
            owner.inventory.mainInventory[0] = stack;
        }

        void click(int blockX) {
            helper.assertTrue(
                owner.theItemInWorldManager.activateBlockOrUseItem(
                    owner,
                    helper.getWorld(),
                    owner.getCurrentEquippedItem(),
                    x + blockX,
                    y,
                    z + 4,
                    1,
                    .5F,
                    1,
                    .5F),
                "Actual ItemInWorldManager interaction was rejected");
        }

        ArrayList<EntityMaid> maids() {
            var result = new ArrayList<EntityMaid>();
            for (var entity : helper.getWorld().loadedEntityList)
                if (entity instanceof EntityMaid maid && !original.contains(maid) && !maid.isDead) result.add(maid);
            return result;
        }
    }
}
