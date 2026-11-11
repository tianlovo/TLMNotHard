package com.tianluoqaq.tlmnh.buildlogic;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.gradle.api.GradleException;

import groovy.json.JsonSlurper;

/** 发布归档的许可证、内置音频、保留资产与非发布类泄露校验 */
public final class ReleaseArchiveChecks {

    private static final List<String> FORBIDDEN_CLASS_SUFFIXES = Arrays
        .asList("Probe", "Fixture", "Fixtures", "ReplayMain", "Test");
    private static final String EXPECTED_MIXIN_CONFIGS = "mixins.tlmnh.json,mixins.tlmnh.ysmu.json";

    private ReleaseArchiveChecks() {}

    public static void validateProjectLicense(byte[] license, byte[] metadata, byte[] notice, String label) {
        String digest = sha256(license);
        if (license.length != 35149
            || !"3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986".equals(digest)) {
            throw new GradleException(
                "Project license [" + label + "] differs from the selected complete GNU GPL version 3 text");
        }
        Map<?, ?> root = (Map<?, ?>) new JsonSlurper().parseText(new String(metadata, StandardCharsets.UTF_8));
        List<?> mods = (List<?>) root.get("modList");
        if (mods == null || mods.size() != 1 || !"GPL-3.0-only".equals(((Map<?, ?>) mods.get(0)).get("license"))) {
            throw new GradleException("Project license [" + label + "] requires the GPL-3.0-only metadata declaration");
        }
        String marker = "TLMNH original code license: GPL-3.0-only";
        long count = new String(notice, StandardCharsets.UTF_8).lines()
            .filter(line -> marker.equals(line.trim()))
            .count();
        if (count != 1L) {
            throw new GradleException("Project license [" + label + "] requires one original-code license notice");
        }
    }

    public static void verifyProjectLicenseJar(File file) {
        try (ZipFile archive = new ZipFile(file)) {
            List<String> required = Arrays
                .asList("META-INF/TLMNH-LICENSE.txt", "mcmod.info", "META-INF/THIRD_PARTY_NOTICES.md");
            List<String> names = zipFileEntries(archive);
            for (String path : required) {
                if (countExact(names, path) != 1) {
                    throw new GradleException(
                        "Project license [" + file.getName() + "] contains a missing or duplicate license declaration");
                }
            }
            validateProjectLicense(
                readEntry(archive, required.get(0)),
                readEntry(archive, required.get(1)),
                readEntry(archive, required.get(2)),
                file.getPath());
        } catch (IOException failure) {
            throw new GradleException("Failed to verify project license JAR: " + file.getName(), failure);
        }
    }

    public static void verifyBuiltinSoundDirectory(File directory) {
        List<String> names = walkRelativeFiles(directory);
        verifyBuiltinSoundResources(path -> readUnchecked(new File(directory, path)), names, directory.getPath());
    }

    public static void verifyBuiltinSoundJar(File file) {
        try (ZipFile archive = new ZipFile(file)) {
            List<String> names = zipFileEntries(archive);
            verifyBuiltinSoundResources(name -> readEntry(archive, name), names, file.getPath());
        } catch (IOException failure) {
            throw new GradleException("Failed to verify builtin sound JAR: " + file.getName(), failure);
        }
    }

    public static void verifyBuiltinRetentionDirectory(File directory, File licensesDirectory) {
        List<String> names = new ArrayList<>(walkRelativeFiles(directory));
        File[] licenses = licensesDirectory.listFiles(File::isFile);
        if (licenses != null) {
            for (File lic : licenses) {
                names.add("META-INF/licenses/" + lic.getName());
            }
        }
        verifyBuiltinRetentionResources(name -> {
            if (name.startsWith("META-INF/licenses/")) {
                return readUnchecked(new File(licensesDirectory, name.substring("META-INF/licenses/".length())));
            }
            return readUnchecked(new File(directory, name));
        }, names, directory.getPath());
    }

    public static void verifyBuiltinRetentionJar(File file) {
        try (ZipFile archive = new ZipFile(file)) {
            List<String> names = zipFileEntries(archive);
            verifyBuiltinRetentionResources(name -> readEntry(archive, name), names, file.getPath());
        } catch (IOException failure) {
            throw new GradleException("Failed to verify builtin retention JAR: " + file.getName(), failure);
        }
    }

