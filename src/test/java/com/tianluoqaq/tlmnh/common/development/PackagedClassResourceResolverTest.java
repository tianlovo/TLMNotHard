package com.tianluoqaq.tlmnh.common.development;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PackagedClassResourceResolverTest {

    private static final String CLASS = ResourceIdentityMarker.class.getName();
    private static final String LOGICAL = CLASS.replace('.', '/') + ".class";

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void baseResourceBindsActualUrlAndPhysicalCodeSource() throws Exception {
        byte[] base = markerBytes();
        Path jar = jar("base.jar", false, base, null, null);
        try (var loader = new URLClassLoader(
            new URL[] { jar.toUri()
                .toURL() },
            null)) {
            var binding = PackagedClassResourceResolver.resolve(loader.loadClass(CLASS), jar);
            assertEquals(LOGICAL, binding.logicalEntry);
            assertEquals(LOGICAL, binding.physicalEntry);
            assertEquals(0, binding.multiReleaseVersion);
            assertEquals(sha(base), binding.sha256);
        }
    }

    @Test
    public void runtimeSelectedMrResourceBindsObservedPhysicalEntry() throws Exception {
        byte[] base = markerBytes(), variant = variantBytes(base);
        String selected = "META-INF/versions/9/" + LOGICAL;
        Path jar = jar("runtime-mr.jar", true, base, selected, variant);
        try (var loader = new URLClassLoader(
            new URL[] { jar.toUri()
                .toURL() },
            null)) {
            var type = loader.loadClass(CLASS);
            var binding = PackagedClassResourceResolver.resolve(type, jar);
            assertEquals(LOGICAL, binding.logicalEntry);
            assertEquals(selected, binding.physicalEntry);
            assertEquals(9, binding.multiReleaseVersion);
            assertEquals(sha(variant), binding.sha256);
            assertNotEquals(sha(base), binding.sha256);
            String old = System.getProperty("tlmnh.devCommands");
            try {
                System.setProperty("tlmnh.devCommands", "true");
                var identity = DevelopmentArtifactIdentity.capture(type);
                var metadata = identity.getAsJsonObject("classResourceBindings")
                    .getAsJsonObject(CLASS);
                assertEquals(
                    selected,
                    metadata.get("physicalEntry")
                        .getAsString());
                assertEquals(
                    sha(variant),
                    identity.getAsJsonObject("classResources")
                        .get(CLASS)
                        .getAsString());
                assertFalse(
                    identity.toString()
                        .contains(
                            temporary.getRoot()
                                .toString()));
                assertFalse(
                    identity.toString()
                        .contains(
                            temporary.getRoot()
                                .getName()));
                assertFalse(
                    identity.toString()
                        .contains("file:"));
                assertFalse(
                    identity.get("transformedDefinitionBytesVerified")
                        .getAsBoolean());
            } finally {
                if (old == null) System.clearProperty("tlmnh.devCommands");
                else System.setProperty("tlmnh.devCommands", old);
            }
        }
    }

    @Test
    public void actualResourceAtDisallowedMrEntryIsRejected() throws Exception {
        byte[] base = markerBytes();
        for (boolean manifest : new boolean[] { false, true }) {
            String entry = "META-INF/versions/" + (manifest ? "999" : "9") + '/' + LOGICAL;
            Path jar = jar("disallowed-" + manifest + ".jar", manifest, base, entry, variantBytes(base));
            try (var loader = resourceOverride(jar, entry)) {
                var type = loader.loadClass(CLASS);
                var failure = assertThrows(
                    IllegalStateException.class,
                    () -> PackagedClassResourceResolver.resolve(type, jar));
                assertTrue(
                    failure.getMessage()
                        .contains(CLASS));
                assertFalse(
                    failure.getMessage()
                        .contains(
                            temporary.getRoot()
                                .toString()));
            }
        }
    }

    @Test
    public void matchingBytesAtWrongPhysicalClassPathAreRejected() throws Exception {
        byte[] base = markerBytes();
        String wrong = "diagnostic/" + LOGICAL;
        Path jar = jar("wrong-path.jar", true, base, wrong, base);
        try (var loader = resourceOverride(jar, wrong)) {
            var type = loader.loadClass(CLASS);
            assertThrows(IllegalStateException.class, () -> PackagedClassResourceResolver.resolve(type, jar));
        }
    }

    @Test
    public void changedResourceStreamCannotMatchAnUnchangedPhysicalEntry() throws Exception {
        byte[] base = markerBytes(), changed = variantBytes(base);
        Path jar = jar("changed-stream.jar", false, base, null, null);
        try (var selectedJar = new JarFile(jar.toFile())) {
            URL changedResource = new URL(
                null,
                "jar:" + jar.toUri()
                    .toURL() + "!/" + LOGICAL,
                new URLStreamHandler() {

                    @Override
                    protected URLConnection openConnection(URL url) throws IOException {
                        return new JarURLConnection(url) {

                            @Override
                            public void connect() {
                                connected = true;
                            }

                            @Override
                            public JarFile getJarFile() {
                                return selectedJar;
                            }

                            @Override
                            public java.io.InputStream getInputStream() {
                                return new ByteArrayInputStream(changed);
                            }
                        };
                    }
                });
            try (var loader = new URLClassLoader(
                new URL[] { jar.toUri()
                    .toURL() },
                null) {

                @Override
                public URL getResource(String name) {
                    return LOGICAL.equals(name) ? changedResource : super.getResource(name);
                }
            }) {
                var type = loader.loadClass(CLASS);
                var failure = assertThrows(
                    IllegalStateException.class,
                    () -> PackagedClassResourceResolver.resolve(type, jar));
                assertTrue(
                    failure.getMessage()
                        .contains("bytes differ"));
            }
        }
    }

    @Test
    public void parentResourceCannotShadowActualClassCodeSource() throws Exception {
        byte[] base = markerBytes();
        Path code = jar("class-source.jar", false, base, null, null);
        Path shadow = jar("resource-shadow.jar", false, variantBytes(base), null, null);
        try (var parent = new URLClassLoader(
            new URL[] { shadow.toUri()
                .toURL() },
            null);
            var child = new URLClassLoader(
                new URL[] { code.toUri()
                    .toURL() },
                parent) {

                @Override
                protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    if (!CLASS.equals(name)) return super.loadClass(name, resolve);
                    Class<?> found = findLoadedClass(name);
                    if (found == null) found = findClass(name);
                    if (resolve) resolveClass(found);
                    return found;
                }
            }) {
            var type = child.loadClass(CLASS);
            assertEquals(code.toRealPath(), PackagedClassResourceResolver.artifactOf(type));
            assertTrue(
                type.getResource('/' + LOGICAL)
                    .toString()
                    .contains("resource-shadow.jar"));
            var failure = assertThrows(
                IllegalStateException.class,
                () -> PackagedClassResourceResolver.resolve(type, code));
            assertTrue(
                failure.getMessage()
                    .contains("shadows"));
            assertFalse(
                failure.getMessage()
                    .contains(
                        temporary.getRoot()
                            .toString()));
        }
    }

    private URLClassLoader resourceOverride(Path jar, String entry) throws Exception {
        URL selected = new URL(
            "jar:" + jar.toUri()
                .toURL() + "!/" + entry);
        return new URLClassLoader(
            new URL[] { jar.toUri()
                .toURL() },
            null) {

            @Override
            public URL getResource(String name) {
                return LOGICAL.equals(name) ? selected : super.getResource(name);
            }
        };
    }

    private Path jar(String name, boolean mr, byte[] base, String additional, byte[] bytes) throws Exception {
        Path path = temporary.newFile(name)
            .toPath();
        var manifest = new Manifest();
        manifest.getMainAttributes()
            .put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (mr) manifest.getMainAttributes()
            .putValue("Multi-Release", "true");
        try (var output = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            output.putNextEntry(new JarEntry(LOGICAL));
            output.write(base);
            output.closeEntry();
            if (additional != null) {
                output.putNextEntry(new JarEntry(additional));
                output.write(bytes);
                output.closeEntry();
            }
        }
        return path;
    }

    private static byte[] markerBytes() throws Exception {
        try (var input = ResourceIdentityMarker.class.getResourceAsStream('/' + LOGICAL)) {
            assertNotNull(input);
            var output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private static byte[] variantBytes(byte[] original) {
        byte[] from = "ResourceIdentityMarker.java".getBytes(StandardCharsets.UTF_8);
        byte[] to = "ResourceIdentityMarkeX.java".getBytes(StandardCharsets.UTF_8);
        byte[] result = original.clone();
        int matches = 0;
        for (int offset = 0; offset <= result.length - from.length; offset++) {
            if (!Arrays.equals(from, Arrays.copyOfRange(result, offset, offset + from.length))) continue;
            System.arraycopy(to, 0, result, offset, to.length);
            matches++;
        }
        assertEquals("Real compiler SourceFile attribute required", 1, matches);
        return result;
    }

    private static String sha(byte[] bytes) throws Exception {
        var result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256")
            .digest(bytes)) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
}
