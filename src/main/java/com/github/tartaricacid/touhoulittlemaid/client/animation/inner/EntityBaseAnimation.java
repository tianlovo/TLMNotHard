/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/client/animation/inner/EntityBaseAnimation.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: legacy resource IDs and per-frame state input; see SOURCE_PROVENANCE.md
 */
package com.github.tartaricacid.touhoulittlemaid.client.animation.inner;

import static com.github.tartaricacid.touhoulittlemaid.client.animation.inner.InnerAnimation.INNER_ANIMATION;

import java.util.Arrays;
import java.util.Date;
import java.util.Map;
import java.util.Objects;

import net.minecraft.util.ResourceLocation;

import com.github.tartaricacid.touhoulittlemaid.client.animation.script.ModelRendererWrapper;
import com.tianluoqaq.tlmnh.client.render.MaidAnimationState;

public final class EntityBaseAnimation {

    public static void init() {
        INNER_ANIMATION
            .put(new ResourceLocation("touhou_little_maid:animation/base/dimension/default.js"), getBaseDimDefault());
        INNER_ANIMATION
            .put(new ResourceLocation("touhou_little_maid:animation/base/float/default.js"), getBaseFloatDefault());
        INNER_ANIMATION.put(
            new ResourceLocation("touhou_little_maid:animation/base/time/day_night_hidden.js"),
            getBaseTimeDayNight());
        INNER_ANIMATION.put(
            new ResourceLocation("touhou_little_maid:animation/base/time/game_rotation.js"),
            getBaseTimeGameRotation());
        INNER_ANIMATION.put(
            new ResourceLocation("touhou_little_maid:animation/base/time/system_rotation.js"),
            getBaseTimeSysRotation());
        INNER_ANIMATION.put(
            new ResourceLocation("touhou_little_maid:animation/base/rotation/reciprocate.js"),
            getBaseRotationReciprocate());
        INNER_ANIMATION.put(
            new ResourceLocation("touhou_little_maid:animation/base/rotation/x_high_speed.js"),
            getBaseRotationXH());
        INNER_ANIMATION.put(
            new ResourceLocation("touhou_little_maid:animation/base/rotation/x_normal_speed.js"),
            getBaseRotationXN());
        INNER_ANIMATION.put(
            new ResourceLocation("touhou_little_maid:animation/base/rotation/x_low_speed.js"),
            getBaseRotationXL());
        INNER_ANIMATION.put(
            new ResourceLocation("touhou_little_maid:animation/base/rotation/y_high_speed.js"),
            getBaseRotationYH());
        INNER_ANIMATION.put(
            new ResourceLocation("touhou_little_maid:animation/base/rotation/y_normal_speed.js"),
            getBaseRotationYN());
        INNER_ANIMATION.put(
            new ResourceLocation("touhou_little_maid:animation/base/rotation/y_low_speed.js"),
            getBaseRotationYL());
        INNER_ANIMATION.put(
            new ResourceLocation("touhou_little_maid:animation/base/rotation/z_high_speed.js"),
            getBaseRotationZH());
        INNER_ANIMATION.put(
            new ResourceLocation("touhou_little_maid:animation/base/rotation/z_normal_speed.js"),
            getBaseRotationZN());
        INNER_ANIMATION.put(
            new ResourceLocation("touhou_little_maid:animation/base/rotation/z_low_speed.js"),
            getBaseRotationZL());
        INNER_ANIMATION
            .put(new ResourceLocation("touhou_little_maid:animation/base/random/select.js"), getRandomSelect());
    }