    public static void verifyReleaseJarContents(File file) {
        try (ZipFile archive = new ZipFile(file)) {
            List<String> classes = new ArrayList<>();
            for (String entry : zipFileEntries(archive)) {
                if (entry.endsWith(".class")) classes.add(entry);
            }
            if (classes.isEmpty()) {
                throw new GradleException("Release JAR [" + file.getName() + "] contains no compiled classes");
            }
            for (String entry : classes) {
                if (entry.contains("/development/") || entry.startsWith("com/tianluoqaq/tlmnh/devtools/")) {
                    throw new GradleException(
                        "Release JAR [" + file.getName() + "] leaked development class entry: " + entry);
                }
                int slash = entry.lastIndexOf('/');
                String simple = entry.substring(slash + 1, entry.length() - ".class".length());
                int dollar = simple.indexOf('$');
                String outerSimple = dollar >= 0 ? simple.substring(0, dollar) : simple;
                if ("DevelopmentCommand".equals(outerSimple) || FORBIDDEN_CLASS_SUFFIXES.stream()
                    .anyMatch(outerSimple::endsWith)) {
                    throw new GradleException(
                        "Release JAR [" + file
                            .getName() + "] leaked non-release class [" + outerSimple + "]: " + entry);
                }
            }
        } catch (IOException failure) {
            throw new GradleException("Failed to inspect release JAR classes: " + file.getName(), failure);
        }
        try (JarFile archive = new JarFile(file)) {
            String mixins = archive.getManifest() == null ? null
                : archive.getManifest()
                    .getMainAttributes()
                    .getValue("MixinConfigs");
            if (!EXPECTED_MIXIN_CONFIGS.equals(mixins)) {
                throw new GradleException(
                    "Release JAR [" + file.getName() + "] has unexpected MixinConfigs manifest: " + mixins);
            }
        } catch (IOException failure) {
            throw new GradleException("Failed to inspect release JAR manifest: " + file.getName(), failure);
        }
    }

    public static void verifyReleaseBundle(File file) {
        verifyBuiltinSoundJar(file);
        verifyBuiltinRetentionJar(file);
        verifyProjectLicenseJar(file);
        verifyReleaseJarContents(file);
    }

    @SuppressWarnings("unchecked")
    private static void verifyBuiltinSoundResources(Function<String, byte[]> read, List<String> names, String label) {
        String manifestPath = "assets/tlmnh/builtin-sounds-provenance.json";
        String catalogPath = "assets/tlmnh/builtin-sounds.json";
        byte[] raw = read.apply(manifestPath);
        requireCheck(
            "2c7957f377103ea90ec71b82c5969c334b52954c66e21c13036869d429aaf5b0".equals(sha256(raw)),
            "Builtin sounds [" + label + "]: authority manifest differs from the fixed original-byte inventory");
        Map<String, Object> manifest = (Map<String, Object>) new JsonSlurper()
            .parseText(new String(raw, StandardCharsets.UTF_8));
        requireCheck(
            Integer.valueOf(2)
                .equals(((Number) manifest.get("schema")).intValue())
                && "8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6".equals(manifest.get("commit")),
            "Builtin sounds [" + label + "]: unexpected source schema or upstream commit");
        requireCheck(
            sha256(read.apply(catalogPath)).equals(manifest.get("catalog_sha256")),
            "Builtin sounds [" + label + "]: catalog differs from authority manifest");
        requireCheck(
            countExact(names, manifestPath) == 1 && countExact(names, catalogPath) == 1,
            "Builtin sounds [" + label + "]: missing or duplicate sound manifests");

        Set<String> recorded = new HashSet<>();
        Set<String> audio = new HashSet<>();
        int voiceCount = 0;
        int baseCount = 0;
        for (Object item : (List<?>) manifest.get("files")) {
            Map<String, Object> entry = (Map<String, Object>) item;
            String path = (String) entry.get("path");
            requireCheck(
                path.startsWith("assets/") && isSafeRelativePath(path),
                "Builtin sounds [" + label + "]: unsafe resource path: " + path);
            requireCheck(
                recorded.add(path) && countExact(names, path) == 1,
                "Builtin sounds [" + label + "]: missing or duplicate resource: " + path);
            byte[] bytes = read.apply(path);
            requireCheck(
                bytes.length == ((Number) entry.get("bytes")).longValue() && sha256(bytes).equals(entry.get("sha256")),
                "Builtin sounds [" + label + "]: original resource bytes differ: " + path);
            requireCheck(
                entry.get("audio") instanceof Boolean,
                "Builtin sounds [" + label + "]: missing audio classification: " + path);
            if (Boolean.TRUE.equals(entry.get("audio"))) {
                requireCheck(
                    path.endsWith(".ogg") && bytes.length >= 4
                        && bytes[0] == 0x4f
                        && bytes[1] == 0x67
                        && bytes[2] == 0x67
                        && bytes[3] == 0x53,
                    "Builtin sounds [" + label + "]: invalid Ogg: " + path);
                audio.add(path);
                String group = (String) entry.get("audio_group");
                if ("voice".equals(group)) voiceCount++;
                else if ("base".equals(group)) baseCount++;
                else throw new GradleException("Builtin sounds [" + label + "]: unknown audio group: " + path);
            }
        }
        List<String> actualAudio = new ArrayList<>();
        for (String name : names) {
            if (name.toLowerCase(Locale.ROOT)
                .endsWith(".ogg")) actualAudio.add(name);
        }
        requireCheck(
            recorded.size() == 483 && audio.size() == 445 && voiceCount == 435 && baseCount == 10,
            "Builtin sounds [" + label + "]: expected 483 sourced entries, including 435 voice and 10 base Ogg files");
        Set<String> actualAudioSet = new HashSet<>(actualAudio);
        requireCheck(
            actualAudio.size() == actualAudioSet.size() && actualAudioSet.equals(audio),
            "Builtin sounds [" + label + "]: actual Ogg inventory differs from the complete fixed inventory");
    }

