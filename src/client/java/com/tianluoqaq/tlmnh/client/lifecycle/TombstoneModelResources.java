package com.tianluoqaq.tlmnh.client.lifecycle;

import java.io.IOException;

import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraft.util.ResourceLocation;

import com.github.tartaricacid.touhoulittlemaid.client.model.bedrock.SimpleBedrockModel;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidTombstoneEntity;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 静态墓碑几何随资源重载失效；延迟解析位于绘制保护边界内 */
@SideOnly(Side.CLIENT)
public final class TombstoneModelResources implements IResourceManagerReloadListener {

    private static final ResourceLocation MODEL = new ResourceLocation(
        "touhou_little_maid:models/bedrock/entity/tombstone.json");
    private IResourceManager manager;
    private SimpleBedrockModel<MaidTombstoneEntity> model;

    @Override
    public void onResourceManagerReload(IResourceManager manager) {
        this.manager = manager;
        model = null;
    }

    public SimpleBedrockModel<MaidTombstoneEntity> get() throws IOException {
        if (model == null) {
            if (manager == null) throw new IOException("Tombstone resources have not been initialized");
            try (var stream = manager.getResource(MODEL)
                .getInputStream()) {
                model = new SimpleBedrockModel<>(stream);
            }
        }
        return model;
    }
}
