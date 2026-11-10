package com.tianluoqaq.tlmnh.compat.hodgepodge.development.population;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.LongHashMap;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.DimensionManager;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.qa.PopulationQuiescenceContract;

import cpw.mods.fml.relauncher.Side;

/** Passive QA owner witness; public getters only, never advances provider state. */
public final class HodgePopulationProbe {

    private static final String KEY = "qa_hodge_population_quiescence_v1";
    private static final String SCHEDULER = "com.mitchej123.hodgepodge.mixins.hooks.ChunkGenScheduler";
    private static final String STATS = "com.mitchej123.hodgepodge.mixins.hooks.ChunkGenStats";
    private static final String[] RAW = { "genQueue", "popQueue", "pipelineBacklog", "popCount", "terrainGenCount",
        "neighborFills", "populationDepth" };
    private static final String[] GETTERS = { "getSnapGenQueue", "getSnapPopQueue", "getSnapPipelineBacklog",
        "getSnapPopCount", "getSnapTerrainGenCount", "getSnapNeighborFills" };
    private static volatile Thread expectedThread, ownerThread;
    private static volatile Object ownerScheduler;
    private static Object ownerStats;
    private static WorldServer ownerWorld;
    private static WorldProvider ownerProvider;
    private static ChunkProviderServer ownerCps;
    private static Method forDimension, getStats, getDepth;
    private static Method[] getters;
    private static JsonObject schedulerSource, statsSource;
    private static Class<?> reviewedPublicMapClass;
    private static JsonObject publicMapSource, reviewedPublicMapSource;
    private static String failure;
    private static volatile boolean valid;
    private static final java.util.concurrent.atomic.AtomicReference<String> ASYNC_FAILURE = new java.util.concurrent.atomic.AtomicReference<>();
    private static final java.util.concurrent.atomic.AtomicLong OUTSIDE_CALLS = new java.util.concurrent.atomic.AtomicLong(),
        OUTSIDE_NANOS = new java.util.concurrent.atomic.AtomicLong(),
        OUTSIDE_UNPAID_CALLS = new java.util.concurrent.atomic.AtomicLong(),
        OUTSIDE_UNPAID_NANOS = new java.util.concurrent.atomic.AtomicLong();
    private static int inFlight, headTick, viewDistance;
    private static long headWorldTick, generation, publishedGeneration, sequence, publishedWorldTick;
    private static int publishedTick;
    private static int[] publishedValues;
    private static long unpaidHookCalls, unpaidHookWallNanos;
    private static final ThreadLocal<Integer> PAID = ThreadLocal.withInitial(() -> 0);

    public static boolean paidScopeActive() {
        return PAID.get() > 0;
    }

    public static void enterPaidScope() {
        PAID.set(PAID.get() + 1);
    }

    public static void exitPaidScope() {
        int n = PAID.get();
        if (n <= 0) throw new IllegalStateException("Observer scope underflow");
        PAID.set(n - 1);
    }

    private static void hookCost(long before) {
        long elapsed = System.nanoTime() - before;
        hookWallNanos += elapsed;
        if (PAID.get() == 0) {
            unpaidHookCalls++;
            unpaidHookWallNanos += elapsed;
        }
    }

    private static long hookCalls, hookWallNanos, ownerHeads, ownerReturns, ownerMutations, clears;

    private HodgePopulationProbe() {}

    public static synchronized void expectThread(Thread thread) {
        if (expectedThread != null && expectedThread != thread) fail("Fixture server thread replaced");
        else expectedThread = thread;
    }

    private static void fail(String reason) {
        if (failure == null && ASYNC_FAILURE.get() == null) failure = reason;
        valid = false;
    }

    private static String problem() {
        return failure != null ? failure : ASYNC_FAILURE.get();
    }

    /** Foreign entries never acquire the owner monitor or read World/provider objects. */
    private static boolean outside(Object scheduler, boolean clearing) {
        Thread expected = expectedThread;
        if (expected == null || Thread.currentThread() == expected) return false;
        long before = System.nanoTime();
        OUTSIDE_CALLS.incrementAndGet();
        Object owner = ownerScheduler;
        if (clearing && owner != null) {
            ASYNC_FAILURE.compareAndSet(null, "Dimension scheduler owners cleared on foreign thread");
            valid = false;
        } else if (owner != null && scheduler == owner) {
            ASYNC_FAILURE.compareAndSet(null, "Owner callback on foreign thread");
            valid = false;
        }
        long elapsed = System.nanoTime() - before;
        OUTSIDE_NANOS.addAndGet(elapsed);
        if (!paidScopeActive()) {
            OUTSIDE_UNPAID_CALLS.incrementAndGet();
            OUTSIDE_UNPAID_NANOS.addAndGet(elapsed);
        }
        return true;
    }

    private static void bump() {
        if (generation == Long.MAX_VALUE) fail("Owner generation overflow");
        else generation++;
    }

    private static void require(boolean b, String reason) {
        if (!b) throw new IllegalStateException(reason);
    }

