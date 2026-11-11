package com.tianluoqaq.tlmnh.common.combat.spi;

import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.util.Vec3;

/** 维度箭矢重力与水流物理的窄适配契约；默认保留原版重力与未缩放液体流向 */
public interface ProjectilePhysicsProvider {

    LiquidFlowSample VANILLA_LIQUID_FLOW = new LiquidFlowSample() {

        @Override
        public boolean isCurrent() {
            return true;
        }

        @Override
        public boolean adjusted() {
            return false;
        }

        @Override
        public Vec3 cellFlow(Vec3 original, int metadata) {
            return original;
        }

        @Override
        public Vec3 normalizeTotal(Vec3 sum) {
            return sum.normalize();
        }
    };

    ProjectilePhysicsProvider NOOP = new ProjectilePhysicsProvider() {};

    interface ArrowLaunchSample {

        float gravity();

        boolean isCurrent(EntityArrow candidate);

        boolean sameObservationInput(EntityArrow candidate);
    }

    interface LiquidFlowSample {

        boolean isCurrent();

        boolean adjusted();

        Vec3 cellFlow(Vec3 original, int metadata);

        Vec3 normalizeTotal(Vec3 sum);
    }

    default boolean arrowGravityAvailable() {
        return false;
    }

    default float arrowGravity(EntityArrow arrow) {
        return CombatSpiTuning.PROJECTILE_PHYSICS_ARROW_GRAVITY_FACTOR;
    }

    default ArrowLaunchSample sampleArrowLaunch(EntityArrow arrow) {
        return null;
    }

    default LiquidFlowSample sampleLiquidFlow() {
        return VANILLA_LIQUID_FLOW;
    }
}
