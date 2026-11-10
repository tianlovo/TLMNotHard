package com.tianluoqaq.tlmnh.qa;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.List;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnewhorizons.horizonqa.internal.GameTestInstance;
import com.gtnewhorizons.horizonqa.internal.GameTestRunner;

import cpw.mods.fml.common.FMLCommonHandler;

/** 仅原真空边界的固定自然保存窗口；不拥有保存、实体、时钟或区块状态。 */
final class EffectSaveBoundary implements AutoCloseable {

    static final String REVISION = "v6-native-save-empty-boundary-v1";
    static final String DESIGN = "7f465a00275968cd75dc9f8cb8b416455e2be5d368e31cab3b6c29ee3254fcb4";
    static final String LEDGER = "1f676be37d7ae613cc4f16445442890cac68f6df11ea8557ccccac3e03687358";
    static final int TIMEOUT = 13000, NON_WAIT_LIMIT = 8000, PERIOD = 900;
    private static final int SAVE_LIMIT = 16, BOUNDARY_LIMIT = 19;
    private final MinecraftServer server;
    private final WorldServer world;
    private final EffectEmptyWorldFixture fixture;
    private final Thread owner = Thread.currentThread();
    private final EffectArrowRandomInput.StackBridge stack = new EffectArrowRandomInput.StackBridge();
    private final MethodCost methodCost;
    private final ThreadMXBean clock;
    private final JsonArray saves = new JsonArray(), boundaries = new JsonArray(), launches = new JsonArray();
    private final JsonObject sources;
    final Window window = new Window();
    private JsonObject currentWait, pendingSave;
    private long pendingSaveTick = -1, observedWall, observedCpu, observedCalls;
    private boolean firstMetadata, closed;

    EffectSaveBoundary(MinecraftServer server, WorldServer world, EffectEmptyWorldFixture fixture,
        MethodCost methodCost) {
        require(fixture != null, "Save-window protocol requires the authenticated owned empty-world fixture");
        this.server = server;
        this.world = world;
        this.fixture = fixture;
        this.methodCost = methodCost;
        clock = methodCost.clock;
        sources = fixture.saveWindowSources();
    }

    static int reservation(int next) {
        require(next >= -6 && next < 12, "No reservation for nonexistent case");
        return next < -2 ? 89 : next == -2 ? 41 : next == -1 ? 169 : 489;
    }

    void end() {
        measure(() -> {
            checkOwner();
            List<EffectArrowRandomInput.Frame> frames = stack.capture();
            requireEndStack(frames);
            long tick = server.getTickCounter();
            window.end(tick, pendingSaveTick);
            if (pendingSave != null) {
                pendingSave.addProperty("completeEndServerTick", tick);
                pendingSave.addProperty("sameTickSaveReturnedBeforeHqaCallback", true);
                pendingSave.add("completeEndSource", fingerprints(frames));
            }
            pendingSave = null;
            pendingSaveTick = -1;
        });
    }

    void save(net.minecraft.world.World actual, boolean noTrial, boolean previousCleanupSucceeded) {
        if (actual != world) return;
        measure(() -> {
            checkOwner();
            long tick = server.getTickCounter();
            require(tick > 0 && tick % PERIOD == 0 && pendingSaveTick < 0, "Unscheduled or duplicate owner-world save");
            require(
                window.lastTick < 0 || tick == window.lastTick + 1,
                "Save tick does not follow the observed server counter");
            require(
                noTrial && previousCleanupSucceeded && !window.running && !window.admitted,
                "Native save crossed a complete owned case or its original drain");
            List<EffectArrowRandomInput.Frame> frames = stack.capture();
            requireSaveStack(frames);
            JsonObject empty = EffectArrowRandomInput.emptyBoundary(world, previousCleanupSucceeded, false);
            require(
                empty.get("allowed")
                    .getAsBoolean()
                    && !empty.get("apiCalled")
                        .getAsBoolean(),
                "Actual native save was not actor/lease/budget empty");
            require(saves.size() < SAVE_LIMIT, "Save-window evidence overflow");
            pendingSave = new JsonObject();
            pendingSave.addProperty("serverTick", tick);
            pendingSave.addProperty("callbackOrdinalBeforeSave", window.callbacks);
            pendingSave.addProperty("sameWorldObject", actual == world);
            pendingSave.addProperty("sameOwnerThread", Thread.currentThread() == owner);
            pendingSave.add("source", fingerprints(frames));
            pendingSave.add("actualSaveReadOnlyEmpty", empty);
            pendingSave.add("authenticatedWorldLifecycleMetadata", fixture.saveWindowMetadata());
            saves.add(pendingSave);
            pendingSaveTick = tick;
        });
    }