    private static <T> T guarded(IntegrationGuard.Call<T> operation, T fallback) {
        if (!IntegrationGuard.available(Side.SERVER, KEY)) {
            fail("Public Hodge capability unavailable");
            return fallback;
        }
        try {
            return operation.get();
        } catch (Exception | LinkageError | AssertionError problem) {
            IntegrationGuard.throwIfFatal(problem);
            fail(problem.toString());
            try {
                IntegrationGuard.report(Side.SERVER, KEY, problem);
            } catch (Exception | LinkageError | AssertionError diagnostic) {
                IntegrationGuard.throwIfFatal(diagnostic);
            }
            return fallback;
        }
    }

    private static void bind(Object scheduler, WorldServer world, ChunkProviderServer cps) throws Exception {
        Class<?> s = scheduler.getClass();
        require(
            s.getName()
                .equals(SCHEDULER),
            "Unreviewed scheduler override");
        schedulerSource = PopulationQuiescenceContract.certify(s);
        forDimension = s.getMethod("getForDimension", int.class);
        getStats = s.getMethod("getStats");
        getDepth = s.getMethod("getPopulationDepth");
        require(forDimension.invoke(null, 0) == scheduler, "Natural scheduler not registered dim0 owner");
        Object stats = getStats.invoke(scheduler);
        require(
            stats != null && stats.getClass()
                .getName()
                .equals(STATS),
            "Unreviewed stats owner");
        statsSource = PopulationQuiescenceContract.certify(stats.getClass());
        getters = new Method[GETTERS.length];
        for (int i = 0; i < GETTERS.length; i++) {
            getters[i] = stats.getClass()
                .getMethod(GETTERS[i]);
            require(getters[i].getReturnType() == int.class, "Public stats signature differs");
        }
        require(getDepth.getReturnType() == int.class, "Public depth signature differs");
        ownerScheduler = scheduler;
        ownerStats = stats;
        ownerWorld = world;
        ownerProvider = world.provider;
        ownerCps = cps;
        ownerThread = Thread.currentThread();
    }

    private static int tick() {
        return MinecraftServer.getServer()
            .getTickCounter();
    }

    private static int range() {
        return MinecraftServer.getServer()
            .getConfigurationManager()
            .getViewDistance();
    }

    private static int[] values() throws Exception {
        require(
            forDimension.invoke(null, 0) == ownerScheduler && getStats.invoke(ownerScheduler) == ownerStats,
            "Scheduler/stats owner changed");
        int[] result = new int[7];
        for (int i = 0; i < 6; i++) result[i] = ((Number) getters[i].invoke(ownerStats)).intValue();
        result[6] = ((Number) getDepth.invoke(ownerScheduler)).intValue();
        for (int v : result) require(v >= 0, "Negative native public value");
        return result;
    }

    public static void head(Object scheduler, WorldServer world, ChunkProviderServer cps, int actualRange) {
        if (outside(scheduler, false)) return;
        synchronized (HodgePopulationProbe.class) {
            long before = System.nanoTime();
            hookCalls++;
            try {
                if (!PopulationQuiescenceContract.selected()) return;
                if (expectedThread == null) return;
                if (Thread.currentThread() != expectedThread) {
                    if (scheduler == ownerScheduler) fail("Owner processTick on foreign thread");
                    return;
                }
                if (world == null || world.provider == null || world.provider.dimensionId != 0) return;
                guarded(() -> {
                    require(
                        world == DimensionManager.getWorld(0) && world.getChunkProvider() == cps,
                        "Natural dim0 world/cps registration differs");
                    if (ownerScheduler == null) bind(scheduler, world, cps);
                    require(
                        scheduler == ownerScheduler && world == ownerWorld
                            && cps == ownerCps
                            && world.provider == ownerProvider
                            && Thread.currentThread() == ownerThread,
                        "Dim0 owner replaced");
                    valid = false;
                    bump();
                    ownerHeads++;
                    require(inFlight == 0, "Reentrant owner processTick");
                    inFlight = 1;
                    headTick = tick();
                    headWorldTick = world.getTotalWorldTime();
                    viewDistance = actualRange;
                    require(
                        actualRange >= 0 && actualRange < Integer.MAX_VALUE && actualRange == range(),
                        "Native/public viewDistance differs");
                    return true;
                }, false);
            } finally {
                hookCost(before);
            }
        }
    }

