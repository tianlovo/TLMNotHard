package com.tianluoqaq.tlmnh.common.ai.enhanced.combat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.util.MathHelper;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskAttack;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatSnapshot.DoorCell;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatSnapshot.Friend;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatSnapshot.Geometry;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatSnapshot.Projectile;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatSnapshot.Target;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatSnapshot.Vec;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatSnapshot.Weapon;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.CombatWorkQuota.Work;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatAttributes;
import com.tianluoqaq.tlmnh.common.combat.weapon.MaidWeaponCatalog;
import com.tianluoqaq.tlmnh.common.config.MaidPerceptionConfig;
import com.tianluoqaq.tlmnh.compat.galacticraftcore.common.physics.NativeArrowGravity;

/** 唯一世界观测边界；区块实体列表增量游标，不先执行全量AABB查询 */
public final class CombatObservation {

    private static final TaskAttack ATTACK = new TaskAttack();
    private static final int TARGETS = 8, PROJECTILES = 12, FRIENDS = 12, CHUNK_COUNT = 25;
    private static final int SCAN_CHUNKS_PER_TICK = 4, PREPARATION_CHUNKS_PER_TICK = 8;
    private final Map<UUID, Tracked> targets = new LinkedHashMap<>();
    private final Map<UUID, LivingTrack> living = new LinkedHashMap<>();
    private final Map<UUID, ProjectileTrack> arrows = new LinkedHashMap<>();
    private final List<Friend> friends = new ArrayList<>();
    private final Map<UUID, Long> ownHits = new LinkedHashMap<>();
    private long tick = Long.MIN_VALUE, epoch, arrowEventCursor, scanCompletedAt = Long.MIN_VALUE,
        scanStartedAt = Long.MIN_VALUE;
    private int chunkCursor, sectionCursor, entityCursor, centerX = Integer.MIN_VALUE, centerZ;
    private boolean scanComplete, projectileComplete, observationComplete, coverageScanning;
    private boolean observedFull, startedFull, permanentObservationGap, permanentProjectileGap, urgentPending,
        scanPending;
    private int chunksVisitedThisTick, geometryUnknownCells, geometryBudgetCells, refreshCursor;
    private int primaryRays, backgroundRays, secondaryRays;
    private net.minecraft.world.chunk.Chunk scanChunk;
    private final Map<UUID, Entity> deferred = new LinkedHashMap<>();
    private final Map<UUID, SampleResult> sampled = new LinkedHashMap<>();
    private static final int MAX_DEFERRED = 32, MAX_SAMPLED = 128;
    private Readiness lastReadiness;
    private long latestArrowSequence, completedScanTicks = Long.MAX_VALUE;
    private boolean scopeResetThisTick;
    private int observedStage;
    private long preparationRound, coverageRound, completedCoverageOldest = Long.MIN_VALUE;
    private final Map<UUID, FriendTrack> friendTracks = new LinkedHashMap<>();
    private boolean friendOverflow, friendIdentityGap;
    private int friendRays;
    private boolean preparationRestarted;
    private boolean selectedCurrent;

    private enum SampleResult {
        OBSERVED,
        HIDDEN,
        OUTSIDE_RANGE,
        BUDGET_REJECTED
    }

    private NativeArrowGravity.LaunchSample observationGravity;
    private int gravityQueries, gravityReuses;

    private Geometry geometry;
    private List<Weapon> weapons = Collections.emptyList();
    private int ammunition;

    public void observe(EntityMaid maid, CombatWorkQuota quota) {
        long now = maid.worldObj.getTotalWorldTime();
        int stage = quota.full ? 2 : quota.preparing ? 1 : 0;
        if (tick == now) {
            if (stage > observedStage) {
                observedStage = stage;
                observedFull |= quota.full;
                beginPreparation(maid, quota);
                collectUrgent(maid, quota);
                refreshPrimary(maid, quota);
                if (quota.full) refreshFriends(maid, quota);
                for (Entity entity : new ArrayList<>(deferred.values())) {
                    if (entity instanceof EntityLivingBase livingEntity) sampleLiving(maid, livingEntity, quota);
                    else if (entity instanceof EntityArrow arrow) sampleArrow(maid, arrow, quota);
                }
                if (weapons.size() < 7) captureWeapons(maid, quota);
                scanLoaded(maid, quota);
                refreshSecondary(maid, quota);
                finishObservation();
                recordReadiness(maid, quota, false);
                sampledPreparation(maid, quota);
            }
            return;
        }
        if (now < tick) clear();
        tick = now;
        observationGravity = null;
        gravityQueries = gravityReuses = 0;
        observedFull = startedFull = quota.full;
        observedStage = stage;
        permanentObservationGap = permanentProjectileGap = urgentPending = scanPending = false;
        chunksVisitedThisTick = 0;
        primaryRays = backgroundRays = secondaryRays = friendRays = 0;
        scopeResetThisTick = false;
        scanChunk = null;
        deferred.clear();
        sampled.clear();
        CombatProjectileEvents.register();
        friends.clear();
        friendIdentityGap = false;
        for (FriendTrack friend : friendTracks.values()) friend.current = false;
        beginPreparation(maid, quota);
        observationComplete = true;
        living.entrySet()
            .removeIf(
                entry -> now - entry.getValue().at > CombatSnapshot.OBSERVATION_TTL
                    || !current(maid, entry.getValue().entity));
        captureWeapons(maid, quota);
        collectUrgent(maid, quota);
        for (Iterator<ProjectileTrack> iterator = arrows.values()
            .iterator(); iterator.hasNext();) {
            ProjectileTrack arrow = iterator.next();
            if (!current(maid, arrow.entity) || now - arrow.at > 2) iterator.remove();
            else sampleArrow(maid, arrow.entity, quota);
        }
        for (Tracked tracked : new ArrayList<>(targets.values())) {
            if (!current(maid, tracked.entity) || now - tracked.observedAt > CombatSnapshot.HIDDEN_TTL) {
                targets.remove(tracked.entity.getUniqueID());
                continue;
            }
            if (tracked.observedAt != tick) tracked.visible = false;
        }
        refreshPrimary(maid, quota);
        EntityLivingBase owner = maid.getOwner();
        if (owner != null) recordFriend(owner, position(owner));
        if (quota.full) refreshFriends(maid, quota);
        scanLoaded(maid, quota);
        refreshSecondary(maid, quota);
        finishObservation();
        recordReadiness(maid, quota, false);
        sampledPreparation(maid, quota);
        ownHits.entrySet()
            .removeIf(entry -> now < entry.getValue() || now - entry.getValue() > 100);
    }

