/*
 * Core state order and item condition meanings adapted from TouhouLittleMaid
 * 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6, MIT
 * See SOURCE_PROVENANCE.md and licenses/TouhouLittleMaid-MIT.txt
 */
package com.tianluoqaq.tlmnh.client.render.animation;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
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
        this.model = Objects.requireNonNull(model, AnimationIds.ID_MODEL);
        this.animations = Objects.requireNonNull(animations, AnimationIds.ID_ANIMATIONS);
        context = new MolangAnimationContext<>(new MolangQueryFrame(), storage, new Random(randomSeed));
        processor = new AnimationProcessor<>(this, storage);
        processor.registerModelRenderer(model.bones());
        context.modelResources()
            .bindBones(model.bones());
        for (int i = 0; i < AnimationLayout.MIN_I_THRESHOLD; i++) {
            String name = AnimationIds.ID_PRE_PARALLEL + i;
            add(
                AnimationIds.ID_PRE_PARALLEL_2 + i + AnimationIds.ID_CONTROLLER,
                AnimationLayout.SET_ROTATE_ANGLE_Y_INITIAL_VALUE,
                event -> play(event, name, EDefaultLoopTypes.LOOP));
        }
        add(AnimationIds.ID_MAIN, AnimationLayout.GECKO_PLAYER_ADD_SECOND_PARAM, this::main);
        add(
            AnimationIds.ID_HOLD_OFFHAND,
            AnimationLayout.SET_ROTATE_ANGLE_Y_INITIAL_VALUE,
            event -> hold(event, Slot.OFFHAND));
        add(
            AnimationIds.ID_HOLD_MAINHAND,
            AnimationLayout.SET_ROTATE_ANGLE_Y_INITIAL_VALUE,
            event -> hold(event, Slot.MAINHAND));
        swingController = add(AnimationIds.ID_SWING, AnimationLayout.GECKO_PLAYER_ADD_SECOND_PARAM, this::swing);
        add(AnimationIds.ID_USE, AnimationLayout.GECKO_PLAYER_ADD_SECOND_PARAM, this::use);
        add(AnimationIds.ID_MISC, AnimationLayout.GECKO_PLAYER_ADD_SECOND_PARAM, this::misc);
        add(
            AnimationIds.ID_PASSENGER,
            AnimationLayout.GECKO_PLAYER_ADD_SECOND_PARAM,
            event -> play(event, AnimationIds.ID_PASSENGER_2 + state.passengerId, EDefaultLoopTypes.LOOP));
        for (int i = 0; i < AnimationLayout.MIN_I_THRESHOLD; i++) {
            String name = AnimationIds.ID_PARALLEL + i;
            add(
                AnimationIds.ID_PARALLEL_2 + i + AnimationIds.ID_CONTROLLER,
                AnimationLayout.SET_ROTATE_ANGLE_Y_INITIAL_VALUE,
                event -> play(event, name, EDefaultLoopTypes.LOOP));
        }
        for (Slot slot : new Slot[] { Slot.FEET, Slot.LEGS, Slot.CHEST, Slot.HEAD }) {
            String name = slot.name()
                .toLowerCase(Locale.ROOT);
            add(
                name + AnimationIds.ID_CONTROLLER,
                AnimationLayout.SET_ROTATE_ANGLE_Y_INITIAL_VALUE,
                event -> armor(event, slot, name));
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
        this.state = Objects.requireNonNull(state, AnimationIds.ID_STATE);
        this.frame = Objects.requireNonNull(frame, AnimationIds.ID_FRAME);
        frame.lifetime = clock.ticks() / AnimationLayout.GECKO_PLAYER_LIFETIME;
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
            limbSwingAmount > AnimationLayout.MOVING_THRESHOLD,
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

    private PlayState misc(AnimationEvent<BuiltinGeckoPlayer> event) {
        if (state.begging) return play(event, AnimationIds.ID_BEG, EDefaultLoopTypes.LOOP);
        if (state.pickingUpSnowball) return play(event, AnimationIds.ID_PICK_UP_SNOWBALL, EDefaultLoopTypes.PLAY_ONCE);
        if (state.snowballAnimationExpired) return play(event, AnimationIds.ID_EMPTY, EDefaultLoopTypes.PLAY_ONCE);
        return PlayState.STOP;
    }

    private PlayState main(AnimationEvent<BuiltinGeckoPlayer> event) {
        String name;
        if (state.dead) name = AnimationIds.ID_DEATH;
        else if (state.sleeping) name = AnimationIds.ID_SLEEP;
        else if (state.swimming) name = AnimationIds.ID_SWIM;
        else if (state.climbing) name = state.verticalSpeed > 0 ? AnimationIds.ID_LADDER_UP
            : state.verticalSpeed < 0 ? AnimationIds.ID_LADDER_DOWN : AnimationIds.ID_LADDER_STILLNESS;
        else if (!state.vehicleId.isEmpty() && exists(AnimationIds.ID_VEHICLE + state.vehicleId))
            name = AnimationIds.ID_VEHICLE + state.vehicleId;
        else if (state.passenger) name = AnimationIds.ID_MINECRAFT_BOAT.equals(state.vehicleId) ? AnimationIds.ID_BOAT
            : AnimationIds.ID_CHAIR;
        else if (state.sitting) name = AnimationIds.ID_SIT;
        else if (frame.inWater) name = AnimationIds.ID_SWIM_STAND;
        else if (state.hurt) name = AnimationIds.ID_ATTACKED;
        else if (!state.onGround) name = AnimationIds.ID_JUMP;
        else if (state.sprinting) name = AnimationIds.ID_RUN;
        else if (event.getLimbSwingAmount() > AnimationLayout.MOVING_THRESHOLD) name = AnimationIds.ID_WALK;
        else name = AnimationIds.ID_IDLE;
        if (!exists(name)) name = AnimationIds.ID_IDLE;
        return play(
            event,
            name,
            AnimationIds.ID_DEATH.equals(name) || AnimationIds.ID_ATTACKED.equals(name) ? EDefaultLoopTypes.PLAY_ONCE
                : EDefaultLoopTypes.LOOP);
    }

    private PlayState hold(AnimationEvent<BuiltinGeckoPlayer> event, Slot slot) {
        boolean offhand = slot == Slot.OFFHAND;
        if (state.sleeping || state.dead
            || state.swinging && state.swingLeft == offhand
            || state.usingItem && state.usingOffhand == offhand) return PlayState.STOP;
        return play(
            event,
            conditional(
                AnimationIds.ID_HOLD + (offhand ? AnimationIds.ID_OFFHAND : AnimationIds.ID_MAINHAND),
                slot,
                true),
            EDefaultLoopTypes.LOOP);
    }

    private PlayState swing(AnimationEvent<BuiltinGeckoPlayer> event) {
        if (state.sleeping || state.dead) return PlayState.STOP;
        if (state.swinging) {
            Slot slot = state.swingLeft ? Slot.OFFHAND : Slot.MAINHAND;
            String name = conditional(
                state.swingLeft ? AnimationIds.ID_SWING_OFFHAND : AnimationIds.ID_SWING,
                slot,
                false);
            if (name == null) name = state.swingLeft ? AnimationIds.ID_SWING_OFFHAND : AnimationIds.ID_SWING_HAND;
            return play(event, name, EDefaultLoopTypes.PLAY_ONCE);
        }
        return event.getController()
            .getAnimationState() == AnimationState.STOPPED ? PlayState.STOP : PlayState.CONTINUE;
    }

    private PlayState use(AnimationEvent<BuiltinGeckoPlayer> event) {
        if (!state.usingItem || state.sleeping || state.dead) return PlayState.STOP;
        Slot slot = state.usingOffhand ? Slot.OFFHAND : Slot.MAINHAND;
        String prefix = AnimationIds.ID_USE_2
            + (state.usingOffhand ? AnimationIds.ID_OFFHAND : AnimationIds.ID_MAINHAND);
        String name = conditional(prefix, slot, false);
        return play(event, name == null ? prefix : name, EDefaultLoopTypes.LOOP);
    }

    private String conditional(String prefix, Slot slot, boolean allowEmpty) {
        var item = frame.equipment(slot);
        if (item == null)
            return allowEmpty && exists(prefix + AnimationIds.ID_EMPTY_2) ? prefix + AnimationIds.ID_EMPTY_2 : null;
        String name = prefix + AnimationIds.GECKO_PLAYER_CONDITIONAL_MESSAGE + item.itemId;
        if (exists(name)) return name;
        name = prefix + AnimationIds.COLON_DELIMITER + item.classification;
        if (!item.classification.isEmpty() && exists(name)) return name;
        name = prefix + AnimationIds.COLON_DELIMITER + item.useAnimation;
        return !AnimationIds.ID_NONE.equals(item.useAnimation) && exists(name) ? name : null;
    }

    private PlayState armor(AnimationEvent<BuiltinGeckoPlayer> event, Slot slot, String prefix) {
        var item = frame.equipment(slot);
        if (item == null) return PlayState.STOP;
        String name = prefix + AnimationIds.GECKO_PLAYER_CONDITIONAL_MESSAGE + item.itemId;
        return play(event, exists(name) ? name : prefix + AnimationIds.ID_DEFAULT, EDefaultLoopTypes.LOOP);
    }

    private PlayState play(AnimationEvent<BuiltinGeckoPlayer> event, String name, ILoopType loopType) {
        if (name == null || !exists(name)) return PlayState.STOP;
        String key = loopType + AnimationIds.PATH_SLASH_SEPARATOR + name;
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
