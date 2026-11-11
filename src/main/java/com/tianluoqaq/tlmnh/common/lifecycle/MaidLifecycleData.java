package com.tianluoqaq.tlmnh.common.lifecycle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;
import net.minecraftforge.common.util.Constants;

/** 世界共享的一次性凭证账本；物品副本没有第二次兑现权 */
public final class MaidLifecycleData extends WorldSavedData {

    public static final String NAME = MaidLifecycleNbtKeys.KEY_TLMNH_LIFECYCLE;

    public enum Kind {
        INITIAL,
        FILM,
        ACQUIRED,
        CAPTURED
    }

    public enum AcquisitionPhase {
        NONE,
        PREPARED,
        ISSUING
    }

    public enum ReturnedPhase {
        READY,
        PREPARED,
        ISSUING,
        HELD
    }

    private static final Set<String> ROOT_FIELDS = fields(
        MaidLifecycleNbtKeys.KEY_SCHEMA,
        MaidLifecycleNbtKeys.KEY_ENTRIES,
        MaidLifecycleNbtKeys.KEY_RETURNED_FILMS);
    private static final Set<String> ENTRY_FIELDS = fields(
        MaidLifecycleNbtKeys.KEY_TOKEN,
        MaidLifecycleNbtKeys.KEY_OWNER,
        MaidLifecycleNbtKeys.KEY_SUBJECT,
        MaidLifecycleNbtKeys.KEY_KIND,
        MaidLifecycleNbtKeys.KEY_CONSUMED,
        MaidLifecycleNbtKeys.KEY_DELIVERED_2,
        MaidLifecycleNbtKeys.KEY_IDENTITY,
        MaidLifecycleNbtKeys.KEY_TOMBSTONE_ITEMS,
        MaidLifecycleNbtKeys.KEY_DIMENSION,
        MaidLifecycleNbtKeys.KEY_X,
        MaidLifecycleNbtKeys.KEY_Y,
        MaidLifecycleNbtKeys.KEY_Z,
        MaidLifecycleNbtKeys.KEY_MAID_NAME,
        MaidLifecycleNbtKeys.KEY_PUBLISHED_2,
        MaidLifecycleNbtKeys.KEY_DEATH_EXPERIENCE,
        MaidLifecycleNbtKeys.KEY_EXPERIENCE_READY_AT,
        MaidLifecycleNbtKeys.KEY_TOMBSTONE_HELD,
        MaidLifecycleNbtKeys.KEY_EXPERIENCE_HELD,
        MaidLifecycleNbtKeys.KEY_ACQUISITION_PHASE,
        MaidLifecycleNbtKeys.KEY_ACQUISITION_SOURCE,
        MaidLifecycleNbtKeys.KEY_ACQUISITION_RECEIPT,
        MaidLifecycleNbtKeys.KEY_ACQUISITION_CREDENTIAL,
        MaidLifecycleNbtKeys.KEY_REDEMPTION,
        MaidLifecycleNbtKeys.KEY_DEATH_INGRESS,
        MaidLifecycleNbtKeys.KEY_TOMBSTONE_PUBLICATION,
        MaidLifecycleNbtKeys.KEY_CAPTURE);
    private static final Set<String> RETURNED_FIELDS = fields(
        MaidLifecycleNbtKeys.KEY_ID,
        MaidLifecycleNbtKeys.KEY_STACK,
        MaidLifecycleNbtKeys.KEY_DIMENSION,
        MaidLifecycleNbtKeys.KEY_X,
        MaidLifecycleNbtKeys.KEY_Y,
        MaidLifecycleNbtKeys.KEY_Z,
        MaidLifecycleNbtKeys.KEY_PHASE,
        MaidLifecycleNbtKeys.KEY_CONTEXT);
    private static final Set<String> RETURN_CONTEXT_FIELDS = fields(
        MaidLifecycleNbtKeys.KEY_KIND,
        MaidLifecycleNbtKeys.KEY_SOURCE);

    private final Map<UUID, Entry> entries = new HashMap<>();
    private final Map<UUID, ReturnedFilm> returnedFilms = new HashMap<>();
    private final Set<UUID> reservations = new HashSet<>();
    private final Set<UUID> tombstoneAttempts = new HashSet<>();
    private final Set<UUID> experienceAttempts = new HashSet<>();
    private final Set<UUID> acquisitionAttempts = new HashSet<>();
    private final Set<UUID> redemptionAttempts = new HashSet<>();
    private final Set<UUID> returnedAttempts = new HashSet<>();
    private final Set<UUID> deathAttempts = new HashSet<>(), tombstonePublications = new HashSet<>();
    private final Map<UUID, UUID> redemptionCandidates = new HashMap<>();
    private final Map<Integer, Integer> experienceCursors = new HashMap<>();
    private final Set<UUID> captureAttempts = new HashSet<>();
    private final Map<UUID, UUID> captureHeads = new HashMap<>();
    private boolean healthy = true;

    public MaidLifecycleData(String name) {
        super(name);
    }