    public static void returned(Object scheduler, WorldServer world, ChunkProviderServer cps, int actualRange) {
        if (outside(scheduler, false)) return;
        synchronized (HodgePopulationProbe.class) {
            long before = System.nanoTime();
            hookCalls++;
            try {
                if (!PopulationQuiescenceContract.selected()) return;
                if (scheduler != ownerScheduler) return;
                if (Thread.currentThread() != ownerThread) {
                    fail("Owner RETURN on foreign thread");
                    return;
                }
                if (world != ownerWorld || cps != ownerCps || world.provider != ownerProvider) {
                    fail("Owner RETURN world/provider differs");
                    return;
                }
                guarded(() -> {
                    require(inFlight == 1 && problem() == null, "Owner RETURN without intact outer HEAD");
                    require(
                        tick() == headTick && world.getTotalWorldTime() == headWorldTick
                            && actualRange == viewDistance
                            && actualRange == range(),
                        "Owner tick/range changed during processTick");
                    int[] snapshot = values();
                    require(sequence < Long.MAX_VALUE, "Publish sequence overflow");
                    publishedValues = snapshot;
                    publishedTick = headTick;
                    publishedWorldTick = headWorldTick;
                    publishedGeneration = generation;
                    sequence++;
                    ownerReturns++;
                    inFlight = 0;
                    valid = true;
                    return true;
                }, false);
            } finally {
                hookCost(before);
            }
        }
    }

    public static void mutation(Object scheduler) {
        if (outside(scheduler, false)) return;
        synchronized (HodgePopulationProbe.class) {
            long before = System.nanoTime();
            hookCalls++;
            try {
                if (!PopulationQuiescenceContract.selected() || scheduler != ownerScheduler || scheduler == null)
                    return;
                valid = false;
                bump();
                ownerMutations++;
                if (Thread.currentThread() != ownerThread) fail("Owner mutation on foreign thread");
            } finally {
                hookCost(before);
            }
        }
    }

    public static void clear() {
        if (outside(null, true)) return;
        synchronized (HodgePopulationProbe.class) {
            long before = System.nanoTime();
            hookCalls++;
            try {
                if (!PopulationQuiescenceContract.selected()) return;
                clears++;
                if (ownerScheduler != null) {
                    bump();
                    fail("Dimension scheduler owners cleared");
                }
            } finally {
                hookCost(before);
            }
        }
    }

    public static final class Loaded {

        public final int x, z;
        public final boolean terrain;

        public Loaded(int x, int z, boolean terrain) {
            this.x = x;
            this.z = z;
            this.terrain = terrain;
        }
    }

    public static final class Player {

        public final double x, z;
        public final int cx, cz;

        public Player(double x, double z) {
            require(
                Double.isFinite(x) && Double.isFinite(z)
                    && x >= Integer.MIN_VALUE
                    && x < Integer.MAX_VALUE
                    && z >= Integer.MIN_VALUE
                    && z < Integer.MAX_VALUE,
                "Invalid public player coordinate");
            this.x = x;
            this.z = z;
            cx = ((int) Math.floor(x)) >> 4;
            cz = ((int) Math.floor(z)) >> 4;
        }
    }

    private static long key(int x, int z) {
        return ((long) x & 0xffffffffL) | (((long) z & 0xffffffffL) << 32);
    }

    /** Same pure topology evaluator used by the actual public-source observation and necessary controls. */
    public static String topology(List<Loaded> loaded, int mapCount, List<Player> players, int distance,
        boolean sameObjects) {
        if (!sameObjects || loaded.size() > 24000
            || players.size() > 1024
            || mapCount != loaded.size()
            || distance < 0
            || distance == Integer.MAX_VALUE) return "PUBLIC_SET_INCOMPLETE";
        Set<Long> all = new HashSet<>();
        for (Loaded c : loaded) if (!all.add(key(c.x, c.z))) return "DUPLICATE_COORDINATE";
        for (Loaded c : loaded) if (!c.terrain) {
            boolean missing = false;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (dx != 0 || dz != 0) {
                long nx = (long) c.x + dx, nz = (long) c.z + dz;
                if (nx < Integer.MIN_VALUE || nx > Integer.MAX_VALUE
                    || nz < Integer.MIN_VALUE
                    || nz > Integer.MAX_VALUE) return "COORDINATE_OVERFLOW";
                if (!all.contains(key((int) nx, (int) nz))) missing = true;
            }
            if (!missing) return "FULL_NEIGHBORHOOD";
            for (Player p : players) if (Math.abs((long) c.x - p.cx) <= ((long) distance + 1)
                && Math.abs((long) c.z - p.cz) <= ((long) distance + 1)) return "PLAYER_FILL_RANGE";
        }
        return "ACCEPTED";
    }

    public static final class Observation {

        private final int[] values;
        private final List<Loaded> loaded;
        private final List<Player> players;
        private final boolean available, accepted;
        private final String reason;
        private final int currentTick, stampTick, range, mapCount;
        private final long currentWorldTick, stampWorldTick, generation, publishedGeneration, sequence;

        private Observation(int[] values, List<Loaded> loaded, List<Player> players, boolean available,
            boolean accepted, String reason, int currentTick, int stampTick, int range, int mapCount,
            long currentWorldTick, long stampWorldTick, long generation, long publishedGeneration, long sequence) {
            this.values = values == null ? null : values.clone();
            this.loaded = Collections.unmodifiableList(new ArrayList<>(loaded));
            this.players = Collections.unmodifiableList(new ArrayList<>(players));
            this.available = available;
            this.accepted = accepted;
            this.reason = reason;
            this.currentTick = currentTick;
            this.stampTick = stampTick;
            this.range = range;
            this.mapCount = mapCount;
            this.currentWorldTick = currentWorldTick;
            this.stampWorldTick = stampWorldTick;
            this.generation = generation;
            this.publishedGeneration = publishedGeneration;
            this.sequence = sequence;
        }

