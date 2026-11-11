/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: client/gui/widget/button/SoundPackButton.java, MIT
 * Adaptation: fixed builtin static icons and common metadata, native legacy drawing
 */
package com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.StatCollector;

import org.lwjgl.opengl.GL11;

import com.tianluoqaq.tlmnh.client.gui.BuiltinModelNames;
import com.tianluoqaq.tlmnh.common.sound.BuiltinSoundCatalog;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class SoundPackButton extends FlatColorButton {

    private static final ResourceLocation ICON = new ResourceLocation(
        "touhou_little_maid",
        "textures/gui/maid_custom_sound.png");
    private final BuiltinSoundCatalog.Pack info;
    public boolean inUse;

    public SoundPackButton(int id, int x, int y, BuiltinSoundCatalog.Pack info) {
        super(id, x, y, 230, 43, "");
        this.info = info;
    }

    @Override
    public void drawButton(Minecraft minecraft, int mouseX, int mouseY) {
        if (!visible) return;
        background(selected ? 0xFF1E90FF : 0xFF434242, mouseX, mouseY);
        var font = minecraft.fontRenderer;
        int x = xPosition + 42, y = yPosition + 7;
        String name = BuiltinModelNames.INSTANCE.text(info.name);
        font.drawStringWithShadow(name, x, y, 0xFFFFFF);
        if (!info.version.isEmpty())
            font.drawStringWithShadow("§nv" + info.version, x + font.getStringWidth(name) + 5, y, 0x55FFFF);
        if (!info.authors.isEmpty()) {
            y += 10;
            // 上游 joinWith 将整个 List 作为一个 vararg，显示内容包含方括号
            font.drawStringWithShadow(
                StatCollector.translateToLocalFormatted(
                    "gui.touhou_little_maid.resources_download.author",
                    info.authors.toString()),
                x,
                y,
                0xFFAA00);
        }
        if (!info.date.isEmpty()) font.drawStringWithShadow(
            StatCollector.translateToLocalFormatted("gui.touhou_little_maid.skin.text.date", info.date),
            x,
            y + 10,
            0x55FF55);
        GL11.glColor4f(1, 1, 1, 1);
        minecraft.getTextureManager()
            .bindTexture(info.icon.isEmpty() ? ICON : new ResourceLocation(info.icon));
        if (info.icon.isEmpty()) drawTexturedModalRect(xPosition + 4, yPosition + 5, 0, 16, 32, 32);
        else Gui.func_146110_a(xPosition + 4, yPosition + 5, 0, 0, 32, 32, 32, 32);
        if (inUse) {
            minecraft.getTextureManager()
                .bindTexture(ICON);
            drawTexturedModalRect(xPosition + width - 20, yPosition + 13, 32, 0, 16, 16);
        }
    }
}