    public static IAnimation<MaidAnimationState> getBaseDimDefault() {
        return new IAnimation<MaidAnimationState>() {

            @Override
            public void setRotationAngles(float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw,
                float headPitch, float scaleFactor, MaidAnimationState entity,
                Map<String, ModelRendererWrapper> modelMap) {
                int dim = entity.dimension;

                ModelRendererWrapper overWorldHidden = modelMap.get("overWorldHidden");
                ModelRendererWrapper overWorldShow = modelMap.get("overWorldShow");
                ModelRendererWrapper netherWorldHidden = modelMap.get("netherWorldHidden");
                ModelRendererWrapper netherWorldShow = modelMap.get("netherWorldShow");
                ModelRendererWrapper endWorldHidden = modelMap.get("endWorldHidden");
                ModelRendererWrapper endWorldShow = modelMap.get("endWorldShow");

                if (overWorldHidden != null) {
                    overWorldHidden.setHidden((dim == 0));
                }
                if (overWorldShow != null) {
                    overWorldShow.setHidden(!(dim == 0));
                }
                if (netherWorldHidden != null) {
                    netherWorldHidden.setHidden((dim == -1));
                }
                if (netherWorldShow != null) {
                    netherWorldShow.setHidden(!(dim == -1));
                }
                if (endWorldHidden != null) {
                    endWorldHidden.setHidden((dim == 1));
                }
                if (endWorldShow != null) {
                    endWorldShow.setHidden(!(dim == 1));
                }
            }
        };
    }

    public static IAnimation<MaidAnimationState> getBaseFloatDefault() {
        return new IAnimation<MaidAnimationState>() {

            @Override
            public void setRotationAngles(float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw,
                float headPitch, float scaleFactor, MaidAnimationState entity,
                Map<String, ModelRendererWrapper> modelMap) {
                ModelRendererWrapper sinFloat = modelMap.get("sinFloat");
                ModelRendererWrapper cosFloat = modelMap.get("cosFloat");
                ModelRendererWrapper negativeSinFloat = modelMap.get("_sinFloat");
                ModelRendererWrapper negativeCosFloat = modelMap.get("_cosFloat");

                if (sinFloat != null) {
                    sinFloat.setOffsetY((float) (Math.sin(ageInTicks * 0.1) * 0.05));
                }
                if (cosFloat != null) {
                    cosFloat.setOffsetY((float) (Math.cos(ageInTicks * 0.1) * 0.05));
                }
                if (negativeSinFloat != null) {
                    negativeSinFloat.setOffsetY((float) (-Math.sin(ageInTicks * 0.1) * 0.05));
                }
                if (negativeCosFloat != null) {
                    negativeCosFloat.setOffsetY((float) (-Math.cos(ageInTicks * 0.1) * 0.05));
                }
            }
        };
    }

    public static IAnimation<MaidAnimationState> getBaseTimeDayNight() {
        return new IAnimation<MaidAnimationState>() {

            @Override
            public void setRotationAngles(float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw,
                float headPitch, float scaleFactor, MaidAnimationState entity,
                Map<String, ModelRendererWrapper> modelMap) {
                ModelRendererWrapper dayShow = modelMap.get("dayShow");
                ModelRendererWrapper nightShow = modelMap.get("nightShow");

                if (dayShow != null) {
                    dayShow.setHidden(entity.dayTime >= 13000);
                }

                if (nightShow != null) {
                    nightShow.setHidden(entity.dayTime < 13000);
                }
            }
        };
    }

    public static IAnimation<MaidAnimationState> getBaseTimeGameRotation() {
        return new IAnimation<MaidAnimationState>() {

            @Override
            public void setRotationAngles(float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw,
                float headPitch, float scaleFactor, MaidAnimationState entity,
                Map<String, ModelRendererWrapper> modelMap) {
                long time = entity.dayTime;
                float hourDeg = (float) (Math.PI + ((time / 1000) % 12) * (Math.PI / 6));
                float minDeg = (float) (((time % 1000) / (50 / 3)) * (Math.PI / 30));

                ModelRendererWrapper gameHourRotationX = modelMap.get("gameHourRotationX");
                ModelRendererWrapper gameMinuteRotationX = modelMap.get("gameMinuteRotationX");
                ModelRendererWrapper gameHourRotationY = modelMap.get("gameHourRotationY");
                ModelRendererWrapper gameMinuteRotationY = modelMap.get("gameMinuteRotationY");
                ModelRendererWrapper gameHourRotationZ = modelMap.get("gameHourRotationZ");
                ModelRendererWrapper gameMinuteRotationZ = modelMap.get("gameMinuteRotationZ");

                if (gameHourRotationX != null) {
                    gameHourRotationX.setRotateAngleX(hourDeg);
                }

                if (gameMinuteRotationX != null) {
                    gameMinuteRotationX.setRotateAngleX(minDeg);
                }

                if (gameHourRotationY != null) {
                    gameHourRotationY.setRotateAngleY(hourDeg);
                }

                if (gameMinuteRotationY != null) {
                    gameMinuteRotationY.setRotateAngleY(minDeg);
                }

                if (gameHourRotationZ != null) {
                    gameHourRotationZ.setRotateAngleZ(hourDeg);
                }

                if (gameMinuteRotationZ != null) {
                    gameMinuteRotationZ.setRotateAngleZ(minDeg);
                }
            }
        };
    }

