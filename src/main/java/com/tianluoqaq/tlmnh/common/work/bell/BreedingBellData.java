package com.tianluoqaq.tlmnh.common.work.bell;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/** 育种铃铛 3x3 田块中心锚点（维度 + 土壤层中心坐标）纯数据编解码 */
public final class BreedingBellData {

    private final boolean bound;
    private final int dimension;
    private final int centerX;
    private final int soilY;
    private final int centerZ;

    private BreedingBellData(boolean bound, int dimension, int centerX, int soilY, int centerZ) {
        this.bound = bound;
        this.dimension = dimension;
        this.centerX = centerX;
        this.soilY = soilY;
        this.centerZ = centerZ;
    }

    public static BreedingBellData unbound() {
        return new BreedingBellData(false, BellTuning.ZERO, BellTuning.ZERO, BellTuning.ZERO, BellTuning.ZERO);
    }

    public boolean isBound() {
        return bound;
    }

    public int dimension() {
        return dimension;
    }

    public int centerX() {
        return centerX;
    }

    public int soilY() {
        return soilY;
    }

    public int cropY() {
        return soilY + BellTuning.ONE;
    }

    public int subSoilY() {
        return soilY - BellTuning.ONE;
    }

    public int centerZ() {
        return centerZ;
    }

    public boolean matches(int dim, int cx, int sy, int cz) {
        return bound && dimension == dim && centerX == cx && soilY == sy && centerZ == cz;
    }

    public static BreedingBellData read(ItemStack stack) {
        if (stack == null || !stack.hasTagCompound()) return unbound();
        return readFromRoot(stack.getTagCompound());
    }

    public static BreedingBellData readFromRoot(NBTTagCompound itemTag) {
        if (itemTag == null || !itemTag.hasKey(BellNbtKeys.ROOT_BREEDING, BellTuning.NBT_COMPOUND_ID)) {
            return unbound();
        }
        NBTTagCompound root = itemTag.getCompoundTag(BellNbtKeys.ROOT_BREEDING);
        if (!root.hasKey(BellNbtKeys.CENTER_X) || !root.hasKey(BellNbtKeys.SOIL_Y)
            || !root.hasKey(BellNbtKeys.CENTER_Z)) {
            return unbound();
        }
        return new BreedingBellData(
            true,
            root.getInteger(BellNbtKeys.DIMENSION),
            root.getInteger(BellNbtKeys.CENTER_X),
            root.getInteger(BellNbtKeys.SOIL_Y),
            root.getInteger(BellNbtKeys.CENTER_Z));
    }

    public static void bind(ItemStack stack, int dimension, int centerX, int soilY, int centerZ) {
        if (stack == null) return;
        NBTTagCompound itemTag = stack.getTagCompound();
        if (itemTag == null) {
            itemTag = new NBTTagCompound();
            stack.setTagCompound(itemTag);
        }
        bindOnRoot(itemTag, dimension, centerX, soilY, centerZ);
    }

    public static void bindOnRoot(NBTTagCompound itemTag, int dimension, int centerX, int soilY, int centerZ) {
        NBTTagCompound root = new NBTTagCompound();
        root.setInteger(BellNbtKeys.DIMENSION, dimension);
        root.setInteger(BellNbtKeys.CENTER_X, centerX);
        root.setInteger(BellNbtKeys.SOIL_Y, soilY);
        root.setInteger(BellNbtKeys.CENTER_Z, centerZ);
        itemTag.setTag(BellNbtKeys.ROOT_BREEDING, root);
    }

    public static void clear(ItemStack stack) {
        if (stack == null || !stack.hasTagCompound()) return;
        NBTTagCompound tag = stack.getTagCompound();
        clearOnRoot(tag);
        if (tag.hasNoTags()) {
            stack.setTagCompound(null);
        }
    }

    public static void clearOnRoot(NBTTagCompound itemTag) {
        if (itemTag != null) {
            itemTag.removeTag(BellNbtKeys.ROOT_BREEDING);
        }
    }
}