        public boolean accepted() {
            return accepted;
        }

        public JsonObject raw() {
            JsonObject j = new JsonObject();
            j.addProperty("available", available);
            j.addProperty("schedulerPresent", values != null);
            if (values != null) for (int i = 0; i < 4; i++) j.addProperty(RAW[i], values[i]);
            j.addProperty("scope", "Public Hodge snapshot; not all queues/IO empty");
            return j;
        }

        public JsonObject summary() {
            JsonObject j = new JsonObject();
            j.addProperty("available", available);
            j.addProperty("accepted", accepted);
            j.addProperty("reason", reason);
            j.addProperty("publishSequence", sequence);
            j.addProperty("currentServerTick", currentTick);
            j.addProperty("publishedServerTick", stampTick);
            j.addProperty("currentWorldTick", currentWorldTick);
            j.addProperty("publishedWorldTick", stampWorldTick);
            j.addProperty("generation", generation);
            j.addProperty("publishedGeneration", publishedGeneration);
            return j;
        }

        public JsonObject proof() {
            JsonObject j = summary();
            j.addProperty("schema", "effect-native-population-quiescence-proof-v1");
            j.add("quiescenceIdentity", PopulationQuiescenceContract.identity());
            j.addProperty("ownerToken", 1);
            j.addProperty("sameWorldProviderSchedulerStatsThread", accepted);
            j.addProperty("inFlight", 0);
            j.addProperty("viewDistance", range);
            j.addProperty("listCount", loaded.size());
            j.addProperty("mapCount", mapCount);
            j.addProperty("uniqueCount", loaded.size());
            j.addProperty("sameMapObjects", accepted);
            JsonObject v = new JsonObject();
            if (values != null) for (int i = 0; i < RAW.length; i++) v.addProperty(RAW[i], values[i]);
            j.add("publicValues", v);
            JsonArray l = new JsonArray();
            for (Loaded c : loaded) {
                JsonObject r = new JsonObject();
                r.addProperty("x", c.x);
                r.addProperty("z", c.z);
                r.addProperty("terrainPopulated", c.terrain);
                l.add(r);
            }
            j.add("loaded", l);
            JsonArray p = new JsonArray();
            for (Player e : players) {
                JsonObject r = new JsonObject();
                r.addProperty("x", e.x);
                r.addProperty("z", e.z);
                r.addProperty("chunkX", e.cx);
                r.addProperty("chunkZ", e.cz);
                p.add(r);
            }
            j.add("players", p);
            j.add("schedulerSource", schedulerSource);
            j.add("statsSource", statsSource);
            j.add("publicMapSource", publicMapSource);
            return j;
        }
    }

    /** Certifies only the native map already owned by the authenticated provider; never constructs or replaces one. */
    private static JsonObject certifyPublicMapClass(Class<?> type, ClassLoader nativeLoader) throws Exception {
        if (type == LongHashMap.class) {
            JsonObject source = new JsonObject();
            source.addProperty("className", type.getName());
            source.addProperty("mechanism", "EXACT_VANILLA_PUBLIC_MAP");
            return source;
        }
        require(
            type.getName()
                .equals("com.mitchej123.hodgepodge.util.ServerThreadLongHashMap")
                && type.getSuperclass()
                    .getName()
                    .equals("com.mitchej123.hodgepodge.util.FastUtilLongHashMap")
                && type.getSuperclass()
                    .getSuperclass() == LongHashMap.class
                && type.getClassLoader() == nativeLoader
                && type.getSuperclass()
                    .getClassLoader() == nativeLoader,
            "Unreviewed public map class/loader: " + type.getName());
        if (type == reviewedPublicMapClass) return reviewedPublicMapSource;
        JsonObject source = certifyPublicMapEntry(
            type,
            "0d8a7826ca625e9a63c023a4cb0fd265191e52e0187c090ca5143a5bdd8af6f7");
        source.add(
            "superclass",
            certifyPublicMapEntry(
                type.getSuperclass(),
                "1b635a4113f1a74f8db94b784522f227d99a4a8f2bf9f005637d6592c3f8704c"));
        source.addProperty("mechanism", "EXACT_SOURCE_ATTESTED_NATIVE_SERVER_THREAD_PUBLIC_MAP");
        source.addProperty(
            "readScope",
            "Public size/get on original authenticated server thread; no snapshot API or private data access");
        reviewedPublicMapClass = type;
        reviewedPublicMapSource = source;
        return source;
    }

