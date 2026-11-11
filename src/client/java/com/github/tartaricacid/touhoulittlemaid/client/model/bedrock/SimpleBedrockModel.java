/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: client/model/bedrock/SimpleBedrockModel.java, MIT
 * Adaptation: Forge 1.7.10 entity type and shared legacy Bedrock model
 */
package com.github.tartaricacid.touhoulittlemaid.client.model.bedrock;

import java.io.InputStream;

import net.minecraft.entity.Entity;

import com.github.tartaricacid.simplebedrockmodel.client.bedrock.AbstractBedrockEntityModel;
import com.github.tartaricacid.simplebedrockmodel.client.bedrock.model.BedrockPart;
import com.github.tartaricacid.simplebedrockmodel.client.bedrock.pojo.BedrockModelPOJO;
import com.github.tartaricacid.simplebedrockmodel.client.bedrock.pojo.BedrockVersion;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public class SimpleBedrockModel<T extends Entity> extends AbstractBedrockEntityModel<T> {

    public static final BedrockPart EMPTY = new BedrockPart();

    public SimpleBedrockModel(InputStream stream) {
        super(stream);
    }

    public SimpleBedrockModel(BedrockModelPOJO pojo, BedrockVersion version) {
        super(pojo, version);
    }

    @Override
    public void setupAnim(T entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw,
        float headPitch) {}

    public BedrockPart getPart(String name) {
        return modelMap.getOrDefault(name, EMPTY);
    }
}
