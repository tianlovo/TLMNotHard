package com.tianluoqaq.tlmnh.client.render.molang;

import java.util.Objects;
import java.util.Random;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.controller.AnimationControllerContext;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.context.IContext;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.storage.IForeignVariableStorage;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.storage.IScopedVariableStorage;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.storage.ITempVariableStorage;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.storage.VariableStorage;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.value.IValue;
import com.github.tartaricacid.touhoulittlemaid.molang.runtime.ExpressionEvaluator;

/** 每个动画实例独立持有变量和随机源，纯数据输入不保活游戏实体 */
public final class MolangAnimationContext<TEntity> implements IContext<TEntity> {

    private TEntity entity;
    private final VariableStorage storage;
    private final Random random;
    private final IForeignVariableStorage foreignStorage;
    private AnimationControllerContext animationControllerContext;
    private final MolangModelResources modelResources;

    public MolangAnimationContext(TEntity entity, VariableStorage storage, Random random) {
        this(entity, storage, random, storage, new AnimationControllerContext(), new MolangModelResources());
    }

    private MolangAnimationContext(TEntity entity, VariableStorage storage, Random random,
        IForeignVariableStorage foreignStorage, AnimationControllerContext animationControllerContext,
        MolangModelResources modelResources) {
        this.entity = Objects.requireNonNull(entity, "entity");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.random = Objects.requireNonNull(random, "random");
        this.foreignStorage = foreignStorage;
        this.animationControllerContext = animationControllerContext;
        this.modelResources = modelResources;
    }

    public void updateEntity(TEntity entity) {
        this.entity = Objects.requireNonNull(entity, "entity");
    }

    public Object evaluateUnsafe(IValue value) throws Exception {
        storage.clearTemp();
        return value.evalUnsafe(ExpressionEvaluator.evaluator(this));
    }

    public double evaluate(IValue value) {
        storage.clearTemp();
        return value.evalAsDouble(ExpressionEvaluator.evaluator(this));
    }

    @Override
    public TEntity entity() {
        return entity;
    }

    @Override
    public Random random() {
        return random;
    }

    @Override
    public <TChild> IContext<TChild> createChild(TChild child) {
        IForeignVariableStorage foreign = child == entity ? foreignStorage
            : child instanceof IForeignVariableStorage ? (IForeignVariableStorage) child : null;
        return new MolangAnimationContext<>(
            child,
            storage,
            random,
            foreign,
            animationControllerContext,
            modelResources);
    }

    @Override
    public AnimationControllerContext animationControllerContext() {
        return animationControllerContext;
    }

    public void setAnimationControllerContext(AnimationControllerContext context) {
        animationControllerContext = Objects.requireNonNull(context, "context");
    }

    @Override
    public MolangModelResources modelResources() {
        return modelResources;
    }

    @Override
    public ITempVariableStorage tempStorage() {
        return storage;
    }

    @Override
    public IScopedVariableStorage scopedStorage() {
        return storage;
    }

    @Override
    public IForeignVariableStorage foreignStorage() {
        return foreignStorage;
    }
}
