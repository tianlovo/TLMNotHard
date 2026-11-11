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
        int value = section.getByteArray("Blocks")[index] & 255;
        if (section.hasKey("Add")) {
            byte[] add = section.getByteArray("Add");
            value |= ((add[index >> 1] >> ((index & 1) * 4)) & 15) << 8;
        }
        return value;
    }

    default int sectionMetadata(NBTTagCompound section, int index) {
        validateVanillaSection(section, index);
        byte[] data = section.getByteArray("Data");
        return (data[index >> 1] >> ((index & 1) * 4)) & 15;
    }

    default void prepareInitialSize(EntityMaid maid, UUID actor) {}

    static void validateVanillaSection(NBTTagCompound section, int index) {
        if (section == null || index < 0 || index >= 4096)
            throw new IllegalArgumentException("Native section and index 0..4095 required");
        if (!section.hasKey("Blocks", Constants.NBT.TAG_BYTE_ARRAY) || section.getByteArray("Blocks").length != 4096
            || !section.hasKey("Data", Constants.NBT.TAG_BYTE_ARRAY)
            || section.getByteArray("Data").length != 2048)
            throw new IllegalStateException("Invalid native section array");
        if (section.hasKey("Add")
            && (!section.hasKey("Add", Constants.NBT.TAG_BYTE_ARRAY) || section.getByteArray("Add").length != 2048))
            throw new IllegalStateException("Invalid native Add section array");
        if (section.hasKey("BlocksB2Hi") || section.hasKey("BlocksB3")
            || section.hasKey("Data1High")
            || section.hasKey("Data2")
            || section.hasKey("Palette")
            || section.hasKey("BlockStates"))
            throw new IllegalStateException("Extended section data has no audited provider");
    }
}
