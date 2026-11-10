package com.tianluoqaq.tlmnh.qa.client;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Map;

import net.minecraft.client.Minecraft;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.client.render.MaidRenderer;
import com.tianluoqaq.tlmnh.common.development.PackagedClassResourceResolver;
import com.tianluoqaq.tlmnh.common.development.interaction.MaidVisibleStateSnapshot;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.NetworkCheckHandler;
import cpw.mods.fml.relauncher.Side;

/** 独立客户端QA小包；只读本场女仆与已完成渲染，不修改客户端实体 */
@Mod(
    modid = "tlmnhqanetworkclient",
    name = "TLMNH network client observation",
    version = "1",
    dependencies = "required-after:tlmnh",
    acceptableRemoteVersions = "*")
public final class CombatNetworkClientObservation {

    private static final int LIMIT = 24000;
    private Path output;
    private int rows, lastEntityTick = Integer.MIN_VALUE;
    private String batch;
    private boolean overflow;

    @NetworkCheckHandler
    public boolean remoteOptional(Map<String, String> mods, Side side) {
        return true;
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) throws Exception {
        if (!"true".equals(System.getenv("TLMNH_QA_COMBAT_NETWORK_OBSERVE"))) return;
        if (!FMLCommonHandler.instance()
            .getSide()
            .isClient()) throw new IllegalStateException("Client QA addon on server");
        batch = System.getenv("TLMNH_QA_COMBAT_NETWORK_BATCH");
        if (batch == null || !batch.matches("network-[0-9]+"))
            throw new IllegalStateException("Explicit batch required");
        Path root = Minecraft.getMinecraft().mcDataDir.toPath()
            .toRealPath();
        Path directory = root.resolve("tlmnh-dev/combat-network");
        for (Path p = directory; p != null && p.startsWith(root); p = p.getParent())
            if (Files.isSymbolicLink(p) || Files.exists(p) && !p.toRealPath()
                .equals(
                    p.toAbsolutePath()
                        .normalize()))
                throw new IllegalStateException("QA output reparse point");
        Files.createDirectories(directory);
        output = directory.resolve(batch + "-snapshots.jsonl");
        JsonObject header = new JsonObject();
        header.addProperty("schema", "combat-network-client-snapshot-v2");
        header.addProperty("batch", batch);
        header.addProperty("javaSpecificationVersion", System.getProperty("java.specification.version"));
        header.addProperty("mainSha256", sha(PackagedClassResourceResolver.artifactOf(EntityMaid.class)));
        header.addProperty("addonSha256", sha(PackagedClassResourceResolver.artifactOf(getClass())));
        header.addProperty("captureBoundary", "RenderTick.END; at most one current-maid row per entity tick");
        header.addProperty("transformedDefinitionBytesVerified", false);
        header.addProperty(
            "addonCodeSource",
            getClass().getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toExternalForm());
        Files.write(output, (header + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
        FMLCommonHandler.instance()
            .bus()
            .register(this);
    }

    @SubscribeEvent
    public void tick(TickEvent.RenderTickEvent event) throws Exception {
        if (event.phase != TickEvent.Phase.END || output == null || overflow) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null || mc.theWorld.provider.dimensionId != 0) return;
        EntityMaid current = null;
        for (Object raw : mc.theWorld.loadedEntityList)
            if (raw instanceof EntityMaid maid && ("QA-" + batch).equals(maid.getCustomNameTag())) {
                if (current != null) throw new IllegalStateException("Duplicate current-batch maid replica");
                current = maid;
            }
        if (current == null || current.ticksExisted == lastEntityTick) return;
        lastEntityTick = current.ticksExisted;
        JsonObject row;
        if (++rows >= LIMIT) {
            row = new JsonObject();
            row.addProperty("overflow", true);
            overflow = true;
        } else {
            row = MaidVisibleStateSnapshot.capture(current);
            row.addProperty(
                "uuid",
                current.getUniqueID()
                    .toString());
            row.addProperty("dimension", mc.theWorld.provider.dimensionId);
            row.addProperty("worldTime", mc.theWorld.getTotalWorldTime());
            row.addProperty("entityTick", current.ticksExisted);
            row.addProperty(
                "taskId",
                current.getTask()
                    .getId());
            row.addProperty("x", current.posX);
            row.addProperty("y", current.posY);
            row.addProperty("z", current.posZ);
            row.addProperty("aimingBow", current.hasPose(MaidPose.AIMING_BOW));
            row.addProperty("swingProgress", current.getSwingProgress(1));
            var renderer = net.minecraft.client.renderer.entity.RenderManager.instance.getEntityRenderObject(current);
            row.addProperty("renderCompleted", false);
            row.addProperty("renderSerial", -1);
            row.addProperty("lastRenderTick", -1);
            if (renderer instanceof MaidRenderer actual) {
                JsonObject diagnostic = actual.inspectPreviewForDevelopment(current);
                if (diagnostic.has("completedPose")) row.add("renderCompleted", diagnostic.get("completedPose"));
                if (diagnostic.has("poseSerial")) row.add("renderSerial", diagnostic.get("poseSerial"));
                if (diagnostic.has("lastRenderTick")) row.add("lastRenderTick", diagnostic.get("lastRenderTick"));
            }
        }
        Files.write(output, (row + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
    }

    private static String sha(Path path) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(path));
        StringBuilder result = new StringBuilder();
        for (byte b : digest) result.append(String.format("%02x", b & 255));
        return result.toString();
    }
}
