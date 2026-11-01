package com.tianluoqaq.tlmnh.common.protection;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import com.tianluoqaq.tlmnh.TLMNotHard;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.relauncher.Side;

/** 外部调用失败只停用该能力；回退值由调用边界明确提供，不猜测替代API */
public final class IntegrationGuard {

    @FunctionalInterface
    public interface Call<T> {

        T get() throws Exception;
    }

    public static final class Fault {

        public final String key, code;
        public final Throwable cause;
        public final Side side;
        public final java.util.UUID owner;

        Fault(Side side, String key, String code, Throwable cause, java.util.UUID owner) {
            this.side = side;
            this.key = key;
            this.code = code;
            this.cause = cause;
            this.owner = owner;
        }
    }

    private static final Map<Side, Map<String, Fault>> FAULTS = new java.util.EnumMap<>(Side.class);
    private static long sequence;

    private IntegrationGuard() {}

    public static <T> T call(ExternalFeature feature, Call<T> operation, Supplier<T> fallback) {
        return call(currentSide(), feature, operation, fallback);
    }

    public static <T> T call(Side side, ExternalFeature feature, Call<T> operation, Supplier<T> fallback) {
        if (!available(side, feature)) return fallback.get();
        try {
            return operation.get();
        } catch (Exception | LinkageError | AssertionError failure) {
            trip(side, feature, failure);
            return fallback.get();
        }
    }

    public static synchronized boolean available(ExternalFeature feature) {
        return available(currentSide(), feature);
    }

    public static synchronized boolean available(Side side, ExternalFeature feature) {
        return !(side == Side.SERVER && feature.clientOnly) && !faultMap(side).containsKey(feature.id);
    }

    public static synchronized boolean available(Side side, String key) {
        return !faultMap(side).containsKey(key);
    }

    public static Fault trip(ExternalFeature feature, Throwable failure) {
        return trip(currentSide(), feature, failure);
    }

    public static Fault trip(Side side, ExternalFeature feature, Throwable failure) {
        return report(side, feature.id, failure);
    }

    public static Fault report(String key, Throwable failure) {
        return report(currentSide(), key, failure);
    }

    public static void throwIfFatal(Throwable failure) {
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        var pending = new java.util.ArrayDeque<Throwable>();
        if (failure != null) pending.add(failure);
        while (!pending.isEmpty()) {
            var cause = pending.removeFirst();
            if (!seen.add(cause)) continue;
            if (cause instanceof VirtualMachineError error) throw error;
            if (cause instanceof ThreadDeath death) throw death;
            if (cause instanceof InterruptedException) Thread.currentThread()
                .interrupt();
            if (cause.getCause() != null) pending.add(cause.getCause());
            java.util.Collections.addAll(pending, cause.getSuppressed());
        }
    }

    public static Fault report(Side side, String key, Throwable failure) {
        return report(side, key, failure, null);
    }

    public static synchronized Fault report(Side side, String key, Throwable failure, java.util.UUID owner) {
        throwIfFatal(failure);
        var faults = faultMap(side);
        var existing = faults.get(key);
        if (existing != null) return existing;
        var fault = new Fault(side, key, String.format("TLMNH-P%06d", ++sequence), failure, owner);
        faults.put(key, fault);
        TLMNotHard.LOG.error(
            "{}: {} protected fallback for {}; original owned data must remain intact",
            fault.code,
            side,
            key,
            failure);
        return fault;
    }

    public static synchronized Map<String, Fault> faults() {
        return faults(currentSide());
    }

    public static synchronized Map<String, Fault> faults(Side side) {
        return new LinkedHashMap<>(faultMap(side));
    }

    private static Map<String, Fault> faultMap(Side side) {
        return FAULTS.computeIfAbsent(side, ignored -> new LinkedHashMap<>());
    }

    private static Side currentSide() {
        var handler = FMLCommonHandler.instance();
        return handler.getSide() == Side.SERVER ? Side.SERVER : handler.getEffectiveSide();
    }

    /** 仅为隔离故障夹具恢复进程内状态，正式玩法不会自动解除熔断 */
    public static synchronized void resetForDevelopment() {
        resetForDevelopment(currentSide());
    }

    public static synchronized void resetForDevelopment(Side side) {
        if (!Boolean.getBoolean("tlmnh.devCommands")) throw new IllegalStateException("Development command required");
        faultMap(side).clear();
    }
}
