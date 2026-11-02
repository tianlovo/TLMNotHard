package com.tianluoqaq.tlmnh.qa;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.world.World;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.life.BlockSnackCabinet;
import com.tianluoqaq.tlmnh.common.life.TileEntitySnackCabinet;
import com.tianluoqaq.tlmnh.common.registry.MaidLifeContent;

/** 原生放置/metadata/27格菜单与既有Items/Recovery原NBT；无真实鼠标或客户端投递声明 */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class SnackCabinetStateTests {

    private SnackCabinetStateTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 40, batch = "cabinet_state_inventory")
    public static void nativePlacementFacingCakeDisplayAndChestMenuKeepAllItemsAndRecovery(GameTestHelper helper) {
        var f = new Fixture(helper);
        helper.assertTrue(
            BlockSnackCabinet.facingDirection(
                helper.getWorld()
                    .getBlockMetadata(f.x, f.y, f.z))
                == 2,
            "Yaw0 placement did not face opposite to the player/default North");
        helper.assertTrue(
            World.doesBlockHaveSolidTopSurface(helper.getWorld(), f.x, f.y, f.z),
            "TESR conversion removed the cabinet's solid support");
        f.base.owner.rotationYaw = 90;
        MaidLifeContent.snackCabinet.onBlockPlacedBy(helper.getWorld(), f.x, f.y, f.z, f.base.owner, f.placed);
        helper.assertTrue(
            BlockSnackCabinet.facingDirection(
                helper.getWorld()
                    .getBlockMetadata(f.x, f.y, f.z))
                == 3,
            "Yaw90 placement callback did not produce East facing");
        f.unchanged();
        helper.assertTrue(
            helper.getWorld()
                .setBlock(f.x, f.y + 1, f.z, Blocks.cake, 0, 3),
            "Native cake placement failed");
        helper.assertTrue(
            BlockSnackCabinet.cabinetType(
                helper.getWorld()
                    .getBlockMetadata(f.x, f.y, f.z))
                == BlockSnackCabinet.TYPE_FULL,
            "Native neighbor notification did not establish the full glass display");
        f.unchanged();
        f.base.owner.displayGUIChest(f.cabinet);
        helper.assertTrue(
            f.base.owner.openContainer instanceof ContainerChest chest && chest.getLowerChestInventory() == f.cabinet
                && f.cabinet.getSizeInventory() == 27,
            "Actual native chest menu did not use the same 27-slot cabinet");
        f.unchanged();
        f.base.owner.closeScreen();
        helper.getWorld()
            .setBlockToAir(f.x, f.y + 1, f.z);
        helper.assertTrue(
            BlockSnackCabinet.cabinetType(
                helper.getWorld()
                    .getBlockMetadata(f.x, f.y, f.z))
                == BlockSnackCabinet.TYPE_FULL,
            "Removing cake reset the original persistent glass type");
        f.unchanged();
        helper.succeedWhen(() -> {
            f.unchanged();
            return true;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 40, batch = "cabinet_state_reload")
    public static void oldLoadedCakeInitializesDisplayUnknownMetadataAndForeignTileRemainUntouched(
        GameTestHelper helper) {
        var f = new Fixture(helper);
        // flags2保留旧场景：原蛋糕已在柜上，但此次加载前没有触发邻居事件
        helper.getWorld()
            .setBlock(f.x, f.y + 1, f.z, Blocks.cake, 0, 2);
        var saved = f.saved();
        var loaded = new TileEntitySnackCabinet();
        loaded.readFromNBT(saved);
        helper.getWorld()
            .setTileEntity(f.x, f.y, f.z, loaded);
        f.cabinet = loaded;
        loaded.updateEntity();
        helper.assertTrue(
            BlockSnackCabinet.cabinetType(
                helper.getWorld()
                    .getBlockMetadata(f.x, f.y, f.z))
                == BlockSnackCabinet.TYPE_FULL,
            "Loaded original cake did not initialize the glass display");
        f.unchanged();
        helper.getWorld()
            .setBlockMetadataWithNotify(f.x, f.y, f.z, 12, 2);
        MaidLifeContent.snackCabinet.onNeighborBlockChange(helper.getWorld(), f.x, f.y, f.z, Blocks.cake);
        f.base.owner.rotationYaw = 90;
        MaidLifeContent.snackCabinet.onBlockPlacedBy(helper.getWorld(), f.x, f.y, f.z, f.base.owner, f.placed);
        helper.assertTrue(
            helper.getWorld()
                .getBlockMetadata(f.x, f.y, f.z) == 12,
            "Unknown display metadata was normalized");
        f.unchanged();
        var other = helper.absolute(12, 1, 4);
        helper.setBlock(12, 1, 4, MaidLifeContent.snackCabinet);
        var foreign = new TileEntityChest();
        var apple = new ItemStack(Items.golden_apple, 1, 1);
        foreign.setInventorySlotContents(0, apple.copy());
        helper.getWorld()
            .setTileEntity(other.x(), other.y(), other.z(), foreign);
        helper.getWorld()
            .setBlock(other.x(), other.y() + 1, other.z(), Blocks.cake, 0, 3);
        helper.assertTrue(
            helper.getWorld()
                .getTileEntity(other.x(), other.y(), other.z()) == foreign
                && helper.getWorld()
                    .getBlockMetadata(other.x(), other.y(), other.z()) == 0
                && ItemStack.areItemStacksEqual(apple, foreign.getStackInSlot(0)),
            "Display update altered a foreign tile/item");
        helper.succeedWhen(() -> {
            f.unchanged();
            return true;
        });
    }

    private static final class Fixture {

        final GameTestHelper helper;
        final ModernCoreTests.Fixture base;
        final int x, y, z;
        final ItemStack placed;
        final NBTTagCompound expected;
        TileEntitySnackCabinet cabinet;

        Fixture(GameTestHelper helper) {
            this.helper = helper;
            helper.afterTest(this::close);
            base = new ModernCoreTests.Fixture(helper, false);
            base.maid.getBrainCoordinator()
                .setSuspended(true);
            var pos = helper.absolute(8, 1, 4);
            x = pos.x();
            y = pos.y();
            z = pos.z();
            helper.setBlock(8, 0, 4, Blocks.stone);
            placed = new ItemStack(Item.getItemFromBlock(MaidLifeContent.snackCabinet));
            base.owner.rotationYaw = 0;
            base.owner.inventory.mainInventory[base.owner.inventory.currentItem] = placed;
            helper.assertTrue(
                placed.getItem()
                    .onItemUse(placed, base.owner, helper.getWorld(), x, y - 1, z, 1, .5F, .5F, .5F),
                "Actual native ItemBlock cabinet placement failed");
            helper.assertTrue(placed.stackSize == 0, "Native placement did not consume exactly one cabinet item");
            cabinet = (TileEntitySnackCabinet) helper.getWorld()
                .getTileEntity(x, y, z);
            for (int slot = 0; slot < 27; slot++) {
                var item = new ItemStack(Items.golden_apple, slot + 1, slot % 2);
                var tag = new NBTTagCompound();
                tag.setInteger("OriginalSlot", slot);
                tag.setString("Opaque", "retain_raw_payload");
                item.setTagCompound(tag);
                cabinet.setInventorySlotContents(slot, item);
            }
            var opaque = new NBTTagCompound();
            opaque.setString("id", "future:missing_item");
            opaque.setString("Raw", "do_not_normalize");
            cabinet.recovery()
                .retain(opaque);
            expected = saved();
        }

        NBTTagCompound saved() {
            var tag = new NBTTagCompound();
            cabinet.writeToNBT(tag);
            return tag;
        }

        void unchanged() {
            helper.assertTrue(
                helper.getWorld()
                    .getTileEntity(x, y, z) == cabinet && !cabinet.isInvalid(),
                "Appearance metadata rebuilt the cabinet tile");
            helper.assertTrue(expected.equals(saved()), "Appearance update changed original inventory/recovery NBT");
        }

        void close() {
            if (cabinet == null || expected == null
                || cabinet.isInvalid()
                || helper.getWorld()
                    .getTileEntity(x, y, z) != cabinet)
                return;
            unchanged();
            if (base.owner.openContainer != base.owner.inventoryContainer) base.owner.closeScreen();
            // 本fixture生成的确切原数据已核，清空后再由标准模板清理方块，避免测试物品进入交付账本
            for (int slot = 0; slot < cabinet.getSizeInventory(); slot++) cabinet.setInventorySlotContents(slot, null);
            cabinet.recovery()
                .read(new net.minecraft.nbt.NBTTagList());
        }
    }
}