    public static IAnimation<MaidAnimationState> getBaseTimeSysRotation() {
        return new IAnimation<MaidAnimationState>() {

            @Override
            public void setRotationAngles(float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw,
                float headPitch, float scaleFactor, MaidAnimationState entity,
                Map<String, ModelRendererWrapper> modelMap) {
                Date date = new Date();
                float hourDeg = (float) (((date.getHours() + date.getMinutes() / 60) % 12) * (Math.PI / 6));
                float minDeg = (float) ((date.getMinutes() + date.getSeconds() / 60) * (Math.PI / 30));
                float secDeg = (float) (date.getSeconds() * (Math.PI / 30));

                ModelRendererWrapper systemHourRotationX = modelMap.get("systemHourRotationX");
                ModelRendererWrapper systemMinuteRotationX = modelMap.get("systemMinuteRotationX");
                ModelRendererWrapper systemSecondRotationX = modelMap.get("systemSecondRotationX");
                ModelRendererWrapper systemHourRotationY = modelMap.get("systemHourRotationY");
                ModelRendererWrapper systemMinuteRotationY = modelMap.get("systemMinuteRotationY");
                ModelRendererWrapper systemSecondRotationY = modelMap.get("systemSecondRotationY");
                ModelRendererWrapper systemHourRotationZ = modelMap.get("systemHourRotationZ");
                ModelRendererWrapper systemMinuteRotationZ = modelMap.get("systemMinuteRotationZ");
                ModelRendererWrapper systemSecondRotationZ = modelMap.get("systemSecondRotationZ");

                if (systemHourRotationX != null) {
                    systemHourRotationX.setRotateAngleX(hourDeg);
                }

                if (systemMinuteRotationX != null) {
                    systemMinuteRotationX.setRotateAngleX(minDeg);
                }

                if (systemSecondRotationX != null) {
                    systemSecondRotationX.setRotateAngleX(secDeg);
                }

                if (systemHourRotationY != null) {
                    systemHourRotationY.setRotateAngleY(hourDeg);
                }

                if (systemMinuteRotationY != null) {
                    systemMinuteRotationY.setRotateAngleY(minDeg);
                }

                if (systemSecondRotationY != null) {
                    systemSecondRotationY.setRotateAngleY(secDeg);
                }

                if (systemHourRotationZ != null) {
                    systemHourRotationZ.setRotateAngleZ(hourDeg);
                }

                if (systemMinuteRotationZ != null) {
                    systemMinuteRotationZ.setRotateAngleZ(minDeg);
                }

                if (systemSecondRotationZ != null) {
                    systemSecondRotationZ.setRotateAngleZ(secDeg);
                }
            }
        };
    }

    public static IAnimation<MaidAnimationState> getBaseRotationReciprocate() {
        return new IAnimation<MaidAnimationState>() {

            @Override
            public void setRotationAngles(float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw,
                float headPitch, float scaleFactor, MaidAnimationState entity,
                Map<String, ModelRendererWrapper> modelMap) {
                ModelRendererWrapper xReciprocate = modelMap.get("xReciprocate");
                ModelRendererWrapper yReciprocate = modelMap.get("yReciprocate");
                ModelRendererWrapper zReciprocate = modelMap.get("zReciprocate");

                if (xReciprocate != null) {
                    xReciprocate.setRotateAngleX((float) (Math.cos(ageInTicks * 0.3) * 0.2));
                }
                if (yReciprocate != null) {
                    yReciprocate.setRotateAngleY((float) (Math.cos(ageInTicks * 0.3) * 0.2));
                }
                if (zReciprocate != null) {
                    zReciprocate.setRotateAngleZ((float) (Math.cos(ageInTicks * 0.3) * 0.2));
                }
            }
        };
    }

