package com.tianluoqaq.tlmnh.qa;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.ContainerWorkbench;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntityFurnace;
import net.minecraft.util.DamageSource;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.oredict.OreDictionary;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.development.DevelopmentArtifactIdentity;
import com.tianluoqaq.tlmnh.common.life.TileEntitySnackCabinet;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleData;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleItems;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleService;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidShrineTile;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidSpawnChecks;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidTombstoneEntity;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidTombstoneTransfer;
import com.tianluoqaq.tlmnh.common.protection.DependencyFingerprint;
import com.tianluoqaq.tlmnh.common.registry.MaidLifeContent;
import com.tianluoqaq.tlmnh.common.registry.MaidResurrectionContent;

import gregtech.api.enums.ItemList;
import gregtech.api.enums.Materials;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.recipe.RecipeMaps;
import gregtech.api.util.GTModHandler;
import gregtech.api.util.GTOreDictUnificator;
import gregtech.common.items.IDMetaTool01;
import gregtech.common.tileentities.boilers.MTEBoiler;
import gregtech.common.tileentities.generators.MTESteamTurbine;

/** 明确工坊与基料起点的一条有限供给链；不声称采矿、设施制造或真实客户端点击已经覆盖 */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa", "gregtech", "dreamcraft" })
public final class SurvivalProgressionTests {