    private void beginPreparation(EntityMaid maid, CombatWorkQuota quota) {
        if (!quota.preparing) return;
        if (preparationRound != quota.preparationRound) {
            preparationRound = quota.preparationRound;
            preparationRestarted = false;
            // 原准备窗末次采样仍须在覆盖TTL内；不回收本tick已付费用或扫描计数
            if (!coverageFresh() && scanStartedAt != Long.MIN_VALUE
                && tick >= scanStartedAt
                && tick - scanStartedAt > CombatSnapshot.OBSERVATION_TTL - (CombatWorldBudget.PREPARATION_TICKS - 1)) {
                startCoverage();
                preparationRestarted = true;
            }
        }
        if (!preparationRestarted && !coverageFresh()
            && scanStartedAt != Long.MIN_VALUE
            && tick >= scanStartedAt
            && tick - scanStartedAt > CombatSnapshot.OBSERVATION_TTL) {
            startCoverage();
            preparationRestarted = true;
        }
    }

    private void startCoverage() {
        scanChunk = null;
        chunkCursor = sectionCursor = entityCursor = 0;
        scanComplete = false;
        coverageScanning = true;
        scanStartedAt = Long.MIN_VALUE;
        completedCoverageOldest = Long.MIN_VALUE;
        coverageRound++;
    }

    private boolean coverageFresh() {
        return completedCoverageOldest != Long.MIN_VALUE && tick >= completedCoverageOldest
            && tick - completedCoverageOldest <= CombatSnapshot.OBSERVATION_TTL;
    }

    /** 已有身份具备FULL重验条件，尚不授予友方安全或几何完整性 */
    private final CombatFullServiceReceipt fullService = new CombatFullServiceReceipt();

    public CombatFullServiceReceipt.Status serviceResult(long now) {
        return fullService.status(now);
    }

    public void fullGranted(CombatWorkQuota quota) {
        if (quota.full) fullService.granted(quota.tick);
    }

    public boolean serviceAttempted(long now) {
        return fullService.attempted(now);
    }

    public boolean settleService(long now) {
        return fullService.settle(now);
    }

    public void completeForecast(long now, boolean complete) {
        if (complete && fullService.attempted(now)) fullService.captured(now, true, true);
    }

    public boolean bodyPreparationReady() {
        if (!preparationInputsReady()) return false;
        for (Tracked target : targets.values()) if (target.position != null && target.observedAt <= tick
            && tick - target.observedAt <= CombatSnapshot.HIDDEN_TTL) return true;
        return false;
    }

    public boolean preparationReady() {
        return preparationInputsReady() && hasCurrentVisibleTarget();
    }

    private boolean preparationInputsReady() {
        if (!scanComplete || !coverageFresh()
            || weapons.size() != 7
            || !projectileComplete
            || !observationComplete
            || friendOverflow) return false;
        return true;
    }

    private boolean hasCurrentVisibleTarget() {
        for (Tracked target : targets.values()) if (target.visible && target.observedAt == tick) return true;
        return false;
    }

    public long oldestCoverage() {
        return completedCoverageOldest;
    }

    public int friendRefreshDebt() {
        int debt = 0;
        for (FriendTrack friend : friendTracks.values()) if (!friend.current || friend.at != tick) debt++;
        return debt;
    }

    public int knownFriends() {
        return friendTracks.size();
    }

    private void sampledPreparation(EntityMaid maid, CombatWorkQuota quota) {
        if (quota.preparing) CombatWorldBudget.sampled(maid.worldObj, maid.getUniqueID(), tick, quota.preparationRound);
    }

    private void recordFriend(EntityLivingBase entity, Vec position) {
        UUID id = entity.getUniqueID();
        if (!friendTracks.containsKey(id) && friendTracks.size() >= FRIENDS) {
            friendOverflow = true;
            return;
        }
        friendTracks.put(id, new FriendTrack(entity, position, tick, coverageRound));
    }

    private void refreshFriends(EntityMaid maid, CombatWorkQuota quota) {
        int before = quota.used(Work.RAY);
        for (FriendTrack friend : new ArrayList<>(friendTracks.values())) {
            if (!quota.spendObservation(Work.ENTITY)) {
                remember(friend.entity, rejectObservation());
                continue;
            }
            // 缓存键退役只依据身份变化，不能把不可见或年龄耗尽当作身份消失
            if (!friend.id.equals(friend.entity.getUniqueID()) || friend.entityId != friend.entity.getEntityId()
                || friend.dimension != friend.entity.dimension) {
                UUID currentId = friend.entity.getUniqueID();
                friendTracks.remove(friend.id, friend);
                sampled.remove(friend.id);
                sampled.remove(currentId);
                living.remove(friend.id);
                living.remove(currentId);
                targets.remove(friend.id);
                targets.remove(currentId);
                // 退役旧键不等于证明当前位置无人；新身份只能由下一次真实LOS建立样本
                friendIdentityGap = permanentObservationGap = true;
                observationComplete = false;
                continue;
            }
            if (friend.current && friend.at == tick) continue;
            if (!current(maid, friend.entity)) {
                friendTracks.remove(friend.id);
                continue;
            }
            if (friend.entity == maid.getOwner()) {
                recordFriend(friend.entity, position(friend.entity));
                continue;
            }
            // 不可见和过期均保留旧身份、位置和时间，只把完整性置为未知
            sampleLiving(maid, friend.entity, quota);
        }
        friendRays += quota.used(Work.RAY) - before;
    }

