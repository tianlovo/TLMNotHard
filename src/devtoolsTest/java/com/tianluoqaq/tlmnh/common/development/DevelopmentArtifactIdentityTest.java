package com.tianluoqaq.tlmnh.common.development;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.tianluoqaq.tlmnh.common.diagnostic.DiagnosticIds;
import com.tianluoqaq.tlmnh.common.protection.Sha256Hex;

public class DevelopmentArtifactIdentityTest {

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    static final class PrimaryAnchorSample {

        private PrimaryAnchorSample() {}
    }

    static final class PrimaryPayloadSample {

        private PrimaryPayloadSample() {}
    }

    static final class DevtoolsAnchorSample {

        private DevtoolsAnchorSample() {}
    }

    static final class DevtoolsPayloadSample {

        private DevtoolsPayloadSample() {}
    }

    static final class ForeignModSample {

        private ForeignModSample() {}
    }

    @Test
    public void singleArtifactCapturePreservesOriginalIdentityFields() throws Exception {
        Path singleJar = jarWithClasses("single-candidate.jar", ResourceIdentityMarker.class);
        try (var loader = isolatedLoader(singleJar)) {
            Class<?> marker = loader.loadClass(ResourceIdentityMarker.class.getName());
            withDevCommandsEnabled(() -> {
                var identity = DevelopmentArtifactIdentity.capture(marker);
                assertEquals(
                    "single-candidate.jar",
                    identity.get("artifactFile")
                        .getAsString());
                assertEquals(
                    Sha256Hex.ofBytes(Files.readAllBytes(singleJar)),
                    identity.get("artifactSha256")
                        .getAsString());
                assertFalse(identity.has("devtoolsArtifactFile"));
                assertFalse(identity.has("devtoolsArtifactSha256"));
                assertEquals(
                    Sha256Hex.ofBytes(classBytes(ResourceIdentityMarker.class)),
                    identity.getAsJsonObject("classResources")
                        .get(ResourceIdentityMarker.class.getName())
                        .getAsString());
            });
        }
    }

    @Test
    public void pairedPrimaryAndDevtoolsArtifactsReportPrimaryCandidateRegardlessOfOrder() throws Exception {
        Path primaryJar = jarWithClasses("tlmnh-primary.jar", PrimaryAnchorSample.class, PrimaryPayloadSample.class);
        Path devtoolsJar = jarWithClasses(
            "tlmnhdevtools-paired.jar",
            DevtoolsAnchorSample.class,
            DevtoolsPayloadSample.class);
        try (var primaryLoader = isolatedLoader(primaryJar); var devtoolsLoader = isolatedLoader(devtoolsJar)) {
            Class<?> primaryAnchor = primaryLoader.loadClass(PrimaryAnchorSample.class.getName());
            Class<?> primaryPayload = primaryLoader.loadClass(PrimaryPayloadSample.class.getName());
            Class<?> devtoolsAnchor = devtoolsLoader.loadClass(DevtoolsAnchorSample.class.getName());
            Class<?> devtoolsPayload = devtoolsLoader.loadClass(DevtoolsPayloadSample.class.getName());
            String expectedPrimarySha = Sha256Hex.ofBytes(Files.readAllBytes(primaryJar));
            String expectedDevtoolsSha = Sha256Hex.ofBytes(Files.readAllBytes(devtoolsJar));
            withDevCommandsEnabled(() -> {
                for (Class<?>[] order : new Class<?>[][] { { devtoolsPayload, primaryPayload },
                    { primaryPayload, devtoolsPayload } }) {
                    var identity = DevelopmentArtifactIdentity.captureWithAnchors(primaryAnchor, devtoolsAnchor, order);
                    assertEquals(
                        "tlmnh-primary.jar",
                        identity.get("artifactFile")
                            .getAsString());
                    assertEquals(
                        expectedPrimarySha,
                        identity.get("artifactSha256")
                            .getAsString());
                    assertEquals(
                        "tlmnhdevtools-paired.jar",
                        identity.get("devtoolsArtifactFile")
                            .getAsString());
                    assertEquals(
                        expectedDevtoolsSha,
                        identity.get("devtoolsArtifactSha256")
                            .getAsString());
                    var resources = identity.getAsJsonObject("classResources");
                    assertEquals(
                        Sha256Hex.ofBytes(classBytes(PrimaryPayloadSample.class)),
                        resources.get(PrimaryPayloadSample.class.getName())
                            .getAsString());
                    assertEquals(
                        Sha256Hex.ofBytes(classBytes(DevtoolsPayloadSample.class)),
                        resources.get(DevtoolsPayloadSample.class.getName())
                            .getAsString());
                    assertFalse(
                        identity.toString()
                            .contains(
                                temporary.getRoot()
                                    .toString()));
                    assertFalse(
                        identity.toString()
                            .contains("file:"));
                }
            });
        }
    }

