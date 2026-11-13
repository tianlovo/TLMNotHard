package com.tianluoqaq.tlmnh.qa;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.zip.ZipFile;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.common.development.PackagedClassResourceResolver;

import cpw.mods.fml.common.asm.transformers.deobf.FMLDeobfuscatingRemapper;

/** QA物理提供方身份。原CodeSource另存，跨runtime只比较相对来源和固定映射 */
final class PaidWearProviderIdentity {
    private static final String FORGE = "00d1ca02192c7efb87da95552ebf247021f1e1d642f86d0a03076314def11529";
    private static final String MINECRAFT = "c70870f00c4024d829e154f7e5f4e885b02dd87991726a3308d81f513972f3fc";
    private static final String MAPPING = "deobfuscation_data-1.7.10.lzma";
    private static final String MAPPING_SHA = "dbc4ace5029244e5bfa70422d637848c8b932f459e94ec503fdafc5e5f7d5aa2";
    private static final String[] VANILLA = {
        "net.minecraft.item.ItemStack", "net.minecraft.item.Item", "net.minecraft.item.ItemSword", "net.minecraft.item.ItemBow" };
    private static final String[] NOTCH = { "add", "adb", "aeh", "abm" };
    private static final String[] PHYSICAL_SHA = {
        "7789e04746f7d22926a1cb0d759e09a1aede83dc6e66ae1adb8b1a8efecb2109",
        "b6ffd27ff281b90e99da5d97011c46ea4155df3ecfeb2ceb2c4bfe56abc79a1b",
        "8f86b282132f76669367bb80402a7a2b4117631ee8d0dca580a9eff2bef65c4b",
        "7c5b050294fa7c5d60bbc6d35c8bfbd49abd7d670f56a318c87088ed1f82d7dc" };

    private PaidWearProviderIdentity() {}

    static JsonObject capture(Class<?> type) throws Exception {
        return capture(type, EffectEmptyWorldFixture.current());
    }

    static JsonObject preworld(EffectEmptyWorldFixture fixture) throws Exception {
        require(FormalMethodAuthority.requested() && PaidWearApplication.requested(), "Explicit formal paid-wear source proof required");
        JsonObject sources = new JsonObject();
        for (String name : new String[] {
            "net.minecraft.item.ItemStack", "net.minecraft.item.Item", "net.minecraft.item.ItemSword", "net.minecraft.item.ItemBow",
            "mods.battlegear2.items.ItemShield", "com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod",
            "com.llamalad7.mixinextras.injector.wrapoperation.Operation" }) {
            Class<?> type = Class.forName(name, false, PaidWearProviderIdentity.class.getClassLoader());
            sources.add(name, capture(type, fixture));
        }
        require(PaidWearApplication.count() == 1, "Actual early paid-wear application differs");
        JsonObject result = new JsonObject();
        result.addProperty("schema", "tlmnh-native-paid-wear-provider-set-v3");
        result.add("sources", sources);
        result.addProperty("logicalSeedUsed", false);
        result.addProperty("preWorld", true);
        return result;
    }

