package com.tianluoqaq.tlmnh.reference.identitytest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/** 在单独Java17进程里通过真实premain验证观测、启动绑定和缺失拒绝，不启动Minecraft */
public final class ReferenceIdentityAgentFixture {

    public static void main(String[] arguments) throws Exception {
        Class<?> agent = Class.forName("com.tianluoqaq.tlmnh.reference.identity.ReferenceIdentityAgent", false,
            ClassLoader.getSystemClassLoader());
        @SuppressWarnings("unchecked")
        var actual = (Map<String, Object>) agent.getMethod("identity", Class.class).invoke(null, ReferenceIdentityAgentFixture.class);
        String name = '/' + ReferenceIdentityAgentFixture.class.getName().replace('.', '/') + ".class";
        byte[] resource;
        try (var input = ReferenceIdentityAgentFixture.class.getResourceAsStream(name)) {
            resource = input.readAllBytes();
        }
        require(hash(resource).equals(actual.get("definitionSha256")), "observer changed or failed to record the actual fixture definition");
        require(((Number) actual.get("definitionCount")).intValue() == 1, "fixture definition count");
        @SuppressWarnings("unchecked")
        var startup = (Map<String, Object>) agent.getMethod("startup").invoke(null);
        require(hash(Files.readAllBytes(Path.of(System.getProperty("tlmnh.reference.buildIdentity")))).equals(startup.get("buildIdentitySha256")),
            "startup manifest binding");
        boolean rejected = false;
        try {
            agent.getMethod("identity", Class.class).invoke(null, String.class);
        } catch (java.lang.reflect.InvocationTargetException expected) {
            rejected = expected.getCause() instanceof IllegalStateException;
        }
        require(rejected, "missing definition must be rejected");
        System.out.println("TLMNH_REFERENCE_IDENTITY_AGENT_TEST_PASS define=observed resource=equal manifest=startup-bound missing=rejected");
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}
