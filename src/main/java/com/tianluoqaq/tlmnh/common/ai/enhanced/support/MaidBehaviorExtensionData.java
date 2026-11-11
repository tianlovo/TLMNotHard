package com.tianluoqaq.tlmnh.common.ai.enhanced.support;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;

import com.tianluoqaq.tlmnh.api.behavior.MaidBehaviorId;

/** 外部行为模块持久化数据校验与隔离合并器；保证异常模块不覆盖既有数据且无扩展时不生成空槽 */
public final class MaidBehaviorExtensionData {

    private static volatile Field nbtTagListField;
    private static volatile boolean nbtTagListFieldResolved;

    public interface ModulePersistentAdapter {

        MaidBehaviorId id();

        boolean isFaulted();

        default boolean isPersistencePaused() {
            return false;
        }

        void writePersistentData(NBTTagCompound candidate);

        void onPersistenceFault(Throwable cause);
    }

    public static final class ReadSlice {

        public final boolean blocked;
        public final String reason;
        public final NBTTagCompound tag;

        private ReadSlice(boolean blocked, String reason, NBTTagCompound tag) {
            this.blocked = blocked;
            this.reason = reason;
            this.tag = tag;
        }

        static ReadSlice ok(NBTTagCompound tag) {
            return new ReadSlice(false, null, tag);
        }

        static ReadSlice blocked(String reason) {
            return new ReadSlice(true, reason, null);
        }
    }

    public static final class ValidationResult {

        public final boolean valid;
        public final String reason;
        public final int serializedBytes;

        private ValidationResult(boolean valid, String reason, int serializedBytes) {
            this.valid = valid;
            this.reason = reason;
            this.serializedBytes = serializedBytes;
        }
    }

    private MaidBehaviorExtensionData() {}

    /** 从 TLMNH 根扩展快照中安全提取单模块独立副本；父键或子键类型异常时返回阻断标记并保留原标签 */
    public static ReadSlice extractModuleTag(NBTTagCompound tlmnhExtension, MaidBehaviorId moduleId) {
        Objects.requireNonNull(moduleId, EnhancedSupportIds.ID_MODULEID);
        if (tlmnhExtension == null || !tlmnhExtension.hasKey(EnhancedSupportNbtKeys.TAG_BEHAVIOR_EXT))
            return ReadSlice.ok(new NBTTagCompound());
        if (!tlmnhExtension.hasKey(EnhancedSupportNbtKeys.TAG_BEHAVIOR_EXT, Constants.NBT.TAG_COMPOUND))
            return ReadSlice.blocked(EnhancedSupportDiagnostics.MSG_NON_COMPOUND_BEHAVIOREXT_TAG);
        var ext = tlmnhExtension.getCompoundTag(EnhancedSupportNbtKeys.TAG_BEHAVIOR_EXT);
        String key = moduleId.toString();
        if (!ext.hasKey(key)) return ReadSlice.ok(new NBTTagCompound());
        if (!ext.hasKey(key, Constants.NBT.TAG_COMPOUND))
            return ReadSlice.blocked(EnhancedSupportDiagnostics.MSG_NON_COMPOUND_MODULE_TAG_FOR + key);
        var rawModuleTag = ext.getCompoundTag(key);
        var validation = validateCandidate(rawModuleTag);
        if (!validation.valid) return ReadSlice.blocked(validation.reason);
        return ReadSlice.ok((NBTTagCompound) rawModuleTag.copy());
    }

    /** 校验模块候选复合标签的嵌套深度、循环引用与序列化字节上限，校验前绝不递归 copy */
    public static ValidationResult validateCandidate(NBTTagCompound candidate) {
        if (candidate == null) return new ValidationResult(
            false,
            EnhancedSupportDiagnostics.MSG_CANDIDATE_MODULE_TAG_IS_NULL,
            EnhancedSupportTuning.ENHANCED_MEAL_NEXT_MEAL);
        String depthError = checkDepth(
            candidate,
            EnhancedSupportTuning.ENHANCED_MEAL_OFFER_UNIT_STEP,
            new IdentityHashMap<>());
        if (depthError != null)
            return new ValidationResult(false, depthError, EnhancedSupportTuning.ENHANCED_MEAL_NEXT_MEAL);
        var counter = new LimitedCountingOutputStream(EnhancedSupportTuning.MAX_MODULE_BYTES);
        try (var dataOut = new DataOutputStream(counter)) {
            CompressedStreamTools.write(candidate, dataOut);
            dataOut.flush();
            return new ValidationResult(true, null, counter.count());
        } catch (QuotaExceededIoException quota) {
            return new ValidationResult(false, quota.getMessage(), counter.count());
        } catch (IOException io) {
            return new ValidationResult(
                false,
                EnhancedSupportDiagnostics.MSG_FAILED_TO_SERIALIZE_MODULE_NBT + io.getMessage(),
                counter.count());
        }
    }