    private SurvivalProgressionTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 9000, batch = "survival_finite_material_life")
    public static void finiteWorkshopStockBecomesSteamCoreLvShrineAndOneNativePlayerLifeChain(GameTestHelper helper)
        throws Exception {
        var f = new ProgressionTests.Fixture(helper);
        var route = new Route(f);
        route.prepare();
        helper.onEachTick("finite native boiler/steam turbine/material processing", route::tick);
        helper.succeedWhen(() -> route.complete);
    }

    private static final class Route {

        final ProgressionTests.Fixture f;
        final GameTestHelper helper;
        final ProgressionTests.NativeMachine smelter, assembler;
        final BaseMetaTileEntity steamBoiler, generator;
        final List<BaseMetaTileEntity> lvBoilers = new ArrayList<>();
        final JsonObject report = new JsonObject();
        final JsonArray crafts = new JsonArray(), samples = new JsonArray();
        final ItemStack hammer = tool(IDMetaTool01.HARDHAMMER), mortar = tool(IDMetaTool01.MORTAR);
        final ItemStack steel = ore("ingotSteel", 1), bronze = ore("ingotBronze", 1);
        final ItemStack steelPlate = ore("plateSteel", 1), bronzePlate = ore("plateBronze", 1);
        TileEntityFurnace oven;
        int age, phase;
        boolean complete, steamSpent, euSpent, generatorSteamSpent, assemblerProgress;
        long previousSteam = -1, previousEu = -1;
        int previousGeneratorSteam = -1;

        Route(ProgressionTests.Fixture f) {
            this.f = f;
            helper = f.helper;
            // 复用190同一注册机器放置与实际配方/输出槽，不造替代机器行为
            smelter = new ProgressionTests.NativeMachine(f, 5, ItemList.Machine_Bronze_AlloySmelter);
            assembler = new ProgressionTests.NativeMachine(f, 11, ItemList.Machine_LV_Assembler);
            steamBoiler = place(ItemList.Machine_Bronze_Boiler, 4, 4, ForgeDirection.NORTH);
            lvBoilers.add(place(ItemList.Machine_Steel_Boiler, 9, 4, ForgeDirection.NORTH));
            lvBoilers.add(place(ItemList.Machine_Steel_Boiler, 10, 3, ForgeDirection.NORTH));
            lvBoilers.add(place(ItemList.Machine_Steel_Boiler, 10, 5, ForgeDirection.SOUTH));
            generator = place(ItemList.Generator_Steam_Turbine_LV, 10, 4, ForgeDirection.EAST);
            helper.assertTrue(
                generator.getMetaTileEntity() instanceof MTESteamTurbine,
                "Registered LV generator is not the actual steam turbine");
            helper.setBlock(2, 1, 6, Blocks.furnace);
            oven = (TileEntityFurnace) helper.getWorld()
                .getTileEntity(f.x + 2, f.y + 1, f.z + 6);
            helper.afterTest(() -> {
                if (helper.getWorld()
                    .getTileEntity(oven.xCoord, oven.yCoord, oven.zCoord) != oven) return;
                for (int slot = 0; slot < oven.getSizeInventory(); slot++) oven.setInventorySlotContents(slot, null);
                helper.getWorld()
                    .setBlockToAir(oven.xCoord, oven.yCoord, oven.zCoord);
            });
            for (var stack : f.owner.inventory.mainInventory)
                helper.assertTrue(stack == null || stack.stackSize <= 0, "Unknown item left by facility preparation");
        }

        void prepare() throws Exception {
            report.addProperty("schema", "finite-survival-progression-v1");
            report.addProperty(
                "boundary",
                "existing steam/LV workshop and finite industrial precursor stock; no mining or facility manufacture proof");
            report.addProperty(
                "nativePlayerScope",
                "isolated server MP, native workbench/container/item-use; no real client packet proof");
            report.addProperty(
                "machineOutputExtraction",
                "controlled NativeMachine.takeOutput extraction from actual output inventory; no GT GUI slot-click proof");
            report.addProperty(
                "returnedFrameScope",
                "actual frame returned and native cabinet round-trip; repeat charging reuses the separate 190 two-charge evidence");
            report.addProperty("gregtechFingerprint", DependencyFingerprint.mod("gregtech"));
            report.addProperty("dreamcraftFingerprint", DependencyFingerprint.mod("dreamcraft"));
            report.addProperty("energyTopups", 0);
            report.addProperty("tlmFinishedItemsSeeded", 0);
            report.add(
                "productionArtifact",
                DevelopmentArtifactIdentity.capture(
                    MaidLifecycleData.class,
                    MaidLifecycleService.class,
                    MaidShrineTile.class,
                    MaidLifecycleItems.class,
                    EntityMaid.class,
                    Class.forName("com.tianluoqaq.tlmnh.common.progression.GregTechProgression")));
            report.add(
                "observerArtifact",
                DevelopmentArtifactIdentity.capture(SurvivalProgressionTests.class, ProgressionTests.class));
            var facilities = new JsonArray();
            for (var name : new String[] { "registered bronze alloy smelter", "registered LV assembler",
                "bronze boiler", "three steel boilers", "LV steam turbine directly adjacent to assembler",
                "vanilla furnace", "workbench", "steel hammer, mortar and wrench" })
                facilities.add(new com.google.gson.JsonPrimitive(name));
            report.add("preexistingFacilities", facilities);
            helper.assertTrue(
                steamBoiler.getStoredSteam() == 0 && generator.getStoredEU() == 0
                    && smelter.base.getStoredSteam() == 0
                    && assembler.base.getStoredEU() == 0,
                "Finite generation must start with empty actual machine energy stores");
            seed(
                ore("ingotSteel", 64),
                ore("ingotBronze", 16),
                ore("ingotCastIron", 4),
                ore("gemLapis", 3),
                new ItemStack(Items.redstone),
                new ItemStack(Blocks.planks, 64),
                new ItemStack(Blocks.log, 4),
                new ItemStack(Items.flint),
                new ItemStack(Blocks.wool, 6),
                new ItemStack(Items.wheat, 2),
                new ItemStack(Items.sugar, 2),
                new ItemStack(Items.coal, 64),
                ore("plateWood", 2),
                ore("wireGt01Copper", 36),
                ore("wireFineCopper", 16),
                ore("dustCoal", 4),
                ItemList.IC2_Resin.get(12),
                ItemList.Circuit_Parts_Glass_Tube.get(4),
                ore("stickSteel", 8),
                ore("boltRedAlloy", 4),
                ore("cableGt01RedAlloy", 6),
                ore("itemCasingSteel", 2),
                ore("cableGt01Tin", 2),
                hammer,
                mortar,
                tool(IDMetaTool01.WRENCH));
            for (int i = 0; i < 6; i++) seed(new ItemStack(Items.water_bucket));
            report.add("initialPlayerInventory", SleepAttributePortSnapshot.binaryNbt(inventory()));
            for (var base : boilers()) {
                var boiler = (MTEBoiler) base.getMetaTileEntity();
                helper.assertTrue(
                    boiler.mTemperature == 20 && boiler.mProcessingEnergy == 0
                        && boiler.getFillableStack() == null
                        && boiler.mSteam == null,
                    "Boiler did not start cold, empty and unfueled");
                boiler.setInventorySlotContents(0, take(new ItemStack(Items.water_bucket), 1));
                boiler.setInventorySlotContents(2, take(new ItemStack(Items.coal), base == steamBoiler ? 15 : 16));
                base.markInventoryBeenModified();
            }
            oven.setInventorySlotContents(1, take(new ItemStack(Items.coal), 1));
            for (int i = 0; i < 20; i++) craft(steelPlate, grid(0, hammer, 3, steel, 6, steel));
            for (int i = 0; i < 3; i++) craft(bronzePlate, grid(0, hammer, 3, bronze, 6, bronze));
            for (int i = 0; i < 2; i++)
                craft(ore("plateCastIron", 1), grid(0, hammer, 3, ore("ingotCastIron", 1), 6, ore("ingotCastIron", 1)));
            helper.assertTrue(
                toolDamage(find(hammer)) > 0,
                "Native material shaping did not consume real hammer durability");
            craft(
                new ItemStack(MaidResurrectionContent.EMPTY_SLAB),
                new ItemStack[] { ore("gemLapis", 1), steelPlate, ore("gemLapis", 1), steelPlate, bronzePlate,
                    steelPlate, bronzePlate, new ItemStack(Items.redstone), bronzePlate });
            for (int i = 0; i < 2; i++) {
                craft(ore("dustWheat", 1), grid(0, new ItemStack(Items.wheat), 3, mortar));
                craft(ItemList.Food_Dough.get(1), grid(0, new ItemStack(Items.water_bucket), 1, ore("dustWheat", 1)));
                craft(
                    ItemList.Food_Dough_Sugar.get(1),
                    grid(0, ItemList.Food_Dough.get(1), 1, new ItemStack(Items.sugar)));
            }
            craft(
                ItemList.Food_Raw_Cake.get(1),
                grid(
                    0,
                    ItemList.Food_Dough_Sugar.get(1),
                    1,
                    ItemList.Food_Dough_Sugar.get(1),
                    2,
                    ItemList.Food_Dough_Sugar.get(1),
                    3,
                    ItemList.Food_Dough_Sugar.get(1)));
            oven.setInventorySlotContents(0, take(ItemList.Food_Raw_Cake.get(1), 1));
            for (int i = 0; i < 2; i++) {
                craft(
                    ItemList.Circuit_Board_Coated.get(1),
                    grid(0, ore("plateWood", 1), 1, ItemList.IC2_Resin.get(1), 2, ItemList.IC2_Resin.get(1)));
                var wire = ore("wireGt01Copper", 1);
                craft(
                    ItemList.Circuit_Board_Coated_Basic.get(1),
                    new ItemStack[] { wire, wire, wire, wire, ItemList.Circuit_Board_Coated.get(1), wire, wire, wire,
                        wire });
            }
            for (int i = 0; i < 4; i++) {
                var resin = ItemList.IC2_Resin.get(1);
                var wire = ore("wireGt01Copper", 1);
                var fine = ore("wireFineCopper", 1);
                craft(
                    ItemList.Circuit_Parts_Resistor.get(1),
                    new ItemStack[] { resin, fine, resin, wire, ore("dustCoal", 1), wire, null, fine, null });
                craft(
                    ItemList.Circuit_Parts_Vacuum_Tube.get(1),
                    new ItemStack[] { wire, wire, wire, fine, ItemList.Circuit_Parts_Glass_Tube.get(1), fine,
                        ore("stickSteel", 1), ore("boltRedAlloy", 1), ore("stickSteel", 1) });
            }
            for (int i = 0; i < 2; i++) {
                var resistor = ItemList.Circuit_Parts_Resistor.get(1);
                var vacuum = ItemList.Circuit_Parts_Vacuum_Tube.get(1);
                var cable = ore("cableGt01RedAlloy", 1);
                craft(
                    GTModHandler.getIC2Item("electronicCircuit", 1),
                    new ItemStack[] { resistor, ore("itemCasingSteel", 1), resistor, vacuum,
                        ItemList.Circuit_Board_Coated_Basic.get(1), vacuum, cable, cable, cable });
            }
            craft(
                ItemList.Casing_LV.get(1),
                new ItemStack[] { steelPlate, steelPlate, steelPlate, steelPlate, tool(IDMetaTool01.WRENCH), steelPlate,
                    steelPlate, steelPlate, steelPlate });
            craft(
                ItemList.Hull_LV.get(1),
                grid(
                    0,
                    ore("plateCastIron", 1),
                    1,
                    steelPlate,
                    2,
                    ore("plateCastIron", 1),
                    3,
                    ore("cableGt01Tin", 1),
                    4,
                    ItemList.Casing_LV.get(1),
                    5,
                    ore("cableGt01Tin", 1)));
            var wood = new ItemStack(Blocks.planks);
            var wool = new ItemStack(Blocks.wool);
            craft(new ItemStack(Items.bed), new ItemStack[] { wool, wool, wool, wood, wood, wood, null, null, null });
            craft(
                new ItemStack(MaidLifeContent.maidBedItem),
                new ItemStack[] { wool, wool, wool, steelPlate, new ItemStack(Items.bed), steelPlate, wood, wood,
                    wood });
            var log = new ItemStack(Blocks.log);
            craft(
                new ItemStack(Blocks.chest),
                new ItemStack[] { log, wood, log, wood, new ItemStack(Items.flint), wood, log, wood, log });
            craft(
                new ItemStack(MaidLifeContent.snackCabinet),
                new ItemStack[] { wood, wood, wood, steelPlate, new ItemStack(Blocks.chest), steelPlate, wood, wood,
                    wood });
            helper.assertTrue(
                count(MaidResurrectionContent.MAID_CORE) == 0
                    && count(Item.getItemFromBlock(MaidResurrectionContent.SHRINE)) == 0,
                "Unprocessed TLM machine output was seeded");
            report.add("crafts", crafts);
            evidence("prepared");
        }

        void tick() {
            if (complete) return;
            age++;
            long steam = smelter.base.getStoredSteam(), eu = assembler.base.getStoredEU();
            steamSpent |= previousSteam >= 0 && steam < previousSteam;
            euSpent |= previousEu >= 0 && eu < previousEu;
            previousSteam = steam;
            previousEu = eu;
            var turbine = (MTESteamTurbine) generator.getMetaTileEntity();
            int turbineSteam = turbine.getFillableStack() == null ? 0 : turbine.getFillableStack().amount;
            generatorSteamSpent |= phase == 2 && previousGeneratorSteam > turbineSteam;
            assemblerProgress |= phase == 2 && assembler.machine.mProgresstime > 0;
            previousGeneratorSteam = turbineSteam;
            if (age % 200 == 0) sample();
            if (phase == 0 && oven.getStackInSlot(2) != null) {
                var baked = oven.decrStackSize(2, 1);
                helper.assertTrue(
                    same(baked, ItemList.Food_Baked_Cake.get(1)) && oven.getStackInSlot(0) == null
                        && oven.furnaceBurnTime > 0,
                    "Finite real furnace did not bake the actually crafted raw cake");
                put(baked);
                phase = 1;
            }
            if (phase == 1 && steam >= 15000 && eu >= assembler.machine.maxEUStore() * 3 / 4 && turbineSteam >= 20000) {
                helper.assertTrue(
                    smelter.output() == null && assembler.output() == null
                        && smelter.machine.mMaxProgresstime == 0
                        && assembler.machine.mMaxProgresstime == 0,
                    "Cold/empty-input machines created a TLM result");
                smelter.inputs(
                    take(new ItemStack(MaidResurrectionContent.EMPTY_SLAB), 1),
                    take(ItemList.Food_Baked_Cake.get(1), 1));
                assembler.inputs(
                    take(steelPlate, 4),
                    take(ore("gemLapis", 1), 1),
                    take(GTModHandler.getIC2Item("electronicCircuit", 1), 2),
                    take(ItemList.Hull_LV.get(1), 1),
                    take(new ItemStack(Blocks.planks), 1));
                helper.assertTrue(
                    RecipeMaps.alloySmelterRecipes.findRecipeQuery()
                        .items(
                            smelter.machine.mInventory[smelter.machine.getInputSlot()],
                            smelter.machine.mInventory[smelter.machine.getInputSlot() + 1])
                        .voltage(8)
                        .find() != null,
                    "Actual crafted frame/cake did not match the native charging recipe");
                phase = 2;
            }
            if (phase == 2 && smelter.output() != null && assembler.output() != null) {
                put(smelter.takeOutput());
                put(assembler.takeOutput());
                smelter.assertInputs();
                assembler.assertInputs();
                helper.assertTrue(
                    steamSpent && generatorSteamSpent
                        && assemblerProgress
                        && count(MaidResurrectionContent.MAID_CORE) == 1
                        && count(Item.getItemFromBlock(MaidResurrectionContent.SHRINE)) == 1,
                    "Finite native steam/LV inputs did not become exactly one of each actual output");
                for (var base : boilers()) {
                    var boiler = (MTEBoiler) base.getMetaTileEntity();
                    helper.assertTrue(
                        boiler.mTemperature > 100 && boiler.getFillableStack() != null
                            && boiler.getFillableStack().amount < 1000
                            && boiler.getFillableStack().amount > 0,
                        "Actual boiler did not consume only its initial finite water or ran dry");
                }
                life();
                report.addProperty("completedTick", age);
                report.addProperty("steamConsumedObserved", steamSpent);
                report.addProperty("consumerNetEuDecreaseObserved", euSpent);
                report.addProperty("turbineSteamConsumedDuringAssembly", generatorSteamSpent);
                report.addProperty("nativeAssemblyProgressObserved", assemblerProgress);
                report.addProperty("lifeChainPassed", true);
                report.add("finalPlayerInventory", SleepAttributePortSnapshot.binaryNbt(inventory()));
                sample();
                evidence("completed");
                complete = true;
                System.out.println(
                    "TLMNH_FINITE_SURVIVAL_PASS materials=native-crafts steam=finite-boiler lv=finite-turbine life=one-native-player tick="
                        + age);
            }
        }

        void life() {
            equip(new ItemStack(MaidResurrectionContent.MAID_CORE));
            use(13, 4);
            var maids = f.maids();
            helper.assertTrue(
                maids.size() == 1 && maids.get(0)
                    .isOwner(f.owner)
                    && count(MaidResurrectionContent.MAID_CORE) == 0
                    && count(MaidResurrectionContent.EMPTY_SLAB) == 1,
                "Actually processed player core did not produce one bound maid and reusable frame");
            var maid = maids.get(0);
            maid.getBrainCoordinator()
                .setSuspended(true);
            maid.setSitting(true);
            equip(new ItemStack(MaidLifeContent.maidBedItem));
            use(15, 1);
            helper.assertTrue(
                helper.getWorld()
                    .getBlock(f.x + 15, f.y + 1, f.z + 1) == MaidLifeContent.maidBed
                    && count(MaidLifeContent.maidBedItem) == 0,
                "Crafted bed did not use its ordinary placement entry");
            equip(new ItemStack(MaidLifeContent.snackCabinet));
            use(17, 1);
            var cabinet = (TileEntitySnackCabinet) helper.getWorld()
                .getTileEntity(f.x + 17, f.y + 1, f.z + 1);
            f.owner.setPosition(f.x + 17.5, f.y + 1, f.z + 2.5);
            clearHand();
            helper.getWorld()
                .getBlock(cabinet.xCoord, cabinet.yCoord, cabinet.zCoord)
                .onBlockActivated(
                    helper.getWorld(),
                    cabinet.xCoord,
                    cabinet.yCoord,
                    cabinet.zCoord,
                    f.owner,
                    2,
                    .5F,
                    .5F,
                    .5F);
            helper.assertTrue(
                f.owner.openContainer instanceof ContainerChest && f.owner.openContainer.canInteractWith(f.owner),
                "Ordinary crafted cabinet interaction did not open its native authorized container");
            var container = f.owner.openContainer;
            var frame = take(new ItemStack(MaidResurrectionContent.EMPTY_SLAB), 1);
            var exactFrame = frame.copy();
            f.owner.inventory.setItemStack(frame);
            container.slotClick(0, 0, 0, f.owner);
            helper.assertTrue(
                f.owner.inventory.getItemStack() == null
                    && ItemStack.areItemStacksEqual(cabinet.getStackInSlot(0), exactFrame),
                "Native cabinet click did not deposit the returned real frame");
            container.slotClick(0, 0, 0, f.owner);
            helper.assertTrue(
                cabinet.getStackInSlot(0) == null
                    && ItemStack.areItemStacksEqual(f.owner.inventory.getItemStack(), exactFrame),
                "Native cabinet click did not return the same complete frame");
            put(f.owner.inventory.getItemStack());
            f.owner.inventory.setItemStack(null);
            container.onContainerClosed(f.owner);
            f.owner.openContainer = f.owner.inventoryContainer;
            equip(new ItemStack(MaidResurrectionContent.SHRINE));
            use(17, 5);
            var shrine = (MaidShrineTile) helper.getWorld()
                .getTileEntity(f.x + 17, f.y + 1, f.z + 5);
            helper.assertTrue(
                shrine != null && count(Item.getItemFromBlock(MaidResurrectionContent.SHRINE)) == 0,
                "Actually assembled shrine did not use its ordinary ItemBlock placement entry");
            maid.setHealth(0);
            maid.onDeath(DamageSource.generic);
            var ledger = MaidLifecycleData.get(helper.getWorld());
            var token = ledger.deathToken(maid.getUniqueID());
            MaidTombstoneEntity grave = null;
            for (Object value : helper.getWorld().loadedEntityList.toArray())
                if (value instanceof MaidTombstoneEntity candidate && token.equals(candidate.getToken()))
                    grave = candidate;
            helper.assertTrue(grave != null, "The actually acquired maid did not retain its native death film");
            f.owner.setPosition(grave.posX + 1, grave.posY, grave.posZ);
            helper.assertTrue(
                MaidTombstoneTransfer.collect(grave, f.owner),
                "Native owner could not retrieve actual death film");
            equip(new ItemStack(MaidResurrectionContent.FILM));
            f.owner.setPosition(shrine.xCoord + .5, shrine.yCoord, shrine.zCoord + 2.5);
            clearSneak();
            activate(shrine);
            helper.assertTrue(
                shrine.getFilm() != null && token.equals(MaidLifecycleItems.token(shrine.getFilm()))
                    && count(MaidResurrectionContent.FILM) == 0,
                "Ordinary shrine insertion lost the actual single-use film");
            clearHand();
            helper.assertTrue(
                f.owner.getHealth() == 20,
                "Original survival player health changed before native revival cost");
            activate(shrine);
            helper.assertTrue(
                shrine.getFilm() == null && f.owner.getHealth() == .25F
                    && ledger.reserve(token, MaidLifecycleData.Kind.FILM) == null,
                "Ordinary shrine revival did not consume its one film and actual survival life cost");
            int alive = 0;
            for (var candidate : f.maids())
                if (candidate.isEntityAlive() && MaidSpawnChecks.present(candidate)) alive++;
            helper.assertTrue(
                alive == 1,
                "Normal finite acquisition/death/revival chain did not end with one live owned maid");
        }

        void craft(ItemStack expected, ItemStack[] recipe) {
            helper.setBlock(2, 1, 2, Blocks.crafting_table);
            f.owner.setPosition(f.x + 3.5, f.y + 1, f.z + 2.5);
            helper.assertTrue(f.owner.inventory.getItemStack() == null, "Unknown cursor item before material craft");
            var container = new ContainerWorkbench(f.owner.inventory, helper.getWorld(), f.x + 2, f.y + 1, f.z + 2);
            helper.assertTrue(container.canInteractWith(f.owner), "Actual workbench permission denied");
            f.owner.openContainer = container;
            var before = inventory();
            for (int slot = 0; slot < recipe.length; slot++)
                if (recipe[slot] != null) container.craftMatrix.setInventorySlotContents(slot, take(recipe[slot], 1));
            var output = container.getSlot(0)
                .getStack();
            helper.assertTrue(
                output != null && same(output, expected),
                "Live native crafting route missing for " + expected
                    + " grid="
                    + Arrays.toString(recipe)
                    + " actual="
                    + output);
            var actualOutput = output.copy();
            container.slotClick(0, 0, 0, f.owner);
            helper.assertTrue(
                ItemStack.areItemStacksEqual(actualOutput, f.owner.inventory.getItemStack()),
                "Native workbench did not deliver its actual registered result");
            put(f.owner.inventory.getItemStack());
            f.owner.inventory.setItemStack(null);
            var remainders = new JsonArray();
            for (int slot = 0; slot < container.craftMatrix.getSizeInventory(); slot++) {
                var remaining = container.craftMatrix.getStackInSlot(slot);
                if (remaining == null) continue;
                // SlotCrafting留下真实磨损工具/容器；关闭工作台前原生shift-click收回，不能补造余物
                var exact = remaining.copy();
                int beforeCount = exactCount(exact);
                var moved = container.transferStackInSlot(f.owner, slot + 1);
                helper.assertTrue(
                    ItemStack.areItemStacksEqual(exact, moved) && container.craftMatrix.getStackInSlot(slot) == null
                        && exactCount(exact) == beforeCount + exact.stackSize
                        && f.owner.inventory.getItemStack() == null,
                    "Native workbench remainder was not completely recovered with its actual NBT: " + exact);
                var remainder = new JsonObject();
                remainder.addProperty("matrixSlot", slot);
                remainder.addProperty("inventoryCountBefore", beforeCount);
                remainder.addProperty("inventoryCountAfter", exactCount(exact));
                remainder
                    .add("nativeStack", SleepAttributePortSnapshot.binaryNbt(exact.writeToNBT(new NBTTagCompound())));
                remainders.add(remainder);
            }
            container.onContainerClosed(f.owner);
            f.owner.openContainer = f.owner.inventoryContainer;
            var step = new JsonObject();
            step.add("before", SleepAttributePortSnapshot.binaryNbt(before));
            step.add("output", SleepAttributePortSnapshot.binaryNbt(actualOutput.writeToNBT(new NBTTagCompound())));
            step.add("nativeRemaindersRecovered", remainders);
            step.add("after", SleepAttributePortSnapshot.binaryNbt(inventory()));
            crafts.add(step);
        }

        BaseMetaTileEntity place(ItemList item, int x, int z, ForgeDirection front) {
            f.hand(item.get(1));
            use(x, z);
            var tile = helper.getWorld()
                .getTileEntity(f.x + x, f.y + 1, f.z + z);
            helper.assertTrue(
                tile instanceof BaseMetaTileEntity,
                "Native infrastructure ItemBlock placement failed: " + item);
            var base = (BaseMetaTileEntity) tile;
            base.setFrontFacing(front);
            base.enableTicking();
            helper.afterTest(() -> {
                if (helper.getWorld()
                    .getTileEntity(base.xCoord, base.yCoord, base.zCoord) != base) return;
                var meta = (MetaTileEntity) base.getMetaTileEntity();
                Arrays.fill(meta.mInventory, null);
                helper.getWorld()
                    .setBlockToAir(base.xCoord, base.yCoord, base.zCoord);
            });
            return base;
        }

        List<BaseMetaTileEntity> boilers() {
            var result = new ArrayList<>(lvBoilers);
            result.add(steamBoiler);
            return result;
        }

        void seed(ItemStack... stacks) {
            for (var stack : stacks) {
                helper.assertTrue(
                    stack != null && stack.getItem() != MaidResurrectionContent.EMPTY_SLAB
                        && stack.getItem() != MaidResurrectionContent.MAID_CORE
                        && stack.getItem() != MaidLifeContent.maidBedItem
                        && stack.getItem() != Item.getItemFromBlock(MaidResurrectionContent.SHRINE)
                        && stack.getItem() != Item.getItemFromBlock(MaidLifeContent.snackCabinet),
                    "Forbidden finished TLM seed");
                put(stack.copy());
            }
        }

        void put(ItemStack stack) {
            helper.assertTrue(
                stack != null && f.owner.inventory.addItemStackToInventory(stack) && stack.stackSize == 0,
                "Finite source/real output did not fit actual native player inventory");
        }

        ItemStack take(ItemStack wanted, int count) {
            ItemStack result = null;
            for (int slot = 0; slot < f.owner.inventory.mainInventory.length && count > 0; slot++) {
                var actual = f.owner.inventory.mainInventory[slot];
                if (!same(actual, wanted)) continue;
                var removed = f.owner.inventory.decrStackSize(slot, Math.min(count, actual.stackSize));
                count -= removed.stackSize;
                if (result == null) result = removed;
                else {
                    helper.assertTrue(
                        ItemStack.areItemStackTagsEqual(result, removed),
                        "Different native source NBT must not merge");
                    result.stackSize += removed.stackSize;
                }
            }
            helper.assertTrue(
                count == 0 && result != null,
                "Finite raw material exhausted: " + wanted + " missing=" + count);
            return result;
        }

        ItemStack find(ItemStack wanted) {
            for (var actual : f.owner.inventory.mainInventory) if (same(actual, wanted)) return actual;
            return null;
        }

        int exactCount(ItemStack wanted) {
            int count = 0;
            for (var actual : f.owner.inventory.mainInventory)
                if (same(actual, wanted) && ItemStack.areItemStackTagsEqual(actual, wanted)) count += actual.stackSize;
            return count;
        }

        int count(Item item) {
            int count = 0;
            for (var stack : f.owner.inventory.mainInventory)
                if (stack != null && stack.getItem() == item) count += stack.stackSize;
            return count;
        }

        void equip(ItemStack wanted) {
            for (int i = 0; i < f.owner.inventory.mainInventory.length; i++)
                if (same(f.owner.inventory.mainInventory[i], wanted)) {
                    var displaced = f.owner.inventory.mainInventory[0];
                    f.owner.inventory.mainInventory[0] = f.owner.inventory.mainInventory[i];
                    f.owner.inventory.mainInventory[i] = displaced;
                    f.owner.inventory.currentItem = 0;
                    return;
                }
            throw new IllegalStateException("Actually earned item missing before native use: " + wanted);
        }

        void clearHand() {
            for (int i = 0; i < net.minecraft.entity.player.InventoryPlayer.getHotbarSize(); i++)
                if (f.owner.inventory.mainInventory[i] == null) {
                    f.owner.inventory.currentItem = i;
                    return;
                }
            int empty = f.owner.inventory.getFirstEmptyStack();
            helper.assertTrue(
                empty >= 0 && f.owner.inventory.mainInventory[empty] == null,
                "Actual inventory has no native permitted destination to clear the hand");
            int selected = f.owner.inventory.currentItem;
            f.owner.inventory.mainInventory[empty] = f.owner.inventory.mainInventory[selected];
            f.owner.inventory.mainInventory[selected] = null;
        }

        void clearSneak() {
            f.owner.setSneaking(false);
        }

        void use(int x, int z) {
            f.owner.setPosition(f.x + x + .5, f.y + 1, f.z + z + 2.5);
            helper.assertTrue(
                f.owner.theItemInWorldManager.activateBlockOrUseItem(
                    f.owner,
                    helper.getWorld(),
                    f.owner.getCurrentEquippedItem(),
                    f.x + x,
                    f.y,
                    f.z + z,
                    1,
                    .5F,
                    1,
                    .5F),
                "Native ordinary item use rejected at " + x + "/" + z);
        }

        void activate(MaidShrineTile shrine) {
            helper.assertTrue(
                helper.getWorld()
                    .getBlock(shrine.xCoord, shrine.yCoord, shrine.zCoord)
                    .onBlockActivated(
                        helper.getWorld(),
                        shrine.xCoord,
                        shrine.yCoord,
                        shrine.zCoord,
                        f.owner,
                        2,
                        .5F,
                        .5F,
                        .5F),
                "Ordinary shrine block activation rejected");
        }

        NBTTagCompound inventory() {
            var result = new NBTTagCompound();
            result.setTag(
                "Inventory",
                f.owner.inventory.writeToNBT(new NBTTagList())
                    .copy());
            result.setInteger("SelectedItemSlot", f.owner.inventory.currentItem);
            return result;
        }

        void sample() {
            var sample = new JsonObject();
            sample.addProperty("tick", age);
            sample.addProperty("phase", phase);
            sample.addProperty("alloySteam", smelter.base.getStoredSteam());
            sample.addProperty("assemblerEU", assembler.base.getStoredEU());
            sample.addProperty("generatorEU", generator.getStoredEU());
            sample.addProperty("alloyProgress", smelter.machine.mProgresstime);
            sample.addProperty("assemblerProgress", assembler.machine.mProgresstime);
            var boilers = new JsonArray();
            for (var base : boilers()) {
                var raw = new NBTTagCompound();
                base.writeToNBT(raw);
                boilers.add(SleepAttributePortSnapshot.binaryNbt(raw));
            }
            sample.add("nativeBoilers", boilers);
            samples.add(sample);
            report.add("samples", samples);
            evidence("running");
        }

        void evidence(String state) {
            report.addProperty("state", state);
            try {
                Files.write(
                    Paths.get(
                        System.getProperty("horizonqa.reportDir"),
                        "survival-finite-" + f.owner.getUniqueID() + ".json"),
                    new GsonBuilder().setPrettyPrinting()
                        .create()
                        .toJson(report)
                        .getBytes(StandardCharsets.UTF_8));
            } catch (Exception failure) {
                throw new IllegalStateException("Cannot retain finite progression evidence", failure);
            }
        }
    }

    private static ItemStack[] grid(Object... entries) {
        var result = new ItemStack[9];
        for (int i = 0; i < entries.length; i += 2) result[(Integer) entries[i]] = (ItemStack) entries[i + 1];
        return result;
    }

    private static boolean same(ItemStack a, ItemStack b) {
        return a != null && b != null && a.getItem() == b.getItem() && a.getItemDamage() == b.getItemDamage();
    }

    private static ItemStack ore(String name, int count) {
        var result = GTOreDictUnificator.get(name, count);
        if (result == null || result.getItemDamage() == OreDictionary.WILDCARD_VALUE)
            throw new IllegalStateException("Actual concrete material absent: " + name);
        return result;
    }

    private static ItemStack tool(IDMetaTool01 kind) {
        try {
            var type = Class.forName("gregtech.common.items.MetaGeneratedTool01");
            var parent = Class.forName("gregtech.api.items.MetaGeneratedTool");
            var field = type.getField("INSTANCE");
            var factory = type
                .getMethod("getToolWithStats", int.class, int.class, Materials.class, Materials.class, long[].class);
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != type
                || Modifier.isStatic(factory.getModifiers())
                || !Modifier.isFinal(factory.getModifiers())
                || factory.getDeclaringClass() != parent
                || factory.getReturnType() != ItemStack.class)
                throw new IllegalStateException("Actual native tool factory API shape differs");
            var instance = field.get(null);
            if (instance == null || instance.getClass() != type)
                throw new IllegalStateException("Actual registered tool factory instance differs");
            var result = factory.invoke(instance, kind.ID, 1, Materials.Steel, Materials.Wood, null);
            if (!(result instanceof ItemStack stack) || stack.stackSize != 1 || stack.getItem() != instance)
                throw new IllegalStateException("Actual native tool factory did not return its own single tool");
            return stack;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot invoke actual native tool factory", failure);
        }
    }

    private static long toolDamage(ItemStack stack) {
        try {
            var type = Class.forName("gregtech.api.items.MetaGeneratedTool");
            var getter = type.getMethod("getToolDamage", ItemStack.class);
            if (!Modifier.isStatic(getter.getModifiers()) || getter.getDeclaringClass() != type
                || getter.getReturnType() != long.class
                || stack == null
                || !type.isInstance(stack.getItem()))
                throw new IllegalStateException("Actual native tool-damage API shape differs");
            return (Long) getter.invoke(null, stack);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot read actual native tool wear", failure);
        }
    }
}