    private boolean friendsComplete() {
        if (friendOverflow || friendIdentityGap) return false;
        for (FriendTrack friend : friendTracks.values()) if (!friend.current || friend.at != tick) return false;
        return true;
    }

    private void collectUrgent(EntityMaid maid, CombatWorkQuota quota) {
        urgentPending = false;
        // 已发生受击和主人受击是增量事件，不等周期清单；事件本身仍收费
        EntityLivingBase attacker = maid.getAITarget();
        if (attacker != null && !sampled.containsKey(attacker.getUniqueID())) {
            if (quota.spend(Work.EVENT)) sampleLiving(maid, attacker, quota);
            else {
                urgentPending = true;
                rejectObservation();
            }
        }
        EntityLivingBase owner = maid.getOwner();
        if (owner != null && owner.getAITarget() != null
            && !sampled.containsKey(
                owner.getAITarget()
                    .getUniqueID())) {
            if (quota.spend(Work.EVENT)) sampleLiving(maid, owner.getAITarget(), quota);
            else {
                urgentPending = true;
                rejectObservation();
            }
        }
        long latestArrow = CombatProjectileEvents.sequence(maid.worldObj);
        latestArrowSequence = latestArrow;
        if (arrowEventCursor < CombatProjectileEvents.oldest(latestArrow) - 1) permanentProjectileGap = true;
        arrowEventCursor = Math.max(arrowEventCursor, CombatProjectileEvents.oldest(latestArrow) - 1);
        int acute = 0;
        while (arrowEventCursor < latestArrow && acute++ < 8) {
            if (!quota.spend(Work.EVENT)) {
                urgentPending = true;
                rejectObservation();
                break;
            }
            long sequence = arrowEventCursor + 1;
            EntityArrow spawned = CombatProjectileEvents.arrow(maid.worldObj, sequence);
            SampleResult result = spawned == null ? SampleResult.HIDDEN : sampleArrow(maid, spawned, quota);
            if (result == SampleResult.BUDGET_REJECTED) break;
            arrowEventCursor = sequence;
        }
        if (arrowEventCursor < latestArrow) projectileComplete = false;
    }

    /** 当前动作只占一个目标刷新名额，其他已知身份不能吃尽背景扫描射线 */
    private void refreshPrimary(EntityMaid maid, CombatWorkQuota quota) {
        EntityLivingBase selected = maid.getModernCombatMemory()
            .target();
        if (selected == null || !targets.containsKey(selected.getUniqueID())) return;
        int before = quota.used(Work.RAY);
        sampleLiving(maid, selected, quota);
        primaryRays += quota.used(Work.RAY) - before;
    }

    /** 新鲜覆盖只做必要的目标重获取，不用可选刷新消耗待FULL的额度 */
    private void refreshSecondary(EntityMaid maid, CombatWorkQuota quota) {
        boolean recoveringTarget = scanComplete && coverageFresh();
        if (recoveringTarget && hasCurrentVisibleTarget()) return;
        var candidates = new ArrayList<>(targets.values());
        int before = quota.used(Work.RAY);
        for (int examined = 0; examined < candidates.size(); examined++) {
            if (quota.observationRemaining(Work.RAY) == 0 || quota.observationRemaining(Work.ENTITY) == 0) break;
            if (refreshCursor >= candidates.size()) refreshCursor = 0;
            Tracked tracked = candidates.get(refreshCursor++);
            if (tracked.observedAt != tick) sampleLiving(maid, tracked.entity, quota);
            if (recoveringTarget && hasCurrentVisibleTarget()) break;
        }
        secondaryRays += quota.used(Work.RAY) - before;
    }

    private void scanLoaded(EntityMaid maid, CombatWorkQuota quota) {
        int before = quota.used(Work.RAY);
        try {
            scanLoadedNow(maid, quota);
        } finally {
            backgroundRays += quota.used(Work.RAY) - before;
        }
    }

    private void scanLoadedNow(EntityMaid maid, CombatWorkQuota quota) {
        int cx = MathHelper.floor_double(maid.posX) >> 4, cz = MathHelper.floor_double(maid.posZ) >> 4;
        if (cx != centerX || cz != centerZ) {
            scopeResetThisTick = true;
            centerX = cx;
            centerZ = cz;
            startCoverage();
        }
        // 覆盖等待/消费FULL时不启动新轮，也不事后清除既有必要采集缺口
        if (scanComplete && coverageFresh()) return;
        if (!coverageScanning) {
            startCoverage();
        }
        scanPending = false;
        // 同tick升级只续做被预算挡住的实际游标，不重开四个chunk的额外扫描
        int chunkLimit = quota.preparing ? PREPARATION_CHUNKS_PER_TICK : SCAN_CHUNKS_PER_TICK;
        while (scanChunk != null || chunksVisitedThisTick < chunkLimit) {
            if (scanChunk == null && !quota.spendObservation(Work.CHUNK)) {
                scanPending = true;
                rejectObservation();
                return;
            }
            int offset = chunkCursor == 0 ? 12 : chunkCursor <= 12 ? chunkCursor - 1 : chunkCursor;
            int x = cx + offset % 5 - 2, z = cz + offset / 5 - 2;
            if (scanChunk == null) {
                if (scanStartedAt == Long.MIN_VALUE) scanStartedAt = tick;
                chunksVisitedThisTick++;
                if (!maid.worldObj.getChunkProvider()
                    .chunkExists(x, z)) {
                    nextChunk();
                    if (chunkCursor == 0) return;
                    continue;
                }
                scanChunk = maid.worldObj.getChunkFromChunkCoords(x, z);
            }
            var chunk = scanChunk;
            while (sectionCursor < chunk.entityLists.length) {
                // 从女仆所在高度开始，空section也只有固定16次访问
                int section = (MathHelper.floor_double(maid.posY) / 16 + sectionCursor) % chunk.entityLists.length;
                List<?> entities = chunk.entityLists[section];
                while (entityCursor < entities.size()) {
                    if (!quota.spendObservation(Work.ENTITY)) {
                        scanPending = true;
                        rejectObservation();
                        return;
                    }
                    Object value = entities.get(entityCursor);
                    SampleResult result = SampleResult.HIDDEN;
                    if (value instanceof Entity entity && entity != maid && current(maid, entity)) {
                        if (entity instanceof EntityLivingBase living) result = sampleLiving(maid, living, quota);
                        else if (entity instanceof EntityArrow arrow) result = sampleArrow(maid, arrow, quota);
                    }
                    if (result == SampleResult.BUDGET_REJECTED) {
                        scanPending = true;
                        return;
                    }
                    entityCursor++;
                }
                sectionCursor++;
                entityCursor = 0;
            }
            nextChunk();
            if (chunkCursor == 0) return;
        }
    }

