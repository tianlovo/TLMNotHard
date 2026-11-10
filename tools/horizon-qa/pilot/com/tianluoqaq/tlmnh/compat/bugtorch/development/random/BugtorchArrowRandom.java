package com.tianluoqaq.tlmnh.compat.bugtorch.development.random;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Random;
import java.util.jar.JarFile;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tianluoqaq.tlmnh.common.development.PackagedClassResourceResolver;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.relauncher.Side;

/** QA-only finite source attestation of an already existing arrow RNG. Never creates, draws or seeds an RNG. */
public final class BugtorchArrowRandom {

    public static final String REVISION = "v6-native-arrow-rng-bugtorch-1";
    public static final String DESIGN = "8cbc49086e1b0f0e9ffc46d442e90f47168c51825589b866e9aa00fa94c12968";
    public static final String LEDGER = "4214b3e885e1a7f96c94d68031bbd84adbf53c75020e954f45fa726070ff113a";
    private static final String GUARD = "bugtorch/development-arrow-random";
    private static final String CLASS = "jss.util.RandomXoshiro256StarStar";
    private static final String ARTIFACT = "mods/bugtorch-1.3.1.jar";
    private static final String JAR_SHA = "90f0f47b165ef8966338fd3699bfb310d307534d0662d15f5f315f59e73b8d47";
    private static final String ENTRY = "jss/util/RandomXoshiro256StarStar.class";
    private static final String ENTRY_SHA = "5d3fb00c2b27aafd449bcdf537280050bdb041b417257e0157e93e1552014573";
    private static final String MIXIN = "jss/bugtorch/mixins/early/minecraft/fastrandom/MixinEntity.class";
    private static final String MIXIN_SHA = "05bf6d13000be8cc8b4567823a336d5848fe91d59cddbd9487294f6e659bbb45";
    private static final String JDK_SHA = "f075f8359202194be2b39fc03afea0a0ed486f8485348afefe14bc05a61ca349";
    private static final Map<Class<?>, JsonObject> IDENTITIES = new IdentityHashMap<>();
    private static Class<?> nativeClass;
    private static String manifestProperty, manifestShaProperty;

    private BugtorchArrowRandom() {}

    /** Pure compiled method identity; does not resolve any optional provider. */
    public static JsonObject revision() {
        JsonObject row = new JsonObject();
        row.addProperty("revision", REVISION);
        row.addProperty("designSha256", DESIGN);
        row.addProperty("sourceLedgerSha256", LEDGER);
        return row;
    }

    public static synchronized JsonObject observations() {
        JsonObject row = revision();
        JsonArray providers = new JsonArray();
        for (JsonObject identity : IDENTITIES.values()) providers.add(identity);
        row.add("actualProviders", providers);
        row.addProperty("transformedDefinitionBytesVerified", false);
        return row;
    }

    public static synchronized JsonObject attest(Random value, ClassLoader gameLoader) {
        if (!IntegrationGuard.available(Side.SERVER, GUARD))
            throw new IllegalStateException("Native arrow RNG source capability remains protected");
        try {
            require(value != null, "Native arrow RNG absent");
            Class<?> type = value.getClass();
            if (type == Random.class) {
                JsonObject known = IDENTITIES.get(type);
                if (known != null) return known;
                JsonObject row = jdkIdentity();
                row.addProperty("providerId", "JAVA_UTIL_RANDOM_JDK25");
                row.addProperty("classSha256", JDK_SHA);
                row.addProperty("seedParameterBits", 64);
                row.addProperty("legacySeed48Meaning", "LOW_48_INPUT_PROJECTION_BEFORE_JDK_LCG_SCRAMBLE");
                IDENTITIES.put(type, row);
                return row;
            }
            require(
                type.getName()
                    .equals(CLASS) && type.getSuperclass() == Random.class
                    && !type.isAnonymousClass()
                    && !type.isLocalClass()
                    && !Proxy.isProxyClass(type),
                "Unreviewed native arrow RNG class");
            require(gameLoader != null && type.getClassLoader() == gameLoader, "Native arrow RNG loader differs");
            require(nativeClass == null || nativeClass == type, "Native arrow RNG Class identity changed");
            JsonObject known = IDENTITIES.get(type);
            if (known != null) {
                require(
                    manifestProperty.equals(System.getProperty("tlmnh.qaEffectEmptyWorldManifest", ""))
                        && manifestShaProperty.equals(System.getProperty("tlmnh.qaEffectEmptyWorldManifestSha256", "")),
                    "Native arrow RNG manifest identity changed");
                return known;
            }
            JsonObject row = nativeIdentity(type);
            nativeClass = type;
            IDENTITIES.put(type, row);
            return row;
        } catch (Exception | LinkageError | AssertionError failure) {
            IntegrationGuard.throwIfFatal(failure);
            try {
                IntegrationGuard.report(Side.SERVER, GUARD, failure);
            } catch (Exception | LinkageError | AssertionError reporting) {
                IntegrationGuard.throwIfFatal(reporting);
                if (reporting != failure) failure.addSuppressed(reporting);
            }
            throw new IllegalStateException("Uncertified native arrow RNG source", failure);
        }
    }

