package com.tianluoqaq.tlmnh.common.compat;

import java.util.Objects;
import java.util.ServiceLoader;

import com.tianluoqaq.tlmnh.common.ai.spi.DoorCompatProvider;
import com.tianluoqaq.tlmnh.common.ai.spi.WorldExtraContentProvider;
import com.tianluoqaq.tlmnh.common.combat.spi.HealingAxeCompatProvider;
import com.tianluoqaq.tlmnh.common.combat.spi.ProjectilePhysicsProvider;
import com.tianluoqaq.tlmnh.common.combat.spi.ShieldCompatProvider;
import com.tianluoqaq.tlmnh.common.combat.spi.WeaponCompatProvider;
import com.tianluoqaq.tlmnh.common.inventory.spi.BackpackContainerCompatProvider;
import com.tianluoqaq.tlmnh.common.life.armor.spi.SpecialArmorCompatProvider;
import com.tianluoqaq.tlmnh.common.life.fall.spi.FallRescueBoatCompatProvider;
import com.tianluoqaq.tlmnh.common.life.flight.spi.FlightEquipmentCompatProvider;
import com.tianluoqaq.tlmnh.common.life.hazard.spi.HotItemHazardCompatProvider;
import com.tianluoqaq.tlmnh.common.life.hunger.spi.HungerPolicyCompatProvider;
import com.tianluoqaq.tlmnh.common.life.space.spi.SpaceSurvivalCompatProvider;
import com.tianluoqaq.tlmnh.common.life.spi.NutritionCompatProvider;
import com.tianluoqaq.tlmnh.common.lifecycle.spi.LifecycleStorageCompatProvider;
import com.tianluoqaq.tlmnh.common.work.breeding.spi.CropsNhBreedingCompatProvider;
import com.tianluoqaq.tlmnh.common.work.maintenance.spi.MaintenanceHatchCompatProvider;

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
    private static MaintenanceHatchCompatProvider maintenanceHatch = MaintenanceHatchCompatProvider.NOOP;
    private static CropsNhBreedingCompatProvider cropsNhBreeding = CropsNhBreedingCompatProvider.NOOP;
    private static HungerPolicyCompatProvider hungerPolicy = HungerPolicyCompatProvider.NOOP;
    private static HealingAxeCompatProvider healingAxe = HealingAxeCompatProvider.NOOP;
    private static HotItemHazardCompatProvider hotItemHazard = HotItemHazardCompatProvider.NOOP;
    private static SpecialArmorCompatProvider specialArmor = SpecialArmorCompatProvider.NOOP;
    private static FlightEquipmentCompatProvider flightEquipment = FlightEquipmentCompatProvider.NOOP;
    private static SpaceSurvivalCompatProvider spaceSurvival = SpaceSurvivalCompatProvider.NOOP;
    private static FallRescueBoatCompatProvider fallRescueBoat = FallRescueBoatCompatProvider.NOOP;
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

    public static MaintenanceHatchCompatProvider maintenanceHatch() {
        return maintenanceHatch;
    }

    public static CropsNhBreedingCompatProvider cropsNhBreeding() {
        return cropsNhBreeding;
    }

    public static HungerPolicyCompatProvider hungerPolicy() {
        return hungerPolicy;
    }

    public static HealingAxeCompatProvider healingAxe() {
        return healingAxe;
    }

    public static HotItemHazardCompatProvider hotItemHazard() {
        return hotItemHazard;
    }

    public static SpecialArmorCompatProvider specialArmor() {
        return specialArmor;
    }

    public static FlightEquipmentCompatProvider flightEquipment() {
        return flightEquipment;
    }

    public static SpaceSurvivalCompatProvider spaceSurvival() {
        return spaceSurvival;
    }

    public static FallRescueBoatCompatProvider fallRescueBoat() {
        return fallRescueBoat;
    }

    public static final class Sink {

        public Sink hungerPolicy(HungerPolicyCompatProvider provider) {
            ensureMutable();
            hungerPolicy = Objects.requireNonNull(provider, CommonCompatIds.ID_PROVIDER);
            return this;
        }

        public Sink healingAxe(HealingAxeCompatProvider provider) {
            ensureMutable();
            healingAxe = Objects.requireNonNull(provider, CommonCompatIds.ID_PROVIDER);
            return this;
        }

        public Sink hotItemHazard(HotItemHazardCompatProvider provider) {
            ensureMutable();
            hotItemHazard = Objects.requireNonNull(provider, CommonCompatIds.ID_PROVIDER);
            return this;
        }

        public Sink specialArmor(SpecialArmorCompatProvider provider) {
            ensureMutable();
            specialArmor = Objects.requireNonNull(provider, CommonCompatIds.ID_PROVIDER);
            return this;
        }

        public Sink flightEquipment(FlightEquipmentCompatProvider provider) {
            ensureMutable();
            flightEquipment = Objects.requireNonNull(provider, CommonCompatIds.ID_PROVIDER);
            return this;
        }

        public Sink spaceSurvival(SpaceSurvivalCompatProvider provider) {
            ensureMutable();
            spaceSurvival = Objects.requireNonNull(provider, CommonCompatIds.ID_PROVIDER);
            return this;
        }

        public Sink fallRescueBoat(FallRescueBoatCompatProvider provider) {
            ensureMutable();
            fallRescueBoat = Objects.requireNonNull(provider, CommonCompatIds.ID_PROVIDER);
            return this;
        }

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

        public Sink maintenanceHatch(MaintenanceHatchCompatProvider provider) {
            ensureMutable();
            maintenanceHatch = Objects.requireNonNull(provider, CommonCompatIds.ID_PROVIDER);
            return this;
        }

        public Sink cropsNhBreeding(CropsNhBreedingCompatProvider provider) {
            ensureMutable();
            cropsNhBreeding = Objects.requireNonNull(provider, CommonCompatIds.ID_PROVIDER);
            return this;
        }
    }
}
