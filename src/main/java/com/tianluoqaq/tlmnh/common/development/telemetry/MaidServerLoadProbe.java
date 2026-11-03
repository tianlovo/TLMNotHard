package com.tianluoqaq.tlmnh.common.development.telemetry;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.common.development.DevelopmentArtifactIdentity;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** 被动开发计量；当前服务端线程CPU与原生tickTimeArray分别记录，不把墙钟当CPU */
public final class MaidServerLoadProbe implements AutoCloseable {

    private static MaidServerLoadProbe active;
    private static final int MAX_SAMPLES = 24000;
    private final MinecraftServer server;
    private final WorldServer world;
    private final Thread thread = Thread.currentThread();
    private final ThreadMXBean cpu = ManagementFactory.getThreadMXBean();
    private final List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
    private final List<Window> windows = new ArrayList<>();
    private final JsonObject metadata = new JsonObject();
    private final boolean enabledCpu;
    private final AllocationClock allocation;
    private Window window;
    private int startCounter, totalSamples, phaseCounter;
    private long startCpu;
    private long observedCpuGrid;
    private String fault;
    private boolean armed, closed, initialEndMayBeUnpaired;

    private MaidServerLoadProbe(String run, String label, WorldServer world) throws Exception {
        require(Boolean.getBoolean("tlmnh.devCommands"), "Explicit development mode required");
        require(active == null, "Another server load recorder is active");
        require(
            run != null && !run.trim()
                .isEmpty()
                && label != null
                && !label.trim()
                    .isEmpty(),
            "Run/label required");
        server = MinecraftServer.getServer();
        require(
            server != null && world != null
                && !world.isRemote
                && server.worldServerForDimension(world.provider.dimensionId) == world,
            "Actual registered server world required");
        this.world = world;
        require(
            cpu.isCurrentThreadCpuTimeSupported(),
            "Server thread CPU time is unsupported; wall time is not a substitute");
        enabledCpu = !cpu.isThreadCpuTimeEnabled();
        metadata.addProperty("schema", "server-load-native-v2");
        metadata.addProperty("run", run);
        metadata.addProperty("label", label);
        metadata.addProperty("dimension", world.provider.dimensionId);
        metadata.addProperty("threadId", thread.getId());
        metadata.addProperty("threadName", thread.getName());
        metadata.addProperty("javaRuntime", System.getProperty("java.runtime.version"));
        metadata.addProperty("cpuClockEnabledByProbe", enabledCpu);
        metadata.addProperty(
            "cpuScope",
            "Current server thread between FML START LOWEST and END HIGHEST; excludes async workers and GC threads");
        metadata.addProperty(
            "msptScope",
            "Native MinecraftServer.tickTimeArray[current tickCounter % 100], written before FML END");
        metadata.addProperty(
            "interpretation",
            "Whole-pack capacity observations; no cross-version AI superiority or independent tick replications");
        metadata.addProperty("heapScope", "Whole JVM heap including the bounded passive recorder; no forced GC");
        metadata.addProperty(
            "continuousCpuScope",
            "Current server thread across the complete phase, including controlled owner/observer work between FML markers; excludes async workers");
        metadata.addProperty(
            "cpuClockCaveat",
            "Nanoseconds are units, not guaranteed resolution. Observed delta grid and zero samples must not be interpreted as zero CPU or fine-grained profile rankings");
        metadata.addProperty(
            "saveCostScope",
            "Native counter%900 scheduled-save tick cost and actual WorldEvent.Save counts; asynchronous I/O may continue beyond those ticks");
        metadata.addProperty(
            "allocationScope",
            "Optional current-thread allocated bytes across the complete phase, including recorder/observer allocations; unsupported remains -1");
        metadata.add("productionArtifact", DevelopmentArtifactIdentity.capture(MaidServerLoadProbe.class));
        if (enabledCpu) cpu.setThreadCpuTimeEnabled(true);
        if (cpu.getCurrentThreadCpuTime() < 0) {
            if (enabledCpu) cpu.setThreadCpuTimeEnabled(false);
            throw new IllegalStateException("Server thread CPU clock unavailable");
        }
        allocation = new AllocationClock();
        FMLCommonHandler.instance()
            .bus()
            .register(this);
        MinecraftForge.EVENT_BUS.register(this);
        active = this;
    }

    public static MaidServerLoadProbe start(String run, String label, WorldServer world) throws Exception {
        return new MaidServerLoadProbe(run, label, world);
    }

