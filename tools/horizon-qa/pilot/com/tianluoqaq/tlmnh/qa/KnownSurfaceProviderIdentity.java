package com.tianluoqaq.tlmnh.qa;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldProviderSurface;

import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.common.development.PackagedClassResourceResolver;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;

/** 两个专用QA场景的已知地表provider来源凭据，不修改provider、注册表或世界 */
final class KnownSurfaceProviderIdentity {

    static final String BOP_CLASS = "biomesoplenty.common.world.WorldProviderSurfaceBOP";
    static final String BOP_JAR_SHA256 = "5382d1c156c871c896b6cebd036b80f58ceaff36b09d416b113f32884b7b4a2a";
    static final String BOP_CLASS_SHA256 = "d25190922dffb0cb6d24029200d83da3981c112910dcf1dd41c6d71ad72cbcbb";

    private KnownSurfaceProviderIdentity() {}

    static JsonObject capture(WorldProvider provider, Path runtime, JsonObject files) throws Exception {
        require(provider != null && provider.dimensionId == 0, "Actual dim0 provider required");
        Class<?> type = provider.getClass();
        boolean vanilla = type == WorldProviderSurface.class;
        boolean bop = BOP_CLASS.equals(type.getName()) && type.getSuperclass() == WorldProviderSurface.class;
        require(vanilla || bop, "Unreviewed surface provider: " + type.getName());
        JsonObject result = new JsonObject();
        result.addProperty("schema", "known-surface-provider-source-v1");
        result.addProperty("providerClass", type.getName());
        result.addProperty("providerIdentity", System.identityHashCode(provider));
        result.addProperty("dimension", provider.dimensionId);
        result.addProperty("admission", vanilla ? "EXACT_NATIVE_SURFACE" : "EXACT_REVIEWED_BOP_SURFACE");
        result.addProperty("transformedDefinitionBytesVerified", false);
        Path source = PackagedClassResourceResolver.artifactOf(type);
        JsonObject sourceIdentity = artifact(type, source, runtime, files);
        result.add("providerSource", sourceIdentity);
        result.add(
            "nativeSurfaceSource",
            artifact(
                WorldProviderSurface.class,
                PackagedClassResourceResolver.artifactOf(WorldProviderSurface.class),
                runtime,
                files));
        if (bop) {
            require(
                BOP_JAR_SHA256.equals(
                    sourceIdentity.get("sha256")
                        .getAsString()),
                "Reviewed BOP artifact changed");
            var binding = PackagedClassResourceResolver.resolve(type, source);
            require(BOP_CLASS_SHA256.equals(binding.sha256), "Reviewed BOP provider class resource changed");
            require(
                binding.logicalEntry.equals(binding.physicalEntry) && binding.multiReleaseVersion == 0,
                "Unexpected BOP physical class binding");
            ModContainer container = Loader.instance()
                .getIndexedModList()
                .get("BiomesOPlenty");
            require(
                container != null && "cpw.mods.fml.common.FMLModContainer".equals(
                    container.getClass()
                        .getName())
                    && "BiomesOPlenty".equals(container.getModId())
                    && container.getSource() != null
                    && container.getSource()
                        .toPath()
                        .toRealPath()
                        .equals(source.toRealPath())
                    && container.getMod() != null
                    && "biomesoplenty.BiomesOPlenty".equals(
                        container.getMod()
                            .getClass()
                            .getName())
                    && PackagedClassResourceResolver.artifactOf(
                        container.getMod()
                            .getClass())
                        .equals(source.toRealPath()),
                "Actual BOP Loader container/source identity mismatch");
            result.addProperty("loaderModId", container.getModId());
            result.addProperty("loaderVersion", container.getVersion());
            result.addProperty(
                "loaderContainerClass",
                container.getClass()
                    .getName());
            result.addProperty("providerClassResourceSha256", binding.sha256);
            result.addProperty("providerPhysicalEntry", binding.physicalEntry);
            result.addProperty(
                "sourceAuthority",
                "Actual indexed FML ModContainer, provider CodeSource and physical resource match the frozen files");
            result.addProperty(
                "reviewScope",
                "BOP provider overrides spawn suitability only; native Surface and actual world-type generator remain separately checked");
        }
        result.addProperty("authenticated", true);
        return result;
    }

    private static JsonObject artifact(Class<?> type, Path source, Path runtime, JsonObject files) throws Exception {
        Path root = runtime.toRealPath(), actual = source.toRealPath();
        require(actual.startsWith(root) && Files.isRegularFile(actual), "Unowned packaged provider source");
        String relative = root.relativize(actual)
            .toString()
            .replace('\\', '/');
        String digest = sha(actual);
        require(
            files != null && files.has(relative)
                && digest.equals(
                    files.get(relative)
                        .getAsString()),
            "Provider source differs from the frozen launch files: " + relative);
        JsonObject result = new JsonObject();
        result.addProperty("class", type.getName());
        result.addProperty("relativeArtifact", relative);
        result.addProperty("sha256", digest);
        result.addProperty(
            "codeSourceUrl",
            type.getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toExternalForm());
        result.addProperty(
            "codeSourceProtocol",
            type.getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .getProtocol());
        result.addProperty("transformedDefinitionBytesVerified", false);
        return result;
    }

    private static String sha(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536];
            for (int size; (size = input.read(buffer)) != -1;) digest.update(buffer, 0, size);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format("%02x", value & 255));
        return result.toString();
    }

    private static void require(boolean allowed, String reason) {
        if (!allowed) throw new IllegalStateException(reason);
    }
}
