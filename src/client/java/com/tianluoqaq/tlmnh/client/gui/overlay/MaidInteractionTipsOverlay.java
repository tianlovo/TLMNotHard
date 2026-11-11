/*
 * Interaction-tip layout and predicates adapted from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: client/overlay/MaidTipsOverlay.java, TartaricAcid contributors, MIT
 * Native 1.7.10 overlay hook, supported core interactions, full GL-state restoration and capability guard
 */
package com.tianluoqaq.tlmnh.client.gui.overlay;

import java.lang.reflect.Field;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.StatCollector;
import net.minecraft.world.WorldSettings;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.opengl.GL11;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.client.gui.config.MaidClientPreferences;
import com.tianluoqaq.tlmnh.client.render.scope.MaidItemRenderScope;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidCaptureService;
import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.ReflectionHelper;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class MaidInteractionTipsOverlay extends Gui {


    private static final ResourceLocation ICON = new ResourceLocation(
        ClientOverlayIds.ID_TOUHOU_LITTLE_MAID,
        ClientOverlayIds.TEXTURE_MAID_TIPS_ICON);
    private RenderItem itemRenderer;
    private Field gameTypeField;

    public static void register() {
        MinecraftForge.EVENT_BUS.register(new MaidInteractionTipsOverlay());
    }

    @SubscribeEvent
    public void render(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.CROSSHAIRS
            || !IntegrationGuard.available(Side.CLIENT, ExternalFeature.CLIENT_RENDER_STATE)
            || !IntegrationGuard.available(Side.CLIENT, ClientOverlayIds.CAPABILITY)) return;
        try {
            var minecraft = Minecraft.getMinecraft();
            var player = minecraft.thePlayer;
            var hit = minecraft.objectMouseOver;
            if (player == null || minecraft.theWorld == null
                || minecraft.playerController == null
                || hit == null
                || hit.typeOfHit != MovingObjectPosition.MovingObjectType.ENTITY
                || !(hit.entityHit instanceof EntityMaid maid)
                || maid.worldObj != minecraft.theWorld) return;
            if (gameTypeField == null) gameTypeField = ReflectionHelper
                .findField(PlayerControllerMP.class, ClientOverlayIds.ID_CURRENTGAMETYPE, ClientOverlayReflectionSpec.SRG_MEMBER_FIELD_78779_K);
            var gameType = (WorldSettings.GameType) gameTypeField.get(minecraft.playerController);
            if (gameType == null) throw new IllegalStateException(ClientOverlayDiagnostics.MSG_NATIVE_CLIENT_GAME_TYPE_IS);
            boolean mainHand = player.inventory.currentItem >= 0
                && player.inventory.currentItem < InventoryPlayer.getHotbarSize();
            boolean sameVehicle = player.ridingEntity != null && player.ridingEntity == maid.ridingEntity;
            if (!MaidInteractionTipsRules.visible(
                minecraft.gameSettings.thirdPersonView == 0,
                !minecraft.gameSettings.hideGUI,
                gameType.getID() == ClientOverlayLayout.TARGET_GET_ID_THRESHOLD,
                maid.isEntityAlive(),
                maid.isOwner(player),
                mainHand,
                sameVehicle)) return;
            ItemStack held = player.getCurrentEquippedItem();
            if (held == null || held.stackSize <= 0) return;
            var tip = tip(held);
            if (tip == null || !MaidClientPreferences.interactionTipEnabled(tip.configKey)) return;
            String text = StatCollector.translateToLocal(tip.translationKey)
                .replace(ClientOverlayIds.KEY_SHIFT, GameSettings.getKeyDisplayString(minecraft.gameSettings.keyBindSneak.getKeyCode()));
            draw(minecraft, held.copy(), text, event.resolution.getScaledWidth(), event.resolution.getScaledHeight());
        } catch (Throwable failure) {
            IntegrationGuard.throwIfFatal(failure);
            IntegrationGuard.report(Side.CLIENT, ClientOverlayIds.CAPABILITY, failure);
        }
    }

    private static MaidInteractionTipsRules.Tip tip(ItemStack held) {
        var item = held.getItem();
        if (item == Items.golden_apple) return MaidInteractionTipsRules.Tip.GOLDEN_APPLE;
        if (item == Items.potionitem && !ItemPotion.isSplash(held.getItemDamage()))
            return MaidInteractionTipsRules.Tip.POTION;
        if (item == Items.milk_bucket) return MaidInteractionTipsRules.Tip.MILK_BUCKET;
        if (item == Items.glass_bottle) return MaidInteractionTipsRules.Tip.GLASS_BOTTLE;
        if (item == Items.name_tag) return MaidInteractionTipsRules.Tip.NAME_TAG;
        if (item == Items.saddle) return MaidInteractionTipsRules.Tip.SADDLE;
        if (MaidCaptureService.isEmpty(held)) return MaidInteractionTipsRules.Tip.EMPTY_SLAB;
        return null;
    }

    private void draw(Minecraft minecraft, ItemStack held, String text, int width, int height) {
        var font = minecraft.fontRenderer;
        var lines = font.listFormattedStringToWidth(text, ClientOverlayLayout.INTERACTION_TIPS_LIST_FORMATTED_STRING_TO_WIDTH_SECO);
        int x = width / ClientOverlayLayout.MIDPOINT_DIVISOR + ClientOverlayLayout.INTERACTION_TIPS_DRAW_X_OFFSET;
        int y = height / ClientOverlayLayout.MIDPOINT_DIVISOR - ClientOverlayLayout.INTERACTION_TIPS_DRAW_Y_OFFSET - lines.size() * ClientOverlayLayout.INTERACTION_TIPS_DRAW_Y_OFFSET_2;
        try (var scope = new MaidItemRenderScope()) {
            GL11.glColor4f(ClientOverlayLayout.INTERACTION_TIPS_RED_LEVEL, ClientOverlayLayout.INTERACTION_TIPS_RED_LEVEL, ClientOverlayLayout.INTERACTION_TIPS_RED_LEVEL, ClientOverlayLayout.INTERACTION_TIPS_RED_LEVEL);
            RenderHelper.enableGUIStandardItemLighting();
            if (itemRenderer == null) itemRenderer = new RenderItem();
            itemRenderer.renderItemAndEffectIntoGUI(font, minecraft.getTextureManager(), held, x, y);
            RenderHelper.disableStandardItemLighting();
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            minecraft.getTextureManager()
                .bindTexture(ICON);
            Gui.func_146110_a(x + ClientOverlayLayout.INTERACTION_TIPS_DRAW_Y_OFFSET_2, y - ClientOverlayLayout.INTERACTION_TIPS_DEST_Y, ClientOverlayLayout.INTERACTION_TIPS_TEX_U, ClientOverlayLayout.INTERACTION_TIPS_TEX_U, ClientOverlayLayout.INTERACTION_TIPS_WIDTH, ClientOverlayLayout.INTERACTION_TIPS_WIDTH, ClientOverlayLayout.INTERACTION_TIPS_WIDTH, ClientOverlayLayout.INTERACTION_TIPS_WIDTH);
            y += ClientOverlayLayout.INTERACTION_TIPS_DRAW_PARAM;
            for (Object line : lines) {
                font.drawStringWithShadow((String) line, x, y, ClientOverlayLayout.RGB_WHITE);
                y += ClientOverlayLayout.INTERACTION_TIPS_DRAW_Y_OFFSET_2;
            }
        }
    }
}
