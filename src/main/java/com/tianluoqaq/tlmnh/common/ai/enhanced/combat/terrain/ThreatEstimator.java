package com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain;

import java.util.Arrays;

import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatBudgetTuning;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatWorkQuota;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatWorkQuota.Work;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot.Geometry;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot.Projectile;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot.Target;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot.Vec;

/** 只使用可见运动和已发生命中，不读取敌方冷却或未观测的真实位置 */
public final class ThreatEstimator {

    private ThreatEstimator() {}

    /** 同一已付费威胁循环中的单次估值余量。generic 不等同于伤害上界 */
    public static final class Risk {

        public double selfSingle, ownerSingle, ownerReceived, hiddenSingle;
        public final boolean observedOnly;
        private CombatSnapshot projectileSnapshot;
        private int projectileHypothesis;
        private boolean[] selfProjectileHits, ownerProjectileHits;

        public Risk() {
            this(false);
        }

        public Risk(boolean observedOnly) {
            this.observedOnly = observedOnly;
        }

        // 单个 Risk 绑定同一不可变快照与同一预测假设，女仆和主人各自按同箭最多计险一次保留保守风险
        private boolean bindProjectiles(CombatSnapshot snapshot, int hypothesis) {
            if (projectileSnapshot == null) {
                projectileSnapshot = snapshot;
                projectileHypothesis = hypothesis;
                selfProjectileHits = new boolean[snapshot.projectiles.size()];
                ownerProjectileHits = new boolean[snapshot.projectiles.size()];
            }
            if (projectileSnapshot == snapshot && projectileHypothesis == hypothesis) return true;
            known = false;
            return false;
        }

        void hidden(Target target) {
            source(target.damageSource, target.estimatedDamage);
            hiddenSingle = Math.max(
                hiddenSingle,
                target.estimatedDamage
                    * (target.explosive ? CombatTerrainTuning.COMBAT_MOVEMENT_DOMAIN_PARAM
                        : CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_UNIT_STEP)
                    * (target.swinging ? CombatTerrainTuning.COMBAT_SNAPSHOT_DOMAIN_FACTOR_2
                        : CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_UNIT_STEP));
        }

        public boolean known = true, acute;

        void source(CombatSnapshot.DamageSource source, double amount) {
            known &= source != CombatSnapshot.DamageSource.UNKNOWN && Double.isFinite(amount) && amount > 0;
        }

        void self(CombatSnapshot.DamageSource source, double amount) {
            source(source, amount);
            selfSingle = Math.max(selfSingle, amount);
        }

        void owner(Target target) {
            source(target.damageSource, target.estimatedDamage);
            ownerSingle = Math.max(ownerSingle, target.estimatedDamage);
        }
    }

    public static final class ProjectileOcclusion {

        private final double[] blockedAt;

        private ProjectileOcclusion(int size) {
            blockedAt = new double[size];
            Arrays.fill(blockedAt, Double.POSITIVE_INFINITY);
        }
    }

    /** 每轮只提取一次纯轨迹截断，所有候选共享，不在每个分支反复扫描前缀 */
    public static ProjectileOcclusion occlusion(CombatSnapshot snapshot, CombatWorkQuota quota) {
        ProjectileOcclusion result = new ProjectileOcclusion(snapshot.projectiles.size());
        for (int i = 0; i < snapshot.projectiles.size(); i++) {
            Projectile projectile = snapshot.projectiles.get(i);
            for (int tick = 0; tick
                < CombatBudgetTuning.HORIZON + CombatTerrainTuning.COMBAT_CONDITIONAL_FROM_PARAM; tick++) {
                if (!quota.spend(Work.COLLISION)) return result;
                double fraction = CombatTerrain.blockingFraction(
                    snapshot,
                    projectilePosition(projectile, tick),
                    projectilePosition(projectile, tick + 1),
                    quota);
                if (Double.isFinite(fraction)) {
                    result.blockedAt[i] = tick + fraction;
                    break;
                }
            }
        }
        return result;
    }

    public static double damage(CombatSnapshot snapshot, Vec from, Vec to, int elapsed, int hypothesis, boolean shield,
        CombatWorkQuota quota) {
        return damage(snapshot, from, to, elapsed, hypothesis, shield, quota, null);
    }