    private void nextChunk() {
        scanChunk = null;
        sectionCursor = entityCursor = 0;
        if (++chunkCursor >= CHUNK_COUNT) {
            chunkCursor = 0;
            completedScanTicks = scanStartedAt != Long.MIN_VALUE && tick >= scanStartedAt ? tick - scanStartedAt
                : Long.MAX_VALUE;
            scanComplete = completedScanTicks <= CombatSnapshot.OBSERVATION_TTL;
            scanCompletedAt = tick;
            completedCoverageOldest = scanStartedAt;
            coverageScanning = false;
        }
    }

    private SampleResult sampleLiving(EntityMaid maid, EntityLivingBase entity, CombatWorkQuota quota) {
        if (entity == null) return SampleResult.HIDDEN;
        SampleResult cached = sampled.get(entity.getUniqueID());
        if (cached != null) return cached;
        return remember(entity, sampleLivingNow(maid, entity, quota));
    }

    private SampleResult sampleLivingNow(EntityMaid maid, EntityLivingBase entity, CombatWorkQuota quota) {
        Tracked already = entity == null ? null : targets.get(entity.getUniqueID());
        if (already != null && already.visible && already.observedAt == tick) return SampleResult.OBSERVED;
        if (entity == null || entity == maid || !current(maid, entity)) return SampleResult.HIDDEN;
        if (!quota.spendObservation(Work.ENTITY) || !quota.spendObservation(Work.RAY)) return rejectObservation();
        // 射线失败前不读取坐标/运动/生命/公开前摇，也不调用会读取隐藏位置的目标规则
        if (!maid.canEntityBeSeen(entity)) return SampleResult.HIDDEN;
        Vec position = position(entity);
        double range = supportedBow() ? Math.max(
            maid.getHomeArea()
                .restrictionRadius(),
            MaidPerceptionConfig.bowRange)
            : maid.getHomeArea()
                .restrictionRadius();
        double detection = entity.isSneaking() ? .8 : 1;
        if (entity.isInvisible()) {
            if (!quota.spendObservation(Work.ENTITY, 4)) return rejectObservation();
            int armor = 0;
            for (int slot = 1; slot <= 4; slot++) {
                ItemStack item = entity.getEquipmentInSlot(slot);
                if (item != null && item.stackSize > 0) armor++;
            }
            detection *= .7 * Math.max(.1F, armor / 4F);
        }
        if (position.distance(position(maid)) > Math.max(2, range * detection)) {
            if (entity != maid.getOwner()) friendTracks.remove(entity.getUniqueID());
            return SampleResult.OUTSIDE_RANGE;
        }
        if (living.size() >= 32 && !living.containsKey(entity.getUniqueID())) living.remove(
            living.keySet()
                .iterator()
                .next());
        living.put(entity.getUniqueID(), new LivingTrack(entity, tick));
        if (!legalVisible(maid, entity, position)) {
            if (friendly(maid, entity)) recordFriend(entity, position);
            else friendTracks.remove(entity.getUniqueID());
            targets.remove(entity.getUniqueID());
            return SampleResult.OBSERVED;
        }
        UUID id = entity.getUniqueID();
        friendTracks.remove(id);
        Tracked tracked = targets.get(id);
        if (tracked == null) {
            if (targets.size() >= TARGETS) {
                Tracked worst = null;
                for (Tracked candidate : targets.values()) if (worst == null || candidate.observedAt < worst.observedAt
                    || candidate.observedAt == worst.observedAt
                        && candidate.position.distance(position(maid)) > worst.position.distance(position(maid)))
                    worst = candidate;
                if (worst != null && worst.visible
                    && worst.position.distance(position(maid)) <= position.distance(position(maid))) {
                    permanentObservationGap = true;
                    observationComplete = false;
                    return SampleResult.OBSERVED;
                }
                permanentObservationGap = true;
                observationComplete = false;
                if (worst != null) targets.remove(worst.entity.getUniqueID());
            }
            tracked = new Tracked(entity);
            targets.put(id, tracked);
        }
        long elapsed = tick - tracked.observedAt;
        Vec observedVelocity = tracked.position != null && elapsed > 0 && elapsed <= 8
            ? position.subtract(tracked.position)
                .scale(1.0 / elapsed)
            : new Vec(0, 0, 0);
        if (observedVelocity.length() > .8) observedVelocity = observedVelocity.scale(.8 / observedVelocity.length());
        tracked.velocity = observedVelocity;
        tracked.position = position;
        tracked.observedAt = tick;
        tracked.visible = true;
        // 外部生物血量不是公开观测；以基础范围和本女仆已发生命中形成估值
        if (tracked.health <= 0) tracked.health = entity instanceof net.minecraft.entity.monster.IMob ? 20 : 30;
        tracked.width = entity.width * .5;
        tracked.height = entity.height;
        tracked.swinging = entity.isSwingInProgress;
        ItemStack held = entity.getHeldItem();
        tracked.ranged = held != null && held.getItem() instanceof ItemBow;
        tracked.explosive = entity instanceof EntityCreeper;
        EntityLivingBase owner = maid.getOwner();
        tracked.attackingOwner = owner != null && (owner.getAITarget() == entity || owner.getLastAttacker() == entity);
        return SampleResult.OBSERVED;
    }

    private SampleResult sampleArrow(EntityMaid maid, EntityArrow arrow, CombatWorkQuota quota) {
        if (arrow == null) return SampleResult.HIDDEN;
        SampleResult cached = sampled.get(arrow.getUniqueID());
        if (cached != null) return cached;
        return remember(arrow, sampleArrowNow(maid, arrow, quota));
    }

