package com.tianluoqaq.tlmnh.client.render;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraft.util.ResourceLocation;

import com.github.tartaricacid.simplebedrockmodel.client.bedrock.AbstractBedrockEntityModel;
import com.github.tartaricacid.simplebedrockmodel.client.bedrock.BedrockModelUtil;
import com.github.tartaricacid.simplebedrockmodel.client.bedrock.pojo.BedrockModelPOJO;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.animated.AnimatedGeoModel;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.raw.pojo.Converter;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.raw.tree.RawGeometryTree;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.render.GeoBuilder;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.render.built.GeoModel;
import com.tianluoqaq.tlmnh.common.model.BuiltinModelCatalog;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 缓存内置几何定义，资源重载时失效；每次创建的模型姿态彼此独立 */
@SideOnly(Side.CLIENT)
public final class BuiltinMaidModels implements IResourceManagerReloadListener {

    private final Map<ResourceLocation, BedrockModelPOJO> bedrock = new HashMap<>();
    private final Map<ResourceLocation, GeoModel> gecko = new HashMap<>();
    private final BuiltinGeckoAnimations animations = new BuiltinGeckoAnimations(this::stream);
    private ResourceOpener opener;
    private int generation;

    public BuiltinMaidModels(ResourceOpener opener) {
        this.opener = opener;
    }

    public int getGeneration() {
        return generation;
    }

    public BuiltinMaidModel create(BuiltinModelCatalog.Choice choice) throws IOException {
        if (choice == null) throw new IllegalArgumentException("Missing builtin model choice");
        var location = choice.getGeometry();
        try {
            if (choice.isGecko()) {
                var model = gecko.get(location);
                if (model == null) {
                    try (var stream = stream(location)) {
                        var raw = Converter.fromInputStream(stream);
                        var tree = RawGeometryTree.parseHierarchy(raw);
                        model = GeoBuilder.getGeoBuilder()
                            .constructGeoModel(tree);
                    }
                    gecko.put(location, model);
                }
                return new BuiltinMaidModel(choice, null, new AnimatedGeoModel(model), animations.load(choice));
            }
            var pojo = bedrock.get(location);
            if (pojo == null) {
                try (var reader = reader(location)) {
                    pojo = BedrockModelUtil.GSON.fromJson(reader, BedrockModelPOJO.class);
                }
                bedrock.put(location, pojo);
            }
            return new BuiltinMaidModel(choice, new AbstractBedrockEntityModel<EntityMaid>(pojo) {

                @Override
                public void setupAnim(EntityMaid entity, float limbSwing, float limbSwingAmount, float ageInTicks,
                    float netHeadYaw, float headPitch) {}
            }, null, null);
        } catch (RuntimeException | cpw.mods.fml.common.versioning.InvalidVersionSpecificationException error) {
            throw new IOException("Cannot build builtin geometry " + location, error);
        }
    }

    private InputStreamReader reader(ResourceLocation location) throws IOException {
        return new InputStreamReader(stream(location), StandardCharsets.UTF_8);
    }

    private InputStream stream(ResourceLocation location) throws IOException {
        var stream = opener.open(location);
        if (stream == null) throw new IOException("Missing builtin geometry " + location);
        return stream;
    }

    @Override
    public void onResourceManagerReload(IResourceManager manager) {
        opener = location -> manager.getResource(location)
            .getInputStream();
        bedrock.clear();
        gecko.clear();
        animations.reload(this::stream);
        generation++;
    }

    @FunctionalInterface
    public interface ResourceOpener {

        InputStream open(ResourceLocation location) throws IOException;
    }
}
