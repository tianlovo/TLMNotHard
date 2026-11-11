package com.tianluoqaq.tlmnh.common.compat;

import java.util.Objects;
import java.util.ServiceLoader;

import com.tianluoqaq.tlmnh.common.ai.spi.DoorCompatProvider;
import com.tianluoqaq.tlmnh.common.ai.spi.WorldExtraContentProvider;
import com.tianluoqaq.tlmnh.common.combat.spi.ProjectilePhysicsProvider;
import com.tianluoqaq.tlmnh.common.combat.spi.ShieldCompatProvider;
import com.tianluoqaq.tlmnh.common.combat.spi.WeaponCompatProvider;
import com.tianluoqaq.tlmnh.common.inventory.spi.BackpackContainerCompatProvider;
import com.tianluoqaq.tlmnh.common.life.spi.NutritionCompatProvider;
import com.tianluoqaq.tlmnh.common.lifecycle.spi.LifecycleStorageCompatProvider;

/**
 * 双端共享的第三方适配入口。
 * 类加载阶段即通过 ServiceLoader 一次性绑定具体实现，preInit 完成后冻结；热路径直接读静态字段，不做表查找
 */
public final class CompatServices {

    private static ShieldCompatProvider shields = ShieldCompatProvider.NOOP;
    private static WeaponCompatProvider weapons = WeaponCompatProvider.NOOP;
    private static ProjectilePhysicsProvider projectilePhysics = ProjectilePhysicsProvider.NOOP;
    private static DoorCompatProvider doors = DoorCompatProvider.NOOP;
    private static WorldExtraContentProvider worldContent = WorldExtraContentProvider.NOOP;
    private static NutritionCompatProvider nutrition = NutritionCompatProvider.NOOP;
    private static LifecycleStorageCompatProvider lifecycleStorage = LifecycleStorageCompatProvider.NOOP;
    private static BackpackContainerCompatProvider backpacks = BackpackContainerCompatProvider.NOOP;
    private static volatile boolean frozen;
    private static volatile int registeredCount;

    static {
        loadProviders();
    }

    private CompatServices() {}

    private static synchronized void loadProviders() {
        if (registeredCount > 0) return;
        var sink = new Sink();
        for (CompatRegistrar registrar : ServiceLoader
            .load(CompatRegistrar.class, CompatServices.class.getClassLoader())) {
            registrar.register(sink);
            registeredCount++;
        }
    }

    /** preInit 阶段确保完成装配并冻结，防止运行期被意外覆盖 */
    public static synchronized void preInitAndFreeze() {
        if (!frozen && registeredCount == 0) {
            loadProviders();
        }
        frozen = true;
    }

    public static boolean isFrozen() {
        return frozen;
    }

    public static ShieldCompatProvider shields() {
        return shields;
    }

    public static WeaponCompatProvider weapons() {
        return weapons;
    }

    public static ProjectilePhysicsProvider projectilePhysics() {
        return projectilePhysics;
    }

    public static DoorCompatProvider doors() {
        return doors;
    }

    public static WorldExtraContentProvider worldContent() {
        return worldContent;
    }

    public static NutritionCompatProvider nutrition() {
        return nutrition;
    }

    public static LifecycleStorageCompatProvider lifecycleStorage() {
        return lifecycleStorage;
    }

    public static BackpackContainerCompatProvider backpacks() {
        return backpacks;
    }

    public static final class Sink {

        private Sink() {}

        private static void ensureMutable() {
            if (frozen) throw new IllegalStateException(CommonCompatDiagnostics.MSG_COMPATSERVICES_IS_ALREADY_FROZEN);
        }

        public Sink shields(ShieldCompatProvider provider) {
            ensureMutable();
            shields = Objects.requireNonNull(provider, CommonCompatIds.ID_PROVIDER);
            return this;
        }

        public Sink weapons(WeaponCompatProvider provider) {
            ensureMutable();
            weapons = Objects.requireNonNull(provider, CommonCompatIds.ID_PROVIDER);
            return this;
        }

        public Sink projectilePhysics(ProjectilePhysicsProvider provider) {
            ensureMutable();
            projectilePhysics = Objects.requireNonNull(provider, CommonCompatIds.ID_PROVIDER);
            return this;
        }

        public Sink doors(DoorCompatProvider provider) {
            ensureMutable();
            doors = Objects.requireNonNull(provider, CommonCompatIds.ID_PROVIDER);
            return this;
        }

        public Sink worldContent(WorldExtraContentProvider provider) {
            ensureMutable();
            worldContent = Objects.requireNonNull(provider, CommonCompatIds.ID_PROVIDER);
            return this;
        }

        public Sink nutrition(NutritionCompatProvider provider) {
            ensureMutable();
            nutrition = Objects.requireNonNull(provider, CommonCompatIds.ID_PROVIDER);
            return this;
        }

        public Sink lifecycleStorage(LifecycleStorageCompatProvider provider) {
            ensureMutable();
            lifecycleStorage = Objects.requireNonNull(provider, CommonCompatIds.ID_PROVIDER);
            return this;
        }

        public Sink backpacks(BackpackContainerCompatProvider provider) {
            ensureMutable();
            backpacks = Objects.requireNonNull(provider, CommonCompatIds.ID_PROVIDER);
            return this;
        }
    }
}
