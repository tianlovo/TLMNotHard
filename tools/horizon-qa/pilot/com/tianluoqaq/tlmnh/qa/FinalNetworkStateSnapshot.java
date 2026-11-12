package com.tianluoqaq.tlmnh.qa;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.util.Base64;
import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.NetworkSystem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.NetHandlerLoginServer;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.common.development.interaction.MaidVisibleStateSnapshot;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.relauncher.ReflectionHelper;

/** 真实两端公开实体与当前原生容器状态，只读观察，不把私有库存当广播状态 */
public final class FinalNetworkStateSnapshot {

    private FinalNetworkStateSnapshot() {}

    public static JsonObject connection(NetworkManager manager) {
        var row = new JsonObject();
        row.addProperty("present", manager != null);
        if (manager == null) return row;
        var channel = manager.channel();
        row.addProperty("channelPresent", channel != null);
        row.addProperty(
            "handlerClass",
            manager.getNetHandler() == null ? ""
                : manager.getNetHandler()
                    .getClass()
                    .getName());
        if (channel != null) {
            row.addProperty("channelOpen", channel.isOpen());
            row.addProperty("channelActive", channel.isActive());
            row.addProperty(
                "autoRead",
                channel.config()
                    .isAutoRead());
            var protocol = channel.attr(NetworkManager.attrKeyConnectionState)
                .get();
            row.addProperty("protocol", protocol == null ? "" : protocol.name());
            row.addProperty("localAddress", String.valueOf(channel.localAddress()));
            row.addProperty("remoteAddress", String.valueOf(channel.remoteAddress()));
            if (channel.remoteAddress() instanceof InetSocketAddress address) {
                row.addProperty(
                    "remoteHost",
                    address.getAddress()
                        .getHostAddress());
                row.addProperty("remotePort", address.getPort());
            }
        }
        var exit = manager.getExitMessage();
        row.addProperty("exitMessage", exit == null ? "" : exit.getUnformattedText());
        if (manager.getNetHandler() instanceof NetHandlerLoginServer login) {
            Object state = ReflectionHelper.getPrivateValue(NetHandlerLoginServer.class, login, "field_147328_g");
            Number ticks = ReflectionHelper.getPrivateValue(NetHandlerLoginServer.class, login, "field_147336_h");
            row.addProperty("loginState", String.valueOf(state));
            row.addProperty("loginTicks", ticks.intValue());
        }
        return row;
    }

    public static JsonObject serverNetwork(MinecraftServer server) {
        var row = new JsonObject();
        var system = server.func_147137_ag();
        row.addProperty("serverPort", server.getServerPort());
        row.addProperty("onlineMode", server.isServerInOnlineMode());
        row.addProperty(
            "fmlAllowsLogins",
            FMLCommonHandler.instance()
                .shouldAllowPlayerLogins());
        row.addProperty("networkAlive", system.isAlive);
        List<?> managers = ReflectionHelper
            .getPrivateValue(NetworkSystem.class, system, "networkManagers", "field_151272_f");
        var connections = new JsonArray();
        synchronized (managers) {
            for (Object raw : managers) connections.add(connection((NetworkManager) raw));
        }
        row.add("connections", connections);
        return row;
    }

    public static JsonObject maid(EntityMaid maid) {
        var row = MaidVisibleStateSnapshot.capture(maid);
        row.addProperty(
            "uuid",
            maid.getUniqueID()
                .toString());
        row.addProperty("entityId", maid.getEntityId());
        row.addProperty("objectIdentity", System.identityHashCode(maid));
        row.addProperty(
            "task",
            maid.getTask()
                .name());
        row.addProperty(
            "taskId",
            maid.getTask()
                .getId());
        row.addProperty("chestNativeNbtBase64", item(maid.getEquipmentInSlot(3)));
        row.addProperty("sitting", maid.isSitting());
        row.addProperty("x", maid.posX);
        row.addProperty("y", maid.posY);
        row.addProperty("z", maid.posZ);
        row.addProperty("age", maid.ticksExisted);
        return row;
    }

    public static JsonObject container(EntityPlayer player) {
        var row = new JsonObject();
        var container = player.openContainer;
        row.addProperty(
            "class",
            container.getClass()
                .getName());
        row.addProperty("windowId", container.windowId);
        row.addProperty("cursorNativeNbtBase64", item(player.inventory.getItemStack()));
        var slots = new JsonArray();
        for (Object raw : container.inventorySlots) {
            var slot = (Slot) raw;
            var value = new JsonObject();
            value.addProperty("containerSlot", slot.slotNumber);
            value.addProperty("inventorySlot", slot.getSlotIndex());
            value.addProperty(
                "inventoryClass",
                slot.inventory.getClass()
                    .getName());
            value.addProperty("nativeNbtBase64", item(slot.getStack()));
            slots.add(value);
        }
        row.add("slots", slots);
        return row;
    }

    public static JsonObject playerInputs(EntityPlayer player) {
        var row = new JsonObject();
        row.addProperty(
            "uuid",
            player.getUniqueID()
                .toString());
        row.addProperty("dimension", player.dimension);
        row.addProperty("selectedSlot", player.inventory.currentItem);
        row.addProperty("x", player.posX);
        row.addProperty("feetY", player.boundingBox.minY);
        row.addProperty("z", player.posZ);
        row.addProperty("yaw", player.rotationYaw);
        row.addProperty("pitch", player.rotationPitch);
        var inventory = new net.minecraft.nbt.NBTTagList();
        player.inventory.writeToNBT(inventory);
        var tag = new NBTTagCompound();
        tag.setTag("Inventory", inventory);
        player.capabilities.writeCapabilitiesToNBT(tag);
        row.addProperty("inventoryAndAbilitiesNativeNbtBase64", compound(tag));
        row.addProperty("mainHandNativeNbtBase64", item(player.getHeldItem()));
        row.addProperty("cursorNativeNbtBase64", item(player.inventory.getItemStack()));
        return row;
    }

    public static String compound(NBTTagCompound tag) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var stream = new DataOutputStream(bytes)) {
                CompressedStreamTools.write(tag, stream);
            }
            return Base64.getEncoder()
                .encodeToString(bytes.toByteArray());
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot capture native final player inputs", failure);
        }
    }

    public static String item(ItemStack stack) {
        if (stack == null) return "";
        try {
            var tag = new NBTTagCompound();
            stack.copy()
                .writeToNBT(tag);
            var bytes = new ByteArrayOutputStream();
            try (var stream = new DataOutputStream(bytes)) {
                CompressedStreamTools.write(tag, stream);
            }
            return Base64.getEncoder()
                .encodeToString(bytes.toByteArray());
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot capture native final network item", failure);
        }
    }
}
