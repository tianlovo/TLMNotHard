package com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatWorkQuota;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatWorkQuota.Work;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot.DoorCell;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot.Geometry;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot.Target;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot.Vec;

/** 局部已知站立图；仅基于本地缓存分析，未知格标记为未探索区域 */
public final class CombatTerrain {

    private static final int RADIUS = 3, MAX_NODES = 49, MAX_WORK = 6000, MAX_RAY_CELLS = 32, CONTINUATION_WORK = 256;
    private static final double STEP_HEIGHT = .5, EPSILON = .001;
    private static final double RETREAT_DISTANCE = 2.5, CONTINUATION_DISTANCE = 2.5;
    private static final int[][] DIRECTIONS = { { 1, 0 }, { 0, 1 }, { -1, 0 }, { 0, -1 } };

    public enum Sight {
        CLEAR,
        BLOCKED,
        UNKNOWN,
        BUDGET
    }

    private CombatTerrain() {}

    public static final class Route {

        public final List<Vec> points, nodes;
        public final Vec destination, entrance, escape, peek;
        public final double length, stopBeforeDoor, protection;
        public final long provedAt;
        public final int defenseNode;
        public final double defenseDistance;
        public final boolean firingLine, conditionalDoor;
        public final CombatPassiveDoorExitProof.Source passiveExit;

        private Route(List<Vec> points, List<Vec> nodes, Vec entrance, Vec escape, Vec peek, double stopBeforeDoor,
            double protection, boolean firingLine, long provedAt) {
            this(points, nodes, entrance, escape, peek, stopBeforeDoor, protection, firingLine, provedAt, 0);
        }

        private Route(List<Vec> points, List<Vec> nodes, Vec entrance, Vec escape, Vec peek, double stopBeforeDoor,
            double protection, boolean firingLine, long provedAt, int defenseNode) {
            this(
                points,
                nodes,
                entrance,
                escape,
                peek,
                stopBeforeDoor,
                protection,
                firingLine,
                provedAt,
                defenseNode,
                null);
        }

        private Route(List<Vec> points, List<Vec> nodes, Vec entrance, Vec escape, Vec peek, double stopBeforeDoor,
            double protection, boolean firingLine, long provedAt, int defenseNode,
            CombatPassiveDoorExitProof.Source passiveExit) {
            this.passiveExit = passiveExit;
            this.defenseNode = defenseNode;
            this.points = Collections.unmodifiableList(new ArrayList<>(points));
            this.nodes = Collections.unmodifiableList(new ArrayList<>(nodes));
            destination = points.get(points.size() - 1);
            this.provedAt = provedAt;
            this.entrance = entrance;
            this.escape = escape;
            this.peek = peek;
            this.stopBeforeDoor = stopBeforeDoor;
            conditionalDoor = Double.isFinite(stopBeforeDoor);
            this.protection = protection;
            this.firingLine = firingLine;
            double total = 0;
            for (int i = 1; i < points.size(); i++) total += points.get(i - 1)
                .distance(points.get(i));
            length = total;
            double prefix = 0;
            if (defenseNode > 0) for (int i = 1; i <= defenseNode && i < nodes.size(); i++)
                prefix += nodeDistance(nodes.get(i - 1), nodes.get(i));
            defenseDistance = prefix;
        }

        public double currentLength() {
            return Math.min(length, stopBeforeDoor);
        }

        /** 关闭门只描述可申请的路径，不在预演中假装其已打开 */
        public Vec at(double travelled) {
            double left = Math.max(0, Math.min(travelled, stopBeforeDoor));
            for (int i = 1; i < points.size(); i++) {
                Vec a = points.get(i - 1), b = points.get(i);
                double distance = a.distance(b);
                if (left <= distance) return distance < EPSILON ? b
                    : a.add(
                        b.subtract(a)
                            .scale(left / distance));
                left -= distance;
            }
            return destination;
        }

        public boolean fresh(long now) {
            return provedAt != Long.MIN_VALUE && provedAt <= now
                && now - provedAt >= 0
                && now - provedAt <= CombatSnapshot.GEOMETRY_TTL;
        }

        /** 原生导航采用有支撑的图节点，不采用模拟中间的抬脚点 */
        public Vec next(int index) {
            return nodes.get(Math.max(0, Math.min(nodes.size() - 1, index)));
        }
    }

    public static final class Choices {

        public Route approach, retreat, left, right, cover, firingCover;
        public int visited, work;
        private List<Node> graph = Collections.emptyList();
    }

    public static Choices find(CombatSnapshot snapshot, Target target, CombatWorkQuota quota) {
        return find(snapshot, target, quota, false);
    }

    public static Choices findBody(CombatSnapshot snapshot, Target target, CombatWorkQuota quota) {
        return find(snapshot, target, quota, true);
    }

