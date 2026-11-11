package com.tianluoqaq.tlmnh.contracts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;
import org.spongepowered.asm.lib.ClassReader;
import org.spongepowered.asm.lib.Opcodes;
import org.spongepowered.asm.lib.Type;
import org.spongepowered.asm.lib.tree.AbstractInsnNode;
import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.lib.tree.FieldInsnNode;
import org.spongepowered.asm.lib.tree.IntInsnNode;
import org.spongepowered.asm.lib.tree.LdcInsnNode;
import org.spongepowered.asm.lib.tree.MethodInsnNode;
import org.spongepowered.asm.lib.tree.MethodNode;
import org.spongepowered.asm.lib.tree.VarInsnNode;
import org.spongepowered.asm.lib.tree.analysis.Analyzer;
import org.spongepowered.asm.lib.tree.analysis.SourceInterpreter;
import org.spongepowered.asm.lib.tree.analysis.SourceValue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleData;
import com.tianluoqaq.tlmnh.common.protection.CompatibilityLedger;
import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;

/** 固定首个 Beta 的存档、网络与 Mixin 契约，供目录和源集迁移时核对 */
public class ReleaseCompatibilityContractTest {

    private static final String COMPOUND = "net/minecraft/nbt/NBTTagCompound";
    private static final String TAG_ARGUMENT = "L" + COMPOUND + ";";

    @Test
    public void externalFeatureIdsDependenciesAndRevisionsStayCompatible() throws Exception {
        var actual = new JsonArray();
        var dependencies = ExternalFeature.class.getDeclaredField("dependencies");
        dependencies.setAccessible(true);
        for (var feature : ExternalFeature.values()) {
            var entry = new JsonObject();
            entry.addProperty("name", feature.name());
            entry.addProperty("id", feature.id);
            entry.addProperty("revision", feature.contractRevision);
            entry.addProperty("clientOnly", feature.clientOnly);
            var mods = new JsonArray();
            for (String dependency : (String[]) dependencies.get(feature)) mods.add(string(dependency));
            entry.add("dependencies", mods);
            actual.add(entry);
        }
        assertEquals(golden().get("features"), actual);
    }

    @Test
    public void compatibilityLedgerKeepsItsNameAndUnknownStoredFields() throws Exception {
        assertEquals(
            golden().get("compatibilityName")
                .getAsString(),
            CompatibilityLedger.NAME);
        var source = new NBTTagCompound();
        source.setInteger("Schema", 1);
        var record = new NBTTagCompound();
        record.setInteger("Contract", 1);
        record.setString("Fingerprint", "backhand=fixture;");
        source.setTag("player_offhand", record);
        var unknown = new NBTTagCompound();
        unknown.setIntArray("FutureField", new int[] { 3, 1, 4 });
        source.setTag("UnknownFeature", unknown);
        var ledger = new CompatibilityLedger(CompatibilityLedger.NAME);
        ledger.readFromNBT(source);
        var saved = new NBTTagCompound();
        ledger.writeToNBT(saved);
        assertEquals(source, saved);
        saved.removeTag("UnknownFeature");
        ledger.writeToNBT(saved);
        assertEquals(source, saved);
    }

    @Test
    public void lifecycleLedgerKeepsTypedFieldsAndRoundTripsExistingRecords() throws Exception {
        var expected = golden().getAsJsonObject("lifecycle");
        assertEquals(
            expected.get("name")
                .getAsString(),
            MaidLifecycleData.NAME);
        var ledger = new MaidLifecycleData(MaidLifecycleData.NAME);
        var owner = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
        var token = ledger.initialToken(owner);
        var raw = new NBTTagCompound();
        raw.setString("UnknownItemPayload", "preserved");
        ledger.queueReturnedFilm(raw, 7, 1.5, 20, 3.5);
        var saved = new NBTTagCompound();
        ledger.writeToNBT(saved);
        assertEquals(
            expected.get("schema")
                .getAsInt(),
            saved.getInteger("Schema"));
        assertEquals(expected.get("root"), typedKeys(saved));
        assertEquals(
            expected.get("entry"),
            typedKeys(
                saved.getTagList("Entries", 10)
                    .getCompoundTagAt(0)));
        assertEquals(
            expected.get("returnedFilm"),
            typedKeys(
                saved.getTagList("ReturnedFilms", 10)
                    .getCompoundTagAt(0)));
        var restored = new MaidLifecycleData(MaidLifecycleData.NAME);
        restored.readFromNBT(saved);
        assertEquals(token, restored.initialToken(owner));
        var again = new NBTTagCompound();
        restored.writeToNBT(again);
        assertEquals(saved, again);
    }