    private SampleResult sampleArrowNow(EntityMaid maid, EntityArrow arrow, CombatWorkQuota quota) {
        ProjectileTrack already = arrow == null ? null : arrows.get(arrow.getUniqueID());
        if (already != null && already.position != null && already.at == tick) return SampleResult.OBSERVED;
        if (arrow == null || !current(maid, arrow)) return SampleResult.HIDDEN;
        if (!quota.spendObservation(Work.ENTITY) || !quota.spend(Work.PROJECTILE)
            || !quota.spendObservation(Work.RAY)) {
            projectileComplete = false;
            return rejectObservation();
        }
        if (!maid.canEntityBeSeen(arrow)) return SampleResult.HIDDEN;
        if (arrow.shootingEntity == maid || arrow.shootingEntity == maid.getOwner()) return SampleResult.HIDDEN;
        Vec position = position(arrow);
        if (position.distance(position(maid)) > 24
            || arrow.motionX * arrow.motionX + arrow.motionY * arrow.motionY + arrow.motionZ * arrow.motionZ < .001)
            return SampleResult.HIDDEN;
        UUID id = arrow.getUniqueID();
        ProjectileTrack tracked = arrows.get(id);
        if (tracked == null) {
            if (arrows.size() >= PROJECTILES) {
                permanentProjectileGap = permanentObservationGap = true;
                projectileComplete = false;
                observationComplete = false;
                return SampleResult.OBSERVED;
            }
            tracked = new ProjectileTrack(arrow);
            arrows.put(id, tracked);
        }
        double gravity = tracked.gravity;
        // 当前observe tick内只复用已实际查询的同源输入；换tick/provider/身份/箭类型均重新收费
        if (observationGravity != null && observationGravity.sameObservationInput(arrow)) {
            gravity = observationGravity.gravity;
            gravityReuses++;
        } else {
            observationGravity = null;
            if (!quota.spend(Work.BALLISTIC)) {
                projectileComplete = false;
                return rejectObservation();
            }
            gravityQueries++;
            var sample = NativeArrowGravity.sampleForLaunch(arrow);
            if (sample != null && sample.isCurrent(arrow)) {
                gravity = sample.gravity;
                observationGravity = sample;
            } else {
                permanentProjectileGap = permanentObservationGap = true;
                projectileComplete = false;
                observationComplete = false;
            }
        }
        tracked.gravity = gravity;
        tracked.position = position;
        tracked.velocity = new Vec(arrow.motionX, arrow.motionY, arrow.motionZ);
        tracked.at = tick;
        return SampleResult.OBSERVED;
    }

    public CombatSnapshot capture(EntityMaid maid, EntityLivingBase selected, CombatWorkQuota quota, long entityEpoch,
        long equipmentEpoch, boolean equipped, boolean cadenceReady, boolean shieldAvailable, boolean drawing,
        int drawTicks, boolean stationary, boolean preferBow, boolean preferencePending,
        CombatMovementFact movementFact) {
        observe(maid, quota);
        Tracked chosen = selected == null ? null : targets.get(selected.getUniqueID());
        if (selected != null && (chosen == null || chosen.observedAt != tick)) sampleLiving(maid, selected, quota);
        chosen = selected == null ? null : targets.get(selected.getUniqueID());
        selectedCurrent = chosen != null && chosen.visible && chosen.observedAt == tick;
        if (quota.full) refreshFriends(maid, quota);
        if (quota.full && fullService.beginCapture(tick)) geometry = collectGeometry(maid, chosen, quota);
        EntityLivingBase owner = maid.getOwner();
        double attackSpeed = MaidCombatAttributes.attackSpeed(maid);
        long cadenceRemaining = maid.getCombatCadence()
            .remainingTicks();
        if (!maid.getCombatCadence()
            .formatSupported() || !cadenceReady && cadenceRemaining == 0) cadenceRemaining = Long.MAX_VALUE;
        CombatSnapshot.Self self = new CombatSnapshot.Self(
            position(maid),
            new Vec(maid.motionX, maid.motionY, maid.motionZ),
            owner == null ? null : position(owner),
            maid.getHealth(),
            owner == null ? 20 : owner.getHealth(),
            maid.width * .5,
            maid.height,
            MaidCombatAttributes.entityReach(maid) + maid.getFavorabilityManager()
                .getAttackDistancePlusByPoint(maid.getFavorability()),
            .20,
            maid.onGround,
            maid.onGround && !maid.isInWater()
                && !maid.isOnLadder()
                && maid.ridingEntity == null
                && !maid.isPotionActive(Potion.blindness),
            stationary,
            shieldAvailable,
            equipped,
            cadenceReady,
            drawing,
            attackSpeed > 0 ? Math.max(1, (int) Math.ceil(20 / attackSpeed)) : 20,
            cadenceRemaining,
            drawTicks,
            ammunition,
            preferBow,
            preferencePending,
            maid.hurtTime > 0,
            owner == null ? .3 : owner.width * .5,
            owner == null ? 1.8 : owner.height);
        List<Target> pureTargets = new ArrayList<>();
        for (Tracked target : targets.values()) {
            if (target.position == null || tick - target.observedAt > CombatSnapshot.HIDDEN_TTL) continue;
            pureTargets.add(
                new Target(
                    target.entity.getUniqueID(),
                    target.entity.getEntityId(),
                    target.position,
                    target.visible ? target.velocity : new Vec(0, 0, 0),
                    target.observedAt,
                    ownHits.getOrDefault(target.entity.getUniqueID(), Long.MIN_VALUE),
                    target.health,
                    target.explosive ? 12 : target.ranged ? 5 : 4,
                    target.width,
                    target.height,
                    target.visible,
                    target.swinging,
                    target.ranged,
                    target.explosive,
                    target.attackingOwner,
                    CombatSnapshot.DamageSource.GENERIC_PRIOR));
        }
        List<Projectile> pureArrows = new ArrayList<>();
        for (ProjectileTrack arrow : arrows.values())
            if (arrow.position != null && tick - arrow.at <= 2) pureArrows.add(
                new Projectile(
                    arrow.entity.getUniqueID(),
                    arrow.position,
                    arrow.velocity,
                    arrow.at,
                    arrow.gravity,
                    (double) .99F,
                    .3,
                    6,
                    CombatSnapshot.DamageSource.GENERIC_PRIOR));
        friends.clear();
        for (FriendTrack friend : friendTracks.values()) friends.add(friend.pure());
        recordReadiness(maid, quota, true);
        CombatSnapshot result = new CombatSnapshot(
            tick,
            entityEpoch,
            epoch,
            equipmentEpoch,
            maid.getUniqueID(),
            self,
            pureTargets,
            pureArrows,
            friends,
            weapons,
            geometry,
            selected == null ? null : selected.getUniqueID(),
            geometry != null && geometry.complete
                && weapons.size() == 7
                && scanComplete
                && projectileComplete
                && observationComplete
                && selectedCurrent
                && coverageFresh(),
            friendsComplete(),
            completedCoverageOldest,
            movementFact,
            geometry != null && geometry.complete
                && weapons.size() == 7
                && scanComplete
                && projectileComplete
                && observationComplete
                && coverageFresh());
        if (fullService.attempted(tick)) fullService.captured(tick, result.bodyComplete, false);
        return result;
    }