    public void phase(String label, int actorCount) {
        phase(label, actorCount, true);
    }

    /** warmup也留时间/堆/GC边界，但不混入正式样本 */
    public void phase(String label, int actorCount, boolean sampling) {
        owner();
        require(
            !closed && fault == null
                && label != null
                && !label.trim()
                    .isEmpty()
                && actorCount >= 0,
            "Invalid recorder phase");
        finishWindow();
        window = new Window(label, actorCount, sampling);
        windows.add(window);
        armed = false;
        phaseCounter = server.getTickCounter();
        initialEndMayBeUnpaired = true;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void startTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START || closed || window == null || fault != null) return;
        if (Thread.currentThread() != thread) {
            fail("Server START moved threads");
            return;
        }
        if (armed) {
            fail("Server START arrived before its previous END");
            return;
        }
        initialEndMayBeUnpaired = false;
        startCounter = server.getTickCounter();
        startCpu = cpu.getCurrentThreadCpuTime();
        armed = startCpu >= 0;
        if (!armed) fail("Server CPU clock became unavailable");
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void endTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || closed || window == null || fault != null) return;
        long endCpu = cpu.getCurrentThreadCpuTime();
        // A command may open a phase after this tick's START. Skip only that first partial tick;
        // a later missing START remains an invalid event sequence, never an unbounded silent wait.
        if (!armed && initialEndMayBeUnpaired
            && Thread.currentThread() == thread
            && server.worldServerForDimension(world.provider.dimensionId) == world
            && (server.getTickCounter() == phaseCounter || server.getTickCounter() == phaseCounter + 1)) {
            initialEndMayBeUnpaired = false;
            window.skippedPartialEnds++;
            return;
        }
        if (Thread.currentThread() != thread || !armed
            || server.getTickCounter() != startCounter + 1
            || endCpu < startCpu
            || server.worldServerForDimension(world.provider.dimensionId) != world) {
            fail("Native server tick/thread/world identity or CPU interval changed");
            return;
        }
        armed = false;
        window.ticks++;
        if (!window.sampling) return;
        if (totalSamples >= MAX_SAMPLES) {
            fail("Bounded native load sample capacity exceeded");
            return;
        }
        long nativeTick = server.tickTimeArray[Math.floorMod(server.getTickCounter(), server.tickTimeArray.length)];
        if (nativeTick <= 0) {
            fail("Current native tickTimeArray sample missing");
            return;
        }
        window.cpuNanos.add(endCpu - startCpu);
        window.nativeNanos.add(nativeTick);
        window.serverCounters.add((long) server.getTickCounter());
        long delta = endCpu - startCpu;
        if (delta > 0) observedCpuGrid = gcd(observedCpuGrid, delta);
        totalSamples++;
        if ((window.ticks % 20) == 0) window.peakHeap = Math.max(window.peakHeap, heapUsed());
    }

    @SubscribeEvent
    public void saving(WorldEvent.Save event) {
        if (closed || window == null || event.world != world) return;
        if (Thread.currentThread() != thread) {
            fail("Native WorldSave observation moved off its server thread");
            return;
        }
        window.worldSaveEvents++;
    }

    public JsonObject snapshot() {
        owner();
        var result = new com.google.gson.JsonParser().parse(metadata.toString())
            .getAsJsonObject();
        result.addProperty("closed", closed);
        result.addProperty("valid", fault == null);
        if (fault != null) result.addProperty("fault", fault);
        result.addProperty("totalSampleTicks", totalSamples);
        result.addProperty("observedCpuDeltaGridNanos", observedCpuGrid);
        result.addProperty("cpuDeltaGridIsHardwareResolutionCalibration", false);
        result.addProperty("allocationSupported", allocation.supported());
        if (allocation.failure != null) result.addProperty("allocationUnavailableReason", allocation.failure);
        var rows = new JsonArray();
        for (var value : windows) rows.add(value.json(value == window && !closed));
        result.add("windows", rows);
        return result;
    }

    @Override
    public void close() {
        owner();
        if (closed) return;
        finishWindow();
        closed = true;
        FMLCommonHandler.instance()
            .bus()
            .unregister(this);
        MinecraftForge.EVENT_BUS.unregister(this);
        allocation.close();
        if (active == this) active = null;
        if (enabledCpu) cpu.setThreadCpuTimeEnabled(false);
    }

    private void finishWindow() {
        if (window != null) window.finish();
    }

    private void owner() {
        require(Thread.currentThread() == thread, "Recorder must be accessed from its actual server thread");
    }

    private void fail(String message) {
        fault = message;
        armed = false;
    }

    private static long heapUsed() {
        return ManagementFactory.getMemoryMXBean()
            .getHeapMemoryUsage()
            .getUsed();
    }

    private long[] gcCounts(boolean time) {
        var values = new long[collectors.size()];
        for (int i = 0; i < values.length; i++) values[i] = time ? collectors.get(i)
            .getCollectionTime()
            : collectors.get(i)
                .getCollectionCount();
        return values;
    }

    private final class Window {

        final String label;
        final int actors;
        final boolean sampling;
        final long startedAt = System.nanoTime(), startWorldTick = world.getTotalWorldTime(), heapBefore = heapUsed();
        final long cpuBefore = cpu.getCurrentThreadCpuTime(), allocatedBefore = allocation.read();
        final long[] gcBefore = gcCounts(false), gcTimeBefore = gcCounts(true);
        final List<Long> cpuNanos = new ArrayList<>(), nativeNanos = new ArrayList<>(),
            serverCounters = new ArrayList<>();
        long endedAt, heapAfter, peakHeap = heapBefore, endWorldTick;
        long cpuAfter, allocatedAfter;
        long[] gcAfter, gcTimeAfter;
        int ticks, skippedPartialEnds, worldSaveEvents;

        Window(String label, int actors, boolean sampling) {
            this.label = label;
            this.actors = actors;
            this.sampling = sampling;
        }

        void finish() {
            if (endedAt != 0) return;
            endedAt = System.nanoTime();
            cpuAfter = cpu.getCurrentThreadCpuTime();
            allocatedAfter = allocation.read();
            heapAfter = heapUsed();
            peakHeap = Math.max(peakHeap, heapAfter);
            endWorldTick = world.getTotalWorldTime();
            gcAfter = gcCounts(false);
            gcTimeAfter = gcCounts(true);
        }

        JsonObject json(boolean ongoing) {
            long end = ongoing ? System.nanoTime() : endedAt;
            var out = new JsonObject();
            out.addProperty("label", label);
            out.addProperty("actors", actors);
            out.addProperty("sampling", sampling);
            out.addProperty("ticks", ticks);
            out.addProperty("skippedInitialPartialEnds", skippedPartialEnds);
            out.addProperty("startWorldTick", startWorldTick);
            out.addProperty("endWorldTick", ongoing ? world.getTotalWorldTime() : endWorldTick);
            out.addProperty("wallNanos", end - startedAt);
            long actualCpuAfter = ongoing ? cpu.getCurrentThreadCpuTime() : cpuAfter;
            long actualAllocationAfter = ongoing ? allocation.read() : allocatedAfter;
            out.addProperty("continuousThreadCpuBeforeNanos", cpuBefore);
            out.addProperty("continuousThreadCpuAfterNanos", actualCpuAfter);
            out.addProperty(
                "continuousThreadCpuDeltaNanos",
                cpuBefore >= 0 && actualCpuAfter >= cpuBefore ? actualCpuAfter - cpuBefore : -1);
            out.addProperty("allocatedBytesBefore", allocatedBefore);
            out.addProperty("allocatedBytesAfter", actualAllocationAfter);
            out.addProperty(
                "allocatedBytesDelta",
                allocatedBefore >= 0 && actualAllocationAfter >= allocatedBefore
                    ? actualAllocationAfter - allocatedBefore
                    : -1);
            out.addProperty("observedTps", ticks == 0 ? 0 : ticks * 1e9 / (end - startedAt));
            out.addProperty("heapBefore", heapBefore);
            out.addProperty("heapAfter", ongoing ? heapUsed() : heapAfter);
            out.addProperty("peakSampledHeap", peakHeap);
            out.addProperty(
                "heapCommitted",
                ManagementFactory.getMemoryMXBean()
                    .getHeapMemoryUsage()
                    .getCommitted());
            out.addProperty(
                "heapMax",
                ManagementFactory.getMemoryMXBean()
                    .getHeapMemoryUsage()
                    .getMax());
            out.add("serverCpuNanos", numbers(cpuNanos));
            out.add("nativeTickNanos", numbers(nativeNanos));
            out.add("nativeServerTickCounters", numbers(serverCounters));
            out.add("cpuSummary", statistics(cpuNanos));
            out.add("nativeMsptSummary", statistics(nativeNanos));
            out.addProperty("actualWorldSaveEvents", worldSaveEvents);
            var saveTicks = new ArrayList<Long>();
            var regularTicks = new ArrayList<Long>();
            for (int i = 0; i < serverCounters.size(); i++)
                (serverCounters.get(i) % 900 == 0 ? saveTicks : regularTicks).add(nativeNanos.get(i));
            out.add("scheduledSaveTickNativeMspt", statistics(saveTicks));
            out.add("otherTickNativeMspt", statistics(regularTicks));
            var gc = new JsonArray();
            long[] count = ongoing ? gcCounts(false) : gcAfter, time = ongoing ? gcCounts(true) : gcTimeAfter;
            for (int i = 0; i < collectors.size(); i++) {
                var row = new JsonObject();
                row.addProperty(
                    "name",
                    collectors.get(i)
                        .getName());
                row.addProperty("countBefore", gcBefore[i]);
                row.addProperty("countAfter", count[i]);
                row.addProperty("timeMillisBefore", gcTimeBefore[i]);
                row.addProperty("timeMillisAfter", time[i]);
                gc.add(row);
            }
            out.add("gc", gc);
            return out;
        }
    }

    private final class AllocationClock {

        Object bean;
        Method read, enabled;
        boolean changed;
        String failure;

        AllocationClock() {
            try {
                var api = Class.forName("com.sun.management.ThreadMXBean");
                bean = ManagementFactory.getThreadMXBean();
                if (!api.isInstance(bean) || !(Boolean) api.getMethod("isThreadAllocatedMemorySupported")
                    .invoke(bean)) {
                    failure = "Native current-thread allocation counter unsupported";
                    bean = null;
                    return;
                }
                enabled = api.getMethod("setThreadAllocatedMemoryEnabled", boolean.class);
                read = api.getMethod("getThreadAllocatedBytes", long.class);
                changed = !(Boolean) api.getMethod("isThreadAllocatedMemoryEnabled")
                    .invoke(bean);
                if (changed) enabled.invoke(bean, true);
            } catch (Exception unavailable) {
                failure = unavailable.toString();
                if (changed && enabled != null && bean != null) try {
                    enabled.invoke(bean, false);
                } catch (Exception restoreFailure) {
                    unavailable.addSuppressed(restoreFailure);
                }
                bean = null;
            }
        }

        boolean supported() {
            return bean != null && failure == null;
        }

        long read() {
            if (!supported()) return -1;
            try {
                return (Long) read.invoke(bean, thread.getId());
            } catch (Exception unavailable) {
                failure = unavailable.toString();
                return -1;
            }
        }

        void close() {
            if (changed && bean != null) try {
                enabled.invoke(bean, false);
            } catch (Exception unavailable) {
                failure = unavailable.toString();
            }
        }
    }

    private static long gcd(long a, long b) {
        while (b != 0) {
            long next = a % b;
            a = b;
            b = next;
        }
        return a;
    }

    private static JsonArray numbers(List<Long> values) {
        var out = new JsonArray();
        for (long value : values) out.add(new com.google.gson.JsonPrimitive(value));
        return out;
    }

    private static JsonObject statistics(List<Long> values) {
        var out = new JsonObject();
        out.addProperty("sampleTicks", values.size());
        if (values.isEmpty()) return out;
        long[] sorted = new long[values.size()];
        double sum = 0;
        int over = 0;
        for (int i = 0; i < sorted.length; i++) {
            sorted[i] = values.get(i);
            sum += sorted[i];
            if (sorted[i] > 50000000L) over++;
        }
        Arrays.sort(sorted);
        out.addProperty("meanMillis", sum / sorted.length / 1e6);
        out.addProperty("p50Millis", sorted[(int) Math.ceil(sorted.length * .50) - 1] / 1e6);
        out.addProperty("p95Millis", sorted[(int) Math.ceil(sorted.length * .95) - 1] / 1e6);
        out.addProperty("p99Millis", sorted[(int) Math.ceil(sorted.length * .99) - 1] / 1e6);
        out.addProperty("maxMillis", sorted[sorted.length - 1] / 1e6);
        out.addProperty("over50MillisFraction", (double) over / sorted.length);
        return out;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}
