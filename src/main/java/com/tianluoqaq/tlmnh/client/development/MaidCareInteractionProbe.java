package com.tianluoqaq.tlmnh.client.development;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.potion.PotionEffect;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.common.development.AnimationLab;

/** 仅给已由服务端准备的开发场景发送原生玩家操作，不直接给予照料效果或奖励 */
public final class MaidCareInteractionProbe {

    private MaidCareInteractionProbe() {}

    public static JsonObject perform(String action, String identity) {
        if (action.equals("native_click") || action.equals("native_sneak_click"))
            return com.tianluoqaq.tlmnh.compat.backhand.client.development.NativeBackhandInput
                .perform(identity, action.equals("native_sneak_click"));
        var mc = Minecraft.getMinecraft();
        if (!Boolean.getBoolean("tlmnh.devCommands") || !AnimationLab.isLab(mc.theWorld)
            || mc.thePlayer == null
            || !mc.thePlayer.isEntityAlive()
            || mc.currentScreen != null
            || mc.getNetHandler() == null)
            throw new IllegalStateException("Actual connected lab player without an open screen required");
        var player = mc.thePlayer;
        var connection = player.sendQueue;
        var result = new JsonObject();
        result.addProperty("action", action);
        result.add("playerBefore", playerState());
        if (action.equals("shrine")) {
            if (!player.capabilities.disableDamage || !player.capabilities.allowFlying
                || !player.capabilities.isFlying
                || mc.theWorld.getBlock(7, 65, 0) != com.tianluoqaq.tlmnh.common.registry.MaidResurrectionContent.SHRINE
                || player.getDistanceSq(7.5, 65.5, .5) > 64)
                throw new IllegalStateException("Actual protected player near the prepared native shrine required");
            boolean previous = player.isSneaking();
            player.setSneaking(false);
            connection.addToSendQueue(new C0BPacketEntityAction(player, 2));
            try {
                result.addProperty(
                    "clientInteractionResult",
                    mc.playerController.onPlayerRightClick(
                        player,
                        mc.theWorld,
                        player.inventory.getCurrentItem(),
                        7,
                        65,
                        0,
                        1,
                        net.minecraft.util.Vec3.createVectorHelper(7.5, 66, .5)));
            } finally {
                player.setSneaking(previous);
                connection.addToSendQueue(new C0BPacketEntityAction(player, previous ? 1 : 2));
            }
            result.addProperty("nativePacket", "C0B_STOP_SNEAKING/C08_BLOCK_PLACEMENT/C0B_RESTORE_SNEAKING");
        } else if (action.equals("grave")) {
            var entity = mc.theWorld.getEntityByID(Integer.parseInt(identity));
            if (!(entity instanceof com.tianluoqaq.tlmnh.common.lifecycle.MaidTombstoneEntity actual)
                || player.getDistanceSqToEntity(actual) > 64
                || !player.capabilities.disableDamage
                || !player.capabilities.allowFlying
                || !player.capabilities.isFlying)
                throw new IllegalStateException("Tracked native tombstone and actual protected player required");
            boolean previous = player.isSneaking();
            player.setSneaking(false);
            connection.addToSendQueue(new C0BPacketEntityAction(player, 2));
            try {
                result.addProperty(
                    "clientInteractionResult",
                    mc.playerController.interactWithEntitySendPacket(player, actual));
            } finally {
                player.setSneaking(previous);
                connection.addToSendQueue(new C0BPacketEntityAction(player, previous ? 1 : 2));
            }
            result.addProperty("entityId", actual.getEntityId());
            result.addProperty(
                "localUuid",
                actual.getUniqueID()
                    .toString());
            result.addProperty("nativePacket", "C0B_STOP_SNEAKING/C02_INTERACT/C0B_RESTORE_SNEAKING");
        } else if (action.equals("sneak_on") || action.equals("sneak_off")) {
            boolean sneaking = action.equals("sneak_on");
            player.setSneaking(sneaking);
            connection.addToSendQueue(new C0BPacketEntityAction(player, sneaking ? 1 : 2));
            result.addProperty("nativePacket", sneaking ? "C0B_START_SNEAKING" : "C0B_STOP_SNEAKING");
        } else {
            UUID target = UUID.fromString(identity);
            EntityMaid actual = null;
            for (Object entity : mc.theWorld.loadedEntityList)
                if (entity instanceof EntityMaid maid && maid.getUniqueID()
                    .equals(target)) {
                        if (actual != null) throw new IllegalStateException("Ambiguous actual maid identity");
                        actual = maid;
                    }
            if (actual == null) throw new IllegalStateException("Tracked native maid required");
            result.addProperty("uuid", target.toString());
            result.addProperty("entityId", actual.getEntityId());
            result.add("maidBefore", maidState(actual));
            if (action.equals("interact") || action.equals("open")) {
                // Keep START/INTERACT/restore in one ordered network request; a later movement tick must not
                // release the native sneak flag between two independent control requests.
                boolean previous = player.isSneaking();
                boolean care = action.equals("interact");
                player.setSneaking(care);
                connection.addToSendQueue(new C0BPacketEntityAction(player, care ? 1 : 2));
                try {
                    result.addProperty(
                        "clientInteractionResult",
                        mc.playerController.interactWithEntitySendPacket(player, actual));
                } finally {
                    player.setSneaking(previous);
                    connection.addToSendQueue(new C0BPacketEntityAction(player, previous ? 1 : 2));
                }
                result.addProperty(
                    "nativePacket",
                    (care ? "C0B_START_SNEAKING" : "C0B_STOP_SNEAKING") + "/C02_INTERACT/C0B_RESTORE_SNEAKING");
            } else if (!action.equals("status")) throw new IllegalArgumentException("Unknown native care action");
            result.add("maidAfterLocalCall", maidState(actual));
        }
        result.add("playerAfterLocalCall", playerState());
        result.addProperty("serverAcknowledged", false);
        return result;
    }