    /** 基于原 TLMNH 根标签副本合入活跃外部模块数据，不原地修改入参，空扩展自动移除父键 */
    public static NBTTagCompound flush(NBTTagCompound baseTlmnhExtension,
        Iterable<? extends ModulePersistentAdapter> adapters) {
        Objects.requireNonNull(adapters, EnhancedSupportIds.ID_ADAPTERS);
        var root = baseTlmnhExtension == null ? new NBTTagCompound() : (NBTTagCompound) baseTlmnhExtension.copy();
        if (root.hasKey(EnhancedSupportNbtKeys.TAG_BEHAVIOR_EXT)
            && !root.hasKey(EnhancedSupportNbtKeys.TAG_BEHAVIOR_EXT, Constants.NBT.TAG_COMPOUND)) {
            return root;
        }
        var ext = root.hasKey(EnhancedSupportNbtKeys.TAG_BEHAVIOR_EXT, Constants.NBT.TAG_COMPOUND)
            ? (NBTTagCompound) root.getCompoundTag(EnhancedSupportNbtKeys.TAG_BEHAVIOR_EXT)
                .copy()
            : new NBTTagCompound();
        for (var adapter : adapters) {
            String key = adapter.id()
                .toString();
            if (ext.hasKey(key) && !ext.hasKey(key, Constants.NBT.TAG_COMPOUND)) continue;
            if (adapter.isFaulted() || adapter.isPersistencePaused()) continue;
            var candidate = new NBTTagCompound();
            try {
                adapter.writePersistentData(candidate);
                var validation = validateCandidate(candidate);
                if (!validation.valid) {
                    adapter.onPersistenceFault(
                        new IllegalStateException(
                            EnhancedSupportDiagnostics.MSG_MODULE + key
                                + EnhancedSupportDiagnostics.MSG_PERSISTENT_NBT_REJECTED
                                + validation.reason));
                    continue;
                }
                if (candidate.hasNoTags()) ext.removeTag(key);
                else ext.setTag(key, candidate.copy());
            } catch (RuntimeException | LinkageError | AssertionError | StackOverflowError failure) {
                adapter.onPersistenceFault(failure);
            }
        }
        if (ext.hasNoTags()) root.removeTag(EnhancedSupportNbtKeys.TAG_BEHAVIOR_EXT);
        else root.setTag(EnhancedSupportNbtKeys.TAG_BEHAVIOR_EXT, ext);
        return root;
    }

    private static String checkDepth(NBTBase tag, int depth, IdentityHashMap<NBTBase, Boolean> activePath) {
        if (depth > EnhancedSupportTuning.MAX_NESTING_DEPTH)
            return EnhancedSupportDiagnostics.MSG_NBT_CONTAINER_NESTING_DEPTH_EXCEEDS
                + EnhancedSupportTuning.MAX_NESTING_DEPTH;
        if (activePath.put(tag, Boolean.TRUE) != null)
            return EnhancedSupportDiagnostics.MSG_CYCLIC_NBT_REFERENCE_DETECTED;
        try {
            if (tag instanceof NBTTagCompound compound) {
                for (Object rawKey : compound.func_150296_c()) {
                    var child = compound.getTag((String) rawKey);
                    if (child instanceof NBTTagCompound || child instanceof NBTTagList) {
                        String err = checkDepth(child, depth + 1, activePath);
                        if (err != null) return err;
                    }
                }
            } else if (tag instanceof NBTTagList list) {
                if (list.tagCount() > 0) {
                    List<?> elements = rawTagListElements(list);
                    if (elements == null)
                        return EnhancedSupportDiagnostics.MSG_NBTTAGLIST_ELEMENT_INSPECTION_UNAVAILABLE;
                    for (Object element : elements) {
                        if (element instanceof NBTTagCompound || element instanceof NBTTagList) {
                            String err = checkDepth((NBTBase) element, depth + 1, activePath);
                            if (err != null) return err;
                        }
                    }
                }
            }
            return null;
        } finally {
            activePath.remove(tag);
        }
    }

    private static List<?> rawTagListElements(NBTTagList list) {
        Field field = resolveTagListField();
        if (field == null) return null;
        try {
            Object raw = field.get(list);
            return raw instanceof List<?>typed ? typed : null;
        } catch (IllegalAccessException | RuntimeException ignored) {
            return null;
        }
    }

    private static Field resolveTagListField() {
        if (nbtTagListFieldResolved) return nbtTagListField;
        synchronized (MaidBehaviorExtensionData.class) {
            if (nbtTagListFieldResolved) return nbtTagListField;
            Field resolved = null;
            for (String candidateName : new String[] { EnhancedSupportIds.ID_TAGLIST,
                EnhancedSupportReflectionSpec.SRG_MEMBER_FIELD_74747_A }) {
                try {
                    Field f = NBTTagList.class.getDeclaredField(candidateName);
                    if (List.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        resolved = f;
                        break;
                    }
                } catch (NoSuchFieldException | RuntimeException ignored) {
                    // try next mapping name
                }
            }
            nbtTagListField = resolved;
            nbtTagListFieldResolved = true;
            return resolved;
        }
    }

    private static final class QuotaExceededIoException extends IOException {

        private static final long serialVersionUID = EnhancedSupportTuning.BEHAVIOR_EXTENSION_SERIAL_VERSION_UID;

        QuotaExceededIoException(int limit) {
            super(EnhancedSupportDiagnostics.MSG_SERIALIZED_MODULE_NBT_EXCEEDS_BYTE + limit);
        }
    }

    private static final class LimitedCountingOutputStream extends OutputStream {

        private final int maxBytes;
        private int written;

        LimitedCountingOutputStream(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        int count() {
            return written;
        }

        @Override
        public void write(int b) throws IOException {
            addBytes(EnhancedSupportTuning.ENHANCED_MEAL_OFFER_UNIT_STEP);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (b == null) throw new NullPointerException(EnhancedSupportIds.ID_B);
            if (off < 0 || len < 0 || off > b.length - len) throw new IndexOutOfBoundsException();
            addBytes(len);
        }

        private void addBytes(int delta) throws IOException {
            if (delta < 0 || maxBytes - written < delta) {
                written = Math
                    .min(maxBytes + 1, written + Math.max(EnhancedSupportTuning.ENHANCED_MEAL_NEXT_MEAL, delta));
                throw new QuotaExceededIoException(maxBytes);
            }
            written += delta;
        }
    }
}
