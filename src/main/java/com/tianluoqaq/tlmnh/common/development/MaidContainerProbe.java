package com.tianluoqaq.tlmnh.common.development;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaid;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaidModels;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventory;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelCatalog;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelSelection;
import com.tianluoqaq.tlmnh.common.proxy.CommonProxy;

/** 在实际模组环境中检查容器移动守恒和逐次权限，临时物品只存在于夹具 */
public final class MaidContainerProbe {

    private MaidContainerProbe() {}

    public static void run(WorldServer world) {
        var owner = new FakePlayer(
            world,
            new GameProfile(UUID.fromString("6b7c10a6-76be-4149-96ec-0bbfe21738d4"), "[TLMNH-Test]"));
        var stranger = new FakePlayer(
            world,
            new GameProfile(UUID.fromString("596b6895-2286-449b-9653-395d847293da"), "[TLMNH-Other]"));
        var maid = new EntityMaid(world);
        var spawn = world.getSpawnPoint();
        int x = spawn.posX + 4;
        int z = spawn.posZ + 4;
        world.getChunkFromBlockCoords(x, z);
        double y = world.getTopSolidOrLiquidBlock(x, z);
        owner.setPosition(x + 0.5D, y, z + 0.5D);
        stranger.setPosition(x + 0.5D, y, z + 0.5D);
        maid.setPosition(x + 0.5D, y, z + 0.5D);
        maid.bindTo(owner.getUniqueID());
        require(world.spawnEntityInWorld(maid), "temporary maid spawn");
        try {
            verifyOriginalLayout(world, owner, stranger, maid);
            var inventory = maid.getMaidInventory();
            var container = new ContainerMaid(owner.inventory, maid);
            require(container.canInteractWith(owner), "owner access");
            require(!container.canInteractWith(stranger), "non-owner access");
            var proxy = new CommonProxy();
            require(
                proxy.getServerGuiElement(
                    CommonProxy.GUI_MAID,
                    owner,
                    world,
                    maid.getEntityId(),
                    0,
                    0) instanceof ContainerMaid,
                "server GUI owner routing");
            require(
                proxy.getServerGuiElement(CommonProxy.GUI_MAID, stranger, world, maid.getEntityId(), 0, 0) == null,
                "server GUI non-owner denial");
            require(
                proxy.getServerGuiElement(127, owner, world, maid.getEntityId(), 0, 0) == null,
                "unknown GUI denial");
            require(
                proxy.getClientGuiElement(CommonProxy.GUI_MAID, owner, world, maid.getEntityId(), 0, 0) == null,
                "common side has no client GUI");
            inventory.setInventorySlotContents(0, new ItemStack(Items.arrow, 63));
            inventory.setInventorySlotContents(1, new ItemStack(Items.arrow, 64));
            for (int slot = 2; slot < MaidInventory.BACKPACK_SIZE; slot++)
                inventory.setInventorySlotContents(slot, new ItemStack(Items.stick, 64));
            owner.inventory.setInventorySlotContents(0, new ItemStack(Items.arrow, 64));
            int before = countArrows(maid, owner);
            int hotbarZero = MaidInventory.TOTAL_SIZE + 27;
            require(container.transferStackInSlot(owner, hotbarZero) != null, "partial backpack merge");
            require(
                inventory.getStackInSlot(0).stackSize == 64 && owner.inventory.getStackInSlot(0).stackSize == 63,
                "partial merge sizes");
            require(countArrows(maid, owner) == before, "partial merge conservation");
            require(
                inventory.getStackInSlot(MaidInventory.MAIN_HAND) == null
                    && inventory.getStackInSlot(MaidInventory.OFF_HAND) == null,
                "no hidden hand insertion");
            require(
                container.transferStackInSlot(owner, hotbarZero) == null && countArrows(maid, owner) == before,
                "full backpack conservation");
            require(
                container.transferStackInSlot(stranger, hotbarZero) == null && countArrows(maid, owner) == before,
                "denied move conservation");
            require(
                container.slotClick(0, 0, 0, stranger) == null && countArrows(maid, owner) == before,
                "denied click conservation");

            owner.inventory.setInventorySlotContents(0, new ItemStack(Items.iron_boots));
            container.transferStackInSlot(owner, hotbarZero);
            require(
                inventory.getStackInSlot(MaidInventory.ARMOR_START)
                    .getItem() == Items.iron_boots && owner.inventory.getStackInSlot(0) == null,
                "boots slot");
            owner.inventory.setInventorySlotContents(0, new ItemStack(Items.iron_helmet));
            container.transferStackInSlot(owner, hotbarZero);
            require(
                inventory.getStackInSlot(MaidInventory.ARMOR_START + 3)
                    .getItem() == Items.iron_helmet,
                "helmet slot");
            require(inventory.getStackInSlot(MaidInventory.ARMOR_START + 1) == null, "armor validity");
            owner.inventory.setInventorySlotContents(0, new ItemStack(Items.bow));
            container.transferStackInSlot(owner, hotbarZero);
            require(
                inventory.getStackInSlot(MaidInventory.MAIN_HAND)
                    .getItem() == Items.bow,
                "main-hand bow");

            for (int slot = 0; slot < 36; slot++)
                owner.inventory.setInventorySlotContents(slot, new ItemStack(Items.stick, 64));
            owner.inventory.setInventorySlotContents(0, new ItemStack(Items.arrow, 63));
            before = countArrows(maid, owner);
            container.transferStackInSlot(owner, 0);
            require(
                inventory.getStackInSlot(0).stackSize == 63 && owner.inventory.getStackInSlot(0).stackSize == 64,
                "reverse partial sizes");
            require(countArrows(maid, owner) == before, "reverse merge conservation");
            require(!container.enchantItem(stranger, ContainerMaid.ACTION_ATTACK), "denied command");
            require(!container.enchantItem(owner, 127), "unknown command");
            require(
                container.enchantItem(owner, ContainerMaid.ACTION_ATTACK) && maid.getTask() == MaidTask.ATTACK,
                "owner command");
            require(!container.enchantItem(owner, ContainerMaid.ACTION_RANGED), "same-tick command bound");
            owner.setPosition(x + 100.0D, y, z);
            require(
                !container.canInteractWith(owner) && container.transferStackInSlot(owner, 0) == null,
                "distance revocation");
            maid.ticksExisted++;
            require(!container.enchantItem(owner, ContainerMaid.ACTION_RANGED), "distant command denial");
            owner.setPosition(x + 0.5D, y, z + 0.5D);
            require(container.enchantItem(owner, ContainerMaid.ACTION_RANGED), "valid next-tick command");
            verifyModelSelection(world, owner, stranger, maid, container, proxy);
            world.removeEntity(maid);
            require(!container.canInteractWith(owner), "removed entity revocation");
            TLMNotHard.LOG.info(
                "TLMNH_CONTAINER_TEST_PASS permissions=checked shifts=conserved armor=bounded commands=validated");
        } finally {
            for (int slot = 0; slot < maid.getMaidInventory()
                .getSizeInventory(); slot++)
                maid.getMaidInventory()
                    .setInventorySlotContents(slot, null);
            for (int slot = 0; slot < 36; slot++) owner.inventory.setInventorySlotContents(slot, null);
            world.removeEntity(maid);
        }
    }

