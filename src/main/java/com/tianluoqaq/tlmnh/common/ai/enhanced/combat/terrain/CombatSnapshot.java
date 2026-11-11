package com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatBudgetTuning;
import com.tianluoqaq.tlmnh.common.combat.weapon.MaidWeaponProfile;

/** 世界边界一次提取的纯值；敌人的运动只由可见样本推导 */
public final class CombatSnapshot {

    public enum DamageSource {
        GENERIC_PRIOR,
        UNKNOWN
    }

    public final CombatMovementFact movementFact;

    public final long tick, entityEpoch, observationEpoch, equipmentEpoch;
    public final UUID selfId;
    public final Self self;
    public final List<Target> targets;
    public final List<Projectile> projectiles;
    public final List<Friend> friends;
    public final List<Weapon> weapons;
    public final Geometry geometry;
    public final UUID selectedTarget;
    public final boolean complete, bodyComplete;
    public final boolean continuationComplete, bodyContinuationComplete;
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
            friendsComplete,
            oldestCoverage,
            null);
    }

    public CombatSnapshot(long tick, long entityEpoch, long observationEpoch, long equipmentEpoch, UUID selfId,
        Self self, List<Target> targets, List<Projectile> projectiles, List<Friend> friends, List<Weapon> weapons,
        Geometry geometry, UUID selectedTarget, boolean complete, boolean friendsComplete, long oldestCoverage,
        CombatMovementFact movementFact) {
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
            friendsComplete,
            oldestCoverage,
            movementFact,
            complete);
    }

    /** 身体资格显式提供，旧构造器不把原不完整输入升级 */
    public CombatSnapshot(long tick, long entityEpoch, long observationEpoch, long equipmentEpoch, UUID selfId,
        Self self, List<Target> targets, List<Projectile> projectiles, List<Friend> friends, List<Weapon> weapons,
        Geometry geometry, UUID selectedTarget, boolean complete, boolean friendsComplete, long oldestCoverage,
        CombatMovementFact movementFact, boolean bodyComplete) {
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
            friendsComplete,
            oldestCoverage,
            movementFact,
            bodyComplete,
            friendsComplete || !friends.isEmpty());
    }

    /** 显式区分当拍完整同伴刷新与TTL内无溢出/身份缺口的近战阶段续行资格 */
    public CombatSnapshot(long tick, long entityEpoch, long observationEpoch, long equipmentEpoch, UUID selfId,
        Self self, List<Target> targets, List<Projectile> projectiles, List<Friend> friends, List<Weapon> weapons,
        Geometry geometry, UUID selectedTarget, boolean complete, boolean friendsComplete, long oldestCoverage,
        CombatMovementFact movementFact, boolean bodyComplete, boolean friendsBounded) {
        this.movementFact = movementFact;
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
        boolean freshFriends = friendsBounded;
        for (Friend friend : this.friends) {
            currentFriends &= friend.id != null && friend.current && friend.observedAt == tick;
            freshFriends &= friend.id != null && friend.observedAt != Long.MIN_VALUE
                && friend.observedAt <= tick
                && tick - friend.observedAt <= CombatBudgetTuning.OBSERVATION_TTL;
        }
        this.friendsComplete = currentFriends;
        this.oldestCoverage = oldestCoverage;
        boolean freshGeometry = geometry != null && geometry.complete && geometry.fresh(tick);
        boolean freshCoverage = oldestCoverage != Long.MIN_VALUE && oldestCoverage <= tick
            && tick - oldestCoverage >= 0
            && tick - oldestCoverage <= CombatBudgetTuning.OBSERVATION_TTL;
        this.bodyComplete = bodyComplete && currentFriends
            && geometry != null
            && geometry.complete
            && geometry.fresh(tick)
            && oldestCoverage != Long.MIN_VALUE
            && oldestCoverage <= tick
            && tick - oldestCoverage <= CombatBudgetTuning.OBSERVATION_TTL;
        this.complete = complete && currentFriends
            && oldestCoverage != Long.MIN_VALUE
            && oldestCoverage <= tick
            && tick - oldestCoverage >= 0
            && tick - oldestCoverage <= CombatBudgetTuning.OBSERVATION_TTL;
        this.bodyContinuationComplete = bodyComplete && freshFriends && freshGeometry && freshCoverage;
        this.continuationComplete = complete && freshFriends && freshGeometry && freshCoverage;
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
            return length < CombatTerrainTuning.MIN_LENGTH_THRESHOLD
                ? new Vec(
                    CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_ZERO_BASE,
                    CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_ZERO_BASE,
                    CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_ZERO_BASE)
                : new Vec(x / length, CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_ZERO_BASE, z / length);
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
        public final boolean recentlyHurt;
        public final double ownerHalfWidth, ownerHeight;

        public Self(Vec position, Vec velocity, Vec ownerPosition, float health, float ownerHealth, double halfWidth,
            double height, double reach, double movementPerTick, boolean grounded, boolean jumpAllowed,
            boolean stationary, boolean shieldAvailable, boolean equipped, boolean cadenceReady, boolean drawing,
            int attackDelay, long cadenceRemainingTicks, int drawTicks, int ammunition, boolean preferBow,
            boolean preferencePending) {
            this(
                position,
                velocity,
                ownerPosition,
                health,
                ownerHealth,
                halfWidth,
                height,
                reach,
                movementPerTick,
                grounded,
                jumpAllowed,
                stationary,
                shieldAvailable,
                equipped,
                cadenceReady,
                drawing,
                attackDelay,
                cadenceRemainingTicks,
                drawTicks,
                ammunition,
                preferBow,
                preferencePending,
                false);
        }

        public Self(Vec position, Vec velocity, Vec ownerPosition, float health, float ownerHealth, double halfWidth,
            double height, double reach, double movementPerTick, boolean grounded, boolean jumpAllowed,
            boolean stationary, boolean shieldAvailable, boolean equipped, boolean cadenceReady, boolean drawing,
            int attackDelay, long cadenceRemainingTicks, int drawTicks, int ammunition, boolean preferBow,
            boolean preferencePending, boolean recentlyHurt) {
            this(
                position,
                velocity,
                ownerPosition,
                health,
                ownerHealth,
                halfWidth,
                height,
                reach,
                movementPerTick,
                grounded,
                jumpAllowed,
                stationary,
                shieldAvailable,
                equipped,
                cadenceReady,
                drawing,
                attackDelay,
                cadenceRemainingTicks,
                drawTicks,
                ammunition,
                preferBow,
                preferencePending,
                recentlyHurt,
                CombatTerrainTuning.COMBAT_SNAPSHOT_DOMAIN_FACTOR,
                CombatTerrainTuning.COMBAT_SNAPSHOT_DOMAIN_FACTOR_2);
        }

        public Self(Vec position, Vec velocity, Vec ownerPosition, float health, float ownerHealth, double halfWidth,
            double height, double reach, double movementPerTick, boolean grounded, boolean jumpAllowed,
            boolean stationary, boolean shieldAvailable, boolean equipped, boolean cadenceReady, boolean drawing,
            int attackDelay, long cadenceRemainingTicks, int drawTicks, int ammunition, boolean preferBow,
            boolean preferencePending, boolean recentlyHurt, double ownerHalfWidth, double ownerHeight) {
            this.ownerHalfWidth = ownerHalfWidth;
            this.ownerHeight = ownerHeight;
            this.recentlyHurt = recentlyHurt;
            this.position = position;
            this.velocity = velocity;
            this.ownerPosition = ownerPosition;
            this.health = Math.max(CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_UNIT_STEP, health);
            this.ownerHealth = Math.max(CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_UNIT_STEP, ownerHealth);
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
            this.attackDelay = Math.max(CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_UNIT_STEP, attackDelay);
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
        public final DamageSource damageSource;
        public final double halfWidth, height;
        public final boolean visible, swinging, ranged, explosive, attackingOwner;

        public Target(UUID id, int entityId, Vec position, Vec velocity, long observedAt, long lastOwnHitAt,
            float health, float estimatedDamage, double halfWidth, double height, boolean visible, boolean swinging,
            boolean ranged, boolean explosive, boolean attackingOwner) {
            this(
                id,
                entityId,
                position,
                velocity,
                observedAt,
                lastOwnHitAt,
                health,
                estimatedDamage,
                halfWidth,
                height,
                visible,
                swinging,
                ranged,
                explosive,
                attackingOwner,
                DamageSource.GENERIC_PRIOR);
        }

        public Target(UUID id, int entityId, Vec position, Vec velocity, long observedAt, long lastOwnHitAt,
            float health, float estimatedDamage, double halfWidth, double height, boolean visible, boolean swinging,
            boolean ranged, boolean explosive, boolean attackingOwner, DamageSource damageSource) {
            this.damageSource = damageSource == null || !Double.isFinite(estimatedDamage) || estimatedDamage <= 0
                ? DamageSource.UNKNOWN
                : damageSource;
            this.id = id;
            this.entityId = entityId;
            this.position = position;
            this.velocity = velocity;
            this.observedAt = observedAt;
            this.lastOwnHitAt = lastOwnHitAt;
            this.health = Math.max(CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_UNIT_STEP, health);
            this.estimatedDamage = Math.max(CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_UNIT_STEP, estimatedDamage);
            this.halfWidth = halfWidth;
            this.height = height;
            this.visible = visible;
            this.swinging = swinging;
            this.ranged = ranged;
            this.explosive = explosive;
            this.attackingOwner = attackingOwner;
        }

        public boolean fresh(long now) {
            return now >= observedAt
                && now - observedAt <= (visible ? CombatBudgetTuning.OBSERVATION_TTL : CombatTerrainTuning.HIDDEN_TTL);
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
        public final DamageSource damageSource;

        public Projectile(UUID id, Vec position, Vec velocity, long observedAt, double gravity, double drag,
            double radius, double estimatedDamage) {
            this(
                id,
                position,
                velocity,
                observedAt,
                gravity,
                drag,
                radius,
                estimatedDamage,
                DamageSource.GENERIC_PRIOR);
        }

        public Projectile(UUID id, Vec position, Vec velocity, long observedAt, double gravity, double drag,
            double radius, double estimatedDamage, DamageSource damageSource) {
            this.damageSource = damageSource == null || !Double.isFinite(estimatedDamage) || estimatedDamage <= 0
                ? DamageSource.UNKNOWN
                : damageSource;
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
            this(
                null,
                CombatTerrainTuning.UNSET_OR_NOT_FOUND,
                CombatTerrainTuning.UNSET_OR_NOT_FOUND,
                Long.MIN_VALUE,
                CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_ZERO_BASE,
                false,
                position,
                halfWidth,
                height);
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

    /** 本轮已付费木门样本的纯值身份；不授予门操作权限 */
    public static final class DoorCell {

        public final String provider;
        public final int x, y, z, queryY, metadata, lowerMetadata, upperMetadata;
        public final boolean centered, stableOpened;

        public DoorCell(String provider, int x, int y, int z, int queryY, int metadata, int lowerMetadata,
            int upperMetadata, boolean centered, boolean stableOpened) {
            this.provider = provider;
            this.x = x;
            this.y = y;
            this.z = z;
            this.queryY = queryY;
            this.metadata = metadata;
            this.lowerMetadata = lowerMetadata;
            this.upperMetadata = upperMetadata;
            this.centered = centered;
            this.stableOpened = stableOpened;
        }

        public boolean sameDoor(DoorCell other) {
            return other != null && CombatTerrainIds.MALISIS_WOOD.equals(provider)
                && CombatTerrainIds.MALISIS_WOOD.equals(other.provider)
                && stableOpened
                && other.stableOpened
                && x == other.x
                && y == other.y
                && z == other.z
                && metadata == other.metadata
                && lowerMetadata == other.lowerMetadata
                && upperMetadata == other.upperMetadata
                && centered == other.centered;
        }
    }

    public static final class Geometry {

        public final int x, y, z, sizeX, sizeY, sizeZ;
        public final long observedAt;
        public final boolean complete;
        private final byte[] cells;
        private final double[] bounds;
        private final DoorCell[] doorCells;

        public Geometry(int x, int y, int z, int sizeX, int sizeY, int sizeZ, long observedAt, boolean complete,
            byte[] cells) {
            this(x, y, z, sizeX, sizeY, sizeZ, observedAt, complete, cells, null);
        }

        public Geometry(int x, int y, int z, int sizeX, int sizeY, int sizeZ, long observedAt, boolean complete,
            byte[] cells, double[] bounds) {
            this(x, y, z, sizeX, sizeY, sizeZ, observedAt, complete, cells, bounds, null);
        }

        public Geometry(int x, int y, int z, int sizeX, int sizeY, int sizeZ, long observedAt, boolean complete,
            byte[] cells, double[] bounds, DoorCell[] doorCells) {
            if (bounds != null && bounds.length != cells.length * CombatTerrainTuning.COMBAT_SNAPSHOT_DOMAIN_PARAM)
                throw new IllegalArgumentException(CombatTerrainDiagnostics.MSG_INVALID_BOUNDED_SHAPE_DIMENSIONS);
            if (doorCells != null && doorCells.length != cells.length)
                throw new IllegalArgumentException(CombatTerrainDiagnostics.MSG_INVALID_BOUNDED_DOOR_CELL_DIMENSIONS);
            if (cells.length != sizeX * sizeY * sizeZ || cells.length > CombatTerrainTuning.MAX_LENGTH_THRESHOLD)
                throw new IllegalArgumentException(CombatTerrainDiagnostics.MSG_COMBAT_GEOMETRY_OUTSIDE_BOUNDED_OUTPUT);
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
            this.doorCells = doorCells == null ? null : doorCells.clone();
        }

        public int at(int px, int py, int pz) {
            px -= x;
            py -= y;
            pz -= z;
            if (px < 0 || py < 0 || pz < 0 || px >= sizeX || py >= sizeY || pz >= sizeZ)
                return CombatTerrainTuning.UNKNOWN;
            return cells[(py * sizeZ + pz) * sizeX + px];
        }

        public double bound(int px, int py, int pz, int side) {
            int lx = px - x, ly = py - y, lz = pz - z;
            if (bounds == null || lx < 0 || ly < 0 || lz < 0 || lx >= sizeX || ly >= sizeY || lz >= sizeZ)
                return (side % CombatTerrainTuning.COMBAT_MOVEMENT_DOMAIN_PARAM == 0 ? px
                    : side % CombatTerrainTuning.COMBAT_MOVEMENT_DOMAIN_PARAM
                        == CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_UNIT_STEP ? py : pz)
                    + (side >= CombatTerrainTuning.COMBAT_MOVEMENT_DOMAIN_PARAM
                        ? CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_UNIT_STEP
                        : CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_ZERO_BASE);
            return bounds[((ly * sizeZ + lz) * sizeX + lx) * CombatTerrainTuning.COMBAT_SNAPSHOT_DOMAIN_PARAM + side];
        }

        public DoorCell doorAt(int px, int py, int pz) {
            int lx = px - x, ly = py - y, lz = pz - z;
            if (doorCells == null || lx < 0 || ly < 0 || lz < 0 || lx >= sizeX || ly >= sizeY || lz >= sizeZ)
                return null;
            return doorCells[(ly * sizeZ + lz) * sizeX + lx];
        }

        public boolean fresh(long now) {
            return now >= observedAt && now - observedAt <= CombatTerrainTuning.GEOMETRY_TTL;
        }
    }
}
