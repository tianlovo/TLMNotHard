package com.tianluoqaq.tlmnh.common.protection;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/** 纯 JDK 的 SHA-256 十六进制摘要工具，连早期 Mixin 插件阶段也能直接调用 */
public final class Sha256Hex {

    private static final char[] HEX_DIGITS = ProtectionIds.ID_0123456789ABCDEF.toCharArray();

    private Sha256Hex() {}

    public static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(ProtectionIds.KEY_SHA_256);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(ProtectionDiagnostics.MSG_SHA_256_MESSAGEDIGEST_UNAVAILABLE, impossible);
        }
    }

    public static String ofBytes(byte[] bytes) {
        Objects.requireNonNull(bytes, ProtectionIds.ID_BYTES);
        return formatHex(newDigest().digest(bytes));
    }

    /** 按流读取并计算摘要，流的开关生命周期仍交由调用方自己管理 */
    public static String ofStream(InputStream stream) throws IOException {
        Objects.requireNonNull(stream, ProtectionIds.ID_STREAM);
        var digest = newDigest();
        byte[] buffer = new byte[ProtectionTuning.SHA256_HEX_OF_STREAM_PARAM];
        int count;
        while ((count = stream.read(buffer)) >= 0) {
            if (count > 0) digest.update(buffer, ProtectionTuning.SUBSTRING_START_OFFSET, count);
        }
        return formatHex(digest.digest());
    }

    public static String ofFile(File file) throws IOException {
        Objects.requireNonNull(file, ProtectionIds.ID_FILE);
        try (var input = new FileInputStream(file)) {
            var digest = newDigest();
            byte[] buffer = new byte[ProtectionTuning.SHA256_HEX_OF_FILE_PARAM];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) digest.update(buffer, ProtectionTuning.SUBSTRING_START_OFFSET, count);
            }
            return formatHex(digest.digest());
        }
    }

    public static String formatHex(byte[] digest) {
        Objects.requireNonNull(digest, ProtectionIds.ID_DIGEST);
        char[] chars = new char[digest.length * ProtectionTuning.SHA256_HEX_CHARS];
        for (int i = 0; i < digest.length; i++) {
            int value = digest[i] & ProtectionTuning.BYTE_BIT_MASK;
            chars[i * ProtectionTuning.SHA256_HEX_CHARS] = HEX_DIGITS[value >>> ProtectionTuning.CHUNK_SECTION_SHIFT];
            chars[i * ProtectionTuning.SHA256_HEX_CHARS + 1] = HEX_DIGITS[value
                & ProtectionTuning.SHA256_HEX_BIT_MASK_0F];
        }
        return new String(chars);
    }
}
