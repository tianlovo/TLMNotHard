package com.tianluoqaq.tlmnh.client.lifecycle.shrine;

import java.io.IOException;

import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;

import com.github.tartaricacid.touhoulittlemaid.client.model.bedrock.SimpleBedrockModel;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 原神龛静态模型的延迟资源加载与reload，不持有任何胶片数据 */
@SideOnly(Side.CLIENT)
public final class ShrineModelResources implements IResourceManagerReloadListener {

    private IResourceManager manager;
    private SimpleBedrockModel<Entity> model;

    @Override
    public void onResourceManagerReload(IResourceManager manager) {
        this.manager = manager;
        model = null;
    }

    public SimpleBedrockModel<Entity> get() throws IOException {
        if (model == null) {
            if (manager == null) throw new IOException("Shrine resources are not initialized");
            var location = new ResourceLocation("touhou_little_maid:models/bedrock/block/shrine.json");
            try (var stream = manager.getResource(location)
                .getInputStream()) {
                model = new SimpleBedrockModel<>(stream);
            }
            if (model.getPart("bone") == SimpleBedrockModel.EMPTY)
                throw new IOException("Original shrine root is missing");
        }
        return model;
    }
}