    @Test
    public void unauthorizedArtifactPairsAndThreeArtifactInputsAreRejected() throws Exception {
        Path primaryJar = jarWithClasses("tlmnh-primary.jar", PrimaryAnchorSample.class, PrimaryPayloadSample.class);
        Path devtoolsJar = jarWithClasses(
            "tlmnhdevtools-paired.jar",
            DevtoolsAnchorSample.class,
            DevtoolsPayloadSample.class);
        Path foreignJar = jarWithClasses("foreign-mod.jar", ForeignModSample.class);
        try (var primaryLoader = isolatedLoader(primaryJar);
            var devtoolsLoader = isolatedLoader(devtoolsJar);
            var foreignLoader = isolatedLoader(foreignJar)) {
            Class<?> primaryAnchor = primaryLoader.loadClass(PrimaryAnchorSample.class.getName());
            Class<?> primaryPayload = primaryLoader.loadClass(PrimaryPayloadSample.class.getName());
            Class<?> devtoolsAnchor = devtoolsLoader.loadClass(DevtoolsAnchorSample.class.getName());
            Class<?> devtoolsPayload = devtoolsLoader.loadClass(DevtoolsPayloadSample.class.getName());
            Class<?> foreignPayload = foreignLoader.loadClass(ForeignModSample.class.getName());
            withDevCommandsEnabled(() -> {
                var devAndForeign = assertThrows(
                    IllegalStateException.class,
                    () -> DevelopmentArtifactIdentity
                        .captureWithAnchors(primaryAnchor, devtoolsAnchor, devtoolsPayload, foreignPayload));
                assertTrue(
                    devAndForeign.getMessage()
                        .contains("unauthorized artifact pair"));
                assertThrows(
                    IllegalStateException.class,
                    () -> DevelopmentArtifactIdentity
                        .captureWithAnchors(primaryAnchor, devtoolsAnchor, foreignPayload, devtoolsPayload));

                var mainAndForeign = assertThrows(
                    IllegalStateException.class,
                    () -> DevelopmentArtifactIdentity
                        .captureWithAnchors(primaryAnchor, devtoolsAnchor, primaryPayload, foreignPayload));
                assertTrue(
                    mainAndForeign.getMessage()
                        .contains("unauthorized artifact pair"));
                assertThrows(
                    IllegalStateException.class,
                    () -> DevelopmentArtifactIdentity
                        .captureWithAnchors(primaryAnchor, devtoolsAnchor, foreignPayload, primaryPayload));

                var threeJars = assertThrows(
                    IllegalStateException.class,
                    () -> DevelopmentArtifactIdentity.captureWithAnchors(
                        primaryAnchor,
                        devtoolsAnchor,
                        primaryPayload,
                        devtoolsPayload,
                        foreignPayload));
                assertTrue(
                    threeJars.getMessage()
                        .contains("more than two artifacts"));

                assertThrows(
                    IllegalStateException.class,
                    () -> DevelopmentArtifactIdentity
                        .captureWithAnchors(primaryAnchor, primaryPayload, primaryPayload, devtoolsPayload));
            });
        }
    }

    private URLClassLoader isolatedLoader(Path jar) throws Exception {
        return new URLClassLoader(
            new URL[] { jar.toUri()
                .toURL() },
            null);
    }

    private Path jarWithClasses(String name, Class<?>... classes) throws Exception {
        Path path = temporary.newFile(name)
            .toPath();
        var manifest = new Manifest();
        manifest.getMainAttributes()
            .put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (var output = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            for (Class<?> type : classes) {
                String entry = type.getName()
                    .replace('.', '/') + ".class";
                output.putNextEntry(new JarEntry(entry));
                output.write(classBytes(type));
                output.closeEntry();
            }
        }
        return path;
    }

    private static byte[] classBytes(Class<?> type) throws Exception {
        String entry = '/' + type.getName()
            .replace('.', '/') + ".class";
        try (var input = type.getResourceAsStream(entry)) {
            assertNotNull(input);
            var output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    @FunctionalInterface
    private interface CheckedAction {

        void run() throws Exception;
    }

    private static void withDevCommandsEnabled(CheckedAction action) throws Exception {
        String previous = System.getProperty(DiagnosticIds.DEV_COMMANDS);
        try {
            System.setProperty(DiagnosticIds.DEV_COMMANDS, "true");
            action.run();
        } finally {
            if (previous == null) System.clearProperty(DiagnosticIds.DEV_COMMANDS);
            else System.setProperty(DiagnosticIds.DEV_COMMANDS, previous);
        }
    }
}
