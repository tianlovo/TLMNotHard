package com.tianluoqaq.tlmnh.client.life.bed;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;

import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;

import com.github.tartaricacid.touhoulittlemaid.client.model.bedrock.SimpleBedrockModel;
import com.tianluoqaq.tlmnh.common.life.bed.MaidBedColor;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 七种静态床模型在绘制保护边界内延迟读取，资源重载清全部旧模型 */
@SideOnly(Side.CLIENT)
public final class MaidBedModelResources implements IResourceManagerReloadListener {

    private final Map<MaidBedColor, SimpleBedrockModel<Entity>> models = new EnumMap<>(MaidBedColor.class);
    private IResourceManager manager;

    @Override
    public void onResourceManagerReload(IResourceManager manager) {
        this.manager = manager;
        models.clear();
    }

    public SimpleBedrockModel<Entity> get(MaidBedColor color) throws IOException {
        if (color == null) throw new IOException(ClientMaidBedDiagnostics.MSG_UNKNOWN_SAVED_MAID_BED_COLOR);
        var model = models.get(color);
        if (model == null) {
            if (manager == null) throw new IOException(ClientMaidBedDiagnostics.MSG_MAID_BED_RESOURCES_ARE_NOT);
            var location = new ResourceLocation(
                ClientMaidBedIds.ID_TOUHOU_LITTLE_MAID_MODELS_BEDROCK_BLOCK_MAID_BED + color.resourceName + ClientMaidBedIds.MODEL_JSON);
            try (var stream = manager.getResource(location)
                .getInputStream()) {
                model = new SimpleBedrockModel<>(stream);
            }
            models.put(color, model);
        }
        return model;
    }
}
