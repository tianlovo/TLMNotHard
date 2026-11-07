package com.tianluoqaq.tlmnh.common.ai.enhanced.combat;

import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatSnapshot.Geometry;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatSnapshot.Projectile;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatSnapshot.Target;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatSnapshot.Vec;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatWorkQuota.Work;

/** 只使用可见运动和已发生命中，不读取敌方冷却或未观测的真实位置 */
public final class ThreatEstimator {

    private ThreatEstimator() {}

    public static double damage(CombatSnapshot snapshot, Vec from, Vec to, int elapsed, int hypothesis, boolean shield,
        CombatWorkQuota quota) {
        double damage = 0;
        for (Target target : snapshot.targets) {
            if (!quota.spend(Work.COLLISION)) return Double.POSITIVE_INFINITY;
            if (!target.visible || !target.fresh(snapshot.tick)) continue;
            Vec predicted = enemyPosition(target, snapshot.self.position, elapsed, hypothesis);
            double distance = predicted.distance(to), uncertainty = .25 + elapsed * .025;
            double contact = distance <= 1.8 + target.halfWidth + uncertainty ? 1 : 0;
            if (target.ranged && distance < 18 && target.visible) contact = Math.max(contact, .18);
            if (target.explosive && distance < 3.5 + uncertainty) contact = Math.max(contact, 3);
            double swing = target.swinging && elapsed <= 6 ? 1.8 : 1;
            // 这是不确定接触伤害率，未生成敌方精确出手时刻
            damage += contact * target.estimatedDamage * swing * CombatWorkQuota.STEP / 20;
        }
        for (Projectile projectile : snapshot.projectiles) {
            if (snapshot.tick < projectile.observedAt || snapshot.tick - projectile.observedAt > 2) continue;
            int age = (int) (snapshot.tick - projectile.observedAt);
            // 两tick推进内各检查一段扫掠，避免箭在两个采样端点之间穿过身体
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
                if (segmentBox(
                    a.subtract(selfA),
                    b.subtract(selfB),
                    -snapshot.self.halfWidth - projectile.radius,
                    -projectile.radius,
                    -snapshot.self.halfWidth - projectile.radius,
                    snapshot.self.halfWidth + projectile.radius,
                    snapshot.self.height + projectile.radius,
                    snapshot.self.halfWidth + projectile.radius)) {
                    damage += projectile.estimatedDamage;
                    break;
                }
            }
        }
        // 仅策略收益估计，实际格挡角、破盾与消耗始终由现有盾牌规则处理
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

    /** 身体在整个区间的扫掠盒；方块以保守体素表示，不会跳过一格墙 */
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