    public static IAnimation<MaidAnimationState> getBaseRotationXH() {
        return new IAnimation<MaidAnimationState>() {

            @Override
            public void setRotationAngles(float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw,
                float headPitch, float scaleFactor, MaidAnimationState entity,
                Map<String, ModelRendererWrapper> modelMap) {
                ModelRendererWrapper xRotationHighA = modelMap.get("xRotationHighA");
                if (xRotationHighA != null) {
                    xRotationHighA.setRotateAngleX((float) ((ageInTicks * 4) % 360 * 0.017453292));
                }

                ModelRendererWrapper xRotationHighB = modelMap.get("xRotationHighB");
                if (xRotationHighB != null) {
                    xRotationHighB.setRotateAngleX((float) ((ageInTicks * 4) % 360 * 0.017453292));
                }

                ModelRendererWrapper xRotationHighC = modelMap.get("xRotationHighC");
                if (xRotationHighC != null) {
                    xRotationHighC.setRotateAngleX((float) ((ageInTicks * 4) % 360 * 0.017453292));
                }

                ModelRendererWrapper xRotationHighD = modelMap.get("xRotationHighD");
                if (xRotationHighD != null) {
                    xRotationHighD.setRotateAngleX((float) ((ageInTicks * 4) % 360 * 0.017453292));
                }

                ModelRendererWrapper xRotationHighE = modelMap.get("xRotationHighE");
                if (xRotationHighE != null) {
                    xRotationHighE.setRotateAngleX((float) ((ageInTicks * 4) % 360 * 0.017453292));
                }
            }
        };
    }

    public static IAnimation<MaidAnimationState> getBaseRotationXN() {
        return new IAnimation<MaidAnimationState>() {

            @Override
            public void setRotationAngles(float limbSwing, float limbSwingAmount, float ageInTicks, float netNeadYaw,
                float headPitch, float scaleFactor, MaidAnimationState entity,
                Map<String, ModelRendererWrapper> modelMap) {
                ModelRendererWrapper xRotationNormalA = modelMap.get("xRotationNormalA");
                if (xRotationNormalA != null) {
                    xRotationNormalA.setRotateAngleX((float) (ageInTicks % 360 * 0.017453292));
                }

                ModelRendererWrapper xRotationNormalB = modelMap.get("xRotationNormalB");
                if (xRotationNormalB != null) {
                    xRotationNormalB.setRotateAngleX((float) (ageInTicks % 360 * 0.017453292));
                }

                ModelRendererWrapper xRotationNormalC = modelMap.get("xRotationNormalC");
                if (xRotationNormalC != null) {
                    xRotationNormalC.setRotateAngleX((float) (ageInTicks % 360 * 0.017453292));
                }

                ModelRendererWrapper xRotationNormalD = modelMap.get("xRotationNormalD");
                if (xRotationNormalD != null) {
                    xRotationNormalD.setRotateAngleX((float) (ageInTicks % 360 * 0.017453292));
                }

                ModelRendererWrapper xRotationNormalE = modelMap.get("xRotationNormalE");
                if (xRotationNormalE != null) {
                    xRotationNormalE.setRotateAngleX((float) (ageInTicks % 360 * 0.017453292));
                }
            }
        };
    }