    private static JsonObject nativeIdentity(Class<?> type) throws Exception {
        String manifestName = System.getProperty("tlmnh.qaEffectEmptyWorldManifest", "");
        String manifestSha = System.getProperty("tlmnh.qaEffectEmptyWorldManifestSha256", "");
        Path manifest = Paths.get(manifestName)
            .toRealPath();
        require(sha(Files.readAllBytes(manifest)).equals(manifestSha), "Native arrow RNG launch manifest drift");
        JsonObject declaration = new JsonParser()
            .parse(new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8))
            .getAsJsonObject();
        Path root = Paths.get(
            declaration.get("instanceRoot")
                .getAsString())
            .toRealPath();
        require(
            root.equals(
                Paths.get(".")
                    .toRealPath()),
            "Native arrow RNG foreign instance root");
        require(
            JAR_SHA.equals(
                declaration.getAsJsonObject("files")
                    .get(ARTIFACT)
                    .getAsString()),
            "Native arrow RNG manifest artifact differs");
        Path expected = root.resolve(ARTIFACT)
            .toRealPath();
        require(
            expected.equals(
                root.resolve(ARTIFACT)
                    .normalize()),
            "Native arrow RNG artifact alias");
        require(
            PackagedClassResourceResolver.artifactOf(type)
                .equals(expected) && sha(Files.readAllBytes(expected)).equals(JAR_SHA),
            "Native arrow RNG packaged artifact differs");
        PackagedClassResourceResolver.Binding binding = PackagedClassResourceResolver.resolve(type, expected);
        require(
            binding.physicalEntry.equals(ENTRY) && binding.sha256.equals(ENTRY_SHA) && binding.multiReleaseVersion == 0,
            "Native arrow RNG actual class resource differs");
        try (JarFile jar = new JarFile(expected.toFile())) {
            checkedEntry(jar, ENTRY, ENTRY_SHA, 52);
            checkedEntry(jar, MIXIN, MIXIN_SHA, 52);
        }
        JsonObject row = revision();
        row.addProperty("providerId", "BUGTORCH_1_3_1_XOSHIRO256STARSTAR");
        row.addProperty("class", type.getName());
        row.addProperty("directSuperclass", Random.class.getName());
        row.addProperty(
            "loaderClass",
            type.getClassLoader()
                .getClass()
                .getName());
        row.addProperty("loaderIdentity", System.identityHashCode(type.getClassLoader()));
        row.addProperty("artifact", ARTIFACT);
        row.addProperty("artifactSha256", JAR_SHA);
        row.addProperty("physicalEntry", ENTRY);
        row.addProperty("classSha256", ENTRY_SHA);
        row.addProperty("classMajor", 52);
        row.addProperty("mixinPhysicalEntry", MIXIN);
        row.addProperty("mixinClassSha256", MIXIN_SHA);
        row.addProperty("mixinClassLoadedForAttestation", false);
        row.addProperty("resourceMatchesCodeSource", true);
        row.addProperty("seedParameterBits", 64);
        row.addProperty("legacySeed48Meaning", "LOW_48_INPUT_PROJECTION_ONLY");
        JsonArray methods = new JsonArray();
        methods.add(publicMethod(type, "setSeed", void.class, type, false, long.class));
        methods.add(publicMethod(type, "nextGaussian", double.class, Random.class, true));
        methods.add(publicMethod(type, "nextDouble", double.class, type, false));
        methods.add(publicMethod(type, "nextBoolean", boolean.class, type, false));
        methods.add(publicMethod(type, "nextFloat", float.class, type, false));
        methods.add(publicMethod(type, "nextInt", int.class, type, false, int.class));
        methods.add(publicMethod(type, "nextLong", long.class, type, false));
        row.add("publicMethodOwners", methods);
        row.add("jdkRandom", jdkIdentity());
        row.addProperty(
            "nonaliasScope",
            "Original world.rand, explicitly owned maid/target/owner public RNGs and retained receipts");
        row.addProperty("transformedDefinitionBytesVerified", false);
        manifestProperty = manifestName;
        manifestShaProperty = manifestSha;
        return row;
    }

    private static JsonObject jdkIdentity() throws Exception {
        require(
            "25".equals(System.getProperty("java.specification.version")) && Random.class.getClassLoader() == null,
            "Reviewed bootstrap JDK Random runtime differs");
        require(
            Random.class.getResource("/java/util/Random.class") != null
                && "jrt:/java.base/java/util/Random.class".equals(
                    Random.class.getResource("/java/util/Random.class")
                        .toExternalForm()),
            "JDK Random resource differs");
        try (InputStream in = Random.class.getResourceAsStream("/java/util/Random.class")) {
            byte[] bytes = read(in);
            require(sha(bytes).equals(JDK_SHA) && major(bytes) == 69, "Reviewed JDK Random bytes differ");
        }
        JsonObject row = revision();
        row.addProperty("class", Random.class.getName());
        row.addProperty("resource", "jrt:/java.base/java/util/Random.class");
        row.addProperty("resourceSha256", JDK_SHA);
        row.addProperty("classMajor", 69);
        row.add("nextGaussian", publicMethod(Random.class, "nextGaussian", double.class, Random.class, true));
        row.add("setSeed", publicMethod(Random.class, "setSeed", void.class, Random.class, true, long.class));
        row.addProperty("transformedDefinitionBytesVerified", false);
        return row;
    }

    private static JsonObject publicMethod(Class<?> type, String name, Class<?> result, Class<?> owner,
        boolean synchronizedMethod, Class<?>... parameters) throws Exception {
        Method method = type.getMethod(name, parameters);
        int modifiers = method.getModifiers();
        require(
            method.getDeclaringClass() == owner && method.getReturnType() == result
                && Modifier.isPublic(modifiers)
                && !Modifier.isStatic(modifiers)
                && !Modifier.isAbstract(modifiers)
                && (!synchronizedMethod || Modifier.isSynchronized(modifiers)),
            "Reviewed RNG public method owner/descriptor differs: " + name);
        JsonObject row = new JsonObject();
        row.addProperty("name", name);
        row.addProperty(
            "declaringClass",
            method.getDeclaringClass()
                .getName());
        row.addProperty(
            "descriptor",
            MethodType.methodType(result, parameters)
                .toMethodDescriptorString());
        row.addProperty("synchronized", Modifier.isSynchronized(modifiers));
        return row;
    }

    private static void checkedEntry(JarFile jar, String entry, String expected, int expectedMajor) throws Exception {
        require(jar.getJarEntry(entry) != null, "Reviewed RNG physical entry absent");
        try (InputStream in = jar.getInputStream(jar.getJarEntry(entry))) {
            byte[] bytes = read(in);
            require(
                sha(bytes).equals(expected) && major(bytes) == expectedMajor,
                "Reviewed RNG physical entry differs");
        }
    }

    private static int major(byte[] bytes) {
        require(
            bytes.length >= 8 && bytes[0] == (byte) 0xca
                && bytes[1] == (byte) 0xfe
                && bytes[2] == (byte) 0xba
                && bytes[3] == (byte) 0xbe,
            "Invalid RNG class bytes");
        return ((bytes[6] & 255) << 8) | (bytes[7] & 255);
    }

    private static byte[] read(InputStream in) throws Exception {
        require(in != null, "Reviewed RNG resource absent");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] block = new byte[8192];
        int n;
        while ((n = in.read(block)) >= 0) {
            if (n > 0) out.write(block, 0, n);
            require(out.size() <= 2_000_000, "RNG class resource exceeds audit bound");
        }
        return out.toByteArray();
    }

    private static String sha(byte[] bytes) throws Exception {
        StringBuilder value = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256")
            .digest(bytes)) value.append(String.format("%02x", b & 255));
        return value.toString();
    }

    private static void require(boolean okay, String reason) {
        if (!okay) throw new IllegalStateException(reason);
    }
}
