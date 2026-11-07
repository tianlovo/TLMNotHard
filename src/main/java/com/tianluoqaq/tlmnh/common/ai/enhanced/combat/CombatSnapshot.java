package com.tianluoqaq.tlmnh.common.ai.enhanced.combat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import com.tianluoqaq.tlmnh.common.combat.weapon.MaidWeaponProfile;

/** 世界边界一次提取的纯值；敌人的运动只由可见样本推导 */
public final class CombatSnapshot {

    public static final int OBSERVATION_TTL = 8, GEOMETRY_TTL = 8, HIDDEN_TTL = 100;
    public final long tick, entityEpoch, observationEpoch, equipmentEpoch;
    public final UUID selfId;
    public final Self self;
    public final List<Target> targets;
    public final List<Projectile> projectiles;
    public final List<Friend> friends;
    public final List<Weapon> weapons;
    public final Geometry geometry;
    public final UUID selectedTarget;
    public final boolean complete;
    public final boolean friendsComplete;
    public final long oldestCoverage;

    public CombatSnapshot(long tick, long entityEpoch, long observationEpoch, long equipmentEpoch, UUID selfId,
        Self self, List<Target> targets, List<Projectile> projectiles, List<Friend> friends, List<Weapon> weapons,
        Geometry geometry, UUID selectedTarget, boolean complete) {
        this(
            tick,
            entityEpoch,
            observationEpoch,
            equipmentEpoch,
            selfId,
            self,
            targets,
            projectiles,
            friends,
            weapons,
            geometry,
            selectedTarget,
            complete,
            friends.isEmpty(),
            tick);
    }

    public CombatSnapshot(long tick, long entityEpoch, long observationEpoch, long equipmentEpoch, UUID selfId,
        Self self, List<Target> targets, List<Projectile> projectiles, List<Friend> friends, List<Weapon> weapons,
        Geometry geometry, UUID selectedTarget, boolean complete, boolean friendsComplete, long oldestCoverage) {
        this.tick = tick;
        this.entityEpoch = entityEpoch;
        this.observationEpoch = observationEpoch;
        this.equipmentEpoch = equipmentEpoch;
        this.selfId = selfId;
        this.self = self;
        this.targets = frozen(targets);
        this.projectiles = frozen(projectiles);
        this.friends = frozen(friends);
        this.weapons = frozen(weapons);
        this.geometry = geometry;
        this.selectedTarget = selectedTarget;
        boolean currentFriends = friendsComplete;
        for (Friend friend : friends)
            currentFriends &= friend.id != null && friend.current && friend.observedAt == tick;
        this.friendsComplete = currentFriends;
        this.oldestCoverage = oldestCoverage;
        this.complete = complete && currentFriends
            && oldestCoverage != Long.MIN_VALUE
            && oldestCoverage <= tick
            && tick - oldestCoverage >= 0
            && tick - oldestCoverage <= OBSERVATION_TTL;
    }