    public static IAnimation<MaidAnimationState> getBaseRotationXL() {
        return new IAnimation<MaidAnimationState>() {

            @Override
            public void setRotationAngles(float limbSwing, float limbSwingAmount, float ageInTicks, float netLeadYaw,
                float headPitch, float scaleFactor, MaidAnimationState entity,
                Map<String, ModelRendererWrapper> modelMap) {
                ModelRendererWrapper xRotationLowA = modelMap.get("xRotationLowA");
                if (xRotationLowA != null) {
                    xRotationLowA.setRotateAngleX((float) ((ageInTicks / 4) % 360 * 0.017453292));
                }

                ModelRendererWrapper xRotationLowB = modelMap.get("xRotationLowB");
                if (xRotationLowB != null) {
                    xRotationLowB.setRotateAngleX((float) ((ageInTicks / 4) % 360 * 0.017453292));
                }

                ModelRendererWrapper xRotationLowC = modelMap.get("xRotationLowC");
                if (xRotationLowC != null) {
                    xRotationLowC.setRotateAngleX((float) ((ageInTicks / 4) % 360 * 0.017453292));
                }

                ModelRendererWrapper xRotationLowD = modelMap.get("xRotationLowD");
                if (xRotationLowD != null) {
                    xRotationLowD.setRotateAngleX((float) ((ageInTicks / 4) % 360 * 0.017453292));
                }

                ModelRendererWrapper xRotationLowE = modelMap.get("xRotationLowE");
                if (xRotationLowE != null) {
                    xRotationLowE.setRotateAngleX((float) ((ageInTicks / 4) % 360 * 0.017453292));
                }
            }
        };
    }

    public static IAnimation<MaidAnimationState> getBaseRotationYH() {
        return new IAnimation<MaidAnimationState>() {

            @Override
            public void setRotationAngles(float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw,
                float headPitch, float scaleFactor, MaidAnimationState entity,
                Map<String, ModelRendererWrapper> modelMap) {
                ModelRendererWrapper yRotationHighA = modelMap.get("yRotationHighA");
                if (yRotationHighA != null) {
                    yRotationHighA.setRotateAngleY((float) ((ageInTicks * 4) % 360 * 0.017453292));
                }

                ModelRendererWrapper yRotationHighB = modelMap.get("yRotationHighB");
                if (yRotationHighB != null) {
                    yRotationHighB.setRotateAngleY((float) ((ageInTicks * 4) % 360 * 0.017453292));
                }

                ModelRendererWrapper yRotationHighC = modelMap.get("yRotationHighC");
                if (yRotationHighC != null) {
                    yRotationHighC.setRotateAngleY((float) ((ageInTicks * 4) % 360 * 0.017453292));
                }

                ModelRendererWrapper yRotationHighD = modelMap.get("yRotationHighD");
                if (yRotationHighD != null) {
                    yRotationHighD.setRotateAngleY((float) ((ageInTicks * 4) % 360 * 0.017453292));
                }

                ModelRendererWrapper yRotationHighE = modelMap.get("yRotationHighE");
                if (yRotationHighE != null) {
                    yRotationHighE.setRotateAngleY((float) ((ageInTicks * 4) % 360 * 0.017453292));
                }
            }
        };
    }

    public static IAnimation<MaidAnimationState> getBaseRotationYN() {
        return new IAnimation<MaidAnimationState>() {

            @Override
            public void setRotationAngles(float limbSwing, float limbSwingAmount, float ageInTicks, float netNeadYaw,
                float headPitch, float scaleFactor, MaidAnimationState entity,
                Map<String, ModelRendererWrapper> modelMap) {
                ModelRendererWrapper yRotationNormalA = modelMap.get("yRotationNormalA");
                if (yRotationNormalA != null) {
                    yRotationNormalA.setRotateAngleY((float) (ageInTicks % 360 * 0.017453292));
                }

                ModelRendererWrapper yRotationNormalB = modelMap.get("yRotationNormalB");
                if (yRotationNormalB != null) {
                    yRotationNormalB.setRotateAngleY((float) (ageInTicks % 360 * 0.017453292));
                }

                ModelRendererWrapper yRotationNormalC = modelMap.get("yRotationNormalC");
                if (yRotationNormalC != null) {
                    yRotationNormalC.setRotateAngleY((float) (ageInTicks % 360 * 0.017453292));
                }

                ModelRendererWrapper yRotationNormalD = modelMap.get("yRotationNormalD");
                if (yRotationNormalD != null) {
                    yRotationNormalD.setRotateAngleY((float) (ageInTicks % 360 * 0.017453292));
                }

                ModelRendererWrapper yRotationNormalE = modelMap.get("yRotationNormalE");
                if (yRotationNormalE != null) {
                    yRotationNormalE.setRotateAngleY((float) (ageInTicks % 360 * 0.017453292));
                }
            }
        };
    }

