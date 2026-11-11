package com.tianluoqaq.tlmnh.client.development;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Items;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;

import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.AbstractMaidContainerGui;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.inventory.container.AbstractMaidContainer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.tianluoqaq.tlmnh.common.diagnostic.DevelopmentFlags;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaid;

import cpw.mods.fml.common.ObfuscationReflectionHelper;
import cpw.mods.fml.relauncher.ReflectionHelper;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 开发用原存档副本控制；只进入已经存在的反馈副本，交互始终走原生客户端 */
@SideOnly(Side.CLIENT)
public final class MaidFeedbackWorldProbe {

    private static final Pattern COPY_NAME = Pattern.compile("TLMNH-Feedback-(?:206|207)-[A-Za-z0-9_-]{1,64}");
    private static final UUID LEGACY_SUBJECT = UUID.fromString("f61d6050-feec-4310-8481-e4f4adaca0da");
    private static Path admittedDirectory;
    private static String admittedName;
    private static UUID admittedSubject;
    private static Integer originalView, expectedView;

    private MaidFeedbackWorldProbe() {}

    public static JsonObject perform(String action, String value) throws IOException, ReflectiveOperationException {
        require(DevelopmentFlags.isDevCommandsEnabled(), "Development commands required");
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
            require(value != null, "Existing feedback copy binding required");
            String[] binding = value.split("\\|", -1);
            require(binding.length == 1 || binding.length == 2, "Use folder or folder|canonical-maid-UUID");
            String folder = binding[0];
            UUID identity = binding.length == 1 ? LEGACY_SUBJECT : UUID.fromString(binding[1]);
            require(
                binding.length == 1 || identity.toString()
                    .equals(binding[1]),
                "Canonical maid UUID required");
            Path directory = copyDirectory(mc, folder);
            require(
                mc.getSaveLoader()
                    .getWorldInfo(folder) != null,
                "Existing native WorldInfo required");
            admittedName = folder;
            admittedDirectory = directory;
            admittedSubject = identity;
            mc.launchIntegratedServer(folder, folder, null);
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
                            && (held == null || held.getItem() == Items.saddle),
                        "Normal native main-hand empty or saddle without a screen or sneak required");
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
                case "gui_tasks", "gui_attack", "gui_ranged" -> guiClick(mc, action, result);
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
        result.addProperty("subject", admittedSubject == null ? null : admittedSubject.toString());
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
            result.add("subjectObservation", subjectObservation(mc));
            result.add("originalGui", guiObservation(mc));
            if (mc.objectMouseOver != null && mc.objectMouseOver.entityHit != null) result.addProperty(
                "mouseOverUUID",
                mc.objectMouseOver.entityHit.getUniqueID()
                    .toString());
        }
        return result;
    }

    private static Path copyDirectory(Minecraft mc, String name) throws IOException {
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

    private static void requireWorld(Minecraft mc) throws IOException {
        require(
            admittedDirectory != null && admittedSubject != null
                && mc.isIntegratedServerRunning()
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

    private static EntityMaid trackedSubject(Minecraft mc) {
        if (mc.theWorld == null || admittedSubject == null) return null;
        EntityMaid found = null;
        for (Object entity : mc.theWorld.loadedEntityList) {
            if (entity instanceof EntityMaid maid && admittedSubject.equals(maid.getUniqueID())) {
                require(found == null, "Duplicate original maid identity");
                found = maid;
            }
        }
        return found;
    }

    private static EntityMaid subject(Minecraft mc) {
        var found = trackedSubject(mc);
        require(
            found != null && found.isEntityAlive()
                && found.worldObj == mc.theWorld
                && mc.theWorld.getEntityByID(found.getEntityId()) == found
                && found.isOwner(mc.thePlayer),
            "The original owned maid must be naturally tracked");
        return found;
    }

    private static JsonObject subjectObservation(Minecraft mc) {
        var result = new JsonObject();
        var maid = trackedSubject(mc);
        result.addProperty("tracked", maid != null);
        if (maid == null) return result;
        result.addProperty("registered", mc.theWorld.getEntityByID(maid.getEntityId()) == maid);
        result.addProperty("owner", maid.isOwner(mc.thePlayer));
        result.addProperty("entityId", maid.getEntityId());
        result.addProperty("dimension", maid.dimension);
        result.addProperty(
            "task",
            maid.getTask()
                .getId());
        result.add("clientNativeState", MaidCareInteractionProbe.maidState(maid));
        var slots = new JsonArray();
        for (int i = 0; i < maid.getMaidInventory()
            .getSizeInventory(); i++) {
            var row = new JsonObject();
            row.addProperty("slot", i);
            row.addProperty(
                "nativeNbtBase64",
                MaidCareInteractionProbe.nativeNbt(
                    maid.getMaidInventory()
                        .getStackInSlot(i)));
            slots.add(row);
        }
        result.add("maidInventory", slots);
        result.addProperty("clientReplicaOnly", true);
        return result;
    }

    /** Applied network cache lives on the actual open AbstractMaidContainer; observing never drains its queue. */
    private static JsonObject guiObservation(Minecraft mc) {
        var result = new JsonObject();
        result.addProperty(
            "screen",
            mc.currentScreen == null ? "none"
                : mc.currentScreen.getClass()
                    .getName());
        result.addProperty(
            "openContainer",
            mc.thePlayer.openContainer.getClass()
                .getName());
        result.addProperty("windowId", mc.thePlayer.openContainer.windowId);
        if (!(mc.currentScreen instanceof AbstractMaidContainerGui<?>gui)
            || !(gui.inventorySlots instanceof AbstractMaidContainer menu)) return result;
        var maid = menu.getMaid();
        result.addProperty("screenContainerIsOpenContainer", mc.thePlayer.openContainer == menu);
        result.addProperty("subjectMatches", admittedSubject.equals(maid.getUniqueID()));
        result.addProperty(
            "entityUUID",
            maid.getUniqueID()
                .toString());
        result.addProperty("entityId", maid.getEntityId());
        result.addProperty("dimension", maid.dimension);
        result.addProperty(
            "task",
            maid.getTask()
                .getId());
        result.addProperty("taskListOpen", gui.isTaskListOpen());
        result.addProperty("displayedInvulnerable", menu.isDisplayedInvulnerable());
        result.addProperty("effectCacheSource", "MaidEffectDisplay.tick -> AbstractMaidContainer.getDisplayedEffects");
        var effects = new JsonArray();
        for (var effect : menu.getDisplayedEffects()) {
            var row = new JsonObject();
            row.addProperty("potionId", effect.potionId);
            row.addProperty("descriptionId", effect.descriptionId);
            row.addProperty("amplifier", effect.amplifier);
            row.addProperty("duration", effect.duration);
            row.addProperty("category", effect.category);
            effects.add(row);
        }
        result.add("receivedEffectCache", effects);
        var drawn = new JsonArray();
        for (String line : gui.getDrawnEffectLines()) drawn.add(new JsonPrimitive(line));
        result.add("lastDrawnEffectLines", drawn);
        result.addProperty("clientReplicaOnly", true);
        return result;
    }

    private static void guiClick(Minecraft mc, String action, JsonObject result) throws ReflectiveOperationException {
        var maid = subject(mc);
        require(mc.currentScreen instanceof AbstractMaidContainerGui<?>, "Actual original maid GUI required");
        var gui = (AbstractMaidContainerGui<?>) mc.currentScreen;
        require(
            gui.inventorySlots == mc.thePlayer.openContainer && gui.inventorySlots instanceof AbstractMaidContainer
                && ((AbstractMaidContainer) gui.inventorySlots).getMaid() == maid
                && gui.inventorySlots.canInteractWith(mc.thePlayer)
                && mc.thePlayer.inventory.getItemStack() == null,
            "Same native window, actual owned subject and empty cursor required");
        result.add("guiBefore", guiObservation(mc));
        int clicks = 0;
        if ("gui_tasks".equals(action) || !gui.isTaskListOpen()) {
            clickButton(mc, gui, 100);
            clicks++;
        }
        if (!"gui_tasks".equals(action)) {
            require(gui.isTaskListOpen(), "Actual task list did not open");
            int button = "gui_attack".equals(action) ? ContainerMaid.ACTION_ATTACK : ContainerMaid.ACTION_RANGED;
            clickButton(mc, gui, button);
            clicks++;
        }
        result.addProperty("nativeGuiMouseClicks", clicks);
        result.addProperty("serverAcknowledged", false);
    }

    private static void clickButton(Minecraft mc, GuiScreen gui, int id) throws ReflectiveOperationException {
        var field = ReflectionHelper.findField(GuiScreen.class, "buttonList", "field_146292_n");
        field.setAccessible(true);
        var buttons = (List<?>) field.get(gui);
        GuiButton selected = null;
        for (Object value : buttons) if (value instanceof GuiButton button && button.id == id) {
            require(selected == null, "Duplicate actual GUI button identity");
            selected = button;
        }
        require(
            selected != null && selected.visible && selected.enabled && selected.width > 0 && selected.height > 0,
            "Visible enabled native GUI button required");
        int x = selected.xPosition + selected.width / 2, y = selected.yPosition + selected.height / 2;
        require(
            mc.currentScreen == gui && x >= 0 && x < gui.width && y >= 0 && y < gui.height,
            "Native button center must be inside the actual current screen");
        var down = ReflectionHelper.findMethod(
            GuiScreen.class,
            gui,
            new String[] { "mouseClicked", "func_73864_a" },
            int.class,
            int.class,
            int.class);
        var up = ReflectionHelper.findMethod(
            GuiScreen.class,
            gui,
            new String[] { "mouseMovedOrUp", "func_146286_b" },
            int.class,
            int.class,
            int.class);
        down.setAccessible(true);
        up.setAccessible(true);
        down.invoke(gui, x, y, 0);
        require(mc.currentScreen == gui, "Native GUI changed while clicking; no alternate action issued");
        up.invoke(gui, x, y, 0);
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
