package com.tianluoqaq.tlmnh.common.ai.enhanced.combat;

import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatSnapshot.Geometry;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatSnapshot.Projectile;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatSnapshot.Target;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatSnapshot.Vec;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatWorkQuota.Work;

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
            hiddenSingle = Math
                .max(hiddenSingle, target.estimatedDamage * (target.explosive ? 3 : 1) * (target.swinging ? 1.8 : 1));
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
            java.util.Arrays.fill(blockedAt, Double.POSITIVE_INFINITY);
        }
    }

    /** 每轮只提取一次纯轨迹截断，所有候选共享，不在每个分支反复扫描前缀 */
    public static ProjectileOcclusion occlusion(CombatSnapshot snapshot, CombatWorkQuota quota) {
        ProjectileOcclusion result = new ProjectileOcclusion(snapshot.projectiles.size());
        for (int i = 0; i < snapshot.projectiles.size(); i++) {
            Projectile projectile = snapshot.projectiles.get(i);
            for (int tick = 0; tick < CombatWorkQuota.HORIZON + 2; tick++) {
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
    static double damage(CombatSnapshot snapshot, Vec from, Vec to, int elapsed, int hypothesis, boolean shield,
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
                    .distance(snapshot.self.ownerPosition) < 2.7) risk.ownerReceived += target.estimatedDamage * .1;
            }
            Vec predicted = enemyPosition(target, snapshot.self.position, elapsed, hypothesis);
            double distance = predicted.distance(to), uncertainty = .25 + elapsed * .025;
            double contact = distance <= 1.8 + target.halfWidth + uncertainty ? 1 : 0;
            if (target.ranged && distance < 18 && target.visible) {
                double exposure = 1 - CombatTerrain.protection(snapshot, to, target, predicted, quota);
                contact = Math.max(contact, .18 * exposure);
            }
            if (target.explosive && distance < 3.5 + uncertainty) contact = Math.max(contact, 3);
            double swing = target.swinging && elapsed <= 6 ? 1.8 : 1;
            // 此处为不确定接触伤害率，不生成敌方精确出手时刻
            damage += contact * target.estimatedDamage * swing * CombatWorkQuota.STEP / 20;
            if (risk != null && contact > 0) {
                risk.self(target.damageSource, target.estimatedDamage * swing * (target.explosive ? 3 : 1));
                if (elapsed <= 2 && target.explosive) risk.acute = true;
            }
        }
        for (int index = 0; index < snapshot.projectiles.size(); index++) {
            boolean selfCounted = projectileHits != null && projectileHits[index]
                || risk != null && risk.selfProjectileHits[index];
            if (selfCounted && (risk == null || snapshot.self.ownerPosition == null || risk.ownerProjectileHits[index]))
                continue;
            Projectile projectile = snapshot.projectiles.get(index);
            if (snapshot.tick < projectile.observedAt || snapshot.tick - projectile.observedAt > 2) continue;
            if (risk != null) risk.source(projectile.damageSource, projectile.estimatedDamage);
            int age = (int) (snapshot.tick - projectile.observedAt);
            // 在两 tick 步进内逐段扫掠检测，避免箭矢在稀疏采样端点之间穿透身体
            for (int part = 0; part < CombatWorkQuota.STEP; part++) {
                if (!quota.spend(Work.COLLISION)) return Double.POSITIVE_INFINITY;
                Vec a = projectilePosition(projectile, age + elapsed - CombatWorkQuota.STEP + part);
                Vec b = projectilePosition(projectile, age + elapsed - CombatWorkQuota.STEP + part + 1);
                Vec selfA = from.add(
                    to.subtract(from)
                        .scale(part / (double) CombatWorkQuota.STEP));
                Vec selfB = from.add(
                    to.subtract(from)
                        .scale((part + 1) / (double) CombatWorkQuota.STEP));
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
                double startTick = age + elapsed - CombatWorkQuota.STEP + part;
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
                        if (elapsed <= 2) risk.acute = true;
                    }
                }
                if (Double.isFinite(wall) && wall < body) break;
                if (Double.isFinite(body)) {
                    if (projectileHits != null) projectileHits[index] = true;
                    damage += projectile.estimatedDamage;
                    if (risk != null) {
                        risk.selfProjectileHits[index] = true;
                        risk.self(projectile.damageSource, projectile.estimatedDamage);
                        if (elapsed <= 2) risk.acute = true;
                    }
                    selfCounted = true;
                    // 女仆命中后仍继续检查本区间余下的主人风险
                    if (risk == null || snapshot.self.ownerPosition == null || risk.ownerProjectileHits[index]) break;
                }
            }
        }
        // 仅用于策略收益估计，实际格挡角、破盾与消耗始终由现有盾牌规则处理
        return shield ? damage * .45 : damage;
    }

    public static Vec enemyPosition(Target target, Vec self, int elapsed, int hypothesis) {
        int prediction = Math.min(12, Math.max(0, elapsed));
        Vec velocity = target.velocity;
        if (hypothesis == 1) {
            Vec toward = self.subtract(target.position)
                .horizontalUnit();
            double speed = Math.min(.28, Math.max(.08, velocity.length()));
            velocity = toward.scale(speed);
        } else if (hypothesis == 2) velocity = new Vec(-velocity.z, velocity.y, velocity.x).scale(.5);
        return target.position.add(velocity.scale(prediction));
    }

    private static Vec projectilePosition(Projectile projectile, int elapsed) {
        double time = Math.max(0, elapsed), drag = projectile.drag;
        if (Math.abs(1 - drag) < 1.0E-9) return projectile.position.add(projectile.velocity.scale(time))
            .add(new Vec(0, -projectile.gravity * time * Math.max(0, time - 1) / 2, 0));
        double series = (1 - Math.pow(drag, time)) / (1 - drag);
        return projectile.position.add(projectile.velocity.scale(series))
            .add(new Vec(0, -projectile.gravity * (time - series) / (1 - drag), 0));
    }

    /** 按整个区间的身体扫掠盒检测碰撞。方块以保守体素表示，不会跳过一格墙 */
    public static boolean safeMove(CombatSnapshot snapshot, Vec from, Vec to, boolean requireFloor,
        CombatWorkQuota quota) {
        Geometry geometry = snapshot.geometry;
        if (geometry == null || !geometry.fresh(snapshot.tick)) return false;
        double width = snapshot.self.halfWidth;
        int minX = floor(Math.min(from.x, to.x) - width), maxX = floor(Math.max(from.x, to.x) + width);
        int minY = floor(Math.min(from.y, to.y) + .02) - 1,
            maxY = floor(Math.max(from.y, to.y) + snapshot.self.height - .02);
        int minZ = floor(Math.min(from.z, to.z) - width), maxZ = floor(Math.max(from.z, to.z) + width);
        for (int y = minY; y <= maxY; y++) for (int z = minZ; z <= maxZ; z++) for (int x = minX; x <= maxX; x++) {
            if (!quota.spend(Work.COLLISION)) return false;
            int flags = geometry.at(x, y, z);
            if ((flags & (Geometry.HAZARD | Geometry.UNKNOWN)) != 0) {
                if (segmentBox(
                    from,
                    to,
                    x - width,
                    y - snapshot.self.height + .02,
                    z - width,
                    x + 1 + width,
                    y + .98,
                    z + 1 + width)) return false;
            } else if ((flags & Geometry.SOLID) != 0 && segmentBox(
                from,
                to,
                geometry.bound(x, y, z, 0) - width,
                geometry.bound(x, y, z, 1) - snapshot.self.height + .02,
                geometry.bound(x, y, z, 2) - width,
                geometry.bound(x, y, z, 3) + width,
                geometry.bound(x, y, z, 4) - .02,
                geometry.bound(x, y, z, 5) + width)) return false;
        }
        if (requireFloor) {
            int floorY = floor(to.y - .08);
            for (int dx = -1; dx <= 1; dx += 2) for (int dz = -1; dz <= 1; dz += 2) {
                if (!quota.spend(Work.COLLISION)) return false;
                double px = to.x + dx * width * .8, pz = to.z + dz * width * .8;
                int x = floor(px), z = floor(pz), flags = geometry.at(x, floorY, z);
                if ((flags & Geometry.SOLID) == 0 || (flags & (Geometry.HAZARD | Geometry.UNKNOWN)) != 0
                    || geometry.bound(x, floorY, z, 4) < to.y - .12
                    || px < geometry.bound(x, floorY, z, 0)
                    || px > geometry.bound(x, floorY, z, 3)
                    || pz < geometry.bound(x, floorY, z, 2)
                    || pz > geometry.bound(x, floorY, z, 5)) return false;
            }
        }
        return true;
    }

    public static boolean friendlyLine(CombatSnapshot snapshot, Vec origin, Vec target, CombatWorkQuota quota) {
        for (CombatSnapshot.Friend friend : snapshot.friends) {
            if (!quota.spend(Work.COLLISION)) return false;
            double width = friend.halfWidth + .3;
            if (segmentBox(
                origin,
                target,
                friend.position.x - width,
                friend.position.y - .3,
                friend.position.z - width,
                friend.position.x + width,
                friend.position.y + friend.height + .3,
                friend.position.z + width)) return false;
        }
        return true;
    }

    public static boolean localCover(CombatSnapshot snapshot, Vec from, Vec threat, CombatWorkQuota quota) {
        if (snapshot.geometry == null || !snapshot.geometry.fresh(snapshot.tick)) return false;
        Vec step = threat.subtract(from)
            .horizontalUnit()
            .scale(.25), p = from.add(new Vec(0, 1.25, 0));
        int steps = Math.min(
            12,
            Math.max(
                0,
                (int) Math.ceil(
                    threat.subtract(from)
                        .length() / .25)
                    - 1));
        for (int i = 0; i < steps; i++) {
            if (!quota.spend(Work.COLLISION)) return false;
            p = p.add(step);
            int flags = snapshot.geometry.at(floor(p.x), floor(p.y), floor(p.z));
            if ((flags & Geometry.UNKNOWN) != 0) return false;
            if ((flags & Geometry.SOLID) != 0) return true;
        }
        return false;
    }

    public static boolean segmentBox(Vec from, Vec to, double minX, double minY, double minZ, double maxX, double maxY,
        double maxZ) {
        double start = 0, end = 1;
        for (int axis = 0; axis < 3; axis++) {
            double a = axis == 0 ? from.x : axis == 1 ? from.y : from.z;
            double delta = (axis == 0 ? to.x : axis == 1 ? to.y : to.z) - a;
            double min = axis == 0 ? minX : axis == 1 ? minY : minZ;
            double max = axis == 0 ? maxX : axis == 1 ? maxY : maxZ;
            if (Math.abs(delta) < 1.0E-9) {
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