    public static double damage(CombatSnapshot snapshot, Vec from, Vec to, int elapsed, int hypothesis, boolean shield,
        CombatWorkQuota quota, ProjectileOcclusion occlusion) {
        return damage(snapshot, from, to, elapsed, hypothesis, shield, quota, occlusion, null);
    }

    public static double damage(CombatSnapshot snapshot, Vec from, Vec to, int elapsed, int hypothesis, boolean shield,
        CombatWorkQuota quota, ProjectileOcclusion occlusion, Risk risk) {
        return damage(snapshot, from, to, elapsed, hypothesis, shield, quota, occlusion, risk, null);
    }

    /** A short reaction may span adjacent sweeps; the same arrow contributes at most one hit. */
    public static double damage(CombatSnapshot snapshot, Vec from, Vec to, int elapsed, int hypothesis, boolean shield,
        CombatWorkQuota quota, ProjectileOcclusion occlusion, Risk risk, boolean[] projectileHits) {
        if (risk != null && !risk.bindProjectiles(snapshot, hypothesis)) return Double.POSITIVE_INFINITY;
        double damage = 0;
        for (Target target : snapshot.targets) {
            if (!quota.spend(Work.COLLISION)) return Double.POSITIVE_INFINITY;
            if (!target.visible || !target.fresh(snapshot.tick)) {
                if (risk != null && risk.observedOnly && target.fresh(snapshot.tick)) risk.hidden(target);
                continue;
            }
            if (risk != null) risk.source(target.damageSource, target.estimatedDamage);
            if (risk != null && target.attackingOwner && snapshot.self.ownerPosition != null) {
                risk.owner(target);
                if (enemyPosition(target, snapshot.self.ownerPosition, elapsed, hypothesis)
                    .distance(snapshot.self.ownerPosition) < CombatTerrainTuning.THREAT_ESTIMATOR_DOMAIN_FACTOR)
                    risk.ownerReceived += target.estimatedDamage * CombatTerrainTuning.THREAT_ESTIMATOR_DOMAIN_FACTOR_2;
            }
            Vec predicted = enemyPosition(target, snapshot.self.position, elapsed, hypothesis);
            double distance = predicted.distance(to), uncertainty = CombatTerrainTuning.COMBAT_SPACE_EXPAND_FIRST_FACTOR
                + elapsed * CombatTerrainTuning.COMBAT_TERRAIN_DOMAIN_FACTOR_4;
            double contact = distance
                <= CombatTerrainTuning.COMBAT_SNAPSHOT_DOMAIN_FACTOR_2 + target.halfWidth + uncertainty
                    ? CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_UNIT_STEP
                    : CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_ZERO_BASE;
            if (target.ranged && distance < CombatTerrainTuning.MIN_DISTANCE_THRESHOLD && target.visible) {
                double exposure = CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_UNIT_STEP
                    - CombatTerrain.protection(snapshot, to, target, predicted, quota);
                contact = Math.max(contact, CombatTerrainTuning.COMBAT_TERRAIN_DOMAIN_FACTOR_5 * exposure);
                if (risk != null && ownerInFireLane(snapshot, target, predicted, to, uncertainty)) {
                    risk.owner(target);
                    risk.ownerReceived += target.estimatedDamage * CombatTerrainTuning.COMBAT_TERRAIN_DOMAIN_FACTOR_5
                        * CombatBudgetTuning.STEP
                        / CombatTerrainTuning.COMBAT_TERRAIN_DOMAIN_PARAM;
                }
            }
            if (target.explosive && distance < CombatTerrainTuning.MIN_DISTANCE_THRESHOLD_2 + uncertainty)
                contact = Math.max(contact, CombatTerrainTuning.COMBAT_MOVEMENT_DOMAIN_PARAM);
            double swing = target.swinging && elapsed <= CombatTerrainTuning.COMBAT_SNAPSHOT_DOMAIN_PARAM
                ? CombatTerrainTuning.COMBAT_SNAPSHOT_DOMAIN_FACTOR_2
                : CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_UNIT_STEP;
            // 此处为不确定接触伤害率，不生成敌方精确出手时刻
            damage += contact * target.estimatedDamage
                * swing
                * CombatBudgetTuning.STEP
                / CombatTerrainTuning.COMBAT_TERRAIN_DOMAIN_PARAM;
            if (risk != null && contact > 0) {
                risk.self(
                    target.damageSource,
                    target.estimatedDamage * swing
                        * (target.explosive ? CombatTerrainTuning.COMBAT_MOVEMENT_DOMAIN_PARAM
                            : CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_UNIT_STEP));
                if (elapsed <= CombatTerrainTuning.COMBAT_CONDITIONAL_FROM_PARAM && target.explosive) risk.acute = true;
            }
        }
        for (int index = 0; index < snapshot.projectiles.size(); index++) {
            boolean selfCounted = projectileHits != null && projectileHits[index]
                || risk != null && risk.selfProjectileHits[index];
            if (selfCounted && (risk == null || snapshot.self.ownerPosition == null || risk.ownerProjectileHits[index]))
                continue;
            Projectile projectile = snapshot.projectiles.get(index);
            if (snapshot.tick < projectile.observedAt
                || snapshot.tick - projectile.observedAt > CombatTerrainTuning.COMBAT_CONDITIONAL_FROM_PARAM) continue;
            if (risk != null) risk.source(projectile.damageSource, projectile.estimatedDamage);
            int age = (int) (snapshot.tick - projectile.observedAt);
            // 在两 tick 步进内逐段扫掠检测，避免箭矢在稀疏采样端点之间穿透身体
            for (int part = 0; part < CombatBudgetTuning.STEP; part++) {
                if (!quota.spend(Work.COLLISION)) return Double.POSITIVE_INFINITY;
                Vec a = projectilePosition(projectile, age + elapsed - CombatBudgetTuning.STEP + part);
                Vec b = projectilePosition(projectile, age + elapsed - CombatBudgetTuning.STEP + part + 1);
                Vec selfA = from.add(
                    to.subtract(from)
                        .scale(part / (double) CombatBudgetTuning.STEP));
                Vec selfB = from.add(
                    to.subtract(from)
                        .scale((part + 1) / (double) CombatBudgetTuning.STEP));
                double body = selfCounted ? Double.POSITIVE_INFINITY
                    : CombatTerrain.entry(
                        a.subtract(selfA),
                        b.subtract(selfB),
                        -snapshot.self.halfWidth - projectile.radius,
                        -projectile.radius,
                        -snapshot.self.halfWidth - projectile.radius,
                        snapshot.self.halfWidth + projectile.radius,
                        snapshot.self.height + projectile.radius,
                        snapshot.self.halfWidth + projectile.radius);
                double startTick = age + elapsed - CombatBudgetTuning.STEP + part;
                double wall = occlusion == null ? CombatTerrain.blockingFraction(snapshot, a, b, quota)
                    : occlusion.blockedAt[index] - startTick;
                if (risk != null && snapshot.self.ownerPosition != null && !risk.ownerProjectileHits[index]) {
                    if (!quota.spend(Work.COLLISION)) {
                        risk.known = false;
                        return Double.POSITIVE_INFINITY;
                    }
                    double ownerBody = CombatTerrain.entry(
                        a.subtract(snapshot.self.ownerPosition),
                        b.subtract(snapshot.self.ownerPosition),
                        -snapshot.self.ownerHalfWidth - projectile.radius,
                        -projectile.radius,
                        -snapshot.self.ownerHalfWidth - projectile.radius,
                        snapshot.self.ownerHalfWidth + projectile.radius,
                        snapshot.self.ownerHeight + projectile.radius,
                        snapshot.self.ownerHalfWidth + projectile.radius);
                    if (Double.isFinite(ownerBody) && (!Double.isFinite(wall) || wall >= ownerBody)) {
                        risk.ownerProjectileHits[index] = true;
                        risk.source(projectile.damageSource, projectile.estimatedDamage);
                        risk.ownerSingle = Math.max(risk.ownerSingle, projectile.estimatedDamage);
                        risk.ownerReceived += projectile.estimatedDamage;
                        if (elapsed <= CombatTerrainTuning.COMBAT_CONDITIONAL_FROM_PARAM) risk.acute = true;
                    }
                }
                if (Double.isFinite(wall) && wall < body) break;
                if (Double.isFinite(body)) {
                    if (projectileHits != null) projectileHits[index] = true;
                    damage += projectile.estimatedDamage;
                    if (risk != null) {
                        risk.selfProjectileHits[index] = true;
                        risk.self(projectile.damageSource, projectile.estimatedDamage);
                        if (elapsed <= CombatTerrainTuning.COMBAT_CONDITIONAL_FROM_PARAM) risk.acute = true;
                    }
                    selfCounted = true;
                    // 女仆命中后仍继续检查本区间余下的主人风险
                    if (risk == null || snapshot.self.ownerPosition == null || risk.ownerProjectileHits[index]) break;
                }
            }
        }
        // 仅用于策略收益估计，实际格挡角、破盾与消耗始终由现有盾牌规则处理
        return shield ? damage * CombatTerrainTuning.THREAT_ESTIMATOR_DOMAIN_FACTOR_3 : damage;
    }

