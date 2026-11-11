package com.tianluoqaq.tlmnh.api.behavior;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.life.MaidLifeRules.Activity;

/** 当前回调帧有效的只读观测视图；除 isCurrent 外在过期帧调用均拒绝并抛出异常 */
public interface MaidObservationView {

    /** 检查当前回调帧与底层策略授权是否仍然有效，过期引用安全返回 false */
    boolean isCurrent();

    long worldTick();

    int entityTicks();

    Vec3d position();

    Vec3d velocity();

    float health();

    float maxHealth();

    int airSupply();

    int hunger();

    int favorability();

    MaidTask task();

    MaidSchedule schedule();

    Activity activity();

    boolean isSitting();

    boolean isRiding();

    boolean isLeashed();

    boolean isSleeping();

    boolean isInWater();

    boolean isHurt();

    boolean canBrainMove();

    boolean isHomeEnabled();

    boolean isWithinActiveDomain(double x, double y, double z);

    EntitySnapshot owner();

    List<EntitySnapshot> visibleEntities();

    List<EntitySnapshot> nearbyEntities();

    WeaponCapabilitySnapshot weaponCapability();

    List<ProjectileThreatSnapshot> projectileThreats();

    final class Vec3d {

        public final double x;
        public final double y;
        public final double z;

        public Vec3d(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public double distanceSq(double px, double py, double pz) {
            double dx = x - px, dy = y - py, dz = z - pz;
            return dx * dx + dy * dy + dz * dz;
        }

        public double distanceSq(Vec3d other) {
            return distanceSq(other.x, other.y, other.z);
        }

        @Override
        public boolean equals(Object obj) {
            return this == obj || obj instanceof Vec3d other && Double.compare(x, other.x) == 0
                && Double.compare(y, other.y) == 0
                && Double.compare(z, other.z) == 0;
        }

        @Override
        public int hashCode() {
            return Objects.hash(x, y, z);
        }

        @Override
        public String toString() {
            return "(" + x + ", " + y + ", " + z + ")";
        }
    }

    final class BlockPoint {

        public final int x;
        public final int y;
        public final int z;

        public BlockPoint(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public boolean equals(Object obj) {
            return this == obj || obj instanceof BlockPoint other && x == other.x && y == other.y && z == other.z;
        }

        @Override
        public int hashCode() {
            return (x * 31 + y) * 31 + z;
        }

        @Override
        public String toString() {
            return x + ":" + y + ":" + z;
        }
    }

    final class EntitySnapshot {

        public final UUID uniqueId;
        public final int entityId;
        public final Vec3d position;
        public final Vec3d velocity;
        public final float health;
        public final float maxHealth;
        public final double distanceSq;
        public final boolean hostile;
        public final boolean friendly;
        public final boolean visible;

        public EntitySnapshot(UUID uniqueId, int entityId, Vec3d position, Vec3d velocity, float health,
            float maxHealth, double distanceSq, boolean hostile, boolean friendly, boolean visible) {
            this.uniqueId = Objects.requireNonNull(uniqueId, "uniqueId");
            this.entityId = entityId;
            this.position = Objects.requireNonNull(position, "position");
            this.velocity = Objects.requireNonNull(velocity, "velocity");
            this.health = health;
            this.maxHealth = maxHealth;
            this.distanceSq = distanceSq;
            this.hostile = hostile;
            this.friendly = friendly;
            this.visible = visible;
        }
    }

    final class WeaponCapabilitySnapshot {

        public final boolean supportedMelee;
        public final boolean supportedBow;
        public final boolean hasAmmunition;
        public final double meleeReach;
        public final long cooldownRemainingTicks;

        public WeaponCapabilitySnapshot(boolean supportedMelee, boolean supportedBow, boolean hasAmmunition,
            double meleeReach, long cooldownRemainingTicks) {
            this.supportedMelee = supportedMelee;
            this.supportedBow = supportedBow;
            this.hasAmmunition = hasAmmunition;
            this.meleeReach = meleeReach;
            this.cooldownRemainingTicks = cooldownRemainingTicks;
        }
    }

    final class ProjectileThreatSnapshot {

        public final UUID uniqueId;
        public final Vec3d position;
        public final Vec3d velocity;
        public final long observedAt;

        public ProjectileThreatSnapshot(UUID uniqueId, Vec3d position, Vec3d velocity, long observedAt) {
            this.uniqueId = Objects.requireNonNull(uniqueId, "uniqueId");
            this.position = Objects.requireNonNull(position, "position");
            this.velocity = Objects.requireNonNull(velocity, "velocity");
            this.observedAt = observedAt;
        }
    }
}