    public static MaidLifecycleData get(World world) {
        if (world == null || world.isRemote)
            throw new IllegalArgumentException(MaidLifecycleDiagnostics.MSG_SERVER_WORLD_REQUIRED);
        var data = (MaidLifecycleData) world.mapStorage.loadData(MaidLifecycleData.class, NAME);
        if (data == null) {
            // MapStorage会吞读取异常，已有文件读坏时不能当新世界再次发放
            var file = world.getSaveHandler()
                .getMapFileFromName(NAME);
            if (file != null && file.exists())
                throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_CANNOT_READ_EXISTING_MAID_LIFECYCLE);
            data = new MaidLifecycleData(NAME);
            world.mapStorage.setData(NAME, data);
            data.markDirty();
        }
        if (!data.healthy)
            throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_INVALID_MAID_LIFECYCLE_DATA_RECOVERY);
        return data;
    }

    public NBTTagCompound capture(UUID token) {
        var entry = entries.get(token);
        return entry == null ? new NBTTagCompound() : (NBTTagCompound) entry.capture.copy();
    }

    public UUID captureHead(UUID subject) {
        return captureHeads.get(subject);
    }

    boolean captureConsumed(UUID token) {
        var entry = entries.get(token);
        return entry != null && entry.kind == Kind.CAPTURED && entry.consumed;
    }

    int nextCaptureSequence(UUID subject) {
        var head = captureHeads.get(subject);
        if (head == null) return MaidLifecycleTuning.TARGET_STACK_SIZE_THRESHOLD;
        var previous = entries.get(head);
        if (!previous.consumed || previous.capture.getInteger(MaidLifecycleNbtKeys.KEY_SEQUENCE) == Integer.MAX_VALUE)
            throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_PREVIOUS_MAID_CAPTURE_IS_STILL);
        return previous.capture.getInteger(MaidLifecycleNbtKeys.KEY_SEQUENCE) + 1;
    }

    void prepareCapture(World world, UUID token, UUID owner, UUID subject, NBTTagCompound source,
        NBTTagCompound record) {
        MaidCaptureRecord.validate(record, token, owner, subject);
        if (entries.containsKey(token) || returnedFilms.containsKey(token)
            || !MaidLifecycleNbtKeys.KEY_PREPARED.equals(record.getString(MaidLifecycleNbtKeys.KEY_STAGE))
            || !source.equals(record.getCompoundTag(MaidLifecycleNbtKeys.KEY_SOURCE))
            || record.getInteger(MaidLifecycleNbtKeys.KEY_SEQUENCE) != nextCaptureSequence(subject))
            throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_CAPTURE_IDENTITY_OR_GENERATION_CHANGED);
        var entry = new Entry(Kind.CAPTURED, owner, subject, (NBTTagCompound) source.copy(), false, false);
        entry.capture = (NBTTagCompound) record.copy();
        entries.put(token, entry);
        captureHeads.put(subject, token);
        captureAttempts.add(token);
        reservations.add(token);
        markDirty();
        MaidLifecycleDurability.ledger(world, this);
    }

    boolean beginCapture(UUID token) {
        var entry = entries.get(token);
        if (entry == null || entry.kind != Kind.CAPTURED
            || entry.consumed
            || entry.delivered
            || !entry.redemption.hasNoTags()
            || !token.equals(captureHeads.get(entry.subject))
            || MaidLifecycleNbtKeys.KEY_HELD.equals(entry.capture.getString(MaidLifecycleNbtKeys.KEY_STAGE))
            || !reservations.add(token)) return false;
        captureAttempts.add(token);
        return true;
    }

    void captureStage(World world, UUID token, String stage) {
        var entry = activeCapture(token);
        String previous = entry.capture.getString(MaidLifecycleNbtKeys.KEY_STAGE);
        if (!(MaidLifecycleNbtKeys.KEY_PREPARED.equals(previous) && MaidLifecycleNbtKeys.KEY_REMOVED.equals(stage)
            || MaidLifecycleNbtKeys.KEY_REMOVED.equals(previous) && MaidLifecycleNbtKeys.KEY_DELIVERED.equals(stage)))
            throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_INVALID_CAPTURE_TRANSITION);
        entry.capture.setString(MaidLifecycleNbtKeys.KEY_STAGE, stage);
        entry.delivered = MaidLifecycleNbtKeys.KEY_DELIVERED.equals(stage);
        try {
            markDirty();
            MaidLifecycleDurability.ledger(world, this);
        } catch (RuntimeException | Error failure) {
            entry.capture.setString(MaidLifecycleNbtKeys.KEY_STAGE, previous);
            entry.delivered = false;
            markDirty();
            throw failure;
        }
    }

    void holdCapture(World world, UUID token, NBTTagCompound observed) {
        var entry = entries.get(token);
        if (entry == null || entry.kind != Kind.CAPTURED || entry.consumed)
            throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_MISSING_CAPTURE_ESCROW);
        if (!MaidLifecycleNbtKeys.KEY_HELD.equals(entry.capture.getString(MaidLifecycleNbtKeys.KEY_STAGE))) {
            entry.capture.setString(MaidLifecycleNbtKeys.KEY_STAGE, MaidLifecycleNbtKeys.KEY_HELD);
            entry.capture.setTag(MaidLifecycleNbtKeys.KEY_OBSERVED, observed.copy());
            entry.delivered = false;
            markDirty();
        }
        MaidLifecycleDurability.ledger(world, this);
    }

    void cancelCapture(World world, UUID token) {
        var entry = activeCapture(token);
        if (!MaidLifecycleNbtKeys.KEY_PREPARED.equals(entry.capture.getString(MaidLifecycleNbtKeys.KEY_STAGE)))
            throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_ONLY_AN_UNTOUCHED_CAPTURE_CAN);
        entries.remove(token);
        rebuildCaptureHead(entry.subject);
        try {
            markDirty();
            MaidLifecycleDurability.ledger(world, this);
        } catch (RuntimeException | Error failure) {
            entries.put(token, entry);
            captureHeads.put(entry.subject, token);
            markDirty();
            throw failure;
        }
    }

    void endCapture(UUID token) {
        captureAttempts.remove(token);
        reservations.remove(token);
    }

    private Entry activeCapture(UUID token) {
        var entry = entries.get(token);
        if (entry == null || entry.kind != Kind.CAPTURED
            || !captureAttempts.contains(token)
            || !reservations.contains(token))
            throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_CAPTURE_RESERVATION_CHANGED);
        return entry;
    }

    private void rebuildCaptureHead(UUID subject) {
        captureHeads.remove(subject);
        int sequence = 0;
        for (var item : entries.entrySet())
            if (item.getValue().kind == Kind.CAPTURED && item.getValue().subject.equals(subject)
                && item.getValue().capture.getInteger(MaidLifecycleNbtKeys.KEY_SEQUENCE) > sequence) {
                    sequence = item.getValue().capture.getInteger(MaidLifecycleNbtKeys.KEY_SEQUENCE);
                    captureHeads.put(subject, item.getKey());
                }
    }

    public UUID initialToken(UUID owner) {
        for (var entry : entries.entrySet()) {
            if (entry.getValue().kind == Kind.INITIAL && entry.getValue().owner.equals(owner)) return entry.getKey();
        }
        return add(Kind.INITIAL, owner, owner, new NBTTagCompound());
    }

    /** 制作获取与每位玩家一次的免费赠送分开，每个核心各有独立兑现权 */
    public UUID acquiredToken(UUID owner) {
        return add(Kind.ACQUIRED, owner, UUID.randomUUID(), new NBTTagCompound());
    }

    public Kind initialKind(UUID token) {
        var entry = entries.get(token);
        return entry != null && (entry.kind == Kind.INITIAL || entry.kind == Kind.ACQUIRED) ? entry.kind : null;
    }

    public AcquisitionPhase acquisitionPhase(UUID token) {
        var entry = entries.get(token);
        return entry == null ? AcquisitionPhase.NONE : entry.acquisitionPhase;
    }

    public NBTTagCompound acquisitionReceipt(UUID token) {
        return (NBTTagCompound) acquisitionEntry(token).acquisitionReceipt.copy();
    }

    public NBTTagCompound acquisitionCredential(UUID token) {
        return (NBTTagCompound) acquisitionEntry(token).acquisitionCredential.copy();
    }

    boolean beginAcquisition(UUID token) {
        var entry = acquisitionEntry(token);
        if (entry.consumed || !entry.redemption.hasNoTags()) return false;
        if (!reservations.add(token)) return false;
        acquisitionAttempts.add(token);
        return true;
    }

    void prepareAcquisition(World world, UUID token, NBTTagCompound source, NBTTagCompound receipt,
        NBTTagCompound credential) {
        var entry = activeAcquisition(token);
        if (entry.acquisitionPhase != AcquisitionPhase.NONE || entry.delivered
            || entry.consumed
            || credential.hasNoTags())
            throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_ACQUISITION_INTENT_ALREADY_EXISTS);
        if (entry.kind == Kind.INITIAL ? !source.hasNoTags() || !receipt.hasNoTags()
            : source.hasNoTags() || receipt.hasNoTags())
            throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_ACQUISITION_SOURCE_DOES_NOT_MATCH);
        var ownedSource = (NBTTagCompound) source.copy();
        var ownedReceipt = (NBTTagCompound) receipt.copy();
        var ownedCredential = (NBTTagCompound) credential.copy();
        entry.acquisitionSource = ownedSource;
        entry.acquisitionReceipt = ownedReceipt;
        entry.acquisitionCredential = ownedCredential;
        entry.acquisitionPhase = entry.kind == Kind.INITIAL ? AcquisitionPhase.ISSUING : AcquisitionPhase.PREPARED;
        markDirty();
        MaidLifecycleDurability.ledger(world, this);
    }

    void issueAcquisition(World world, UUID token) {
        var entry = activeAcquisition(token);
        if (entry.delivered || entry.consumed || entry.acquisitionPhase == AcquisitionPhase.NONE)
            throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_ACQUISITION_INTENT_IS_NOT_PENDING);
        entry.acquisitionPhase = AcquisitionPhase.ISSUING;
        markDirty();
        MaidLifecycleDurability.ledger(world, this);
    }

    void finishAcquisition(World world, UUID token) {
        var entry = activeAcquisition(token);
        if (entry.acquisitionPhase != AcquisitionPhase.ISSUING || entry.delivered || entry.consumed)
            throw new IllegalStateException(
                MaidLifecycleDiagnostics.MSG_ACQUISITION_INTENT_CHANGED_BEFORE_ACKNOWLEDGEMEN);
        entry.delivered = true;
        entry.acquisitionPhase = AcquisitionPhase.NONE;
        boolean durable = false;
        try {
            markDirty();
            MaidLifecycleDurability.ledger(world, this);
            durable = true;
        } finally {
            if (!durable) {
                entry.delivered = false;
                entry.acquisitionPhase = AcquisitionPhase.ISSUING;
                markDirty();
            }
        }
    }

    void endAcquisition(UUID token) {
        acquisitionAttempts.remove(token);
        reservations.remove(token);
    }

    private Entry acquisitionEntry(UUID token) {
        var entry = entries.get(token);
        if (entry == null || entry.kind != Kind.INITIAL && entry.kind != Kind.ACQUIRED)
            throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_NO_ACQUISITION_CREDENTIAL);
        return entry;
    }

    private Entry activeAcquisition(UUID token) {
        var entry = acquisitionEntry(token);
        if (!reservations.contains(token) || !acquisitionAttempts.contains(token))
            throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_ACQUISITION_ATTEMPT_IS_NO_LONGER);
        return entry;
    }

    public NBTTagCompound redemption(UUID token) {
        var entry = entries.get(token);
        return entry == null ? new NBTTagCompound() : (NBTTagCompound) entry.redemption.copy();
    }

    Set<UUID> pendingRedemptions(UUID actor) {
        var result = new HashSet<UUID>();
        for (var item : entries.entrySet()) if (!item.getValue().redemption.hasNoTags() && actor.toString()
            .equals(item.getValue().redemption.getString(MaidLifecycleNbtKeys.KEY_ACTOR))) result.add(item.getKey());
        return result;
    }

    NBTTagCompound pendingMaid(UUID identity) {
        var token = redemptionCandidates.get(identity);
        return token == null ? new NBTTagCompound() : redemption(token);
    }

    boolean resumeRedemption(UUID token) {
        var entry = entries.get(token);
        if (entry == null || entry.consumed || entry.redemption.hasNoTags() || !reservations.add(token)) return false;
        redemptionAttempts.add(token);
        return true;
    }

    void prepareRedemption(World world, UUID token, NBTTagCompound intent) {
        var entry = entries.get(token);
        if (entry == null || entry.consumed
            || !entry.redemption.hasNoTags()
            || !reservations.contains(token)
            || acquisitionAttempts.contains(token))
            throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_NO_EXCLUSIVE_MAID_REDEMPTION_RESERVATION);
        MaidRedemptionRecord.validate(intent);
        var candidate = UUID.fromString(intent.getString(MaidLifecycleNbtKeys.KEY_CANDIDATE));
        if (redemptionCandidates.containsKey(candidate))
            throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_MAID_CANDIDATE_ALREADY_RESERVED);
        entry.redemption = (NBTTagCompound) intent.copy();
        redemptionCandidates.put(candidate, token);
        redemptionAttempts.add(token);
        markDirty();
        MaidLifecycleDurability.ledger(world, this);
    }

    void beginRedemptionCosts(World world, UUID token) {
        var entry = activeRedemption(token);
        entry.redemption.setString(MaidLifecyclePart2NbtKeys.KEY_STAGE, MaidLifecyclePart2NbtKeys.KEY_COSTS);
        markDirty();
        MaidLifecycleDurability.ledger(world, this);
    }

    void finishRedemption(World world, UUID token, boolean consumed) {
        var entry = activeRedemption(token);
        var pending = entry.redemption;
        entry.redemption = new NBTTagCompound();
        entry.consumed = consumed;
        boolean durable = false;
        try {
            markDirty();
            MaidLifecycleDurability.ledger(world, this);
            durable = true;
            redemptionCandidates.remove(UUID.fromString(pending.getString(MaidLifecyclePart2NbtKeys.KEY_CANDIDATE)));
        } finally {
            if (!durable) {
                entry.redemption = pending;
                entry.consumed = false;
                markDirty();
            }
        }
    }

    void endRedemption(UUID token) {
        redemptionAttempts.remove(token);
        reservations.remove(token);
    }

    private Entry activeRedemption(UUID token) {
        var entry = entries.get(token);
        if (entry == null || entry.consumed
            || entry.redemption.hasNoTags()
            || !redemptionAttempts.contains(token)
            || !reservations.contains(token))
            throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_MAID_REDEMPTION_IS_NO_LONGER);
        return entry;
    }

    /** 同一具死亡实体只产生一份胶片凭证，后续调用返回null */
    public UUID newFilm(UUID owner, UUID deadMaid, NBTTagCompound identity) {
        for (var entry : entries.values()) {
            if (entry.kind == Kind.FILM && entry.subject.equals(deadMaid)) return null;
        }
        return add(Kind.FILM, owner, deadMaid, identity);
    }

    public UUID deathToken(UUID deadMaid) {
        for (var item : entries.entrySet()) {
            if (item.getValue().kind == Kind.FILM && item.getValue().subject.equals(deadMaid)) return item.getKey();
        }
        return null;
    }

    /** 库存单独保管，不属于复活身份；库存存入后由墓碑凭证领取 */
    public UUID depositDeath(UUID owner, UUID deadMaid, NBTTagCompound identity, NBTTagList items, int dimension,
        double x, double y, double z, String name) {
        return depositDeath(
            owner,
            deadMaid,
            identity,
            items,
            dimension,
            x,
            y,
            z,
            name,
            MaidLifecycleTuning.CORE_INITIAL_VALUE,
            MaidLifecycleTuning.CORE_INITIAL_VALUE);
    }

    /** 经验与墓碑物品分别兑现，胶片身份始终不携带这份余额 */
    public UUID depositDeath(UUID owner, UUID deadMaid, NBTTagCompound identity, NBTTagList items, int dimension,
        double x, double y, double z, String name, int experience, long experienceReadyAt) {
        if (experience < 0 || experienceReadyAt < 0)
            throw new IllegalArgumentException(MaidLifecycleDiagnostics.MSG_INVALID_DEATH_EXPERIENCE_ESCROW);
        var token = deathToken(deadMaid);
        if (token == null) token = newFilm(owner, deadMaid, identity);
        var entry = entries.get(token);
        if (!entry.delivered) {
            entry.tombstoneItems = (NBTTagList) items.copy();
            entry.dimension = dimension;
            entry.x = x;
            entry.y = y;
            entry.z = z;
            entry.name = name;
            entry.experience = experience;
            entry.experienceReadyAt = experienceReadyAt;
            entry.delivered = true;
            markDirty();
        }
        return token;
    }

    NBTTagCompound deathIngress(UUID token) {
        var entry = entries.get(token);
        return entry == null ? new NBTTagCompound() : (NBTTagCompound) entry.deathIngress.copy();
    }

    boolean pendingDeath(UUID subject) {
        var token = deathToken(subject);
        return token != null && !deathReady(entries.get(token));
    }

    private static boolean deathReady(Entry entry) {
        return entry.deathIngress.hasNoTags() || MaidLifecyclePart2NbtKeys.KEY_COMMITTED
            .equals(entry.deathIngress.getString(MaidLifecyclePart2NbtKeys.KEY_STAGE));
    }

    void prepareDeath(UUID token, UUID owner, UUID subject, NBTTagCompound identity, NBTTagList items, int dimension,
        double x, double y, double z, String name, int experience, long readyAt, NBTTagCompound receipt) {
        MaidDeathIngress.validate(receipt, token, owner, subject);
        var existing = deathToken(subject);
        if (existing != null && !existing.equals(token) || returnedFilms.containsKey(token))
            throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_CONFLICTING_DEATH_SOURCE_IDENTITY_RETAINED);
        var entry = entries.get(token);
        if (entry == null) {
            entry = new Entry(Kind.FILM, owner, subject, (NBTTagCompound) identity.copy(), false, false);
            entries.put(token, entry);
        }
        if (entry.kind != Kind.FILM || !entry.owner.equals(owner) || !entry.subject.equals(subject))
            throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_DEATH_ESCROW_BELONGS_TO_DIFFERENT);
        if (entry.delivered) {
            if (!entry.deathIngress.equals(receipt))
                throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_DEATH_SOURCE_RECEIPT_CHANGED);
            return;
        }
        if (experience < 0 || readyAt < 0 || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
            throw new IllegalArgumentException(MaidLifecycleDiagnostics.MSG_INVALID_PREPARED_DEATH_ESCROW);
        entry.tombstoneItems = (NBTTagList) items.copy();
        entry.dimension = dimension;
        entry.x = x;
        entry.y = y;
        entry.z = z;
        entry.name = name;
        entry.experience = experience;
        entry.experienceReadyAt = readyAt;
        entry.deathIngress = (NBTTagCompound) receipt.copy();
        entry.delivered = true;
        markDirty();
    }

    void beginDeathAttempt(UUID token, NBTTagCompound receipt) {
        var entry = entries.get(token);
        if (entry == null || !entry.delivered
            || !entry.deathIngress.equals(receipt)
            || !MaidLifecyclePart2NbtKeys.KEY_PREPARED.equals(receipt.getString(MaidLifecyclePart2NbtKeys.KEY_STAGE))
            || !deathAttempts.add(token))
            throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_NO_EXACT_OWNED_DEATH_INGRESS);
    }

    void finishDeathAttempt(World world, UUID token, NBTTagCompound receipt) {
        var entry = entries.get(token);
        if (entry == null || !deathAttempts.contains(token) || !entry.deathIngress.equals(receipt))
            throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_DEATH_SOURCE_CHANGED_BEFORE_ACKNOWLEDGEMENT);
        entry.deathIngress.setString(MaidLifecyclePart2NbtKeys.KEY_STAGE, MaidLifecyclePart2NbtKeys.KEY_COMMITTED);
        boolean durable = false;
        try {
            markDirty();
            MaidLifecycleDurability.ledger(world, this);
            durable = true;
        } finally {
            if (!durable) {
                entry.deathIngress
                    .setString(MaidLifecyclePart2NbtKeys.KEY_STAGE, MaidLifecyclePart2NbtKeys.KEY_PREPARED);
                markDirty();
            }
        }
    }

    void endDeathAttempt(UUID token) {
        deathAttempts.remove(token);
    }

    void holdDeathIngress(UUID token, NBTTagCompound observed) {
        var entry = entries.get(token);
        if (entry == null || entry.deathIngress.hasNoTags() || deathReady(entry))
            throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_MISSING_PENDING_DEATH_SOURCE_RECORD);
        if (!MaidLifecyclePart2NbtKeys.KEY_HELD
            .equals(entry.deathIngress.getString(MaidLifecyclePart2NbtKeys.KEY_STAGE))) {
            entry.deathIngress.setString(MaidLifecyclePart2NbtKeys.KEY_STAGE, MaidLifecyclePart2NbtKeys.KEY_HELD);
            entry.deathIngress.setTag(MaidLifecyclePart2NbtKeys.KEY_OBSERVED, observed.copy());
            markDirty();
        }
    }

    NBTTagCompound tombstonePublication(UUID token) {
        var entry = entries.get(token);
        return entry == null ? new NBTTagCompound() : (NBTTagCompound) entry.tombstonePublication.copy();
    }

    void beginTombstonePublication(World world, UUID token, NBTTagCompound receipt) {
        var entry = deathEscrow(token);
        MaidDeathService.validatePublication(receipt, token, entry.owner);
        if (!deathReady(entry) || entry.published
            || !reservations.contains(token)
            || !entry.tombstonePublication.hasNoTags()
            || !tombstonePublications.add(token))
            throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_NO_OWNED_TOMBSTONE_PUBLICATION_ATTEMPT);
        entry.tombstonePublication = (NBTTagCompound) receipt.copy();
        markDirty();
        MaidLifecycleDurability.ledger(world, this);
    }

    void finishTombstonePublication(World world, UUID token, NBTTagCompound receipt) {
        var entry = deathEscrow(token);
        if (!tombstonePublications.contains(token) || !reservations.contains(token)
            || !entry.tombstonePublication.equals(receipt)
            || !MaidLifecyclePart2NbtKeys.KEY_PREPARED.equals(receipt.getString(MaidLifecyclePart2NbtKeys.KEY_STAGE)))
            throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_UNKNOWN_TOMBSTONE_SUCCESSOR_CANNOT_BE);
        entry.published = true;
        entry.tombstonePublication
            .setString(MaidLifecyclePart2NbtKeys.KEY_STAGE, MaidLifecyclePart2NbtKeys.KEY_PUBLISHED);
        boolean durable = false;
        try {
            markDirty();
            MaidLifecycleDurability.ledger(world, this);
            durable = true;
        } finally {
            if (!durable) {
                entry.published = false;
                entry.tombstonePublication
                    .setString(MaidLifecyclePart2NbtKeys.KEY_STAGE, MaidLifecyclePart2NbtKeys.KEY_PREPARED);
                markDirty();
            }
        }
    }

    void cancelTombstonePublication(World world, UUID token, NBTTagCompound receipt) {
        var entry = deathEscrow(token);
        if (!tombstonePublications.contains(token) || entry.published || !entry.tombstonePublication.equals(receipt))
            throw new IllegalStateException(
                MaidLifecyclePart2Diagnostics.MSG_NO_SAFELY_RETRACTED_TOMBSTONE_PUBLICATION);
        entry.tombstonePublication = new NBTTagCompound();
        boolean durable = false;
        try {
            markDirty();
            MaidLifecycleDurability.ledger(world, this);
            durable = true;
        } finally {
            if (!durable) {
                entry.tombstonePublication = (NBTTagCompound) receipt.copy();
                markDirty();
            }
        }
    }

    void resumeTombstonePublication(UUID token, NBTTagCompound receipt) {
        var entry = deathEscrow(token);
        if (!reservations.contains(token) || !entry.tombstonePublication.equals(receipt)
            || !MaidLifecyclePart2NbtKeys.KEY_PREPARED.equals(receipt.getString(MaidLifecyclePart2NbtKeys.KEY_STAGE))
            || !tombstonePublications.add(token))
            throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_NO_EXACT_PENDING_TOMBSTONE_TO);
    }

    void holdTombstonePublication(UUID token, NBTTagCompound observed) {
        var entry = deathEscrow(token);
        if (entry.tombstonePublication.hasNoTags())
            throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_MISSING_TOMBSTONE_PUBLICATION_RECORD);
        if (!MaidLifecyclePart2NbtKeys.KEY_HELD
            .equals(entry.tombstonePublication.getString(MaidLifecyclePart2NbtKeys.KEY_STAGE))) {
            entry.tombstonePublication
                .setString(MaidLifecyclePart2NbtKeys.KEY_STAGE, MaidLifecyclePart2NbtKeys.KEY_HELD);
            entry.tombstonePublication.setTag(MaidLifecyclePart2NbtKeys.KEY_OBSERVED, observed.copy());
            entry.published = false;
            markDirty();
        }
    }

    void endTombstonePublication(UUID token) {
        tombstonePublications.remove(token);
        reservations.remove(token);
    }

    public List<UUID> pendingDeathExperience(int dimension, long now) {
        var result = new ArrayList<UUID>();
        for (var item : entries.entrySet()) {
            var entry = item.getValue();
            if (entry.kind == Kind.FILM && entry.delivered
                && deathReady(entry)
                && !entry.experienceHeld
                && entry.dimension == dimension
                && entry.experience > 0
                && entry.experienceReadyAt <= now) result.add(item.getKey());
        }
        if (result.size() > MaidLifecycleTuning.TARGET_STACK_SIZE_THRESHOLD) {
            int cursor = experienceCursors.getOrDefault(dimension, MaidLifecycleTuning.CORE_INITIAL_VALUE);
            Collections.rotate(result, -(cursor % result.size()));
            experienceCursors
                .put(dimension, cursor == Integer.MAX_VALUE ? MaidLifecycleTuning.CORE_INITIAL_VALUE : cursor + 1);
        }
        return result;
    }

    public int remainingDeathExperience(UUID token) {
        var entry = entries.get(token);
        return entry != null && entry.kind == Kind.FILM && entry.delivered ? entry.experience
            : MaidLifecycleTuning.CORE_INITIAL_VALUE;
    }

    public int reserveDeathExperience(UUID token) {
        var entry = entries.get(token);
        if (entry == null || !deathReady(entry) || isDeathExperienceHeld(token))
            return MaidLifecycleTuning.CORE_INITIAL_VALUE;
        int remaining = remainingDeathExperience(token);
        return remaining > 0 && reservations.add(token) ? remaining : MaidLifecycleTuning.CORE_INITIAL_VALUE;
    }

    public void finishDeathExperience(UUID token, int expected, int released) {
        var entry = entries.get(token);
        if (entry == null || entry.experienceHeld
            || !reservations.contains(token)
            || entry.experience != expected
            || released <= 0
            || released > expected)
            throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_DEATH_EXPERIENCE_RESERVATION_OR_BALANCE);
        reservations.remove(token);
        entry.experience -= released;
        markDirty();
    }

    /** 无法确认已投放实体的归属时保留原余额，重启也不得重新兑现 */
    public void holdDeathExperience(UUID token) {
        deathEscrow(token).experienceHeld = true;
        experienceAttempts.remove(token);
        markDirty();
    }

    public boolean isDeathExperienceHeld(UUID token) {
        var entry = entries.get(token);
        return entry != null && entry.experienceHeld;
    }

    public void holdTombstone(UUID token) {
        deathEscrow(token).tombstoneHeld = true;
        tombstoneAttempts.remove(token);
        markDirty();
    }

    /** 复用schema4持久hold作为写前屏障；本进程的调用所有权不写盘，重启后只能保持封存 */
    void beginTombstoneAttempt(World world, UUID token, NBTTagList expected) {
        var entry = deathEscrow(token);
        if (!reservations.contains(token) || entry.tombstoneHeld
            || !entry.tombstoneItems.equals(expected)
            || !tombstoneAttempts.add(token))
            throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_TOMBSTONE_ATTEMPT_CHANGED_BEFORE_WRITE);
        entry.tombstoneHeld = true;
        markDirty();
        boolean durable = false;
        try {
            MaidLifecycleDurability.ledger(world, this);
            durable = true;
        } finally {
            if (!durable) tombstoneAttempts.remove(token);
        }
    }

    void beginExperienceAttempt(World world, UUID token, int expected) {
        var entry = deathEscrow(token);
        if (!reservations.contains(token) || entry.experienceHeld
            || entry.experience != expected
            || !experienceAttempts.add(token))
            throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_XP_ATTEMPT_CHANGED_BEFORE_WRITE);
        entry.experienceHeld = true;
        markDirty();
        boolean durable = false;
        try {
            MaidLifecycleDurability.ledger(world, this);
            durable = true;
        } finally {
            if (!durable) experienceAttempts.remove(token);
        }
    }

    void finishTombstoneAttempt(World world, UUID token, NBTTagList expected) {
        var entry = activeAttempt(token, false);
        if (!entry.tombstoneItems.equals(expected))
            throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_TOMBSTONE_ESCROW_CHANGED);
        var original = entry.tombstoneItems;
        entry.tombstoneItems = new NBTTagList();
        entry.tombstoneHeld = false;
        boolean durable = false;
        try {
            markDirty();
            MaidLifecycleDurability.ledger(world, this);
            durable = true;
        } finally {
            if (!durable) {
                entry.tombstoneItems = original;
                entry.tombstoneHeld = true;
                markDirty();
            }
            tombstoneAttempts.remove(token);
            reservations.remove(token);
        }
    }

    void finishExperienceAttempt(World world, UUID token, int expected, int released) {
        var entry = activeAttempt(token, true);
        if (entry.experience != expected || released <= 0 || released > expected)
            throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_XP_ATTEMPT_BALANCE_CHANGED);
        entry.experience -= released;
        entry.experienceHeld = false;
        boolean durable = false;
        try {
            markDirty();
            MaidLifecycleDurability.ledger(world, this);
            durable = true;
        } finally {
            if (!durable) {
                entry.experience = expected;
                entry.experienceHeld = true;
                markDirty();
            }
            experienceAttempts.remove(token);
            reservations.remove(token);
        }
    }

    void cancelAttempt(World world, UUID token, boolean experience) {
        var entry = activeAttempt(token, experience);
        if (experience) entry.experienceHeld = false;
        else entry.tombstoneHeld = false;
        boolean durable = false;
        try {
            markDirty();
            MaidLifecycleDurability.ledger(world, this);
            durable = true;
        } finally {
            if (!durable) {
                if (experience) entry.experienceHeld = true;
                else entry.tombstoneHeld = true;
                markDirty();
            }
            abandonAttempt(token, experience);
        }
    }

    void abandonAttempt(UUID token, boolean experience) {
        (experience ? experienceAttempts : tombstoneAttempts).remove(token);
        reservations.remove(token);
    }

    private Entry activeAttempt(UUID token, boolean experience) {
        var entry = deathEscrow(token);
        if (!reservations.contains(token) || !(experience ? experienceAttempts : tombstoneAttempts).contains(token)
            || !(experience ? entry.experienceHeld : entry.tombstoneHeld))
            throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_DURABLE_MATERIAL_ATTEMPT_IS_NO);
        return entry;
    }

    public boolean isTombstoneHeld(UUID token) {
        var entry = entries.get(token);
        return entry != null && entry.tombstoneHeld;
    }

    private Entry deathEscrow(UUID token) {
        var entry = entries.get(token);
        if (entry == null || entry.kind != Kind.FILM || !entry.delivered)
            throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_NO_DELIVERED_DEATH_ESCROW_TO);
        return entry;
    }

    public List<UUID> pendingTombstones(int dimension) {
        var result = new ArrayList<UUID>();
        for (var item : entries.entrySet()) {
            var entry = item.getValue();
            if (entry.kind == Kind.FILM && entry.delivered
                && deathReady(entry)
                && !MaidLifecyclePart2NbtKeys.KEY_HELD
                    .equals(entry.tombstonePublication.getString(MaidLifecyclePart2NbtKeys.KEY_STAGE))
                && !entry.tombstoneHeld
                && !entry.published
                && entry.dimension == dimension) result.add(item.getKey());
        }
        return result;
    }

    public Vec3 tombstonePosition(UUID token) {
        var entry = entries.get(token);
        return entry == null ? null : Vec3.createVectorHelper(entry.x, entry.y, entry.z);
    }

    public String tombstoneName(UUID token) {
        var entry = entries.get(token);
        return entry == null ? "" : entry.name;
    }

    public void markPublished(UUID token) {
        var entry = entries.get(token);
        if (entry == null || entry.kind != Kind.FILM || !deathReady(entry) || !entry.tombstonePublication.hasNoTags())
            throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_PENDING_SOURCE_OR_HANDLE_REQUIRES);
        entry.published = true;
        markDirty();
    }

    public NBTTagList reserveTombstone(UUID token) {
        var entry = entries.get(token);
        if (entry == null || entry.kind != Kind.FILM
            || !entry.delivered
            || !deathReady(entry)
            || entry.tombstoneHeld
            || !entry.published
            || entry.tombstoneItems.tagCount() == 0
            || !reservations.add(token)) return null;
        return (NBTTagList) entry.tombstoneItems.copy();
    }

    public boolean tombstoneEmpty(UUID token) {
        var entry = entries.get(token);
        return entry == null || !entry.delivered || entry.tombstoneItems.tagCount() == 0;
    }

    public void finishTombstone(UUID token, NBTTagList remainder) {
        finishTombstone(token, null, remainder);
    }

    public void finishTombstone(UUID token, NBTTagList expected, NBTTagList remainder) {
        var entry = entries.get(token);
        if (entry == null || entry.tombstoneHeld
            || !reservations.contains(token)
            || expected != null && !expected.equals(entry.tombstoneItems))
            throw new IllegalStateException(
                MaidLifecyclePart2Diagnostics.MSG_TOMBSTONE_RESERVATION_OR_ORIGINAL_CONTENTS);
        reservations.remove(token);
        entry.tombstoneItems = (NBTTagList) remainder.copy();
        markDirty();
    }

    private UUID add(Kind kind, UUID owner, UUID subject, NBTTagCompound identity) {
        if (owner == null || subject == null || identity == null)
            throw new IllegalArgumentException(MaidLifecyclePart2Diagnostics.MSG_MISSING_LIFECYCLE_IDENTITY);
        UUID token;
        do token = UUID.randomUUID(); while (entries.containsKey(token));
        entries.put(token, new Entry(kind, owner, subject, (NBTTagCompound) identity.copy(), false, false));
        markDirty();
        return token;
    }

    public UUID owner(UUID token, Kind kind) {
        var entry = entries.get(token);
        return entry != null && entry.kind == kind ? entry.owner : null;
    }

    public boolean delivered(UUID token) {
        var entry = entries.get(token);
        return entry != null && (entry.delivered || entry.consumed);
    }

    public void markDelivered(UUID token) {
        var entry = entries.get(token);
        if (entry == null || initialKind(token) == null)
            throw new IllegalArgumentException(MaidLifecyclePart2Diagnostics.MSG_UNKNOWN_INITIAL_TOKEN);
        if (entry.acquisitionPhase != AcquisitionPhase.NONE) throw new IllegalStateException(
            MaidLifecyclePart2Diagnostics.MSG_PENDING_ACQUISITION_REQUIRES_DURABLE_ACKNOWLEDGE);
        entry.delivered = true;
        markDirty();
    }

    /** 返回独立快照，同时阻止出生事件回调重入；保留状态仅存在本次服务端调用栈 */
    public NBTTagCompound reserve(UUID token, Kind kind) {
        var entry = entries.get(token);
        if (entry == null || entry.kind != kind
            || entry.consumed
            || !deathReady(entry)
            || kind == Kind.CAPTURED && (!entry.delivered
                || !MaidLifecyclePart2NbtKeys.KEY_DELIVERED
                    .equals(entry.capture.getString(MaidLifecyclePart2NbtKeys.KEY_STAGE))
                || !token.equals(captureHeads.get(entry.subject)))
            || entry.acquisitionPhase != AcquisitionPhase.NONE
            || !entry.redemption.hasNoTags()
            || !reservations.add(token)) return null;
        if (kind == Kind.CAPTURED) redemptionAttempts.add(token);
        return (NBTTagCompound) entry.identity.copy();
    }

    public void commit(UUID token) {
        var entry = entries.get(token);
        if (entry == null || entry.acquisitionPhase != AcquisitionPhase.NONE
            || acquisitionAttempts.contains(token)
            || !entry.redemption.hasNoTags()
            || !reservations.remove(token))
            throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_NO_LIFECYCLE_RESERVATION);
        redemptionAttempts.remove(token);
        entry.consumed = true;
        markDirty();
    }

    public void rollback(UUID token) {
        reservations.remove(token);
        redemptionAttempts.remove(token);
    }

    public UUID queueReturnedFilm(NBTTagCompound stack, int dimension, double x, double y, double z) {
        return addReturnedFilm(stack, dimension, x, y, z, ReturnedPhase.READY, new NBTTagCompound());
    }

    /** 未知野生死亡数据只封存原raw，永远不是可解码投放的ItemStack */
    void retainUnownedDeath(UUID id, NBTTagCompound raw, int dimension, double x, double y, double z) {
        if (entries.containsKey(id) || raw.hasNoTags()
            || !Double.isFinite(x)
            || !Double.isFinite(y)
            || !Double.isFinite(z))
            throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_INVALID_OPAQUE_UNOWNED_DEATH_HOLD);
        var existing = returnedFilms.get(id);
        if (existing != null) {
            if (existing.phase != ReturnedPhase.HELD || !existing.stack.equals(raw))
                throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_UNKNOWN_OPAQUE_DEATH_HOLD_SUCCESSOR);
            return;
        }
        var entry = new ReturnedFilm((NBTTagCompound) raw.copy(), dimension, x, y, z);
        entry.phase = ReturnedPhase.HELD;
        returnedFilms.put(id, entry);
        markDirty();
    }

    UUID prepareReturnedFilm(NBTTagCompound stack, int dimension, double x, double y, double z,
        NBTTagCompound context) {
        validateReturnContext(context, ReturnedPhase.PREPARED);
        var id = addReturnedFilm(stack, dimension, x, y, z, ReturnedPhase.PREPARED, context);
        returnedAttempts.add(id);
        reservations.add(id);
        return id;
    }

    private UUID addReturnedFilm(NBTTagCompound stack, int dimension, double x, double y, double z, ReturnedPhase phase,
        NBTTagCompound context) {
        if (stack == null || stack.hasNoTags() || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
            throw new IllegalArgumentException(MaidLifecyclePart2Diagnostics.MSG_COMPLETE_RETURNED_MATERIAL_REQUIRED);
        UUID id;
        do id = UUID.randomUUID(); while (entries.containsKey(id) || returnedFilms.containsKey(id));
        var entry = new ReturnedFilm((NBTTagCompound) stack.copy(), dimension, x, y, z);
        entry.phase = phase;
        entry.context = (NBTTagCompound) context.copy();
        returnedFilms.put(id, entry);
        markDirty();
        return id;
    }

    public List<UUID> pendingReturnedFilms(int dimension) {
        var result = new ArrayList<UUID>();
        for (var item : returnedFilms.entrySet()) if (item.getValue().dimension == dimension) result.add(item.getKey());
        return result;
    }

    public NBTTagCompound reserveReturnedFilm(UUID id) {
        var entry = returnedFilms.get(id);
        if (entry == null || entry.phase != ReturnedPhase.READY || !reservations.add(id)) return null;
        returnedAttempts.add(id);
        return (NBTTagCompound) entry.stack.copy();
    }

    void beginReturnedFilm(UUID id) {
        var entry = returnedFilms.get(id);
        if (entry == null || entry.phase != ReturnedPhase.READY
            || !returnedAttempts.contains(id)
            || !reservations.contains(id))
            throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_NO_OWNED_RETURNED_MATERIAL_ATTEMPT);
        entry.phase = ReturnedPhase.ISSUING;
        markDirty();
    }

    void retryReturnedFilm(UUID id) {
        var entry = returnedFilms.get(id);
        if (entry == null || !returnedAttempts.contains(id) || !reservations.contains(id))
            throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_NO_SAFELY_RETRACTED_RETURNED_MATERIAL);
        entry.phase = ReturnedPhase.READY;
        abandonReturnedFilm(id);
        markDirty();
    }

    void abandonReturnedFilm(UUID id) {
        returnedAttempts.remove(id);
        reservations.remove(id);
    }

    void holdReturnedFilm(UUID id) {
        var entry = returnedFilms.get(id);
        if (entry == null) throw new IllegalStateException(
            MaidLifecyclePart2Diagnostics.MSG_MISSING_RETURNED_ESCROW_REMAINS_UNCONFIRMED);
        entry.phase = ReturnedPhase.HELD;
        abandonReturnedFilm(id);
        markDirty();
    }

    void readyPreparedReturn(UUID id, NBTTagCompound context) {
        var entry = preparedReturn(id, context);
        entry.phase = ReturnedPhase.READY;
        abandonReturnedFilm(id);
        markDirty();
    }

    void finishPreparedReturn(UUID id, NBTTagCompound context) {
        preparedReturn(id, context);
        returnedFilms.remove(id);
        abandonReturnedFilm(id);
        markDirty();
    }

    private ReturnedFilm preparedReturn(UUID id, NBTTagCompound context) {
        var entry = returnedFilms.get(id);
        if (entry == null || entry.phase != ReturnedPhase.PREPARED
            || !entry.context.equals(context)
            || !returnedAttempts.contains(id)
            || !reservations.contains(id))
            throw new IllegalStateException(
                MaidLifecyclePart2Diagnostics.MSG_UNKNOWN_RETURNED_SOURCE_SUCCESSOR_REMAINS);
        return entry;
    }

    void observeReturnedSource(UUID id, NBTTagCompound context, NBTTagCompound observed) {
        var entry = returnedFilms.get(id);
        if (entry == null || !(entry.phase == ReturnedPhase.PREPARED || entry.phase == ReturnedPhase.READY)
            || !entry.context.equals(context)
            || !entry.context.hasKey(MaidLifecyclePart2NbtKeys.KEY_SOURCE, Constants.NBT.TAG_COMPOUND))
            throw new IllegalStateException(
                MaidLifecyclePart2Diagnostics.MSG_UNKNOWN_MATERIAL_SOURCE_OBSERVATION_RETAINED);
        entry.context.getCompoundTag(MaidLifecyclePart2NbtKeys.KEY_SOURCE)
            .setTag(MaidLifecyclePart2NbtKeys.KEY_UNCONFIRMED_SOURCE, observed.copy());
        markDirty();
    }

    public ReturnedPhase returnedPhase(UUID id) {
        var entry = returnedFilms.get(id);
        return entry == null ? null : entry.phase;
    }

    public Vec3 returnedFilmPosition(UUID id) {
        var entry = returnedFilms.get(id);
        return entry == null ? null : Vec3.createVectorHelper(entry.x, entry.y, entry.z);
    }

    public void finishReturnedFilm(UUID id) {
        var entry = returnedFilms.get(id);
        if (entry == null || entry.phase != ReturnedPhase.ISSUING
            || !returnedAttempts.contains(id)
            || !reservations.contains(id))
            throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_NO_RETURNED_FILM_RESERVATION);
        returnedFilms.remove(id);
        abandonReturnedFilm(id);
        markDirty();
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        if (!tombstoneAttempts.isEmpty() || !experienceAttempts.isEmpty()
            || !acquisitionAttempts.isEmpty()
            || !redemptionAttempts.isEmpty()
            || !returnedAttempts.isEmpty()
            || !deathAttempts.isEmpty()
            || !tombstonePublications.isEmpty()
            || !captureAttempts.isEmpty())
            throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_CANNOT_REPLACE_LEDGER_DURING_DURABLE);
        healthy = false;
        int schema = tag.getInteger(MaidLifecyclePart2NbtKeys.KEY_SCHEMA);
        if (!tag.hasKey(MaidLifecyclePart2NbtKeys.KEY_SCHEMA, Constants.NBT.TAG_INT)
            || schema < MaidLifecycleTuning.TARGET_STACK_SIZE_THRESHOLD
            || schema > MaidLifecycleTuning.MAX_SCHEMA_THRESHOLD)
            throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_UNSUPPORTED_MAID_LIFECYCLE_SCHEMA);
        knownFields(tag, ROOT_FIELDS);
        var restored = new HashMap<UUID, Entry>();
        var list = ownedList(tag, MaidLifecyclePart2NbtKeys.KEY_ENTRIES, true);
        var initialOwners = new HashSet<UUID>();
        var deadMaids = new HashSet<UUID>();
        var acquisitions = new HashSet<UUID>();
        var candidates = new HashMap<UUID, UUID>();
        var captureGenerations = new HashSet<String>();
        var restoredCaptureHeads = new HashMap<UUID, UUID>();
        for (int i = 0; i < list.tagCount(); i++) {
            var item = list.getCompoundTagAt(i);
            knownFields(item, ENTRY_FIELDS);
            var token = UUID.fromString(item.getString(MaidLifecyclePart2NbtKeys.KEY_TOKEN));
            var owner = UUID.fromString(item.getString(MaidLifecyclePart2NbtKeys.KEY_OWNER));
            var subject = UUID.fromString(item.getString(MaidLifecyclePart2NbtKeys.KEY_SUBJECT));
            var kind = Kind.valueOf(item.getString(MaidLifecyclePart2NbtKeys.KEY_KIND));
            if (item.hasKey(MaidLifecyclePart2NbtKeys.KEY_IDENTITY)
                && !item.hasKey(MaidLifecyclePart2NbtKeys.KEY_IDENTITY, Constants.NBT.TAG_COMPOUND))
                throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_INVALID_LIFECYCLE_IDENTITY_ENCODING);
            if (schema == MaidLifecycleTuning.TARGET_STACK_SIZE_THRESHOLD && kind == Kind.ACQUIRED)
                throw new IllegalStateException(
                    MaidLifecyclePart2Diagnostics.MSG_ACQUIRED_CREDENTIAL_REQUIRES_LIFECYCLE_SCHEMA);
            boolean unique = switch (kind) {
                case INITIAL -> initialOwners.add(owner);
                case FILM -> deadMaids.add(subject);
                case ACQUIRED -> acquisitions.add(subject);
                case CAPTURED -> schema >= MaidLifecycleTuning.MAX_SCHEMA_THRESHOLD;
            };
            if (restored.containsKey(token) || !unique)
                throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_DUPLICATE_MAID_LIFECYCLE_IDENTITY);
            var entry = new Entry(
                kind,
                owner,
                subject,
                (NBTTagCompound) item.getCompoundTag(MaidLifecyclePart2NbtKeys.KEY_IDENTITY)
                    .copy(),
                readFlag(item, MaidLifecyclePart2NbtKeys.KEY_CONSUMED, true),
                readFlag(item, MaidLifecyclePart2NbtKeys.KEY_DELIVERED_2, true));
            entry.tombstoneItems = (NBTTagList) ownedList(
                item,
                MaidLifecyclePart2NbtKeys.KEY_TOMBSTONE_ITEMS,
                schema >= MaidLifecycleTuning.DEATH_EXPERIENCE_BLOCK_EXISTS_SECOND_PARAM
                    || kind == Kind.FILM && entry.delivered).copy();
            entry.dimension = item.getInteger(MaidLifecyclePart2NbtKeys.KEY_DIMENSION);
            entry.x = item.getDouble(MaidLifecyclePart2NbtKeys.KEY_X);
            entry.y = item.getDouble(MaidLifecyclePart2NbtKeys.KEY_Y);
            entry.z = item.getDouble(MaidLifecyclePart2NbtKeys.KEY_Z);
            entry.name = item.getString(MaidLifecyclePart2NbtKeys.KEY_MAID_NAME);
            entry.published = readFlag(item, MaidLifecyclePart2NbtKeys.KEY_PUBLISHED_2, true);
            if (schema >= MaidLifecycleTuning.TARGET_SIZE_THRESHOLD
                && (!item.hasKey(MaidLifecyclePart2NbtKeys.KEY_DEATH_EXPERIENCE, Constants.NBT.TAG_INT)
                    || !item.hasKey(MaidLifecyclePart2NbtKeys.KEY_EXPERIENCE_READY_AT, Constants.NBT.TAG_LONG))
                || item.hasKey(MaidLifecyclePart2NbtKeys.KEY_DEATH_EXPERIENCE)
                    && !item.hasKey(MaidLifecyclePart2NbtKeys.KEY_DEATH_EXPERIENCE, Constants.NBT.TAG_INT)
                || item.hasKey(MaidLifecyclePart2NbtKeys.KEY_EXPERIENCE_READY_AT)
                    && !item.hasKey(MaidLifecyclePart2NbtKeys.KEY_EXPERIENCE_READY_AT, Constants.NBT.TAG_LONG))
                throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_INVALID_DEATH_EXPERIENCE_ENCODING);
            entry.experience = item.getInteger(MaidLifecyclePart2NbtKeys.KEY_DEATH_EXPERIENCE);
            entry.experienceReadyAt = item.getLong(MaidLifecyclePart2NbtKeys.KEY_EXPERIENCE_READY_AT);
            if (entry.experience < 0 || entry.experienceReadyAt < 0
                || entry.experience > 0 && (kind != Kind.FILM || !entry.delivered))
                throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_INVALID_DEATH_EXPERIENCE_BALANCE);
            entry.tombstoneHeld = readHold(item, MaidLifecyclePart2NbtKeys.KEY_TOMBSTONE_HELD, schema);
            entry.experienceHeld = readHold(item, MaidLifecyclePart2NbtKeys.KEY_EXPERIENCE_HELD, schema);
            readAcquisition(entry, item, schema);
            if (schema < MaidLifecycleTuning.MIN_SCHEMA_THRESHOLD
                && item.hasKey(MaidLifecyclePart2NbtKeys.KEY_REDEMPTION)
                || schema >= MaidLifecycleTuning.MIN_SCHEMA_THRESHOLD
                    && !item.hasKey(MaidLifecyclePart2NbtKeys.KEY_REDEMPTION, Constants.NBT.TAG_COMPOUND))
                throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_INVALID_MAID_REDEMPTION_ENCODING);
            entry.redemption = (NBTTagCompound) item.getCompoundTag(MaidLifecyclePart2NbtKeys.KEY_REDEMPTION)
                .copy();
            if (!entry.redemption.hasNoTags()) {
                MaidRedemptionRecord.validate(entry.redemption);
                if (entry.consumed || entry.acquisitionPhase != AcquisitionPhase.NONE
                    || (kind == Kind.FILM) != entry.redemption.getBoolean(MaidLifecyclePart2NbtKeys.KEY_SHRINE))
                    throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_INVALID_MAID_REDEMPTION_STATE);
                if (candidates
                    .put(UUID.fromString(entry.redemption.getString(MaidLifecyclePart2NbtKeys.KEY_CANDIDATE)), token)
                    != null)
                    throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_DUPLICATE_PENDING_MAID_CANDIDATE);
            }
            if (schema >= MaidLifecycleTuning.CAPTURE_RECORD_VALIDATE_PARAM) {
                if (!item.hasKey(MaidLifecyclePart2NbtKeys.KEY_DEATH_INGRESS, Constants.NBT.TAG_COMPOUND)
                    || !item.hasKey(MaidLifecyclePart2NbtKeys.KEY_TOMBSTONE_PUBLICATION, Constants.NBT.TAG_COMPOUND))
                    throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_INVALID_DEATH_TRANSFER_RECORDS);
                entry.deathIngress = (NBTTagCompound) item.getCompoundTag(MaidLifecyclePart2NbtKeys.KEY_DEATH_INGRESS)
                    .copy();
                entry.tombstonePublication = (NBTTagCompound) item
                    .getCompoundTag(MaidLifecyclePart2NbtKeys.KEY_TOMBSTONE_PUBLICATION)
                    .copy();
                if (!entry.deathIngress.hasNoTags()) {
                    MaidDeathIngress.validate(entry.deathIngress, token, owner, subject);
                    if (kind != Kind.FILM || !entry.delivered
                        || !deathReady(entry) && (entry.consumed || entry.published))
                        throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_INVALID_PENDING_DEATH_SOURCE);
                }
                if (!entry.tombstonePublication.hasNoTags()) {
                    MaidDeathService.validatePublication(entry.tombstonePublication, token, owner);
                    if (kind != Kind.FILM || !entry.delivered
                        || !deathReady(entry)
                        || entry.published != MaidLifecyclePart2NbtKeys.KEY_PUBLISHED
                            .equals(entry.tombstonePublication.getString(MaidLifecyclePart2NbtKeys.KEY_STAGE)))
                        throw new IllegalStateException(
                            MaidLifecyclePart2Diagnostics.MSG_INVALID_TOMBSTONE_PUBLICATION_STATE);
                }
            } else if (item.hasKey(MaidLifecyclePart2NbtKeys.KEY_DEATH_INGRESS)
                || item.hasKey(MaidLifecyclePart2NbtKeys.KEY_TOMBSTONE_PUBLICATION)) {
                    throw new IllegalStateException(
                        MaidLifecyclePart2Diagnostics.MSG_DEATH_TRANSFER_RECORDS_REQUIRE_LIFECYCLE);
                }
            if (schema >= MaidLifecycleTuning.MAX_SCHEMA_THRESHOLD) {
                if (!item.hasKey(MaidLifecyclePart2NbtKeys.KEY_CAPTURE, Constants.NBT.TAG_COMPOUND))
                    throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_MISSING_CAPTURE_RECORD);
                entry.capture = (NBTTagCompound) item.getCompoundTag(MaidLifecyclePart2NbtKeys.KEY_CAPTURE)
                    .copy();
                if (kind == Kind.CAPTURED) {
                    MaidCaptureRecord.validate(entry.capture, token, owner, subject);
                    if (!entry.identity.equals(entry.capture.getCompoundTag(MaidLifecyclePart2NbtKeys.KEY_SOURCE))
                        || entry.delivered != MaidLifecyclePart2NbtKeys.KEY_DELIVERED
                            .equals(entry.capture.getString(MaidLifecyclePart2NbtKeys.KEY_STAGE))
                        || entry.consumed && !entry.delivered
                        || !captureGenerations.add(
                            subject + MaidLifecycleIds.PATH_SLASH_SEPARATOR
                                + entry.capture.getInteger(MaidLifecyclePart2NbtKeys.KEY_SEQUENCE)))
                        throw new IllegalStateException(
                            MaidLifecyclePart2Diagnostics.MSG_INVALID_STORED_MAID_IDENTITY_OR);
                    var previous = restoredCaptureHeads.get(subject);
                    if (previous == null
                        || restored.get(previous).capture.getInteger(MaidLifecyclePart2NbtKeys.KEY_SEQUENCE)
                            < entry.capture.getInteger(MaidLifecyclePart2NbtKeys.KEY_SEQUENCE))
                        restoredCaptureHeads.put(subject, token);
                } else if (!entry.capture.hasNoTags())
                    throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_UNEXPECTED_CAPTURE_RECORD);
            } else if (item.hasKey(MaidLifecyclePart2NbtKeys.KEY_CAPTURE))
                throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_CAPTURE_REQUIRES_LIFECYCLE_SCHEMA);
            if ((entry.tombstoneHeld || entry.experienceHeld) && (kind != Kind.FILM || !entry.delivered))
                throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_INVALID_HELD_DEATH_ESCROW);
            if (!Double.isFinite(entry.x) || !Double.isFinite(entry.y) || !Double.isFinite(entry.z))
                throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_INVALID_TOMBSTONE_POSITION);
            restored.put(token, entry);
        }
        var restoredFilms = new HashMap<UUID, ReturnedFilm>();
        var films = ownedList(
            tag,
            MaidLifecyclePart2NbtKeys.KEY_RETURNED_FILMS,
            schema >= MaidLifecycleTuning.DEATH_EXPERIENCE_BLOCK_EXISTS_SECOND_PARAM);
        for (int i = 0; i < films.tagCount(); i++) {
            var film = films.getCompoundTagAt(i);
            knownFields(film, RETURNED_FIELDS);
            var id = UUID.fromString(film.getString(MaidLifecyclePart2NbtKeys.KEY_ID));
            double x = film.getDouble(MaidLifecyclePart2NbtKeys.KEY_X),
                y = film.getDouble(MaidLifecyclePart2NbtKeys.KEY_Y),
                z = film.getDouble(MaidLifecyclePart2NbtKeys.KEY_Z);
            if (restored.containsKey(id) || restoredFilms.containsKey(id)
                || !Double.isFinite(x)
                || !Double.isFinite(y)
                || !Double.isFinite(z)
                || !film.hasKey(MaidLifecyclePart2NbtKeys.KEY_STACK, Constants.NBT.TAG_COMPOUND)
                || !film.hasKey(MaidLifecyclePart2NbtKeys.KEY_ID, Constants.NBT.TAG_STRING)
                || !film.hasKey(MaidLifecyclePart2NbtKeys.KEY_DIMENSION, Constants.NBT.TAG_INT)
                || !film.hasKey(MaidLifecyclePart2NbtKeys.KEY_X, Constants.NBT.TAG_DOUBLE)
                || !film.hasKey(MaidLifecyclePart2NbtKeys.KEY_Y, Constants.NBT.TAG_DOUBLE)
                || !film.hasKey(MaidLifecyclePart2NbtKeys.KEY_Z, Constants.NBT.TAG_DOUBLE))
                throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_INVALID_RETURNED_FILM_ESCROW);
            var entry = new ReturnedFilm(
                (NBTTagCompound) film.getCompoundTag(MaidLifecyclePart2NbtKeys.KEY_STACK)
                    .copy(),
                film.getInteger(MaidLifecyclePart2NbtKeys.KEY_DIMENSION),
                x,
                y,
                z);
            if (schema >= MaidLifecycleTuning.MAX_SCHEMA_THRESHOLD_2) {
                if (!film.hasKey(MaidLifecyclePart2NbtKeys.KEY_PHASE, Constants.NBT.TAG_STRING)
                    || !film.hasKey(MaidLifecyclePart2NbtKeys.KEY_CONTEXT, Constants.NBT.TAG_COMPOUND))
                    throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_INVALID_RETURNED_MATERIAL_STATE);
                entry.phase = ReturnedPhase.valueOf(film.getString(MaidLifecyclePart2NbtKeys.KEY_PHASE));
                entry.context = (NBTTagCompound) film.getCompoundTag(MaidLifecyclePart2NbtKeys.KEY_CONTEXT)
                    .copy();
                validateReturnContext(entry.context, entry.phase);
            } else if (film.hasKey(MaidLifecyclePart2NbtKeys.KEY_PHASE)
                || film.hasKey(MaidLifecyclePart2NbtKeys.KEY_CONTEXT)) {
                    throw new IllegalStateException(
                        MaidLifecyclePart2Diagnostics.MSG_RETURNED_MATERIAL_STATE_REQUIRES_LIFECYCLE);
                }
            restoredFilms.put(id, entry);
        }
        entries.clear();
        entries.putAll(restored);
        returnedFilms.clear();
        returnedFilms.putAll(restoredFilms);
        reservations.clear();
        tombstoneAttempts.clear();
        experienceAttempts.clear();
        acquisitionAttempts.clear();
        redemptionAttempts.clear();
        returnedAttempts.clear();
        deathAttempts.clear();
        tombstonePublications.clear();
        redemptionCandidates.clear();
        redemptionCandidates.putAll(candidates);
        captureAttempts.clear();
        captureHeads.clear();
        captureHeads.putAll(restoredCaptureHeads);
        for (var item : restored.entrySet()) if (item.getValue().kind == Kind.CAPTURED && !item.getKey()
            .equals(captureHeads.get(item.getValue().subject)) && !item.getValue().consumed)
            throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_UNSETTLED_OLDER_MAID_CAPTURE_GENERATION);
        experienceCursors.clear();
        healthy = true;
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        if (!healthy)
            throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_REFUSING_TO_OVERWRITE_INVALID_MAID);
        tag.setInteger(MaidLifecyclePart2NbtKeys.KEY_SCHEMA, MaidLifecycleTuning.MAX_SCHEMA_THRESHOLD);
        var list = new NBTTagList();
        for (var item : entries.entrySet()) {
            var entry = item.getValue();
            var data = new NBTTagCompound();
            data.setString(
                MaidLifecyclePart2NbtKeys.KEY_TOKEN,
                item.getKey()
                    .toString());
            data.setString(MaidLifecyclePart2NbtKeys.KEY_OWNER, entry.owner.toString());
            data.setString(MaidLifecyclePart2NbtKeys.KEY_SUBJECT, entry.subject.toString());
            data.setString(MaidLifecyclePart2NbtKeys.KEY_KIND, entry.kind.name());
            data.setBoolean(MaidLifecyclePart2NbtKeys.KEY_CONSUMED, entry.consumed);
            data.setBoolean(MaidLifecyclePart2NbtKeys.KEY_DELIVERED_2, entry.delivered);
            data.setTag(MaidLifecyclePart2NbtKeys.KEY_IDENTITY, entry.identity.copy());
            data.setTag(MaidLifecyclePart2NbtKeys.KEY_TOMBSTONE_ITEMS, entry.tombstoneItems.copy());
            data.setInteger(MaidLifecyclePart2NbtKeys.KEY_DIMENSION, entry.dimension);
            data.setDouble(MaidLifecyclePart2NbtKeys.KEY_X, entry.x);
            data.setDouble(MaidLifecyclePart2NbtKeys.KEY_Y, entry.y);
            data.setDouble(MaidLifecyclePart2NbtKeys.KEY_Z, entry.z);
            data.setString(MaidLifecyclePart2NbtKeys.KEY_MAID_NAME, entry.name);
            data.setBoolean(MaidLifecyclePart2NbtKeys.KEY_PUBLISHED_2, entry.published);
            data.setInteger(MaidLifecyclePart2NbtKeys.KEY_DEATH_EXPERIENCE, entry.experience);
            data.setLong(MaidLifecyclePart2NbtKeys.KEY_EXPERIENCE_READY_AT, entry.experienceReadyAt);
            data.setBoolean(MaidLifecyclePart2NbtKeys.KEY_TOMBSTONE_HELD, entry.tombstoneHeld);
            data.setBoolean(MaidLifecyclePart2NbtKeys.KEY_EXPERIENCE_HELD, entry.experienceHeld);
            data.setString(MaidLifecyclePart2NbtKeys.KEY_ACQUISITION_PHASE, entry.acquisitionPhase.name());
            data.setTag(MaidLifecyclePart2NbtKeys.KEY_ACQUISITION_SOURCE, entry.acquisitionSource.copy());
            data.setTag(MaidLifecyclePart2NbtKeys.KEY_ACQUISITION_RECEIPT, entry.acquisitionReceipt.copy());
            data.setTag(MaidLifecyclePart2NbtKeys.KEY_ACQUISITION_CREDENTIAL, entry.acquisitionCredential.copy());
            data.setTag(MaidLifecyclePart2NbtKeys.KEY_REDEMPTION, entry.redemption.copy());
            data.setTag(MaidLifecyclePart2NbtKeys.KEY_DEATH_INGRESS, entry.deathIngress.copy());
            data.setTag(MaidLifecyclePart2NbtKeys.KEY_TOMBSTONE_PUBLICATION, entry.tombstonePublication.copy());
            data.setTag(MaidLifecyclePart2NbtKeys.KEY_CAPTURE, entry.capture.copy());
            list.appendTag(data);
        }
        tag.setTag(MaidLifecyclePart2NbtKeys.KEY_ENTRIES, list);
        var films = new NBTTagList();
        for (var item : returnedFilms.entrySet()) {
            var film = new NBTTagCompound();
            var entry = item.getValue();
            film.setString(
                MaidLifecyclePart2NbtKeys.KEY_ID,
                item.getKey()
                    .toString());
            film.setTag(MaidLifecyclePart2NbtKeys.KEY_STACK, entry.stack.copy());
            film.setInteger(MaidLifecyclePart2NbtKeys.KEY_DIMENSION, entry.dimension);
            film.setDouble(MaidLifecyclePart2NbtKeys.KEY_X, entry.x);
            film.setDouble(MaidLifecyclePart2NbtKeys.KEY_Y, entry.y);
            film.setDouble(MaidLifecyclePart2NbtKeys.KEY_Z, entry.z);
            film.setString(MaidLifecyclePart2NbtKeys.KEY_PHASE, entry.phase.name());
            film.setTag(MaidLifecyclePart2NbtKeys.KEY_CONTEXT, entry.context.copy());
            films.appendTag(film);
        }
        tag.setTag(MaidLifecyclePart2NbtKeys.KEY_RETURNED_FILMS, films);
    }

    private static boolean readHold(NBTTagCompound entry, String key, int schema) {
        return readFlag(entry, key, schema >= MaidLifecycleTuning.CHUNK_SECTION_SHIFT);
    }

    private static void validateReturnContext(NBTTagCompound context, ReturnedPhase phase) {
        if (context.hasNoTags() && phase != ReturnedPhase.PREPARED) return;
        knownFields(context, RETURN_CONTEXT_FIELDS);
        if (context.func_150296_c()
            .size() != RETURN_CONTEXT_FIELDS.size()
            || !context.hasKey(MaidLifecyclePart2NbtKeys.KEY_KIND, Constants.NBT.TAG_STRING)
            || !context.hasKey(MaidLifecyclePart2NbtKeys.KEY_SOURCE, Constants.NBT.TAG_COMPOUND)
            || context.getCompoundTag(MaidLifecyclePart2NbtKeys.KEY_SOURCE)
                .hasNoTags()
            || !Arrays
                .asList(
                    MaidLifecycleIds.ID_TILE_BREAK,
                    MaidLifecycleIds.ID_SHRINE_INSERT,
                    MaidLifecycleIds.ID_SHRINE_EXTRACT,
                    MaidLifecycleIds.ID_RECOVERY_PLAYER)
                .contains(context.getString(MaidLifecyclePart2NbtKeys.KEY_KIND)))
            throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_UNKNOWN_RETURNED_SOURCE_CONTEXT_RETAINED);
    }

    private static boolean readFlag(NBTTagCompound entry, String key, boolean required) {
        if (required && !entry.hasKey(key, Constants.NBT.TAG_BYTE)
            || entry.hasKey(key) && (!entry.hasKey(key, Constants.NBT.TAG_BYTE) || entry.getByte(key) < 0
                || entry.getByte(key) > MaidLifecycleTuning.TARGET_STACK_SIZE_THRESHOLD))
            throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_INVALID_LIFECYCLE_FLAG_ENCODING + key);
        return entry.getBoolean(key);
    }

    private static void readAcquisition(Entry entry, NBTTagCompound data, int schema) {
        String[] keys = { MaidLifecyclePart2NbtKeys.KEY_ACQUISITION_PHASE,
            MaidLifecyclePart2NbtKeys.KEY_ACQUISITION_SOURCE, MaidLifecyclePart2NbtKeys.KEY_ACQUISITION_RECEIPT,
            MaidLifecyclePart2NbtKeys.KEY_ACQUISITION_CREDENTIAL };
        if (schema < MaidLifecycleTuning.DEATH_EXPERIENCE_BLOCK_EXISTS_SECOND_PARAM) {
            for (String key : keys) if (data.hasKey(key)) throw new IllegalStateException(
                MaidLifecyclePart2Diagnostics.MSG_ACQUISITION_INTENT_REQUIRES_LIFECYCLE_SCHEMA);
            return;
        }
        if (!data.hasKey(keys[MaidLifecycleTuning.CORE_INITIAL_VALUE], Constants.NBT.TAG_STRING)
            || !data.hasKey(keys[MaidLifecycleTuning.TARGET_STACK_SIZE_THRESHOLD], Constants.NBT.TAG_COMPOUND)
            || !data.hasKey(keys[MaidLifecycleTuning.DEATH_INGRESS_STATE_PARAM], Constants.NBT.TAG_COMPOUND)
            || !data.hasKey(keys[MaidLifecycleTuning.TARGET_SIZE_THRESHOLD], Constants.NBT.TAG_COMPOUND))
            throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_INVALID_ACQUISITION_INTENT_ENCODING);
        entry.acquisitionPhase = AcquisitionPhase.valueOf(data.getString(keys[MaidLifecycleTuning.CORE_INITIAL_VALUE]));
        entry.acquisitionSource = (NBTTagCompound) data
            .getCompoundTag(keys[MaidLifecycleTuning.TARGET_STACK_SIZE_THRESHOLD])
            .copy();
        entry.acquisitionReceipt = (NBTTagCompound) data
            .getCompoundTag(keys[MaidLifecycleTuning.DEATH_INGRESS_STATE_PARAM])
            .copy();
        entry.acquisitionCredential = (NBTTagCompound) data
            .getCompoundTag(keys[MaidLifecycleTuning.TARGET_SIZE_THRESHOLD])
            .copy();
        boolean source = !entry.acquisitionSource.hasNoTags(), receipt = !entry.acquisitionReceipt.hasNoTags();
        boolean credential = !entry.acquisitionCredential.hasNoTags();
        if ((entry.kind == Kind.FILM || entry.kind == Kind.CAPTURED)
            && (entry.acquisitionPhase != AcquisitionPhase.NONE || source || receipt || credential)
            || entry.kind == Kind.INITIAL && (source || receipt || entry.acquisitionPhase == AcquisitionPhase.PREPARED)
            || source != receipt
            || source && !credential
            || entry.kind == Kind.ACQUIRED && (source || receipt || credential) && !(source && receipt && credential)
            || entry.acquisitionPhase == AcquisitionPhase.NONE && credential && !entry.delivered && !entry.consumed
            || entry.acquisitionPhase != AcquisitionPhase.NONE && (!credential || entry.delivered || entry.consumed)
            || entry.kind == Kind.ACQUIRED && entry.acquisitionPhase != AcquisitionPhase.NONE && !source)
            throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_INVALID_ACQUISITION_INTENT_STATE);
    }

    private static Set<String> fields(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    private static NBTTagList ownedList(NBTTagCompound data, String key, boolean required) {
        if (!data.hasKey(key)) {
            if (required)
                throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_MISSING_LIFECYCLE_LIST + key);
            return new NBTTagList();
        }
        if (!data.hasKey(key, Constants.NBT.TAG_LIST))
            throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_INVALID_LIFECYCLE_LIST_ENCODING + key);
        var typed = data.getTagList(key, Constants.NBT.TAG_COMPOUND);
        if (((NBTTagList) data.getTag(key)).tagCount() != typed.tagCount())
            throw new IllegalStateException(MaidLifecyclePart2Diagnostics.MSG_INVALID_LIFECYCLE_LIST_ENTRIES + key);
        return typed;
    }

    private static void knownFields(NBTTagCompound data, Set<String> known) {
        for (Object key : data.func_150296_c()) if (!known.contains(key)) throw new IllegalStateException(
            MaidLifecyclePart2Diagnostics.MSG_UNKNOWN_LIFECYCLE_FIELD_PRESERVED_WITHOUT + key);
    }

    private static final class Entry {

        private final Kind kind;
        private final UUID owner;
        private final UUID subject;
        private final NBTTagCompound identity;
        private boolean consumed;
        private boolean delivered;
        private NBTTagList tombstoneItems = new NBTTagList();
        private int dimension;
        private double x;
        private double y;
        private double z;
        private String name = "";
        private boolean published;
        private int experience;
        private long experienceReadyAt;
        private boolean tombstoneHeld, experienceHeld;
        private AcquisitionPhase acquisitionPhase = AcquisitionPhase.NONE;
        private NBTTagCompound acquisitionSource = new NBTTagCompound(), acquisitionReceipt = new NBTTagCompound(),
            acquisitionCredential = new NBTTagCompound();
        private NBTTagCompound redemption = new NBTTagCompound();
        private NBTTagCompound capture = new NBTTagCompound();
        private NBTTagCompound deathIngress = new NBTTagCompound(), tombstonePublication = new NBTTagCompound();

        private Entry(Kind kind, UUID owner, UUID subject, NBTTagCompound identity, boolean consumed,
            boolean delivered) {
            this.kind = kind;
            this.owner = owner;
            this.subject = subject;
            this.identity = identity;
            this.consumed = consumed;
            this.delivered = delivered;
        }
    }

    private static final class ReturnedFilm {

        private final NBTTagCompound stack;
        private final int dimension;
        private final double x, y, z;
        private ReturnedPhase phase = ReturnedPhase.READY;
        private NBTTagCompound context = new NBTTagCompound();

        private ReturnedFilm(NBTTagCompound stack, int dimension, double x, double y, double z) {
            this.stack = stack;
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
