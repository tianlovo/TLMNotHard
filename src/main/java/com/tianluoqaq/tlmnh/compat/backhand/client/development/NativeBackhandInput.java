package com.tianluoqaq.tlmnh.compat.backhand.client.development;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.client.development.MaidCareInteractionProbe;
import com.tianluoqaq.tlmnh.common.development.AnimationLab;
import com.tianluoqaq.tlmnh.common.development.DevelopmentArtifactIdentity;
import com.tianluoqaq.tlmnh.common.protection.DependencyFingerprint;
import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.common.ObfuscationReflectionHelper;
import cpw.mods.fml.relauncher.ReflectionHelper;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 只发送一次真实Minecraft右键；完整双手顺序、换槽和原生交互由实装Backhand决定 */
@SideOnly(Side.CLIENT)
public final class NativeBackhandInput {

    private static final String PROVIDER = "1.8.18:sha256:da773e8ffcafadc14e69c4019e6be5260d3c99ff74758028207f354846742ac0";

    private NativeBackhandInput() {}

    public static JsonObject perform(String maidUuid, boolean sneaking) {
        var mc = Minecraft.getMinecraft();
        var player = mc.thePlayer;
        require(
            Boolean.getBoolean("tlmnh.devCommands") && AnimationLab.isLab(mc.theWorld)
                && player != null
                && player.isEntityAlive()
                && mc.currentScreen == null
                && mc.playerController != null
                && mc.getNetHandler() != null
                && player.capabilities.disableDamage
                && player.capabilities.allowFlying
                && player.capabilities.isFlying,
            "Connected protected lab player without a screen required for actual native right-click");
        var uuid = UUID.fromString(maidUuid);
        require(
            uuid.toString()
                .equals(maidUuid),
            "Canonical actual maid UUID required");
        EntityMaid target = null;
        for (Object entity : mc.theWorld.loadedEntityList)
            if (entity instanceof EntityMaid candidate && candidate.getUniqueID()
                .equals(uuid)) {
                    require(target == null, "Ambiguous tracked native maid identity");
                    target = candidate;
                }
        var hit = mc.objectMouseOver;
        require(
            target != null && target.isEntityAlive()
                && target.worldObj == mc.theWorld
                && mc.theWorld.getEntityByID(target.getEntityId()) == target
                && hit != null
                && hit.typeOfHit == MovingObjectType.ENTITY
                && hit.entityHit == target
                && hit.hitVec != null
                && player.getDistanceSqToEntity(target) <= 64,
            "Actual mouse-over ENTITY ray must hit the same unique tracked maid");
        int selected = player.inventory.currentItem;
        require(
            selected >= 0 && selected < InventoryPlayer.getHotbarSize(),
            "Native full-hand click must start in an actual main-hand hotbar slot");
        require(
            IntegrationGuard.available(Side.CLIENT, ExternalFeature.PLAYER_OFFHAND),
            "Actual Backhand input capability is unavailable");
        try {
            String fingerprint = DependencyFingerprint.mod("backhand");
            require(
                PROVIDER.equals(fingerprint),
                "Native click provider differs from the inspected Backhand implementation");
            var api = Class.forName("xonin.backhand.api.core.BackhandUtils");
            var apiArtifact = DevelopmentArtifactIdentity.capture(api);
            require(
                PROVIDER.endsWith(
                    ":sha256:" + apiArtifact.get("artifactSha256")
                        .getAsString()),
                "Actual loaded Backhand API source differs from its inspected provider");
            var slotMethod = api.getMethod("getOffhandSlot", EntityPlayer.class);
            require(
                Modifier.isStatic(slotMethod.getModifiers()) && slotMethod.getReturnType() == int.class
                    && slotMethod.getDeclaringClass() == api,
                "Actual native offhand-slot API shape differs");
            int offhand = (Integer) slotMethod.invoke(null, player);
            require(
                offhand >= 0 && offhand < player.inventory.mainInventory.length && offhand != selected,
                "Actual Backhand offhand slot unavailable");
            // Forge's ObfuscationReflectionHelper handles fields; its ReflectionHelper companion owns methods.
            var click = ReflectionHelper
                .findMethod(Minecraft.class, mc, new String[] { "func_147121_ag", "rightClickMouse" });
            require(
                click.getDeclaringClass() == Minecraft.class && click.getReturnType() == void.class
                    && click.getParameterTypes().length == 0
                    && !Modifier.isStatic(click.getModifiers()),
                "Actual Minecraft right-click method shape differs");
            click.setAccessible(true);
            var result = new JsonObject();
            result.addProperty("uuid", uuid.toString());
            result.addProperty("entityId", target.getEntityId());
            result.addProperty("mouseOverType", hit.typeOfHit.name());
            result.addProperty("actualMouseOverTarget", true);
            result.addProperty("requestedSneaking", sneaking);
            result.addProperty("backhandFingerprint", fingerprint);
            result.addProperty("backhandApiClass", api.getName());
            result.add("backhandApiArtifact", apiArtifact);
            result.addProperty("minecraftMethod", click.getName());
            result.addProperty(
                "minecraftDeclaringClass",
                click.getDeclaringClass()
                    .getName());
            result.addProperty("transformedDefinitionBytesVerified", false);
            result.add(
                "probeArtifact",
                DevelopmentArtifactIdentity.capture(NativeBackhandInput.class, MaidCareInteractionProbe.class));
            result.add("playerBefore", playerState(mc, offhand));
            result.add("maidBefore", MaidCareInteractionProbe.maidState(target));
            boolean previous = player.isSneaking();
            var connection = player.sendQueue;
            player.setSneaking(sneaking);
            connection.addToSendQueue(new C0BPacketEntityAction(player, sneaking ? 1 : 2));
            try {
                click.invoke(mc);
                result.addProperty("nativeVoidMethodReturned", true);
            } finally {
                player.setSneaking(previous);
                connection.addToSendQueue(new C0BPacketEntityAction(player, previous ? 1 : 2));
            }
            result.add("playerAfterLocalCall", playerState(mc, offhand));
            result.add("maidAfterLocalCall", MaidCareInteractionProbe.maidState(target));
            result.addProperty("selectedRestoredByNativeBackhand", player.inventory.currentItem == selected);
            result.addProperty("nativeRightClickCalls", 1);
            result.addProperty(
                "nativePacketContract",
                "C0B temporary sneak / one Minecraft rightClickMouse (Backhand chooses native hand and C09/entity/item packets) / C0B restore");
            result.addProperty("serverAcknowledged", false);
            return result;
        } catch (Exception | LinkageError | AssertionError failure) {
            var cause = failure instanceof InvocationTargetException wrapped && wrapped.getCause() != null
                ? wrapped.getCause()
                : failure;
            IntegrationGuard.trip(Side.CLIENT, ExternalFeature.PLAYER_OFFHAND, cause);
            throw new IllegalStateException(
                "Actual native Backhand click failed; no alternate interaction was issued",
                cause);
        }
    }

    private static JsonObject playerState(Minecraft mc, int offhand) {
        var player = mc.thePlayer;
        var result = MaidCareInteractionProbe.playerState();
        result.addProperty("mainSlot", player.inventory.currentItem);
        result.addProperty("offhandSlot", offhand);
        result.addProperty(
            "mainNativeNbtBase64",
            MaidCareInteractionProbe.nativeNbt(player.inventory.getStackInSlot(player.inventory.currentItem)));
        result.addProperty(
            "offhandNativeNbtBase64",
            MaidCareInteractionProbe.nativeNbt(player.inventory.getStackInSlot(offhand)));
        Object timer = ObfuscationReflectionHelper
            .getPrivateValue(Minecraft.class, mc, "rightClickDelayTimer", "field_71467_ac");
        require(timer instanceof Integer, "Actual native right-click timer field differs");
        result.addProperty("rightClickDelayTimer", (Integer) timer);
        return result;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}