    @Test
    public void maidNbtWritersKeepTheirKeysAndDelegation() throws Exception {
        for (var element : golden().getAsJsonArray("entityWriters")) {
            var writer = element.getAsJsonObject();
            var owner = bytecode(
                writer.get("class")
                    .getAsString());
            var method = method(
                owner,
                writer.get("method")
                    .getAsString());
            var frames = new Analyzer<SourceValue>(new SourceInterpreter()).analyze(owner.name, method);
            var keys = new TreeSet<String>();
            var delegates = new TreeSet<String>();
            for (int index = 0; index < method.instructions.size(); index++) {
                if (!(method.instructions.get(index) instanceof MethodInsnNode call) || frames[index] == null) continue;
                var frame = frames[index];
                var args = Type.getArgumentTypes(call.desc);
                int start = frame.getStackSize() - args.length;
                if (call.owner.equals(COMPOUND) && call.name.startsWith("set")
                    && args.length == 2
                    && isTagParameter(frame.getStack(start - 1))) keys.add((String) constant(frame.getStack(start)));
                for (int arg = 0; arg < args.length; arg++) {
                    if (args[arg].getDescriptor()
                        .equals(TAG_ARGUMENT) && isTagParameter(frame.getStack(start + arg)))
                        delegates.add(call.owner.replace('/', '.') + "#" + call.name + call.desc);
                }
            }
            assertEquals(owner.name, writer.get("keys"), strings(keys));
            if (writer.has("delegates")) assertEquals(owner.name, writer.get("delegates"), strings(delegates));
        }
    }

    @Test
    public void networkChannelsKeepMessageClassesDiscriminatorsAndSides() throws Exception {
        for (var element : golden().getAsJsonArray("networks")) {
            var expected = element.getAsJsonObject();
            var owner = bytecode(
                expected.get("class")
                    .getAsString());
            var channels = new JsonArray();
            var messages = new JsonArray();
            for (MethodNode method : owner.methods) {
                var frames = new Analyzer<SourceValue>(new SourceInterpreter()).analyze(owner.name, method);
                for (int index = 0; index < method.instructions.size(); index++) {
                    if (!(method.instructions.get(index) instanceof MethodInsnNode call) || frames[index] == null)
                        continue;
                    if (!call.name.equals("newSimpleChannel") && !call.name.equals("registerMessage")) continue;
                    var frame = frames[index];
                    int count = Type.getArgumentTypes(call.desc).length;
                    var arguments = new JsonArray();
                    for (int arg = frame.getStackSize() - count; arg < frame.getStackSize(); arg++)
                        arguments.add(new Gson().toJsonTree(constant(frame.getStack(arg))));
                    if (call.name.equals("newSimpleChannel")) channels.add(arguments.get(0));
                    else messages.add(arguments);
                }
            }
            assertEquals(owner.name, expected.get("channels"), channels);
            assertEquals(owner.name, expected.get("messages"), messages);
        }
    }

    @Test
    public void mixinConfigurationKeepsItsClassesSidesAndPlugin() throws Exception {
        for (var config : golden().getAsJsonObject("mixins")
            .entrySet()) assertEquals(config.getKey(), config.getValue(), json("/" + config.getKey()));
    }

    private static JsonObject golden() throws Exception {
        return json("/fixtures/release-compatibility-beta1.json");
    }

    private static JsonObject json(String path) throws Exception {
        try (var stream = ReleaseCompatibilityContractTest.class.getResourceAsStream(path)) {
            assertNotNull(path, stream);
            return new JsonParser().parse(new InputStreamReader(stream, StandardCharsets.UTF_8))
                .getAsJsonObject();
        }
    }

    private static ClassNode bytecode(String name) throws Exception {
        try (var stream = ReleaseCompatibilityContractTest.class
            .getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
            assertNotNull(name, stream);
            var node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    private static MethodNode method(ClassNode owner, String name) {
        List<MethodNode> found = new ArrayList<>();
        for (MethodNode method : owner.methods)
            if (method.name.equals(name) && method.desc.startsWith("(" + TAG_ARGUMENT)) found.add(method);
        assertEquals(owner.name + "#" + name, 1, found.size());
        return found.get(0);
    }

    private static boolean isTagParameter(SourceValue value) {
        if (value.insns.size() != 1) return false;
        return value.insns.iterator()
            .next() instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == 1;
    }

    private static Object constant(SourceValue value) {
        assertEquals("Contract argument must be a single constant", 1, value.insns.size());
        AbstractInsnNode instruction = value.insns.iterator()
            .next();
        if (instruction instanceof LdcInsnNode literal)
            return literal.cst instanceof Type type ? type.getClassName() : literal.cst;
        if (instruction instanceof FieldInsnNode field) return field.name;
        if (instruction instanceof IntInsnNode number) return number.operand;
        if (instruction.getOpcode() >= Opcodes.ICONST_M1 && instruction.getOpcode() <= Opcodes.ICONST_5)
            return instruction.getOpcode() - Opcodes.ICONST_0;
        throw new AssertionError("Nonconstant contract argument: " + instruction.getOpcode());
    }

    private static JsonObject typedKeys(NBTTagCompound tag) {
        var result = new JsonObject();
        for (Object key : tag.func_150296_c()) result.addProperty(
            (String) key,
            tag.getTag((String) key)
                .getId());
        return result;
    }

    private static JsonElement string(String value) {
        return new JsonPrimitive(value);
    }

    private static JsonArray strings(Iterable<String> values) {
        var result = new JsonArray();
        for (String value : values) result.add(string(value));
        return result;
    }
}
