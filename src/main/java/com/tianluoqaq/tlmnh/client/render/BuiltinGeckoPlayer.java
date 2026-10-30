/*
 * Core state order and item condition meanings adapted from TouhouLittleMaid
 * 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6, MIT
 * See SOURCE_PROVENANCE.md and licenses/TouhouLittleMaid-MIT.txt
 */
package com.tianluoqaq.tlmnh.client.render;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.function.Consumer;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.AnimationState;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.PlayState;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.builder.Animation;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.builder.AnimationBuilder;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.builder.ILoopType;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.builder.ILoopType.EDefaultLoopTypes;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.controller.AnimationController;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.event.predicate.AnimationEvent;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.manager.AnimationData;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.storage.VariableStorage;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.processor.AnimationProcessor;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.file.AnimationFile;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.animated.AnimatedGeoModel;
import com.tianluoqaq.tlmnh.client.render.molang.MolangAnimationContext;
import com.tianluoqaq.tlmnh.client.render.molang.MolangQueryFrame;
import com.tianluoqaq.tlmnh.client.render.molang.MolangQueryFrame.Slot;

/** 按上游控制器顺序播放首版核心动作，只保存快照、骨骼与动画定义 */
public final class BuiltinGeckoPlayer implements GeckoAnimationTarget {

    private static final float MOVING_THRESHOLD = 0.05F;
    private final AnimatedGeoModel model;
    private final AnimationFile animations;
    private final AnimationData data = new AnimationData();
    private final AnimationClock clock = new AnimationClock();
    private final Map<String, AnimationBuilder> builders = new HashMap<>();
    private final VariableStorage storage = new VariableStorage();
    private final MolangAnimationContext<MolangQueryFrame> context;
    private final AnimationProcessor<BuiltinGeckoPlayer> processor;
    private final AnimationController<BuiltinGeckoPlayer> swingController;
    private MaidAnimationState state;
    private MolangQueryFrame frame;
    private boolean applied, wasSwinging;
    private int previousSwingTick;

    public BuiltinGeckoPlayer(AnimatedGeoModel model, AnimationFile animations, long randomSeed) {
        this.model = Objects.requireNonNull(model, "model");
        this.animations = Objects.requireNonNull(animations, "animations");
        context = new MolangAnimationContext<>(new MolangQueryFrame(), storage, new Random(randomSeed));
        processor = new AnimationProcessor<>(this, storage);
        processor.registerModelRenderer(model.bones());
        context.modelResources()
            .bindBones(model.bones());
        for (int i = 0; i < 8; i++) {
            String name = "pre_parallel" + i;
            add("pre_parallel_" + i + "_controller", 0, event -> play(event, name, EDefaultLoopTypes.LOOP));
        }
        add("main", 2, this::main);
        add("hold_offhand", 0, event -> hold(event, Slot.OFFHAND));
        add("hold_mainhand", 0, event -> hold(event, Slot.MAINHAND));
        swingController = add("swing", 2, this::swing);
        add("use", 2, this::use);
        add("misc", 2, event -> state.begging ? play(event, "beg", EDefaultLoopTypes.LOOP) : PlayState.STOP);
        add("passenger", 2, event -> play(event, "passenger$" + state.passengerId, EDefaultLoopTypes.LOOP));
        for (int i = 0; i < 8; i++) {
            String name = "parallel" + i;
            add("parallel_" + i + "_controller", 0, event -> play(event, name, EDefaultLoopTypes.LOOP));
        }
        for (Slot slot : new Slot[] { Slot.FEET, Slot.LEGS, Slot.CHEST, Slot.HEAD }) {
            String name = slot.name()
                .toLowerCase(java.util.Locale.ROOT);
            add(name + "_controller", 0, event -> armor(event, slot, name));
        }
    }

    private AnimationController<BuiltinGeckoPlayer> add(String name, float transition,
        AnimationController.IAnimationPredicate<BuiltinGeckoPlayer> predicate) {
        var controller = new AnimationController<>(this, name, transition, predicate);
        data.addAnimationController(controller);
        return controller;
    }

    public double advanceClock(double gameTicks, boolean paused) {
        return clock.advance(gameTicks, paused);
    }

    public boolean apply(MaidAnimationState state, MolangQueryFrame frame, float limbSwing, float limbSwingAmount,
        float partialTick, boolean paused) {
        return apply(state, frame, limbSwing, limbSwingAmount, partialTick, paused, null);
    }

    /** 可选观测器只读取核心姿态，普通绘制不创建快照；不得从回调修改模型 */
    public boolean apply(MaidAnimationState state, MolangQueryFrame frame, float limbSwing, float limbSwingAmount,
        float partialTick, boolean paused, Consumer<AnimatedGeoModel> coreObserver) {
        if (paused && applied) return false;
        this.state = Objects.requireNonNull(state, "state");
        this.frame = Objects.requireNonNull(frame, "frame");
        frame.lifetime = clock.ticks() / 20;
        context.updateEntity(frame);
        if (state.swinging && (!wasSwinging || state.swingTick < previousSwingTick)) swingController.markNeedsReload();
        wasSwinging = state.swinging;
        previousSwingTick = state.swingTick;
        context.modelResources()
            .physics()
            .update((float) clock.ticks());
        data.tick = clock.ticks();
        var event = new AnimationEvent<>(
            this,
            limbSwing,
            limbSwingAmount,
            partialTick,
            limbSwingAmount > MOVING_THRESHOLD,
            Collections.emptyList());
        event.animationTick = clock.ticks();
        processor.tickAnimation(clock.ticks(), event, context);
        if (coreObserver != null) coreObserver.accept(model);
        if (model.head() != null) {
            var head = model.head();
            head.setRotationX(head.getRotationX() + (float) Math.toRadians(frame.headPitch));
            head.setRotationY(head.getRotationY() + (float) Math.toRadians(frame.netHeadYaw));
        }
        applied = true;
        return true;
    }

