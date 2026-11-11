package com.tianluoqaq.tlmnh.common.lifecycle.spi;

import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.Constants;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

/** 存档写前屏障、区块区段方块解码与复活体型初始化的窄适配契约 */
public interface LifecycleStorageCompatProvider {

    LifecycleStorageCompatProvider NOOP = new LifecycleStorageCompatProvider() {};

    default void beforeLedgerWrite() throws Exception {}

    default int sectionBlockId(NBTTagCompound section, int index) {
        validateVanillaSection(section, index);
        int value = section.getByteArray(LifecycleSpiNbtKeys.KEY_BLOCKS)[index] & LifecycleSpiTuning.UNSIGNED_BYTE_MASK;
        if (section.hasKey(LifecycleSpiNbtKeys.KEY_ADD)) {
            byte[] add = section.getByteArray(LifecycleSpiNbtKeys.KEY_ADD);
            value |= ((add[index >> LifecycleSpiTuning.LIFECYCLE_STORAGE_BIT_SHIFT_1]
                >> ((index & LifecycleSpiTuning.LIFECYCLE_STORAGE_BIT_SHIFT_1)
                    * LifecycleSpiTuning.LIFECYCLE_STORAGE_SECTION_BLOCK_ID_PARAM))
                & LifecycleSpiTuning.CHUNK_LOCAL_MASK) << LifecycleSpiTuning.SECOND_BYTE_SHIFT;
        }
        return value;
    }

    default int sectionMetadata(NBTTagCompound section, int index) {
        validateVanillaSection(section, index);
        byte[] data = section.getByteArray(LifecycleSpiNbtKeys.KEY_DATA);
        return (data[index >> LifecycleSpiTuning.LIFECYCLE_STORAGE_BIT_SHIFT_1]
            >> ((index & LifecycleSpiTuning.LIFECYCLE_STORAGE_BIT_SHIFT_1)
                * LifecycleSpiTuning.LIFECYCLE_STORAGE_SECTION_BLOCK_ID_PARAM))
            & LifecycleSpiTuning.CHUNK_LOCAL_MASK;
    }

    default void prepareInitialSize(EntityMaid maid, UUID actor) {}

    static void validateVanillaSection(NBTTagCompound section, int index) {
        if (section == null || index < 0 || index >= LifecycleSpiTuning.MAX_INDEX_THRESHOLD)
            throw new IllegalArgumentException(LifecycleSpiDiagnostics.MSG_NATIVE_SECTION_AND_INDEX_4095);
        if (!section.hasKey(LifecycleSpiNbtKeys.KEY_BLOCKS, Constants.NBT.TAG_BYTE_ARRAY)
            || section.getByteArray(LifecycleSpiNbtKeys.KEY_BLOCKS).length != LifecycleSpiTuning.MAX_INDEX_THRESHOLD
            || !section.hasKey(LifecycleSpiNbtKeys.KEY_DATA, Constants.NBT.TAG_BYTE_ARRAY)
            || section.getByteArray(LifecycleSpiNbtKeys.KEY_DATA).length != LifecycleSpiTuning.TARGET_LENGTH_THRESHOLD)
            throw new IllegalStateException(LifecycleSpiDiagnostics.MSG_INVALID_NATIVE_SECTION_ARRAY);
        if (section.hasKey(LifecycleSpiNbtKeys.KEY_ADD)
            && (!section.hasKey(LifecycleSpiNbtKeys.KEY_ADD, Constants.NBT.TAG_BYTE_ARRAY)
                || section.getByteArray(LifecycleSpiNbtKeys.KEY_ADD).length
                    != LifecycleSpiTuning.TARGET_LENGTH_THRESHOLD))
            throw new IllegalStateException(LifecycleSpiDiagnostics.MSG_INVALID_NATIVE_ADD_SECTION_ARRAY);
        if (section.hasKey(LifecycleSpiNbtKeys.KEY_BLOCKS_B2_HI) || section.hasKey(LifecycleSpiNbtKeys.KEY_BLOCKS_B3)
            || section.hasKey(LifecycleSpiNbtKeys.KEY_DATA1_HIGH)
            || section.hasKey(LifecycleSpiNbtKeys.KEY_DATA2)
            || section.hasKey(LifecycleSpiNbtKeys.KEY_PALETTE)
            || section.hasKey(LifecycleSpiNbtKeys.KEY_BLOCK_STATES))
            throw new IllegalStateException(LifecycleSpiDiagnostics.MSG_EXTENDED_SECTION_DATA_HAS_NO);
    }
}
