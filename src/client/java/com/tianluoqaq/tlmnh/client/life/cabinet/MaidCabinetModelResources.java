package com.tianluoqaq.tlmnh.client.life.cabinet;

import java.io.IOException;

import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;

import com.github.tartaricacid.touhoulittlemaid.client.model.bedrock.SimpleBedrockModel;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 原静态柜体在独立显示保护内延迟加载，重载只清资源对象 */
@SideOnly(Side.CLIENT)
public final class MaidCabinetModelResources implements IResourceManagerReloadListener {

    private IResourceManager manager;
    private SimpleBedrockModel<Entity> model;

    @Override
    public void onResourceManagerReload(IResourceManager manager) {
        this.manager = manager;
        model = null;
    }

    public SimpleBedrockModel<Entity> get() throws IOException {
        if (model == null) {
            if (manager == null) throw new IOException("Snack-cabinet resources are not initialized");
            var location = new ResourceLocation("touhou_little_maid:models/bedrock/block/snack_cabinet.json");
            try (var stream = manager.getResource(location)
                .getInputStream()) {
                model = new SimpleBedrockModel<>(stream);
            }
            if (model.getPart("full") == SimpleBedrockModel.EMPTY || model.getPart("half") == SimpleBedrockModel.EMPTY)
                throw new IOException("Original cabinet display parts are missing");
        }
        return model;
    }
}