    private PlayState main(AnimationEvent<BuiltinGeckoPlayer> event) {
        String name;
        if (state.dead) name = "death";
        else if (state.sleeping) name = "sleep";
        else if (state.swimming) name = "swim";
        else if (state.climbing)
            name = state.verticalSpeed > 0 ? "ladder_up" : state.verticalSpeed < 0 ? "ladder_down" : "ladder_stillness";
        else if (!state.vehicleId.isEmpty() && exists("vehicle$" + state.vehicleId))
            name = "vehicle$" + state.vehicleId;
        else if (state.passenger) name = "minecraft:boat".equals(state.vehicleId) ? "boat" : "chair";
        else if (state.sitting) name = "sit";
        else if (frame.inWater) name = "swim_stand";
        else if (state.hurt) name = "attacked";
        else if (!state.onGround) name = "jump";
        else if (state.sprinting) name = "run";
        else if (event.getLimbSwingAmount() > MOVING_THRESHOLD) name = "walk";
        else name = "idle";
        if (!exists(name)) name = "idle";
        return play(
            event,
            name,
            "death".equals(name) || "attacked".equals(name) ? EDefaultLoopTypes.PLAY_ONCE : EDefaultLoopTypes.LOOP);
    }

    private PlayState hold(AnimationEvent<BuiltinGeckoPlayer> event, Slot slot) {
        boolean offhand = slot == Slot.OFFHAND;
        if (state.sleeping || state.dead
            || state.swinging && state.swingLeft == offhand
            || state.usingItem && state.usingOffhand == offhand) return PlayState.STOP;
        return play(
            event,
            conditional("hold_" + (offhand ? "offhand" : "mainhand"), slot, true),
            EDefaultLoopTypes.LOOP);
    }

    private PlayState swing(AnimationEvent<BuiltinGeckoPlayer> event) {
        if (state.sleeping || state.dead) return PlayState.STOP;
        if (state.swinging) {
            Slot slot = state.swingLeft ? Slot.OFFHAND : Slot.MAINHAND;
            String name = conditional(state.swingLeft ? "swing_offhand" : "swing", slot, false);
            if (name == null) name = state.swingLeft ? "swing_offhand" : "swing_hand";
            return play(event, name, EDefaultLoopTypes.PLAY_ONCE);
        }
        return event.getController()
            .getAnimationState() == AnimationState.STOPPED ? PlayState.STOP : PlayState.CONTINUE;
    }

    private PlayState use(AnimationEvent<BuiltinGeckoPlayer> event) {
        if (!state.usingItem || state.sleeping || state.dead) return PlayState.STOP;
        Slot slot = state.usingOffhand ? Slot.OFFHAND : Slot.MAINHAND;
        String prefix = "use_" + (state.usingOffhand ? "offhand" : "mainhand");
        String name = conditional(prefix, slot, false);
        return play(event, name == null ? prefix : name, EDefaultLoopTypes.LOOP);
    }

    private String conditional(String prefix, Slot slot, boolean allowEmpty) {
        var item = frame.equipment(slot);
        if (item == null) return allowEmpty && exists(prefix + ":empty") ? prefix + ":empty" : null;
        String name = prefix + "$" + item.itemId;
        if (exists(name)) return name;
        name = prefix + ":" + item.classification;
        if (!item.classification.isEmpty() && exists(name)) return name;
        name = prefix + ":" + item.useAnimation;
        return !"none".equals(item.useAnimation) && exists(name) ? name : null;
    }

    private PlayState armor(AnimationEvent<BuiltinGeckoPlayer> event, Slot slot, String prefix) {
        var item = frame.equipment(slot);
        if (item == null) return PlayState.STOP;
        String name = prefix + "$" + item.itemId;
        return play(event, exists(name) ? name : prefix + ":default", EDefaultLoopTypes.LOOP);
    }

    private PlayState play(AnimationEvent<BuiltinGeckoPlayer> event, String name, ILoopType loopType) {
        if (name == null || !exists(name)) return PlayState.STOP;
        String key = loopType + "/" + name;
        var builder = builders.computeIfAbsent(key, ignored -> new AnimationBuilder().addAnimation(name, loopType));
        event.getController()
            .setAnimation(builder);
        return PlayState.CONTINUE;
    }

    private boolean exists(String name) {
        return animations.getAnimation(name) != null;
    }

    public double getSeekTicks() {
        return clock.ticks();
    }

    @Override
    public Animation getAnimation(String name) {
        return animations.getAnimation(name);
    }

    @Override
    public AnimationData getAnimationData() {
        return data;
    }
}
