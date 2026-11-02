package com.tianluoqaq.tlmnh.common.development.interaction;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Base64;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonObject;

/** 两端共用的只读公开状态；不读取未同步的私有背包，不代替图像验收 */
public final class MaidVisibleStateSnapshot {

    private MaidVisibleStateSnapshot() {}

    public static JsonObject capture(EntityMaid maid) {
        var result = new JsonObject();
        result.addProperty("model", maid.getModelId());
        result.addProperty("ownerUuid", maid.func_152113_b());
        result.addProperty("customName", maid.getCustomNameTag());
        result.addProperty("alwaysRenderNameTag", maid.getAlwaysRenderNameTag());
        result.addProperty("health", maid.getHealth());
        result.addProperty("hunger", maid.getHunger());
        result.addProperty("soundPack", maid.getSoundPackId());
        result.addProperty("mainItemNativeNbtBase64", nativeItem(maid.getHeldItem()));
        result.addProperty("offhandItemNativeNbtBase64", nativeItem(maid.getOffhandItem()));
        result.addProperty("headItemNativeNbtBase64", nativeItem(maid.getEquipmentInSlot(4)));
        result.addProperty("backItemNativeNbtBase64", nativeItem(maid.getBackpackShowItem()));
        return result;
    }

    private static String nativeItem(ItemStack stack) {
        if (stack == null) return "";
        var tag = new NBTTagCompound();
        stack.copy()
            .writeToNBT(tag);
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            CompressedStreamTools.write(tag, output);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot encode actual visible equipment", failure);
        }
        return Base64.getEncoder()
            .encodeToString(bytes.toByteArray());
    }
}
