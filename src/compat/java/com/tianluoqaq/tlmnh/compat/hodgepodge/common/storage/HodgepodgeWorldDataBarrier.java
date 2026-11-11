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

    private static final String KEY = "hodgepodge/world_data_saver";
    private static final String FINGERPRINT = "2.7.221:sha256:9b0bfb7e84a66387319952d76e6f114d6218a988e04d303965c31efb2165e824";
    private static volatile Bridge bridge;

    private HodgepodgeWorldDataBarrier() {}

    public static void beforeLedgerWrite() throws Exception {
        if (!Loader.isModLoaded("hodgepodge")) return;
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
                IntegrationGuard.report(Side.SERVER, KEY, linkage);
                throw linkage;
            }
            if (cause instanceof AssertionError assertion) throw assertion;
            throw new IllegalStateException("Native world-data flush failed", cause);
        }
    }

    private static Bridge resolve() {
        if (!IntegrationGuard.available(Side.SERVER, KEY))
            throw new IllegalStateException("Native world-data barrier remains unavailable");
        var selected = bridge;
        if (selected != null) return selected;
        synchronized (HodgepodgeWorldDataBarrier.class) {
            if (bridge != null) return bridge;
            try {
                if (!FINGERPRINT.equals(DependencyFingerprint.mod("hodgepodge")))
                    throw new IllegalStateException("Unverified native world-data saver provider");
                var type = Class.forName("com.mitchej123.hodgepodge.util.WorldDataSaver");
                var source = type.getProtectionDomain()
                    .getCodeSource();
                if (source == null
                    || !FINGERPRINT.equals(DependencyFingerprint.source(sourceFile(source.getLocation()), "2.7.221")))
                    throw new IllegalStateException("World-data saver class does not belong to the verified provider");
                var instance = type.getField("INSTANCE");
                var flush = type.getMethod("flush");
                if (!Modifier.isStatic(instance.getModifiers()) || instance.getType() != type
                    || Modifier.isStatic(flush.getModifiers())
                    || flush.getReturnType() != void.class
                    || flush.getParameterTypes().length != 0)
                    throw new IllegalStateException("Unknown native world-data flush API shape");
                var saver = instance.get(null);
                if (saver == null || saver.getClass() != type)
                    throw new IllegalStateException("Unknown native world-data saver instance");
                bridge = new Bridge(saver, flush);
                return bridge;
            } catch (Exception | LinkageError | AssertionError failure) {
                IntegrationGuard.throwIfFatal(failure);
                IntegrationGuard.report(Side.SERVER, KEY, failure);
                throw new IllegalStateException("Cannot coordinate native lifecycle persistence", failure);
            }
        }
    }

    private static File sourceFile(URL location) throws Exception {
        // 与现有原生类来源核验一致：Launch可返回jar:file，不将不透明jar URI直接交给File
        if ("jar".equals(location.getProtocol())) {
            var connection = location.openConnection();
            if (!(connection instanceof JarURLConnection jar))
                throw new IllegalStateException("Unsupported world-data saver JAR CodeSource");
            location = jar.getJarFileURL();
        }
        if (!"file".equals(location.getProtocol()))
            throw new IllegalStateException("World-data saver CodeSource must resolve to a local file");
        var file = new File(location.toURI()).getCanonicalFile();
        if (!file.isFile()) throw new IllegalStateException("World-data saver CodeSource is not a regular file");
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
