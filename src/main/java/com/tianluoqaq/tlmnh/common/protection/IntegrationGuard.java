package com.tianluoqaq.tlmnh.common.protection;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import com.tianluoqaq.tlmnh.TLMNotHard;

/** 外部调用失败只停用该能力；回退值由调用边界明确提供，不猜测替代API */
public final class IntegrationGuard {

    @FunctionalInterface
    public interface Call<T> {

        T get() throws Exception;
    }

    public static final class Fault {

        public final String key, code;
        public final Throwable cause;

        Fault(String key, String code, Throwable cause) {
            this.key = key;
            this.code = code;
            this.cause = cause;
        }
    }

    private static final Map<String, Fault> FAULTS = new LinkedHashMap<>();
    private static long sequence;

    private IntegrationGuard() {}

    public static <T> T call(ExternalFeature feature, Call<T> operation, Supplier<T> fallback) {
        if (!available(feature)) return fallback.get();
        try {
            return operation.get();
        } catch (Exception | LinkageError | AssertionError failure) {
            trip(feature, failure);
            return fallback.get();
        }
    }

    public static synchronized boolean available(ExternalFeature feature) {
        return !FAULTS.containsKey(feature.id);
    }

    public static Fault trip(ExternalFeature feature, Throwable failure) {
        return report(feature.id, failure);
    }

    public static synchronized Fault report(String key, Throwable failure) {
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof VirtualMachineError error) throw error;
            if (cause instanceof ThreadDeath death) throw death;
            if (cause instanceof InterruptedException) Thread.currentThread()
                .interrupt();
        }
        var existing = FAULTS.get(key);
        if (existing != null) return existing;
        var fault = new Fault(key, String.format("TLMNH-P%06d", ++sequence), failure);
        FAULTS.put(key, fault);
        TLMNotHard.LOG
            .error("{}: protected fallback for {}; original owned data must remain intact", fault.code, key, failure);
        return fault;
    }

    public static synchronized Map<String, Fault> faults() {
        return new LinkedHashMap<>(FAULTS);
    }

    /** 仅为隔离故障夹具恢复进程内状态，正式玩法不会自动解除熔断 */
    public static synchronized void resetForDevelopment() {
        if (!Boolean.getBoolean("tlmnh.devCommands")) throw new IllegalStateException("Development command required");
        FAULTS.clear();
    }
}
