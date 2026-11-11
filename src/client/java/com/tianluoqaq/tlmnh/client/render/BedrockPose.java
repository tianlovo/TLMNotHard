package com.tianluoqaq.tlmnh.client.render;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.joml.Quaternionf;

import com.github.tartaricacid.simplebedrockmodel.client.bedrock.AbstractBedrockEntityModel;
import com.github.tartaricacid.simplebedrockmodel.client.bedrock.model.BedrockPart;
import com.github.tartaricacid.touhoulittlemaid.client.animation.script.GlWrapper;
import com.github.tartaricacid.touhoulittlemaid.client.animation.script.ModelRendererWrapper;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 在动画前恢复初始姿态，保存本体与附件共用的整体变换 */
@SideOnly(Side.CLIENT)
public final class BedrockPose {

    private final Map<String, ModelRendererWrapper> models;
    private final List<PartState> initial = new ArrayList<>();
    private LegacyPoseStack transform = new LegacyPoseStack();

    public BedrockPose(AbstractBedrockEntityModel<?> model) {
        var wrappers = new HashMap<String, ModelRendererWrapper>();
        model.getModelMap()
            .forEach((name, part) -> wrappers.put(name, new ModelRendererWrapper(part)));
        models = Collections.unmodifiableMap(wrappers);
        Set<BedrockPart> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        capture(
            model.getModelMap()
                .values(),
            visited);
    }

    private void capture(Collection<BedrockPart> parts, Set<BedrockPart> visited) {
        for (var part : parts) {
            if (!visited.add(part)) continue;
            initial.add(new PartState(part));
            capture(part.children, visited);
        }
    }

    public Map<String, ModelRendererWrapper> getModels() {
        return models;
    }

    public void animate(Consumer<Map<String, ModelRendererWrapper>> animation) {
        reset();
        transform = new LegacyPoseStack();
        GlWrapper.setPoseStack(transform);
        try {
            animation.accept(models);
        } catch (RuntimeException | Error failure) {
            reset();
            transform = new LegacyPoseStack();
            throw failure;
        } finally {
            GlWrapper.clearPoseStack();
        }
    }

    private void reset() {
        for (var state : initial) state.restore();
    }

    /** 同一动画产生的整体变换也用于手持物和护甲定位 */
    public void applyTransform(LegacyPoseStack poses) {
        poses.last()
            .pose()
            .mul(
                transform.last()
                    .pose());
        poses.last()
            .normal()
            .mul(
                transform.last()
                    .normal());
    }

    private static final class PartState {

        private final BedrockPart part;
        private final float x, y, z, rx, ry, rz, ox, oy, oz, sx, sy, sz;
        private final Quaternionf rotation;
        private final boolean visible, illuminated;

        private PartState(BedrockPart part) {
            this.part = part;
            x = part.x;
            y = part.y;
            z = part.z;
            rx = part.xRot;
            ry = part.yRot;
            rz = part.zRot;
            ox = part.offsetX;
            oy = part.offsetY;
            oz = part.offsetZ;
            sx = part.xScale;
            sy = part.yScale;
            sz = part.zScale;
            rotation = new Quaternionf(part.additionalQuaternion);
            visible = part.visible;
            illuminated = part.illuminated;
        }

        private void restore() {
            part.setPos(x, y, z);
            part.xRot = rx;
            part.yRot = ry;
            part.zRot = rz;
            part.offsetX = ox;
            part.offsetY = oy;
            part.offsetZ = oz;
            part.xScale = sx;
            part.yScale = sy;
            part.zScale = sz;
            part.additionalQuaternion.set(rotation);
            part.visible = visible;
            part.illuminated = illuminated;
        }
    }
}