    boolean admit(int next, JsonObject empty, Throwable asynchronousFailure) {
        final boolean[] admitted = { false };
        measure(() -> {
            checkOwner();
            boolean allowed = empty.has("allowed") && empty.get("allowed")
                .getAsBoolean()
                && empty.has("apiCalled")
                && !empty.get("apiCalled")
                    .getAsBoolean();
            int reserved = reservation(next);
            boolean wasWaiting = window.waiting();
            admitted[0] = window.admit(server.getTickCounter(), reserved, allowed, asynchronousFailure);
            if (!firstMetadata) {
                sources.add("firstAuthenticatedBoundaryMetadata", fixture.saveWindowMetadata());
                firstMetadata = true;
            }
            if (!admitted[0] && !wasWaiting) {
                require(boundaries.size() < BOUNDARY_LIMIT, "Save-window boundary evidence overflow");
                currentWait = new JsonObject();
                currentWait.addProperty("nextIndex", next);
                currentWait.addProperty("reservation", reserved);
                currentWait.addProperty("enterServerTick", window.waitEntered);
                currentWait.addProperty("enterPhase", window.waitEntered % PERIOD);
                currentWait.addProperty("nextSaveTick", window.expectedSave);
                currentWait.addProperty("reason", "ONLY_INSUFFICIENT_SAVE_WINDOW");
                currentWait.addProperty("enterCallbackOrdinal", window.callbacks);
                boundaries.add(currentWait);
            }
            if (admitted[0] && wasWaiting) {
                require(
                    currentWait != null && window.confirmedSave == server.getTickCounter(),
                    "Wait ended without the fixed next natural save and complete END");
                currentWait.addProperty("endServerTick", server.getTickCounter());
                currentWait.addProperty("endCallbackOrdinal", window.callbacks);
                currentWait.addProperty("certifiedEmptyWaitTicks", server.getTickCounter() - window.waitEntered);
                currentWait.addProperty("allTicksCheckpointAndReadOnlyEmptyPassed", true);
                currentWait = null;
            }
            window.audit();
        });
        return admitted[0];
    }

    void finalBoundary(JsonObject empty) {
        require(
            !window.waiting() && empty.get("allowed")
                .getAsBoolean()
                && !empty.get("apiCalled")
                    .getAsBoolean(),
            "Final boundary is not read-only empty");
        window.audit();
    }

    void returnedToBoundary() {
        window.boundary();
    }

    JsonObject launch(int next, JsonObject cleared) {
        window.launch(
            reservation(next),
            cleared.get("allowed")
                .getAsBoolean()
                && cleared.get("apiCalled")
                    .getAsBoolean());
        require(launches.size() < 18, "Case launch evidence overflow");
        JsonObject row = new JsonObject();
        row.addProperty("nextIndex", next);
        row.addProperty("serverTick", window.lastTick);
        row.addProperty("phase", window.lastTick % PERIOD);
        row.addProperty("reservation", reservation(next));
        row.addProperty("callbackOrdinal", window.callbacks);
        row.addProperty("latestCompleteNaturalSaveTick", window.confirmedSave);
        row.add("singleAppliedEmptyBoundary", cleared);
        launches.add(row);
        return row;
    }

