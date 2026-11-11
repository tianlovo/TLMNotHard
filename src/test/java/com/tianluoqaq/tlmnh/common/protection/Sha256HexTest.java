package com.tianluoqaq.tlmnh.common.protection;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

/** 核对字节数组、输入流与文件的 SHA-256 十六进制输出一致性 */
public class Sha256HexTest {

    private static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final String ABC_SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Test
    public void emptyAndKnownVectorsMatchStandardDigests() throws Exception {
        assertEquals(EMPTY_SHA256, Sha256Hex.ofBytes(new byte[0]));
        byte[] sample = "abc".getBytes(StandardCharsets.US_ASCII);
        assertEquals(ABC_SHA256, Sha256Hex.ofBytes(sample));
        try (var stream = new ByteArrayInputStream(sample)) {
            assertEquals(ABC_SHA256, Sha256Hex.ofStream(stream));
        }
    }

    @Test
    public void multiChunkStreamAndFileProduceIdenticalDigest() throws Exception {
        byte[] payload = new byte[20000];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 31 + 7);
        String expected = Sha256Hex.ofBytes(payload);
        try (var stream = new ByteArrayInputStream(payload)) {
            assertEquals(expected, Sha256Hex.ofStream(stream));
        }
        var temp = Files.createTempFile("tlmnh-sha256-", ".bin");
        try {
            Files.write(temp, payload);
            assertEquals(expected, Sha256Hex.ofFile(temp.toFile()));
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