    public static IAnimation<MaidAnimationState> getBaseRotationYL() {
        return new IAnimation<MaidAnimationState>() {

            @Override
            public void setRotationAngles(float limbSwing, float limbSwingAmount, float ageInTicks, float netLeadYaw,
                float headPitch, float scaleFactor, MaidAnimationState entity,
                Map<String, ModelRendererWrapper> modelMap) {
                ModelRendererWrapper yRotationLowA = modelMap.get("yRotationLowA");
                if (yRotationLowA != null) {
                    yRotationLowA.setRotateAngleY((float) ((ageInTicks / 4) % 360 * 0.017453292));
                }

                ModelRendererWrapper yRotationLowB = modelMap.get("yRotationLowB");
                if (yRotationLowB != null) {
                    yRotationLowB.setRotateAngleY((float) ((ageInTicks / 4) % 360 * 0.017453292));
                }

                ModelRendererWrapper yRotationLowC = modelMap.get("yRotationLowC");
                if (yRotationLowC != null) {
                    yRotationLowC.setRotateAngleY((float) ((ageInTicks / 4) % 360 * 0.017453292));
                }

                ModelRendererWrapper yRotationLowD = modelMap.get("yRotationLowD");
                if (yRotationLowD != null) {
                    yRotationLowD.setRotateAngleY((float) ((ageInTicks / 4) % 360 * 0.017453292));
                }

                ModelRendererWrapper yRotationLowE = modelMap.get("yRotationLowE");
                if (yRotationLowE != null) {
                    yRotationLowE.setRotateAngleY((float) ((ageInTicks / 4) % 360 * 0.017453292));
                }
            }
        };
    }

    public static IAnimation<MaidAnimationState> getBaseRotationZH() {
        return new IAnimation<MaidAnimationState>() {

            @Override
            public void setRotationAngles(float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw,
                float headPitch, float scaleFactor, MaidAnimationState entity,
                Map<String, ModelRendererWrapper> modelMap) {
                ModelRendererWrapper zRotationHighA = modelMap.get("zRotationHighA");
                if (zRotationHighA != null) {
                    zRotationHighA.setRotateAngleZ((float) ((ageInTicks * 4) % 360 * 0.017453292));
                }

                ModelRendererWrapper zRotationHighB = modelMap.get("zRotationHighB");
                if (zRotationHighB != null) {
                    zRotationHighB.setRotateAngleZ((float) ((ageInTicks * 4) % 360 * 0.017453292));
                }

                ModelRendererWrapper zRotationHighC = modelMap.get("zRotationHighC");
                if (zRotationHighC != null) {
                    zRotationHighC.setRotateAngleZ((float) ((ageInTicks * 4) % 360 * 0.017453292));
                }

                ModelRendererWrapper zRotationHighD = modelMap.get("zRotationHighD");
                if (zRotationHighD != null) {
                    zRotationHighD.setRotateAngleZ((float) ((ageInTicks * 4) % 360 * 0.017453292));
                }

                ModelRendererWrapper zRotationHighE = modelMap.get("zRotationHighE");
                if (zRotationHighE != null) {
                    zRotationHighE.setRotateAngleZ((float) ((ageInTicks * 4) % 360 * 0.017453292));
                }
            }
        };
    }

