package com.tianluoqaq.tlmnh.client.development;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.world.World;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.development.AnimationLab;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaid;
import com.tianluoqaq.tlmnh.compat.lwjgl3ify.development.input.LwjglContainerMouseInput;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 开发场景沿原生客户端发送容器输入，服务端结果由实际窗口和库存另行确认 */
@SideOnly(Side.CLIENT)
public final class MaidNativeContainerProbe {

    private static ButtonInput lastButton;

    private MaidNativeContainerProbe() {}

    public static JsonObject perform(String action, String spec) {
        var mc = Minecraft.getMinecraft();
        require(
            Boolean.getBoolean("tlmnh.devCommands") && AnimationLab.isLab(mc.theWorld)
                && mc.thePlayer != null
                && mc.thePlayer.isEntityAlive()
                && mc.getNetHandler() != null
                && mc.thePlayer.capabilities.disableDamage
                && mc.thePlayer.capabilities.allowFlying
                && mc.thePlayer.capabilities.isFlying,
            "Connected protected development lab player required");
        if (lastButton != null && !lastButton.sameConnection(mc)) lastButton = null;
        var result = new JsonObject();
        result.addProperty("action", action);
        result.add("before", snapshot(mc));
        try {
            if (action.equals("container_status")) {
                require(spec == null || spec.isEmpty(), "Container status takes no input");
            } else if (action.equals("container_replay_button")) {
                require(spec == null || spec.isEmpty(), "Replay uses only the previously submitted native button");
                var saved = lastButton;
                require(
                    saved != null && saved.sameConnection(mc) && tracked(mc, saved.maid),
                    "Previous native button and its same live player/world/maid required");
                mc.playerController.sendEnchantPacket(saved.windowId, saved.button);
                result.addProperty("windowId", saved.windowId);
                result.addProperty("button", saved.button);
                result.addProperty(
                    "maidUUID",
                    saved.maid.getUniqueID()
                        .toString());
                result.addProperty("nativePacket", "C11_ENCHANT_ITEM_REPLAY");
            } else {
                var container = actualWindow(mc);
                result.addProperty("windowId", container.windowId);
                result.addProperty(
                    "maidUUID",
                    container.getMaid()
                        .getUniqueID()
                        .toString());
                if (action.equals("container_click")) {
                    var values = integers(spec, 3);
                    int slot = values[0], button = values[1], mode = values[2];
                    require(
                        slot >= 0 && slot < container.inventorySlots.size() && button >= 0 && button <= 1 && mode == 0,
                        "Actual in-window slot and ordinary left/right native mouse click required; Shift is not synthesized");
                    result.add(
                        "nativeMouse",
                        LwjglContainerMouseInput
                            .click(mc, (GuiContainer) mc.currentScreen, container.getSlot(slot), button, mode));
                    result.addProperty("slot", slot);
                    result.addProperty("button", button);
                    result.addProperty("mode", mode);
                    result.addProperty("nativePacket", "C0E_CLICK_WINDOW");
                } else if (action.equals("container_button")) {
                    int button = integers(spec, 1)[0];
                    require(
                        button >= ContainerMaid.ACTION_IDLE && button <= ContainerMaid.ACTION_TASK_CONFIG,
                        "Declared native maid container action required");
                    mc.playerController.sendEnchantPacket(container.windowId, button);
                    lastButton = new ButtonInput(mc, container, button);
                    result.addProperty("button", button);
                    result.addProperty("nativePacket", "C11_ENCHANT_ITEM");
                } else throw new IllegalArgumentException("Unknown native container action");
            }
        } catch (RuntimeException failure) {
            // The server may already own this click. Preserve observations and fail; never issue a replacement.
            result.addProperty("status", "failed");
            result.addProperty("error", failure.toString());
            result.addProperty("inputFailed", true);
            if (failure instanceof LwjglContainerMouseInput.InputFailure observed)
                result.add("nativeMouseFailure", observed.observation());
            TLMNotHard.LOG.warn("TLMNH_NATIVE_CONTAINER_INPUT_FAILED action=" + action + " spec=" + spec, failure);
        } finally {
            try {
                result.add("after", snapshot(mc));
            } catch (RuntimeException failure) {
                result.addProperty("status", "failed");
                result.addProperty("afterObservationFailure", failure.toString());
            }
        }
        result.addProperty("serverAcknowledged", false);
        return result;
    }

