package com.tianluoqaq.tlmnh.common.work.bell;

/** 女仆佩戴铃铛范围只读预览通道协议与限流参数 */
public final class BellPreviewProtocol {

    public static final String CHANNEL_NAME = "tlmnh_bell1";

    public static final int DISCRIMINATOR_REQUEST = 0;
    public static final int DISCRIMINATOR_SNAPSHOT = 1;

    public static final double MAX_PREVIEW_DIST_SQ = 64.0D;
    public static final long SERVER_COOLDOWN_TICKS = 4L;
    public static final long CLIENT_SAME_TARGET_COOLDOWN_TICKS = 10L;
    public static final int SNEAK_SYNC_GRACE_TICKS = 2;
    public static final int MAX_PROCESSED_PER_TICK = 16;
    public static final int MAX_CLIENT_QUEUE_SIZE = 32;
    public static final int SPECTATOR_GAME_TYPE_ID = 3;
    public static final int UNSET_ENTITY_ID = -1;
    public static final long UNSET_TICK = -100L;

    private BellPreviewProtocol() {}
}
