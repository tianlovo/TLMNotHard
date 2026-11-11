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
            if (manager == null) throw new IOException(ClientSnackCabinetDiagnostics.MSG_SNACK_CABINET_RESOURCES_ARE_NOT);
            var location = new ResourceLocation(ClientSnackCabinetIds.MODEL_JSON_SNACK_CABINET);
            try (var stream = manager.getResource(location)
                .getInputStream()) {
                model = new SimpleBedrockModel<>(stream);
            }
            if (model.getPart(ClientSnackCabinetIds.ID_FULL) == SimpleBedrockModel.EMPTY || model.getPart(ClientSnackCabinetIds.ID_HALF) == SimpleBedrockModel.EMPTY)
                throw new IOException(ClientSnackCabinetDiagnostics.MSG_ORIGINAL_CABINET_DISPLAY_PARTS_ARE);
        }
        return model;
    }
}