    private static JsonObject certifyPublicMapEntry(Class<?> type, String expectedEntrySha256) throws Exception {
        java.nio.file.Path artifact = java.nio.file.Paths.get("mods/hodgepodge-2.7.221.jar")
            .toRealPath();
        java.nio.file.Path actual = com.tianluoqaq.tlmnh.common.development.PackagedClassResourceResolver
            .artifactOf(type);
        com.tianluoqaq.tlmnh.common.development.PackagedClassResourceResolver.Binding binding = com.tianluoqaq.tlmnh.common.development.PackagedClassResourceResolver
            .resolve(type, artifact);
        String expectedJar = PopulationQuiescenceContract.pinned()
            .getAsJsonObject("nativeArtifacts")
            .getAsJsonObject("mods/hodgepodge-2.7.221.jar")
            .get("sha256")
            .getAsString();
        require(
            actual.equals(artifact) && PopulationQuiescenceContract.sha(actual)
                .equals(expectedJar)
                && binding.physicalEntry.equals(
                    type.getName()
                        .replace('.', '/') + ".class")
                && binding.sha256.equals(expectedEntrySha256),
            "Public map packaged source differs: " + type.getName());
        JsonObject source = new JsonObject();
        source.addProperty("className", type.getName());
        source.addProperty("artifact", "mods/hodgepodge-2.7.221.jar");
        source.addProperty("artifactSha256", expectedJar);
        source.addProperty("physicalEntry", binding.physicalEntry);
        source.addProperty("classSha256", binding.sha256);
        source.addProperty("resourceMatchesCodeSource", true);
        source.addProperty("transformedDefinitionBytesVerified", false);
        return source;
    }

    /** Historical un-overlaid four-zero getter semantics; optional provider resolution remains guarded and lazy. */
    public static synchronized JsonObject legacySnapshot() {
        JsonObject result = guarded(() -> {
            JsonObject row = new JsonObject();
            Class<?> scheduler = Class.forName(SCHEDULER);
            Object existing = scheduler.getMethod("getForDimension", int.class)
                .invoke(null, 0);
            row.addProperty("available", true);
            row.addProperty("schedulerPresent", existing != null);
            if (existing == null) for (int i = 0; i < 4; i++) row.addProperty(RAW[i], 0);
            else {
                Object stats = scheduler.getMethod("getStats")
                    .invoke(existing);
                for (int i = 0; i < 4; i++) row.addProperty(
                    RAW[i],
                    (Number) stats.getClass()
                        .getMethod(GETTERS[i])
                        .invoke(stats));
            }
            row.addProperty("scope", "Public Hodge snapshot only; not all third-party queues empty");
            return row;
        }, null);
        if (result == null) {
            result = new JsonObject();
            result.addProperty("available", false);
        }
        return result;
    }

    public static synchronized Observation inspect(WorldServer world, Thread thread, Object[] existingLoaded) {
        final List<Loaded> loaded = new ArrayList<>();
        final List<Player> players = new ArrayList<>();
        int now = -1;
        long worldNow = -1;
        int[] raw = null;
        String reason = "NO_CURRENT_PUBLICATION";
        int count = -1;
        if (problem() == null && ownerScheduler != null
            && Thread.currentThread() == ownerThread
            && thread == ownerThread
            && world == ownerWorld
            && world.provider == ownerProvider
            && world.getChunkProvider() == ownerCps
            && world == DimensionManager.getWorld(0)) {
            raw = guarded(() -> values(), null);
            now = tick();
            worldNow = world.getTotalWorldTime();
            if (raw != null && valid
                && inFlight == 0
                && generation == publishedGeneration
                && now == publishedTick
                && worldNow == publishedWorldTick
                && Arrays.equals(raw, publishedValues)) {
                final int[] counters = raw;
                String result = guarded(() -> {
                    require(
                        existingLoaded.length <= 24000 && world.playerEntities.size() <= 1024,
                        "Original topology capacity exceeded");
                    require(
                        ownerCps.getClass() == ChunkProviderServer.class,
                        "Unreviewed public provider override: " + ownerCps.getClass()
                            .getName());
                    publicMapSource = certifyPublicMapClass(
                        ownerCps.loadedChunkHashMap.getClass(),
                        ownerScheduler.getClass()
                            .getClassLoader());
                    int before = ownerCps.loadedChunkHashMap.getNumHashElements();
                    for (Object item : existingLoaded) {
                        require(item instanceof Chunk, "Non-chunk public loaded entry");
                        Chunk c = (Chunk) item;
                        require(
                            c.worldObj == world
                                && ownerCps.loadedChunkHashMap.getValueByKey(key(c.xPosition, c.zPosition)) == c,
                            "Public list/map strong identity differs");
                        loaded.add(new Loaded(c.xPosition, c.zPosition, c.isTerrainPopulated));
                    }
                    for (Object item : world.playerEntities.toArray()) {
                        require(
                            item instanceof EntityPlayer && ((EntityPlayer) item).worldObj == world,
                            "Unknown public player entry");
                        EntityPlayer e = (EntityPlayer) item;
                        players.add(new Player(e.posX, e.posZ));
                    }
                    require(
                        before == ownerCps.loadedChunkHashMap.getNumHashElements(),
                        "Native map changed during read");
                    loaded.sort((a, b) -> a.x == b.x ? Integer.compare(a.z, b.z) : Integer.compare(a.x, b.x));
                    players.sort((a, b) -> a.x == b.x ? Double.compare(a.z, b.z) : Double.compare(a.x, b.x));
                    require(range() == viewDistance, "Public range changed after publish");
                    if (counters[0] != 0 || counters[3] != 0
                        || counters[4] != 0
                        || counters[5] != 0
                        || counters[6] != 0) return "NATIVE_ACTIVITY";
                    return topology(loaded, before, players, viewDistance, true);
                }, "PUBLIC_SOURCE_UNAVAILABLE");
                reason = result;
                count = ownerCps.loadedChunkHashMap.getNumHashElements();
            } else reason = "STALE_OR_MUTATED_PUBLICATION";
        }
        if (problem() != null) reason = "PROBE_FAILURE: " + problem();
        return new Observation(
            raw,
            loaded,
            players,
            raw != null,
            "ACCEPTED".equals(reason),
            reason,
            now,
            publishedTick,
            viewDistance,
            count,
            worldNow,
            publishedWorldTick,
            generation,
            publishedGeneration,
            sequence);
    }

