package com.tianluoqaq.tlmnh.qa.client;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.network.NetworkManager;
import net.minecraft.util.IChatComponent;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tianluoqaq.tlmnh.common.development.DevelopmentArtifactIdentity;
import com.tianluoqaq.tlmnh.common.inventory.container.ContainerMaid;
import com.tianluoqaq.tlmnh.common.inventory.container.MaidContainerProtocol;
import com.tianluoqaq.tlmnh.qa.FinalNetworkStateFiles;
import com.tianluoqaq.tlmnh.qa.FinalNetworkStateSnapshot;

import cpw.mods.fml.client.FMLClientHandler;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.NetworkCheckHandler;
import cpw.mods.fml.relauncher.ReflectionHelper;
import cpw.mods.fml.relauncher.Side;

/** 独立真实客户端观察与原生输入小包，输入沿 Minecraft 原生网络发送 */
@Mod(
    modid = "tlmnhqafinalclient",
    name = "TLMNH final native network client",
    version = "1",
    dependencies = "required-after:tlmnh;required-after:tlmnhdevtools;required-after:tlmnhqapilot",
    acceptableRemoteVersions = "*")
public final class FinalNetworkClientObservation {

    private String batch;
    private Path root;
    private Path output;
    private JsonObject artifact, pilotArtifact, addonArtifact, devtoolsArtifact;
    private boolean initialJump;
    private int liftTicks;
    private boolean originalJump;
    private int rows;

