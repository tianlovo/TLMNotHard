package com.tianluoqaq.tlmnh.compat.angelica.client.development;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

import net.minecraft.client.Minecraft;

import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.common.development.AnimationLab;
import com.tianluoqaq.tlmnh.common.protection.DependencyFingerprint;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 开发世界用实际Iris开关与重载，并保留原配置供同客户端恢复 */
@SideOnly(Side.CLIENT)
public final class MaidWorldShaderProbe {

    private static final String SELECTED = "ComplementaryReimagined_r5.9.3 + EuphoriaPatches_1.10.5";
    private static final String PROVIDER = "2.2.43:sha256:d72ea5bea07943729cf38e4e6a72c4d09987f96bb7ad4e94f42b07f9397a8958";
    private static final String GUARD = "development/angelica_world_shader";
    private static Snapshot pending;

    private MaidWorldShaderProbe() {}

    public static JsonObject perform(String action) {
        var mc = Minecraft.getMinecraft();
        require(Boolean.getBoolean("tlmnh.devCommands"), "Development shader command required");
        require(
            action.equals("status") || action.equals("off") || action.equals("selected") || action.equals("restore"),
            "Unknown world shader action");
        if (!action.equals("restore") && !(action.equals("status") && pending != null)) {
            var player = mc.thePlayer;
            require(
                AnimationLab.isLab(mc.theWorld) && player != null
                    && player.isEntityAlive()
                    && player.capabilities.disableDamage
                    && player.capabilities.isFlying
                    && player.noClip
                    && mc.currentScreen == null,
                "Protected native lab player without a screen required");
        }
        try {
            var api = new Api();
            var result = new JsonObject();
            result.addProperty("action", action);
            result.add("before", api.state());
            if (action.equals("restore")) {
                if (pending != null) {
                    pending.restore(api, mc);
                    pending = null;
                }
                result.addProperty("originalSettingsRestored", true);
            } else if (!action.equals("status")) {
                require(IntegrationGuard.available(Side.CLIENT, GUARD), "Native shader switching is protected");
                if (pending == null) pending = new Snapshot(api, mc);
                pending.verify(api, mc);
                boolean enabled = action.equals("selected");
                try {
                    api.iris.getMethod("toggleShaders", Minecraft.class, boolean.class)
                        .invoke(null, mc, enabled);
                } finally {
                    pending.remember();
                }
                require(
                    api.enabled() == enabled && api.renderingMatches(enabled),
                    "Actual shader pack/pipeline did not reach the requested native state; original settings retained");
                result.addProperty("nativeToggleReturned", true);
            }
            result.add("after", api.state());
            result.addProperty("worldLoaded", mc.theWorld != null);
            result.addProperty("restorePending", pending != null);
            if (pending != null) result.add("originalSettings", pending.summary());
            return result;
        } catch (ReflectiveOperationException | IOException | LinkageError failure) {
            var cause = failure instanceof InvocationTargetException wrapped && wrapped.getCause() != null
                ? wrapped.getCause()
                : failure;
            IntegrationGuard.report(Side.CLIENT, GUARD, cause);
            throw new IllegalStateException("Native world shader operation failed; restore snapshot retained", cause);
        }
    }

    private static final class Api {

        final Class<?> iris;
        final Object config;

        Api() throws ReflectiveOperationException {
            require(
                PROVIDER.equals(DependencyFingerprint.mod("angelica")),
                "Reviewed native Angelica provider required");
            iris = Class.forName("net.coderbot.iris.Iris");
            require(
                (Boolean) iris.getMethod("isInitialized")
                    .invoke(null),
                "Native shader initialization required");
            config = iris.getMethod("getIrisConfig")
                .invoke(null);
            require(
                config != null && config.getClass()
                    .getName()
                    .equals("net.coderbot.iris.config.IrisConfig"),
                "Actual native shader configuration required");
        }

        boolean enabled() throws ReflectiveOperationException {
            return (Boolean) config.getClass()
                .getMethod("areShadersEnabled")
                .invoke(config);
        }

        String packName() throws ReflectiveOperationException {
            var name = (Optional<?>) config.getClass()
                .getMethod("getShaderPackName")
                .invoke(config);
            return name.isPresent() ? (String) name.get() : "";
        }

        String pipeline() throws ReflectiveOperationException {
            var manager = iris.getMethod("getPipelineManagerNullable")
                .invoke(null);
            var pipeline = manager == null ? null
                : manager.getClass()
                    .getMethod("getPipelineNullable")
                    .invoke(manager);
            return pipeline == null ? ""
                : pipeline.getClass()
                    .getName();
        }

        boolean renderingMatches(boolean shaders) throws ReflectiveOperationException {
            var pack = (Optional<?>) iris.getMethod("getCurrentPack")
                .invoke(null);
            boolean fallback = (Boolean) iris.getMethod("isFallback")
                .invoke(null);
            return !fallback && pack.isPresent() == shaders
                && pipeline().equals(
                    shaders ? "net.coderbot.iris.pipeline.DeferredWorldRenderingPipeline"
                        : "net.coderbot.iris.pipeline.FixedFunctionWorldRenderingPipeline")
                && (!shaders || SELECTED.equals(
                    iris.getMethod("getCurrentPackName")
                        .invoke(null)));
        }