    private void captureWeapons(EntityMaid maid, CombatWorkQuota quota) {
        List<Weapon> captured = new ArrayList<>();
        ammunition = 0;
        var inventory = maid.getMaidInventory();
        for (int slot = -1; slot < 6; slot++) {
            if (slot >= 0 && !quota.spend(Work.ITEM)) break;
            ItemStack stack = slot == -1 ? maid.getHeldItem() : inventory.getStackInSlot(slot);
            captured.add(
                new Weapon(
                    slot,
                    fingerprint(stack),
                    slot == -1 ? maid.getCombatWeaponProfile() : MaidWeaponCatalog.inspect(stack)));
            if (stack != null && stack.getItem() == Items.arrow) ammunition += stack.stackSize;
        }
        ItemStack offhand = maid.getOffhandItem();
        if (quota.spend(Work.ITEM) && offhand != null && offhand.getItem() == Items.arrow)
            ammunition += offhand.stackSize;
        weapons = Collections.unmodifiableList(captured);
    }

    /** 只读取当tick已经采集的资源；实际发射仍逐项执行原库存/ACK最终复核 */
    public boolean observedAmmunition(long now) {
        return tick == now && ammunition > 0;
    }

    public boolean supportedBow() {
        for (Weapon weapon : weapons) if (weapon.profile.bow()) return true;
        return false;
    }

    public long epoch() {
        return epoch;
    }

    private Geometry collectGeometry(EntityMaid maid, Tracked selected, CombatWorkQuota quota) {
        boolean currentVisible = selected != null && selected.visible
            && selected.observedAt == tick
            && selected.position != null;
        int ox = CombatGeometryWindow
            .horizontalOrigin(maid.posX, currentVisible ? selected.position.x : 0, currentVisible, maid.width * .5),
            oy = MathHelper.floor_double(maid.boundingBox.minY) - CombatGeometryWindow.FLOOR_DEPTH,
            oz = CombatGeometryWindow
                .horizontalOrigin(maid.posZ, currentVisible ? selected.position.z : 0, currentVisible, maid.width * .5);
        byte[] cells = new byte[CombatGeometryWindow.WIDTH * CombatGeometryWindow.HEIGHT * CombatGeometryWindow.WIDTH];
        double[] bounds = new double[cells.length * 6];
        DoorCell[] doorCells = new DoorCell[cells.length];
        CombatSpace.BlockReader reader = CombatSpace.reader(
            maid,
            ox,
            oy,
            oz,
            ox + CombatGeometryWindow.WIDTH - 1,
            oy + CombatGeometryWindow.HEIGHT - 1,
            oz + CombatGeometryWindow.WIDTH - 1,
            quota);
        boolean complete = reader.result == CombatSpace.Result.SAFE;
        geometryUnknownCells = geometryBudgetCells = 0;
        for (int y = 0; y < CombatGeometryWindow.HEIGHT; y++)
            for (int z = 0; z < CombatGeometryWindow.WIDTH; z++) for (int x = 0; x < CombatGeometryWindow.WIDTH; x++) {
                int index = (y * CombatGeometryWindow.WIDTH + z) * CombatGeometryWindow.WIDTH + x, px = ox + x,
                    py = oy + y, pz = oz + z;
                var mask = net.minecraft.util.AxisAlignedBB
                    .getBoundingBox(px - .01, py - .01, pz - .01, px + 1.01, py + 2.01, pz + 1.01);
                CombatSpace.BlockShapes shapes = reader.block(px, py, pz, mask);
                if (shapes.result != CombatSpace.Result.SAFE) {
                    cells[index] = Geometry.UNKNOWN;
                    if (shapes.result == CombatSpace.Result.BUDGET) geometryBudgetCells++;
                    else geometryUnknownCells++;
                    complete = false;
                    continue;
                }
                cells[index] = (byte) shapes.flags;
                doorCells[index] = shapes.doorCell;
                if (shapes.count == 0) continue;
                cells[index] |= Geometry.SOLID;
                int offset = index * 6;
                bounds[offset] = shapes.minX;
                bounds[offset + 1] = shapes.minY;
                bounds[offset + 2] = shapes.minZ;
                bounds[offset + 3] = shapes.maxX;
                bounds[offset + 4] = shapes.maxY;
                bounds[offset + 5] = shapes.maxZ;
            }
        return new Geometry(
            ox,
            oy,
            oz,
            CombatGeometryWindow.WIDTH,
            CombatGeometryWindow.HEIGHT,
            CombatGeometryWindow.WIDTH,
            tick,
            complete,
            cells,
            bounds,
            doorCells);
    }

