/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: client/gui/entity/detail/AbstractModelDetailsGui.java, MIT
 * Adaptation: native screen/input, same authoritative container, isolated preview renderer
 */
package com.github.tartaricacid.touhoulittlemaid.client.gui.entity.detail;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.ModelDetailsButton;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.client.gui.BuiltinModelNames;
import com.tianluoqaq.tlmnh.client.gui.MaidAtlasButton;
import com.tianluoqaq.tlmnh.client.render.MaidDetailsPreview;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaidModels;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelCatalog;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public abstract class AbstractModelDetailsGui extends GuiScreen {

    private static final ResourceLocation BUTTON_TEXTURE = new ResourceLocation(
        "touhou_little_maid",
        "textures/gui/skin_detail.png");
    protected final ContainerMaidModels container;
    protected final BuiltinModelCatalog.Choice modelInfo;
    protected EntityMaid guiEntity;
    protected final MaidDetailsPreview preview = new MaidDetailsPreview();
    private float posX, posY = 25, scale = 80, yaw = 145, pitch;
    private boolean showFloor = true;
    private int dragX, dragY;

    protected AbstractModelDetailsGui(ContainerMaidModels container, BuiltinModelCatalog.Choice modelInfo) {
        this.container = container;
        this.modelInfo = modelInfo;
    }

    protected abstract void applyReturnButtonLogic();

    protected abstract void initSideButton();

    public ContainerMaidModels getContainer() {
        return container;
    }

    @Override
    public void initGui() {
        mc.thePlayer.openContainer = container;
        buttonList.clear();
        addTopButton(0, width - 15, 0);
        addTopButton(1, width - 30, 30);
        addTopButton(2, width - 45, 15);
        initSideButton();
    }

    private void addTopButton(int id, int x, int u) {
        buttonList.add(new MaidAtlasButton(id, x, 0, 15, 15, BUTTON_TEXTURE, u, 24, 15, 0, ""));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (!button.enabled) return;
        for (var widget : buttonList) {
            if (widget instanceof ModelDetailsButton state) state.focused = widget == button;
            if (widget instanceof MaidAtlasButton atlas) atlas.focused = widget == button;
        }
        if (button instanceof ModelDetailsButton state) state.press();
        else switch (button.id) {
            case 0 -> mc.thePlayer.closeScreen();
            case 1 -> showFloor = !showFloor;
            case 2 -> applyReturnButtonLogic();
            default -> {}
        }
    }

    public boolean pressButton(int id) {
        if (mc.currentScreen != this || !container.canInteractWith(mc.thePlayer)) return false;
        for (var button : buttonList) if (button.id == id && button.enabled) {
            actionPerformed(button);
            return true;
        }
        return false;
    }

    @Override
    public void updateScreen() {
        if (mc.thePlayer.openContainer != container || !container.canInteractWith(mc.thePlayer))
            mc.thePlayer.closeScreen();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTick) {
        int depthFunction = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        try {
            renderDetails(mouseX, mouseY, partialTick);
        } finally {
            GL11.glDepthFunc(depthFunction);
        }
    }

    private void renderDetails(int mouseX, int mouseY, float partialTick) {
        int matrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        try {
            GL11.glTranslatef(0, 0, -999);
            drawRect(0, 0, width, height, 0xFE17191D);
            GL11.glTranslatef(0, 0, 99);
            fontRendererObj.drawString(text("left_mouse"), 136, 19, 0xAAAAAA);
            fontRendererObj.drawString(text("right_mouse"), 136, 29, 0xAAAAAA);
            fontRendererObj.drawString(text("mouse_wheel"), 136, 39, 0xAAAAAA);
        } finally {
            GL11.glPopMatrix();
            GL11.glMatrixMode(matrixMode);
        }
        preview.draw(
            guiEntity,
            (width + 132) / 2 + posX,
            height / 2 + 50 + posY,
            scale,
            yaw,
            pitch,
            showFloor,
            partialTick);
        preview.axes(width - 16, height - 32);
        drawRect(0, height - 16, width, height, 0xFE282C34);
        String name = "\u2714 " + BuiltinModelNames.INSTANCE.name(modelInfo);
        String status = String.format("%d FPS %.2f%%", debugFps(), scale * 100 / 80);
        fontRendererObj.drawString(name, 136, height - 12, 0xCACAD4);
        fontRendererObj.drawString(status, width - fontRendererObj.getStringWidth(status) - 4, height - 12, 0xCACAD4);
        drawRect(0, 0, 132, height, 0xFE21252B);
        drawRect(0, 0, width, 15, 0xFE282C34);
        fontRendererObj.drawStringWithShadow(
            StatCollector.translateToLocal("gui.touhou_little_maid.custom_model_details_gui.title"),
            6,
            4,
            0xAAAAAA);
        super.drawScreen(mouseX, mouseY, partialTick);
    }

    private int debugFps() {
        try {
            return Integer.parseInt(mc.debug.substring(0, mc.debug.indexOf(' ')));
        } catch (NumberFormatException | IndexOutOfBoundsException ignored) {
            return 0;
        }
    }

    private static String text(String key) {
        return StatCollector.translateToLocal("gui.touhou_little_maid.skin_details." + key);
    }

    private boolean inView(double x, double y) {
        return 132 < x && x < width - 1 && 15 < y && y < height - 16;
    }

    public boolean dragView(double x, double y, int button, double dx, double dy) {
        if (!inView(x, y)) return false;
        if (button == 0) {
            yaw += dx;
            pitch = Math.max(-90, Math.min(90, pitch - (float) dy));
        }
        if (button == 1) {
            posX += dx;
            posY += dy;
        }
        return true;
    }

    public boolean scrollView(double x, double y, double delta) {
        if (!inView(x, y) || delta == 0) return false;
        float amount = (float) delta * 0.07F;
        scale = Math.max(18, Math.min(360, scale + amount * scale));
        return true;
    }

    @Override
    protected void mouseClicked(int x, int y, int button) {
        dragX = x;
        dragY = y;
        super.mouseClicked(x, y, button);
    }

    @Override
    protected void mouseClickMove(int x, int y, int button, long elapsed) {
        dragView(x, y, button, x - dragX, y - dragY);
        dragX = x;
        dragY = y;
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) scrollView(
            Mouse.getEventX() * width / mc.displayWidth,
            height - Mouse.getEventY() * height / mc.displayHeight - 1,
            wheel / 120.0);
    }

    @Override
    protected void keyTyped(char character, int key) {
        if (key == Keyboard.KEY_ESCAPE) mc.thePlayer.closeScreen();
        else super.keyTyped(character, key);
    }

    public JsonObject observation() {
        var result = new JsonObject();
        result.addProperty("model", modelInfo.getId());
        result.addProperty(
            "previewUuid",
            guiEntity.getUniqueID()
                .toString());
        result.addProperty(
            "sourceUuid",
            container.getMaid()
                .getUniqueID()
                .toString());
        result.addProperty("scale", scale);
        result.addProperty("yaw", yaw);
        result.addProperty("pitch", pitch);
        result.addProperty("posX", posX);
        result.addProperty("posY", posY);
        result.addProperty("floor", showFloor);
        result.add("renderDiagnostic", preview.observation());
        result.addProperty("previewTicks", guiEntity.ticksExisted);
        result.addProperty("previewBegging", guiEntity.hasPose(com.tianluoqaq.tlmnh.common.entity.MaidPose.BEGGING));
        result.addProperty("previewSitting", guiEntity.isSitting());
        result.addProperty("previewRiding", guiEntity.ridingEntity != null);
        result.addProperty("previewMainHand", item(guiEntity.getHeldItem()));
        result.addProperty("previewOffhand", item(guiEntity.getOffhandItem()));
        result.addProperty("previewRegistered", guiEntity.worldObj.loadedEntityList.contains(guiEntity));
        result.addProperty("limbSwing", guiEntity.limbSwing);
        result.addProperty("limbSwingAmount", guiEntity.limbSwingAmount);
        result.addProperty(
            "sourceMainHand",
            item(
                container.getMaid()
                    .getHeldItem()));
        result.addProperty(
            "sourceOffhand",
            item(
                container.getMaid()
                    .getOffhandItem()));
        result.addProperty(
            "sourceSitting",
            container.getMaid()
                .isSitting());
        var armor = new com.google.gson.JsonArray();
        for (int slot = 1; slot <= 4; slot++)
            armor.add(new com.google.gson.JsonPrimitive(item(guiEntity.getEquipmentInSlot(slot))));
        result.add("previewArmor", armor);
        var toggles = new JsonObject();
        for (var button : buttonList) if (button instanceof ModelDetailsButton state)
            toggles.addProperty(Integer.toString(button.id), state.isSelected());
        result.add("toggles", toggles);
        return result;
    }

    private static String item(net.minecraft.item.ItemStack stack) {
        return stack == null ? "" : net.minecraft.item.Item.itemRegistry.getNameForObject(stack.getItem());
    }

    @Override
    public void onGuiClosed() {
        if (guiEntity != null) guiEntity.mountEntity(null);
        guiEntity = null;
        preview.close();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