    JsonObject snapshot() {
        JsonObject row = new JsonObject();
        row.addProperty("revision", REVISION);
        row.addProperty("designSha256", DESIGN);
        row.addProperty("sourceLedgerSha256", LEDGER);
        row.addProperty("originalHqaTimeoutTicks", NON_WAIT_LIMIT);
        row.addProperty("hqaTimeoutTicks", TIMEOUT);
        row.addProperty("scheduledSavePeriod", PERIOD);
        row.addProperty("callbackOrdinal", window.callbacks);
        row.addProperty(
            "localTickScope",
            "Observed original HQA onEachTick callback ordinal; private GameTestHelper instance is not read");
        row.addProperty("hqaInstanceLocalTick", "NOT_EXPOSED_BY_PUBLIC_HELPER_API");
        row.addProperty("conservativeRegistrationTick", 1);
        row.addProperty("firstServerTick", window.firstTick);
        row.addProperty("lastServerTick", window.lastTick);
        row.addProperty("certifiedEmptyWaitTicks", window.waitTicks);
        row.addProperty("nonWaitTicksIncludingRegistration", window.callbacks + 1 - window.waitTicks);
        row.addProperty("nonWaitTickLimit", NON_WAIT_LIMIT);
        row.addProperty("nonWaitTickAuditPassed", window.callbacks + 1 - window.waitTicks <= NON_WAIT_LIMIT);
        row.addProperty("waiting", window.waiting());
        row.add("sourceBindings", sources);
        row.add("naturalSaves", saves);
        row.add("emptyWaits", boundaries);
        row.add("caseLaunches", launches);
        row.addProperty("observerCalls", observedCalls);
        row.addProperty("observerCpuNanos", observedCpu);
        row.addProperty("observerWallNanos", observedWall);
        row.add("wholeMethodCosts", methodCost.snapshot());
        row.addProperty(
            "costScope",
            "Whole-method CPU/wall/GC include native saves and empty waits; original per-case and 400-frame costs retained without subtraction");
        row.addProperty(
            "performanceAdmission",
            "Activity windows are scheduled away from saves; no formal performance conclusion or cross-revision cost ranking");
        return row;
    }

    private void checkOwner() {
        require(
            !closed && Thread.currentThread() == owner
                && MinecraftServer.getServer() == server
                && net.minecraftforge.common.DimensionManager.getWorld(world.provider.dimensionId) == world,
            "Save-window world/server/thread identity changed");
    }

    private void measure(Runnable operation) {
        long wall = System.nanoTime(), cpu = clock.getCurrentThreadCpuTime();
        observedCalls++;
        try {
            operation.run();
        } finally {
            long after = clock.getCurrentThreadCpuTime();
            observedWall += System.nanoTime() - wall;
            if (cpu >= 0 && after >= cpu) observedCpu += after - cpu;
        }
    }

