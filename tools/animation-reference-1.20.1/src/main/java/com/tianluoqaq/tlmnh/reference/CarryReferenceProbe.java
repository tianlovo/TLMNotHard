package com.tianluoqaq.tlmnh.reference;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import com.github.tartaricacid.touhoulittlemaid.capability.GeckoMaidEntityCapabilityProvider;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** One actual native saddle carry in the existing original platform, with numerical geometry and a third-person image. */
@Mod.EventBusSubscriber(modid = "touhou_little_maid", value = Dist.CLIENT)
public final class CarryReferenceProbe {
    private static Saved saved;
    private static Pending pending;
    private static boolean nativeMountRequested;
    private static boolean nativeEjectRequested;
    private CarryReferenceProbe() {}

    static JsonObject fixture() {
        var mc = lab();
        require(saved == null && pending == null && mc.player.isSpectator(), "Idle protected carry fixture required");
        UUID id = UUID.fromString(ReferenceScene.status().get("entityUuid").getAsString());
        UUID owner = mc.player.getUUID();
        CameraType camera = mc.options.getCameraType();
        saved = mc.getSingleplayerServer().submit(() -> {
            var server = mc.getSingleplayerServer();
            var player = server.getPlayerList().getPlayer(owner);
            var entity = server.overworld().getEntity(id);
            require(entity instanceof EntityMaid maid && ReferenceScene.isCurrent(maid) && maid.isOwnedBy(player)
                && maid.getModelId().equals("geckolib:winefox_magical"), "Owned original Magic scene required");
            var maid = (EntityMaid) entity;
            require(player != null && player.isSpectator() && player.getVehicle() == null && player.getPassengers().isEmpty()
                && maid.getVehicle() == null && maid.getPassengers().isEmpty() && !player.isShiftKeyDown(),
                "Unridden original owner and maid required");
            var before = new Saved(owner, id, maid.getId(), player.position(), player.getDeltaMovement(), player.getYRot(),
                player.getXRot(), camera, player.getInventory().selected, player.getMainHandItem().copy(),
                player.getInventory().save(new ListTag()).copy(), maid.isHomeModeEnable(), null);
            player.setGameMode(GameType.CREATIVE);
            player.connection.teleport(maid.getX(), maid.getY(), maid.getZ() - 1.25, 0, 0);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SADDLE));
            var expected = player.getInventory().save(new ListTag()).copy();
            player.inventoryMenu.broadcastChanges();
            return new Saved(before.owner(), before.entity(), before.entityId(), before.position(), before.velocity(), before.yaw(),
                before.pitch(), before.camera(), before.selected(), before.hand(), before.inventory(), before.home(), expected);
        }).join();
        nativeMountRequested = false;
        nativeEjectRequested = false;
        return snapshot();
    }

    static JsonObject view() {
        var mc = lab(); var own = saved;
        require(own != null && pending == null && !nativeEjectRequested, "Prepared original carry fixture required");
        if (!nativeMountRequested) {
            require(mc.gameMode != null && mc.gameMode.getPlayerMode() == GameType.CREATIVE
                && mc.player.isCreative() && mc.player.getInventory().selected == own.selected()
                && mc.player.getMainHandItem().is(Items.SADDLE) && !mc.player.isShiftKeyDown(),
                "Wait for actual client Creative mode and native saddle inventory synchronization");
            var clientEntity = mc.level.getEntity(own.entityId());
            require(clientEntity instanceof EntityMaid && clientEntity.getUUID().equals(own.entity())
                && clientEntity.getVehicle() == null && clientEntity.getPassengers().isEmpty()
                && mc.player.getVehicle() == null && mc.player.getPassengers().isEmpty(), "Native unmounted client identities required");
            verifyServerState(own, false);
            nativeMountRequested = true;
            var result = mc.gameMode.interact(mc.player, clientEntity, InteractionHand.MAIN_HAND);
            require(clientEntity.getVehicle() == mc.player && mc.player.getPassengers().contains(clientEntity),
                "Actual local native saddle interaction did not establish carry: " + result);
        }
        mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
        return snapshot();
    }

    private static void verifyServerState(Saved own, boolean carried) {
        var mc = lab();
        mc.getSingleplayerServer().submit(() -> {
            var player = mc.getSingleplayerServer().getPlayerList().getPlayer(own.owner());
            var entity = mc.getSingleplayerServer().overworld().getEntity(own.entity());
            require(player != null && player.isCreative() && entity instanceof EntityMaid
                && (carried ? entity.getVehicle() == player && player.getPassengers().size() == 1
                    : entity.getVehicle() == null && player.getPassengers().isEmpty())
                && entity.getPassengers().isEmpty() && player.getVehicle() == null
                && player.getInventory().selected == own.selected()
                && player.getInventory().save(new ListTag()).equals(own.expectedInventory()),
                "Unknown original carry/inventory successor retained, or native interaction packet still pending");
        }).join();
    }

    static JsonObject snapshot() {
        var mc = Minecraft.getInstance();
        var result = new JsonObject();
        result.addProperty("fixtureActive", saved != null);
        result.addProperty("cameraType", mc.options.getCameraType().name());
        result.addProperty("scope", "Native MultiPlayerGameMode.interact sends the ordinary interaction packet and runs local Player.interactOn; natural positionRider updates only");
        result.addProperty("nativeMountRequested", nativeMountRequested);
        result.addProperty("nativeEjectRequested", nativeEjectRequested);
        if (mc.gameMode != null) result.addProperty("actualClientGameMode", mc.gameMode.getPlayerMode().getName());
        if (mc.player != null) result.addProperty("clientMainHandNativeSaddle", mc.player.getMainHandItem().is(Items.SADDLE));
        var own = saved;
        if (own == null) return result;
        result.addProperty("maidUuid", own.entity().toString());
        if (mc.player != null && mc.level != null && mc.level.getEntity(own.entityId()) instanceof EntityMaid maid) {
            result.add("clientPlayer", nativeEntity(mc.player)); result.add("clientMaid", nativeEntity(maid));
            result.addProperty("clientCarriedByOwner", maid.getVehicle() == mc.player);
            var playerRenderer = mc.getEntityRenderDispatcher().getRenderer(mc.player);
            result.addProperty("actualPlayerRendererClass", playerRenderer.getClass().getName());
            if (playerRenderer instanceof PlayerRenderer nativePlayerRenderer) {
                var playerModel = nativePlayerRenderer.getModel();
                var arms = new JsonObject();
                arms.addProperty("modelClass", playerModel.getClass().getName());
                arms.add("left", modelPart(playerModel.leftArm));
                arms.add("right", modelPart(playerModel.rightArm));
                arms.addProperty("scope", "Actual shared renderer model after last world render; read only, no setupAnim or pose substitution");
                result.add("nativePlayerArmsFromLastActualWorldRender", arms);
            }
            var animated = maid.getCapability(GeckoMaidEntityCapabilityProvider.CAP).orElse(null);
            if (animated != null && animated.getCurrentModel() != null) {
                var bones = new JsonObject();
                ReferenceObservationProbe.geckoBones(animated.getCurrentModel(), bones);
                result.add("modelBonesFromLastActualWorldRender", bones);
                result.addProperty("modelBoneSnapshotNativeAge", maid.tickCount);
            }
        }
        result.add("server", mc.getSingleplayerServer().submit(() -> {
            var player = mc.getSingleplayerServer().getPlayerList().getPlayer(own.owner());
            var entity = mc.getSingleplayerServer().overworld().getEntity(own.entity());
            require(player != null && entity instanceof EntityMaid, "Original live carry identities required");
            var row = new JsonObject(); row.add("player", nativeEntity(player)); row.add("maid", nativeEntity(entity));
            row.addProperty("carriedByOwner", entity.getVehicle() == player);
            Vec3 expected = player.position().add(new Vec3(0, 0, .75).yRot((float) -Math.toRadians(player.yBodyRot)))
                .add(0, player.isDescending() ? -.15 : .15, 0);
            row.addProperty("sourceExpectedX", expected.x); row.addProperty("sourceExpectedY", expected.y); row.addProperty("sourceExpectedZ", expected.z);
            row.addProperty("deltaToSourceExpectedX", entity.getX() - expected.x);
            row.addProperty("deltaToSourceExpectedY", entity.getY() - expected.y);
            row.addProperty("deltaToSourceExpectedZ", entity.getZ() - expected.z);
            row.addProperty("playerBodyYaw", player.yBodyRot);
            row.addProperty("playerDescending", player.isDescending());
            row.addProperty("mainHandStillNativeSaddle", player.getMainHandItem().is(Items.SADDLE));
            return row;
        }).join());
        return result;
    }

    private static JsonObject modelPart(ModelPart part) {
        var row = new JsonObject();
        row.addProperty("x", part.x); row.addProperty("y", part.y); row.addProperty("z", part.z);
        row.addProperty("xRotRadians", part.xRot); row.addProperty("yRotRadians", part.yRot); row.addProperty("zRotRadians", part.zRot);
        row.addProperty("xRotDegrees", Math.toDegrees(part.xRot)); row.addProperty("yRotDegrees", Math.toDegrees(part.yRot));
        row.addProperty("zRotDegrees", Math.toDegrees(part.zRot));
        row.addProperty("xScale", part.xScale); row.addProperty("yScale", part.yScale); row.addProperty("zScale", part.zScale);
        row.addProperty("visible", part.visible); row.addProperty("skipDraw", part.skipDraw);
        return row;
    }

    private static JsonObject nativeEntity(Entity entity) {
        var row = new JsonObject(); var box = entity.getBoundingBox();
        row.addProperty("uuid", entity.getUUID().toString()); row.addProperty("class", entity.getClass().getName());
        row.addProperty("nativeAge", entity.tickCount);
        row.addProperty("x", entity.getX()); row.addProperty("y", entity.getY()); row.addProperty("z", entity.getZ());
        row.addProperty("bboxMinX", box.minX); row.addProperty("bboxMinY", box.minY); row.addProperty("bboxMinZ", box.minZ);
        row.addProperty("bboxMaxX", box.maxX); row.addProperty("bboxMaxY", box.maxY); row.addProperty("bboxMaxZ", box.maxZ);
        row.addProperty("eyeHeight", entity.getEyeHeight()); row.addProperty("yaw", entity.getYRot()); row.addProperty("pitch", entity.getXRot());
        row.addProperty("getMyRidingOffset", entity.getMyRidingOffset()); row.addProperty("getPassengersRidingOffset", entity.getPassengersRidingOffset());
        row.addProperty("vehicleUuid", entity.getVehicle() == null ? "" : entity.getVehicle().getUUID().toString());
        return row;
    }

    static JsonObject capture(JsonObject request, Path root, String name) {
        var mc = lab(); require(saved != null && pending == null && mc.options.getCameraType() == CameraType.THIRD_PERSON_FRONT,
            "Ready native third-person front carry required");
        String filename = request.get("name").getAsString();
        require(filename.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,80}\\.png"), "Invalid carry screenshot filename");
        pending = new Pending(root, name, filename); var result = new JsonObject(); result.addProperty("status", "waiting-render"); return result;
    }

    @SubscribeEvent
    public static void rendered(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || pending == null) return;
        var capture = pending; pending = null; var result = new JsonObject();
        try {
            var mc = lab(); var facts = snapshot();
            require(facts.get("clientCarriedByOwner").getAsBoolean()
                && facts.getAsJsonObject("server").get("carriedByOwner").getAsBoolean(),
                "Wait for actual native carry on both client and server");
            var image = capture.root().resolve("world").resolve(capture.filename()); Files.createDirectories(image.getParent());
            require(!Files.exists(image), "Carry screenshot already exists");
            try (var pixels = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { pixels.writeToFile(image); }
            result.addProperty("status", "complete"); result.addProperty("image", "world/" + capture.filename());
            result.add("carryAtCapture", facts); result.add("runtimeIdentity", ReferenceRuntimeIdentity.capture().runtimeIdentity());
        } catch (Exception failure) { result.addProperty("status", "failed"); result.addProperty("error", failure.toString()); }
        try {
            var target = capture.root().resolve("responses").resolve(capture.request()); var temporary = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(temporary, new GsonBuilder().serializeNulls().create().toJson(result) + "\n");
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException failure) { throw new IllegalStateException("Cannot save actual carry capture", failure); }
    }

    static JsonObject restore() {
        var mc = lab(); var own = saved;
        require(own != null && pending == null, "Actual carry fixture required");
        if (nativeMountRequested && !nativeEjectRequested) {
            verifyServerState(own, true);
            var clientEntity = mc.level.getEntity(own.entityId());
            require(mc.gameMode != null && mc.gameMode.getPlayerMode() == GameType.CREATIVE
                && mc.player.getMainHandItem().is(Items.SADDLE) && clientEntity != null
                && clientEntity.getUUID().equals(own.entity()) && clientEntity.getVehicle() == mc.player
                && mc.player.getPassengers().size() == 1, "Actual client carry and native saddle required for ordinary eject interaction");
            nativeEjectRequested = true;
            var result = mc.gameMode.interact(mc.player, clientEntity, InteractionHand.MAIN_HAND);
            require(clientEntity.getVehicle() == null && mc.player.getPassengers().isEmpty(),
                "Actual local native saddle interaction did not eject: " + result);
            var response = snapshot();
            response.addProperty("status", "complete");
            response.addProperty("restorationComplete", false);
            response.addProperty("nextStep", "After ordinary server packet processing, call carry_restore again to restore the original inventory and camera");
            return response;
        }
        verifyServerState(own, false);
        var clientEntity = mc.level.getEntity(own.entityId());
        require(clientEntity != null && clientEntity.getUUID().equals(own.entity())
            && clientEntity.getVehicle() == null && mc.player.getPassengers().isEmpty(),
            "Wait for actual native dismount on both sides before restoring the player");
        mc.getSingleplayerServer().submit(() -> {
            var server = mc.getSingleplayerServer(); var player = server.getPlayerList().getPlayer(own.owner());
            var entity = server.overworld().getEntity(own.entity());
            require(player != null && entity instanceof EntityMaid && entity.getVehicle() == null
                && player.getPassengers().isEmpty() && player.getInventory().selected == own.selected()
                && player.getInventory().save(new ListTag()).equals(own.expectedInventory()), "Unknown carry/inventory successor retained");
            var maid = (EntityMaid) entity;
            player.setItemInHand(InteractionHand.MAIN_HAND, own.hand().copy());
            player.inventoryMenu.broadcastChanges();
            maid.setHomeModeEnable(own.home());
            player.setGameMode(GameType.SPECTATOR);
            player.connection.teleport(own.position().x, own.position().y, own.position().z, own.yaw(), own.pitch());
            player.setDeltaMovement(own.velocity());
            require(player.getInventory().save(new ListTag()).equals(own.inventory()), "Original player inventory restoration differs");
        }).join();
        mc.options.setCameraType(own.camera()); saved = null;
        var response = new JsonObject(); response.addProperty("status", "complete"); response.addProperty("restorationComplete", true);
        response.addProperty("originalInventoryRestored", true); response.addProperty("nativeSaddleEjected", nativeEjectRequested);
        response.addProperty("cameraAndSpectatorRestored", true);
        nativeMountRequested = false; nativeEjectRequested = false;
        return response;
    }

    private static Minecraft lab() {
        var mc = Minecraft.getInstance(); require(Boolean.getBoolean("tlmnh.referenceProbe") && mc.level != null
            && mc.player != null && mc.screen == null && mc.getSingleplayerServer() != null
            && "TLMNH-Reference-Lab".equals(mc.getSingleplayerServer().getWorldData().getLevelName()), "Existing original lab required"); return mc;
    }
    private static void require(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
    private record Pending(Path root, String request, String filename) {}
    private record Saved(UUID owner, UUID entity, int entityId, Vec3 position, Vec3 velocity, float yaw, float pitch,
        CameraType camera, int selected, ItemStack hand, ListTag inventory, boolean home, ListTag expectedInventory) {}
}