        void verifyEmptyQueue() throws ReflectiveOperationException {
            var queue = (Map<?, ?>) iris.getMethod("getShaderPackOptionQueue")
                .invoke(null);
            require(
                queue.isEmpty() && !(Boolean) iris.getMethod("shouldResetShaderPackOptionsOnNextReload")
                    .invoke(null),
                "Pending native shader option edits must be completed first");
        }

        JsonObject state() throws ReflectiveOperationException {
            var result = new JsonObject();
            result.addProperty("provider", PROVIDER);
            result.addProperty("enabled", enabled());
            result.addProperty("configuredPack", packName());
            result.addProperty(
                "currentPackName",
                (String) iris.getMethod("getCurrentPackName")
                    .invoke(null));
            result.addProperty(
                "currentPackPresent",
                ((Optional<?>) iris.getMethod("getCurrentPack")
                    .invoke(null)).isPresent());
            result.addProperty(
                "fallback",
                (Boolean) iris.getMethod("isFallback")
                    .invoke(null));
            result.addProperty(
                "loadId",
                (Integer) iris.getMethod("getShaderPackLoadId")
                    .invoke(null));
            result.addProperty(
                "dimensionName",
                (String) iris.getMethod("getCurrentDimensionName")
                    .invoke(null));
            result.addProperty("pipelineClass", pipeline());
            return result;
        }
    }

    private static final class Snapshot {

        final Object config;
        final Path directory;
        final boolean enabled;
        final String pack;
        final SavedFile settings, options;

        Snapshot(Api api, Minecraft mc) throws ReflectiveOperationException, IOException {
            api.verifyEmptyQueue();
            config = api.config;
            enabled = api.enabled();
            pack = api.packName();
            require(SELECTED.equals(pack), "Previously selected and reviewed shader pack required");
            require(api.renderingMatches(enabled), "Known native initial pipeline required before switching");
            directory = mc.mcDataDir.toPath()
                .toRealPath();
            var packs = ((Path) api.iris.getMethod("getShaderpacksDirectory")
                .invoke(null)).toRealPath();
            require(packs.startsWith(directory), "Shader configuration must belong to this client directory");
            settings = new SavedFile(directory.resolve("config/shaders.properties"), directory);
            options = new SavedFile(packs.resolve(SELECTED + ".txt"), directory);
            require(settings.original != null, "Existing native shader settings required");
        }

        void verify(Api api, Minecraft mc) throws IOException, ReflectiveOperationException {
            require(
                config == api.config && directory.equals(
                    mc.mcDataDir.toPath()
                        .toRealPath())
                    && pack.equals(api.packName()),
                "Original client shader context changed; snapshot retained");
            api.verifyEmptyQueue();
            settings.verify();
            options.verify();
        }

        void remember() throws IOException {
            settings.remember();
            options.remember();
        }

        void restore(Api api, Minecraft mc) throws IOException, ReflectiveOperationException {
            verify(api, mc);
            settings.restore();
            options.restore();
            try {
                api.iris.getMethod("reload")
                    .invoke(null);
            } finally {
                remember();
            }
            require(
                api.enabled() == enabled && api.packName()
                    .equals(pack) && (mc.theWorld == null || api.renderingMatches(enabled)),
                "Native original shader pipeline restoration remains incomplete");
            // Native loading may rewrite option-file formatting; restore the exact original bytes after loading.
            settings.restore();
            options.restore();
            require(
                Arrays.equals(settings.original, settings.read()) && Arrays.equals(options.original, options.read()),
                "Original shader configuration bytes differ");
        }

        JsonObject summary() {
            var result = new JsonObject();
            result.addProperty("enabled", enabled);
            result.addProperty("pack", pack);
            result.addProperty("settingsSha256", hash(settings.original));
            result.addProperty("optionsOriginallyPresent", options.original != null);
            result.addProperty("optionsSha256", hash(options.original));
            return result;
        }
    }

    private static final class SavedFile {

        final Path path, directory;
        final byte[] original;
        byte[] expected;

        SavedFile(Path path, Path directory) throws IOException {
            this.path = path;
            this.directory = directory;
            original = read();
            expected = original;
        }

        byte[] read() throws IOException {
            require(
                path.toAbsolutePath()
                    .normalize()
                    .startsWith(directory)
                    && path.getParent()
                        .toRealPath()
                        .startsWith(directory),
                "Shader file left the original client directory");
            if (!Files.exists(path)) return null;
            require(
                Files.isRegularFile(path) && path.toRealPath()
                    .startsWith(directory),
                "Unknown shader-file successor retained");
            return Files.readAllBytes(path);
        }

        void verify() throws IOException {
            require(
                Arrays.equals(expected, read()),
                "Shader configuration changed outside this input sequence; retained");
        }

        void remember() throws IOException {
            expected = read();
        }

        void restore() throws IOException {
            verify();
            if (original == null) {
                Files.deleteIfExists(path);
                expected = null;
            } else {
                var temporary = Files.createTempFile(path.getParent(), "tlmnh-shader-restore-", ".tmp");
                try {
                    Files.write(temporary, original);
                    verify();
                    Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    expected = original;
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
            verify();
        }
    }

    private static String hash(byte[] value) {
        if (value == null) return "";
        try {
            var result = new StringBuilder();
            for (byte part : MessageDigest.getInstance("SHA-256")
                .digest(value)) result.append(String.format("%02x", part & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}
