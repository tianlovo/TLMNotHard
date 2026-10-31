/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: client/gui/entity/cache/CacheScreen.java, MIT
 * Adaptation: native GUI preview/capture, same container and balanced GL state per icon
 */
package com.github.tartaricacid.touhoulittlemaid.client.gui.entity.cache;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.StatCollector;

import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.model.MaidModelGui;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.client.render.MaidIconCapture;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaidModels;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class CacheScreen extends GuiScreen {

    private final MaidModelGui parent;
    private final int totalCount;
    private EntityMaid preview;

    CacheScreen(MaidModelGui parent) {
        this.parent = parent;
        totalCount = CacheIconManager.INSTANCE.remaining();
    }

    public ContainerMaidModels getContainer() {
        return parent.getContainer();
    }

    @Override
    public void initGui() {
        mc.thePlayer.openContainer = getContainer();
        preview = new EntityMaid(mc.theWorld);
        preview.onGround = true;
        preview.setSitting(false);
    }

    @Override
    public void updateScreen() {
        if (mc.thePlayer.openContainer != getContainer() || !getContainer().canInteractWith(mc.thePlayer))
            mc.thePlayer.closeScreen();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTick) {
        drawDefaultBackground();
        var manager = CacheIconManager.INSTANCE;
        if (manager.remaining() == 0) {
            mc.displayGuiScreen(parent);
            return;
        }
        for (int i = 0; i < 5 && manager.remaining() > 0; i++) manager.cacheNext(preview);
        drawCenteredString(
            fontRendererObj,
            StatCollector.translateToLocalFormatted(
                "gui.touhou_little_maid.cache_screen.progress",
                totalCount - manager.remaining(),
                totalCount),
            width / 2,
            height - 42,
            0xFFFFFF);
        drawCenteredString(
            fontRendererObj,
            StatCollector.translateToLocal("gui.touhou_little_maid.cache_screen.desc"),
            width / 2,
            height - 30,
            0xFFFFFF);
    }

    @Override
    protected void keyTyped(char character, int key) {
        if (key == org.lwjgl.input.Keyboard.KEY_ESCAPE) mc.thePlayer.closeScreen();
        else super.keyTyped(character, key);
    }

    @Override
    public void onGuiClosed() {
        preview = null;
        MaidIconCapture.releaseScratch();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
