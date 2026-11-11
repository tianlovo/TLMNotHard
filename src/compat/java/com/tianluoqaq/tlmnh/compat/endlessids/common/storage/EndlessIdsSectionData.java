package com.tianluoqaq.tlmnh.compat.endlessids.common.storage;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.Constants;

import com.tianluoqaq.tlmnh.common.protection.DependencyFingerprint;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.compat.endlessids.EndlessIdsCompat;

import cpw.mods.fml.relauncher.Side;

/**
 * Read-only decoding of the target EndlessIDs 1.7.4 section format. Field names and shifts follow
 * BlockIDManager/BlockMetaManager and ExtendedBlockStorageMixin; no section or live world is rewritten.
 */
public final class EndlessIdsSectionData {




    private EndlessIdsSectionData() {}

    public static int blockId(NBTTagCompound section, int index) {
        return read(section, index, false);
    }

    public static int metadata(NBTTagCompound section, int index) {
        return read(section, index, true);
    }

    private static int read(NBTTagCompound section, int index, boolean metadata) {
        if (!IntegrationGuard.available(Side.SERVER, EndlessIdsStorageIds.GUARD))
            throw new IllegalStateException(EndlessIdsStorageDiagnostics.MSG_NATIVE_SECTION_DECODING_REMAINS_PROTECTED);
        try {
            if (section == null || index < 0 || index >= EndlessIdsStorageTuning.MAX_INDEX_THRESHOLD)
                throw new IllegalArgumentException(EndlessIdsStorageDiagnostics.MSG_NATIVE_SECTION_AND_INDEX_4095);
            String fingerprint = DependencyFingerprint.mod(EndlessIdsCompat.MOD_ID);
            boolean extended = !EndlessIdsStorageIds.ID_ABSENT.equals(fingerprint);
            if (extended && !EndlessIdsStorageIds.EXPECTED.equals(fingerprint))
                throw new IllegalStateException(EndlessIdsStorageDiagnostics.MSG_UNAUDITED_ENDLESSIDS_SECTION_PROVIDER + fingerprint);
            validate(section, extended);
            if (metadata) {
                int value = nibble(section.getByteArray(EndlessIdsStorageNbtKeys.KEY_DATA), index);
                if (section.hasKey(EndlessIdsStorageNbtKeys.KEY_DATA1_HIGH)) value |= nibble(section.getByteArray(EndlessIdsStorageNbtKeys.KEY_DATA1_HIGH), index) << EndlessIdsStorageTuning.CHUNK_SECTION_SHIFT;
                if (section.hasKey(EndlessIdsStorageNbtKeys.KEY_DATA2)) value |= (section.getByteArray(EndlessIdsStorageNbtKeys.KEY_DATA2)[index] & EndlessIdsStorageTuning.UNSIGNED_BYTE_MASK) << EndlessIdsStorageTuning.SECOND_BYTE_SHIFT;
                return value;
            }
            int value = section.getByteArray(EndlessIdsStorageNbtKeys.KEY_BLOCKS)[index] & EndlessIdsStorageTuning.UNSIGNED_BYTE_MASK;
            if (section.hasKey(EndlessIdsStorageNbtKeys.KEY_ADD)) value |= nibble(section.getByteArray(EndlessIdsStorageNbtKeys.KEY_ADD), index) << EndlessIdsStorageTuning.SECOND_BYTE_SHIFT;
            if (section.hasKey(EndlessIdsStorageNbtKeys.KEY_BLOCKS_B2_HI)) value |= nibble(section.getByteArray(EndlessIdsStorageNbtKeys.KEY_BLOCKS_B2_HI), index) << EndlessIdsStorageTuning.ENDLESS_IDS_BIT_SHIFT_12;
            if (section.hasKey(EndlessIdsStorageNbtKeys.KEY_BLOCKS_B3)) value |= (section.getByteArray(EndlessIdsStorageNbtKeys.KEY_BLOCKS_B3)[index] & EndlessIdsStorageTuning.UNSIGNED_BYTE_MASK) << EndlessIdsStorageTuning.THIRD_BYTE_SHIFT;
            return value;
        } catch (Exception | LinkageError | AssertionError failure) {
            IntegrationGuard.report(Side.SERVER, EndlessIdsStorageIds.GUARD, failure);
            throw new IllegalStateException(EndlessIdsStorageDiagnostics.MSG_CANNOT_CONFIRM_NATIVE_SECTION_ORIGINAL, failure);
        }
    }

