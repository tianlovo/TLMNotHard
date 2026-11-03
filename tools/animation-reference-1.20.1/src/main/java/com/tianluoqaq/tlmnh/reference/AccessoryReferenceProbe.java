package com.tianluoqaq.tlmnh.reference;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.github.tartaricacid.touhoulittlemaid.TouhouLittleMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/** Same-input native head/back images of the existing owned reference scene, without changing its renderer. */
@Mod.EventBusSubscriber(modid = TouhouLittleMaid.MOD_ID, value = Dist.CLIENT)
public final class AccessoryReferenceProbe {

    private static volatile String state = "idle", failure = "", view = "";
    private static volatile Saved saved;
    private static Pending pending;

    private AccessoryReferenceProbe() {}

    private static Minecraft lab() {
        var mc = Minecraft.getInstance();
        require(Boolean.getBoolean("tlmnh.referenceProbe") && mc.level != null && mc.player != null
            && mc.player.isSpectator() && mc.screen == null && mc.getSingleplayerServer() != null
            && "TLMNH-Reference-Lab".equals(mc.getSingleplayerServer().getWorldData().getLevelName()),
            "Current protected original lab required");
        return mc;
    }

    static JsonObject fixture(JsonObject request) {
        var mc = lab();
        String action = request.get("action").getAsString();
        require(action.equals("equip") || action.equals("restore"), "Unknown accessory action");
        require(pending == null && !state.equals("queued"), "Accessory request already pending");
        UUID entity = UUID.fromString(ReferenceScene.status().get("entityUuid").getAsString());
        UUID owner = mc.player.getUUID();
        state = "queued"; failure = "";
        mc.options.pauseOnLostFocus = false;
        mc.getSingleplayerServer().execute(() -> {
            try {
                var server = mc.getSingleplayerServer();
                var player = server.getPlayerList().getPlayer(owner);
                require(player != null && player.isSpectator(), "Original observer changed");
                require(server.overworld().getEntity(entity) instanceof EntityMaid, "Original maid missing");
                var maid = (EntityMaid) server.overworld().getEntity(entity);
                require(ReferenceScene.isCurrent(maid) && maid.isOwnedBy(player), "Owned original scene required");
                if (action.equals("equip")) {
                    require(saved == null && (maid.getModelId().equals("touhou_little_maid:hakurei_reimu")
                        || maid.getModelId().equals("geckolib:winefox_hanfu")), "Only the two reviewed original models are allowed");
                    saved = new Saved(owner, entity, maid.getId(), maid.getModelId(), maid.getItemBySlot(EquipmentSlot.HEAD).copy(),
                        maid.getMaidInv().getStackInSlot(5).copy(), maid.getBackpackShowItem().copy(),
                        maid.getConfigManager().isShowBackItem(), maid.getHunger(), maid.isInSittingPose(),
                        player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot(),
                        maid.yBodyRot, maid.yBodyRotO, maid.yHeadRot, maid.yHeadRotO);
                    maid.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CREEPER_HEAD));
                    maid.getMaidInv().setStackInSlot(5, new ItemStack(Items.COMPASS));
                    maid.setBackpackShowItem(new ItemStack(Items.COMPASS));
                    maid.getConfigManager().setShowBackItem(true);
                    maid.setHunger(20); maid.setInSittingPose(false);
                    require(maid.getYRot() == 180 && maid.getXRot() == 0, "Original body orientation must match the lab input");
                    maid.yBodyRotO = maid.yBodyRot = maid.yHeadRotO = maid.yHeadRot = 180;
                    view = ""; state = "ready";
                } else {
                    var original = saved;
                    require(original != null && original.owner().equals(owner) && original.entity().equals(entity)
                        && original.model().equals(maid.getModelId()), "Original fixture identity changed; retained");
                    require(same(maid.getItemBySlot(EquipmentSlot.HEAD), new ItemStack(Items.CREEPER_HEAD))
                        && same(maid.getMaidInv().getStackInSlot(5), new ItemStack(Items.COMPASS))
                        && same(maid.getBackpackShowItem(), new ItemStack(Items.COMPASS))
                        && maid.getConfigManager().isShowBackItem() && maid.getHunger() == 20 && !maid.isInSittingPose(),
                        "Changed original accessory successor retained");
                    maid.setItemSlot(EquipmentSlot.HEAD, original.head().copy());
                    maid.getMaidInv().setStackInSlot(5, original.back().copy());
                    maid.setBackpackShowItem(original.display().copy());
                    maid.getConfigManager().setShowBackItem(original.show());
                    maid.setHunger(original.hunger()); maid.setInSittingPose(original.sitting());
                    maid.yBodyRot = original.body(); maid.yBodyRotO = original.previousBody();
                    maid.yHeadRot = original.headYaw(); maid.yHeadRotO = original.previousHead();
                    double[] camera = camera(view);
                    if (camera != null && player.distanceToSqr(camera[0], camera[1], camera[2]) < .000001)
                        player.connection.teleport(original.x(), original.y(), original.z(), original.yaw(), original.pitch());
                    saved = null; view = ""; state = "restored";
                }
            } catch (RuntimeException error) {
                failure = error.toString(); state = "failed";
                TouhouLittleMaid.LOGGER.error("TLMNH_REFERENCE_ACCESSORY_FAILED", error);
            }
        });
        return snapshot();
    }

    static JsonObject view(JsonObject request) {
        var mc = lab();
        String requested = request.get("action").getAsString();
        double[] position = camera(requested);
        require(state.equals("ready") && saved != null && position != null && pending == null, "Ready original accessories required");
        UUID owner = mc.player.getUUID();
        mc.getSingleplayerServer().execute(() -> {
            var player = mc.getSingleplayerServer().getPlayerList().getPlayer(owner);
            require(player != null && player.isSpectator() && saved != null && saved.owner().equals(owner),
                "Original observer changed");
            player.connection.teleport(position[0], position[1], position[2], (float) position[3], (float) position[4]);
            view = requested;
        });
        return snapshot();
    }

    private static double[] camera(String action) {
        return switch (action) {
            // Native player feet coordinates; both versions have the same 1.62 eye offset.
            case "front" -> new double[] { 1.5, 65.18, -2.5, 18.435, 9 };
            case "back" -> new double[] { .5, 65.5, 3.5, 180, 11.7 };
            default -> null;
        };
    }

    static JsonObject snapshot() {
        var mc = Minecraft.getInstance();
        var result = new JsonObject();
        result.addProperty("fixture", state); result.addProperty("error", failure); result.addProperty("view", view);
        result.addProperty("fixtureActive", saved != null);
        var original = saved;
        if (mc.level != null && original != null && mc.level.getEntity(original.entityId()) instanceof EntityMaid maid
            && maid.getUUID().equals(original.entity())) {
            result.addProperty("uuid", maid.getUUID().toString()); result.addProperty("model", maid.getModelId());
            result.addProperty("hunger", maid.getHunger()); result.addProperty("sitting", maid.isInSittingPose());
            result.addProperty("ticks", maid.tickCount); result.addProperty("health", maid.getHealth());
            result.addProperty("showBackItem", maid.getConfigManager().isShowBackItem());
            result.addProperty("headNativeNbtBase64", nativeNbt(maid.getItemBySlot(EquipmentSlot.HEAD)));
            result.addProperty("backNativeNbtBase64", nativeNbt(maid.getBackpackShowItem()));
            result.addProperty("head", maid.getItemBySlot(EquipmentSlot.HEAD).save(new CompoundTag()).toString());
            result.addProperty("back", maid.getBackpackShowItem().save(new CompoundTag()).toString());
            result.addProperty("bodyYaw", maid.yBodyRot); result.addProperty("headYaw", maid.yHeadRot);
            result.addProperty("previousBodyYaw", maid.yBodyRotO); result.addProperty("previousHeadYaw", maid.yHeadRotO);
            result.addProperty("yaw", maid.getYRot()); result.addProperty("pitch", maid.getXRot());
            result.addProperty("x", maid.getX()); result.addProperty("y", maid.getY()); result.addProperty("z", maid.getZ());
        }
        if (mc.player != null) {
            result.addProperty("spectator", mc.player.isSpectator());
            result.addProperty("cameraX", mc.player.getX()); result.addProperty("cameraY", mc.player.getY());
            result.addProperty("cameraZ", mc.player.getZ()); result.addProperty("cameraYaw", mc.player.getYRot());
            result.addProperty("cameraPitch", mc.player.getXRot());
        }
        return result;
    }

    static JsonObject capture(JsonObject request, Path root, String name) {
        var mc = lab();
        require(state.equals("ready") && pending == null, "Ready original accessory capture required");
        String filename = request.get("name").getAsString();
        require(filename.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,80}\\.png"), "Invalid original accessory screenshot name");
        pending = new Pending(root, name, filename, mc.level);
        var result = new JsonObject(); result.addProperty("status", "waiting-render"); return result;
    }

    @SubscribeEvent
    public static void rendered(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || pending == null) return;
        var capture = pending; pending = null;
        var result = new JsonObject();
        try {
            var mc = lab();
            require(mc.level == capture.level() && saved != null, "Original scene changed before accessory capture");
            var image = capture.root().resolve("world").resolve(capture.filename());
            Files.createDirectories(image.getParent()); require(!Files.exists(image), "Original image already exists");
            try (var pixels = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { pixels.writeToFile(image); }
            result.addProperty("status", "complete"); result.addProperty("image", "world/" + capture.filename());
            result.add("accessoriesAtCapture", snapshot());
            result.add("runtimeIdentity", ReferenceRuntimeIdentity.capture().runtimeIdentity());
        } catch (IOException | RuntimeException error) {
            result.addProperty("status", "failed"); result.addProperty("error", error.toString());
        }
        try {
            var target = capture.root().resolve("responses").resolve(capture.request());
            var temporary = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(temporary, new GsonBuilder().serializeNulls().create().toJson(result) + "\n");
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) { TouhouLittleMaid.LOGGER.error("TLMNH_REFERENCE_ACCESSORY_CAPTURE_FAILED", error); }
    }

    private static boolean same(ItemStack actual, ItemStack expected) {
        return actual.save(new CompoundTag()).equals(expected.save(new CompoundTag()));
    }

    private static String nativeNbt(ItemStack stack) {
        try (var bytes = new ByteArrayOutputStream(); var output = new DataOutputStream(bytes)) {
            NbtIo.write(stack.save(new CompoundTag()), output);
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException error) { throw new IllegalStateException("Original native item snapshot failed", error); }
    }

    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private record Pending(Path root, String request, String filename, ClientLevel level) {}
    private record Saved(UUID owner, UUID entity, int entityId, String model, ItemStack head, ItemStack back, ItemStack display,
        boolean show, int hunger, boolean sitting, double x, double y, double z, float yaw, float pitch,
        float body, float previousBody, float headYaw, float previousHead) {}
}
