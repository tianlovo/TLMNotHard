package com.tianluoqaq.tlmnh.qa;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.nbt.NBTTagCompound;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.compat.CompatServices;
import com.tianluoqaq.tlmnh.common.work.bell.BellPreviewSnapshot;
import com.tianluoqaq.tlmnh.common.work.bell.BreedingBellData;
import com.tianluoqaq.tlmnh.common.work.bell.MaidBellPreviewNetwork;
import com.tianluoqaq.tlmnh.common.work.bell.MaintenanceBellData;
import com.tianluoqaq.tlmnh.common.work.coordinator.MaidWorkItems;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class WorkBellNativeTests {

    private WorkBellNativeTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 80, batch = "work_bells")
    public static void maintenanceBellUsesSneakInteractionForSixteenSeparatedChunksAndRejectsOverflow(
        GameTestHelper h) {
        var f = new LifecycleConservationTests.Fixture(h);
        var bell = new ItemStack(MaidWorkItems.maintenanceBell);
        f.owner.inventory.setInventorySlotContents(0, bell);
        int dim = h.getWorld().provider.dimensionId;
        h.assertTrue(!use(f, bell, 0, f.y, 0), "Non-sneaking interaction consumed a bell action");
        h.assertTrue(
            !MaintenanceBellData.read(bell)
                .isBound(),
            "Non-sneaking interaction bound a chunk");
        f.owner.setSneaking(true);
        for (int x = 0; x < 4; x++) for (int z = 0; z < 4; z++)
            h.assertTrue(use(f, bell, x * 32, f.y, z * 32), "Sneaking bell use was rejected");
        h.assertTrue(
            MaintenanceBellData.read(bell)
                .chunkCount() == 16,
            "Separated chunks did not reach sixteen");
        var full = bell.copy();
        use(f, bell, 128, f.y, 0);
        h.assertTrue(ItemStack.areItemStacksEqual(full, bell), "Seventeenth chunk changed the full bell");
        use(f, bell, 0, f.y, 0);
        h.assertTrue(
            MaintenanceBellData.read(bell)
                .chunkCount() == 15,
            "Second click did not remove its chunk");
        var beforeSpan = bell.copy();
        use(f, bell, 144, f.y, 0);
        h.assertTrue(ItemStack.areItemStacksEqual(beforeSpan, bell), "Over-distance chunk changed the bell");
        use(f, bell, 128, f.y, 0);
        h.assertTrue(
            MaintenanceBellData.read(bell)
                .chunkCount() == 16,
            "Allowed boundary chunk was rejected");
        MaintenanceBellData.clear(bell);
        MaintenanceBellData.toggleChunk(bell, dim + 1, 0, 0);
        var foreign = bell.copy();
        use(f, bell, 0, f.y, 0);
        h.assertTrue(ItemStack.areItemStacksEqual(foreign, bell), "Wrong-dimension use rewrote an existing domain");
        h.succeed();
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 80, batch = "work_bells")
    public static void registeredShapelessRecipesClearOnlyBindingAndPreserveAllOtherNbt(GameTestHelper h) {
        var f = new LifecycleConservationTests.Fixture(h);
        for (boolean maintenance : new boolean[] { true, false }) {
            var bell = new ItemStack(maintenance ? MaidWorkItems.maintenanceBell : MaidWorkItems.breedingBell);
            bell.setStackDisplayName("[QA] Preserved custom bell");
            bell.addEnchantment(Enchantment.unbreaking, 3);
            var unknown = new NBTTagCompound();
            unknown.setString("payload", "foreign-addon-value");
            bell.getTagCompound()
                .setTag("UnknownAddon", unknown);
            if (maintenance) MaintenanceBellData.toggleChunk(bell, h.getWorld().provider.dimensionId, -3, 5);
            else BreedingBellData.bind(bell, h.getWorld().provider.dimensionId, f.x + 6, f.y, f.z + 4);
            var original = bell.copy();
            var expected = bell.copy();
            if (maintenance) MaintenanceBellData.clear(expected);
            else BreedingBellData.clear(expected);
            var crafting = new InventoryCrafting(new Container() {

                @Override
                public boolean canInteractWith(EntityPlayer player) {
                    return true;
                }
            }, 3, 3);
            crafting.setInventorySlotContents(7, bell);
            var result = CraftingManager.getInstance()
                .findMatchingRecipe(crafting, h.getWorld());
            h.assertTrue(
                ItemStack.areItemStacksEqual(expected, result),
                "Registered clear recipe lost non-binding NBT");
            h.assertTrue(ItemStack.areItemStacksEqual(original, bell), "Recipe mutated its input before crafting");
            crafting.setInventorySlotContents(1, new ItemStack(Items.stick));
            h.assertTrue(
                CraftingManager.getInstance()
                    .findMatchingRecipe(crafting, h.getWorld()) == null,
                "Clear recipe accepted an unrelated additional ingredient");
            crafting.setInventorySlotContents(1, null);
            crafting.setInventorySlotContents(7, result);
            h.assertTrue(
                CraftingManager.getInstance()
                    .findMatchingRecipe(crafting, h.getWorld()) == null,
                "Already-unbound bell matched the clear recipe");
        }
        h.succeed();
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 80, batch = "work_bells")
    public static void breedingBellValidatesActualThreeByThreeSoilAndNormalizesCropStickClicks(GameTestHelper h) {
        var f = new LifecycleConservationTests.Fixture(h);
        var provider = CompatServices.cropsNhBreeding();
        h.assertTrue(provider.isAvailable(), "Real CropsNH provider unavailable");
        int x = f.x + 6, z = f.z + 4;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            h.getWorld()
                .setBlock(x + dx, f.y, z + dz, Blocks.farmland, 7, 3);
            h.getWorld()
                .setBlockToAir(x + dx, f.y + 1, z + dz);
        }
        var bell = new ItemStack(MaidWorkItems.breedingBell);
        f.owner.inventory.setInventorySlotContents(0, bell);
        f.owner.setSneaking(true);
        use(f, bell, x, f.y, z);
        h.assertTrue(
            BreedingBellData.read(bell)
                .matches(h.getWorld().provider.dimensionId, x, f.y, z),
            "Accepted soil was not bound to its three-by-three center");
        use(f, bell, x, f.y, z);
        h.assertTrue(
            !BreedingBellData.read(bell)
                .isBound(),
            "Second center click did not clear the plot");
        h.getWorld()
            .setBlock(x + 1, f.y + 1, z + 1, Blocks.stone);
        use(f, bell, x, f.y, z);
        h.assertTrue(
            !BreedingBellData.read(bell)
                .isBound(),
            "Obstructed outer plot cell was accepted");
        h.getWorld()
            .setBlockToAir(x + 1, f.y + 1, z + 1);
        h.getWorld()
            .setBlock(x - 1, f.y, z - 1, Blocks.bedrock);
        use(f, bell, x, f.y, z);
        h.assertTrue(
            !BreedingBellData.read(bell)
                .isBound(),
            "Invalid outer soil cell was accepted");
        h.getWorld()
            .setBlock(x - 1, f.y, z - 1, Blocks.farmland, 7, 3);
        var sticks = provider.createCropSticksStack(2);
        h.assertTrue(
            provider.placeOrUpgradeCropStick(h.getWorld(), x, f.y + 1, z, sticks, false),
            "Actual crop stick placement failed");
        use(f, bell, x, f.y + 1, z);
        h.assertTrue(
            BreedingBellData.read(bell)
                .matches(h.getWorld().provider.dimensionId, x, f.y, z),
            "Clicking actual crop sticks used the crop layer as soil");
        h.succeed();
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 80, batch = "work_bells")
    public static void wornBellPreviewRequiresNearbySneakingOwnerAndDropsRemovedOrLockedBellData(GameTestHelper h) {
        var f = new LifecycleConservationTests.Fixture(h);
        var maid = new EntityMaid(h.getWorld());
        f.owned.add(maid);
        h.assertTrue(maid.bindTo(f.owner.getUniqueID()), "Maid binding failed");
        h.spawnEntity(maid, 6.5, 1, 4.5);
        maid.getBrainCoordinator()
            .setSuspended(true);
        maid.setFavorability(0);
        var maintenance = new ItemStack(MaidWorkItems.maintenanceBell);
        var breeding = new ItemStack(MaidWorkItems.breedingBell);
        MaintenanceBellData.toggleChunk(maintenance, h.getWorld().provider.dimensionId, f.x >> 4, f.z >> 4);
        BreedingBellData.bind(breeding, h.getWorld().provider.dimensionId, f.x + 6, f.y, f.z + 4);
        maid.getMaidBaubleInventory()
            .setInventorySlotContents(0, maintenance);
        maid.getMaidBaubleInventory()
            .setInventorySlotContents(1, breeding);
        h.assertTrue(
            MaidBellPreviewNetwork.resolvePreviewableMaid(f.owner, maid.getEntityId()) == null,
            "Non-sneaking owner obtained a worn preview");
        f.owner.setSneaking(true);
        h.assertTrue(
            MaidBellPreviewNetwork.resolvePreviewableMaid(f.owner, maid.getEntityId()) == maid,
            "Nearby sneaking owner was denied");
        var snapshot = BellPreviewSnapshot.captureFromMaid(maid);
        h.assertTrue(
            snapshot.hasMaintenance() && snapshot.hasBreeding(),
            "Equipped unlocked bells missing from snapshot");
        maid.func_152115_b(
            java.util.UUID.randomUUID()
                .toString());
        h.assertTrue(
            MaidBellPreviewNetwork.resolvePreviewableMaid(f.owner, maid.getEntityId()) == null,
            "Foreign owner obtained a worn preview");
        maid.func_152115_b(
            f.owner.getUniqueID()
                .toString());
        f.owner.setPosition(maid.posX + 9, maid.posY, maid.posZ);
        h.assertTrue(
            MaidBellPreviewNetwork.resolvePreviewableMaid(f.owner, maid.getEntityId()) == null,
            "Distant owner obtained a worn preview");
        maid.getMaidBaubleInventory()
            .setInventorySlotContents(0, null);
        maid.getMaidBaubleInventory()
            .setInventorySlotContents(1, null);
        maid.getMaidBaubleInventory()
            .setInventorySlotContents(10, maintenance);
        snapshot = BellPreviewSnapshot.captureFromMaid(maid);
        h.assertTrue(
            !snapshot.hasMaintenance() && !snapshot.hasBreeding(),
            "Removed or locked bell retained a preview domain");
        maid.getMaidBaubleInventory()
            .setInventorySlotContents(0, breeding);
        BreedingBellData.clear(breeding);
        h.assertTrue(
            !BellPreviewSnapshot.captureFromMaid(maid)
                .hasBreeding(),
            "Unbound equipped bell retained a preview domain");
        h.succeed();
    }

    private static boolean use(LifecycleConservationTests.Fixture f, ItemStack bell, int x, int y, int z) {
        return bell.getItem()
            .onItemUseFirst(bell, f.owner, f.helper.getWorld(), x, y, z, 1, 0.5F, 1F, 0.5F);
    }
}