    private SampleResult remember(Entity entity, SampleResult result) {
        UUID id = entity.getUniqueID();
        if (result == SampleResult.BUDGET_REJECTED) {
            if (deferred.size() < MAX_DEFERRED || deferred.containsKey(id)) deferred.put(id, entity);
            else permanentObservationGap = true;
        } else {
            deferred.remove(id);
            if (sampled.size() < MAX_SAMPLED || sampled.containsKey(id)) sampled.put(id, result);
            else permanentObservationGap = true;
        }
        return result;
    }

    private void finishObservation() {
        observationComplete = !permanentObservationGap && !urgentPending && !scanPending && deferred.isEmpty();
        boolean missingArrow = false;
        for (Entity entity : deferred.values()) missingArrow |= entity instanceof EntityArrow;
        projectileComplete = !permanentProjectileGap && !missingArrow && arrowEventCursor >= latestArrowSequence;
    }

    private void recordReadiness(EntityMaid maid, CombatWorkQuota quota, boolean captured) {
        lastReadiness = new Readiness(this, quota, captured);
    }

    public Readiness readiness() {
        return lastReadiness;
    }

    public static final class Readiness {

        public static final int FRIENDS_INCOMPLETE = 1024, TARGET_INCOMPLETE = 2048;
        public static final int GEOMETRY_MISSING = 1, GEOMETRY_UNKNOWN = 2, GEOMETRY_STALE = 4, WEAPONS_PARTIAL = 8,
            SCAN_INCOMPLETE = 16, SCAN_STALE = 32, PROJECTILE_INCOMPLETE = 64, OBSERVATION_INCOMPLETE = 128,
            NO_CAPTURE = 256, OBSERVATION_DENIED = 512;
        public final long tick, scanCycleTicks, completedScanAge, completedScanTicks, geometryAge;
        public final long oldestCoverage, coverageRound, preparationRound;
        public final int friendCount, friendRays;
        public final boolean preparing, preparationReady, friendsComplete, friendOverflow;
        public final List<Friend> friendSamples;
        public final int blockedMask, chunkCursor, sectionCursor, entityCursor, chunksVisited, deferredEntities,
            targetCount, weaponCount, unknownGeometryCells, budgetGeometryCells, primaryRays, backgroundRays,
            secondaryRays, visibleTargets, gravityQueries, gravityReuses;
        public final boolean fullAtStart, fullAtCapture, fullContinuation, captured;
        public final boolean permanentObservationGap, permanentProjectileGap, urgentPending, scanPending, scopeReset;
        public final int[] used, observationDenials, denials;

        private Readiness(CombatObservation source, CombatWorkQuota quota, boolean captured) {
            this.captured = captured;
            oldestCoverage = source.completedCoverageOldest;
            coverageRound = source.coverageRound;
            preparationRound = source.preparationRound;
            preparing = quota.preparing;
            preparationReady = source.preparationReady();
            friendsComplete = source.friendsComplete();
            friendCount = source.friendTracks.size();
            friendOverflow = source.friendOverflow;
            friendRays = source.friendRays;
            List<Friend> sampledFriends = new ArrayList<>();
            for (FriendTrack friend : source.friendTracks.values()) sampledFriends.add(friend.pure());
            friendSamples = Collections.unmodifiableList(sampledFriends);
            permanentObservationGap = source.permanentObservationGap;
            permanentProjectileGap = source.permanentProjectileGap;
            urgentPending = source.urgentPending;
            scanPending = source.scanPending;
            scopeReset = source.scopeResetThisTick;
            completedScanTicks = source.completedScanTicks;
            tick = source.tick;
            fullAtStart = source.startedFull;
            fullAtCapture = quota.full;
            fullContinuation = quota.full && !source.startedFull && source.observedFull;
            scanCycleTicks = source.scanStartedAt != Long.MIN_VALUE && tick >= source.scanStartedAt
                ? tick - source.scanStartedAt
                : Long.MAX_VALUE;
            completedScanAge = source.scanCompletedAt == Long.MIN_VALUE || tick < source.scanCompletedAt
                ? Long.MAX_VALUE
                : tick - source.scanCompletedAt;
            geometryAge = source.geometry == null || tick < source.geometry.observedAt ? Long.MAX_VALUE
                : tick - source.geometry.observedAt;
            int mask = source.geometry == null ? GEOMETRY_MISSING : !source.geometry.complete ? GEOMETRY_UNKNOWN : 0;
            if (source.geometry != null && !source.geometry.fresh(tick)) mask |= GEOMETRY_STALE;
            if (source.weapons.size() != 7) mask |= WEAPONS_PARTIAL;
            if (!source.scanComplete) mask |= SCAN_INCOMPLETE;
            if (!source.coverageFresh()) mask |= SCAN_STALE;
            if (!friendsComplete) mask |= FRIENDS_INCOMPLETE;
            if (captured && !source.selectedCurrent) mask |= TARGET_INCOMPLETE;
            if (!source.projectileComplete) mask |= PROJECTILE_INCOMPLETE;
            if (!source.observationComplete) mask |= OBSERVATION_INCOMPLETE;
            if (!source.deferred.isEmpty() || source.urgentPending || source.scanPending) mask |= OBSERVATION_DENIED;
            if (!captured) mask |= NO_CAPTURE;
            blockedMask = mask;
            chunkCursor = source.chunkCursor;
            sectionCursor = source.sectionCursor;
            entityCursor = source.entityCursor;
            chunksVisited = source.chunksVisitedThisTick;
            deferredEntities = source.deferred.size();
            targetCount = source.targets.size();
            int visible = 0;
            for (Tracked tracked : source.targets.values())
                if (tracked.visible && tracked.observedAt == tick) visible++;
            visibleTargets = visible;
            primaryRays = source.primaryRays;
            backgroundRays = source.backgroundRays;
            secondaryRays = source.secondaryRays;
            gravityQueries = source.gravityQueries;
            gravityReuses = source.gravityReuses;
            weaponCount = source.weapons.size();
            unknownGeometryCells = source.geometryUnknownCells;
            budgetGeometryCells = source.geometryBudgetCells;
            used = quota.counts();
            observationDenials = quota.observationDenials();
            denials = quota.denials();
        }
    }

