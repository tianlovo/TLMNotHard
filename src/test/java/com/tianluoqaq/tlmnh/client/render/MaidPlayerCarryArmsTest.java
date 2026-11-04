package com.tianluoqaq.tlmnh.client.render;

import static org.junit.Assert.assertEquals;

import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelRenderer;

import org.junit.Test;

public class MaidPlayerCarryArmsTest {

    @Test
    public void nativeCarryUsesOriginalFourAnglesAndRetainsRoll() {
        var model = new ModelBase() {};
        var left = new ModelRenderer(model);
        var right = new ModelRenderer(model);
        left.rotateAngleZ = .3F;
        right.rotateAngleZ = -.4F;
        MaidPlayerCarryArms.setArmRotations(left, right);
        assertEquals(Math.toRadians(-65), left.rotateAngleX, .000001);
        assertEquals(Math.toRadians(-65), right.rotateAngleX, .000001);
        assertEquals(Math.toRadians(10), left.rotateAngleY, .000001);
        assertEquals(Math.toRadians(-10), right.rotateAngleY, .000001);
        assertEquals(.3F, left.rotateAngleZ, 0);
        assertEquals(-.4F, right.rotateAngleZ, 0);
    }
}
