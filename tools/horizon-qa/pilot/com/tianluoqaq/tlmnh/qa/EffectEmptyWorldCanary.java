package com.tianluoqaq.tlmnh.qa;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Random;

import net.minecraft.world.World;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.gen.ChunkProviderServer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.gtnewhorizons.horizonqa.internal.GameTestChunkLoader;
import com.tianluoqaq.tlmnh.compat.endlessids.development.biome.EndlessIdsBiomeRead;

import cpw.mods.fml.common.IWorldGenerator;
import cpw.mods.fml.common.registry.GameRegistry;

/** 仅独立N/P冷启动注册的只计数sentinel；不读Random，不生成或清除对象 */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa", "hodgepodge" })
public final class EffectEmptyWorldCanary implements IWorldGenerator {

    private static final int MIN = 6250, MAX = 6253, LIMIT = 24000;
    private static final EffectEmptyWorldCanary SENTINEL = new EffectEmptyWorldCanary();
    private final Map<World, Map<String, Integer>> counts = new IdentityHashMap<>();
    private final JsonArray callbacks = new JsonArray();
    private boolean registered, overflow;

    private EffectEmptyWorldCanary() {}

    static void register() {
        if (!EffectEmptyWorldFixture.enabled()) return;
        require(
            !EffectEmptyWorldFixture.current()
                .mode()
                .equals("RUN") && !SENTINEL.registered,
            "Sentinel must never register in normal V6 JVM");
        SENTINEL.registered = true;
        GameRegistry.registerWorldGenerator(SENTINEL, Integer.MAX_VALUE);
    }

    @Override
    public void generate(Random ignored, int chunkX, int chunkZ, World actual, IChunkProvider chunkGenerator,
        IChunkProvider chunkProvider) {
        // Identity/accounting only: no Random, generation or chunk queries
        int observationSequence = EffectEmptyWorldFixture.current()
            .sentinel(actual, chunkX, chunkZ);
        if (observationSequence < 0) return;
        if (callbacks.size() >= LIMIT) {
            overflow = true;
            return;
        }
        Map<String, Integer> worldCounts = counts.get(actual);
        if (worldCounts == null) {
            worldCounts = new java.util.HashMap<>();
            counts.put(actual, worldCounts);
        }
        String key = chunkX + ":" + chunkZ;
        worldCounts.put(key, worldCounts.containsKey(key) ? worldCounts.get(key) + 1 : 1);
        JsonObject row = new JsonObject();
        row.addProperty("fixtureObservationSequence", observationSequence);
        row.addProperty("worldIdentity", System.identityHashCode(actual));
        row.addProperty("dimension", actual.provider.dimensionId);
        row.addProperty("chunkX", chunkX);
        row.addProperty("chunkZ", chunkZ);
        row.addProperty(
            "thread",
            Thread.currentThread()
                .getName());
        callbacks.add(row);
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 1400, batch = "effect_empty_world_mechanism")
    public static void nativeDispatcherColdStartMechanism(GameTestHelper h) throws Exception {
        EffectEmptyWorldFixture fixture = EffectEmptyWorldFixture.current();
        require(
            !fixture.mode()
                .equals("RUN") && SENTINEL.registered,
            "Explicit N/P sentinel mode required");
        require(h.getWorld() == fixture.world(), "Mechanism helper world mismatch");
        var loader = new GameTestChunkLoader();
        h.afterTest(loader::releaseAll);
        fixture.preparing(h.getWorld(), entity -> false);
        ChunkProviderServer provider = h.getWorld().theChunkProviderServer;
        for (int x = MIN; x <= MAX; x++) for (int z = MIN; z <= MAX; z++)
            require(!provider.chunkExists(x, z), "Fixed target mechanism chunk was already loaded; no reselection");
        fixture.prepareArea(loader, MIN * 16, MIN * 16, MAX * 16 + 15, MAX * 16 + 15);
        boolean[] finished = { false };
        h.onEachTick("fixed native-population mechanism, no actors or ring API", () -> {
            if (finished[0] || !fixture.ready()) return;
            require(!SENTINEL.overflow, "Sentinel callback witness overflow");
            Map<String, Integer> actual = SENTINEL.counts.get(h.getWorld());
            int total = 0;
            JsonArray targets = new JsonArray();
            for (int x = MIN; x <= MAX; x++) for (int z = MIN; z <= MAX; z++) {
                var chunk = EffectEmptyWorldFixture.existing(h.getWorld(), x, z);
                require(chunk.isTerrainPopulated && chunk.isLightPopulated, "Native population/light path incomplete");
                int count = actual == null || !actual.containsKey(x + ":" + z) ? 0 : actual.get(x + ":" + z);
                total += count;
                JsonObject row = new JsonObject();
                row.addProperty("x", x);
                row.addProperty("z", z);
                row.addProperty("sentinelCallbacks", count);
                row.addProperty("terrainPopulated", chunk.isTerrainPopulated);
                row.addProperty("lightPopulated", chunk.isLightPopulated);
                EndlessIdsBiomeRead.requireFixedPlains(chunk);
                row.addProperty("allPlainsBiome", true);
                targets.add(row);
            }
            boolean negative = fixture.mode()
                .equals("N");
            require(
                negative ? total > 0 : total == 0,
                negative ? "N dispatcher was not detectable; P zero cannot admit" : "P still invoked Forge dispatcher");
            JsonObject report = new JsonObject();
            report.addProperty("schema", "effect-empty-world-canary-v1");
            report.addProperty("mode", fixture.mode());
            report.addProperty("passed", true);
            report.addProperty("targetCallbacks", total);
            report.addProperty("sentinelRegistered", true);
            report.addProperty("actorCreated", false);
            report.addProperty("arrowSeedWrites", 0);
            report.addProperty("ringApiCalled", false);
            report.addProperty("negativeDoesNotRequestEmptyAdmission", negative);
            report.addProperty("transformedDefinitionBytesVerified", false);
            report.add("fixedTargets", targets);
            report.add("callbacks", SENTINEL.callbacks);
            fixture.completeCanary(report);
            finished[0] = true;
        });
        h.succeedWhen(() -> finished[0]);
    }

    private static void require(boolean okay, String message) {
        if (!okay) throw new IllegalStateException(message);
    }
}
