package com.tianluoqaq.tlmnh.common.lifecycle;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;

/** 世界共享的一次性凭证账本；物品副本没有第二次兑现权 */
public final class MaidLifecycleData extends WorldSavedData {

    public static final String NAME = "TLMNH_Lifecycle";

    public enum Kind {
        INITIAL,
        FILM
    }

    private final Map<UUID, Entry> entries = new HashMap<>();
    private final Map<UUID, ReturnedFilm> returnedFilms = new HashMap<>();
    private final Set<UUID> reservations = new HashSet<>();
    private boolean healthy = true;

    public MaidLifecycleData(String name) {
        super(name);
    }

    public static MaidLifecycleData get(World world) {
        if (world == null || world.isRemote) throw new IllegalArgumentException("Server world required");
        var data = (MaidLifecycleData) world.mapStorage.loadData(MaidLifecycleData.class, NAME);
        if (data == null) {
            // MapStorage会吞读取异常，已有文件读坏时不能当新世界再次发放
            var file = world.getSaveHandler()
                .getMapFileFromName(NAME);
            if (file != null && file.exists())
                throw new IllegalStateException("Cannot read existing maid lifecycle data");
            data = new MaidLifecycleData(NAME);
            world.mapStorage.setData(NAME, data);
            data.markDirty();
        }
        if (!data.healthy) throw new IllegalStateException("Invalid maid lifecycle data; recovery required");
        return data;
    }

