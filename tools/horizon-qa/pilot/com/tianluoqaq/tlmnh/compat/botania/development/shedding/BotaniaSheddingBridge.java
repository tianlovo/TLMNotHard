package com.tianluoqaq.tlmnh.compat.botania.development.shedding;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.lib.Handle;
import org.spongepowered.asm.lib.Type;
import org.spongepowered.asm.lib.tree.AbstractInsnNode;
import org.spongepowered.asm.lib.tree.AnnotationNode;
import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.lib.tree.IntInsnNode;
import org.spongepowered.asm.lib.tree.InvokeDynamicInsnNode;
import org.spongepowered.asm.lib.tree.LdcInsnNode;
import org.spongepowered.asm.lib.tree.MethodInsnNode;
import org.spongepowered.asm.lib.tree.MethodNode;
import org.spongepowered.asm.lib.tree.TypeInsnNode;
import org.spongepowered.asm.lib.tree.VarInsnNode;

import com.google.gson.JsonObject;

/** Binds the operation bridge from its actual native-drop call site after injection. */
public final class BotaniaSheddingBridge {

    public static final String REVISION = "entity-native-shedding-bridge-v2";
    private static final String ENTITY = "net/minecraft/entity/Entity";
    private static final String WORLD = "net/minecraft/world/World";
    private static final String MIXIN = BotaniaSheddingBootstrap.PREFIX + "NativeSheddingSpawnMixin";
    private static final String SPAWN = "(Lnet/minecraft/world/World;Lnet/minecraft/entity/Entity;Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)Z";
    private static final String BRIDGE = "([Ljava/lang/Object;)Ljava/lang/Boolean;";
    private static final String DROP = "(Lnet/minecraft/item/ItemStack;F)Lnet/minecraft/entity/item/EntityItem;";
    private static volatile BotaniaSheddingBridge binding;
    private final String bridgeName, wrapperName, shape;

    private BotaniaSheddingBridge(String bridgeName, String wrapperName, String shape) {
        this.bridgeName = bridgeName;
        this.wrapperName = wrapperName;
        this.shape = shape;
    }

    public static synchronized void register(ClassNode node) {
        require(binding == null, "Duplicate native bridge registration");
        binding = inspect(node);
        System.out.println("TLMNH_QA_BOTANIA_NATIVE_BRIDGE_BOUND " + binding.diagnostics());
    }

