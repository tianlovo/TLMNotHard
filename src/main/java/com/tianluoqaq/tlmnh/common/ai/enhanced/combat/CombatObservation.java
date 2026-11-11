package com.tianluoqaq.tlmnh.common.ai.enhanced.combat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.world.chunk.Chunk;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskAttack;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatBudgetTuning;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatDiagnosticScope;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatFullServiceReceipt;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatGeometryWindow;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatRangePolicy;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatWorkQuota;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatWorkQuota.Work;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.budget.CombatWorldBudget;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatMovementFact;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot.DoorCell;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot.Friend;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot.Geometry;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot.Projectile;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot.Target;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot.Vec;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSnapshot.Weapon;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatSpace;
import com.tianluoqaq.tlmnh.common.ai.enhanced.combat.terrain.CombatTerrainTuning;
import com.tianluoqaq.tlmnh.common.combat.MaidCombatAttributes;
import com.tianluoqaq.tlmnh.common.combat.spi.ProjectilePhysicsProvider;
import com.tianluoqaq.tlmnh.common.combat.weapon.MaidWeaponCatalog;
import com.tianluoqaq.tlmnh.common.compat.CompatServices;
import com.tianluoqaq.tlmnh.common.config.MaidCombatConfig;
import com.tianluoqaq.tlmnh.common.config.MaidPerceptionConfig;
import com.tianluoqaq.tlmnh.common.life.MaidLifeRules;

/** 唯一世界观测边界；区块实体列表增量游标，不先执行全量AABB查询 */
public final class CombatObservation {

    private final CombatRangePolicy rangePolicy = new CombatRangePolicy();

    private static final TaskAttack ATTACK = new TaskAttack();

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
    private Chunk scanChunk;
    private final Map<UUID, Entity> deferred = new LinkedHashMap<>();
    private final Map<UUID, SampleResult> sampled = new LinkedHashMap<>();

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

    private ProjectilePhysicsProvider.ArrowLaunchSample observationGravity;
    private int gravityQueries, gravityReuses;

    private Geometry geometry;
    private List<Weapon> weapons = Collections.emptyList();
    private int ammunition;

    public void observe(EntityMaid maid, CombatWorkQuota quota) {
        long now = maid.worldObj.getTotalWorldTime();
        int stage = quota.full ? EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_PARAM
            : quota.preparing ? EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_UNIT_STEP
                : EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_ZERO_BASE;
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
                if (weapons.size() < EnhancedCombatTuning.MIN_SIZE_THRESHOLD) captureWeapons(maid, quota);
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
        var maidThreat = maid.getAITarget();
        var threatOwner = maid.getOwner();
        var ownerThreat = threatOwner == null ? null : threatOwner.getAITarget();
        rangePolicy.tick(
            now,
            maidThreat == null ? null : maidThreat.getUniqueID(),
            ownerThreat == null ? null : ownerThreat.getUniqueID());
        observationGravity = null;
        gravityQueries = gravityReuses = EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_ZERO_BASE;
        observedFull = startedFull = quota.full;
        observedStage = stage;
        permanentObservationGap = permanentProjectileGap = urgentPending = scanPending = false;
        chunksVisitedThisTick = EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_ZERO_BASE;
        primaryRays = backgroundRays = secondaryRays = friendRays = EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_ZERO_BASE;
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
                entry -> now - entry.getValue().at > CombatBudgetTuning.OBSERVATION_TTL
                    || !current(maid, entry.getValue().entity));
        captureWeapons(maid, quota);
        collectUrgent(maid, quota);
        for (Iterator<ProjectileTrack> iterator = arrows.values()
            .iterator(); iterator.hasNext();) {
            ProjectileTrack arrow = iterator.next();
            if (!current(maid, arrow.entity) || now - arrow.at > EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_PARAM)
                iterator.remove();
            else sampleArrow(maid, arrow.entity, quota);
        }
        for (Tracked tracked : new ArrayList<>(targets.values())) {
            if (!current(maid, tracked.entity) || now - tracked.observedAt > CombatTerrainTuning.HIDDEN_TTL) {
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
            .removeIf(
                entry -> now < entry.getValue()
                    || now - entry.getValue() > EnhancedCombatTuning.MAX_GET_VALUE_THRESHOLD);
    }

    private void beginPreparation(EntityMaid maid, CombatWorkQuota quota) {
        if (!quota.preparing) return;
        if (preparationRound != quota.preparationRound) {
            preparationRound = quota.preparationRound;
            preparationRestarted = false;
            // 原准备窗末次采样仍须在覆盖TTL内；不回收本tick已付费用或扫描计数
            if (!coverageFresh() && scanStartedAt != Long.MIN_VALUE
                && tick >= scanStartedAt
                && tick - scanStartedAt
                    > CombatBudgetTuning.OBSERVATION_TTL - (CombatBudgetTuning.PREPARATION_TICKS - 1)) {
                traceCoverage(maid, EnhancedCombatIds.KEY_PREPARATION_WINDOW_SCAN);
                startCoverage();
                preparationRestarted = true;
            }
        }
        if (!preparationRestarted && !coverageFresh()
            && scanStartedAt != Long.MIN_VALUE
            && tick >= scanStartedAt
            && tick - scanStartedAt > CombatBudgetTuning.OBSERVATION_TTL) {
            traceCoverage(maid, EnhancedCombatIds.KEY_PREPARATION_EXPIRED_SCAN);
            startCoverage();
            preparationRestarted = true;
        }
    }

    private void startCoverage() {
        scanChunk = null;
        chunkCursor = sectionCursor = entityCursor = EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_ZERO_BASE;
        scanComplete = false;
        coverageScanning = true;
        scanStartedAt = Long.MIN_VALUE;
        completedCoverageOldest = Long.MIN_VALUE;
        coverageRound++;
    }

    private boolean coverageFresh() {
        return completedCoverageOldest != Long.MIN_VALUE && tick >= completedCoverageOldest
            && tick - completedCoverageOldest <= CombatBudgetTuning.OBSERVATION_TTL;
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
            && tick - target.observedAt <= CombatTerrainTuning.HIDDEN_TTL) return true;
        return false;
    }