    private static JsonObject capture(Class<?> type, EffectEmptyWorldFixture fixture) throws Exception {
        JsonObject certified = fixture.certifyPaidWearProvider(type);
        JsonObject actual = certified.getAsJsonObject("actualSource");
        String name = type.getName(), logical = name.replace('.', '/');
        int vanilla = -1;
        for (int i = 0; i < VANILLA.length; i++) if (VANILLA[i].equals(name)) vanilla = i;
        require(vanilla >= 0 || name.equals("mods.battlegear2.items.ItemShield")
            || name.equals("com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod")
            || name.equals("com.llamalad7.mixinextras.injector.wrapoperation.Operation"), "Unknown paid-wear provider Class");
        String physical = logical, physicalEntry;
        Path artifact = Paths.get(text(actual, "codeSource"));
        JsonObject mapping = new JsonObject(), mappingActual = null;
        if (vanilla >= 0) {
            require(text(actual, "sourceSha256").equals(MINECRAFT)
                && text(certified, "relativeArtifact").equals("minecraft_server.1.7.10.jar"),
                "Actual Minecraft paid-wear source differs");
            JsonObject forge = fixture.certifyPaidWearProvider(FMLDeobfuscatingRemapper.class);
            mappingActual = forge.getAsJsonObject("actualSource");
            require(text(mappingActual, "sourceSha256").equals(FORGE)
                && text(forge, "relativeArtifact").equals("forge-1.7.10-10.13.4.1614-1.7.10-universal.jar"),
                "Actual FML mapping provider differs");
            try (ZipFile archive = new ZipFile(Paths.get(text(mappingActual, "codeSource")).toFile())) {
                require(hash(read(archive, MAPPING)).equals(MAPPING_SHA), "Fixed Forge CL mapping bytes changed");
            }
            physical = FMLDeobfuscatingRemapper.INSTANCE.unmap(logical);
            require(physical.equals(NOTCH[vanilla]), "Actual FML unmap differs from fixed Forge CL relation");
            mapping.addProperty("kind", "FML_UNMAP_FIXED_FORGE_CL");
            mapping.addProperty("logicalInternalName", logical);
            mapping.addProperty("unmappedInternalName", physical);
            mapping.addProperty("forgeArtifact", text(forge, "relativeArtifact"));
            mapping.addProperty("forgeArtifactSha256", FORGE);
            mapping.addProperty("mappingEntry", MAPPING);
            mapping.addProperty("mappingEntrySha256", MAPPING_SHA);
            physicalEntry = physical + ".class";
        } else {
            var direct = PackagedClassResourceResolver.resolve(type, artifact);
            physicalEntry = direct.physicalEntry;
            mapping.addProperty("kind", "DIRECT_RESOURCE_URL");
            mapping.addProperty("multiReleaseVersion", direct.multiReleaseVersion);
        }
        byte[] bytes;
        try (ZipFile archive = new ZipFile(artifact.toFile())) {
            bytes = read(archive, physicalEntry);
        }
        String binaryName = className(bytes), digest = hash(bytes);
        require(binaryName.equals(physical), "Physical paid-wear this_class differs");
        if (vanilla >= 0) require(digest.equals(PHYSICAL_SHA[vanilla]), "Physical Minecraft paid-wear class bytes changed");
        JsonObject portable = new JsonObject();
        portable.addProperty("schema", "tlmnh-paid-wear-portable-provider-v3");
        portable.addProperty("class", name);
        portable.addProperty("artifact", text(certified, "relativeArtifact"));
        portable.addProperty("artifactSha256", text(actual, "sourceSha256"));
        portable.addProperty("logicalEntry", logical + ".class");
        portable.addProperty("physicalEntry", physicalEntry);
        portable.addProperty("physicalClassThisName", binaryName);
        portable.addProperty("classEntrySha256", digest);
        portable.add("mapping", mapping);
        portable.addProperty("transformedDefinitionBytesVerified", false);
        JsonObject result = new JsonObject();
        result.addProperty("schema", "tlmnh-native-paid-wear-provider-source-v3");
        result.add("portable", portable);
        result.add("actualSource", actual);
        result.add("actualMappingSource", mappingActual == null ? JsonNull.INSTANCE : mappingActual);
        return result;
    }

    private static byte[] read(ZipFile archive, String name) throws Exception {
        var entry = archive.getEntry(name);
        require(entry != null && !entry.isDirectory(), "Exact physical paid-wear resource missing");
        try (InputStream input = archive.getInputStream(entry); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int size;
            while ((size = input.read(buffer)) != -1) output.write(buffer, 0, size);
            return output.toByteArray();
        }
    }

    private static String className(byte[] bytes) throws Exception {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            require(input.readInt() == 0xcafebabe, "Physical paid-wear class magic differs");
            input.readUnsignedShort();
            input.readUnsignedShort();
            int count = input.readUnsignedShort();
            String[] utf = new String[count];
            int[] names = new int[count];
            for (int i = 1; i < count; i++) {
                switch (input.readUnsignedByte()) {
                    case 1: utf[i] = input.readUTF(); break;
                    case 7: names[i] = input.readUnsignedShort(); break;
                    case 3: case 4: input.readInt(); break;
                    case 5: case 6: input.readLong(); i++; break;
                    case 8: case 16: case 19: case 20: input.readUnsignedShort(); break;
                    case 9: case 10: case 11: case 12: case 17: case 18: input.readInt(); break;
                    case 15: input.readUnsignedByte(); input.readUnsignedShort(); break;
                    default: throw new IllegalStateException("Unknown physical class constant-pool tag");
                }
            }
            input.readUnsignedShort();
            int self = input.readUnsignedShort();
            require(self > 0 && self < count && names[self] > 0 && names[self] < count
                && utf[names[self]] != null, "Physical class this_class is invalid");
            return utf[names[self]];
        }
    }

    private static String hash(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) result.append(String.format("%02x", value & 255));
        return result.toString();
    }

    private static String text(JsonObject value, String field) {
        return value.get(field).getAsString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
