package com.tianluoqaq.tlmnh.common.development.inventory;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings.GameType;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.common.compat.CompatServices;
import com.tianluoqaq.tlmnh.common.development.AnimationLab;
import com.tianluoqaq.tlmnh.common.diagnostic.DevelopmentFlags;
import com.tianluoqaq.tlmnh.common.foundation.KnownModIds;
import com.tianluoqaq.tlmnh.common.foundation.ModContext;
import com.tianluoqaq.tlmnh.common.inventory.MaidContainerAccess;
import com.tianluoqaq.tlmnh.common.proxy.CommonProxyProtocol;

/** 独立观察场的实际玩家背包输入，完整物品快照随服务端回执输出 */
public final class MaidInventoryUiFixture {

    private static final Map<UUID, State> ACTIVE = new HashMap<>();

    private MaidInventoryUiFixture() {}

    public static boolean isActivePlayer(EntityPlayerMP player) {
        if (player == null || !AnimationLab.isLab(player.worldObj)) return false;
        var state = ACTIVE.get(player.getUniqueID());
        return state != null && state.player == player
            && !state.restored
            && state.maid.worldObj == player.worldObj
            && state.maid.isEntityAlive()
            && state.maid.isOwner(player);
    }

    public static void protectActivePlayer(EntityPlayerMP player) {
        if (!isActivePlayer(player)) return;
        boolean changed = !player.capabilities.disableDamage || !player.capabilities.allowFlying
            || !player.capabilities.isFlying;
        player.capabilities.disableDamage = player.capabilities.allowFlying = player.capabilities.isFlying = true;
        player.noClip = true;
        player.fallDistance = 0;
        if (changed) player.sendPlayerAbilities();
    }

    public static void run(EntityPlayerMP player, String action) {
        require(
            DevelopmentFlags.isDevCommandsEnabled() && AnimationLab.isLab(player.worldObj),
            "Protected development lab required");
        var state = ACTIVE.get(player.getUniqueID());
        if (action.equals("prepare")) {
            require(
                state == null && player.inventory.getItemStack() == null,
                "Fresh fixture and empty cursor required");
            state = new State(player, AnimationLab.sceneMaid((WorldServer) player.worldObj));
            ACTIVE.put(player.getUniqueID(), state);
            state.prepare();
        } else {
            require(
                state != null && state.player == player
                    && state.maid.worldObj == player.worldObj
                    && state.maid.isEntityAlive()
                    && state.maid.isOwner(player),
                "Same live fixture player and maid required");
            if (action.startsWith("level-")) {
                require(action.matches("level-[0-3]"), "Declared favorability tier required");
                int level = action.charAt(action.length() - 1) - '0';
                player.closeScreen();
                state.maid.setFavorability(
                    state.maid.getFavorabilityManager()
                        .getPointByLevel(level));
                state.open();
            } else if (action.equals("open")) state.open();
            else if (action.equals("finish")) {
                state.finish();
                ACTIVE.remove(player.getUniqueID());
            } else require(action.equals("status"), "Unknown inventory fixture action");
        }
        ModContext.LOG.info("TLMNH_INVENTORY_UI_FIXTURE {}", state.snapshot(action));
    }

    private static final class State {

        final EntityPlayerMP player;
        final EntityMaid maid;
        final ItemStack[] playerItems, maidItems;
        final GameType originalMode;
        final NBTTagCompound originalAbilities = new NBTTagCompound();
        final int selected, favorability;
        final boolean originalNoClip;
        final ItemStack bag, tool;
        boolean restored;

        State(EntityPlayerMP player, EntityMaid maid) {
            this.player = player;
            this.maid = maid;
            require(
                maid != null && MaidContainerAccess.canInteract(player, player, maid)
                    && maid.isOwner(player)
                    && !maid.getCombatHitReceipt()
                        .blocksTransfer(),
                "Owned idle lab maid required");
            playerItems = copy(player.inventory);
            maidItems = copy(maid.getMaidInventory());
            originalMode = player.theItemInWorldManager.getGameType();
            player.capabilities.writeCapabilitiesToNBT(originalAbilities);
            selected = player.inventory.currentItem;
            favorability = maid.getFavorability();
            originalNoClip = player.noClip;
            bag = backpack();
            tool = sample(Items.iron_pickaxe);
            tool.addEnchantment(Enchantment.efficiency, 4);
            tool.addEnchantment(Enchantment.unbreaking, 3);
        }

        void prepare() {
            player.closeScreen();
            clear(player.inventory);
            clear(maid.getMaidInventory());
            player.setGameType(GameType.SURVIVAL);
            player.capabilities.disableDamage = true;
            player.capabilities.allowFlying = true;
            player.capabilities.isFlying = true;
            player.noClip = true;
            player.sendPlayerAbilities();
            player.inventory.currentItem = 0;
            player.inventory.setInventorySlotContents(0, bag.copy());
            player.inventory.setInventorySlotContents(1, tool.copy());
            player.inventoryContainer.detectAndSendChanges();
            open();
        }

        void open() {
            require(player.inventory.getItemStack() == null, "Empty cursor required before navigation");
            player.openGui(
                ModContext.modInstance(),
                CommonProxyProtocol.GUI_MAID_ORIGINAL,
                maid.worldObj,
                maid.getEntityId(),
                0,
                0);
        }

