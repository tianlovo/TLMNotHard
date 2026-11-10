package com.tianluoqaq.tlmnh.qa;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

import net.minecraft.server.dedicated.DedicatedPlayerList;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.dedicated.PropertyManager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.common.development.PackagedClassResourceResolver;

/** 仅认证原输入或原生重存字节；不写实际配置，不容许键值变化 */
final class NativeServerPropertiesInput {

    private static final String COMMENT = "Minecraft server properties";
    private static final String MC_SHA = "c70870f00c4024d829e154f7e5f4e885b02dd87991726a3308d81f513972f3fc";
    private static final String JDK_PROPERTIES_SHA = "0363b7cffac8ebaf8f634ad6abb2dc33b7a5a5300d1ed5910363549d4793388b";
    private static final String[] CONTROLLED = { "level-name", "level-seed", "level-type", "generator-settings",
        "server-ip", "server-port", "online-mode", "enable-rcon", "enable-query" };
    private static final Pattern DATE = Pattern
        .compile("^([A-Z][a-z]{2} [A-Z][a-z]{2} [0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}) ([^\\s]+) ([0-9]{4})$");

    private NativeServerPropertiesInput() {}

    static JsonObject verify(Path root, Path output, Path path, JsonObject declaration, String expectedSha,
        String observedSha, long registrationMillis, long aboutMillis) throws Exception {
        JsonObject input = declaration.getAsJsonObject("serverPropertiesInput");
        require(
            input != null && "native-server-properties-input-v1".equals(text(input, "schema")),
            "Missing exact prelaunch server.properties input");
        byte[] before = Base64.getDecoder()
            .decode(text(input, "beforeBase64"));
        byte[] after = Files.readAllBytes(path);
        String beforeSha = sha(before), afterSha = sha(after);
        require(
            expectedSha.equals(beforeSha) && expectedSha.equals(text(input, "beforeSha256"))
                && before.length == input.get("beforeLength")
                    .getAsInt(),
            "Prelaunch server.properties bytes do not match the frozen file SHA");
        require(observedSha.equals(afterSha), "server.properties changed while being authenticated");
        Files.write(output.resolve("server-properties-before.bin"), before, StandardOpenOption.CREATE_NEW);
        Files.write(output.resolve("server-properties-observed.bin"), after, StandardOpenOption.CREATE_NEW);
        Properties original = load(before);
        verifyControls(original, declaration);
        JsonObject proof = new JsonObject();
        proof.addProperty("schema", "native-server-properties-proof-v1");
        proof.addProperty("beforeSha256", beforeSha);
        proof.addProperty("afterSha256", afterSha);
        proof.addProperty("beforeLength", before.length);
        proof.addProperty("afterLength", after.length);
        proof.addProperty("beforeFile", "server-properties-before.bin");
        proof.addProperty("afterFile", "server-properties-observed.bin");
        proof.addProperty("propertyCount", original.size());
        proof.add("controlledProperties", declaration.getAsJsonObject("serverPropertiesDifferences"));
        proof.addProperty("transformedDefinitionBytesVerified", false);
        if (Arrays.equals(before, after)) {
            proof.addProperty("representation", "EXACT_PRELAUNCH_BYTES");
            proof.addProperty("authenticated", true);
            return proof;
        }

        proof.add("nativeWriterSources", nativeSources(root, declaration));
        Path java = Paths.get(System.getProperty("java.home"), "bin", "java.exe")
            .toRealPath();
        require(
            java.equals(
                Paths.get(text(declaration, "java"))
                    .toRealPath())
                && sha(Files.readAllBytes(java)).equals(text(declaration, "javaSha256")),
            "Actual native properties JVM differs from the frozen executable");
        require(
            Properties.class.getClassLoader() == null && Properties.class.getResource("Properties.class") != null
                && "jrt".equals(
                    Properties.class.getResource("Properties.class")
                        .getProtocol()),
            "Native Properties is not the reviewed bootstrap class");
        String jdkHash;
        try (InputStream resource = Properties.class.getResourceAsStream("Properties.class")) {
            require(resource != null, "Native Properties resource missing");
            jdkHash = sha(read(resource));
        }
        require(JDK_PROPERTIES_SHA.equals(jdkHash), "Native JDK Properties implementation changed");
        require(original.equals(load(after)), "server.properties has added, missing or changed key/value input");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        original.store(bytes, COMMENT);
        Header canonical = withoutDate(bytes.toByteArray()), observed = withoutDate(after);
        require(
            Arrays.equals(canonical.body, observed.body),
            "server.properties is not the exact native store representation of the frozen before bytes");
        long jvmStart = ManagementFactory.getRuntimeMXBean()
            .getStartTime();
        require(
            registrationMillis >= jvmStart && aboutMillis >= registrationMillis,
            "Native properties observation window is invalid");
        long dateMillis = nativeDateMillis(observed.date);
        require(
            dateMillis / 1000 >= registrationMillis / 1000 && dateMillis / 1000 <= aboutMillis / 1000,
            "Native properties Date header is outside this registration-to-About window");
        proof.addProperty("representation", "NATIVE_LOAD_STORE_CANONICAL");
        proof.addProperty("onlyVariableHeaderLine", 2);
        proof.addProperty("dateHeader", observed.date);
        proof.addProperty("nativeDateMillis", dateMillis);
        proof.addProperty("datePrecision", "whole seconds as emitted by Date.toString");
        proof.addProperty("jvmStartMillis", jvmStart);
        proof.addProperty("registrationMillis", registrationMillis);
        proof.addProperty("aboutToStartMillis", aboutMillis);
        proof.addProperty(
            "nativeTimeZone",
            TimeZone.getDefault()
                .getID());
        proof.addProperty("jdkPropertiesClassSha256", jdkHash);
        proof.addProperty("javaExecutableSha256", text(declaration, "javaSha256"));
        proof.addProperty("allKeysAndValuesUnchanged", true);
        proof.addProperty("fullCanonicalBodyMatched", true);
        proof.addProperty("authenticated", true);
        return proof;
    }