    private static void verifyOriginalLayout(WorldServer world, EntityPlayer owner, EntityPlayer stranger,
        EntityMaid maid) {
        var original = new com.github.tartaricacid.touhoulittlemaid.inventory.container.backpack.EmptyBackpackContainer(
            owner.inventory,
            maid);
        var inventory = maid.getMaidInventory();
        require(original.inventorySlots.size() == 48, "original slot count");
        for (int i = 0; i < 36; i++) {
            var slot = original.inventorySlots.get(i);
            require(slot.inventory == owner.inventory, "original player-first inventory");
            require(
                slot.xDisplayPosition == 88 + 18 * (i % 9)
                    && slot.yDisplayPosition == (i < 27 ? 174 + 18 * (i / 9) : 232),
                "original player coordinates");
        }
        int[] x = { 94, 114, 94, 114, 87, 121, 143, 161, 179, 197, 215, 233 };
        int[] y = { 37, 37, 57, 57, 77, 77, 37, 37, 37, 37, 37, 37 };
        for (int i = 0; i < 12; i++) {
            var slot = original.inventorySlots.get(36 + i);
            require(
                slot.inventory == inventory && slot.xDisplayPosition == x[i] && slot.yDisplayPosition == y[i],
                "original maid coordinates");
        }
        require(
            new CommonProxy().getServerGuiElement(CommonProxy.GUI_MAID_ORIGINAL, owner, world, maid.getEntityId(), 0, 0)
                != null,
            "original owner route");
        require(
            new CommonProxy()
                .getServerGuiElement(CommonProxy.GUI_MAID_ORIGINAL, stranger, world, maid.getEntityId(), 0, 0) == null,
            "original stranger route");
        owner.inventory.setInventorySlotContents(0, new ItemStack(Items.iron_helmet));
        original.transferStackInSlot(owner, 27);
        require(
            inventory.getStackInSlot(MaidInventory.ARMOR_START + 3) != null
                && owner.inventory.getStackInSlot(0) == null,
            "original helmet logical mapping");
        owner.inventory.setInventorySlotContents(0, new ItemStack(Items.bow));
        original.transferStackInSlot(owner, 27);
        require(
            inventory.getStackInSlot(MaidInventory.MAIN_HAND) != null && owner.inventory.getStackInSlot(0) == null,
            "original main-hand mapping");
        inventory.setInventorySlotContents(0, new ItemStack(Items.arrow, 63));
        for (int i = 1; i < 6; i++) inventory.setInventorySlotContents(i, new ItemStack(Items.stick, 64));
        owner.inventory.setInventorySlotContents(0, new ItemStack(Items.arrow, 64));
        int before = countArrows(maid, owner);
        original.transferStackInSlot(owner, 27);
        require(
            inventory.getStackInSlot(0).stackSize == 64 && owner.inventory.getStackInSlot(0).stackSize == 63
                && countArrows(maid, owner) == before,
            "original bounded shift conservation");
        require(
            original.transferStackInSlot(stranger, 42) == null && original.slotClick(42, 0, 0, stranger) == null
                && !original.enchantItem(stranger, ContainerMaid.ACTION_HOME),
            "original stranger operations");
        original.transferStackInSlot(owner, 42);
        require(
            countArrows(maid, owner) == before && inventory.getStackInSlot(0) == null,
            "original reverse conservation");
        maid.ticksExisted++;
        maid.setPickupItems(true);
        maid.setPickupExperience(false);
        require(
            original.enchantItem(owner, ContainerMaid.ACTION_PICKUP_ALL) && !maid.canPickupItems()
                && !maid.canPickupExperience(),
            "original combined pickup off");
        maid.ticksExisted++;
        require(
            original.enchantItem(owner, ContainerMaid.ACTION_PICKUP_ALL) && maid.canPickupItems()
                && maid.canPickupExperience(),
            "original combined pickup on");
        for (int i = 0; i < MaidInventory.TOTAL_SIZE; i++) inventory.setInventorySlotContents(i, null);
        for (int i = 0; i < 36; i++) owner.inventory.setInventorySlotContents(i, null);
        TLMNotHard.LOG.info(
            "TLMNH_ORIGINAL_CONTAINER_TEST_PASS slots=48 mapping=original shifts=conserved permissions=checked pickup=authoritative");
    }

