package com.tianluoqaq.tlmnh.compat.endlessids.common.storage;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.Constants;

import com.tianluoqaq.tlmnh.common.protection.DependencyFingerprint;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.relauncher.Side;

/**
 * Read-only decoding of the target EndlessIDs 1.7.4 section format. Field names and shifts follow
 * BlockIDManager/BlockMetaManager and ExtendedBlockStorageMixin; no section or live world is rewritten.
 */
public final class EndlessIdsSectionData {

    private static final String GUARD = "endlessids/section-data";
    private static final String EXPECTED = "1.7.4:sha256:9be2b3ae458a72f2cdeb3fb30b77d8528a1dcd64ef08fc69720b79335e5ce0d7";

    private EndlessIdsSectionData() {}

    public static int blockId(NBTTagCompound section, int index) {
        return read(section, index, false);
    }

    public static int metadata(NBTTagCompound section, int index) {
        return read(section, index, true);
    }

    private static int read(NBTTagCompound section, int index, boolean metadata) {
        if (!IntegrationGuard.available(Side.SERVER, GUARD))
            throw new IllegalStateException("Native section decoding remains protected");
        try {
            if (section == null || index < 0 || index >= 4096)
                throw new IllegalArgumentException("Native section and index 0..4095 required");
            String fingerprint = DependencyFingerprint.mod("endlessids");
            boolean extended = !"absent".equals(fingerprint);
            if (extended && !EXPECTED.equals(fingerprint))
                throw new IllegalStateException("Unaudited EndlessIDs section provider: " + fingerprint);
            validate(section, extended);
            if (metadata) {
                int value = nibble(section.getByteArray("Data"), index);
                if (section.hasKey("Data1High")) value |= nibble(section.getByteArray("Data1High"), index) << 4;
                if (section.hasKey("Data2")) value |= (section.getByteArray("Data2")[index] & 255) << 8;
                return value;
            }
            int value = section.getByteArray("Blocks")[index] & 255;
            if (section.hasKey("Add")) value |= nibble(section.getByteArray("Add"), index) << 8;
            if (section.hasKey("BlocksB2Hi")) value |= nibble(section.getByteArray("BlocksB2Hi"), index) << 12;
            if (section.hasKey("BlocksB3")) value |= (section.getByteArray("BlocksB3")[index] & 255) << 16;
            return value;
        } catch (Exception | LinkageError | AssertionError failure) {
            IntegrationGuard.report(Side.SERVER, GUARD, failure);
            throw new IllegalStateException("Cannot confirm native section; original data remains held", failure);
        }
    }

    private static void validate(NBTTagCompound section, boolean extended) {
        for (Object raw : section.func_150296_c()) {
            String key = (String) raw;
            if ((key.startsWith("Blocks") || key.startsWith("Data") || key.startsWith("Add")) && !key.equals("Blocks")
                && !key.equals("Add")
                && !key.equals("BlocksB2Hi")
                && !key.equals("BlocksB3")
                && !key.equals("Data")
                && !key.equals("Data1High")
                && !key.equals("Data2") || key.equals("Palette")
                || key.equals("BlockStates"))
                throw new IllegalStateException("Unknown native section encoding: " + key);
        }
        array(section, "Blocks", 4096, true);
        array(section, "Add", 2048, false);
        array(section, "Data", 2048, true);
        array(section, "BlocksB2Hi", 2048, false);
        array(section, "BlocksB3", 4096, false);
        array(section, "Data1High", 2048, false);
        array(section, "Data2", 4096, false);
        if (!extended && (section.hasKey("BlocksB2Hi") || section.hasKey("BlocksB3")
            || section.hasKey("Data1High")
            || section.hasKey("Data2")))
            throw new IllegalStateException("Extended section data has no audited provider");
        // The target native getters nest these layers; a detached upper layer is not a valid encoding.
        if (section.hasKey("BlocksB2Hi") && !section.hasKey("Add")
            || section.hasKey("BlocksB3") && !section.hasKey("BlocksB2Hi")
            || section.hasKey("Data2") && !section.hasKey("Data1High"))
            throw new IllegalStateException("Native section extension layers disagree");
    }

    private static void array(NBTTagCompound section, String key, int length, boolean required) {
        if (required && !section.hasKey(key) || section.hasKey(key)
            && (!section.hasKey(key, Constants.NBT.TAG_BYTE_ARRAY) || section.getByteArray(key).length != length))
            throw new IllegalStateException("Invalid native section array: " + key);
    }

    private static int nibble(byte[] bytes, int index) {
        return (bytes[index >> 1] >> ((index & 1) * 4)) & 15;
    }
}
