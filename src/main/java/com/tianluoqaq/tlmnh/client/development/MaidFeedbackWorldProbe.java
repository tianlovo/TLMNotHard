package com.tianluoqaq.tlmnh.client.development;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Items;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import cpw.mods.fml.common.ObfuscationReflectionHelper;
import cpw.mods.fml.relauncher.ReflectionHelper;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 开发用原存档副本控制；只进入已经存在的反馈副本，交互始终走原生客户端 */
@SideOnly(Side.CLIENT)
public final class MaidFeedbackWorldProbe {

    private static final Pattern COPY_NAME = Pattern.compile("TLMNH-Feedback-206-[A-Za-z0-9_-]{1,64}");
    private static final UUID SUBJECT = UUID.fromString("f61d6050-feec-4310-8481-e4f4adaca0da");
    private static Path admittedDirectory;
    private static String admittedName;
    private static Integer originalView, expectedView;

    private MaidFeedbackWorldProbe() {}

    public static JsonObject perform(String action, String value)
        throws java.io.IOException, ReflectiveOperationException {
        require(Boolean.getBoolean("tlmnh.devCommands"), "Development commands required");
        var mc = Minecraft.getMinecraft();
        var result = new JsonObject();
        result.addProperty("action", action);
        if ("open".equals(action)) {
            require(
                mc.theWorld == null && mc.thePlayer == null
                    && mc.getNetHandler() == null
                    && !mc.isIntegratedServerRunning()
                    && mc.getIntegratedServer() == null,
                "Disconnect before opening an existing feedback copy");
            require(originalView == null, "Restore the previous owned view before another opening");
            Path directory = copyDirectory(mc, value);
            require(
                mc.getSaveLoader()
                    .getWorldInfo(value) != null,
                "Existing native WorldInfo required");
            admittedName = value;
            admittedDirectory = directory;
            mc.launchIntegratedServer(value, value, null);
            result.addProperty("nativeLaunchReturned", true);
        } else if ("status".equals(action) && mc.theWorld == null) {
            result.addProperty("worldLoaded", false);
        } else {
            requireWorld(mc);
            switch (action) {
                case "status" -> {}
                case "close_gui" -> mc.thePlayer.closeScreen();
                case "select_hand" -> {
                    require(mc.currentScreen == null, "Close the screen before selecting the native hand");
                    int selected = -1;
                    for (int slot = 0; slot < InventoryPlayer.getHotbarSize(); slot++) {
                        var item = mc.thePlayer.inventory.getStackInSlot(slot);
                        if (("saddle".equals(value) && item != null && item.getItem() == Items.saddle)
                            || ("empty".equals(value) && item == null)) {
                            selected = slot;
                            break;
                        }
                    }
                    if (value != null && value.matches("[0-8]")) selected = Integer.parseInt(value);
                    require(selected >= 0, "Requested existing native hotbar item is absent");
                    mc.thePlayer.inventory.currentItem = selected;
                    mc.playerController.updateController();
                    result.addProperty("selected", selected);
                }
                case "look_maid" -> {
                    var maid = subject(mc);
                    double dx = maid.posX - mc.thePlayer.posX;
                    double dz = maid.posZ - mc.thePlayer.posZ;
                    // Local 1.7 player posY already includes its native eye-origin offset.
                    double dy = (maid.boundingBox.minY + maid.boundingBox.maxY) * .5 - mc.thePlayer.posY;
                    float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
                    float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
                    mc.thePlayer.rotationYaw = mc.thePlayer.prevRotationYaw = yaw;
                    mc.thePlayer.rotationPitch = mc.thePlayer.prevRotationPitch = pitch;
                }
                case "interact" -> {
                    var maid = subject(mc);
                    var hit = mc.objectMouseOver;
                    var held = mc.thePlayer.getHeldItem();
                    require(
                        mc.currentScreen == null && !mc.thePlayer.isSneaking()
                            && mc.thePlayer.inventory.currentItem >= 0
                            && mc.thePlayer.inventory.currentItem < InventoryPlayer.getHotbarSize()
                            && held != null
                            && held.getItem() == Items.saddle,
                        "Normal native main-hand saddle without a screen or sneak required");
                    require(
                        hit != null && hit.typeOfHit == MovingObjectType.ENTITY
                            && hit.entityHit == maid
                            && hit.hitVec != null
                            && mc.thePlayer.getDistanceSqToEntity(maid) <= 36,
                        "Actual native mouse-over must hit the original tracked maid");
                    Object delay = ObfuscationReflectionHelper
                        .getPrivateValue(Minecraft.class, mc, "rightClickDelayTimer", "field_71467_ac");
                    require(delay instanceof Integer && ((Integer) delay) == 0, "Wait for the native click delay");
                    var method = ReflectionHelper
                        .findMethod(Minecraft.class, mc, new String[] { "func_147121_ag", "rightClickMouse" });
                    require(
                        method.getDeclaringClass() == Minecraft.class && method.getReturnType() == void.class
                            && method.getParameterTypes().length == 0
                            && !Modifier.isStatic(method.getModifiers()),
                        "Unexpected native right-click method");
                    method.setAccessible(true);
                    result.add("handBefore", hand(mc));
                    method.invoke(mc);
                    result.addProperty("nativeRightClickCalls", 1);
                    result.addProperty("serverAcknowledged", false);
                }
                case "view" -> {
                    require(value != null && value.matches("[0-2]"), "Native thirdPersonView 0, 1 or 2 required");
                    if (originalView == null) originalView = mc.gameSettings.thirdPersonView;
                    else require(mc.gameSettings.thirdPersonView == expectedView, "Unknown successor view retained");
                    expectedView = Integer.parseInt(value);
                    mc.gameSettings.thirdPersonView = expectedView;
                }
                case "restore_view" -> restoreView(mc);
                case "disconnect" -> {
                    restoreView(mc);
                    mc.theWorld.sendQuittingDisconnectingPacket();
                    mc.loadWorld(null);
                    mc.displayGuiScreen(new GuiMainMenu());
                }
                default -> throw new IllegalArgumentException("Unknown feedback-copy action");
            }
        }
        result.addProperty("worldLoaded", mc.theWorld != null);
        result.addProperty("folder", admittedName);
        result.addProperty("subject", SUBJECT.toString());
        result.addProperty("thirdPersonView", mc.gameSettings.thirdPersonView);
        result.addProperty("viewRestorePending", originalView != null);
        if (mc.thePlayer != null) {
            result.add("player", MaidCareInteractionProbe.playerState());
            result.add("hand", hand(mc));
            var slots = new JsonArray();
            for (int slot = 0; slot < InventoryPlayer.getHotbarSize(); slot++) {
                var row = new JsonObject();
                row.addProperty("slot", slot);
                row.addProperty(
                    "nativeNbtBase64",
                    MaidCareInteractionProbe.nativeNbt(mc.thePlayer.inventory.getStackInSlot(slot)));
                slots.add(row);
            }
            result.add("hotbar", slots);
            if (mc.objectMouseOver != null && mc.objectMouseOver.entityHit != null) result.addProperty(
                "mouseOverUUID",
                mc.objectMouseOver.entityHit.getUniqueID()
                    .toString());
        }
        return result;
    }