    public static boolean verifyProofGeometry(JsonObject proof) {
        try {
            List<Loaded> loaded = new ArrayList<>();
            for (JsonElement e : proof.getAsJsonArray("loaded")) {
                JsonObject c = e.getAsJsonObject();
                loaded.add(
                    new Loaded(
                        c.get("x")
                            .getAsInt(),
                        c.get("z")
                            .getAsInt(),
                        c.get("terrainPopulated")
                            .getAsBoolean()));
            }
            List<Player> players = new ArrayList<>();
            for (JsonElement e : proof.getAsJsonArray("players")) {
                JsonObject p = e.getAsJsonObject();
                Player v = new Player(
                    p.get("x")
                        .getAsDouble(),
                    p.get("z")
                        .getAsDouble());
                if (v.cx != p.get("chunkX")
                    .getAsInt() || v.cz
                        != p.get("chunkZ")
                            .getAsInt())
                    return false;
                players.add(v);
            }
            if (!proof.get("available")
                .getAsBoolean()
                || proof.get("publishSequence")
                    .getAsLong() <= 0
                || proof.get("generation")
                    .getAsLong() < 1
                || proof.get("currentServerTick")
                    .getAsInt() < 0
                || proof.get("currentWorldTick")
                    .getAsLong() < 0
                || !proof.get("accepted")
                    .getAsBoolean()
                || !proof.get("sameWorldProviderSchedulerStatsThread")
                    .getAsBoolean()
                || !proof.get("sameMapObjects")
                    .getAsBoolean()
                || proof.get("listCount")
                    .getAsInt() != loaded.size()
                || proof.get("uniqueCount")
                    .getAsInt() != loaded.size()
                || proof.get("inFlight")
                    .getAsInt() != 0
                || proof.get("currentServerTick")
                    .getAsInt()
                    != proof.get("publishedServerTick")
                        .getAsInt()
                || proof.get("currentWorldTick")
                    .getAsLong()
                    != proof.get("publishedWorldTick")
                        .getAsLong()
                || proof.get("generation")
                    .getAsLong()
                    != proof.get("publishedGeneration")
                        .getAsLong())
                return false;
            for (String field : new String[] { "genQueue", "popCount", "terrainGenCount", "neighborFills",
                "populationDepth" })
                if (proof.getAsJsonObject("publicValues")
                    .get(field)
                    .getAsInt() != 0) return false;
            return "ACCEPTED".equals(
                topology(
                    loaded,
                    proof.get("mapCount")
                        .getAsInt(),
                    players,
                    proof.get("viewDistance")
                        .getAsInt(),
                    true));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    /** Only the already-reviewed physical native public cache getter; never loadClass/findClass/Class.forName. */
    private static Class<?> publicCachedScheduler(ClassLoader receiver, String role, JsonArray rows) {
        JsonObject row = new JsonObject();
        rows.add(row);
        row.addProperty("role", role);
        row.addProperty(
            "receiverClass",
            receiver == null ? null
                : receiver.getClass()
                    .getName());
        row.addProperty("receiverIdentity", receiver == null ? 0 : System.identityHashCode(receiver));
        row.addProperty("available", false);
        row.addProperty("probeReferenceSame", false);
        row.addProperty("targetPresent", false);
        if (receiver == null || receiver.getClass() != net.minecraft.launchwrapper.LaunchClassLoader.class) {
            row.addProperty("reason", "UNREVIEWED_RECEIVER");
            return null;
        }
        try {
            Class<?> type = receiver.getClass();
            java.nio.file.Path expected = java.nio.file.Paths.get("lwjgl3ify-forgePatches.jar")
                .toRealPath();
            java.nio.file.Path artifact = com.tianluoqaq.tlmnh.common.development.PackagedClassResourceResolver
                .artifactOf(type);
            com.tianluoqaq.tlmnh.common.development.PackagedClassResourceResolver.Binding binding = com.tianluoqaq.tlmnh.common.development.PackagedClassResourceResolver
                .resolve(type, expected);
            if (!artifact.equals(expected) || !PopulationQuiescenceContract.sha(artifact)
                .equals("4a772fa88485448ecd8b6a82b426523b2058b6e901d2d766e8d9f62113fde73e")
                || !binding.physicalEntry.equals("net/minecraft/launchwrapper/LaunchClassLoader.class")
                || !binding.sha256.equals("4a45120f1a2e82e92486c264db11dddcfa3571c505778dda552c9c9ee6e59ed3")) {
                row.addProperty("reason", "UNREVIEWED_CACHE_SOURCE");
                return null;
            }
            Method method = type.getMethod("findCachedClass", String.class);
            if (method.getDeclaringClass() != type || method.getReturnType() != Class.class
                || !java.lang.reflect.Modifier.isPublic(method.getModifiers())
                || java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                row.addProperty("reason", "UNREVIEWED_CACHE_OVERRIDE");
                return null;
            }
            row.addProperty("loaderArtifactSha256", "4a772fa88485448ecd8b6a82b426523b2058b6e901d2d766e8d9f62113fde73e");
            row.addProperty("loaderClassSha256", binding.sha256);
            row.addProperty("available", true);
            row.addProperty(
                "probeReferenceSame",
                method.invoke(receiver, HodgePopulationProbe.class.getName()) == HodgePopulationProbe.class);
            Object raw = method.invoke(receiver, SCHEDULER);
            if (raw == null) {
                row.addProperty("reason", "ABSENT_FROM_PUBLIC_CACHE");
                return null;
            }
            if (!(raw instanceof Class) || !((Class<?>) raw).getName()
                .equals(SCHEDULER)) {
                row.addProperty("available", false);
                row.addProperty("reason", "CACHE_CLASS_NAME_DIFFERS");
                return null;
            }
            Class<?> found = (Class<?>) raw;
            ClassLoader defining = found.getClassLoader();
            row.addProperty(
                "definingLoaderClass",
                defining == null ? null
                    : defining.getClass()
                        .getName());
            row.addProperty("definingLoaderIdentity", defining == null ? 0 : System.identityHashCode(defining));
            if (defining != receiver) {
                row.addProperty("available", false);
                row.addProperty("reason", "CACHE_DEFINING_LOADER_DIFFERS");
                return null;
            }
            row.addProperty("targetPresent", true);
            row.addProperty("targetClass", found.getName());
            row.addProperty("targetClassIdentity", System.identityHashCode(found));
            row.addProperty("reason", "EXISTING_CLASS_RETURNED");
            return found;
        } catch (Exception | LinkageError | AssertionError problem) {
            IntegrationGuard.throwIfFatal(problem);
            row.addProperty("available", false);
            row.addProperty("reason", problem.toString());
            return null;
        }
    }

    private static boolean verifyCachedDefinition(Class<?> found, JsonObject proof) {
        if (found == null) return false;
        try {
            JsonObject expected = PopulationQuiescenceContract.pinned()
                .getAsJsonObject("nativeArtifacts")
                .getAsJsonObject("mods/hodgepodge-2.7.221.jar");
            String entry = "com/mitchej123/hodgepodge/mixins/hooks/ChunkGenScheduler.class";
            java.nio.file.Path artifact = java.nio.file.Paths.get("mods/hodgepodge-2.7.221.jar")
                .toRealPath();
            java.nio.file.Path actual = com.tianluoqaq.tlmnh.common.development.PackagedClassResourceResolver
                .artifactOf(found);
            com.tianluoqaq.tlmnh.common.development.PackagedClassResourceResolver.Binding binding = com.tianluoqaq.tlmnh.common.development.PackagedClassResourceResolver
                .resolve(found, artifact);
            if (!found.getName()
                .equals(SCHEDULER) || !actual.equals(artifact)
                || !PopulationQuiescenceContract.sha(actual)
                    .equals(
                        expected.get("sha256")
                            .getAsString())
                || !binding.physicalEntry.equals(entry)
                || !binding.sha256.equals(
                    expected.getAsJsonObject("entries")
                        .get(entry)
                        .getAsString())) {
                proof.addProperty("sourceFailure", "Cached scheduler packaged source mismatch");
                return false;
            }
            proof.remove("sourceFailure");
            proof.addProperty("mechanism", "EXISTING_PUBLIC_CACHE_CLASS_WITH_PACKAGED_SOURCE");
            proof.addProperty("className", found.getName());
            proof.addProperty("classIdentity", System.identityHashCode(found));
            proof.addProperty("codeSource", actual.toString());
            proof.addProperty(
                "artifactSha256",
                expected.get("sha256")
                    .getAsString());
            proof.addProperty("physicalEntry", binding.physicalEntry);
            proof.addProperty("classSha256", binding.sha256);
            proof.addProperty("resourceMatchesCodeSource", true);
            proof.addProperty("transformedDefinitionBytesVerified", false);
            proof.addProperty(
                "definingLoaderClass",
                found.getClassLoader() == null ? null
                    : found.getClassLoader()
                        .getClass()
                        .getName());
            proof.addProperty(
                "definingLoaderIdentity",
                found.getClassLoader() == null ? 0 : System.identityHashCode(found.getClassLoader()));
            return true;
        } catch (Exception | LinkageError | AssertionError problem) {
            IntegrationGuard.throwIfFatal(problem);
            proof.addProperty("sourceFailure", problem.toString());
            return false;
        }
    }

    /** Explicit bounded observation for root's existing env-only JVM; never enables a target hook. */
    public static synchronized void retainDefaultOffControl() {
        if (!Boolean.getBoolean("tlmnh.qaPopulationQuiescenceObserveOff")
            && !PopulationQuiescenceContract.REVISION.equals(System.getenv("TLMNH_QA_POPULATION_OBSERVE_OFF"))) return;
        try {
            boolean tracked = org.spongepowered.asm.service.MixinService.getService()
                .getClassTracker()
                .isClassLoaded(SCHEDULER);
            JsonArray cacheRows = new JsonArray();
            JsonObject definition = new JsonObject();
            Class<?> launchCached = publicCachedScheduler(
                net.minecraft.launchwrapper.Launch.classLoader,
                "launch",
                cacheRows);
            Class<?> probeCached = publicCachedScheduler(
                HodgePopulationProbe.class.getClassLoader(),
                "probe",
                cacheRows);
            boolean loaded = verifyCachedDefinition(launchCached, definition);
            if (probeCached != launchCached) loaded |= verifyCachedDefinition(probeCached, definition);
            int mods = 0;
            for (cpw.mods.fml.common.ModContainer mod : cpw.mods.fml.common.Loader.instance()
                .getActiveModList()) if ("tlmnhqapilot".equals(mod.getModId())) mods++;
            JsonObject j = status();
            j.addProperty("schema", "qa-population-quiescence-default-off-control-v1");
            j.addProperty("classTrackerNaturallyDefined", tracked);
            j.addProperty("targetNaturallyDefined", loaded);
            j.add("publicCacheReceivers", cacheRows);
            j.add("naturalDefinition", definition);
            j.addProperty("qaModDiscoveries", mods);
            java.nio.file.Path own = com.tianluoqaq.tlmnh.common.development.PackagedClassResourceResolver
                .artifactOf(HodgePopulationProbe.class);
            j.addProperty("qaCodeSource", own.toString());
            j.addProperty("qaSha256", PopulationQuiescenceContract.sha(own));
            j.addProperty(
                "passed",
                loaded && mods == 1
                    && !PopulationQuiescenceContract.requested()
                    && !PopulationQuiescenceContract.selected()
                    && hookCalls == 0);
            j.addProperty("methodAdmission", false);
            java.nio.file.Files.write(
                java.nio.file.Paths.get(System.getProperty("horizonqa.reportDir", ""))
                    .resolve("population-quiescence-default-off.json"),
                new GsonBuilder().serializeNulls()
                    .setPrettyPrinting()
                    .create()
                    .toJson(j)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.CREATE_NEW);
        } catch (Exception | LinkageError | AssertionError problem) {
            IntegrationGuard.throwIfFatal(problem);
            System.err.println("TLMNH QA default-off control unavailable: " + problem);
        }
    }

    public static synchronized JsonObject status() {
        JsonObject j = PopulationQuiescenceContract.selection();
        j.addProperty("hookCalls", hookCalls + OUTSIDE_CALLS.get());
        j.addProperty("unpaidHookCalls", unpaidHookCalls + OUTSIDE_UNPAID_CALLS.get());
        j.addProperty("unpaidHookWallNanos", unpaidHookWallNanos + OUTSIDE_UNPAID_NANOS.get());
        j.addProperty("hookWallNanos", hookWallNanos + OUTSIDE_NANOS.get());
        j.addProperty("ownerHeads", ownerHeads);
        j.addProperty("ownerReturns", ownerReturns);
        j.addProperty("ownerMutations", ownerMutations);
        j.addProperty("clears", clears);
        j.addProperty("failure", problem());
        j.add("schedulerSource", schedulerSource);
        j.add("statsSource", statsSource);
        j.add("publicMapSource", publicMapSource);
        j.addProperty("publishSequence", sequence);
        j.addProperty("publishedServerTick", publishedTick);
        j.addProperty("publishedWorldTick", publishedWorldTick);
        j.addProperty("inFlight", inFlight);
        j.addProperty("ownerToken", ownerScheduler == null ? 0 : 1);
        j.addProperty("valid", valid);
        return j;
    }
}