    private SampleResult rejectObservation() {
        observationComplete = false;
        return SampleResult.BUDGET_REJECTED;
    }

    public List<EntityLivingBase> boundedLiving() {
        List<EntityLivingBase> result = new ArrayList<>();
        for (LivingTrack tracked : living.values()) result.add(tracked.entity);
        return Collections.unmodifiableList(result);
    }

    /** 返回有界真实引用只供根仲裁，不能进入纯规划器 */
    public List<EntityLivingBase> targets() {
        List<EntityLivingBase> result = new ArrayList<>();
        for (Tracked tracked : targets.values()) if (tracked.position != null) result.add(tracked.entity);
        return Collections.unmodifiableList(result);
    }

    public Vec lastSeen(UUID id) {
        Tracked tracked = targets.get(id);
        return tracked == null ? null : tracked.position;
    }

    public boolean visibleLiving(UUID id) {
        LivingTrack tracked = living.get(id);
        return tracked != null && tracked.at == tick;
    }

    public boolean visible(UUID id) {
        Tracked tracked = targets.get(id);
        return tracked != null && tracked.visible && tracked.observedAt == tick;
    }

    public long observedAt(UUID id) {
        Tracked tracked = targets.get(id);
        return tracked == null ? Long.MIN_VALUE : tracked.observedAt;
    }

    public void acceptedHit(UUID target, long now) {
        if (ownHits.size() >= TARGETS && !ownHits.containsKey(target)) ownHits.remove(
            ownHits.keySet()
                .iterator()
                .next());
        ownHits.put(target, now);
        Tracked tracked = targets.get(target);
        if (tracked != null) tracked.health = Math.max(1, tracked.health - 4);
    }

    public void clear() {
        fullService.clear();
        observationGravity = null;
        gravityQueries = gravityReuses = 0;
        epoch++;
        targets.clear();
        living.clear();
        arrows.clear();
        friends.clear();
        friendTracks.clear();
        friendOverflow = friendIdentityGap = false;
        completedCoverageOldest = Long.MIN_VALUE;
        preparationRound = 0;
        coverageRound++;
        ownHits.clear();
        geometry = null;
        scanChunk = null;
        deferred.clear();
        sampled.clear();
        lastReadiness = null;
        weapons = Collections.emptyList();
        ammunition = 0;
        tick = Long.MIN_VALUE;
        centerX = Integer.MIN_VALUE;
        scanComplete = false;
        coverageScanning = false;
        scanStartedAt = Long.MIN_VALUE;
        scanCompletedAt = Long.MIN_VALUE;
        completedScanTicks = Long.MAX_VALUE;
        refreshCursor = 0;
        arrowEventCursor = 0;
    }

    public boolean scanComplete() {
        return scanComplete;
    }

    public static String fingerprint(ItemStack stack) {
        if (stack == null) return "empty";
        return Item.getIdFromItem(stack.getItem()) + ":"
            + stack.getItemDamage()
            + ":"
            + stack.stackSize
            + ":"
            + (stack.stackTagCompound == null ? "" : stack.stackTagCompound.toString());
    }

    private static Vec position(Entity entity) {
        return new Vec(entity.posX, entity.boundingBox.minY, entity.posZ);
    }

    private static boolean current(EntityMaid maid, Entity entity) {
        return entity != null && !entity.isDead
            && entity.worldObj == maid.worldObj
            && entity.dimension == maid.dimension
            && maid.worldObj.getEntityByID(entity.getEntityId()) == entity;
    }

    private static boolean legalVisible(EntityMaid maid, EntityLivingBase target, Vec observed) {
        EntityLivingBase owner = maid.getOwner();
        if (!target.isEntityAlive() || target == owner
            || target.isEntityInvulnerable()
            || maid.isOnSameTeam(target)
            || owner != null && owner.isOnSameTeam(target)
            || !ATTACK.canAttack(maid, target)) return false;
        var home = maid.getHomeArea();
        if (home.enabled()) return home.sameDimension() && home.containsMaid()
            && home.activity() == com.tianluoqaq.tlmnh.common.life.MaidLifeRules.Activity.WORK
            && home.contains(observed.x, observed.y, observed.z);
        return owner != null && owner.getDistanceSq(observed.x, observed.y, observed.z) <= 256;
    }

    private static boolean friendly(EntityMaid maid, EntityLivingBase entity) {
        if (entity == maid.getOwner() || maid.isOnSameTeam(entity)) return true;
        return entity instanceof EntityMaid other
            && java.util.Objects.equals(maid.func_152113_b(), other.func_152113_b());
    }

    private static final class FriendTrack {

        final EntityLivingBase entity;
        final UUID id;
        final int entityId, dimension;
        final Vec position;
        final double halfWidth, height;
        final long at, round;
        boolean current = true;

        FriendTrack(EntityLivingBase entity, Vec position, long at, long round) {
            this.entity = entity;
            id = entity.getUniqueID();
            entityId = entity.getEntityId();
            dimension = entity.dimension;
            this.position = position;
            halfWidth = entity.width * .5;
            height = entity.height;
            this.at = at;
            this.round = round;
        }

        Friend pure() {
            return new Friend(id, entityId, dimension, at, round, current, position, halfWidth, height);
        }
    }

    private static final class LivingTrack {

        final EntityLivingBase entity;
        final long at;

        LivingTrack(EntityLivingBase entity, long at) {
            this.entity = entity;
            this.at = at;
        }
    }

    private static final class Tracked {

        final EntityLivingBase entity;
        Vec position, velocity = new Vec(0, 0, 0);
        long observedAt = Long.MIN_VALUE;
        float health;
        double width, height;
        boolean visible, swinging, ranged, explosive, attackingOwner;

        Tracked(EntityLivingBase entity) {
            this.entity = entity;
        }
    }

    private static final class ProjectileTrack {

        final EntityArrow entity;
        Vec position, velocity;
        long at;
        double gravity = (double) .05F;

        ProjectileTrack(EntityArrow entity) {
            this.entity = entity;
        }
    }
}
