package com.tianluoqaq.tlmnh.common.work.bell;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/**
 * 维护铃铛纯数据编解码与非连续区块跨度校验：
 * 最多绑定 16 个区块，允许非相邻离散区块，但包围盒跨度需满足 max(cx)-min(cx)<=8 且 max(cz)-min(cz)<=8
 */
public final class MaintenanceBellData {

    public enum ToggleOutcome {
        BOUND,
        UNBOUND,
        CLEARED_ALL,
        REJECTED_WRONG_DIMENSION,
        REJECTED_CHUNK_LIMIT,
        REJECTED_SPAN_EXCEEDED
    }

    public static final class ToggleResult {

        public final ToggleOutcome outcome;
        public final int boundCount;
        public final int spanX;
        public final int spanZ;

        public ToggleResult(ToggleOutcome outcome, int boundCount, int spanX, int spanZ) {
            this.outcome = outcome;
            this.boundCount = boundCount;
            this.spanX = spanX;
            this.spanZ = spanZ;
        }
    }

    private final boolean bound;
    private final int dimension;
    private final List<int[]> chunks;

    private MaintenanceBellData(boolean bound, int dimension, List<int[]> chunks) {
        this.bound = bound;
        this.dimension = dimension;
        this.chunks = chunks;
    }

    public static MaintenanceBellData unbound() {
        return new MaintenanceBellData(false, BellTuning.ZERO, Collections.emptyList());
    }

    public boolean isBound() {
        return bound && !chunks.isEmpty();
    }

    public int dimension() {
        return dimension;
    }

    public List<int[]> chunks() {
        return chunks;
    }

    public int chunkCount() {
        return chunks.size();
    }

    public boolean containsChunk(int chunkX, int chunkZ) {
        for (int[] pair : chunks) {
            if (pair[BellTuning.IDX_X] == chunkX && pair[BellTuning.IDX_Z] == chunkZ) {
                return true;
            }
        }
        return false;
    }

    public static MaintenanceBellData read(ItemStack stack) {
        if (stack == null || !stack.hasTagCompound()) return unbound();
        return readFromRoot(stack.getTagCompound());
    }

    public static MaintenanceBellData readFromRoot(NBTTagCompound itemTag) {
        if (itemTag == null || !itemTag.hasKey(BellNbtKeys.ROOT_MAINTENANCE, BellTuning.NBT_COMPOUND_ID)) {
            return unbound();
        }
        NBTTagCompound root = itemTag.getCompoundTag(BellNbtKeys.ROOT_MAINTENANCE);
        if (!root.hasKey(BellNbtKeys.CHUNKS)) return unbound();
        int dim = root.getInteger(BellNbtKeys.DIMENSION);
        NBTTagList list = root.getTagList(BellNbtKeys.CHUNKS, BellTuning.NBT_COMPOUND_ID);
        if (list.tagCount() <= 0) return unbound();
        List<int[]> parsed = new ArrayList<>(Math.min(list.tagCount(), BellTuning.MAX_BOUND_CHUNKS));
        for (int i = 0; i < list.tagCount() && parsed.size() < BellTuning.MAX_BOUND_CHUNKS; i++) {
            NBTTagCompound entry = list.getCompoundTagAt(i);
            int cx = entry.getInteger(BellNbtKeys.CHUNK_X);
            int cz = entry.getInteger(BellNbtKeys.CHUNK_Z);
            boolean duplicate = false;
            for (int[] existing : parsed) {
                if (existing[BellTuning.IDX_X] == cx && existing[BellTuning.IDX_Z] == cz) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                int[] pair = new int[BellTuning.PAIR_LEN];
                pair[BellTuning.IDX_X] = cx;
                pair[BellTuning.IDX_Z] = cz;
                parsed.add(pair);
            }
        }
        if (parsed.isEmpty()) return unbound();
        return new MaintenanceBellData(true, dim, Collections.unmodifiableList(parsed));
    }

    public static ToggleResult toggleChunk(ItemStack stack, int worldDimension, int targetChunkX, int targetChunkZ) {
        if (stack == null) {
            return new ToggleResult(
                ToggleOutcome.REJECTED_CHUNK_LIMIT,
                BellTuning.ZERO,
                BellTuning.ZERO,
                BellTuning.ZERO);
        }
        NBTTagCompound itemTag = stack.getTagCompound();
        if (itemTag == null) {
            itemTag = new NBTTagCompound();
            stack.setTagCompound(itemTag);
        }
        ToggleResult res = toggleChunkOnRoot(itemTag, worldDimension, targetChunkX, targetChunkZ);
        if (itemTag.hasNoTags()) {
            stack.setTagCompound(null);
        }
        return res;
    }

