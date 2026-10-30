package com.tianluoqaq.tlmnh.geometry.identity;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 独立的首次类定义观察器，不更改既有动画 agent 的选定集合或任何类字节 */
public final class GeometryDefinitionAgent implements ClassFileTransformer {
    private static final Map<ClassLoader, Map<String, Definition>> DEFINITIONS = new IdentityHashMap<>();
    private static Set<String> selected = Set.of();
    private static String manifestHash, agentHash;

    public static void premain(String argument, Instrumentation instrumentation) throws Exception {
        if (argument == null || !argument.matches("[A-Za-z0-9_.$,]+")) throw new IllegalArgumentException("Geometry classes required");
        selected = Arrays.stream(argument.split(",")).collect(Collectors.toUnmodifiableSet());
        manifestHash = sha(Files.readAllBytes(Path.of(System.getProperty("tlmnh.geometry.buildIdentity"))));
        agentHash = sha(Files.readAllBytes(Path.of(GeometryDefinitionAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI())));
        instrumentation.addTransformer(new GeometryDefinitionAgent(), false);
    }

    public static Map<String, String> startup() {
        if (manifestHash == null) throw new IllegalStateException("Geometry premain is inactive");
        return Map.of("manifestSha256", manifestHash, "agentSha256", agentHash);
    }

    public static Map<String, Object> identity(Class<?> type) {
        synchronized (DEFINITIONS) {
            var byLoader = DEFINITIONS.get(type.getClassLoader());
            var value = byLoader == null ? null : byLoader.get(type.getName());
            if (value == null || value.count != 1 || value.redefined) throw new IllegalStateException("Missing/repeated geometry definition: " + type.getName());
            return Map.of("sha256", value.hash, "count", value.count,
                "stage", "premain-transformer-input-after-defining-loader-transformations",
                "loaderClass", type.getClassLoader() == null ? "bootstrap" : type.getClassLoader().getClass().getName());
        }
    }

    @Override
    public byte[] transform(ClassLoader loader, String internalName, Class<?> redefined, ProtectionDomain domain, byte[] bytes) {
        if (internalName == null || !selected.contains(internalName.replace('/', '.'))) return null;
        synchronized (DEFINITIONS) {
            var classes = DEFINITIONS.computeIfAbsent(loader, ignored -> new HashMap<>());
            String name = internalName.replace('/', '.');
            var previous = classes.get(name);
            classes.put(name, new Definition("UNAVAILABLE", previous == null ? 1 : previous.count + 1, true));
            try {
                var invalid = classes.get(name);
                classes.put(name, new Definition(sha(bytes), invalid.count, redefined != null));
            } catch (Throwable failure) { /* identity() will reject the unavailable definition */ }
        }
        return null;
    }

    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private record Definition(String hash, int count, boolean redefined) {}
}