    private static void validate(NBTTagCompound section, boolean extended) {
        for (Object raw : section.func_150296_c()) {
            String key = (String) raw;
            if ((key.startsWith(EndlessIdsStorageNbtKeys.KEY_BLOCKS) || key.startsWith(EndlessIdsStorageNbtKeys.KEY_DATA) || key.startsWith(EndlessIdsStorageNbtKeys.KEY_ADD)) && !key.equals(EndlessIdsStorageNbtKeys.KEY_BLOCKS)
                && !key.equals(EndlessIdsStorageNbtKeys.KEY_ADD)
                && !key.equals(EndlessIdsStorageNbtKeys.KEY_BLOCKS_B2_HI)
                && !key.equals(EndlessIdsStorageNbtKeys.KEY_BLOCKS_B3)
                && !key.equals(EndlessIdsStorageNbtKeys.KEY_DATA)
                && !key.equals(EndlessIdsStorageNbtKeys.KEY_DATA1_HIGH)
                && !key.equals(EndlessIdsStorageNbtKeys.KEY_DATA2) || key.equals(EndlessIdsStorageNbtKeys.KEY_PALETTE)
                || key.equals(EndlessIdsStorageNbtKeys.KEY_BLOCK_STATES))
                throw new IllegalStateException(EndlessIdsStorageDiagnostics.MSG_UNKNOWN_NATIVE_SECTION_ENCODING + key);
        }
        array(section, EndlessIdsStorageNbtKeys.KEY_BLOCKS, EndlessIdsStorageTuning.MAX_INDEX_THRESHOLD, true);
        array(section, EndlessIdsStorageNbtKeys.KEY_ADD, EndlessIdsStorageTuning.ENDLESS_IDS_ARRAY_THIRD_PARAM, false);
        array(section, EndlessIdsStorageNbtKeys.KEY_DATA, EndlessIdsStorageTuning.ENDLESS_IDS_ARRAY_THIRD_PARAM, true);
        array(section, EndlessIdsStorageNbtKeys.KEY_BLOCKS_B2_HI, EndlessIdsStorageTuning.ENDLESS_IDS_ARRAY_THIRD_PARAM, false);
        array(section, EndlessIdsStorageNbtKeys.KEY_BLOCKS_B3, EndlessIdsStorageTuning.MAX_INDEX_THRESHOLD, false);
        array(section, EndlessIdsStorageNbtKeys.KEY_DATA1_HIGH, EndlessIdsStorageTuning.ENDLESS_IDS_ARRAY_THIRD_PARAM, false);
        array(section, EndlessIdsStorageNbtKeys.KEY_DATA2, EndlessIdsStorageTuning.MAX_INDEX_THRESHOLD, false);
        if (!extended && (section.hasKey(EndlessIdsStorageNbtKeys.KEY_BLOCKS_B2_HI) || section.hasKey(EndlessIdsStorageNbtKeys.KEY_BLOCKS_B3)
            || section.hasKey(EndlessIdsStorageNbtKeys.KEY_DATA1_HIGH)
            || section.hasKey(EndlessIdsStorageNbtKeys.KEY_DATA2)))
            throw new IllegalStateException(EndlessIdsStorageDiagnostics.MSG_EXTENDED_SECTION_DATA_HAS_NO);
        // The target native getters nest these layers; a detached upper layer is not a valid encoding.
        if (section.hasKey(EndlessIdsStorageNbtKeys.KEY_BLOCKS_B2_HI) && !section.hasKey(EndlessIdsStorageNbtKeys.KEY_ADD)
            || section.hasKey(EndlessIdsStorageNbtKeys.KEY_BLOCKS_B3) && !section.hasKey(EndlessIdsStorageNbtKeys.KEY_BLOCKS_B2_HI)
            || section.hasKey(EndlessIdsStorageNbtKeys.KEY_DATA2) && !section.hasKey(EndlessIdsStorageNbtKeys.KEY_DATA1_HIGH))
            throw new IllegalStateException(EndlessIdsStorageDiagnostics.MSG_NATIVE_SECTION_EXTENSION_LAYERS_DISAGREE);
    }

    private static void array(NBTTagCompound section, String key, int length, boolean required) {
        if (required && !section.hasKey(key) || section.hasKey(key)
            && (!section.hasKey(key, Constants.NBT.TAG_BYTE_ARRAY) || section.getByteArray(key).length != length))
            throw new IllegalStateException(EndlessIdsStorageDiagnostics.MSG_INVALID_NATIVE_SECTION_ARRAY + key);
    }

    private static int nibble(byte[] bytes, int index) {
        return (bytes[index >> EndlessIdsStorageTuning.ENDLESS_IDS_BIT_SHIFT_1] >> ((index & EndlessIdsStorageTuning.ENDLESS_IDS_BIT_SHIFT_1) * EndlessIdsStorageTuning.CHUNK_SECTION_SHIFT)) & EndlessIdsStorageTuning.CHUNK_LOCAL_MASK;
    }
}
