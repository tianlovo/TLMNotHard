package com.tianluoqaq.tlmnh.reference.identity;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 只记录白名单类首次defineClass输入，不返回替换字节，不主动重定义或重转换类 */
public final class ReferenceIdentityAgent implements ClassFileTransformer {

    private static final Map<ClassLoader, Map<String, Definition>> DEFINITIONS = new IdentityHashMap<>();
    private static Set<String> selected = Set.of();
    private static volatile boolean active;
    private static String startupManifestSha256, startupAgentSha256;

    public static void premain(String arguments, Instrumentation instrumentation) {
        if (arguments == null || !arguments.matches("[A-Za-z0-9_.$,]+"))
            throw new IllegalArgumentException("Reference identity class list required");
        selected = Arrays.stream(arguments.split(",")).collect(Collectors.toUnmodifiableSet());
        try {
            startupManifestSha256 = hash(Files.readAllBytes(Path.of(System.getProperty("tlmnh.reference.buildIdentity"))));
            startupAgentSha256 = hash(Files.readAllBytes(Path.of(ReferenceIdentityAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI())));
        } catch (Exception error) {
            throw new IllegalStateException("Reference identity startup files unavailable");
        }
        instrumentation.addTransformer(new ReferenceIdentityAgent(), false);
        active = true;
    }

    public static Map<String, Object> startup() {
        if (!active) throw new IllegalStateException("Reference identity agent is inactive");
        return Map.of("buildIdentitySha256", startupManifestSha256, "agentSha256", startupAgentSha256);
    }

    @Override
    public byte[] transform(ClassLoader loader, String internalName, Class<?> redefined, ProtectionDomain domain,
        byte[] bytes) {
        if (internalName == null || !selected.contains(internalName.replace('/', '.'))) return null;
        synchronized (DEFINITIONS) {
            var classes = DEFINITIONS.computeIfAbsent(loader, ignored -> new HashMap<>());
            String name = internalName.replace('/', '.');
            var previous = classes.get(name);
            int count = previous == null ? 1 : previous.count + 1;
            classes.put(name, new Definition("UNAVAILABLE", count, true));
            try {
                classes.put(name, new Definition(hash(bytes), count, redefined != null));
            } catch (Throwable error) {
                classes.put(name, new Definition("UNAVAILABLE", count, true));
            }
        }
        return null;
    }

    /** 调用者经SystemClassLoader取得本agent，按实际ClassLoader对象隔离同名类 */
    public static Map<String, Object> identity(Class<?> type) {
        synchronized (DEFINITIONS) {
            var classes = DEFINITIONS.get(type.getClassLoader());
            var definition = classes == null ? null : classes.get(type.getName());
            if (!active || definition == null || definition.count != 1 || definition.redefined
                || definition.sha256.equals("UNAVAILABLE"))
                throw new IllegalStateException("Missing or repeated reference class definition: " + type.getName());
            var result = new LinkedHashMap<String, Object>();
            result.put("definitionSha256", definition.sha256);
            result.put("definitionCount", definition.count);
            result.put("loaderClass", type.getClassLoader() == null ? "bootstrap" : type.getClassLoader().getClass().getName());
            result.put("stage", "premain-transformer-input-after-defining-loader-transformations");
            return result;
        }
    }

    private static String hash(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private record Definition(String sha256, int count, boolean redefined) {}
}