    private static Choices find(CombatSnapshot snapshot, Target target, CombatWorkQuota quota, boolean body) {
        Choices result = new Choices();
        if (!quota.full || !(body ? snapshot.bodyComplete : snapshot.complete)
            || !snapshot.self.grounded
            || snapshot.self.stationary
            || snapshot.geometry == null
            || !snapshot.geometry.complete
            || !snapshot.geometry.fresh(snapshot.tick)
            || target == null) return result;
        int allowance = Math.min(MAX_WORK, quota.remaining(Work.COLLISION) / 3);
        Meter meter = new Meter(quota, allowance / 2);
        List<Node> nodes = new ArrayList<>();
        Node start = new Node(snapshot.self.position, null, false);
        if (!support(snapshot, start.point, start.point.y, start.point.y, meter)
            || !clear(snapshot, start.point, start.point, false, meter)) return result;
        nodes.add(start);
        DoorCell occupied = body
            ? occupiedOpenDoor(snapshot.geometry, snapshot.self.halfWidth, snapshot.self.height, start.point, meter)
            : null;
        int cx = floor(start.point.x), cz = floor(start.point.z);
        Node[][] visited = new Node[7][7];
        visited[3][3] = start;
        for (int cursor = 0; cursor < nodes.size() && nodes.size() < MAX_NODES && !meter.exhausted; cursor++) {
            Node from = nodes.get(cursor);
            for (int[] direction : DIRECTIONS) {
                if (!meter.pay()) break;
                int x = floor(from.point.x) + direction[0], z = floor(from.point.z) + direction[1];
                if (Math.abs(x - cx) > RADIUS || Math.abs(z - cz) > RADIUS) continue;
                Node known = visited[x - cx + RADIUS][z - cz + RADIUS];
                if (known != null) {
                    // 已证边反向仍是同一半格/支撑证明，不重复采样；交叉边须另收费证明
                    if (known != from.parent && known.parent != from
                        && connect(snapshot, from.point, known.point, false, meter)) {
                        from.exit = known.point;
                        known.exit = from.point;
                    }
                    continue;
                }
                Vec point = standing(snapshot, x + .5, z + .5, from.point.y, meter);
                boolean door = false;
                boolean connected = point != null && connect(snapshot, from.point, point, false, meter);
                if (!body && !connected && openable(snapshot.geometry, x, floor(from.point.y), z, meter)) {
                    x += direction[0];
                    z += direction[1];
                    if (Math.abs(x - cx) > RADIUS || Math.abs(z - cz) > RADIUS) continue;
                    point = standing(snapshot, x + .5, z + .5, from.point.y, meter);
                    door = true;
                    connected = point != null && connect(snapshot, from.point, point, true, meter);
                }
                if (!connected) continue;
                CombatPassiveDoorExitProof.Source exit = null;
                if (body && from == start && occupied != null) {
                    exit = new CombatPassiveDoorExitProof.Source(occupied, start.point, point);
                    if (!exit.outside(point, snapshot.self.halfWidth, snapshot.self.height)
                        || Math.abs(floor(point.x) - floor(start.point.x))
                            + Math.abs(floor(point.z) - floor(start.point.z)) != 1
                        || !exitDoorVolume(
                            snapshot.geometry,
                            snapshot.self.halfWidth,
                            snapshot.self.height,
                            from.point,
                            point,
                            occupied,
                            meter)
                        || !connect(snapshot, point, from.point, false, meter)
                        || !exitDoorVolume(
                            snapshot.geometry,
                            snapshot.self.halfWidth,
                            snapshot.self.height,
                            point,
                            from.point,
                            occupied,
                            meter))
                        continue;
                } else if (body && !noDoorVolume(snapshot, from.point, point, meter)) continue;
                known = visited[x - cx + RADIUS][z - cz + RADIUS];
                if (known != null) {
                    if (!door && known != from.parent
                        && known.parent != from
                        && Math.abs(known.point.y - point.y) < EPSILON) {
                        from.exit = known.point;
                        known.exit = from.point;
                    }
                    continue;
                }
                Node next = new Node(point, from, door);
                next.passiveExit = exit != null ? exit : from.passiveExit;
                visited[x - cx + RADIUS][z - cz + RADIUS] = next;
                nodes.add(next);
                if (!door && from.parent != null) from.exit = next.point;
                if (nodes.size() == MAX_NODES) break;
            }
        }
        result.graph = Collections.unmodifiableList(new ArrayList<>(nodes));
        int graphWork = meter.used;
        meter = new Meter(quota, allowance - graphWork);
        double[] scores = { Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
            Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY };
        Vec toward = target.position.subtract(start.point)
            .horizontalUnit();
        double initialDistance = start.point.distance(target.position);
        if (body) {
            double best = Double.NEGATIVE_INFINITY, retreat = Double.NEGATIVE_INFINITY;
            for (int i = 1; i < nodes.size() && !meter.exhausted; i++) {
                Node node = nodes.get(i);
                if (!meter.pay()) break;
                Route route = route(node, 0, false, snapshot.geometry.observedAt, meter);
                if (route == null || route.conditionalDoor) continue;
                double distance = node.point.distance(target.position), progress = initialDistance - distance;
                if ((occupied == null || node.parent == start) && progress > .25
                    && distance > Math.max(.8, snapshot.self.reach * .65)
                    && progress - .10 * route.length > best) {
                    best = progress - .10 * route.length;
                    result.approach = route;
                }
                if (node.point.distance(start.point) <= RETREAT_DISTANCE && distance > initialDistance + .5
                    && distance - initialDistance - .10 * route.length > retreat) {
                    retreat = distance - initialDistance - .10 * route.length;
                    result.retreat = route;
                }
            }
            result.visited = nodes.size();
            result.work = graphWork + meter.used;
            return result;
        }
        double initialProtection = protection(snapshot, start.point, target, meter);
        for (int i = 1; i < nodes.size() && !meter.exhausted; i++) {
            Node node = nodes.get(i);
            if (!meter.pay()) break;
            double distance = node.point.distance(target.position), progress = initialDistance - distance;
            double protection = protection(snapshot, node.point, target, meter);
            boolean line = aimClear(snapshot, node.point, target, meter)
                && friendly(snapshot, node.point, target, meter);
            node.peek = line ? node.point : null;
            if (protection > initialProtection + .2 && node.exit != null && node.parent != null) {
                if (node.peek == null && aimClear(snapshot, node.exit, target, meter)
                    && friendly(snapshot, node.exit, target, meter)) node.peek = node.exit;
                if (node.peek == null && aimClear(snapshot, node.parent.point, target, meter)
                    && friendly(snapshot, node.parent.point, target, meter)) node.peek = node.parent.point;
            }
            double side = (node.point.x - start.point.x) * -toward.z + (node.point.z - start.point.z) * toward.x;
            double narrow = corridor(snapshot, node.point, meter) ? .15 : 0;
            Route route = route(node, protection, line, snapshot.geometry.observedAt, meter);
            if (route == null) break;
            double cost = route.length * .10;
            if (distance > Math.max(.8, snapshot.self.reach * .65) && progress > .25 && progress - cost > scores[0]) {
                scores[0] = progress - cost;
                result.approach = route;
            }
            if (!route.conditionalDoor && node.point.distance(start.point) <= RETREAT_DISTANCE
                && distance > initialDistance + .5
                && distance - initialDistance + protection + narrow - cost > scores[1]) {
                scores[1] = distance - initialDistance + protection + narrow - cost;
                result.retreat = route;
            }
            if (line && !route.conditionalDoor && Math.abs(side) > .5) {
                int index = side > 0 ? 2 : 3;
                double score = protection + narrow - Math.abs(Math.abs(side) - 1.5) * .3 - cost;
                if (score > scores[index]) {
                    scores[index] = score;
                    if (side > 0) result.left = route;
                    else result.right = route;
                }
            }
            // 两条已验证连接：返回入口以及另一个可走出口，不把墙角封闭处算成掩体
            if (!route.conditionalDoor && node.exit != null
                && node.parent != null
                && protection > initialProtection + .2
                && node.peek != null) {
                if (!meter.pay()) break;
                Vec predicted = ThreatEstimator.enemyPosition(target, start.point, CombatWorkQuota.HORIZON, 1);
                double contact = predicted.distance(node.point)
                    <= 1.8 + target.halfWidth + .25 + CombatWorkQuota.HORIZON * .025 ? 1 : .18 * (1 - protection);
                double contactCost = contact * target.estimatedDamage
                    * CombatWorkQuota.HORIZON
                    / 20
                    / snapshot.self.health;
                double score = protection * 2 + (line ? .2 : 0) + narrow - cost - contactCost;
                if (score > scores[4]) {
                    scores[4] = score;
                    result.cover = route;
                }
                if (line && score > scores[5]) {
                    scores[5] = score;
                    result.firingCover = route;
                }
            }
        }
        result.visited = nodes.size();
        result.work = graphWork + meter.used;
        return result;
    }