    public UUID initialToken(UUID owner) {
        for (var entry : entries.entrySet()) {
            if (entry.getValue().kind == Kind.INITIAL && entry.getValue().owner.equals(owner)) return entry.getKey();
        }
        return add(Kind.INITIAL, owner, owner, new NBTTagCompound());
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
            entry.delivered = true;
            markDirty();
        }
        return token;
    }

    public java.util.List<UUID> pendingTombstones(int dimension) {
        var result = new java.util.ArrayList<UUID>();
        for (var item : entries.entrySet()) {
            var entry = item.getValue();
            if (entry.kind == Kind.FILM && entry.delivered && !entry.published && entry.dimension == dimension)
                result.add(item.getKey());
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
        entries.get(token).published = true;
        markDirty();
    }

    public NBTTagList reserveTombstone(UUID token) {
        var entry = entries.get(token);
        if (entry == null || entry.kind != Kind.FILM
            || !entry.delivered
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
        var entry = entries.get(token);
        if (entry == null || !reservations.remove(token)) throw new IllegalStateException("No tombstone reservation");
        entry.tombstoneItems = (NBTTagList) remainder.copy();
        markDirty();
    }

    private UUID add(Kind kind, UUID owner, UUID subject, NBTTagCompound identity) {
        if (owner == null || subject == null || identity == null)
            throw new IllegalArgumentException("Missing lifecycle identity");
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
        if (entry == null || entry.kind != Kind.INITIAL) throw new IllegalArgumentException("Unknown initial token");
        entry.delivered = true;
        markDirty();
    }

    /** 返回独立快照，同时阻止出生事件回调重入；保留状态仅存在本次服务端调用栈 */
    public NBTTagCompound reserve(UUID token, Kind kind) {
        var entry = entries.get(token);
        if (entry == null || entry.kind != kind || entry.consumed || !reservations.add(token)) return null;
        return (NBTTagCompound) entry.identity.copy();
    }

    public void commit(UUID token) {
        var entry = entries.get(token);
        if (entry == null || !reservations.remove(token)) throw new IllegalStateException("No lifecycle reservation");
        entry.consumed = true;
        markDirty();
    }

    public void rollback(UUID token) {
        reservations.remove(token);
    }

    public UUID queueReturnedFilm(NBTTagCompound stack, int dimension, double x, double y, double z) {
        UUID id;
        do id = UUID.randomUUID(); while (entries.containsKey(id) || returnedFilms.containsKey(id));
        returnedFilms.put(id, new ReturnedFilm((NBTTagCompound) stack.copy(), dimension, x, y, z));
        markDirty();
        return id;
    }

    public java.util.List<UUID> pendingReturnedFilms(int dimension) {
        var result = new java.util.ArrayList<UUID>();
        for (var item : returnedFilms.entrySet()) if (item.getValue().dimension == dimension) result.add(item.getKey());
        return result;
    }

    public NBTTagCompound reserveReturnedFilm(UUID id) {
        var entry = returnedFilms.get(id);
        return entry != null && reservations.add(id) ? (NBTTagCompound) entry.stack.copy() : null;
    }

    public Vec3 returnedFilmPosition(UUID id) {
        var entry = returnedFilms.get(id);
        return entry == null ? null : Vec3.createVectorHelper(entry.x, entry.y, entry.z);
    }

    public void finishReturnedFilm(UUID id) {
        if (!reservations.remove(id) || returnedFilms.remove(id) == null)
            throw new IllegalStateException("No returned film reservation");
        markDirty();
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        healthy = false;
        if (tag.getInteger("Schema") != 1) throw new IllegalStateException("Unsupported maid lifecycle schema");
        var restored = new HashMap<UUID, Entry>();
        var list = tag.getTagList("Entries", 10);
        var initialOwners = new HashSet<UUID>();
        var deadMaids = new HashSet<UUID>();
        for (int i = 0; i < list.tagCount(); i++) {
            var item = list.getCompoundTagAt(i);
            var token = UUID.fromString(item.getString("Token"));
            var owner = UUID.fromString(item.getString("Owner"));
            var subject = UUID.fromString(item.getString("Subject"));
            var kind = Kind.valueOf(item.getString("Kind"));
            if (restored.containsKey(token)
                || !(kind == Kind.INITIAL ? initialOwners.add(owner) : deadMaids.add(subject)))
                throw new IllegalStateException("Duplicate maid lifecycle identity");
            var entry = new Entry(
                kind,
                owner,
                subject,
                (NBTTagCompound) item.getCompoundTag("Identity")
                    .copy(),
                item.getBoolean("Consumed"),
                item.getBoolean("Delivered"));
            entry.tombstoneItems = (NBTTagList) item.getTagList("TombstoneItems", 10)
                .copy();
            entry.dimension = item.getInteger("Dimension");
            entry.x = item.getDouble("X");
            entry.y = item.getDouble("Y");
            entry.z = item.getDouble("Z");
            entry.name = item.getString("MaidName");
            entry.published = item.getBoolean("Published");
            if (!Double.isFinite(entry.x) || !Double.isFinite(entry.y) || !Double.isFinite(entry.z))
                throw new IllegalStateException("Invalid tombstone position");
            restored.put(token, entry);
        }
        var restoredFilms = new HashMap<UUID, ReturnedFilm>();
        var films = tag.getTagList("ReturnedFilms", 10);
        for (int i = 0; i < films.tagCount(); i++) {
            var film = films.getCompoundTagAt(i);
            var id = UUID.fromString(film.getString("Id"));
            double x = film.getDouble("X"), y = film.getDouble("Y"), z = film.getDouble("Z");
            if (restored.containsKey(id) || restoredFilms.containsKey(
                id) || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !film.hasKey("Stack", 10))
                throw new IllegalStateException("Invalid returned film escrow");
            restoredFilms.put(
                id,
                new ReturnedFilm(
                    (NBTTagCompound) film.getCompoundTag("Stack")
                        .copy(),
                    film.getInteger("Dimension"),
                    x,
                    y,
                    z));
        }
        entries.clear();
        entries.putAll(restored);
        returnedFilms.clear();
        returnedFilms.putAll(restoredFilms);
        reservations.clear();
        healthy = true;
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        if (!healthy) throw new IllegalStateException("Refusing to overwrite invalid maid lifecycle data");
        tag.setInteger("Schema", 1);
        var list = new NBTTagList();
        for (var item : entries.entrySet()) {
            var entry = item.getValue();
            var data = new NBTTagCompound();
            data.setString(
                "Token",
                item.getKey()
                    .toString());
            data.setString("Owner", entry.owner.toString());
            data.setString("Subject", entry.subject.toString());
            data.setString("Kind", entry.kind.name());
            data.setBoolean("Consumed", entry.consumed);
            data.setBoolean("Delivered", entry.delivered);
            data.setTag("Identity", entry.identity.copy());
            data.setTag("TombstoneItems", entry.tombstoneItems.copy());
            data.setInteger("Dimension", entry.dimension);
            data.setDouble("X", entry.x);
            data.setDouble("Y", entry.y);
            data.setDouble("Z", entry.z);
            data.setString("MaidName", entry.name);
            data.setBoolean("Published", entry.published);
            list.appendTag(data);
        }
        tag.setTag("Entries", list);
        var films = new NBTTagList();
        for (var item : returnedFilms.entrySet()) {
            var film = new NBTTagCompound();
            var entry = item.getValue();
            film.setString(
                "Id",
                item.getKey()
                    .toString());
            film.setTag("Stack", entry.stack.copy());
            film.setInteger("Dimension", entry.dimension);
            film.setDouble("X", entry.x);
            film.setDouble("Y", entry.y);
            film.setDouble("Z", entry.z);
            films.appendTag(film);
        }
        tag.setTag("ReturnedFilms", films);
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

        private ReturnedFilm(NBTTagCompound stack, int dimension, double x, double y, double z) {
            this.stack = stack;
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