        void finish() {
            require(player.inventory.getItemStack() == null, "Complete the native cursor transaction first");
            int bags = 0, tools = 0;
            for (var inventory : new IInventory[] { player.inventory, maid.getMaidInventory() }) {
                for (int slot = 0; slot < inventory.getSizeInventory(); slot++) {
                    var stack = inventory.getStackInSlot(slot);
                    if (stack == null) continue;
                    if (ItemStack.areItemStacksEqual(stack, bag)) bags++;
                    else if (ItemStack.areItemStacksEqual(stack, tool)) tools++;
                    else throw new IllegalStateException("Unknown inventory successor retained before fixture cleanup");
                }
            }
            require(bags == 1 && tools == 1, "Exactly one complete instance of each generated item required");
            player.closeScreen();
            restore(player.inventory, playerItems);
            restore(maid.getMaidInventory(), maidItems);
            maid.setFavorability(favorability);
            player.inventory.currentItem = selected;
            player.setGameType(originalMode);
            player.capabilities.readCapabilitiesFromNBT(originalAbilities);
            player.noClip = originalNoClip;
            player.sendPlayerAbilities();
            player.inventoryContainer.detectAndSendChanges();
            require(
                equal(player.inventory, playerItems) && equal(maid.getMaidInventory(), maidItems),
                "Original complete inventory was not restored");
            restored = true;
        }

        JsonObject snapshot(String action) {
            var result = new JsonObject();
            result.addProperty("action", action);
            result.addProperty(
                "maidUUID",
                maid.getUniqueID()
                    .toString());
            result.addProperty(
                "mode",
                player.theItemInWorldManager.getGameType()
                    .getID());
            result.addProperty(
                "level",
                maid.getFavorabilityManager()
                    .getLevel());
            result.addProperty("windowId", player.openContainer.windowId);
            result.addProperty("invulnerable", player.capabilities.disableDamage);
            result.addProperty("allowFlying", player.capabilities.allowFlying);
            result.addProperty("flying", player.capabilities.isFlying);
            result.addProperty("bagNativeNbtBase64", nbt(bag));
            result.addProperty("toolNativeNbtBase64", nbt(tool));
            result.addProperty("cursorNativeNbtBase64", nbt(player.inventory.getItemStack()));
            result.addProperty("originalInventoriesRestored", restored);
            var slots = new JsonArray();
            for (var slot : player.openContainer.inventorySlots) {
                var row = new JsonObject();
                row.addProperty("containerSlot", slot.slotNumber);
                row.addProperty("inventorySlot", slot.getSlotIndex());
                row.addProperty(
                    "inventoryClass",
                    slot.inventory.getClass()
                        .getName());
                row.addProperty("nativeNbtBase64", nbt(slot.getStack()));
                slots.add(row);
            }
            result.add("slots", slots);
            return result;
        }
    }

    private static ItemStack backpack() {
        var provider = CompatServices.backpacks();
        for (Object value : Item.itemRegistry) {
            if (!(value instanceof Item item)) continue;
            String id = Item.itemRegistry.getNameForObject(item);
            if (id == null || !id.startsWith(KnownModIds.ADVENTURE_BACKPACK + ":")) continue;
            var stack = new ItemStack(item);
            if (!provider.isSupportedBackpack(stack)) continue;
            var nested = sample(Items.iron_ingot);
            nested.stackSize = 3;
            if (provider.insertIntoBackpack(stack, nested, false) != null) continue;
            stack.setStackDisplayName("Nested backpack input");
            stack.addEnchantment(Enchantment.unbreaking, 3);
            return stack;
        }
        throw new IllegalStateException("Actual AdventureBackpack item required");
    }

    private static ItemStack sample(Item item) {
        var stack = new ItemStack(item);
        var root = new NBTTagCompound();
        var nested = new NBTTagCompound();
        nested.setIntArray("values", new int[] { 3, 1, 4, 1, 5, 9 });
        nested.setLong(
            "identity",
            UUID.randomUUID()
                .getLeastSignificantBits());
        var lore = new NBTTagList();
        lore.appendTag(new NBTTagString("Complete original nested input"));
        nested.setTag("lore", lore);
        root.setTag("TLMNHInventoryInput", nested);
        stack.setTagCompound(root);
        return stack;
    }

    private static ItemStack[] copy(IInventory inventory) {
        var result = new ItemStack[inventory.getSizeInventory()];
        for (int slot = 0; slot < result.length; slot++)
            result[slot] = ItemStack.copyItemStack(inventory.getStackInSlot(slot));
        return result;
    }

    private static void clear(IInventory inventory) {
        for (int slot = 0; slot < inventory.getSizeInventory(); slot++) inventory.setInventorySlotContents(slot, null);
    }

    private static void restore(IInventory inventory, ItemStack[] items) {
        for (int slot = 0; slot < items.length; slot++)
            inventory.setInventorySlotContents(slot, ItemStack.copyItemStack(items[slot]));
    }

    private static boolean equal(IInventory inventory, ItemStack[] items) {
        for (int slot = 0; slot < items.length; slot++)
            if (!ItemStack.areItemStacksEqual(items[slot], inventory.getStackInSlot(slot))) return false;
        return true;
    }

    private static String nbt(ItemStack stack) {
        if (stack == null) return "";
        try {
            var bytes = new ByteArrayOutputStream();
            CompressedStreamTools.write(stack.writeToNBT(new NBTTagCompound()), new DataOutputStream(bytes));
            return Base64.getEncoder()
                .encodeToString(bytes.toByteArray());
        } catch (IOException failure) {
            throw new IllegalStateException("Complete native item snapshot failed", failure);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