    private static JsonArray gcSnapshot() {
        JsonArray result = new JsonArray();
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            JsonObject row = new JsonObject();
            row.addProperty("name", bean.getName());
            row.addProperty("collectionCount", bean.getCollectionCount());
            row.addProperty("collectionTimeMillis", bean.getCollectionTime());
            result.add(row);
        }
        return result;
    }

    private static JsonArray fingerprints(List<EffectArrowRandomInput.Frame> frames) {
        JsonArray row = new JsonArray();
        for (EffectArrowRandomInput.Frame frame : frames)
            row.add(new com.google.gson.JsonPrimitive(frame.fingerprint()));
        return row;
    }

    private static boolean method(EffectArrowRandomInput.Frame frame, Class<?> type, String named, String srg,
        String descriptor) {
        return frame.type == type && (frame.method.equals(named) || frame.method.equals(srg))
            && frame.descriptor.equals(descriptor);
    }

    private static int unique(List<EffectArrowRandomInput.Frame> frames, Class<?> type, String named, String srg,
        String descriptor) {
        int found = -1;
        for (int i = 0; i < frames.size(); i++) if (method(frames.get(i), type, named, srg, descriptor)) {
            require(found < 0, "Duplicate reviewed save/end frame");
            found = i;
        }
        require(found >= 0, "Missing reviewed save/end frame: " + type.getName() + "#" + named);
        return found;
    }

    private static void requireSaveStack(List<EffectArrowRandomInput.Frame> frames) {
        int world = unique(
            frames,
            WorldServer.class,
            "saveAllChunks",
            "func_73044_a",
            "(ZLnet/minecraft/util/IProgressUpdate;)V");
        int server = unique(frames, MinecraftServer.class, "saveAllWorlds", "func_71267_a", "(Z)V");
        int tick = unique(frames, MinecraftServer.class, "tick", "func_71217_p", "()V");
        int run = unique(frames, MinecraftServer.class, "run", "run", "()V");
        require(server == world + 1 && tick == server + 2 && run > tick, "Unknown native scheduled-save caller chain");
        EffectArrowRandomInput.Frame redirect = frames.get(server + 1);
        require(
            redirect.type == MinecraftServer.class && redirect.method.startsWith("redirect$")
                && redirect.method.endsWith("$horizonqa$gametest$skipScheduledWorldSave")
                && redirect.descriptor.equals("(Lnet/minecraft/server/MinecraftServer;Z)V"),
            "Unknown scheduled-save redirect");
    }

    private static void requireEndStack(List<EffectArrowRandomInput.Frame> frames) {
        int end = unique(frames, GameTestInstance.class, "tickEnd", "tickEnd", "()V");
        int doEnd = unique(frames, GameTestRunner.class, "doTickEnd", "doTickEnd", "()V");
        int handle = unique(frames, GameTestRunner.class, "handleTickEnd", "handleTickEnd", "()V");
        int post = unique(frames, FMLCommonHandler.class, "onPostServerTick", "onPostServerTick", "()V");
        int tick = unique(frames, MinecraftServer.class, "tick", "func_71217_p", "()V");
        int run = unique(frames, MinecraftServer.class, "run", "run", "()V");
        require(
            doEnd == end + 1 && handle == doEnd + 1 && post == handle + 2 && tick == post + 1 && run > tick,
            "Unknown complete-END callback chain");
        EffectArrowRandomInput.Frame injection = frames.get(handle + 1);
        require(
            injection.type == FMLCommonHandler.class && injection.method.startsWith("handler$")
                && injection.method.endsWith("$horizonqa$gametest$afterTickEnd")
                && injection.descriptor.equals("(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V"),
            "Unknown END injection");
        for (EffectArrowRandomInput.Frame f : frames) require(
            !method(f, WorldServer.class, "saveAllChunks", "func_73044_a", "(ZLnet/minecraft/util/IProgressUpdate;)V")
                && !method(f, MinecraftServer.class, "saveAllWorlds", "func_71267_a", "(Z)V"),
            "Save has not returned at END");
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        methodCost.close();
    }

    /** 从原GameTest方法入口至最终真空边界；包括preflight、readiness、保存和所有等待。 */
    static final class MethodCost implements AutoCloseable {

        final ThreadMXBean clock = ManagementFactory.getThreadMXBean();
        final long wall = System.nanoTime(), cpu;
        final long processCpu;
        final boolean restore;
        final JsonArray gc;
        boolean closed;

        MethodCost() {
            require(clock.isCurrentThreadCpuTimeSupported(), "Whole-method CPU clock unavailable");
            restore = !clock.isThreadCpuTimeEnabled();
            if (restore) clock.setThreadCpuTimeEnabled(true);
            cpu = clock.getCurrentThreadCpuTime();
            require(cpu >= 0, "Whole-method CPU reading unavailable");
            gc = gcSnapshot();
            processCpu = processCpu();
        }

        JsonObject snapshot() {
            JsonObject row = new JsonObject();
            row.addProperty("serverThreadCpuNanos", clock.getCurrentThreadCpuTime() - cpu);
            row.addProperty("wallNanos", System.nanoTime() - wall);
            long currentProcessCpu = processCpu();
            row.addProperty(
                "processCpuNanos",
                processCpu >= 0 && currentProcessCpu >= processCpu ? currentProcessCpu - processCpu : -1);
            row.add("gcStart", gc);
            row.add("gcEnd", gcSnapshot());
            row.addProperty(
                "scope",
                "Original GameTest method entry through this snapshot; server thread CPU includes native tick work, saves and waits; GC is process-wide cumulative start/end");
            return row;
        }

        private static long processCpu() {
            java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
            return bean instanceof com.sun.management.OperatingSystemMXBean
                ? ((com.sun.management.OperatingSystemMXBean) bean).getProcessCpuTime()
                : -1;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (restore) clock.setThreadCpuTimeEnabled(false);
        }
    }

    /** 纯算术/状态接缝；原生counter/保存栈/真空证据必须由外层实际取得。 */
    static final class Window {

        long firstTick = -1, lastTick = -1, callbacks, waitTicks, waitEntered = -1, expectedSave = -1,
            confirmedSave = -1;
        int reserved;
        long launchTick = -1, boundaryDeadline = -1;
        boolean admitted, running;

        void end(long tick, long saveTick) {
            require(
                tick >= 0 && (lastTick < 0 || tick == lastTick + 1),
                "Server counter repeated, rolled back or jumped");
            require(
                tick % PERIOD == 0 ? saveTick == tick : saveTick < 0,
                "Missing, wrong-tick or unreturned natural save");
            if (firstTick < 0) firstTick = tick;
            lastTick = tick;
            callbacks++;
            require(!running || tick <= boundaryDeadline, "Original complete-case/drain reservation exceeded");
            if (saveTick >= 0) confirmedSave = saveTick;
            if (waiting()) require(tick <= expectedSave, "Missed fixed next-save boundary");
            audit();
        }

        boolean admit(long tick, int span, boolean empty, Throwable asynchronousFailure) {
            if (asynchronousFailure != null)
                throw new IllegalStateException("Native observer failed", asynchronousFailure);
            require(empty && tick == lastTick, "No authenticated read-only empty END");
            require(!admitted && !running, "Repeated admission or actor present at empty wait");
            require(span == 89 || span == 41 || span == 169 || span == 489, "Unknown case reservation");
            if (waiting()) {
                require(span == reserved && tick <= expectedSave, "Pending case/reservation changed");
                if (tick == expectedSave) {
                    require(confirmedSave == tick, "Fixed next save has no complete END witness");
                    expectedSave = -1;
                    admitted = true;
                    return true;
                }
            } else if (tick % PERIOD + span < PERIOD) {
                reserved = span;
                admitted = true;
                return true;
            } else {
                waitEntered = tick;
                expectedSave = tick + PERIOD - tick % PERIOD;
                reserved = span;
            }
            require(expectedSave > tick && expectedSave - waitEntered < PERIOD, "Unbounded empty wait");
            waitTicks++;
            audit();
            return false;
        }

        boolean waiting() {
            return expectedSave >= 0;
        }

        void launch(int span, boolean singleEmptyClear) {
            require(
                admitted && !running && !waiting() && span == reserved && singleEmptyClear,
                "Duplicate clear/create or missing admission");
            admitted = false;
            running = true;
            launchTick = lastTick;
            boundaryDeadline = launchTick + span;
        }

        void boundary() {
            require(!admitted && !waiting(), "Boundary transition during pending admission/wait");
            require(!running || lastTick <= boundaryDeadline, "Complete-case/drain exceeded fixed bound");
            running = false;
        }

        void audit() {
            require(callbacks + 1 - waitTicks <= NON_WAIT_LIMIT, "Original 8000 non-empty-wait tick audit exceeded");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