    private static ContainerMaid actualWindow(Minecraft mc) {
        require(mc.thePlayer.openContainer instanceof ContainerMaid, "Actual maid container required");
        var container = (ContainerMaid) mc.thePlayer.openContainer;
        require(
            mc.currentScreen instanceof GuiContainer gui && gui.inventorySlots == container
                && container.windowId > 0
                && tracked(mc, container.getMaid()),
            "Current native GUI and its registered maid must match the same player container");
        return container;
    }

    private static boolean tracked(Minecraft mc, EntityMaid maid) {
        return maid != null && maid.isEntityAlive()
            && maid.worldObj == mc.theWorld
            && mc.theWorld.getEntityByID(maid.getEntityId()) == maid;
    }

    private static int[] integers(String spec, int count) {
        require(spec != null && spec.length() <= 32, "Bounded native container input required");
        var parts = spec.split(",", -1);
        require(parts.length == count, "Wrong native container input field count");
        int[] values = new int[count];
        for (int i = 0; i < count; i++) {
            require(parts[i].matches("[0-9]{1,3}"), "Unsigned native container input required");
            values[i] = Integer.parseInt(parts[i]);
        }
        return values;
    }

    private static JsonObject snapshot(Minecraft mc) {
        var result = new JsonObject();
        var player = mc.thePlayer;
        Container container = player.openContainer;
        result.addProperty(
            "playerUUID",
            player.getUniqueID()
                .toString());
        result.addProperty("windowId", container.windowId);
        result.addProperty(
            "containerClass",
            container.getClass()
                .getName());
        result.addProperty("containerIdentity", identity(container));
        result.addProperty("screenIdentity", identity(mc.currentScreen));
        result.addProperty(
            "sameScreenContainer",
            mc.currentScreen instanceof GuiContainer gui && gui.inventorySlots == container);
        if (mc.currentScreen instanceof GuiContainer gui)
            result.addProperty("screenContainerIdentity", identity(gui.inventorySlots));
        result.addProperty(
            "screenClass",
            mc.currentScreen == null ? ""
                : mc.currentScreen.getClass()
                    .getName());
        result.addProperty("selected", player.inventory.currentItem);
        result.addProperty("canInteractWith", container.canInteractWith(player));
        result.addProperty("creativeController", mc.playerController.isInCreativeMode());
        var nativeMode = cpw.mods.fml.common.ObfuscationReflectionHelper.getPrivateValue(
            net.minecraft.client.multiplayer.PlayerControllerMP.class,
            mc.playerController,
            "currentGameType",
            "field_78779_k");
        result.addProperty("nativeControllerMode", ((net.minecraft.world.WorldSettings.GameType) nativeMode).getID());
        result
            .addProperty("cursorNativeNbtBase64", MaidCareInteractionProbe.nativeNbt(player.inventory.getItemStack()));
        if (container instanceof ContainerMaid maidContainer) {
            result.addProperty(
                "maidUUID",
                maidContainer.getMaid()
                    .getUniqueID()
                    .toString());
            result.addProperty(
                "maidTamed",
                maidContainer.getMaid()
                    .isTamed());
            result.addProperty(
                "maidOwner",
                maidContainer.getMaid()
                    .isOwner(player));
        }
        var slots = new JsonArray();
        for (Object value : container.inventorySlots) {
            var slot = (Slot) value;
            var row = new JsonObject();
            row.addProperty("containerSlot", slot.slotNumber);
            row.addProperty("inventorySlot", slot.getSlotIndex());
            row.addProperty("canTakeStack", slot.canTakeStack(player));
            row.addProperty(
                "inventoryClass",
                slot.inventory.getClass()
                    .getName());
            row.addProperty("nativeNbtBase64", MaidCareInteractionProbe.nativeNbt(slot.getStack()));
            slots.add(row);
        }
        result.add("slots", slots);
        return result;
    }

    private static String identity(Object value) {
        return value == null ? "null"
            : value.getClass()
                .getName() + "@"
                + Integer.toHexString(System.identityHashCode(value));
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalStateException(message);
    }

    private static final class ButtonInput {

        final EntityClientPlayerMP player;
        final NetHandlerPlayClient connection;
        final World world;
        final EntityMaid maid;
        final int windowId, button;

        ButtonInput(Minecraft mc, ContainerMaid container, int button) {
            player = mc.thePlayer;
            connection = mc.getNetHandler();
            world = mc.theWorld;
            maid = container.getMaid();
            windowId = container.windowId;
            this.button = button;
        }

        boolean sameConnection(Minecraft mc) {
            return player == mc.thePlayer && connection == mc.getNetHandler() && world == mc.theWorld;
        }
    }
}