    /** 保守保留主人处于潜在射线上的风险，不推断精确出手时刻或垂直命中 */
    private static boolean ownerInFireLane(CombatSnapshot snapshot, Target target, Vec origin, Vec aim,
        double uncertainty) {
        Vec owner = snapshot.self.ownerPosition;
        if (owner == null) return false;
        if (target.attackingOwner) return true;
        double dx = aim.x - origin.x, dz = aim.z - origin.z;
        double lengthSquared = dx * dx + dz * dz;
        if (!Double.isFinite(lengthSquared) || Math.sqrt(lengthSquared) < CombatTerrainTuning.MIN_LENGTH_THRESHOLD)
            return true;
        double ownerX = owner.x - origin.x, ownerZ = owner.z - origin.z;
        if (!Double.isFinite(ownerX + ownerZ + uncertainty)) return true;
        double projection = (ownerX * dx + ownerZ * dz) / lengthSquared;
        if (projection <= 0) return false;
        double lateralX = ownerX - projection * dx, lateralZ = ownerZ - projection * dz;
        double radius = snapshot.self.ownerHalfWidth + projection * (snapshot.self.halfWidth + uncertainty);
        return lateralX * lateralX + lateralZ * lateralZ <= radius * radius;
    }

    public static Vec enemyPosition(Target target, Vec self, int elapsed, int hypothesis) {
        int prediction = Math.min(
            CombatTerrainTuning.COMBAT_SPACE_QUERY_ALLOWANCE_PARAM,
            Math.max(CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_ZERO_BASE, elapsed));
        Vec velocity = target.velocity;
        if (hypothesis == CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_UNIT_STEP) {
            Vec toward = self.subtract(target.position)
                .horizontalUnit();
            double speed = Math.min(
                CombatTerrainTuning.THREAT_ESTIMATOR_MIN_FIRST_FACTOR,
                Math.max(CombatTerrainTuning.THREAT_ESTIMATOR_MAX_FIRST_FACTOR, velocity.length()));
            velocity = toward.scale(speed);
        } else if (hypothesis == CombatTerrainTuning.COMBAT_CONDITIONAL_FROM_PARAM)
            velocity = new Vec(-velocity.z, velocity.y, velocity.x)
                .scale(CombatTerrainTuning.COMBAT_CONDITIONAL_ABS_FIRST_FACTOR);
        return target.position.add(velocity.scale(prediction));
    }

