/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: com/github/tartaricacid/simplebedrockmodel/client/bedrock/BedrockModelProvider.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: Forge 1.7.10 types and legacy matrix/vertex interfaces
 */
package com.github.tartaricacid.simplebedrockmodel.client.bedrock;

import java.util.HashMap;

import net.minecraft.util.AxisAlignedBB;

import com.github.tartaricacid.simplebedrockmodel.client.bedrock.model.BedrockPart;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Interface for Bedrock model providers.
 * Provides methods to get the model, render bounding box, and a map of model parts.
 *
 * @param <T> The type of the Bedrock model provider.
 */
@SideOnly(Side.CLIENT)
public interface BedrockModelProvider<T extends BedrockModelProvider<T>> {

    /**
     * Gets the render bounding box for the model.
     *
     * @return The AxisAlignedBB representing the render bounding box.
     */
    AxisAlignedBB getRenderBoundingBox();

    /**
     * Gets a map of model parts.
     *
     * @return A HashMap where the key is a String identifier and the value is a BedrockPart.
     */
    HashMap<String, BedrockPart> getModelMap();
}
