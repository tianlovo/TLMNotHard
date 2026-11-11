package com.tianluoqaq.tlmnh.common.network;

import com.tianluoqaq.tlmnh.common.interaction.MaidInteractionTuning;

/** 女仆网络通道、包判别符与编解码协议常量（Protocol） */
public final class MaidNetworkProtocol {

    public static final int MAX_ENTRIES = 1024;
    public static final int MAX_MAP_BYTES = 28000;
    public static final int MAX_ID_BYTES = 256;
    public static final String EMPTY = "1;[]";
    public static final int MAX_ENCODED_LENGTH = MaidInteractionTuning.MAX_LENGTH * 4;
    public static final String MSG_ATTACK_LIST_EXCEEDS_PACKET_LIMITS = "Attack list exceeds packet limits";
    public static final int ATTACK_LIST_BYTES = 2;
    public static final String ID_A_Z0_9_A_Z0_9 = "[a-z0-9_.-]+:[a-z0-9/._-]+";
    public static final int ATTACK_LIST_FITS_PARAM = 3;
    public static final String MSG_INVALID_ATTACK_LIST_HEADER = "Invalid attack list header";
    public static final int MIN_LENGTH_THRESHOLD = 1;
    public static final String MSG_INVALID_ATTACK_LIST_IDENTIFIER_LENGTH = "Invalid attack list identifier length";
    public static final String MSG_INVALID_ATTACK_LIST_IDENTIFIER = "Invalid attack list identifier";
    public static final String MSG_INVALID_OR_REPEATED_ATTACK_LIST = "Invalid or repeated attack list entry";
    public static final String MSG_UNEXPECTED_ATTACK_LIST_PAYLOAD = "Unexpected attack list payload";
    public static final String ID_1 = "1;";
    public static final String MSG_UNKNOWN_BUBBLE_METADATA_VERSION = "Unknown bubble metadata version";
    public static final String MSG_TOO_MANY_BUBBLE_ROWS = "Too many bubble rows";
    public static final String MSG_INVALID_BUBBLE_ROW = "Invalid bubble row";
    public static final int GET_INITIAL_VALUE = 0;
    public static final int MAX_LENGTH_THRESHOLD = 256;
    public static final String MSG_INVALID_BUBBLE_IDENTITY_CONTENT = "Invalid bubble identity/content";
    public static final int MAX_LENGTH_THRESHOLD_2 = 4096;
    public static final int MAX_LENGTH_THRESHOLD_3 = 8192;
    public static final String MSG_BUBBLE_METADATA_TOO_LARGE = "Bubble metadata too large";
    public static final String ID_TLMNH_EFFECT1 = "tlmnh_effect1";
    public static final String ID_NETWORK_MAID_EFFECTS = "network/maid_effects/";
    public static final String MSG_INVALID_MAID_NAME_BYTE_LENGTH = "Invalid maid name byte length";
    public static final String MSG_INVALID_MAID_NAME_UTF = "Invalid maid name UTF-8";
    public static final String MSG_MAID_NAME_IS_TOO_LONG = "Maid name is too long";
    public static final String MSG_UNEXPECTED_MAID_NAME_PAYLOAD = "Unexpected maid name payload";
    public static final String ID_TLMNH_NAME1 = "tlmnh_name1";
    public static final String I18N_MESSAGE_TLMNH_NAMING_DENIED = "message.tlmnh.naming.denied";
    public static final int MAX_PROCESSED_THRESHOLD = 128;
    public static final String ID_TLMNH_SOUND1 = "tlmnh_sound1";
    public static final int SOUND_NETWORK_TARGET_POINT_FIFTH_PARAM = 16;
    public static final String ID_TLMNH_TASK1 = "tlmnh_task1";
    public static final int UNSET_OR_NOT_FOUND = -1;
    public static final String I18N_MESSAGE_TLMNH_ATTACK_CONFIG_DENIED = "message.tlmnh.attack_config.denied";

    private MaidNetworkProtocol() {}
}
