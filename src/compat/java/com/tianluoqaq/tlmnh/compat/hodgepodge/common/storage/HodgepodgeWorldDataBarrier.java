package com.tianluoqaq.tlmnh.compat.hodgepodge.common.storage;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.JarURLConnection;
import java.net.URL;

import com.tianluoqaq.tlmnh.common.protection.DependencyFingerprint;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.relauncher.Side;

/** 已核提供方会保留失败旧快照；先沿其公开flush完成失败重排，之后才能原子写新账本 */
public final class HodgepodgeWorldDataBarrier {



    private static volatile Bridge bridge;

    private HodgepodgeWorldDataBarrier() {}

    public static void beforeLedgerWrite() throws Exception {
        if (!Loader.isModLoaded(HodgepodgeStorageIds.ID_HODGEPODGE)) return;
        var selected = resolve();
        try {
            selected.flush.invoke(selected.saver);
        } catch (InvocationTargetException invocation) {
            var cause = invocation.getCause();
            IntegrationGuard.throwIfFatal(cause);
            if (cause instanceof InterruptedException interrupted) {
                Thread.currentThread()
                    .interrupt();
                throw interrupted;
            }
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof LinkageError linkage) {
                IntegrationGuard.report(Side.SERVER, HodgepodgeStorageIds.KEY, linkage);
                throw linkage;
            }
            if (cause instanceof AssertionError assertion) throw assertion;
            throw new IllegalStateException(HodgepodgeStorageDiagnostics.MSG_NATIVE_WORLD_DATA_FLUSH_FAILED, cause);
        }
    }

    private static Bridge resolve() {
        if (!IntegrationGuard.available(Side.SERVER, HodgepodgeStorageIds.KEY))
            throw new IllegalStateException(HodgepodgeStorageDiagnostics.MSG_NATIVE_WORLD_DATA_BARRIER_REMAINS);
        var selected = bridge;
        if (selected != null) return selected;
        synchronized (HodgepodgeWorldDataBarrier.class) {
            if (bridge != null) return bridge;
            try {
                if (!HodgepodgeStorageIds.FINGERPRINT.equals(DependencyFingerprint.mod(HodgepodgeStorageIds.ID_HODGEPODGE)))
                    throw new IllegalStateException(HodgepodgeStorageDiagnostics.MSG_UNVERIFIED_NATIVE_WORLD_DATA_SAVER);
                var type = Class.forName(HodgepodgeStorageIds.TARGET_CLASS_WORLD_DATA_SAVER);
                var source = type.getProtectionDomain()
                    .getCodeSource();
                if (source == null
                    || !HodgepodgeStorageIds.FINGERPRINT.equals(DependencyFingerprint.source(sourceFile(source.getLocation()), HodgepodgeStorageIds.ID_2_7_221)))
                    throw new IllegalStateException(HodgepodgeStorageDiagnostics.MSG_WORLD_DATA_SAVER_CLASS_DOES);
                var instance = type.getField(HodgepodgeStorageNbtKeys.KEY_INSTANCE);
                var flush = type.getMethod(HodgepodgeStorageIds.ID_FLUSH);
                if (!Modifier.isStatic(instance.getModifiers()) || instance.getType() != type
                    || Modifier.isStatic(flush.getModifiers())
                    || flush.getReturnType() != void.class
                    || flush.getParameterTypes().length != 0)
                    throw new IllegalStateException(HodgepodgeStorageDiagnostics.MSG_UNKNOWN_NATIVE_WORLD_DATA_FLUSH);
                var saver = instance.get(null);
                if (saver == null || saver.getClass() != type)
                    throw new IllegalStateException(HodgepodgeStorageDiagnostics.MSG_UNKNOWN_NATIVE_WORLD_DATA_SAVER);
                bridge = new Bridge(saver, flush);
                return bridge;
            } catch (Exception | LinkageError | AssertionError failure) {
                IntegrationGuard.throwIfFatal(failure);
                IntegrationGuard.report(Side.SERVER, HodgepodgeStorageIds.KEY, failure);
                throw new IllegalStateException(HodgepodgeStorageDiagnostics.MSG_CANNOT_COORDINATE_NATIVE_LIFECYCLE_PERSISTENCE, failure);
            }
        }
    }

    private static File sourceFile(URL location) throws Exception {
        // 与现有原生类来源核验一致：Launch可返回jar:file，不将不透明jar URI直接交给File
        if (HodgepodgeStorageIds.ID_JAR.equals(location.getProtocol())) {
            var connection = location.openConnection();
            if (!(connection instanceof JarURLConnection jar))
                throw new IllegalStateException(HodgepodgeStorageDiagnostics.MSG_UNSUPPORTED_WORLD_DATA_SAVER_JAR);
            location = jar.getJarFileURL();
        }
        if (!HodgepodgeStorageIds.ID_FILE.equals(location.getProtocol()))
            throw new IllegalStateException(HodgepodgeStorageDiagnostics.MSG_WORLD_DATA_SAVER_CODESOURCE_MUST);
        var file = new File(location.toURI()).getCanonicalFile();
        if (!file.isFile()) throw new IllegalStateException(HodgepodgeStorageDiagnostics.MSG_WORLD_DATA_SAVER_CODESOURCE_IS);
        return file;
    }

    private static final class Bridge {

        final Object saver;
        final Method flush;

        Bridge(Object saver, Method flush) {
            this.saver = saver;
            this.flush = flush;
        }
    }
}