    public static ToggleResult toggleChunkOnRoot(NBTTagCompound itemTag, int worldDimension, int targetChunkX,
        int targetChunkZ) {
        MaintenanceBellData current = readFromRoot(itemTag);
        if (current.isBound() && current.dimension != worldDimension) {
            return new ToggleResult(
                ToggleOutcome.REJECTED_WRONG_DIMENSION,
                current.chunkCount(),
                BellTuning.ZERO,
                BellTuning.ZERO);
        }

        List<int[]> updated = new ArrayList<>(current.chunks.size() + BellTuning.ONE);
        boolean removed = false;
        for (int[] existing : current.chunks) {
            if (existing[BellTuning.IDX_X] == targetChunkX && existing[BellTuning.IDX_Z] == targetChunkZ) {
                removed = true;
            } else {
                int[] copy = new int[BellTuning.PAIR_LEN];
                copy[BellTuning.IDX_X] = existing[BellTuning.IDX_X];
                copy[BellTuning.IDX_Z] = existing[BellTuning.IDX_Z];
                updated.add(copy);
            }
        }

        if (removed) {
            if (updated.isEmpty()) {
                itemTag.removeTag(BellNbtKeys.ROOT_MAINTENANCE);
                return new ToggleResult(ToggleOutcome.CLEARED_ALL, BellTuning.ZERO, BellTuning.ZERO, BellTuning.ZERO);
            }
            writeToRoot(itemTag, worldDimension, updated);
            int[] spans = computeSpans(updated);
            return new ToggleResult(
                ToggleOutcome.UNBOUND,
                updated.size(),
                spans[BellTuning.IDX_X],
                spans[BellTuning.IDX_Z]);
        }

        if (updated.size() >= BellTuning.MAX_BOUND_CHUNKS) {
            int[] spans = computeSpans(updated);
            return new ToggleResult(
                ToggleOutcome.REJECTED_CHUNK_LIMIT,
                updated.size(),
                spans[BellTuning.IDX_X],
                spans[BellTuning.IDX_Z]);
        }

        int[] added = new int[BellTuning.PAIR_LEN];
        added[BellTuning.IDX_X] = targetChunkX;
        added[BellTuning.IDX_Z] = targetChunkZ;
        updated.add(added);
        int[] candidateSpans = computeSpans(updated);
        if (candidateSpans[BellTuning.IDX_X] > BellTuning.MAX_CHUNK_SPAN
            || candidateSpans[BellTuning.IDX_Z] > BellTuning.MAX_CHUNK_SPAN) {
            return new ToggleResult(
                ToggleOutcome.REJECTED_SPAN_EXCEEDED,
                current.chunkCount(),
                candidateSpans[BellTuning.IDX_X],
                candidateSpans[BellTuning.IDX_Z]);
        }

        writeToRoot(itemTag, worldDimension, updated);
        return new ToggleResult(
            ToggleOutcome.BOUND,
            updated.size(),
            candidateSpans[BellTuning.IDX_X],
            candidateSpans[BellTuning.IDX_Z]);
    }

    public static void clear(ItemStack stack) {
        if (stack == null || !stack.hasTagCompound()) return;
        NBTTagCompound tag = stack.getTagCompound();
        tag.removeTag(BellNbtKeys.ROOT_MAINTENANCE);
        if (tag.hasNoTags()) {
            stack.setTagCompound(null);
        }
    }

    private static int[] computeSpans(List<int[]> list) {
        int[] out = new int[BellTuning.PAIR_LEN];
        if (list.isEmpty()) return out;
        int minX = list.get(BellTuning.ZERO)[BellTuning.IDX_X];
        int maxX = minX;
        int minZ = list.get(BellTuning.ZERO)[BellTuning.IDX_Z];
        int maxZ = minZ;
        for (int i = BellTuning.ONE; i < list.size(); i++) {
            int cx = list.get(i)[BellTuning.IDX_X];
            int cz = list.get(i)[BellTuning.IDX_Z];
            if (cx < minX) minX = cx;
            if (cx > maxX) maxX = cx;
            if (cz < minZ) minZ = cz;
            if (cz > maxZ) maxZ = cz;
        }
        out[BellTuning.IDX_X] = maxX - minX;
        out[BellTuning.IDX_Z] = maxZ - minZ;
        return out;
    }

    private static void writeToRoot(NBTTagCompound itemTag, int dimension, List<int[]> chunks) {
        NBTTagCompound root = new NBTTagCompound();
        root.setInteger(BellNbtKeys.DIMENSION, dimension);
        NBTTagList list = new NBTTagList();
        for (int[] pair : chunks) {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setInteger(BellNbtKeys.CHUNK_X, pair[BellTuning.IDX_X]);
            entry.setInteger(BellNbtKeys.CHUNK_Z, pair[BellTuning.IDX_Z]);
            list.appendTag(entry);
        }
        root.setTag(BellNbtKeys.CHUNKS, list);
        itemTag.setTag(BellNbtKeys.ROOT_MAINTENANCE, root);
    }
}