    private static Path copyDirectory(Minecraft mc, String name) throws java.io.IOException {
        require(
            name != null && COPY_NAME.matcher(name)
                .matches(),
            "Fixed feedback-copy folder name required");
        Path saves = mc.mcDataDir.toPath()
            .toAbsolutePath()
            .normalize()
            .resolve("saves");
        require(
            Files.isDirectory(saves) && saves.toRealPath()
                .equals(saves),
            "Native saves directory redirects");
        Path copy = saves.resolve(name);
        require(
            Files.isDirectory(copy) && copy.toRealPath()
                .equals(copy),
            "Existing direct feedback-copy directory required");
        Path level = copy.resolve("level.dat");
        require(
            Files.isRegularFile(level) && level.toRealPath()
                .equals(level),
            "Existing unredirected level.dat required");
        Path original = saves.resolve("新的世界");
        require(!Files.exists(original) || !Files.isSameFile(copy, original), "Original user world is excluded");
        return copy;
    }

    private static void requireWorld(Minecraft mc) throws java.io.IOException {
        require(
            admittedDirectory != null && mc.isIntegratedServerRunning()
                && mc.isSingleplayer()
                && mc.theWorld != null
                && mc.thePlayer != null
                && mc.thePlayer.isEntityAlive()
                && mc.playerController != null
                && mc.getNetHandler() != null,
            "Authorized local feedback copy required");
        var server = mc.getIntegratedServer();
        require(server != null && admittedName.equals(server.getFolderName()), "Different integrated server retained");
        require(copyDirectory(mc, admittedName).equals(admittedDirectory), "Feedback-copy path changed");
        var overworld = server.worldServerForDimension(0);
        require(
            overworld != null && overworld.getSaveHandler()
                .getWorldDirectory()
                .toPath()
                .toRealPath()
                .equals(admittedDirectory),
            "Native loaded world is outside the authorized feedback copy");
    }

    private static EntityMaid subject(Minecraft mc) {
        EntityMaid found = null;
        for (Object entity : mc.theWorld.loadedEntityList) {
            if (entity instanceof EntityMaid maid && SUBJECT.equals(maid.getUniqueID())) {
                require(found == null, "Duplicate original maid identity");
                found = maid;
            }
        }
        require(
            found != null && found.isEntityAlive()
                && found.worldObj == mc.theWorld
                && mc.theWorld.getEntityByID(found.getEntityId()) == found
                && found.isOwner(mc.thePlayer),
            "The original owned maid must be naturally tracked");
        return found;
    }

    private static JsonObject hand(Minecraft mc) {
        var result = new JsonObject();
        result.addProperty("selected", mc.thePlayer.inventory.currentItem);
        result.addProperty("nativeNbtBase64", MaidCareInteractionProbe.nativeNbt(mc.thePlayer.getHeldItem()));
        return result;
    }

    private static void restoreView(Minecraft mc) {
        if (originalView == null) return;
        require(mc.gameSettings.thirdPersonView == expectedView, "Unknown successor view retained");
        mc.gameSettings.thirdPersonView = originalView;
        originalView = expectedView = null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