    /** 只提取本轮已构建图到精确旧next的普通父链，不扩图、不越过该节点 */
    public static Route connector(CombatSnapshot snapshot, Choices choices, Vec next, CombatWorkQuota quota) {
        if (!quota.full || !snapshot.complete || choices == null || next == null) return null;
        Meter meter = new Meter(quota, MAX_WORK);
        for (Node node : choices.graph) {
            if (!meter.pay()) return null;
            if (node.point.distance(next) > EPSILON) continue;
            Route route = route(node, 0, false, snapshot.geometry.observedAt, meter);
            return route == null || route.conditionalDoor ? null : reproveBody(snapshot, route, 1, quota);
        }
        return null;
    }

    /** 与原当前段上限同源，纯值访问不授予任何证明 */
    public static double continuationDistance() {
        return CONTINUATION_DISTANCE;
    }

    public static final class EntrySource {

        public final Vec sourceA, entryK, exactB;
        public final CombatConditionalEntryProof.DoorEdge edge;
        public final Route route;

        private EntrySource(Vec a, Vec k, Vec b, CombatConditionalEntryProof.DoorEdge edge, Route route) {
            sourceA = a;
            entryK = k;
            exactB = b;
            this.edge = edge;
            this.route = route;
        }
    }

    /** 定位父原route第一条条件边，逐边实付；不以conditionalDoor泛化任意边 */
    public static CombatConditionalEntryProof.DoorEdge entryEdge(Route parent, int cursor, CombatWorkQuota quota) {
        if (parent == null || !parent.conditionalDoor
            || cursor < 1
            || cursor >= parent.nodes.size()
            || cursor <= parent.defenseNode) return null;
        double distance = 0;
        for (int i = 1; i < parent.nodes.size(); i++) {
            if (!quota.spend(Work.COLLISION)) return null;
            Vec a = parent.nodes.get(i - 1), b = parent.nodes.get(i);
            double length = nodeDistance(a, b);
            if (distance + length > parent.stopBeforeDoor + EPSILON)
                return i == cursor ? CombatConditionalEntryProof.DoorEdge.from(a, b) : null;
            distance += length;
        }
        return null;
    }

    /** 只取本轮已有图中的P-K-B，K恰为start的普通直接子节点 */
    public static EntrySource conditionalEntry(CombatSnapshot s, Choices choices, Route parent, int cursor,
        CombatWorkQuota quota) {
        if (!quota.full || !s.complete
            || !s.bodyComplete
            || !s.self.grounded
            || s.self.stationary
            || choices == null
            || choices.graph.isEmpty()) return null;
        var edge = entryEdge(parent, cursor, quota);
        if (edge == null) return null;
        Vec b = parent.next(cursor), a = parent.next(cursor - 1);
        Node start = choices.graph.get(0);
        for (Node node : choices.graph) {
            if (!quota.spend(Work.COLLISION)) return null;
            if (node.point.distance(b) > EPSILON) continue;
            Node k = node.parent;
            if (!node.door || k == null
                || k.door
                || k.parent != start
                || start.parent != null
                || start.point.distance(s.self.position) > EPSILON
                || !edge.entry(k.point, b)
                || Math.abs(k.point.y - a.y) > .001) return null;
            Route route = conditionalEntryRoute(s, parent, cursor, k.point, edge, quota);
            return route == null ? null : new EntrySource(a, k.point, b, edge, route);
        }
        return null;
    }

    /** 原来源K的续持只重证一个普通段与未完成父tail，不重新搜K或改变B */
    public static Route conditionalEntryRoute(CombatSnapshot s, Route parent, int cursor, Vec k,
        CombatConditionalEntryProof.DoorEdge edge, CombatWorkQuota quota) {
        if (!quota.full || !s.complete
            || !s.bodyComplete
            || !s.self.grounded
            || s.self.stationary
            || s.geometry == null
            || !s.geometry.fresh(s.tick)
            || edge == null
            || !edge.same(entryEdge(parent, cursor, quota))
            || !edge.entry(k, parent.next(cursor))
            || Math.abs(k.y - parent.next(cursor - 1).y) > .001) return null;
        Vec p = s.self.position, b = parent.next(cursor);
        if (Math.abs(Math.floor(p.x) - Math.floor(k.x)) + Math.abs(Math.floor(p.z) - Math.floor(k.z)) > 1
            || !bodyStep(s, p, k, quota)
            || !bodyStep(s, k, p, quota)
            || !safeRequestStep(s, k, b, quota)) return null;
        List<Vec> nodes = new ArrayList<>();
        nodes.add(p);
        nodes.add(k);
        nodes.add(b);
        double stop = nodeDistance(p, k);
        for (int i = cursor + 1; i < parent.nodes.size(); i++) {
            Vec from = nodes.get(nodes.size() - 1), to = parent.nodes.get(i);
            if (!quota.spend(Work.COLLISION)
                || !safeStep(s, from, to, quota) && (!parent.conditionalDoor || !safeRequestStep(s, from, to, quota)))
                return null;
            nodes.add(to);
        }
        Route renewed = fromNodes(nodes, stop, parent.protection, parent.firingLine, s.geometry.observedAt, 0);
        return renewed == null ? null
            : new Route(
                renewed.points,
                renewed.nodes,
                parent.entrance,
                parent.escape,
                parent.peek,
                renewed.stopBeforeDoor,
                renewed.protection,
                renewed.firingLine,
                renewed.provedAt,
                renewed.defenseNode);
    }

    /** 身体复视仅重证普通连接，逆向另行收费，绝不调用safeRequestStep */
    public static Route reproveBody(CombatSnapshot snapshot, Route original, int cursor, CombatWorkQuota quota) {
        if (!snapshot.bodyComplete || original == null || original.conditionalDoor) return null;
        List<Vec> nodes = new ArrayList<>();
        nodes.add(snapshot.self.position);
        for (int i = cursor; i < original.nodes.size(); i++) {
            Vec a = nodes.get(nodes.size() - 1), b = original.nodes.get(i);
            if (!bodyStep(snapshot, a, b, quota) || !bodyStep(snapshot, b, a, quota)) return null;
            nodes.add(b);
        }
        return fromNodes(nodes, Double.POSITIVE_INFINITY, 0, false, snapshot.geometry.observedAt, 0);
    }

    public static boolean bodyStep(CombatSnapshot snapshot, Vec a, Vec b, CombatWorkQuota quota) {
        return snapshot.bodyComplete && safeStep(snapshot, a, b, quota)
            && noDoorVolume(snapshot, a, b, new Meter(quota, MAX_WORK));
    }

    private static boolean stablePair(Geometry g, DoorCell d, Meter meter) {
        if (g == null || d == null || !meter.pay() || !meter.pay()) return false;
        DoorCell lower = g.doorAt(d.x, d.y, d.z), upper = g.doorAt(d.x, d.y + 1, d.z);
        return lower != null && upper != null
            && lower.queryY == d.y
            && upper.queryY == d.y + 1
            && lower.x == d.x
            && lower.y == d.y
            && lower.z == d.z
            && upper.x == d.x
            && upper.y == d.y
            && upper.z == d.z
            && d.sameDoor(lower)
            && d.sameDoor(upper)
            && (g.at(d.x, d.y, d.z) & (Geometry.OPENABLE | Geometry.UNKNOWN | Geometry.HAZARD | Geometry.WATER))
                == Geometry.OPENABLE
            && (g.at(d.x, d.y + 1, d.z) & (Geometry.OPENABLE | Geometry.UNKNOWN | Geometry.HAZARD | Geometry.WATER))
                == Geometry.OPENABLE;
    }

