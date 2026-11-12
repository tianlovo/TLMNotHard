package com.tianluoqaq.tlmnh.client.development;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.tianluoqaq.tlmnh.client.compat.ClientCompatServices;
import com.tianluoqaq.tlmnh.client.render.work.BellAreaOverlayRenderer;
import com.tianluoqaq.tlmnh.common.development.AnimationLab;
import com.tianluoqaq.tlmnh.common.diagnostic.DevelopmentFlags;
import com.tianluoqaq.tlmnh.common.work.bell.BreedingBellItem;
import com.tianluoqaq.tlmnh.common.work.bell.MaintenanceBellItem;
import com.tianluoqaq.tlmnh.compat.lwjgl3ify.development.input.LwjglContainerMouseInput;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 只读记录真实世界渲染事件前后状态，范围包含同一事件内的全部监听器 */
@SideOnly(Side.CLIENT)
public final class BellOverlayGpuProbe {

    private static HeldItemGpuState before;
    private static HeldItemGpuState scopedBefore;
    private static long scopedFrames, scopedDriftFrames;
    private static JsonObject scopedDriftDetails;
    private static long frames, driftFrames, maintenanceFrames, breedingFrames, offhandFrames, wornFrames;
    private static long idleFrames, idleDriftFrames;
    private static JsonObject driftDetails, idleDriftDetails;
    private static boolean maintenance, breeding, offhand, worn;
    private static AutoCloseable heldSneak;
    private static final List<String> WORK_CHAT = new ArrayList<>();

    private BellOverlayGpuProbe() {}

    static void register() {
        MinecraftForge.EVENT_BUS.register(new BellOverlayGpuProbe());
    }

    static JsonObject sneak(boolean enabled) {
        var mc = Minecraft.getMinecraft();
        if (!enabled) {
            releaseSneak();
        } else {
            if (!DevelopmentFlags.isDevCommandsEnabled() || !AnimationLab.isOverPlatform(mc.thePlayer)
                || !mc.thePlayer.isEntityAlive()
                || mc.currentScreen != null
                || !mc.thePlayer.capabilities.disableDamage
                || mc.getNetHandler() == null
                || mc.playerController == null
                || !hasNearbyOwnedWorkFixture(mc))
                throw new IllegalStateException("Protected connected lab view required for held sneak input");
            if (heldSneak == null) {
                heldSneak = LwjglContainerMouseInput.holdSneakModifier(mc);
            }
        }
        return observe();
    }

    private static boolean hasNearbyOwnedWorkFixture(Minecraft mc) {
        for (Object entity : mc.theWorld.loadedEntityList) {
            if (entity instanceof EntityMaid maid && maid.isEntityAlive()
                && maid.isOwner(mc.thePlayer)
                && maid.getDistanceSqToEntity(mc.thePlayer) <= 64
                && "[QA] Work UI".equals(maid.getCustomNameTag())) return true;
        }
        return false;
    }

    private static void releaseSneak() {
        if (heldSneak == null) return;
        try {
            heldSneak.close();
        } catch (Exception failure) {
            throw new IllegalStateException("Native held sneak modifier release failed", failure);
        } finally {
            heldSneak = null;
        }
    }

    public static void beforeDraw() {
        var mc = Minecraft.getMinecraft();
        scopedBefore = AnimationLab.isLab(mc.theWorld) && mc.thePlayer != null && mc.currentScreen == null
            ? new HeldItemGpuState()
            : null;
    }

    public static void afterDraw() {
        if (scopedBefore == null) return;
        scopedFrames++;
        if (!scopedBefore.matchesCurrent()) {
            scopedDriftFrames++;
            if (scopedDriftDetails == null) scopedDriftDetails = scopedBefore.differencesCurrent();
        }
        scopedBefore = null;
    }

    @SubscribeEvent
    public void unload(WorldEvent.Unload event) {
        if (event.world.isRemote) releaseSneak();
    }