    @SuppressWarnings("unchecked")
    private static void verifyBuiltinRetentionResources(Function<String, byte[]> read, List<String> names,
        String label) {
        Map<String, Integer> counts = new HashMap<>();
        for (String name : names) counts.merge(name, 1, Integer::sum);
        String manifestPath = "assets/tlmnh/builtin-retention-provenance.json";
        byte[] raw = read.apply(manifestPath);
        requireCheck(
            "9829ef13de6c8134c947df3e3e2568805a075a2075f3737a27eac22674cafe90".equals(sha256(raw)),
            "Builtin retention [" + label + "]: authority manifest differs from the complete fixed Git-tree inventory");
        Map<String, Object> manifest = (Map<String, Object>) new JsonSlurper()
            .parseText(new String(raw, StandardCharsets.UTF_8));
        requireCheck(
            Integer.valueOf(1)
                .equals(((Number) manifest.get("schema")).intValue())
                && "8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6".equals(manifest.get("commit"))
                && "CC-BY-NC-SA-4.0".equals(manifest.get("license")),
            "Builtin retention [" + label + "]: unexpected source schema, commit or license");
        requireCheck(
            Integer.valueOf(1)
                .equals(counts.get(manifestPath)),
            "Builtin retention [" + label + "]: missing or duplicate retention manifest");

        Set<String> recorded = new HashSet<>();
        long totalBytes = 0L;
        for (Object item : (List<?>) manifest.get("files")) {
            Map<String, Object> entry = (Map<String, Object>) item;
            String path = (String) entry.get("path");
            requireCheck(
                recorded.add(path),
                "Builtin retention [" + label + "]: duplicate retention declaration: " + path);
            checkRetentionEntry(read, counts, label, path, entry);
            totalBytes += ((Number) entry.get("bytes")).longValue();
        }
        requireCheck(
            recorded.size() == 332 && totalBytes == 5538204L,
            "Builtin retention [" + label + "]: expected all 332 reviewed original files");

        Map<String, Object> catalog = (Map<String, Object>) manifest.get("catalog");
        checkRetentionEntry(read, counts, label, (String) catalog.get("path"), catalog);

        for (Object item : (List<?>) manifest.get("baseline_manifests")) {
            Map<String, Object> baseline = (Map<String, Object>) item;
            String path = (String) baseline.get("path");
            checkRetentionEntry(read, counts, label, path, baseline);
            if (path.endsWith("builtin-sounds-provenance.json")) continue;
            Map<String, Object> summary = (Map<String, Object>) new JsonSlurper()
                .parseText(new String(read.apply(path), StandardCharsets.UTF_8));
            Object entries = summary.get("files");
            if (entries instanceof Map<?, ?>mapEntries) {
                for (Map.Entry<?, ?> pair : mapEntries.entrySet()) {
                    verifyBaselineTarget(
                        read,
                        counts,
                        recorded,
                        label,
                        (String) pair.getKey(),
                        (Map<String, Object>) pair.getValue());
                }
            } else if (entries instanceof List<?>listEntries) {
                for (Object recordObj : listEntries) {
                    Map<String, Object> record = (Map<String, Object>) recordObj;
                    verifyBaselineTarget(read, counts, recorded, label, (String) record.get("path"), record);
                }
            } else {
                throw new GradleException("Builtin retention [" + label + "]: invalid protected manifest: " + path);
            }
        }
    }

