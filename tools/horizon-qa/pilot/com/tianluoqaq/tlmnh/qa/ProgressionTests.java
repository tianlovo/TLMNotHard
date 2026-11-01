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
import cpw.mods.fml.relauncher.Side;
import gregtech.api.enums.ItemList;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa", "gregtech", "dreamcraft" })
public final class ProgressionTests {

    private ProgressionTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 40, batch = "progression_crafting")
    public static void actualWorkbenchConsumesFourRecipesAndRejectsEarlierTierSubstitutions(GameTestHelper helper) {
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
        var core = new ItemStack[] { lapis, steel, lapis, steel, cake, steel, bronze, new ItemStack(Items.redstone),
            bronze };
        craft(helper, f, core, MaidResurrectionContent.MAID_CORE);
        var earlier = core.clone();
        earlier[1] = earlier[3] = earlier[5] = ore("plateIron");
        rejects(helper, f, earlier, MaidResurrectionContent.MAID_CORE);
        var circuit = ore("circuitBasic");
        var shrine = new ItemStack[] { steel, lapis, steel, circuit, ItemList.Hull_LV.get(1), circuit, steel, wood,
            steel };
        craft(helper, f, shrine, Item.getItemFromBlock(MaidResurrectionContent.SHRINE));
        var lowerHull = shrine.clone();
        lowerHull[4] = ItemList.Hull_ULV.get(1);
        rejects(helper, f, lowerHull, Item.getItemFromBlock(MaidResurrectionContent.SHRINE));
        var wool = ore("blockWool");
        craft(
            helper,
            f,
            new ItemStack[] { wool, wool, wool, steel, new ItemStack(Items.bed), steel, wood, wood, wood },
            MaidLifeContent.maidBedItem);
        craft(
            helper,
            f,
            new ItemStack[] { wood, wood, wood, steel, new ItemStack(Blocks.chest), steel, wood, wood, wood },
            Item.getItemFromBlock(MaidLifeContent.snackCabinet));
        helper.succeedWhen(() -> true);
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

    private static final class Fixture {

        final GameTestHelper helper;
        final int x, y, z;
        final EntityPlayerMP owner, stranger;
        final HashSet<Entity> original = new HashSet<>();

        Fixture(GameTestHelper helper) {
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
                for (var entity : new ArrayList<>(helper.getWorld().loadedEntityList)) {
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