    @SubscribeEvent
    public void chat(ClientChatReceivedEvent event) {
        if (!AnimationLab.isLab(Minecraft.getMinecraft().theWorld)) return;
        String message = event.message.getUnformattedText();
        if (!message.contains("[QA] Work UI")) return;
        if (WORK_CHAT.size() == 16) WORK_CHAT.remove(0);
        WORK_CHAT.add(message);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void before(RenderWorldLastEvent event) {
        before = null;
        var mc = Minecraft.getMinecraft();
        if (!AnimationLab.isLab(mc.theWorld) || mc.thePlayer == null || mc.currentScreen != null) return;
        var main = mc.thePlayer.getHeldItem();
        var other = ClientCompatServices.itemRender()
            .playerOffhandStack(mc.thePlayer);
        maintenance = item(main, true) || item(other, true);
        breeding = item(main, false) || item(other, false);
        offhand = item(other, true) || item(other, false);
        var preview = BellAreaOverlayRenderer.previewSnapshot();
        worn = preview != null && (preview.hasMaintenance() || preview.hasBreeding());
        if (maintenance || breeding || worn || idleFrames < 32) before = new HeldItemGpuState();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void after(RenderWorldLastEvent event) {
        if (before == null) return;
        if (!maintenance && !breeding && !worn) {
            idleFrames++;
            if (!before.matchesCurrent()) {
                idleDriftFrames++;
                if (idleDriftDetails == null) idleDriftDetails = before.differencesCurrent();
            }
            before = null;
            return;
        }
        frames++;
        if (!before.matchesCurrent()) {
            driftFrames++;
            if (driftDetails == null) driftDetails = before.differencesCurrent();
        }
        if (maintenance) maintenanceFrames++;
        if (breeding) breedingFrames++;
        if (offhand) offhandFrames++;
        if (worn) wornFrames++;
        before = null;
    }

    private static boolean item(ItemStack stack, boolean maintenance) {
        return stack != null && (maintenance ? stack.getItem() instanceof MaintenanceBellItem
            : stack.getItem() instanceof BreedingBellItem);
    }

    static JsonObject observe() {
        var result = new JsonObject();
        result.addProperty("scope", "all RenderWorldLastEvent listeners, passive before/after observation");
        result.addProperty(
            "scopedScope",
            "BellAreaOverlayRenderer actual drawing only, passive before/after observation");
        result.addProperty("scopedFrames", scopedFrames);
        result.addProperty("scopedDriftFrames", scopedDriftFrames);
        result.add("firstScopedDriftDetails", scopedDriftDetails);
        result.addProperty("frames", frames);
        result.addProperty("driftFrames", driftFrames);
        result.addProperty("maintenanceFrames", maintenanceFrames);
        result.addProperty("breedingFrames", breedingFrames);
        result.addProperty("offhandFrames", offhandFrames);
        result.addProperty("wornFrames", wornFrames);
        result.addProperty("idleFrames", idleFrames);
        result.addProperty("idleDriftFrames", idleDriftFrames);
        result.add("firstDriftDetails", driftDetails);
        result.add("firstIdleDriftDetails", idleDriftDetails);
        result.addProperty("synthesizedHeldSneak", heldSneak != null);
        var client = Minecraft.getMinecraft();
        if (client.thePlayer != null) {
            result.addProperty("inputSneak", client.gameSettings.keyBindSneak.getIsKeyPressed());
            result.addProperty("configuredSneakKey", client.gameSettings.keyBindSneak.getKeyCode());
            result.addProperty("entitySneaking", client.thePlayer.isSneaking());
            result.addProperty("movementSneak", client.thePlayer.movementInput.sneak);
            result.addProperty("playerY", client.thePlayer.posY);
            result.addProperty(
                "mainhandBell",
                item(client.thePlayer.getHeldItem(), true) || item(client.thePlayer.getHeldItem(), false));
            var other = ClientCompatServices.itemRender()
                .playerOffhandStack(client.thePlayer);
            result.addProperty("offhandBell", item(other, true) || item(other, false));
            var hit = client.objectMouseOver == null ? null : client.objectMouseOver.entityHit;
            result.addProperty("aimEntityId", hit == null ? -1 : hit.getEntityId());
            result.addProperty("aimOwnedMaid", hit instanceof EntityMaid maid && maid.isOwner(client.thePlayer));
        }
        var chat = new JsonArray();
        for (String message : WORK_CHAT) chat.add(new JsonPrimitive(message));
        result.add("receivedWorkChat", chat);
        var snapshot = BellAreaOverlayRenderer.previewSnapshot();
        result.addProperty("previewPresent", snapshot != null);
        if (snapshot != null) {
            result.addProperty("maidEntityId", snapshot.maidEntityId());
            result.addProperty("hasMaintenance", snapshot.hasMaintenance());
            result.addProperty(
                "maintenanceChunks",
                snapshot.maintenanceChunks()
                    .size());
            result.addProperty("hasBreeding", snapshot.hasBreeding());
            result.addProperty("breedingCenterX", snapshot.breedingCenterX());
            result.addProperty("breedingSoilY", snapshot.breedingSoilY());
            result.addProperty("breedingCenterZ", snapshot.breedingCenterZ());
            var mc = Minecraft.getMinecraft();
            if (mc.theWorld != null && mc.theWorld.getEntityByID(snapshot.maidEntityId()) instanceof EntityMaid maid) {
                int items = 0;
                for (int slot = 0; slot < maid.getMaidBaubleInventory()
                    .getSizeInventory(); slot++)
                    if (maid.getMaidBaubleInventory()
                        .getStackInSlot(slot) != null) items++;
                result.addProperty("trackedClientBaubleItems", items);
            }
        }
        return result;
    }
}