    private static DoorCell occupiedOpenDoor(Geometry g, double w, double height, Vec p, Meter meter) {
        DoorCell door = null;
        for (int y = floor(p.y); y <= floor(p.y + height - .0001); y++)
            for (int z = floor(p.z - w); z <= floor(p.z + w - .0001); z++)
                for (int x = floor(p.x - w); x <= floor(p.x + w - .0001); x++) {
                    if (!meter.pay()) return null;
                    int flags = g.at(x, y, z);
                    if ((flags & (Geometry.UNKNOWN | Geometry.HAZARD | Geometry.WATER)) != 0) return null;
                    if ((flags & Geometry.OPENABLE) == 0) continue;
                    DoorCell d = g.doorAt(x, y, z);
                    if (d == null || d.x != x
                        || d.z != z
                        || d.queryY != y
                        || !stablePair(g, d, meter)
                        || !CombatPassiveDoorExitProof.Source.occupies(d, p, w, height)
                        || door != null && !door.sameDoor(d)) return null;
                    door = d;
                }
        return door;
    }

    private static boolean exitDoorVolume(Geometry g, double w, double height, Vec a, Vec b, DoorCell d, Meter meter) {
        if (!stablePair(g, d, meter)) return false;
        for (int y = floor(Math.min(a.y, b.y)); y <= floor(Math.max(a.y, b.y) + height); y++)
            for (int z = floor(Math.min(a.z, b.z) - w); z <= floor(Math.max(a.z, b.z) + w); z++)
                for (int x = floor(Math.min(a.x, b.x) - w); x <= floor(Math.max(a.x, b.x) + w); x++) {
                    if (!meter.pay()) return false;
                    if ((g.at(x, y, z) & Geometry.OPENABLE) == 0) continue;
                    DoorCell cell = g.doorAt(x, y, z);
                    if (x != d.x || z != d.z
                        || y != d.y && y != d.y + 1
                        || cell == null
                        || cell.queryY != y
                        || !d.sameDoor(cell)) return false;
                }
        return true;
    }

    /** 仅本次已占单门的固定相邻退出段；物理碰撞仍严格allowDoor=false */
    public static boolean passiveDoorStep(Geometry g, double w, double height, Vec a, Vec b,
        CombatPassiveDoorExitProof.Source source, long now, CombatWorkQuota quota) {
        if (g == null || !g.complete
            || !g.fresh(now)
            || source == null
            || a == null
            || b == null
            || w <= 0
            || height <= 0
            || !Double.isFinite(w + height + a.x + a.y + a.z + b.x + b.y + b.z)
            || !source.contains(a)
            || !source.contains(b)
            || a.distance(b) > CONTINUATION_DISTANCE
            || !CombatPassiveDoorExitProof.Source.occupies(source.door, source.occupiedOrigin, w, height)
            || !source.outside(source.k, w, height)
            || Math.abs(floor(source.k.x) - floor(source.occupiedOrigin.x))
                + Math.abs(floor(source.k.z) - floor(source.occupiedOrigin.z)) != 1)
            return false;
        Meter meter = new Meter(quota, Math.min(CONTINUATION_WORK, quota.remaining(Work.COLLISION)));
        return support(g, w, a, a.y, a.y, meter) && clear(g, w, height, a, a, false, meter)
            && support(g, w, b, b.y, b.y, meter)
            && connect(g, w, height, a, b, false, meter)
            && exitDoorVolume(g, w, height, a, b, source.door, new Meter(quota, MAX_WORK));
    }

    public static Route reprovePassiveExit(CombatSnapshot s, Route original, int cursor, CombatWorkQuota quota) {
        if (!s.bodyComplete || original == null
            || original.passiveExit == null
            || original.conditionalDoor
            || cursor != 1
            || original.nodes.size() < 2) return null;
        var source = original.passiveExit;
        if (original.nodes.get(1)
            .distance(source.k) > .0001
            || Math.abs(Math.floor(s.self.position.x) - Math.floor(source.k.x))
                + Math.abs(Math.floor(s.self.position.z) - Math.floor(source.k.z)) > 1
            || Math.abs(s.self.position.y - source.k.y) > .500001
            || !passiveDoorStep(
                s.geometry,
                s.self.halfWidth,
                s.self.height,
                s.self.position,
                source.k,
                source,
                s.tick,
                quota)
            || !passiveDoorStep(
                s.geometry,
                s.self.halfWidth,
                s.self.height,
                source.k,
                s.self.position,
                source,
                s.tick,
                quota))
            return null;
        List<Vec> nodes = new ArrayList<>();
        nodes.add(s.self.position);
        nodes.add(source.k);
        for (int i = 2; i < original.nodes.size(); i++) {
            Vec a = nodes.get(nodes.size() - 1), b = original.nodes.get(i);
            if (!bodyStep(s, a, b, quota) || !bodyStep(s, b, a, quota)) return null;
            nodes.add(b);
        }
        Route route = fromNodes(nodes, Double.POSITIVE_INFINITY, 0, false, s.geometry.observedAt, 0);
        return new Route(
            route.points,
            route.nodes,
            route.entrance,
            route.escape,
            route.peek,
            route.stopBeforeDoor,
            route.protection,
            route.firingLine,
            route.provedAt,
            0,
            source);
    }

    /** 原safeStep的256上限与noDoorVolume独立Meter分别真实收费 */
    public static boolean alignmentBodyStep(Geometry geometry, double halfWidth, double height, Vec a, Vec b, long now,
        CombatWorkQuota quota) {
        if (geometry == null || !geometry.complete
            || !geometry.fresh(now)
            || a == null
            || b == null
            || halfWidth <= 0
            || height <= 0
            || !Double.isFinite(halfWidth + height + a.x + a.y + a.z + b.x + b.y + b.z)
            || a.distance(b) > CONTINUATION_DISTANCE) return false;
        Meter meter = new Meter(quota, Math.min(CONTINUATION_WORK, quota.remaining(Work.COLLISION)));
        return support(geometry, halfWidth, a, a.y, a.y, meter)
            && clear(geometry, halfWidth, height, a, a, false, meter)
            && support(geometry, halfWidth, b, b.y, b.y, meter)
            && connect(geometry, halfWidth, height, a, b, false, meter)
            && noDoorVolume(geometry, halfWidth, height, a, b, new Meter(quota, MAX_WORK));
    }

