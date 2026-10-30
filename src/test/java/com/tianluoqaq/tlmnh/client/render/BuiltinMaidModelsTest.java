package com.tianluoqaq.tlmnh.client.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.data.IMetadataSection;
import net.minecraft.util.ResourceLocation;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.junit.BeforeClass;
import org.junit.Test;

import com.tianluoqaq.tlmnh.common.model.BuiltinModelCatalog;

/** 检查实体渲染桥的几何、缓存和资源生命周期，不创建 GL 上下文 */
public class BuiltinMaidModelsTest {

    private static final float EPSILON = 1.0E-4F;
    private static final String GEOMETRY = "{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{"
        + "\"description\":{\"identifier\":\"geometry.test\",\"texture_width\":64,\"texture_height\":64,"
        + "\"visible_bounds_width\":2,\"visible_bounds_height\":2,\"visible_bounds_offset\":[0,1,0]},"
        + "\"bones\":[{\"name\":\"root\",\"pivot\":[7,12,5],\"rotation\":[13,29,-37],\"cubes\":[{"
        + "\"origin\":[1,2,3],\"size\":[2,4,6],\"uv\":[0,0],\"rotation\":[17,-23,31],\"pivot\":[2,6,4]}]}]}]}";

    @BeforeClass
    public static void loadCatalog() {
        BuiltinModelCatalog.load();
    }

    @Test
    public void allChoicesUseTheirOwnGeometryAndShareOnlyDefinitions() throws Exception {
        var reads = new HashMap<ResourceLocation, Integer>();
        var models = new BuiltinMaidModels(location -> {
            reads.merge(location, 1, Integer::sum);
            return resource(location);
        });
        int choices = 0, winefox = 0;
        long vertices = 0;
        for (var choice : BuiltinModelCatalog.choices()) {
            var model = models.create(choice);
            assertSame(choice, model.getChoice());
            assertEquals(choice.isGecko(), model.getGecko() != null);
            var output = render(model);
            assertTrue(choice.getId(), output.count > 0 && output.count % 4 == 0);
            choices++;
            if (choice.isWinefox()) winefox++;
            vertices += output.count;
        }
        assertEquals(254, choices);
        assertEquals(35, winefox);
        assertEquals(287, reads.size());
        for (var count : reads.values()) assertEquals(1, count.intValue());
        System.out.println(
            "TLMNH_MODEL_BRIDGE_PASS choices=" + choices
                + " winefox="
                + winefox
                + " geometries="
                + reads.size()
                + " vertices="
                + vertices
                + " scope=cpu-bridge-only");
    }

    @Test
    public void separateMaidsNeverShareMutableBones() throws Exception {
        var models = new BuiltinMaidModels(BuiltinMaidModelsTest::resource);
        var geckoChoice = choice(true);
        var first = models.create(geckoChoice)
            .getGecko();
        var second = models.create(geckoChoice)
            .getGecko();
        assertSame(first.geoModel(), second.geoModel());
        for (var entry : first.bones()
            .entrySet()) {
            var other = second.bones()
                .get(entry.getKey());
            assertNotSame(entry.getValue(), other);
            float original = other.getRotationX();
            entry.getValue()
                .setRotationX(original + 1);
            assertEquals(original, other.getRotationX(), 0);
        }
        var firstBedrock = models.create(choice(false))
            .getBedrock();
        var secondBedrock = models.create(choice(false))
            .getBedrock();
        for (var entry : firstBedrock.getModelMap()
            .entrySet()) {
            var other = secondBedrock.getModelMap()
                .get(entry.getKey());
            assertNotSame(entry.getValue(), other);
            float original = other.xRot;
            entry.getValue().xRot += 1;
            assertEquals(original, other.xRot, 0);
        }
    }

    @Test
    public void bothCoordinateSystemsAgreeAfterModelBaseConversion() throws Exception {
        var models = new BuiltinMaidModels(
            location -> location.equals(choice(false).getGeometry()) || location.equals(choice(true).getGeometry())
                ? new ByteArrayInputStream(GEOMETRY.getBytes(StandardCharsets.UTF_8))
                : resource(location));
        var bedrock = render(models.create(choice(false)));
        var gecko = render(models.create(choice(true)));
        assertEquals(24, bedrock.count);
        assertEquals(bedrock.count, gecko.count);
        for (var expected : bedrock.samples) {
            assertTrue(
                "Missing converted corner",
                gecko.samples.stream()
                    .anyMatch(actual -> sameCorner(expected, actual)));
        }
    }

