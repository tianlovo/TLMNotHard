/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: client/model/DebugFloorModel.java, MIT
 * Adaptation: native 1.7.10 ModelRenderer
 */
package com.github.tartaricacid.touhoulittlemaid.client.model;

import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelRenderer;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class DebugFloorModel extends ModelBase {

    private final ModelRenderer floor;

    public DebugFloorModel() {
        textureWidth = 64;
        textureHeight = 32;
        floor = new ModelRenderer(this, 0, 0);
        floor.addBox(-8, 0, -11, 16, 0, 19);
        floor.setRotationPoint(0, -8, 0);
        floor.rotateAngleX = -3.1416F;
        floor.rotateAngleZ = 3.1416F;
    }

    public void draw() {
        floor.render(1 / 16.0F);
    }
}