    /** 只有一个固定来源，原find/49节点图保持原样 */
    public static Route alignmentPrefix(CombatSnapshot s, Vec center, Vec next, CombatWorkQuota quota) {
        if (!quota.full || !s.complete
            || !s.bodyComplete
            || !s.self.grounded
            || s.self.stationary
            || center == null
            || next == null
            || !CombatMovementFact.sameStandingCell(s.self.position, center)
            || Math.abs(center.x - (Math.floor(center.x) + .5)) > EPSILON
            || Math.abs(center.z - (Math.floor(center.z) + .5)) > EPSILON
            || Math.abs(Math.floor(center.x) - Math.floor(next.x)) + Math.abs(Math.floor(center.z) - Math.floor(next.z))
                != 1
            || Math.abs(center.y - next.y) > STEP_HEIGHT + EPSILON) return null;
        if (!quota.spend(Work.COLLISION, 3) || !bodyStep(s, s.self.position, center, quota)
            || !bodyStep(s, center, s.self.position, quota)
            || !bodyStep(s, center, next, quota)
            || !bodyStep(s, next, center, quota)) return null;
        List<Vec> points = new ArrayList<>();
        points.add(s.self.position);
        points.add(center);
        points.add(next);
        return fromNodes(points, Double.POSITIVE_INFINITY, 0, false, s.geometry.observedAt, 0);
    }

    private static boolean noDoorVolume(CombatSnapshot snapshot, Vec a, Vec b, Meter meter) {
        return noDoorVolume(snapshot.geometry, snapshot.self.halfWidth, snapshot.self.height, a, b, meter);
    }

    private static boolean noDoorVolume(Geometry geometry, double w, double height, Vec a, Vec b, Meter meter) {
        for (int y = floor(Math.min(a.y, b.y)); y <= floor(Math.max(a.y, b.y) + height); y++)
            for (int z = floor(Math.min(a.z, b.z) - w); z <= floor(Math.max(a.z, b.z) + w); z++)
                for (int x = floor(Math.min(a.x, b.x) - w); x <= floor(Math.max(a.x, b.x) + w); x++)
                    if (!meter.pay() || (geometry.at(x, y, z) & Geometry.OPENABLE) != 0) return false;
        return true;
    }

    /** 同一H40里的connector前缀与原未完成tail，条件门只保留当前首段残余 */
    public static Route prepend(CombatSnapshot snapshot, Route prefix, Route parent, int cursor,
        CombatWorkQuota quota) {
        if (prefix == null || prefix.conditionalDoor || parent == null) return null;
        List<Vec> nodes = new ArrayList<>(prefix.nodes);
        double distance = prefix.length;
        double stop = Double.POSITIVE_INFINITY;
        for (int i = cursor + 1; i < parent.nodes.size(); i++) {
            Vec a = nodes.get(nodes.size() - 1), b = parent.nodes.get(i);
            if (!quota.spend(Work.COLLISION)) return null;
            boolean ordinary = safeStep(snapshot, a, b, quota);
            if (!ordinary && (!parent.conditionalDoor || !safeRequestStep(snapshot, a, b, quota))) return null;
            if (!ordinary) stop = Math.min(stop, distance);
            distance += nodeDistance(a, b);
            nodes.add(b);
        }
        Route renewed = fromNodes(
            nodes,
            stop,
            parent.protection,
            parent.firingLine,
            snapshot.geometry.observedAt,
            Math.max(0, prefix.nodes.size() - 1 + parent.defenseNode - cursor));
        return renewed == null ? null
            : new Route(
                renewed.points,
                renewed.nodes,
                parent.entrance,
                parent.escape,
                parent.peek,
                renewed.stopBeforeDoor,
                renewed.protection,
                renewed.firingLine,
                renewed.provedAt,
                renewed.defenseNode);
    }

    /** 只重证既有剩余连接，不重新搜索；原条件门仍是待原生请求的闭门 */
    public static Route reprove(CombatSnapshot snapshot, Route original, int nextNode, CombatWorkQuota quota) {
        if (original == null || !snapshot.complete
            || snapshot.geometry == null
            || !snapshot.geometry.complete
            || !snapshot.geometry.fresh(snapshot.tick)) return null;
        List<Vec> nodes = new ArrayList<>();
        nodes.add(snapshot.self.position);
        Meter meter = new Meter(quota, MAX_WORK);
        if (!support(snapshot, snapshot.self.position, snapshot.self.position.y, snapshot.self.position.y, meter)
            || !clear(snapshot, snapshot.self.position, snapshot.self.position, false, meter)) return null;
        double stop = Double.POSITIVE_INFINITY, length = 0;
        for (int i = nextNode; i < original.nodes.size(); i++) {
            Vec a = nodes.get(nodes.size() - 1), b = original.nodes.get(i);
            boolean door = original.conditionalDoor && a.distance(b) > 1.5
                && openable(snapshot.geometry, floor((a.x + b.x) * .5), floor(a.y), floor((a.z + b.z) * .5), meter);
            if (!support(snapshot, b, b.y, b.y, meter) || !connect(snapshot, a, b, door, meter)) return null;
            if (door) stop = Math.min(stop, length);
            length += nodeDistance(a, b);
            nodes.add(b);
        }
        Route renewed = fromNodes(
            nodes,
            stop,
            original.protection,
            original.firingLine,
            snapshot.geometry.observedAt,
            Math.max(0, original.defenseNode - nextNode + 1));
        return renewed == null ? null
            : new Route(
                renewed.points,
                renewed.nodes,
                original.entrance,
                original.escape,
                original.peek,
                renewed.stopBeforeDoor,
                renewed.protection,
                renewed.firingLine,
                renewed.provedAt,
                renewed.defenseNode);
    }

    /** 返程逐连接重新证明并收费；门后的尚未发生路径不进入退路证明 */
    public static boolean reverseEscape(CombatSnapshot snapshot, Route route, CombatWorkQuota quota) {
        if (route == null || !snapshot.complete || route.nodes.size() < 2) return false;
        double distance = 0;
        for (int i = 1; i < route.nodes.size(); i++) {
            Vec a = route.nodes.get(i - 1), b = route.nodes.get(i);
            double edge = nodeDistance(a, b);
            if (distance + edge > route.stopBeforeDoor + EPSILON) break;
            if (!safeStep(snapshot, b, a, quota)) return false;
            distance += edge;
        }
        return true;
    }

    /** 同一图内的退避→已证返回→任务前沿，一次候选/一次H40，不模拟门已打开 */
    public static Route defensiveReturn(CombatSnapshot snapshot, Route defense, Route task, CombatWorkQuota quota) {
        if (defense == null || task == null
            || defense.conditionalDoor
            || defense.nodes.size() < 2
            || !reverseEscape(snapshot, defense, quota)) return null;
        List<Vec> nodes = new ArrayList<>(defense.nodes);
        for (int i = defense.nodes.size() - 2; i >= 0; i--) nodes.add(defense.nodes.get(i));
        for (int i = 1; i < task.nodes.size(); i++) nodes.add(task.nodes.get(i));
        double returned = defense.length * 2;
        Route joined = fromNodes(
            nodes,
            returned + task.stopBeforeDoor,
            task.protection,
            task.firingLine,
            snapshot.geometry.observedAt,
            defense.nodes.size() - 1);
        return new Route(
            joined.points,
            joined.nodes,
            defense.entrance,
            defense.escape,
            defense.peek,
            joined.stopBeforeDoor,
            joined.protection,
            joined.firingLine,
            joined.provedAt,
            joined.defenseNode);
    }