    private static void verifyBaselineTarget(Function<String, byte[]> read, Map<String, Integer> counts,
        Set<String> recorded, String label, String target, Map<String, Object> entry) {
        String resource = target.startsWith("licenses/") ? "META-INF/" + target
            : (target.startsWith("src/main/resources/") ? target.substring("src/main/resources/".length()) : target);
        requireCheck(
            !recorded.contains(resource),
            "Builtin retention [" + label
                + "]: retained resource would override an existing effective asset: "
                + resource);
        checkRetentionEntry(read, counts, label, resource, entry);
    }

    private static void checkRetentionEntry(Function<String, byte[]> read, Map<String, Integer> counts, String label,
        String path, Map<String, Object> entry) {
        requireCheck(
            !path.isEmpty() && !path.startsWith("/") && isSafeRelativePath(path),
            "Builtin retention [" + label + "]: unsafe resource path: " + path);
        requireCheck(
            Integer.valueOf(1)
                .equals(counts.get(path)),
            "Builtin retention [" + label + "]: missing or duplicate retained/protected resource: " + path);
        byte[] bytes = read.apply(path);
        Object expectedBytes = entry.get("bytes");
        requireCheck(
            sha256(bytes).equals(entry.get("sha256"))
                && (expectedBytes == null || bytes.length == ((Number) expectedBytes).longValue()),
            "Builtin retention [" + label + "]: original resource bytes differ: " + path);
    }

    private static boolean isSafeRelativePath(String path) {
        if (path.indexOf('\\') >= 0 || path.indexOf(':') >= 0) return false;
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) return false;
        }
        return true;
    }

    private static void requireCheck(boolean condition, String message) {
        if (!condition) throw new GradleException(message);
    }

    private static int countExact(List<String> names, String target) {
        int count = 0;
        for (String name : names) {
            if (target.equals(name)) count++;
        }
        return count;
    }

    private static List<String> walkRelativeFiles(File root) {
        List<String> result = new ArrayList<>();
        if (!root.exists()) return result;
        try (var stream = Files.walk(root.toPath())) {
            stream.filter(Files::isRegularFile)
                .forEach(
                    p -> result.add(
                        root.toPath()
                            .relativize(p)
                            .toString()
                            .replace('\\', '/')));
        } catch (IOException failure) {
            throw new GradleException("Failed to scan directory: " + root.getPath(), failure);
        }
        return result;
    }

    private static List<String> zipFileEntries(ZipFile archive) {
        List<String> names = new ArrayList<>();
        Enumeration<? extends ZipEntry> entries = archive.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (!entry.isDirectory()) names.add(entry.getName());
        }
        return names;
    }

    private static byte[] readEntry(ZipFile archive, String name) {
        ZipEntry entry = archive.getEntry(name);
        if (entry == null) throw new GradleException("Missing archive entry: " + name);
        try (InputStream input = archive.getInputStream(entry)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return output.toByteArray();
        } catch (IOException failure) {
            throw new GradleException("Failed to read archive entry: " + name, failure);
        }
    }

    private static byte[] readUnchecked(File file) {
        try {
            return Files.readAllBytes(file.toPath());
        } catch (IOException failure) {
            throw new GradleException("Failed to read file: " + file.getPath(), failure);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return hex.toString();
        } catch (Exception failure) {
            throw new GradleException("SHA-256 unavailable", failure);
        }
    }
}