    /** Pure detached-node validation, also used by the ordinary actual-generator checks. */
    public static BotaniaSheddingBridge inspect(ClassNode node) {
        require(ENTITY.equals(node.name), "Native bridge owner differs");
        MethodNode drop = null, wrapper = null;
        for (Object raw : node.methods) {
            MethodNode method = (MethodNode) raw;
            if (DROP.equals(method.desc)
                && (method.name.equals("func_70099_a") || method.name.equals("entityDropItem"))) {
                require(drop == null, "Ambiguous native drop");
                drop = method;
            }
            if (SPAWN.equals(method.desc) && method.name.endsWith("$shedding$spawn")) {
                require(wrapper == null && mergedFrom(method, MIXIN), "Foreign/ambiguous native wrapper");
                require((method.access & 10) == 2, "Native wrapper is not a private instance method");
                wrapper = method;
            }
        }
        require(drop != null && wrapper != null, "Native bridge or wrapper absent");
        InvokeDynamicInsnNode operation = null;
        int wrapperCalls = 0;
        for (AbstractInsnNode instruction : drop.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call) || !call.owner.equals(ENTITY)
                || !call.name.equals(wrapper.name)
                || !call.desc.equals(SPAWN)) continue;
            require(call.getOpcode() == 183 && !call.itf, "Native wrapper invocation differs");
            wrapperCalls++;
            AbstractInsnNode previous = instruction.getPrevious();
            while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
            require(previous instanceof InvokeDynamicInsnNode, "Native wrapper lacks its adjacent operation");
            operation = (InvokeDynamicInsnNode) previous;
        }
        require(wrapperCalls == 1 && operation != null, "Native wrapper must have one exact call site");
        require(
            operation.name.equals("call")
                && operation.desc.equals("()Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;")
                && operation.bsm.getTag() == 6
                && !operation.bsm.isInterface()
                && operation.bsm.getOwner()
                    .equals("java/lang/invoke/LambdaMetafactory")
                && operation.bsm.getName()
                    .equals("metafactory")
                && operation.bsm.getDesc()
                    .equals(
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;")
                && operation.bsmArgs.length == 3
                && Type.getMethodType("([Ljava/lang/Object;)Ljava/lang/Object;")
                    .equals(operation.bsmArgs[0])
                && operation.bsmArgs[1] instanceof Handle
                && Type.getMethodType(BRIDGE)
                    .equals(operation.bsmArgs[2]),
            "Native operation factory differs: " + operation.desc
                + " "
                + operation.bsm
                + " "
                + java.util.Arrays.toString(operation.bsmArgs));
        Handle handle = (Handle) operation.bsmArgs[1];
        require(
            handle.getTag() == 6 && !handle.isInterface()
                && handle.getOwner()
                    .equals(ENTITY)
                && handle.getDesc()
                    .equals(BRIDGE),
            "Native operation handle differs: " + handle);
        MethodNode bridge = null;
        for (Object raw : node.methods) {
            MethodNode method = (MethodNode) raw;
            if (method.name.equals(handle.getName()) && method.desc.equals(handle.getDesc())) {
                require(bridge == null, "Duplicate bridge method");
                bridge = method;
            }
        }
        require(
            bridge != null && bridge.access == 4106 && bridge.tryCatchBlocks != null && bridge.tryCatchBlocks.isEmpty(),
            "Native bridge flags/exception table differ: "
                + (bridge == null ? "missing" : bridge.access + " " + bridge.desc));
        String shape = shape(bridge);
        String originalName = null;
        for (AbstractInsnNode instruction : bridge.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(WORLD)) {
                require(
                    originalName == null
                        && (call.name.equals("func_72838_d") || call.name.equals("spawnEntityInWorld")),
                    "Native World spawn differs");
                originalName = call.name;
            }
        }
        String expected = "25:0;16:2;18:[net.minecraft.world.World, net.minecraft.entity.Entity];"
            + "184:com/llamalad7/mixinextras/injector/wrapoperation/WrapOperationRuntime:checkArgumentCount:([Ljava/lang/Object;ILjava/lang/String;)V:false;"
            + "25:0;89;16:0;50;192:"
            + WORLD
            + ";95;89;16:1;50;192:"
            + ENTITY
            + ";95;87;182:"
            + WORLD
            + ":"
            + originalName
            + ":(Lnet/minecraft/entity/Entity;)Z:false;184:java/lang/Boolean:valueOf:(Z)Ljava/lang/Boolean;:false;176;";
        require(originalName != null && shape.equals(expected), "Native bridge body differs: " + shape);
        return new BotaniaSheddingBridge(bridge.name, wrapper.name, shape);
    }

    private static boolean mergedFrom(MethodNode method, String source) {
        List<AnnotationNode> annotations = new ArrayList<>();
        if (method.visibleAnnotations != null) annotations.addAll(method.visibleAnnotations);
        if (method.invisibleAnnotations != null) annotations.addAll(method.invisibleAnnotations);
        int matches = 0;
        for (AnnotationNode annotation : annotations) {
            if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;")) continue;
            require(annotation.values != null, "Native wrapper merge annotation lacks values");
            for (int i = 0; i < annotation.values.size(); i += 2) {
                if (annotation.values.get(i)
                    .equals("mixin")
                    && annotation.values.get(i + 1)
                        .equals(source))
                    matches++;
            }
        }
        return matches == 1;
    }

    private static String shape(MethodNode method) {
        StringBuilder result = new StringBuilder();
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            int opcode = instruction.getOpcode();
            if (opcode < 0) continue;
            result.append(opcode);
            if (instruction instanceof VarInsnNode var) result.append(':')
                .append(var.var);
            else if (instruction instanceof IntInsnNode value) result.append(':')
                .append(value.operand);
            else if (instruction instanceof LdcInsnNode constant) {
                require(constant.cst instanceof String, "Native bridge diagnostic constant differs");
                result.append(':')
                    .append(constant.cst);
            } else if (instruction instanceof TypeInsnNode type) result.append(':')
                .append(type.desc);
            else if (instruction instanceof MethodInsnNode call) result.append(':')
                .append(call.owner)
                .append(':')
                .append(call.name)
                .append(':')
                .append(call.desc)
                .append(':')
                .append(call.itf);
            else require(instruction.getType() == AbstractInsnNode.INSN, "Unexpected bridge instruction");
            result.append(';');
        }
        return result.toString();
    }

    public static boolean isBridge(String name, String descriptor) {
        BotaniaSheddingBridge observed = binding;
        return observed != null && observed.bridgeName.equals(name) && BRIDGE.equals(descriptor);
    }

    public static boolean isWrapper(String name, String descriptor) {
        BotaniaSheddingBridge observed = binding;
        return observed != null && observed.wrapperName.equals(name) && SPAWN.equals(descriptor);
    }

    public static JsonObject selection() {
        require(binding != null, "Native bridge binding absent");
        return binding.diagnostics();
    }

    public JsonObject diagnostics() {
        JsonObject result = new JsonObject();
        result.addProperty("revision", REVISION);
        result.addProperty("observationPhase", "MIXIN_EXTENSIONS_POST_APPLY");
        result.addProperty("bridge", bridgeName);
        result.addProperty("bridgeDescriptor", BRIDGE);
        result.addProperty("wrapper", wrapperName);
        result.addProperty("wrapperDescriptor", SPAWN);
        result.addProperty("bridgeShape", shape);
        result.addProperty("definedClassBytesVerified", false);
        return result;
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalStateException(message);
    }
}