    private static Vec projectilePosition(Projectile projectile, int elapsed) {
        double time = Math.max(CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_ZERO_BASE, elapsed), drag = projectile.drag;
        if (Math.abs(CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_UNIT_STEP - drag)
            < CombatTerrainTuning.COMBAT_TERRAIN_DOMAIN_FACTOR_7)
            return projectile.position.add(projectile.velocity.scale(time))
                .add(
                    new Vec(
                        CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_ZERO_BASE,
                        -projectile.gravity * time
                            * Math.max(CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_ZERO_BASE, time - 1)
                            / CombatTerrainTuning.COMBAT_CONDITIONAL_FROM_PARAM,
                        CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_ZERO_BASE));
        double series = (CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_UNIT_STEP - Math.pow(drag, time))
            / (CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_UNIT_STEP - drag);
        return projectile.position.add(projectile.velocity.scale(series))
            .add(
                new Vec(
                    CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_ZERO_BASE,
                    -projectile.gravity * (time - series)
                        / (CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_UNIT_STEP - drag),
                    CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_ZERO_BASE));
    }

    /** 按整个区间的身体扫掠盒检测碰撞。方块以保守体素表示，不会跳过一格墙 */
    public static boolean safeMove(CombatSnapshot snapshot, Vec from, Vec to, boolean requireFloor,
        CombatWorkQuota quota) {
        Geometry geometry = snapshot.geometry;
        if (geometry == null || !geometry.fresh(snapshot.tick)) return false;
        double width = snapshot.self.halfWidth;
        int minX = floor(Math.min(from.x, to.x) - width), maxX = floor(Math.max(from.x, to.x) + width);
        int minY = floor(Math.min(from.y, to.y) + CombatTerrainTuning.COMBAT_SPACE_DOMAIN_FACTOR) - 1, maxY = floor(
            Math.max(from.y, to.y) + snapshot.self.height - CombatTerrainTuning.COMBAT_SPACE_DOMAIN_FACTOR);
        int minZ = floor(Math.min(from.z, to.z) - width), maxZ = floor(Math.max(from.z, to.z) + width);
        for (int y = minY; y <= maxY; y++) for (int z = minZ; z <= maxZ; z++) for (int x = minX; x <= maxX; x++) {
            if (!quota.spend(Work.COLLISION)) return false;
            int flags = geometry.at(x, y, z);
            if ((flags & (CombatTerrainTuning.HAZARD | CombatTerrainTuning.UNKNOWN)) != 0) {
                if (segmentBox(
                    from,
                    to,
                    x - width,
                    y - snapshot.self.height + CombatTerrainTuning.COMBAT_SPACE_DOMAIN_FACTOR,
                    z - width,
                    x + 1 + width,
                    y + CombatTerrainTuning.THREAT_ESTIMATOR_DOMAIN_FACTOR_4,
                    z + 1 + width)) return false;
            } else if ((flags & CombatTerrainTuning.SOLID) != 0 && segmentBox(
                from,
                to,
                geometry.bound(x, y, z, CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_ZERO_BASE) - width,
                geometry.bound(x, y, z, CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_UNIT_STEP) - snapshot.self.height
                    + CombatTerrainTuning.COMBAT_SPACE_DOMAIN_FACTOR,
                geometry.bound(x, y, z, CombatTerrainTuning.COMBAT_CONDITIONAL_FROM_PARAM) - width,
                geometry.bound(x, y, z, CombatTerrainTuning.COMBAT_MOVEMENT_DOMAIN_PARAM) + width,
                geometry.bound(x, y, z, CombatTerrainTuning.COMBAT_SPACE_DOMAIN_PARAM_2)
                    - CombatTerrainTuning.COMBAT_SPACE_DOMAIN_FACTOR,
                geometry.bound(x, y, z, CombatTerrainTuning.COMBAT_SPACE_DOMAIN_PARAM) + width)) return false;
        }
        if (requireFloor) {
            int floorY = floor(to.y - CombatTerrainTuning.THREAT_ESTIMATOR_MAX_FIRST_FACTOR);
            for (int dx = CombatTerrainTuning.FOOTPRINT_CORNER_MIN_DIRECTION; dx
                <= CombatTerrainTuning.FOOTPRINT_CORNER_MAX_DIRECTION; dx += CombatTerrainTuning.FOOTPRINT_CORNER_DIRECTION_STRIDE)
                for (int dz = CombatTerrainTuning.FOOTPRINT_CORNER_MIN_DIRECTION; dz
                    <= CombatTerrainTuning.FOOTPRINT_CORNER_MAX_DIRECTION; dz += CombatTerrainTuning.FOOTPRINT_CORNER_DIRECTION_STRIDE) {
                        if (!quota.spend(Work.COLLISION)) return false;
                        double px = to.x + dx * width * CombatTerrainTuning.COMBAT_SPACE_LAYOUT_PX_OFFSET,
                            pz = to.z + dz * width * CombatTerrainTuning.COMBAT_SPACE_LAYOUT_PX_OFFSET;
                        int x = floor(px), z = floor(pz), flags = geometry.at(x, floorY, z);
                        if ((flags & CombatTerrainTuning.SOLID) == 0
                            || (flags & (CombatTerrainTuning.HAZARD | CombatTerrainTuning.UNKNOWN)) != 0
                            || geometry.bound(x, floorY, z, CombatTerrainTuning.COMBAT_SPACE_DOMAIN_PARAM_2)
                                < to.y - CombatTerrainTuning.COMBAT_SPACE_DOMAIN_FACTOR_2
                            || px < geometry.bound(x, floorY, z, CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_ZERO_BASE)
                            || px > geometry.bound(x, floorY, z, CombatTerrainTuning.COMBAT_MOVEMENT_DOMAIN_PARAM)
                            || pz < geometry.bound(x, floorY, z, CombatTerrainTuning.COMBAT_CONDITIONAL_FROM_PARAM)
                            || pz > geometry.bound(x, floorY, z, CombatTerrainTuning.COMBAT_SPACE_DOMAIN_PARAM))
                            return false;
                    }
        }
        return true;
    }