    @NetworkCheckHandler
    public boolean remoteOptional(Map<String, String> mods, Side side) {
        return true;
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) throws Exception {
        batch = System.getProperty("tlmnh.qaFinalNetworkBatch", "");
        require(
            batch.matches("final-network-[0-9]+") && FMLCommonHandler.instance()
                .getSide()
                .isClient(),
            "Explicit independent final client batch required");
        var mc = Minecraft.getMinecraft();
        root = mc.mcDataDir.toPath()
            .toRealPath()
            .resolve("tlmnh-dev/final-network")
            .resolve(batch);
        Files.createDirectories(root.resolve("control"));
        Files.createDirectories(root.resolve("responses"));
        output = root.resolve("client-state.json");
        require(!Files.exists(root.resolve("snapshots.jsonl")), "Never reuse final client evidence");
        artifact = DevelopmentArtifactIdentity.capture(EntityMaid.class);
        pilotArtifact = DevelopmentArtifactIdentity
            .capture(FinalNetworkStateSnapshot.class, FinalNetworkStateFiles.class);
        addonArtifact = DevelopmentArtifactIdentity.capture(FinalNetworkClientObservation.class);
        devtoolsArtifact = DevelopmentArtifactIdentity.capture(DevelopmentArtifactIdentity.class);
        initialJump = mc.gameSettings.keyBindJump.getIsKeyPressed();
        FMLCommonHandler.instance()
            .bus()
            .register(this);
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) throws Exception {
        if (event.phase != TickEvent.Phase.END || root == null) return;
        if (liftTicks == 1) releaseInput();
        else if (liftTicks > 1) liftTicks--;
        var requests = new ArrayList<Path>();
        try (var paths = Files.newDirectoryStream(root.resolve("control"), "*.json")) {
            for (var path : paths) requests.add(path);
        }
        requests.sort(Comparator.comparing(Path::toString));
        for (var path : requests) {
            var response = new JsonObject();
            try {
                var request = new JsonParser().parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8))
                    .getAsJsonObject();
                require(
                    batch.equals(
                        request.get("batch")
                            .getAsString()),
                    "Stale client input batch");
                String action = request.get("action")
                    .getAsString();
                response.addProperty("action", action);
                act(request, response);
                response.addProperty("completed", true);
            } catch (Exception failure) {
                response.addProperty("completed", false);
                response.addProperty("failure", failure.toString());
                releaseInput();
            }
            response.addProperty("batch", batch);
            Files.write(
                root.resolve("responses")
                    .resolve(path.getFileName()),
                response.toString()
                    .getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE_NEW);
            Files.delete(path);
        }
    }

    @SubscribeEvent
    public void render(TickEvent.RenderTickEvent event) throws Exception {
        if (event.phase != TickEvent.Phase.END || output == null) return;
        require(++rows <= 80000, "Final client observation row bound exceeded");
        var mc = Minecraft.getMinecraft();
        var row = new JsonObject();
        row.addProperty("schema", "final-real-network-client-v1");
        row.addProperty("batch", batch);
        row.addProperty("sequence", rows);
        row.addProperty("captureBoundary", "RenderTick.END");
        row.add("productionArtifact", artifact);
        row.add("pilotArtifact", pilotArtifact);
        row.add("addonArtifact", addonArtifact);
        row.add("devtoolsArtifact", devtoolsArtifact);
        row.addProperty("initialJumpPressed", initialJump);
        row.addProperty("jumpPressed", mc.gameSettings.keyBindJump.getIsKeyPressed());
        row.addProperty("worldLoaded", mc.theWorld != null);
        row.addProperty(
            "screenClass",
            mc.currentScreen == null ? ""
                : mc.currentScreen.getClass()
                    .getName());
        row.addProperty(
            "fmlLoading",
            FMLClientHandler.instance()
                .isLoading());
        NetworkManager connection = null;
        if (mc.currentScreen instanceof GuiConnecting connecting)
            connection = ReflectionHelper.getPrivateValue(GuiConnecting.class, connecting, "field_146371_g");
        else if (mc.getNetHandler() != null) connection = FMLClientHandler.instance()
            .getClientToServerNetworkManager();
        row.add("network", FinalNetworkStateSnapshot.connection(connection));
        if (mc.currentScreen instanceof GuiDisconnected disconnected) {
            IChatComponent reason = ReflectionHelper
                .getPrivateValue(GuiDisconnected.class, disconnected, "field_146304_f");
            row.addProperty("disconnectReason", reason == null ? "" : reason.getUnformattedText());
        }
        row.addProperty(
            "username",
            mc.getSession()
                .getUsername());
        row.addProperty("worldIdentity", mc.theWorld == null ? 0 : System.identityHashCode(mc.theWorld));
        row.addProperty("playerIdentity", mc.thePlayer == null ? 0 : System.identityHashCode(mc.thePlayer));
        row.addProperty("liftTicksRemaining", liftTicks);
        if (mc.thePlayer != null && mc.theWorld != null) {
            row.addProperty(
                "playerUuid",
                mc.thePlayer.getUniqueID()
                    .toString());
            row.addProperty("health", mc.thePlayer.getHealth());
            row.addProperty("invulnerable", mc.thePlayer.capabilities.disableDamage);
            row.addProperty("flying", mc.thePlayer.capabilities.isFlying);
            row.addProperty("dimension", mc.thePlayer.dimension);
            row.addProperty("x", mc.thePlayer.posX);
            row.addProperty("y", mc.thePlayer.posY);
            row.addProperty("z", mc.thePlayer.posZ);
            row.addProperty("jumpPressed", mc.gameSettings.keyBindJump.getIsKeyPressed());
            row.add("container", FinalNetworkStateSnapshot.container(mc.thePlayer));
            row.add("playerInputs", FinalNetworkStateSnapshot.playerInputs(mc.thePlayer));
        }
        var maids = new JsonArray();
        if (mc.theWorld != null) for (Object raw : mc.theWorld.loadedEntityList)
            if (raw instanceof EntityMaid maid && maid.getCustomNameTag()
                .startsWith("QA-" + batch + "-")) {
                    var visible = FinalNetworkStateSnapshot.maid(maid);
                    visible.addProperty("ownedByLocalPlayer", mc.thePlayer != null && maid.isOwner(mc.thePlayer));
                    if (mc.thePlayer != null)
                        visible.addProperty("distanceSqToLocalPlayer", mc.thePlayer.getDistanceSqToEntity(maid));
                    maids.add(visible);
                }
        row.add("maids", maids);
        FinalNetworkStateFiles.publish(output, row.toString());
        if (rows % 5 == 0) Files.write(
            root.resolve("snapshots.jsonl"),
            (row + "\n").getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND);
    }

    private void act(JsonObject request, JsonObject response) {
        var mc = Minecraft.getMinecraft();
        String action = request.get("action")
            .getAsString();
        if (action.equals("release-input")) {
            releaseInput();
            boolean restored = mc.gameSettings.keyBindJump.getIsKeyPressed() == initialJump;
            response.addProperty("inputRestored", restored);
            require(restored, "Native jump input differs from startup baseline");
            return;
        }
        if (action.equals("shutdown")) {
            releaseInput();
            mc.shutdown();
            return;
        }
        if (action.equals("connect")) {
            require(mc.theWorld == null, "Actual native disconnect required before connect");
            int port = request.get("port")
                .getAsInt();
            require(port > 0 && port <= 65535, "Actual loopback port required");
            var handler = FMLClientHandler.instance();
            handler.setupServerList();
            handler.connectToServer(new GuiMainMenu(), new ServerData("Final QA", "127.0.0.1:" + port, false));
            response.addProperty("nativeAction", "FMLClientHandler.setupServerList/connectToServer real TCP");
            response.addProperty(
                "screenAfterConnect",
                mc.currentScreen == null ? ""
                    : mc.currentScreen.getClass()
                        .getName());
            return;
        }
        require(
            mc.thePlayer != null && mc.theWorld != null
                && mc.getNetHandler() != null
                && mc.thePlayer.capabilities.disableDamage
                && mc.thePlayer.getHealth() == 20,
            "Connected protected actual client required");
        if (action.equals("disconnect")) {
            releaseInput();
            mc.thePlayer.closeScreen();
            mc.theWorld.sendQuittingDisconnectingPacket();
            mc.loadWorld(null);
            mc.displayGuiScreen(new GuiMainMenu());
            response.addProperty("nativeAction", "WorldClient native disconnect and unload");
        } else if (action.equals("close")) mc.thePlayer.closeScreen();
        else if (action.equals("look")) {
            mc.thePlayer.rotationYaw = request.get("yaw")
                .getAsFloat();
            mc.thePlayer.rotationPitch = request.get("pitch")
                .getAsFloat();
        } else if (action.equals("lift")) {
            int ticks = request.get("ticks")
                .getAsInt();
            require(
                mc.getSession()
                    .getUsername()
                    .equals("TLMNHTest") && mc.thePlayer.capabilities.allowFlying
                    && mc.thePlayer.capabilities.isFlying
                    && mc.currentScreen == null
                    && liftTicks == 0
                    && ticks >= 1
                    && ticks <= 60,
                "Real airborne owner with bounded native jump input required");
            originalJump = mc.gameSettings.keyBindJump.getIsKeyPressed();
            liftTicks = ticks;
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), true);
            response.addProperty("nativeAction", "Native flying movement via jump KeyBinding and normal C03 packets");
            response.addProperty("originalJumpPressed", originalJump);
        } else if (action.equals("interact")) {
            require(mc.currentScreen == null, "Close the prior native screen first");
            var uuid = UUID.fromString(
                request.get("maid")
                    .getAsString());
            EntityMaid target = null;
            for (Object raw : mc.theWorld.loadedEntityList) if (raw instanceof EntityMaid maid && maid.getUniqueID()
                .equals(uuid)) target = maid;
            require(target != null, "Requested maid is not tracked by the actual client");
            require(target.isOwner(mc.thePlayer), "Tracked maid does not recognize actual client ownership");
            require(
                mc.thePlayer.getDistanceSqToEntity(target) <= 64,
                "Tracked owned maid is outside native interaction reach");
            require(
                mc.thePlayer.getHeldItem() == null && mc.thePlayer.inventory.getItemStack() == null,
                "Native empty hand and cursor required for fixture interaction");
            mc.playerController.interactWithEntitySendPacket(mc.thePlayer, target);
            response.addProperty("nativePacket", "C02_INTERACT");
        } else {
            require(
                mc.thePlayer.openContainer instanceof ContainerMaid container && container.getMaid()
                    .isOwner(mc.thePlayer)
                    && container.getMaid()
                        .getCustomNameTag()
                        .startsWith("QA-" + batch + "-"),
                "Actual owned maid container required");
            var container = mc.thePlayer.openContainer;
            if (action.equals("click")) {
                int slot = request.get("slot")
                    .getAsInt();
                require(slot >= 0 && slot < container.inventorySlots.size(), "Actual native slot required");
                mc.playerController.windowClick(container.windowId, slot, 0, 0, mc.thePlayer);
                response.addProperty("nativePacket", "C0E_CLICK_WINDOW");
            } else if (action.equals("button")) {
                String name = request.get("button")
                    .getAsString();
                int button = switch (name) {
                    case "bauble" -> MaidContainerProtocol.ACTION_BAUBLE;
                    case "main" -> MaidContainerProtocol.ACTION_ORIGINAL_MAIN;
                    case "maintenance" -> MaidContainerProtocol.ACTION_MAINTENANCE_HATCH;
                    case "breeding" -> MaidContainerProtocol.ACTION_CROPSNH_BREEDING;
                    default -> throw new IllegalArgumentException("Unapproved native GUI action");
                };
                mc.playerController.sendEnchantPacket(container.windowId, button);
                response.addProperty("nativePacket", "C11_ENCHANT_ITEM");
            } else throw new IllegalArgumentException("Unknown final client input");
        }
    }

    private void releaseInput() {
        if (liftTicks <= 0) return;
        var mc = Minecraft.getMinecraft();
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), originalJump);
        liftTicks = 0;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