    private static void verifyControls(Properties before, JsonObject declaration) {
        JsonObject changes = declaration.getAsJsonObject("serverPropertiesDifferences");
        Set<String> names = new HashSet<>();
        for (String key : CONTROLLED) names.add(key);
        require(
            changes != null && changes.entrySet()
                .size() == names.size(),
            "Wrong controlled property set");
        for (var row : changes.entrySet()) require(names.contains(row.getKey()), "Unknown controlled property");
        for (String key : CONTROLLED)
            require(text(changes, key).equals(before.getProperty(key)), "Controlled property changed: " + key);
        require(
            text(changes, "level-name").equals(text(declaration, "worldName"))
                && text(changes, "level-seed").equals(EffectEmptyWorldFixture.WORLD_SEED)
                && text(changes, "level-seed").equals(text(declaration, "worldSeed"))
                && text(changes, "level-type").equals("FLAT")
                && text(changes, "generator-settings").equals("2;7,2x3,2;1;")
                && text(changes, "server-ip").equals("127.0.0.1")
                && text(changes, "online-mode").equals("false")
                && text(changes, "enable-rcon").equals("false")
                && text(changes, "enable-query").equals("false"),
            "Controlled property declaration differs from the original method");
        String port = text(changes, "server-port");
        int value = Integer.parseInt(port);
        require(
            value > 0 && value <= 65535
                && Integer.toString(value)
                    .equals(port),
            "Invalid exact prepared port");
    }

