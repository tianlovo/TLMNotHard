package com.tianluoqaq.tlmnh.client.compat;

import java.util.Objects;
import java.util.ServiceLoader;

import com.tianluoqaq.tlmnh.client.compat.spi.ClientItemRenderCompat;
import com.tianluoqaq.tlmnh.client.compat.spi.RenderPlatformCompat;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 客户端第三方适配静态入口；渲染帧直接读取冻结后的静态字段，避免热路径查表开销 */
@SideOnly(Side.CLIENT)
public final class ClientCompatServices {

    private static RenderPlatformCompat renderPlatform = RenderPlatformCompat.NOOP;
    private static ClientItemRenderCompat itemRender = ClientItemRenderCompat.NOOP;
    private static volatile boolean frozen;
    private static volatile int registeredCount;

    static {
        loadProviders();
    }

    private ClientCompatServices() {}

    private static synchronized void loadProviders() {
        if (registeredCount > 0) return;
        var sink = new Sink();
        for (ClientCompatRegistrar registrar : ServiceLoader
            .load(ClientCompatRegistrar.class, ClientCompatServices.class.getClassLoader())) {
            registrar.register(sink);
            registeredCount++;
        }
    }

    public static synchronized void initAndFreeze() {
        if (!frozen && registeredCount == 0) {
            loadProviders();
        }
        frozen = true;
    }

    public static boolean isFrozen() {
        return frozen;
    }

    public static RenderPlatformCompat renderPlatform() {
        return renderPlatform;
    }

    public static ClientItemRenderCompat itemRender() {
        return itemRender;
    }

    public static final class Sink {

        private Sink() {}

        private static void ensureMutable() {
            if (frozen) throw new IllegalStateException("ClientCompatServices is already frozen");
        }

        public Sink renderPlatform(RenderPlatformCompat provider) {
            ensureMutable();
            renderPlatform = Objects.requireNonNull(provider, "provider");
            return this;
        }

        public Sink itemRender(ClientItemRenderCompat provider) {
            ensureMutable();
            itemRender = Objects.requireNonNull(provider, "provider");
            return this;
        }
    }
}
