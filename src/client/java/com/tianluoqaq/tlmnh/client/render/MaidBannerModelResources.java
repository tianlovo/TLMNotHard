package com.tianluoqaq.tlmnh.client.render;

import java.io.IOException;

import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;

import com.github.tartaricacid.touhoulittlemaid.client.model.bedrock.SimpleBedrockModel;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 固定原版背旗几何及资源重载，不另存女仆物品或原生图案纹理 */
@SideOnly(Side.CLIENT)
public final class MaidBannerModelResources implements IResourceManagerReloadListener {

    public static final MaidBannerModelResources INSTANCE = new MaidBannerModelResources();
    private IResourceManager manager;
    private SimpleBedrockModel<Entity> model;

    private MaidBannerModelResources() {}

    @Override
    public void onResourceManagerReload(IResourceManager manager) {
        this.manager = manager;
        model = null;
    }

    public SimpleBedrockModel<Entity> get() throws IOException {
        if (model == null) {
            if (manager == null) throw new IOException("Banner resources are not initialized");
            var location = new ResourceLocation("touhou_little_maid:models/bedrock/entity/maid_banner.json");
            try (var stream = manager.getResource(location)
                .getInputStream()) {
                model = new SimpleBedrockModel<>(stream);
            }
            if (model.getPart("main") == SimpleBedrockModel.EMPTY
                || model.getPart("banner") == SimpleBedrockModel.EMPTY)
                throw new IOException("Original maid banner frame or cloth is missing");
        }
        return model;
    }
}