    private static <T> List<T> frozen(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    public Target target(UUID id) {
        for (Target target : targets) if (target.id.equals(id)) return target;
        return null;
    }

    public Weapon weapon(int slot) {
        for (Weapon weapon : weapons) if (weapon.slot == slot) return weapon;
        return null;
    }

    public static final class Vec {

        public final double x, y, z;

        public Vec(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public Vec add(Vec value) {
            return new Vec(x + value.x, y + value.y, z + value.z);
        }

        public Vec scale(double value) {
            return new Vec(x * value, y * value, z * value);
        }

        public Vec subtract(Vec value) {
            return new Vec(x - value.x, y - value.y, z - value.z);
        }

        public double length() {
            return Math.sqrt(x * x + y * y + z * z);
        }

        public double distance(Vec value) {
            return subtract(value).length();
        }

        public Vec horizontalUnit() {
            double length = Math.sqrt(x * x + z * z);
            return length < 1.0E-6 ? new Vec(0, 0, 0) : new Vec(x / length, 0, z / length);
        }
    }

    public static final class Self {

        public final Vec position, velocity, ownerPosition;
        public final float health, ownerHealth;
        public final double halfWidth, height, reach, movementPerTick;
        public final boolean grounded, jumpAllowed, stationary, shieldAvailable, equipped, cadenceReady, drawing;
        public final int attackDelay, drawTicks, ammunition;
        public final long cadenceRemainingTicks;
        public final boolean preferBow, preferencePending;

        public Self(Vec position, Vec velocity, Vec ownerPosition, float health, float ownerHealth, double halfWidth,
            double height, double reach, double movementPerTick, boolean grounded, boolean jumpAllowed,
            boolean stationary, boolean shieldAvailable, boolean equipped, boolean cadenceReady, boolean drawing,
            int attackDelay, long cadenceRemainingTicks, int drawTicks, int ammunition, boolean preferBow,
            boolean preferencePending) {
            this.position = position;
            this.velocity = velocity;
            this.ownerPosition = ownerPosition;
            this.health = Math.max(1, health);
            this.ownerHealth = Math.max(1, ownerHealth);
            this.halfWidth = halfWidth;
            this.height = height;
            this.reach = reach;
            this.movementPerTick = movementPerTick;
            this.grounded = grounded;
            this.jumpAllowed = jumpAllowed;
            this.stationary = stationary;
            this.shieldAvailable = shieldAvailable;
            this.equipped = equipped;
            this.cadenceRemainingTicks = cadenceRemainingTicks < 0 ? Long.MAX_VALUE : cadenceRemainingTicks;
            this.cadenceReady = cadenceReady && this.cadenceRemainingTicks == 0;
            this.drawing = drawing;
            this.attackDelay = Math.max(1, attackDelay);
            this.drawTicks = drawTicks;
            this.ammunition = ammunition;
            this.preferBow = preferBow;
            this.preferencePending = preferencePending;
        }
    }

    public static final class Target {

        public final UUID id;
        public final int entityId;
        public final Vec position, velocity;
        public final long observedAt, lastOwnHitAt;
        public final float health, estimatedDamage;
        public final double halfWidth, height;
        public final boolean visible, swinging, ranged, explosive, attackingOwner;

        public Target(UUID id, int entityId, Vec position, Vec velocity, long observedAt, long lastOwnHitAt,
            float health, float estimatedDamage, double halfWidth, double height, boolean visible, boolean swinging,
            boolean ranged, boolean explosive, boolean attackingOwner) {
            this.id = id;
            this.entityId = entityId;
            this.position = position;
            this.velocity = velocity;
            this.observedAt = observedAt;
            this.lastOwnHitAt = lastOwnHitAt;
            this.health = Math.max(1, health);
            this.estimatedDamage = Math.max(1, estimatedDamage);
            this.halfWidth = halfWidth;
            this.height = height;
            this.visible = visible;
            this.swinging = swinging;
            this.ranged = ranged;
            this.explosive = explosive;
            this.attackingOwner = attackingOwner;
        }

        public boolean fresh(long now) {
            return now >= observedAt && now - observedAt <= (visible ? OBSERVATION_TTL : HIDDEN_TTL);
        }
    }

    public static final class Weapon {

        public final int slot;
        public final String fingerprint;
        public final MaidWeaponProfile profile;

        public Weapon(int slot, String fingerprint, MaidWeaponProfile profile) {
            this.slot = slot;
            this.fingerprint = fingerprint;
            this.profile = profile;
        }
    }

    public static final class Projectile {

        public final UUID id;
        public final Vec position, velocity;
        public final long observedAt;
        public final double gravity, drag, radius, estimatedDamage;

        public Projectile(UUID id, Vec position, Vec velocity, long observedAt, double gravity, double drag,
            double radius, double estimatedDamage) {
            this.id = id;
            this.position = position;
            this.velocity = velocity;
            this.observedAt = observedAt;
            this.gravity = gravity;
            this.drag = drag;
            this.radius = radius;
            this.estimatedDamage = estimatedDamage;
        }
    }

    public static final class Friend {

        public final UUID id;
        public final int entityId, dimension;
        public final long observedAt, coverageRound;
        public final boolean current;
        public final Vec position;
        public final double halfWidth, height;

        public Friend(Vec position, double halfWidth, double height) {
            this(null, -1, -1, Long.MIN_VALUE, 0, false, position, halfWidth, height);
        }

        public Friend(UUID id, int entityId, int dimension, long observedAt, long coverageRound, boolean current,
            Vec position, double halfWidth, double height) {
            this.id = id;
            this.entityId = entityId;
            this.dimension = dimension;
            this.observedAt = observedAt;
            this.coverageRound = coverageRound;
            this.current = current;
            this.position = position;
            this.halfWidth = halfWidth;
            this.height = height;
        }
    }

    public static final class Geometry {

        public static final byte SOLID = 1, WATER = 2, HAZARD = 4, UNKNOWN = 8;
        public final int x, y, z, sizeX, sizeY, sizeZ;
        public final long observedAt;
        public final boolean complete;
        private final byte[] cells;
        private final double[] bounds;

        public Geometry(int x, int y, int z, int sizeX, int sizeY, int sizeZ, long observedAt, boolean complete,
            byte[] cells) {
            this(x, y, z, sizeX, sizeY, sizeZ, observedAt, complete, cells, null);
        }

        public Geometry(int x, int y, int z, int sizeX, int sizeY, int sizeZ, long observedAt, boolean complete,
            byte[] cells, double[] bounds) {
            if (bounds != null && bounds.length != cells.length * 6)
                throw new IllegalArgumentException("Invalid bounded shape dimensions");
            if (cells.length != sizeX * sizeY * sizeZ || cells.length > 512)
                throw new IllegalArgumentException("Combat geometry outside bounded output");
            this.x = x;
            this.y = y;
            this.z = z;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.observedAt = observedAt;
            this.complete = complete;
            this.cells = cells.clone();
            this.bounds = bounds == null ? null : bounds.clone();
        }

        public int at(int px, int py, int pz) {
            px -= x;
            py -= y;
            pz -= z;
            if (px < 0 || py < 0 || pz < 0 || px >= sizeX || py >= sizeY || pz >= sizeZ) return UNKNOWN;
            return cells[(py * sizeZ + pz) * sizeX + px];
        }

        public double bound(int px, int py, int pz, int side) {
            int lx = px - x, ly = py - y, lz = pz - z;
            if (bounds == null || lx < 0 || ly < 0 || lz < 0 || lx >= sizeX || ly >= sizeY || lz >= sizeZ)
                return (side % 3 == 0 ? px : side % 3 == 1 ? py : pz) + (side >= 3 ? 1 : 0);
            return bounds[((ly * sizeZ + lz) * sizeX + lx) * 6 + side];
        }

        public boolean fresh(long now) {
            return now >= observedAt && now - observedAt <= GEOMETRY_TTL;
        }
    }
}
