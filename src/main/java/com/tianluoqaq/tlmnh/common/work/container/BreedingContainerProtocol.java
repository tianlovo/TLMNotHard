package com.tianluoqaq.tlmnh.common.work.container;

/** 育种配置容器与网络通道协议常量 */
public final class BreedingContainerProtocol {

    public static final String CHANNEL_NAME = "tlmnh_breed1";
    public static final String MSG_CONFIG_DENIED = "message.tlmnh.breeding_config_denied";

    public static final int DISCRIMINATOR_SUBMIT = 0;
    public static final int DISCRIMINATOR_SNAPSHOT = 1;

    public static final int SNAPSHOT_SYNC_INTERVAL_TICKS = 20;
    public static final long CLOSE_GRACE_TICKS = 20L;
    public static final int NO_NEXT_ACTION = -1;
    public static final int MAX_PROCESSED_PER_TICK = 16;
    public static final int MAX_UTF8_LEN = 256;

    private BreedingContainerProtocol() {}
}