    public static boolean friendlyLine(CombatSnapshot snapshot, Vec origin, Vec target, CombatWorkQuota quota) {
        for (CombatSnapshot.Friend friend : snapshot.friends) {
            if (!quota.spend(Work.COLLISION)) return false;
            double width = friend.halfWidth + CombatTerrainTuning.COMBAT_SNAPSHOT_DOMAIN_FACTOR;
            if (segmentBox(
                origin,
                target,
                friend.position.x - width,
                friend.position.y - CombatTerrainTuning.COMBAT_SNAPSHOT_DOMAIN_FACTOR,
                friend.position.z - width,
                friend.position.x + width,
                friend.position.y + friend.height + CombatTerrainTuning.COMBAT_SNAPSHOT_DOMAIN_FACTOR,
                friend.position.z + width)) return false;
        }
        return true;
    }

    public static boolean localCover(CombatSnapshot snapshot, Vec from, Vec threat, CombatWorkQuota quota) {
        if (snapshot.geometry == null || !snapshot.geometry.fresh(snapshot.tick)) return false;
        Vec step = threat.subtract(from)
            .horizontalUnit()
            .scale(CombatTerrainTuning.COMBAT_SPACE_EXPAND_FIRST_FACTOR),
            p = from.add(
                new Vec(
                    CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_ZERO_BASE,
                    CombatTerrainTuning.THREAT_ESTIMATOR_VEC_SECOND_FACTOR,
                    CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_ZERO_BASE));
        int steps = Math.min(
            CombatTerrainTuning.COMBAT_SPACE_QUERY_ALLOWANCE_PARAM,
            Math.max(
                CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_ZERO_BASE,
                (int) Math.ceil(
                    threat.subtract(from)
                        .length() / CombatTerrainTuning.COMBAT_SPACE_EXPAND_FIRST_FACTOR)
                    - CombatTerrainTuning.OCCLUSION_ENDPOINT_EXCLUSION_STEPS));
        for (int i = 0; i < steps; i++) {
            if (!quota.spend(Work.COLLISION)) return false;
            p = p.add(step);
            int flags = snapshot.geometry.at(floor(p.x), floor(p.y), floor(p.z));
            if ((flags & CombatTerrainTuning.UNKNOWN) != 0) return false;
            if ((flags & CombatTerrainTuning.SOLID) != 0) return true;
        }
        return false;
    }

