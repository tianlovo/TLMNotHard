package com.tianluoqaq.tlmnh.qa;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/** 发布端与读者共用独立文件第一个字节的排他锁，JSON 读句柄关闭后再原子替换 */
public final class FinalNetworkStateFiles {

    private static final long LOCK_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

    private FinalNetworkStateFiles() {}

    public static void publish(Path target, String value) throws IOException {
        Path mutex = target.resolveSibling(
            target.getFileName()
                .toString() + ".lock");
        Path temporary = target.resolveSibling(
            target.getFileName()
                .toString() + ".pending");
        try (FileChannel channel = FileChannel.open(mutex, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock ignored = acquire(channel, target)) {
            Files.write(temporary, value.getBytes(StandardCharsets.UTF_8));
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static FileLock acquire(FileChannel channel, Path target) throws IOException {
        long started = System.nanoTime();
        while (true) {
            try {
                FileLock lock = channel.tryLock(0, 1, false);
                if (lock != null) return lock;
            } catch (OverlappingFileLockException failure) {
                throw new IOException("Overlapping native state publication in this JVM: " + target, failure);
            }
            if (Thread.currentThread()
                .isInterrupted()) throw new IOException("Interrupted native state publication lock: " + target);
            long elapsed = System.nanoTime() - started;
            if (elapsed >= LOCK_TIMEOUT_NANOS) throw new IOException(
                "Native state publication lock exceeded 50 ms: " + target + "; waitedNanos=" + elapsed);
            LockSupport.parkNanos(Math.min(TimeUnit.MILLISECONDS.toNanos(1), LOCK_TIMEOUT_NANOS - elapsed));
        }
    }
}