    public boolean preparationReady() {
        return preparationInputsReady() && hasCurrentVisibleTarget();
    }

    private boolean preparationInputsReady() {
        if (!scanComplete || !coverageFresh()
            || weapons.size() != EnhancedCombatTuning.MIN_SIZE_THRESHOLD
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
        if (!friendTracks.containsKey(id) && friendTracks.size() >= EnhancedCombatTuning.FRIENDS) {
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
            // 缓存键退役依据实体身份变化；目标不可见或年龄耗尽时保留其身份记录
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
        while (arrowEventCursor < latestArrow
            && acute++ < EnhancedCombatTuning.COMBAT_OBSERVATION_COLLECT_URGENT_PARAM) {
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

    /** 当前动作仅占一个目标刷新名额，避免单次动作耗尽背景扫描射线预算 */
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
            if (refreshCursor >= candidates.size())
                refreshCursor = EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_ZERO_BASE;
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
        int cx = MathHelper.floor_double(maid.posX) >> EnhancedCombatTuning.CHUNK_SECTION_SHIFT,
            cz = MathHelper.floor_double(maid.posZ) >> EnhancedCombatTuning.CHUNK_SECTION_SHIFT;
        if (cx != centerX || cz != centerZ) {
            scopeResetThisTick = true;
            centerX = cx;
            centerZ = cz;
            traceCoverage(maid, EnhancedCombatIds.KEY_CHUNK_SCOPE_CHANGED);
            startCoverage();
        }
        // 覆盖等待/消费FULL时不启动新轮，也不事后清除既有必要采集缺口
        if (scanComplete && coverageFresh()) return;
        if (!coverageScanning) {
            traceCoverage(maid, EnhancedCombatIds.KEY_NEXT_COVERAGE_SCAN);
            startCoverage();
        }
        scanPending = false;
        // 同tick升级只续做被预算挡住的实际游标，不重开四个chunk的额外扫描
        int chunkLimit = quota.preparing ? EnhancedCombatTuning.PREPARATION_CHUNKS_PER_TICK
            : EnhancedCombatTuning.SCAN_CHUNKS_PER_TICK;
        while (scanChunk != null || chunksVisitedThisTick < chunkLimit) {
            if (scanChunk == null && !quota.spendObservation(Work.CHUNK)) {
                scanPending = true;
                rejectObservation();
                return;
            }
            int offset = chunkCursor == 0 ? EnhancedCombatTuning.COMBAT_OBSERVATION_SCAN_LOADED_NOW_PARAM
                : chunkCursor <= EnhancedCombatTuning.COMBAT_OBSERVATION_SCAN_LOADED_NOW_PARAM ? chunkCursor - 1
                    : chunkCursor;
            int x = cx + offset % EnhancedCombatTuning.COMBAT_OBSERVATION_SCAN_LOADED_NOW_PARAM_2
                - EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_PARAM,
                z = cz + offset / EnhancedCombatTuning.COMBAT_OBSERVATION_SCAN_LOADED_NOW_PARAM_2
                    - EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_PARAM;
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
                int section = (MathHelper.floor_double(maid.posY) / EnhancedCombatTuning.COMBAT_OBSERVATION_SECTION
                    + sectionCursor) % chunk.entityLists.length;
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
                entityCursor = EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_ZERO_BASE;
            }
            nextChunk();
            if (chunkCursor == 0) return;
        }
    }

    private void nextChunk() {
        scanChunk = null;
        sectionCursor = entityCursor = EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_ZERO_BASE;
        if (++chunkCursor >= EnhancedCombatTuning.CHUNK_COUNT) {
            chunkCursor = EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_ZERO_BASE;
            completedScanTicks = scanStartedAt != Long.MIN_VALUE && tick >= scanStartedAt ? tick - scanStartedAt
                : Long.MAX_VALUE;
            scanComplete = completedScanTicks <= CombatBudgetTuning.OBSERVATION_TTL;
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
        // 可见友方按完整感知范围采样，保持友方覆盖度与真实可见性一致
        if (!maid.getHomeArea()
            .enabled() && !friendly(maid, entity))
            range = rangePolicy.detection(
                tick,
                maid.getUniqueID(),
                entity.getUniqueID(),
                range,
                MaidCombatConfig.ownerCombatAcquireRange,
                MaidCombatConfig.ownerCombatRetainRange);
        double detection = entity.isSneaking() ? EnhancedCombatTuning.COMBAT_OBSERVATION_SAMPLE_LIVING_NOW_FACTOR
            : EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_UNIT_STEP;
        if (entity.isInvisible()) {
            if (!quota.spendObservation(Work.ENTITY, EnhancedCombatTuning.CHUNK_SECTION_SHIFT))
                return rejectObservation();
            int armor = 0;
            for (int slot = 1; slot <= EnhancedCombatTuning.CHUNK_SECTION_SHIFT; slot++) {
                ItemStack item = entity.getEquipmentInSlot(slot);
                if (item != null && item.stackSize > 0) armor++;
            }
            detection *= EnhancedCombatTuning.COMBAT_OBSERVATION_SAMPLE_LIVING_NOW_FACTOR_2 * Math.max(
                EnhancedCombatTuning.COMBAT_OBSERVATION_MAX_FIRST_FACTOR,
                armor / EnhancedCombatTuning.COMBAT_OBSERVATION_MAX_SECOND_FACTOR);
        }
        if (position.distance(position(maid))
            > Math.max(EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_PARAM, range * detection)) {
            if (entity != maid.getOwner()) friendTracks.remove(entity.getUniqueID());
            return SampleResult.OUTSIDE_RANGE;
        }
        if (living.size() >= EnhancedCombatTuning.MAX_SIZE_THRESHOLD && !living.containsKey(entity.getUniqueID()))
            living.remove(
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
        rangePolicy.seen(tick);
        UUID id = entity.getUniqueID();
        friendTracks.remove(id);
        Tracked tracked = targets.get(id);
        if (tracked == null) {
            if (targets.size() >= EnhancedCombatTuning.TARGETS) {
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
        Vec observedVelocity = tracked.position != null && elapsed > 0
            && elapsed <= EnhancedCombatTuning.COMBAT_OBSERVATION_COLLECT_URGENT_PARAM
                ? position.subtract(tracked.position)
                    .scale(EnhancedCombatTuning.UNIT_AXIS_SCALE_D / elapsed)
                : new Vec(
                    EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_ZERO_BASE,
                    EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_ZERO_BASE,
                    EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_ZERO_BASE);
        if (observedVelocity.length() > EnhancedCombatTuning.COMBAT_OBSERVATION_SAMPLE_LIVING_NOW_FACTOR)
            observedVelocity = observedVelocity
                .scale(EnhancedCombatTuning.COMBAT_OBSERVATION_SAMPLE_LIVING_NOW_FACTOR / observedVelocity.length());
        tracked.velocity = observedVelocity;
        tracked.position = position;
        tracked.observedAt = tick;
        tracked.visible = true;
        // 外部生物血量依据基础属性范围与既有命中记录估算
        if (tracked.health <= 0)
            tracked.health = entity instanceof IMob ? EnhancedCombatTuning.COMBAT_OBSERVATION_SAMPLE_LIVING_NOW_PARAM
                : EnhancedCombatTuning.COMBAT_OBSERVATION_SAMPLE_LIVING_NOW_PARAM_2;
        tracked.width = entity.width * EnhancedCombatTuning.COMBAT_OBSERVATION_WIDTH;
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
        if (position.distance(position(maid)) > EnhancedCombatTuning.COMBAT_OBSERVATION_SAMPLE_ARROW_NOW_PARAM
            || arrow.motionX * arrow.motionX + arrow.motionY * arrow.motionY + arrow.motionZ * arrow.motionZ
                < EnhancedCombatTuning.MIN_MOTION_Z_THRESHOLD)
            return SampleResult.HIDDEN;
        UUID id = arrow.getUniqueID();
        ProjectileTrack tracked = arrows.get(id);
        if (tracked == null) {
            if (arrows.size() >= EnhancedCombatTuning.PROJECTILES) {
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
            gravity = observationGravity.gravity();
            gravityReuses++;
        } else {
            observationGravity = null;
            if (!quota.spend(Work.BALLISTIC)) {
                projectileComplete = false;
                return rejectObservation();
            }
            gravityQueries++;
            var sample = CompatServices.projectilePhysics()
                .sampleArrowLaunch(arrow);
            if (sample != null && sample.isCurrent(arrow)) {
                gravity = sample.gravity();
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
            owner == null ? EnhancedCombatTuning.COMBAT_OBSERVATION_SAMPLE_LIVING_NOW_PARAM : owner.getHealth(),
            maid.width * EnhancedCombatTuning.COMBAT_OBSERVATION_WIDTH,
            maid.height,
            MaidCombatAttributes.entityReach(maid) + maid.getFavorabilityManager()
                .getAttackDistancePlusByPoint(maid.getFavorability()),
            EnhancedCombatTuning.COMBAT_OBSERVATION_DOMAIN_FACTOR,
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
            attackSpeed > 0
                ? Math.max(
                    EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_UNIT_STEP,
                    (int) Math.ceil(EnhancedCombatTuning.COMBAT_OBSERVATION_SAMPLE_LIVING_NOW_PARAM / attackSpeed))
                : EnhancedCombatTuning.COMBAT_OBSERVATION_SAMPLE_LIVING_NOW_PARAM,
            cadenceRemaining,
            drawTicks,
            ammunition,
            preferBow,
            preferencePending,
            maid.hurtTime > 0,
            owner == null ? EnhancedCombatTuning.COMBAT_OBSERVATION_DOMAIN_FACTOR_2
                : owner.width * EnhancedCombatTuning.COMBAT_OBSERVATION_WIDTH,
            owner == null ? EnhancedCombatTuning.COMBAT_OBSERVATION_DOMAIN_FACTOR_3 : owner.height);
        List<Target> pureTargets = new ArrayList<>();
        for (Tracked target : targets.values()) {
            if (target.position == null || tick - target.observedAt > CombatTerrainTuning.HIDDEN_TTL) continue;
            pureTargets.add(
                new Target(
                    target.entity.getUniqueID(),
                    target.entity.getEntityId(),
                    target.position,
                    target.visible ? target.velocity
                        : new Vec(
                            EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_ZERO_BASE,
                            EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_ZERO_BASE,
                            EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_ZERO_BASE),
                    target.observedAt,
                    ownHits.getOrDefault(target.entity.getUniqueID(), Long.MIN_VALUE),
                    target.health,
                    target.explosive ? EnhancedCombatTuning.COMBAT_OBSERVATION_SCAN_LOADED_NOW_PARAM
                        : target.ranged ? EnhancedCombatTuning.COMBAT_OBSERVATION_SCAN_LOADED_NOW_PARAM_2
                            : EnhancedCombatTuning.CHUNK_SECTION_SHIFT,
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
            if (arrow.position != null && tick - arrow.at <= EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_PARAM)
                pureArrows.add(
                    new Projectile(
                        arrow.entity.getUniqueID(),
                        arrow.position,
                        arrow.velocity,
                        arrow.at,
                        arrow.gravity,
                        (double) EnhancedCombatTuning.COMBAT_OBSERVATION_DOMAIN_FACTOR_4,
                        EnhancedCombatTuning.COMBAT_OBSERVATION_DOMAIN_FACTOR_2,
                        EnhancedCombatTuning.COMBAT_OBSERVATION_DOMAIN_PARAM,
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
                && weapons.size() == EnhancedCombatTuning.MIN_SIZE_THRESHOLD
                && scanComplete
                && projectileComplete
                && observationComplete
                && selectedCurrent
                && coverageFresh(),
            friendsComplete(),
            completedCoverageOldest,
            movementFact,
            geometry != null && geometry.complete
                && weapons.size() == EnhancedCombatTuning.MIN_SIZE_THRESHOLD
                && scanComplete
                && projectileComplete
                && observationComplete
                && coverageFresh(),
            !friendOverflow && !friendIdentityGap);
        if (fullService.attempted(tick)) fullService.captured(tick, result.bodyComplete, false);
        return result;
    }

    private void captureWeapons(EntityMaid maid, CombatWorkQuota quota) {
        List<Weapon> captured = new ArrayList<>();
        ammunition = EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_ZERO_BASE;
        var inventory = maid.getMaidInventory();
        for (int slot = EnhancedCombatTuning.UNSET_OR_NOT_FOUND; slot
            < EnhancedCombatTuning.COMBAT_OBSERVATION_DOMAIN_PARAM; slot++) {
            if (slot >= 0 && !quota.spend(Work.ITEM)) break;
            ItemStack stack = slot == EnhancedCombatTuning.UNSET_OR_NOT_FOUND ? maid.getHeldItem()
                : inventory.getStackInSlot(slot);
            captured.add(
                new Weapon(
                    slot,
                    fingerprint(stack),
                    slot == EnhancedCombatTuning.UNSET_OR_NOT_FOUND ? maid.getCombatWeaponProfile()
                        : MaidWeaponCatalog.inspect(stack)));
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

    public boolean supportedMelee() {
        for (Weapon weapon : weapons) if (weapon.profile.melee()) return true;
        return false;
    }

    public long epoch() {
        return epoch;
    }

    private Geometry collectGeometry(EntityMaid maid, Tracked selected, CombatWorkQuota quota) {
        boolean currentVisible = selected != null && selected.visible
            && selected.observedAt == tick
            && selected.position != null;
        int ox = CombatGeometryWindow.horizontalOrigin(
            maid.posX,
            currentVisible ? selected.position.x : EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_ZERO_BASE,
            currentVisible,
            maid.width * EnhancedCombatTuning.COMBAT_OBSERVATION_WIDTH),
            oy = MathHelper.floor_double(maid.boundingBox.minY) - CombatBudgetTuning.FLOOR_DEPTH,
            oz = CombatGeometryWindow.horizontalOrigin(
                maid.posZ,
                currentVisible ? selected.position.z : EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_ZERO_BASE,
                currentVisible,
                maid.width * EnhancedCombatTuning.COMBAT_OBSERVATION_WIDTH);
        byte[] cells = new byte[CombatBudgetTuning.WIDTH * CombatBudgetTuning.HEIGHT * CombatBudgetTuning.WIDTH];
        double[] bounds = new double[cells.length * EnhancedCombatTuning.COMBAT_OBSERVATION_DOMAIN_PARAM];
        DoorCell[] doorCells = new DoorCell[cells.length];
        CombatSpace.BlockReader reader = CombatSpace.reader(
            maid,
            ox,
            oy,
            oz,
            ox + CombatBudgetTuning.WIDTH - 1,
            oy + CombatBudgetTuning.HEIGHT - 1,
            oz + CombatBudgetTuning.WIDTH - 1,
            quota);
        boolean complete = reader.result == CombatSpace.Result.SAFE;
        geometryUnknownCells = geometryBudgetCells = EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_ZERO_BASE;
        for (int y = 0; y < CombatBudgetTuning.HEIGHT; y++)
            for (int z = 0; z < CombatBudgetTuning.WIDTH; z++) for (int x = 0; x < CombatBudgetTuning.WIDTH; x++) {
                int index = (y * CombatBudgetTuning.WIDTH + z) * CombatBudgetTuning.WIDTH + x, px = ox + x, py = oy + y,
                    pz = oz + z;
                var mask = AxisAlignedBB.getBoundingBox(
                    px - EnhancedCombatTuning.COMBAT_OBSERVATION_MASK,
                    py - EnhancedCombatTuning.COMBAT_OBSERVATION_MASK,
                    pz - EnhancedCombatTuning.COMBAT_OBSERVATION_MASK,
                    px + EnhancedCombatTuning.COMBAT_OBSERVATION_MASK_2,
                    py + EnhancedCombatTuning.COMBAT_OBSERVATION_MASK_3,
                    pz + EnhancedCombatTuning.COMBAT_OBSERVATION_MASK_2);
                CombatSpace.BlockShapes shapes = reader.block(px, py, pz, mask);
                if (shapes.result != CombatSpace.Result.SAFE) {
                    cells[index] = CombatTerrainTuning.UNKNOWN;
                    if (shapes.result == CombatSpace.Result.BUDGET) geometryBudgetCells++;
                    else geometryUnknownCells++;
                    complete = false;
                    continue;
                }
                cells[index] = (byte) shapes.flags;
                doorCells[index] = shapes.doorCell;
                if (shapes.count == 0) continue;
                cells[index] |= CombatTerrainTuning.SOLID;
                int offset = index * EnhancedCombatTuning.COMBAT_OBSERVATION_DOMAIN_PARAM;
                bounds[offset] = shapes.minX;
                bounds[offset + 1] = shapes.minY;
                bounds[offset + EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_PARAM] = shapes.minZ;
                bounds[offset + EnhancedCombatTuning.COMBAT_OBSERVATION_COLLECT_GEOMETRY_PARAM] = shapes.maxX;
                bounds[offset + EnhancedCombatTuning.CHUNK_SECTION_SHIFT] = shapes.maxY;
                bounds[offset + EnhancedCombatTuning.COMBAT_OBSERVATION_SCAN_LOADED_NOW_PARAM_2] = shapes.maxZ;
            }
        return new Geometry(
            ox,
            oy,
            oz,
            CombatBudgetTuning.WIDTH,
            CombatBudgetTuning.HEIGHT,
            CombatBudgetTuning.WIDTH,
            tick,
            complete,
            cells,
            bounds,
            doorCells);
    }

    private SampleResult remember(Entity entity, SampleResult result) {
        UUID id = entity.getUniqueID();
        if (result == SampleResult.BUDGET_REJECTED) {
            if (deferred.size() < EnhancedCombatTuning.MAX_DEFERRED || deferred.containsKey(id))
                deferred.put(id, entity);
            else permanentObservationGap = true;
        } else {
            deferred.remove(id);
            if (sampled.size() < EnhancedCombatTuning.MAX_SAMPLED || sampled.containsKey(id)) sampled.put(id, result);
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

    /** 完全被动的最后一次capture诊断，不续期、不扫描、不申请配额 */
    private static final class DiagnosticHistory {

        final CombatDiagnosticScope.Log stages = new CombatDiagnosticScope.Log(
            EnhancedCombatTuning.CHUNK_SECTION_SHIFT);
        String startReason = EnhancedCombatIds.KEY_NOT_RECORDED;
        long[] start;
    }

    private DiagnosticHistory history(CombatDiagnosticScope scope) {
        var value = (DiagnosticHistory) scope.get(this);
        if (value == null) {
            value = new DiagnosticHistory();
            scope.put(this, value);
        }
        return value;
    }

    private void traceCoverage(EntityMaid maid, String reason) {
        var scope = CombatDiagnosticScope.current(maid.worldObj);
        if (scope == null) return;
        var h = history(scope);
        h.startReason = reason;
        h.start = new long[] { tick, coverageRound, scanStartedAt, scanCompletedAt, chunkCursor, sectionCursor,
            entityCursor, chunksVisitedThisTick, preparationRound,
            preparationRestarted ? EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_UNIT_STEP
                : EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_ZERO_BASE };
    }

    private void recordReadiness(EntityMaid maid, CombatWorkQuota quota, boolean captured) {
        lastReadiness = new Readiness(this, quota, captured);
        var scope = CombatDiagnosticScope.current(maid.worldObj);
        if (scope == null) return;
        var h = history(scope);
        String kind = captured ? EnhancedCombatIds.KEY_CAPTURE
            : quota.full ? EnhancedCombatIds.KEY_FULL
                : quota.preparing ? EnhancedCombatIds.KEY_PREPARING : EnhancedCombatIds.KEY_LIGHT;
        if (!h.stages.shouldCapture(tick, kind)) return;
        var r = lastReadiness;
        long[] v = new long[EnhancedCombatTuning.COMBAT_OBSERVATION_SAMPLE_LIVING_NOW_PARAM_2
            + r.used.length * EnhancedCombatTuning.COMBAT_OBSERVATION_COLLECT_GEOMETRY_PARAM];
        long[] head = { scanStartedAt, scanCompletedAt,
            bodyPreparationReady() ? EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_UNIT_STEP
                : EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_ZERO_BASE,
            preparationRestarted ? EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_UNIT_STEP
                : EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_ZERO_BASE,
            r.preparationRound, r.coverageRound, r.oldestCoverage, r.blockedMask, r.chunkCursor, r.sectionCursor,
            r.entityCursor, r.chunksVisited, r.deferredEntities, r.scanCycleTicks, r.completedScanTicks,
            r.completedScanAge, r.primaryRays, r.backgroundRays, r.secondaryRays, r.friendCount, r.friendRays,
            r.scanPending ? EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_UNIT_STEP
                : EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_ZERO_BASE,
            r.urgentPending ? EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_UNIT_STEP
                : EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_ZERO_BASE,
            r.permanentObservationGap ? EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_UNIT_STEP
                : EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_ZERO_BASE,
            r.permanentProjectileGap ? EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_UNIT_STEP
                : EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_ZERO_BASE,
            r.scopeReset ? EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_UNIT_STEP
                : EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_ZERO_BASE,
            r.preparing ? EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_UNIT_STEP
                : EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_ZERO_BASE,
            r.fullAtCapture ? EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_UNIT_STEP
                : EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_ZERO_BASE,
            r.preparationReady ? EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_UNIT_STEP
                : EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_ZERO_BASE,
            captured ? EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_UNIT_STEP
                : EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_ZERO_BASE };
        System.arraycopy(
            head,
            EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_ZERO_BASE,
            v,
            EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_ZERO_BASE,
            head.length);
        for (int i = 0; i < r.used.length; i++) {
            v[EnhancedCombatTuning.COMBAT_OBSERVATION_SAMPLE_LIVING_NOW_PARAM_2 + i] = r.used[i];
            v[EnhancedCombatTuning.COMBAT_OBSERVATION_SAMPLE_LIVING_NOW_PARAM_2 + r.used.length
                + i] = r.observationDenials[i];
            v[EnhancedCombatTuning.COMBAT_OBSERVATION_SAMPLE_LIVING_NOW_PARAM_2
                + r.used.length * EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_PARAM
                + i] = r.denials[i];
        }
        h.stages.addCaptured(tick, kind, EnhancedCombatIds.ID_HEAD30_LOCALUSED15_OBSERVATIONDENIED15_LOCALDENIE, v);
    }

    public ObservationDiagnostic diagnostic(Object world) {
        var scope = CombatDiagnosticScope.current(world);
        if (scope == null) return null;
        var cached = (DiagnosticHistory) scope.get(this);
        return cached == null ? null
            : new ObservationDiagnostic(
                cached.stages.snapshot(),
                cached.startReason,
                cached.start == null ? null : cached.start.clone());
    }

    public static final class ObservationDiagnostic {

        public final CombatDiagnosticScope.Frame stages;
        public final String lastStartReason;
        public final long[] lastStartValues;

        ObservationDiagnostic(CombatDiagnosticScope.Frame stages, String reason, long[] values) {
            this.stages = stages;
            lastStartReason = reason;
            lastStartValues = values;
        }
    }

    public Readiness readiness() {
        return lastReadiness;
    }

    /** 仅按身份复制本tick已有缓存，诊断不补采、不读实体或世界、不续期 */
    public SampleDiagnostic sampleDiagnostic(UUID id) {
        SampleResult result = sampled.get(id);
        FriendTrack friend = friendTracks.get(id);
        Tracked target = targets.get(id);
        LivingTrack seen = living.get(id);
        return new SampleDiagnostic(
            tick,
            id,
            result == null ? EnhancedCombatIds.KEY_NOT_SAMPLED : result.name(),
            deferred.containsKey(id),
            friend != null,
            friend == null ? Long.MIN_VALUE : friend.at,
            friend != null && friend.current && friend.at == tick,
            target != null,
            target == null ? Long.MIN_VALUE : target.observedAt,
            target != null && target.visible && target.observedAt == tick,
            seen != null,
            seen == null ? Long.MIN_VALUE : seen.at,
            friendOverflow);
    }

    public static final class SampleDiagnostic {

        public final long tick, friendObservedAt, targetObservedAt, livingObservedAt;
        public final UUID id;
        public final String result;
        public final boolean deferred, friendTracked, friendCurrent, targetTracked, targetCurrent, livingTracked,
            friendOverflow;

        private SampleDiagnostic(long tick, UUID id, String result, boolean deferred, boolean friendTracked,
            long friendObservedAt, boolean friendCurrent, boolean targetTracked, long targetObservedAt,
            boolean targetCurrent, boolean livingTracked, long livingObservedAt, boolean friendOverflow) {
            this.tick = tick;
            this.id = id;
            this.result = result;
            this.deferred = deferred;
            this.friendTracked = friendTracked;
            this.friendObservedAt = friendObservedAt;
            this.friendCurrent = friendCurrent;
            this.targetTracked = targetTracked;
            this.targetObservedAt = targetObservedAt;
            this.targetCurrent = targetCurrent;
            this.livingTracked = livingTracked;
            this.livingObservedAt = livingObservedAt;
            this.friendOverflow = friendOverflow;
        }
    }

    public static final class Readiness {

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
            int mask = source.geometry == null ? EnhancedCombatTuning.GEOMETRY_MISSING
                : !source.geometry.complete ? EnhancedCombatTuning.GEOMETRY_UNKNOWN
                    : EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_ZERO_BASE;
            if (source.geometry != null && !source.geometry.fresh(tick)) mask |= EnhancedCombatTuning.GEOMETRY_STALE;
            if (source.weapons.size() != EnhancedCombatTuning.MIN_SIZE_THRESHOLD)
                mask |= EnhancedCombatTuning.WEAPONS_PARTIAL;
            if (!source.scanComplete) mask |= EnhancedCombatTuning.SCAN_INCOMPLETE;
            if (!source.coverageFresh()) mask |= EnhancedCombatTuning.SCAN_STALE;
            if (!friendsComplete) mask |= EnhancedCombatTuning.FRIENDS_INCOMPLETE;
            if (captured && !source.selectedCurrent) mask |= EnhancedCombatTuning.TARGET_INCOMPLETE;
            if (!source.projectileComplete) mask |= EnhancedCombatTuning.PROJECTILE_INCOMPLETE;
            if (!source.observationComplete) mask |= EnhancedCombatTuning.OBSERVATION_INCOMPLETE;
            if (!source.deferred.isEmpty() || source.urgentPending || source.scanPending)
                mask |= EnhancedCombatTuning.OBSERVATION_DENIED;
            if (!captured) mask |= EnhancedCombatTuning.NO_CAPTURE;
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

    /** 返回有界真实引用供根仲裁使用，规划器层保持数据解耦 */
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
        if (ownHits.size() >= EnhancedCombatTuning.TARGETS && !ownHits.containsKey(target)) ownHits.remove(
            ownHits.keySet()
                .iterator()
                .next());
        ownHits.put(target, now);
        Tracked tracked = targets.get(target);
        if (tracked != null) tracked.health = Math.max(
            EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_UNIT_STEP,
            tracked.health - EnhancedCombatTuning.CHUNK_SECTION_SHIFT);
    }

    public void clear() {
        rangePolicy.clear();
        fullService.clear();
        observationGravity = null;
        gravityQueries = gravityReuses = EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_ZERO_BASE;
        epoch++;
        targets.clear();
        living.clear();
        arrows.clear();
        friends.clear();
        friendTracks.clear();
        friendOverflow = friendIdentityGap = false;
        completedCoverageOldest = Long.MIN_VALUE;
        preparationRound = EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_ZERO_BASE;
        coverageRound++;
        ownHits.clear();
        geometry = null;
        scanChunk = null;
        deferred.clear();
        sampled.clear();
        lastReadiness = null;
        weapons = Collections.emptyList();
        ammunition = EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_ZERO_BASE;
        tick = Long.MIN_VALUE;
        centerX = Integer.MIN_VALUE;
        scanComplete = false;
        coverageScanning = false;
        scanStartedAt = Long.MIN_VALUE;
        scanCompletedAt = Long.MIN_VALUE;
        completedScanTicks = Long.MAX_VALUE;
        refreshCursor = EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_ZERO_BASE;
        arrowEventCursor = EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_ZERO_BASE;
    }

    public boolean scanComplete() {
        return scanComplete;
    }

    public static String fingerprint(ItemStack stack) {
        if (stack == null) return EnhancedCombatIds.ID_EMPTY;
        return Item.getIdFromItem(stack.getItem()) + EnhancedCombatIds.COLON_DELIMITER
            + stack.getItemDamage()
            + EnhancedCombatIds.COLON_DELIMITER
            + stack.stackSize
            + EnhancedCombatIds.COLON_DELIMITER
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

    public CombatRangePolicy rangePolicy() {
        return rangePolicy;
    }

    /** 仅读取OBSERVATION_TTL新鲜窗内的非主人友方已观测坐标判断近战拥挤，不查询实体实时位置或消耗配额 */
    public boolean hasObservedFriendNear(EntityMaid maid, Vec targetSeen, double radiusSq) {
        if (tick == Long.MIN_VALUE || targetSeen == null || !(radiusSq > 0)) return false;
        EntityLivingBase owner = maid.getOwner();
        for (FriendTrack friend : friendTracks.values()) {
            if (friend.entity == maid || friend.entity == owner || friend.position == null) continue;
            if (tick < friend.at || tick - friend.at > CombatBudgetTuning.OBSERVATION_TTL) continue;
            double dx = friend.position.x - targetSeen.x, dy = friend.position.y - targetSeen.y,
                dz = friend.position.z - targetSeen.z;
            if (dx * dx + dy * dy + dz * dz <= radiusSq) return true;
        }
        return false;
    }

    public boolean targetInDomain(EntityMaid maid, UUID id, Vec observed) {
        if (observed == null) return false;
        var home = maid.getHomeArea();
        if (home.enabled()) return home.sameDimension() && home.contains(observed.x, observed.y, observed.z);
        var owner = maid.getOwner();
        return owner != null && rangePolicy.targetAllowed(
            id,
            owner.getDistanceSq(observed.x, observed.y, observed.z),
            maid.getDistanceSq(observed.x, observed.y, observed.z),
            MaidCombatConfig.ownerCombatAcquireRange,
            MaidCombatConfig.ownerCombatRetainRange);
    }

    private boolean legalVisible(EntityMaid maid, EntityLivingBase target, Vec observed) {
        EntityLivingBase owner = maid.getOwner();
        if (!target.isEntityAlive() || target == owner
            || target.isEntityInvulnerable()
            || maid.isOnSameTeam(target)
            || owner != null && owner.isOnSameTeam(target)
            || !ATTACK.canAttack(maid, target)) return false;
        var home = maid.getHomeArea();
        if (home.enabled()) return home.sameDimension() && home.containsMaid()
            && home.activity() == MaidLifeRules.Activity.WORK
            && home.contains(observed.x, observed.y, observed.z);
        return targetInDomain(maid, target.getUniqueID(), observed);
    }

    private static boolean friendly(EntityMaid maid, EntityLivingBase entity) {
        if (entity == maid.getOwner() || maid.isOnSameTeam(entity)) return true;
        return entity instanceof EntityMaid other && Objects.equals(maid.func_152113_b(), other.func_152113_b());
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
            halfWidth = entity.width * EnhancedCombatTuning.COMBAT_OBSERVATION_WIDTH;
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
        Vec position, velocity = new Vec(
            EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_ZERO_BASE,
            EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_ZERO_BASE,
            EnhancedCombatTuning.COMBAT_OBSERVATION_OBSERVE_ZERO_BASE);
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
        double gravity = (double) EnhancedCombatTuning.COMBAT_OBSERVATION_DOMAIN_FACTOR_5;

        ProjectileTrack(EntityArrow entity) {
            this.entity = entity;
        }
    }
}