    private static JsonArray nativeSources(Path root, JsonObject declaration) throws Exception {
        Class<?>[] owners = { PropertyManager.class, DedicatedServer.class, DedicatedPlayerList.class };
        String[] entries = { "lq.class", "lt.class", "ls.class" };
        String[] hashes = { "27ac97609be408f3e8df83de5ed5bbc9d149e9d11a70733affbcaa7a224b5ccc",
            "ea8cd01950c042c11ffb3441395797b8548d0c6f6759cdcff681443a20e96754",
            "2c2a395405c90f5804f04b17f7e550724562bc68c23ee99428b5e9268191b767" };
        JsonArray sources = new JsonArray();
        for (int i = 0; i < owners.length; i++) {
            Class<?> type = owners[i];
            Path artifact = PackagedClassResourceResolver.artifactOf(type)
                .toRealPath();
            require(artifact.startsWith(root.toRealPath()) && Files.isRegularFile(artifact), "Unowned native writer");
            String relative = root.toRealPath()
                .relativize(artifact)
                .toString()
                .replace('\\', '/');
            JsonObject files = declaration.getAsJsonObject("files");
            require(
                files.has(relative) && MC_SHA.equals(text(files, relative))
                    && MC_SHA.equals(sha(Files.readAllBytes(artifact))),
                "Unfrozen native property writer artifact");
            try (ZipFile zip = new ZipFile(artifact.toFile())) {
                var entry = zip.getEntry(entries[i]);
                require(entry != null && !entry.isDirectory(), "Native property writer entry missing");
                try (InputStream input = zip.getInputStream(entry)) {
                    require(hashes[i].equals(sha(read(input))), "Native property writer physical class changed");
                }
            }
            JsonObject source = new JsonObject();
            source.addProperty("class", type.getName());
            source.addProperty("relativeArtifact", relative);
            source.addProperty("artifactSha256", MC_SHA);
            source.addProperty("physicalEntry", entries[i]);
            source.addProperty("physicalClassSha256", hashes[i]);
            source.addProperty(
                "actualCodeSourceUrl",
                type.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toExternalForm());
            source.addProperty("transformedDefinitionBytesVerified", false);
            sources.add(source);
        }
        return sources;
    }

    private static Header withoutDate(byte[] data) {
        byte[] separator = System.lineSeparator()
            .getBytes(StandardCharsets.ISO_8859_1);
        byte[] prefix = ("#" + COMMENT + System.lineSeparator()).getBytes(StandardCharsets.ISO_8859_1);
        require(
            data.length > prefix.length && Arrays.equals(prefix, Arrays.copyOf(data, prefix.length))
                && data[prefix.length] == '#',
            "Wrong native properties header prefix or line position");
        int end = -1;
        for (int i = prefix.length + 1; i <= data.length - separator.length; i++) {
            boolean found = true;
            for (int j = 0; j < separator.length; j++) found &= data[i + j] == separator[j];
            if (found) {
                end = i;
                break;
            }
        }
        require(end >= 0, "Missing native Date header terminator");
        String date = new String(data, prefix.length + 1, end - prefix.length - 1, StandardCharsets.ISO_8859_1);
        require(
            DATE.matcher(date)
                .matches(),
            "Malformed native Date header");
        byte[] body = new byte[prefix.length + data.length - end - separator.length];
        System.arraycopy(prefix, 0, body, 0, prefix.length);
        System.arraycopy(data, end + separator.length, body, prefix.length, data.length - end - separator.length);
        return new Header(date, body);
    }

    private static long nativeDateMillis(String value) {
        Matcher match = DATE.matcher(value);
        require(match.matches(), "Malformed native Date text");
        String local = match.group(1) + " " + match.group(3);
        SimpleDateFormat format = new SimpleDateFormat("EEE MMM dd HH:mm:ss yyyy", Locale.ENGLISH);
        format.setLenient(false);
        format.setTimeZone(TimeZone.getDefault());
        ParsePosition position = new ParsePosition(0);
        Date parsed = format.parse(local, position);
        require(
            parsed != null && position.getIndex() == local.length()
                && parsed.toString()
                    .equals(value),
            "Date header is not generated by this JVM's native Date.toString timezone/format");
        return parsed.getTime();
    }

    private static Properties load(byte[] bytes) throws Exception {
        Properties result = new Properties();
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
            result.load(input);
        }
        return result;
    }

    private static byte[] read(InputStream input) throws Exception {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[65536];
        for (int size; (size = input.read(buffer)) != -1;) result.write(buffer, 0, size);
        return result.toByteArray();
    }

    private static String sha(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256")
            .digest(bytes)) {
            result.append(Character.forDigit((value & 255) >>> 4, 16));
            result.append(Character.forDigit(value & 15, 16));
        }
        return result.toString();
    }

    private static String text(JsonObject object, String key) {
        return object.get(key)
            .getAsString();
    }

    private static void require(boolean allowed, String reason) {
        if (!allowed) throw new IllegalStateException(reason);
    }

    private static final class Header {

        final String date;
        final byte[] body;

        Header(String date, byte[] body) {
            this.date = date;
            this.body = body;
        }
    }
}