    private static void verifyModelSelection(WorldServer world, EntityPlayer owner, EntityPlayer stranger,
        EntityMaid maid, ContainerMaid main, CommonProxy proxy) {
        var models = new ContainerMaidModels(owner.inventory, maid);
        require(models.canInteractWith(owner) && !models.canInteractWith(stranger), "model owner access");
        require(
            proxy.getServerGuiElement(
                CommonProxy.GUI_MAID_MODELS,
                owner,
                world,
                maid.getEntityId(),
                0,
                0) instanceof ContainerMaidModels,
            "model GUI owner routing");
        require(
            proxy.getServerGuiElement(CommonProxy.GUI_MAID_MODELS, stranger, world, maid.getEntityId(), 0, 0) == null,
            "model GUI non-owner denial");
        require(
            proxy.getClientGuiElement(CommonProxy.GUI_MAID_MODELS, owner, world, maid.getEntityId(), 0, 0) == null,
            "model common side has no client GUI");
        String before = maid.getModelId();
        require(!models.enchantItem(stranger, 0) && before.equals(maid.getModelId()), "model non-owner change denial");
        for (int invalid : new int[] { -129, 128, 255, 256, -1 }) require(
            !models.enchantItem(owner, invalid) && before.equals(maid.getModelId()),
            "model invalid action denial");
        maid.ticksExisted++;
        owner.inventory.setItemStack(new ItemStack(Items.stick, 3));
        try {
            require(!main.enchantItem(owner, ContainerMaid.ACTION_MODELS), "model entry cursor denial");
            require(
                proxy.getServerGuiElement(CommonProxy.GUI_MAID_MODELS, owner, world, maid.getEntityId(), 0, 0) == null,
                "model routed cursor denial");
            require(!models.enchantItem(owner, 0), "model selection cursor denial");
            require(
                models.slotClick(-999, 0, 0, owner) == null && models.transferStackInSlot(owner, 0) == null
                    && owner.inventory.getItemStack().stackSize == 3,
                "model cursor and shift conservation");
        } finally {
            owner.inventory.setItemStack(null);
        }
        int accepted = 0, winefox = 0, extraWinefox = 0;
        for (int index = 0; index < BuiltinModelCatalog.choices()
            .size(); index++) {
            maid.ticksExisted++;
            var choice = BuiltinModelCatalog.choices()
                .get(index);
            require(
                models.enchantItem(owner, BuiltinModelSelection.wireAction(index)) && choice.getId()
                    .equals(maid.getModelId()),
                "model fixed-index selection");
            require(!models.enchantItem(owner, 0), "model same-tick bound");
            accepted++;
            if (choice.isWinefox()) {
                winefox++;
                if (!choice.getId()
                    .equals(choice.getBaseId())) extraWinefox++;
            }
        }
        require(accepted == 254 && winefox == 35 && extraWinefox == 14, "model selection coverage");
        double oldX = owner.posX, oldY = owner.posY, oldZ = owner.posZ;
        owner.setPosition(oldX + 100, oldY, oldZ);
        maid.ticksExisted++;
        before = maid.getModelId();
        require(!models.enchantItem(owner, 0) && before.equals(maid.getModelId()), "model distant change denial");
        owner.setPosition(oldX, oldY, oldZ);
        world.removeEntity(maid);
        require(!models.canInteractWith(owner) && !models.enchantItem(owner, 0), "model removed entity denial");
        TLMNotHard.LOG.info(
            "TLMNH_MODEL_SELECTION_TEST_PASS choices={} winefox={} extraWinefox={} permissions=checked wire=signed-byte",
            accepted,
            winefox,
            extraWinefox);
    }

    private static int countArrows(EntityMaid maid, EntityPlayer player) {
        int count = 0;
        for (int slot = 0; slot < MaidInventory.TOTAL_SIZE; slot++) {
            var stack = maid.getMaidInventory()
                .getStackInSlot(slot);
            if (stack != null && stack.getItem() == Items.arrow) count += stack.stackSize;
        }
        for (int slot = 0; slot < 36; slot++) {
            var stack = player.inventory.getStackInSlot(slot);
            if (stack != null && stack.getItem() == Items.arrow) count += stack.stackSize;
        }
        return count;
    }

    private static void require(boolean condition, String name) {
        if (!condition) throw new IllegalStateException("TLMNH container probe failed: " + name);
    }
}