    public static IAnimation<MaidAnimationState> getBaseRotationZN() {
        return new IAnimation<MaidAnimationState>() {

            @Override
            public void setRotationAngles(float limbSwing, float limbSwingAmount, float ageInTicks, float netNeadYaw,
                float headPitch, float scaleFactor, MaidAnimationState entity,
                Map<String, ModelRendererWrapper> modelMap) {
                ModelRendererWrapper zRotationNormalA = modelMap.get("zRotationNormalA");
                if (zRotationNormalA != null) {
                    zRotationNormalA.setRotateAngleZ((float) (ageInTicks % 360 * 0.017453292));
                }

                ModelRendererWrapper zRotationNormalB = modelMap.get("zRotationNormalB");
                if (zRotationNormalB != null) {
                    zRotationNormalB.setRotateAngleZ((float) (ageInTicks % 360 * 0.017453292));
                }

                ModelRendererWrapper zRotationNormalC = modelMap.get("zRotationNormalC");
                if (zRotationNormalC != null) {
                    zRotationNormalC.setRotateAngleZ((float) (ageInTicks % 360 * 0.017453292));
                }

                ModelRendererWrapper zRotationNormalD = modelMap.get("zRotationNormalD");
                if (zRotationNormalD != null) {
                    zRotationNormalD.setRotateAngleZ((float) (ageInTicks % 360 * 0.017453292));
                }

                ModelRendererWrapper zRotationNormalE = modelMap.get("zRotationNormalE");
                if (zRotationNormalE != null) {
                    zRotationNormalE.setRotateAngleZ((float) (ageInTicks % 360 * 0.017453292));
                }
            }
        };
    }

    public static IAnimation<MaidAnimationState> getBaseRotationZL() {
        return new IAnimation<MaidAnimationState>() {

            @Override
            public void setRotationAngles(float limbSwing, float limbSwingAmount, float ageInTicks, float netLeadYaw,
                float headPitch, float scaleFactor, MaidAnimationState entity,
                Map<String, ModelRendererWrapper> modelMap) {
                ModelRendererWrapper zRotationLowA = modelMap.get("zRotationLowA");
                if (zRotationLowA != null) {
                    zRotationLowA.setRotateAngleZ((float) ((ageInTicks / 4) % 360 * 0.017453292));
                }

                ModelRendererWrapper zRotationLowB = modelMap.get("zRotationLowB");
                if (zRotationLowB != null) {
                    zRotationLowB.setRotateAngleZ((float) ((ageInTicks / 4) % 360 * 0.017453292));
                }

                ModelRendererWrapper zRotationLowC = modelMap.get("zRotationLowC");
                if (zRotationLowC != null) {
                    zRotationLowC.setRotateAngleZ((float) ((ageInTicks / 4) % 360 * 0.017453292));
                }

                ModelRendererWrapper zRotationLowD = modelMap.get("zRotationLowD");
                if (zRotationLowD != null) {
                    zRotationLowD.setRotateAngleZ((float) ((ageInTicks / 4) % 360 * 0.017453292));
                }

                ModelRendererWrapper zRotationLowE = modelMap.get("zRotationLowE");
                if (zRotationLowE != null) {
                    zRotationLowE.setRotateAngleZ((float) ((ageInTicks / 4) % 360 * 0.017453292));
                }
            }
        };
    }

    public static IAnimation<MaidAnimationState> getRandomSelect() {
        return new IAnimation<MaidAnimationState>() {

            @Override
            public void setRotationAngles(float limbSwing, float limbSwingAmount, float ageInTicks, float netLeadYaw,
                float headPitch, float scaleFactor, MaidAnimationState entity,
                Map<String, ModelRendererWrapper> modelMap) {
                ModelRendererWrapper[] randomSelectUncheck = new ModelRendererWrapper[] { modelMap.get("randomSelect1"),
                    modelMap.get("randomSelect2"), modelMap.get("randomSelect3"), modelMap.get("randomSelect4"),
                    modelMap.get("randomSelect5") };
                ModelRendererWrapper[] randomSelect = Arrays.stream(randomSelectUncheck)
                    .filter(Objects::nonNull)
                    .toArray(ModelRendererWrapper[]::new);
                if (randomSelect.length == 0) return;
                long index = Math.floorMod(entity.uuid.getLeastSignificantBits(), randomSelect.length);
                for (int i = 0; i < randomSelect.length; i++) {
                    randomSelect[i].setHidden(i != index);
                }
            }
        };
    }
}