    public static boolean segmentBox(Vec from, Vec to, double minX, double minY, double minZ, double maxX, double maxY,
        double maxZ) {
        double start = 0, end = CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_UNIT_STEP;
        for (int axis = 0; axis < CombatTerrainTuning.COMBAT_MOVEMENT_DOMAIN_PARAM; axis++) {
            double a = axis == 0 ? from.x
                : axis == CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_UNIT_STEP ? from.y : from.z;
            double delta = (axis == 0 ? to.x
                : axis == CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_UNIT_STEP ? to.y : to.z) - a;
            double min = axis == 0 ? minX : axis == CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_UNIT_STEP ? minY : minZ;
            double max = axis == 0 ? maxX : axis == CombatTerrainTuning.COMBAT_MOVEMENT_DEFAULT_UNIT_STEP ? maxY : maxZ;
            if (Math.abs(delta) < CombatTerrainTuning.COMBAT_TERRAIN_DOMAIN_FACTOR_7) {
                if (a < min || a > max) return false;
            } else {
                double first = (min - a) / delta, last = (max - a) / delta;
                if (first > last) {
                    double swap = first;
                    first = last;
                    last = swap;
                }
                start = Math.max(start, first);
                end = Math.min(end, last);
                if (start > end) return false;
            }
        }
        return true;
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }
}
