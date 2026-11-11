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

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    private Sha256Hex() {}

    public static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 MessageDigest unavailable", impossible);
        }
    }

    public static String ofBytes(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        return formatHex(newDigest().digest(bytes));
    }

    /** 按流读取并计算摘要，流的开关生命周期仍交由调用方自己管理 */
    public static String ofStream(InputStream stream) throws IOException {
        Objects.requireNonNull(stream, "stream");
        var digest = newDigest();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = stream.read(buffer)) >= 0) {
            if (count > 0) digest.update(buffer, 0, count);
        }
        return formatHex(digest.digest());
    }

    public static String ofFile(File file) throws IOException {
        Objects.requireNonNull(file, "file");
        try (var input = new FileInputStream(file)) {
            var digest = newDigest();
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) digest.update(buffer, 0, count);
            }
            return formatHex(digest.digest());
        }
    }

    public static String formatHex(byte[] digest) {
        Objects.requireNonNull(digest, "digest");
        char[] chars = new char[digest.length * 2];
        for (int i = 0; i < digest.length; i++) {
            int value = digest[i] & 0xFF;
            chars[i * 2] = HEX_DIGITS[value >>> 4];
            chars[i * 2 + 1] = HEX_DIGITS[value & 0x0F];
        }
        return new String(chars);
    }
}