    private static double nodeDistance(Vec a, Vec b) {
        double dx = a.x - b.x, dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz) + Math.abs(a.y - b.y);
    }

    private static Route fromNodes(List<Vec> nodes, double stop, double protection, boolean firing, long proof,
        int defenseNode) {
        if (nodes.size() < 2) return null;
        List<Vec> points = new ArrayList<>();
        points.add(nodes.get(0));
        for (int i = 1; i < nodes.size(); i++) {
            Vec a = nodes.get(i - 1), b = nodes.get(i);
            double top = Math.max(a.y, b.y);
            Vec raisedA = new Vec(a.x, top, a.z), raisedB = new Vec(b.x, top, b.z);
            if (a.distance(raisedA) > EPSILON) points.add(raisedA);
            points.add(raisedB);
            if (raisedB.distance(b) > EPSILON) points.add(b);
        }
        return new Route(points, nodes, nodes.get(0), null, null, stop, protection, firing, proof, defenseNode);
    }

    /** FULL 将当前掩体作为候选重新求证，严格以原计划落点为基准计算遮挡 */
    public static Route retainCover(CombatSnapshot snapshot, Target target, Route previous, int nextNode,
        CombatWorkQuota quota) {
        if (!quota.full || !snapshot.complete
            || !snapshot.self.grounded
            || snapshot.self.stationary
            || snapshot.geometry == null
            || !snapshot.geometry.complete
            || !snapshot.geometry.fresh(snapshot.tick)
            || previous == null
            || !previous.fresh(snapshot.tick)
            || previous.conditionalDoor
            || previous.escape == null
            || target == null
            || !target.visible
            || target.observedAt != snapshot.tick) return null;
        Meter meter = new Meter(quota, Math.min(MAX_WORK / 2, quota.remaining(Work.COLLISION)));
        Node last = new Node(snapshot.self.position, null, false);
        if (!support(snapshot, last.point, last.point.y, last.point.y, meter)
            || !clear(snapshot, last.point, last.point, false, meter)) return null;
        for (int i = Math.max(1, nextNode); i < previous.nodes.size(); i++) {
            if (!meter.pay()) return null;
            Vec point = previous.nodes.get(i);
            if (last.point.distance(point) < EPSILON) continue;
            if (!support(snapshot, point, point.y, point.y, meter) || !clear(snapshot, point, point, false, meter)
                || !connect(snapshot, last.point, point, false, meter)) return null;
            last = new Node(point, last, false);
        }
        Vec entrance = previous.entrance, escape = previous.escape;
        // 剩余tail可缩成终点一格，原入口身份仍须单独保留并用当前几何重证
        if (entrance == null || entrance.distance(last.point) < EPSILON
            || !support(snapshot, entrance, entrance.y, entrance.y, meter)
            || !clear(snapshot, entrance, entrance, false, meter)
            || !connect(snapshot, last.point, entrance, false, meter)) return null;
        last.entrance = entrance;
        if (escape.distance(last.point) < EPSILON || escape.distance(entrance) < EPSILON
            || !support(snapshot, escape, escape.y, escape.y, meter)
            || !clear(snapshot, escape, escape, false, meter)
            || !connect(snapshot, last.point, escape, false, meter)) return null;
        last.exit = escape;
        double protection = protection(snapshot, last.point, target, meter);
        if (protection <= .2) return null;
        boolean line = aimClear(snapshot, last.point, target, meter) && friendly(snapshot, last.point, target, meter);
        if (line) last.peek = last.point;
        else if (previous.peek != null && previous.peek.distance(last.point) <= CONTINUATION_DISTANCE
            && support(snapshot, previous.peek, previous.peek.y, previous.peek.y, meter)
            && clear(snapshot, previous.peek, previous.peek, false, meter)
            && connect(snapshot, last.point, previous.peek, false, meter)
            && aimClear(snapshot, previous.peek, target, meter)
            && friendly(snapshot, previous.peek, target, meter)) last.peek = previous.peek;
        if (last.peek == null || meter.exhausted) return null;
        return route(last, protection, line, snapshot.geometry.observedAt, meter);
    }

    /** 只复核既有路线的下一个已知段；不搜索、不跨关闭门、不续期原证明 */
    public static boolean safeStep(CombatSnapshot snapshot, Vec from, Vec to, CombatWorkQuota quota) {
        if (from == null || to == null
            || !Double.isFinite(from.x + from.y + from.z + to.x + to.y + to.z)
            || snapshot.geometry == null
            || !snapshot.geometry.complete
            || !snapshot.geometry.fresh(snapshot.tick)
            || from.distance(to) > CONTINUATION_DISTANCE) return false;
        Meter meter = new Meter(quota, Math.min(CONTINUATION_WORK, quota.remaining(Work.COLLISION)));
        return support(snapshot, from, from.y, from.y, meter) && clear(snapshot, from, from, false, meter)
            && support(snapshot, to, to.y, to.y, meter)
            && connect(snapshot, from, to, false, meter);
    }

    /** 仅求证当前闭门请求段的连续性；闭门状态保持闭门，条件路线仍需常规核验 */
    public static boolean safeRequestStep(CombatSnapshot snapshot, Vec from, Vec to, CombatWorkQuota quota) {
        if (!snapshot.complete || snapshot.geometry == null
            || !snapshot.geometry.complete
            || !snapshot.geometry.fresh(snapshot.tick)
            || from.distance(to) > CONTINUATION_DISTANCE
            || from.distance(to) < 1.5) return false;
        Meter meter = new Meter(quota, Math.min(CONTINUATION_WORK, quota.remaining(Work.COLLISION)));
        return openable(
            snapshot.geometry,
            floor((from.x + to.x) * .5),
            floor(from.y),
            floor((from.z + to.z) * .5),
            meter) && support(snapshot, from, from.y, from.y, meter)
            && support(snapshot, to, to.y, to.y, meter)
            && connect(snapshot, from, to, true, meter);
    }

    public static Sight sight(CombatSnapshot snapshot, Vec from, Vec to, CombatWorkQuota quota) {
        if (snapshot.geometry == null || !snapshot.geometry.fresh(snapshot.tick)) return Sight.UNKNOWN;
        return trace(snapshot.geometry, from, to, new Meter(quota, MAX_RAY_CELLS));
    }

    /** UNKNOWN/BUDGET不提供保护；只有已知方块且在身体之前相交才能截断箭 */
    public static double blockingFraction(CombatSnapshot snapshot, Vec from, Vec to, CombatWorkQuota quota) {
        if (snapshot.geometry == null || !snapshot.geometry.fresh(snapshot.tick)) return Double.POSITIVE_INFINITY;
        double hit = traceEntry(snapshot.geometry, from, to, new Meter(quota, MAX_RAY_CELLS));
        return hit < 0 ? Double.POSITIVE_INFINITY : hit;
    }

    public static double protection(CombatSnapshot snapshot, Vec point, Target threat, CombatWorkQuota quota) {
        return protection(snapshot, point, threat, threat.position, quota);
    }

    public static double protection(CombatSnapshot snapshot, Vec point, Target threat, Vec predicted,
        CombatWorkQuota quota) {
        if (snapshot.geometry == null || !snapshot.geometry.fresh(snapshot.tick)) return 0;
        return protection(
            snapshot,
            point,
            predicted.add(new Vec(0, threat.height * .85, 0)),
            new Meter(quota, MAX_RAY_CELLS * 3));
    }

    private static double protection(CombatSnapshot snapshot, Vec point, Target target, Meter meter) {
        return protection(snapshot, point, target.position.add(new Vec(0, target.height * .85, 0)), meter);
    }

    private static double protection(CombatSnapshot snapshot, Vec point, Vec eye, Meter meter) {
        int blocked = 0;
        double[] heights = { .25, snapshot.self.height * .55, snapshot.self.height - .15 };
        for (double height : heights) {
            Sight sight = trace(snapshot.geometry, point.add(new Vec(0, height, 0)), eye, meter);
            if (sight == Sight.BLOCKED) blocked++;
        }
        return blocked / 3.0;
    }

    private static boolean aimClear(CombatSnapshot snapshot, Vec point, Target target, Meter meter) {
        if (!target.visible || target.observedAt != snapshot.tick || !meter.pay()) return false;
        if (point.distance(snapshot.self.position) < .05) return true; // 当帧已取得的真实LOS
        return trace(
            snapshot.geometry,
            point.add(new Vec(0, snapshot.self.height * .85, 0)),
            target.position.add(new Vec(0, target.height * .5, 0)),
            meter) == Sight.CLEAR;
    }

    private static boolean friendly(CombatSnapshot snapshot, Vec point, Target target, Meter meter) {
        Vec a = point.add(new Vec(0, snapshot.self.height * .85, 0));
        Vec b = target.position.add(new Vec(0, target.height * .5, 0));
        for (CombatSnapshot.Friend friend : snapshot.friends) {
            if (!meter.pay() || !friend.current || friend.observedAt != snapshot.tick) return false;
            double width = friend.halfWidth + .3;
            if (ThreatEstimator.segmentBox(
                a,
                b,
                friend.position.x - width,
                friend.position.y - .3,
                friend.position.z - width,
                friend.position.x + width,
                friend.position.y + friend.height + .3,
                friend.position.z + width)) return false;
        }
        return snapshot.friendsComplete;
    }

    private static Vec standing(CombatSnapshot snapshot, double x, double z, double around, Meter meter) {
        Geometry geometry = snapshot.geometry;
        Vec best = null;
        for (int y = floor(around) - 1; y <= floor(around); y++) {
            if (!meter.pay()) return null;
            int flags = geometry.at(floor(x), y, floor(z));
            if ((flags & Geometry.SOLID) == 0 || (flags & (Geometry.UNKNOWN | Geometry.HAZARD | Geometry.WATER)) != 0)
                continue;
            double foot = geometry.bound(floor(x), y, floor(z), 4);
            if (Math.abs(foot - around) > STEP_HEIGHT + EPSILON) continue;
            Vec point = new Vec(x, foot, z);
            if (!support(snapshot, point, foot, foot, meter) || !clear(snapshot, point, point, false, meter)) continue;
            if (best == null || Math.abs(foot - around) < Math.abs(best.y - around)) best = point;
        }
        return best;
    }

    private static boolean connect(CombatSnapshot snapshot, Vec a, Vec b, boolean door, Meter meter) {
        return connect(snapshot.geometry, snapshot.self.halfWidth, snapshot.self.height, a, b, door, meter);
    }

    private static boolean connect(Geometry geometry, double halfWidth, double height, Vec a, Vec b, boolean door,
        Meter meter) {
        if (Math.abs(a.y - b.y) > STEP_HEIGHT + EPSILON) return false;
        double top = Math.max(a.y, b.y), low = Math.min(a.y, b.y);
        Vec raisedA = new Vec(a.x, top, a.z), raisedB = new Vec(b.x, top, b.z);
        if (!clear(geometry, halfWidth, height, a, raisedA, false, meter)
            || !clear(geometry, halfWidth, height, raisedA, raisedB, door, meter)
            || !clear(geometry, halfWidth, height, raisedB, b, false, meter)) return false;
        for (int i = 1; i <= 3; i++) {
            Vec p = a.add(
                b.subtract(a)
                    .scale(i / 4.0));
            if (!support(geometry, halfWidth, p, low, top, meter)) return false;
        }
        return true;
    }

    private static boolean support(CombatSnapshot snapshot, Vec p, double low, double high, Meter meter) {
        return support(snapshot.geometry, snapshot.self.halfWidth, p, low, high, meter);
    }

    private static boolean support(Geometry g, double halfWidth, Vec p, double low, double high, Meter meter) {
        double half = halfWidth * .8;
        for (int dx = -1; dx <= 1; dx += 2) for (int dz = -1; dz <= 1; dz += 2) {
            double px = p.x + half * dx, pz = p.z + half * dz;
            int x = floor(px), z = floor(pz);
            boolean supported = false;
            for (int y = floor(low) - 1; y <= floor(high); y++) {
                if (!meter.pay()) return false;
                int flags = g.at(x, y, z);
                if ((flags & (Geometry.UNKNOWN | Geometry.HAZARD | Geometry.WATER)) != 0) return false;
                if ((flags & Geometry.SOLID) == 0) continue;
                double foot = g.bound(x, y, z, 4);
                if (foot < low - EPSILON || foot > high + EPSILON) continue;
                if (px >= g.bound(x, y, z, 0) && px <= g.bound(x, y, z, 3)
                    && pz >= g.bound(x, y, z, 2)
                    && pz <= g.bound(x, y, z, 5)) supported = true;
            }
            if (!supported) return false;
        }
        return true;
    }

    private static boolean clear(CombatSnapshot snapshot, Vec a, Vec b, boolean allowDoor, Meter meter) {
        return clear(snapshot.geometry, snapshot.self.halfWidth, snapshot.self.height, a, b, allowDoor, meter);
    }

    private static boolean clear(Geometry g, double w, double height, Vec a, Vec b, boolean allowDoor, Meter meter) {
        for (int y = floor(Math.min(a.y, b.y) + .02) - 1; y <= floor(Math.max(a.y, b.y) + height - .02); y++)
            for (int z = floor(Math.min(a.z, b.z) - w); z <= floor(Math.max(a.z, b.z) + w); z++)
                for (int x = floor(Math.min(a.x, b.x) - w); x <= floor(Math.max(a.x, b.x) + w); x++) {
                    if (!meter.pay()) return false;
                    int flags = g.at(x, y, z);
                    if ((flags & (Geometry.UNKNOWN | Geometry.HAZARD | Geometry.WATER)) != 0) return false;
                    if ((flags & Geometry.SOLID) == 0 || allowDoor && (flags & Geometry.OPENABLE) != 0) continue;
                    if (ThreatEstimator.segmentBox(
                        a,
                        b,
                        g.bound(x, y, z, 0) - w,
                        g.bound(x, y, z, 1) - height + .02,
                        g.bound(x, y, z, 2) - w,
                        g.bound(x, y, z, 3) + w,
                        g.bound(x, y, z, 4) - .02,
                        g.bound(x, y, z, 5) + w)) return false;
                }
        return true;
    }

    private static boolean corridor(CombatSnapshot snapshot, Vec point, Meter meter) {
        Geometry g = snapshot.geometry;
        int x = floor(point.x), y = floor(point.y + .8), z = floor(point.z);
        if (!meter.pay() || !meter.pay() || !meter.pay() || !meter.pay()) return false;
        int a = g.at(x - 1, y, z), b = g.at(x + 1, y, z), c = g.at(x, y, z - 1), d = g.at(x, y, z + 1);
        return solidKnown(a) && solidKnown(b) || solidKnown(c) && solidKnown(d);
    }

    private static boolean solidKnown(int flags) {
        return (flags & Geometry.SOLID) != 0 && (flags & (Geometry.UNKNOWN | Geometry.HAZARD)) == 0;
    }

    private static boolean openable(Geometry g, int x, int y, int z, Meter meter) {
        return meter.pay()
            && (g.at(x, y, z) & (Geometry.OPENABLE | Geometry.UNKNOWN | Geometry.HAZARD)) == Geometry.OPENABLE;
    }

    private static Route route(Node node, double protection, boolean line, long provedAt, Meter meter) {
        List<Node> reverse = new ArrayList<>();
        for (Node n = node; n != null; n = n.parent) {
            if (!meter.pay()) return null;
            reverse.add(n);
        }
        List<Vec> points = new ArrayList<>();
        List<Vec> navigation = new ArrayList<>();
        Vec previous = reverse.get(reverse.size() - 1).point;
        points.add(previous);
        navigation.add(previous);
        double length = 0, door = Double.POSITIVE_INFINITY;
        for (int i = reverse.size() - 2; i >= 0; i--) {
            Node n = reverse.get(i);
            if (n.door) door = Math.min(door, length);
            double top = Math.max(previous.y, n.point.y);
            Vec raisedA = new Vec(previous.x, top, previous.z), raisedB = new Vec(n.point.x, top, n.point.z);
            if (previous.distance(raisedA) > EPSILON) {
                points.add(raisedA);
                length += previous.distance(raisedA);
            }
            points.add(raisedB);
            length += raisedA.distance(raisedB);
            if (n.point.distance(raisedB) > EPSILON) {
                points.add(n.point);
                length += raisedB.distance(n.point);
            }
            previous = n.point;
            navigation.add(n.point);
        }
        return new Route(
            points,
            navigation,
            node.entrance != null ? node.entrance : node.parent == null ? null : node.parent.point,
            node.exit,
            node.peek,
            door,
            protection,
            line,
            provedAt,
            0,
            node.passiveExit);
    }

    private static Sight trace(Geometry g, Vec from, Vec to, Meter meter) {
        double entry = traceEntry(g, from, to, meter);
        return entry == -2 ? Sight.BUDGET
            : entry < 0 ? Sight.UNKNOWN : Double.isFinite(entry) ? Sight.BLOCKED : Sight.CLEAR;
    }

    private static double traceEntry(Geometry g, Vec from, Vec to, Meter meter) {
        if (g == null) return -1;
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        int x = floor(from.x), y = floor(from.y), z = floor(from.z);
        int endX = floor(to.x), endY = floor(to.y), endZ = floor(to.z);
        int sx = Double.compare(dx, 0), sy = Double.compare(dy, 0), sz = Double.compare(dz, 0);
        double tx = crossing(from.x, dx), ty = crossing(from.y, dy), tz = crossing(from.z, dz);
        for (int i = 0; i < MAX_RAY_CELLS; i++) {
            if (!meter.pay()) return -2;
            int flags = g.at(x, y, z);
            if ((flags & (Geometry.UNKNOWN | Geometry.RAY_UNKNOWN)) != 0) return -1;
            if ((flags & Geometry.SOLID) != 0 && (flags & Geometry.RAY_BLOCKING) == 0) return -1;
            double hit = (flags & Geometry.SOLID) == 0 ? Double.POSITIVE_INFINITY
                : entry(
                    from,
                    to,
                    g.bound(x, y, z, 0),
                    g.bound(x, y, z, 1),
                    g.bound(x, y, z, 2),
                    g.bound(x, y, z, 3),
                    g.bound(x, y, z, 4),
                    g.bound(x, y, z, 5));
            if (Double.isFinite(hit)) return hit;
            if (x == endX && y == endY && z == endZ) return Double.POSITIVE_INFINITY;
            double next = Math.min(tx, Math.min(ty, tz));
            if (tx <= next + 1.0E-10) {
                x += sx;
                tx += Math.abs(1 / dx);
            }
            if (ty <= next + 1.0E-10) {
                y += sy;
                ty += Math.abs(1 / dy);
            }
            if (tz <= next + 1.0E-10) {
                z += sz;
                tz += Math.abs(1 / dz);
            }
        }
        return -1;
    }

    public static double entry(Vec from, Vec to, double minX, double minY, double minZ, double maxX, double maxY,
        double maxZ) {
        double start = 0, end = 1;
        for (int axis = 0; axis < 3; axis++) {
            double a = axis == 0 ? from.x : axis == 1 ? from.y : from.z;
            double delta = (axis == 0 ? to.x : axis == 1 ? to.y : to.z) - a;
            double min = axis == 0 ? minX : axis == 1 ? minY : minZ;
            double max = axis == 0 ? maxX : axis == 1 ? maxY : maxZ;
            if (Math.abs(delta) < 1.0E-9) {
                if (a < min || a > max) return Double.POSITIVE_INFINITY;
            } else {
                double first = (min - a) / delta, last = (max - a) / delta;
                start = Math.max(start, Math.min(first, last));
                end = Math.min(end, Math.max(first, last));
                if (start > end) return Double.POSITIVE_INFINITY;
            }
        }
        return start;
    }

    private static double crossing(double start, double delta) {
        if (Math.abs(delta) < 1.0E-10) return Double.POSITIVE_INFINITY;
        return ((delta > 0 ? Math.floor(start) + 1 : Math.floor(start)) - start) / delta;
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    private static final class Node {

        final Vec point;
        final Node parent;
        final boolean door;
        CombatPassiveDoorExitProof.Source passiveExit;
        Vec entrance, exit, peek;

        Node(Vec point, Node parent, boolean door) {
            this.point = point;
            this.parent = parent;
            this.door = door;
        }
    }

    private static final class Meter {

        final CombatWorkQuota quota;
        final int limit;
        int used;
        boolean exhausted;

        Meter(CombatWorkQuota quota, int limit) {
            this.quota = quota;
            this.limit = limit;
        }

        boolean pay() {
            if (used >= limit || !quota.spend(Work.COLLISION)) {
                exhausted = true;
                return false;
            }
            used++;
            return true;
        }
    }
}
