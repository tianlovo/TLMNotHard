package com.tianluoqaq.tlmnh.common.ai.telemetry;

/** 服务端会话可显式接入，默认不分配事件、不记录或改变策略 */
public interface MaidTelemetrySink {

    enum Acceptance {
        ACCEPTED,
        ACCEPTED_DETAIL_DROPPED,
        DISABLED,
        STALE_EPOCH,
        REPLAYED_SEQUENCE,
        STALE_TICK,
        COUNTER_OVERFLOW
    }

    MaidTelemetrySink NONE = new MaidTelemetrySink() {

        @Override
        public boolean isEnabled() {
            return false;
        }

        @Override
        public Acceptance record(MaidTelemetryEvent event) {
            return Acceptance.DISABLED;
        }
    };

    boolean isEnabled();

    Acceptance record(MaidTelemetryEvent event);
}