    @Test
    public void reloadDropsCachedDefinitionsAndUsesNewResourceManager() throws Exception {
        var models = new BuiltinMaidModels(BuiltinMaidModelsTest::resource);
        var selected = choice(true);
        var before = models.create(selected)
            .getGecko()
            .geoModel();
        int oldGeneration = models.getGeneration();
        int[] reads = { 0 }, closes = { 0 };
        models.onResourceManagerReload(new Resources(location -> {
            reads[0]++;
            return tracked(resource(location), closes);
        }));
        assertEquals(oldGeneration + 1, models.getGeneration());
        var after = models.create(selected)
            .getGecko()
            .geoModel();
        assertNotSame(before, after);
        assertSame(
            after,
            models.create(selected)
                .getGecko()
                .geoModel());
        var animationResources = new java.util.HashSet<>(selected.getAnimations());
        animationResources.add(BuiltinGeckoAnimations.DEFAULT_MAID_ANIMATION);
        int expectedResources = 1 + animationResources.size();
        assertEquals(expectedResources, reads[0]);
        assertEquals(expectedResources, closes[0]);
    }

    @Test
    public void badResourcesFailClearlyAndCloseStreams() {
        for (boolean gecko : new boolean[] { false, true }) {
            var missing = new BuiltinMaidModels(location -> null);
            assertThrows(IOException.class, () -> missing.create(choice(gecko)));
            int[] closes = { 0 };
            var invalid = new BuiltinMaidModels(
                location -> tracked(new ByteArrayInputStream(new byte[] { '{' }), closes));
            assertThrows(IOException.class, () -> invalid.create(choice(gecko)));
            assertEquals(1, closes[0]);
        }
    }

    @Test
    public void failedVertexSubmissionRestoresTheCallerPose() throws Exception {
        var models = new BuiltinMaidModels(BuiltinMaidModelsTest::resource);
        for (boolean gecko : new boolean[] { false, true }) {
            var model = models.create(choice(gecko));
            var poses = new LegacyPoseStack();
            poses.translate(3, 4, 5);
            var beforePose = new Matrix4f(
                poses.last()
                    .pose());
            var beforeNormal = new Matrix3f(
                poses.last()
                    .normal());
            LegacyVertexConsumer broken = (x, y, z, r, g, b, a, u, v, overlay, light, nx, ny, nz) -> {
                throw new IllegalStateException("Test vertex failure");
            };
            assertThrows(IllegalStateException.class, () -> model.renderGeometry(poses, broken, 0));
            assertEquals(
                beforePose,
                poses.last()
                    .pose());
            assertEquals(
                beforeNormal,
                poses.last()
                    .normal());
            assertThrows(IllegalStateException.class, poses::popPose);
        }
    }

    private static BuiltinModelCatalog.Choice choice(boolean gecko) {
        return BuiltinModelCatalog.choices()
            .stream()
            .filter(c -> c.isGecko() == gecko)
            .findFirst()
            .get();
    }

    private static InputStream resource(ResourceLocation location) {
        return BuiltinMaidModelsTest.class
            .getResourceAsStream("/assets/" + location.getResourceDomain() + "/" + location.getResourcePath());
    }

    private static InputStream tracked(InputStream stream, int[] closes) {
        return new FilterInputStream(stream) {

            @Override
            public void close() throws IOException {
                closes[0]++;
                super.close();
            }
        };
    }

    private static RecordingConsumer render(BuiltinMaidModel model) {
        var output = new RecordingConsumer();
        model.renderGeometry(new LegacyPoseStack(), output, 0x00F000F0);
        return output;
    }

    private static boolean sameCorner(float[] a, float[] b) {
        return Math.abs(a[0] - b[0]) < EPSILON && Math.abs(a[1] - b[1]) < EPSILON && Math.abs(a[2] - b[2]) < EPSILON;
    }

    private static final class RecordingConsumer implements LegacyVertexConsumer {

        private int count;
        private final List<float[]> samples = new ArrayList<>();

        @Override
        public void vertex(float x, float y, float z, float r, float g, float b, float a, float u, float v, int overlay,
            int light, float nx, float ny, float nz) {
            for (float value : new float[] { x, y, z, r, g, b, a, u, v, nx, ny, nz }) assertTrue(Float.isFinite(value));
            if (samples.size() < 128) samples.add(new float[] { x, y, z });
            count++;
        }
    }

    private static final class Resources implements IResourceManager {

        private final BuiltinMaidModels.ResourceOpener opener;

        private Resources(BuiltinMaidModels.ResourceOpener opener) {
            this.opener = opener;
        }

        @Override
        public Set<String> getResourceDomains() {
            return Collections.emptySet();
        }

        @Override
        public IResource getResource(ResourceLocation location) throws IOException {
            var stream = opener.open(location);
            return new IResource() {

                @Override
                public InputStream getInputStream() {
                    return stream;
                }

                @Override
                public boolean hasMetadata() {
                    return false;
                }

                @Override
                public IMetadataSection getMetadata(String section) {
                    return null;
                }
            };
        }

        @Override
        public List<IResource> getAllResources(ResourceLocation location) throws IOException {
            return Collections.singletonList(getResource(location));
        }
    }
}
