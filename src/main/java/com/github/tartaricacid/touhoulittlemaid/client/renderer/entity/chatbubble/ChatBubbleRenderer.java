/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: client/renderer/entity/chatbubble/ChatBubbleRenderer.java, MIT
 * Adaptation: legacy client rendering for builtin ordinary bubbles
 */
package com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.chatbubble;

import java.util.EnumMap;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.entity.RenderManager;

import org.lwjgl.opengl.GL11;

import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.chatbubble.implement.EmojiChatBubbleRenderer;
import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.chatbubble.implement.TextChatBubbleRenderer;
import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.IChatBubbleData;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.client.gui.MaidClientPreferences;
import com.tianluoqaq.tlmnh.client.render.MaidItemRenderScope;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class ChatBubbleRenderer {

    private final Map<IChatBubbleData, EnumMap<IChatBubbleRenderer.Position, IChatBubbleRenderer>> renderers = new WeakHashMap<>();
    private static final Map<EntityMaid, Integer> LAST_DRAWN = new WeakHashMap<>();

    public static int drawnCount(EntityMaid maid) {
        return LAST_DRAWN.getOrDefault(maid, 0);
    }

    public void render(EntityMaid maid, double x, double y, double z, float partialTick) {
        boolean dev = Boolean.getBoolean("tlmnh.devCommands");
        if (dev) LAST_DRAWN.put(maid, 0);
        var manager = RenderManager.instance;
        if (!MaidClientPreferences.showChatBubbles() || !maid.getConfigManager()
            .isChatBubbleShow()
            || manager.livingPlayer == null
            || maid.getDistanceSqToEntity(manager.livingPlayer) > 4096) return;
        var collection = maid.getChatBubbleManager()
            .getChatBubbleDataCollection();
        if (collection.isEmpty()) return;
        float lightX = OpenGlHelper.lastBrightnessX, lightY = OpenGlHelper.lastBrightnessY;
        try (var scope = new MaidItemRenderScope()) {
            GL11.glTranslated(x, y + maid.height + .5 - (maid.isSitting() ? .25 : 0), z);
            GL11.glRotatef(-manager.playerViewY, 0, 1, 0);
            GL11.glRotatef(manager.playerViewX, 1, 0, 0);
            GL11.glScalef(-.025F, -.025F, .025F);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            var graphics = new EntityGraphics(maid.getBrightnessForRender(partialTick));
            int count = collection.size(), sideNum = count % 2 == 1 ? count - 1 : count, leftY = 0, rightY = 0,
                index = 0;
            var entries = collection.entries()
                .values()
                .iterator();
            while (index < sideNum && entries.hasNext()) {
                var position = index % 2 == 1 ? IChatBubbleRenderer.Position.LEFT : IChatBubbleRenderer.Position.RIGHT;
                var bubble = renderer(entries.next(), position);
                draw(graphics, bubble, position, index % 2 == 1 ? leftY : rightY);
                if (index % 2 == 1) leftY += bubble.getHeight() + 16;
                else rightY += bubble.getHeight() + 16;
                index++;
            }
            if (count % 2 == 1 && entries.hasNext()) draw(
                graphics,
                renderer(entries.next(), IChatBubbleRenderer.Position.CENTER),
                IChatBubbleRenderer.Position.CENTER,
                Math.max(leftY, rightY));
            if (dev) LAST_DRAWN.put(maid, count);
        } finally {
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, lightX, lightY);
        }
    }

    private IChatBubbleRenderer renderer(IChatBubbleData data, IChatBubbleRenderer.Position position) {
        var map = renderers.computeIfAbsent(data, key -> new EnumMap<>(IChatBubbleRenderer.Position.class));
        return map.computeIfAbsent(
            position,
            key -> data.isImage() ? new EmojiChatBubbleRenderer(data.background())
                : new TextChatBubbleRenderer(data.text(), data.background(), position));
    }

    private static void draw(EntityGraphics graphics, IChatBubbleRenderer bubble, IChatBubbleRenderer.Position position,
        int y) {
        int width = bubble.getWidth() + 10, height = bubble.getHeight() + 10;
        int x = position == IChatBubbleRenderer.Position.LEFT ? -1 - width
            : position == IChatBubbleRenderer.Position.RIGHT ? 1 : -width / 2;
        GL11.glPushMatrix();
        try {
            GL11.glTranslatef(0, -y, 0);
            graphics.blitNineSliced(bubble.getBackgroundTexture(), x, -height, width, height);
            GL11.glTranslatef(0, 0, -.01F);
            graphics.blit(
                bubble.getBackgroundTexture(),
                position == IChatBubbleRenderer.Position.LEFT ? -9
                    : position == IChatBubbleRenderer.Position.RIGHT ? -7 : -8,
                -8,
                position == IChatBubbleRenderer.Position.LEFT ? 32
                    : position == IChatBubbleRenderer.Position.RIGHT ? 0 : 16,
                24,
                16,
                16);
            GL11.glTranslatef(x + 5, -height + 5, -.01F);
            bubble.render(graphics);
        } finally {
            GL11.glPopMatrix();
        }
    }
}