    public static JsonObject playerState() {
        var player = Minecraft.getMinecraft().thePlayer;
        var result = new JsonObject();
        if (player == null) return result;
        result.addProperty(
            "uuid",
            player.getUniqueID()
                .toString());
        result.addProperty("sneaking", player.isSneaking());
        result.addProperty("selected", player.inventory.currentItem);
        result.addProperty("health", player.getHealth());
        result.addProperty(
            "handNativeNbtBase64",
            nativeNbt(player.inventory.getStackInSlot(player.inventory.currentItem)));
        return result;
    }

    public static JsonObject maidState(EntityMaid maid) {
        var result = new JsonObject();
        result.addProperty(
            "uuid",
            maid.getUniqueID()
                .toString());
        result.addProperty("model", maid.getModelId());
        result.addProperty("health", maid.getHealth());
        result.addProperty("favorability", maid.getFavorability());
        result.addProperty("experience", maid.getExperience());
        var effects = new JsonArray();
        for (Object object : maid.getActivePotionEffects()) {
            var effect = (PotionEffect) object;
            var tag = new NBTTagCompound();
            effect.writeCustomPotionEffectToNBT(tag);
            var row = new JsonObject();
            row.addProperty("id", effect.getPotionID());
            row.addProperty("amplifier", effect.getAmplifier());
            row.addProperty("duration", effect.getDuration());
            row.addProperty("nativeNbtBase64", binary(tag));
            effects.add(row);
        }
        result.add("activePotionEffects", effects);
        return result;
    }

    public static String nativeNbt(ItemStack stack) {
        return stack == null ? ""
            : binary(
                stack.copy()
                    .writeToNBT(new NBTTagCompound()));
    }

    private static String binary(NBTTagCompound tag) {
        try (var bytes = new ByteArrayOutputStream(); var output = new DataOutputStream(bytes)) {
            CompressedStreamTools.write(tag, output);
            return Base64.getEncoder()
                .encodeToString(bytes.toByteArray());
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot encode native care observation", failure);
        }
    }
}
