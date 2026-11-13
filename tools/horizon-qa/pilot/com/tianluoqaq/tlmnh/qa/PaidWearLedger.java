package com.tianluoqaq.tlmnh.qa;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** 三种精确物品的被动扣减账本，所有回调进入原观察闭包 */
public final class PaidWearLedger {
    private static final String[] ROLES = { "sword", "bow", "shield" };
    private static final String[] ITEMS = { "minecraft:iron_sword", "minecraft:bow", "battlegear2:shield.wood" };
    private static final String[] CLASSES = {
        "net.minecraft.item.ItemSword", "net.minecraft.item.ItemBow", "mods.battlegear2.items.ItemShield" };
    private static final Map<Class<?>, JsonObject> SOURCES = new LinkedHashMap<>();
    private static final int LIMIT = 20000;
    private static final ThreadMXBean CLOCK = ManagementFactory.getThreadMXBean();
    private static volatile PaidWearLedger active;
    private final Thread thread = Thread.currentThread();
    private final IntSupplier frame;
    private final Supplier<List<ItemStack>> physicalInventory;
    private final JsonObject artifacts;
    private final JsonObject roles = new JsonObject(), observerApiSources = new JsonObject();
    private JsonObject nativeStackSource, nativeItemSource;
    private final JsonArray calls = new JsonArray(), checkpoints = new JsonArray();
    private final Deque<Call> pending = new ArrayDeque<>();
    private final IdentityHashMap<ItemStack, Integer> references = new IdentityHashMap<>();
    private volatile Set<ItemStack> publishedReferences = Collections.emptySet();
    private final long[] paid = new long[3], repairs = new long[3];
    private final int[] maximums = new int[3];
    private int sequence, nextId;
    private long observerCalls, observerCpuNanos, observerWallNanos;
    private boolean closed;
    private volatile boolean foreignThreadObserved;
    private volatile Throwable firstFailure;
    private volatile String failureBoundary;
    private Throwable nativeFailure;
    private Call nativeFailureCall;
    private String controlBoundary;
    private RuntimeException controlFailure;

    /** 与原异常分开保存，边界拒签不会修改原异常对象 */
    public static final class ObservationRejected extends IllegalStateException {
        private ObservationRejected(Throwable cause) {
            super("Paid-wear observation is invalid; retain partial evidence", cause);
        }
    }

    private static final class Call {
        final ItemStack stack;
        final int role;
        final String method;
        final JsonObject row;
        final int beforeDamage;

        Call(ItemStack stack, int role, String method, JsonObject row) {
            this.stack = stack;
            this.role = role;
            this.method = method;
            this.row = row;
            beforeDamage = stack.getItemDamage();
        }
    }

    private PaidWearLedger(IntSupplier frame, Supplier<List<ItemStack>> physicalInventory, JsonObject artifacts) {
        this.frame = frame;
        this.physicalInventory = physicalInventory;
        this.artifacts = new JsonParser().parse(artifacts.toString()).getAsJsonObject();
    }

    public static PaidWearLedger begin(IntSupplier frame, Supplier<List<ItemStack>> physicalInventory,
        JsonObject artifacts, Map<String, List<ItemStack>> items) {
        require(PaidWearApplication.requested() && PaidWearApplication.count() == 1 && active == null,
            "Paid-wear startup application or session differs");
        PaidWearLedger value = new PaidWearLedger(frame, physicalInventory, artifacts);
        value.observe(() -> {
            value.nativeStackSource = source(ItemStack.class);
            value.nativeItemSource = source(Item.class);
            value.observerApiSources.add(WrapMethod.class.getName(), source(WrapMethod.class));
            value.observerApiSources.add(Operation.class.getName(), source(Operation.class));
            Map<String, ItemStack> initial = value.present(items);
            for (int i = 0; i < ROLES.length; i++) {
                ItemStack stack = initial.get(ROLES[i]);
                require(stack != null && stack.stackSize == 1 && stack.getItemDamage() == 0,
                    "Paid-wear initial typed stock changed");
                value.reference(stack);
                require(stack.getMaxDamage() > 0 && stack.getMaxDamage() < Integer.MAX_VALUE,
                    "Real registered paid-wear maximum unavailable");
                value.maximums[i] = stack.getMaxDamage();
                JsonObject entry = new JsonObject();
                entry.addProperty("registry", ITEMS[i]);
                entry.addProperty("providerClass", CLASSES[i]);
                entry.add("initial", state(stack));
                entry.add("providerSource", source(stack.getItem().getClass()));
                value.roles.add(ROLES[i], entry);
            }
            FormalMethodAuthority.verifyProviders(value.roles, value.nativeStackSource, value.nativeItemSource, value.observerApiSources);
        });
        active = value;
        return value;
    }

    /** 原操作恰好执行一次，观察失败仅使账本失格 */
    public static <T> T invoke(ItemStack stack, String method, int requested, Supplier<T> original) {
        PaidWearLedger value = active;
        Call call = null;
        if (value != null) try {
            call = value.enter(stack, method, requested);
        } catch (Throwable observationFailure) {
            value.invalidate("entry-bridge", observationFailure);
        }
        T result;
        try {
            result = original.get();
        } catch (Throwable problem) {
            if (value != null) value.nativeThrew(stack, call, problem);
            return PaidWearLedger.<RuntimeException, T>rethrow(problem);
        }
        if (value != null) try {
            value.exit(call, result instanceof Boolean ? (Boolean) result : null);
        } catch (Throwable observationFailure) {
            value.invalidate("return-bridge", observationFailure);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable, T> T rethrow(Throwable problem) throws E {
        throw (E) problem;
    }

    private Call enter(ItemStack stack, String method, int requested) {
        if (Thread.currentThread() != thread) {
            if (publishedReferences.contains(stack)) foreignThreadObserved = true;
            return null;
        }
        Call[] entered = new Call[1];
        passiveObserve("entry", () -> {
            if (!physical(stack) || role(stack) < 0) return;
            controlFailure("entry");
            require(calls.size() + pending.size() < LIMIT, "Paid-wear mutation limit exceeded");
            int role = role(stack);
            JsonObject row = new JsonObject();
            row.addProperty("id", ++nextId);
            row.addProperty("entry", ++sequence);
            row.addProperty("frame", frame.getAsInt());
            row.addProperty("role", ROLES[role]);
            row.addProperty("reference", reference(stack));
            row.addProperty("method", method);
            row.addProperty("requested", requested);
            row.add("parent", pending.isEmpty() ? JsonNull.INSTANCE : pending.peek().row.get("id"));
            row.add("before", state(stack));
            entered[0] = new Call(stack, role, method, row);
            pending.push(entered[0]);
        });
        return entered[0];
    }

    private void exit(Call call, Boolean broke) {
        if (call == null) return;
        passiveObserve("return", () -> {
            controlFailure("return");
            require(!pending.isEmpty() && pending.peek() == call, "Paid-wear native return stack changed");
            // 完整写入后才弹出，失败保留未闭合原记录
            call.row.addProperty("exit", ++sequence);
            call.row.add("after", state(call.stack));
            if (broke == null) call.row.add("broke", JsonNull.INSTANCE);
            else call.row.addProperty("broke", broke);
            if (call.method.equals("setItemDamage")) {
                long delta = (long) call.stack.getItemDamage() - call.beforeDamage;
                paid[call.role] += Math.min(Math.max(delta, 0L), Math.max(0L, (long) maximums[call.role] + 1 - call.beforeDamage));
                repairs[call.role] += Math.max(-delta, 0L);
            }
            calls.add(call.row);
            pending.pop();
        });
    }

    private void nativeThrew(ItemStack stack, Call call, Throwable problem) {
        // 不调用物品/provider方法，不修改原异常。所有已知物理引用的异常均使账本失格
        try {
            if (Thread.currentThread() != thread) {
                if (publishedReferences.contains(stack)) foreignThreadObserved = true;
                return;
            }
            if (call == null && !references.containsKey(stack)) return;
            if (nativeFailure == null) {
                nativeFailure = problem;
                nativeFailureCall = call;
            }
            invalidate("native-exception", problem);
        } catch (Throwable observationFailure) {
            invalidate("native-exception-observer", observationFailure);
        }
    }

    private void invalidate(String boundary, Throwable problem) {
        if (firstFailure == null) {
            failureBoundary = boundary;
            firstFailure = problem;
        }
    }

    private void passiveObserve(String boundary, Runnable action) {
        if (firstFailure != null) return;
        try {
            // 错误在原计量闭包内部截获，避免Fixture的异常处理替换原生语义
            EffectEmptyWorldFixture.current().meterObservedProtocol(() -> {
                try {
                    observeBody(() -> {
                        try {
                            action.run();
                        } catch (Throwable problem) {
                            invalidate(boundary, problem);
                        }
                    });
                } catch (Throwable problem) {
                    invalidate(boundary, problem);
                }
            });
        } catch (Throwable problem) {
            invalidate(boundary + "-meter", problem);
        }
    }

    void failNextObserverForControl(String boundary, RuntimeException problem) {
        require(FormalMethodAuthority.requested() && Boolean.getBoolean("tlmnh.qaPaidWearNativeControls")
            && (boundary.equals("entry") || boundary.equals("return")) && controlFailure == null,
            "Observer fault injection requires explicit method preflight");
        controlBoundary = boundary;
        controlFailure = problem;
    }

    private void controlFailure(String boundary) {
        if (boundary.equals(controlBoundary)) {
            controlBoundary = null;
            throw controlFailure;
        }
    }

    private void requireQualified() {
        if (foreignThreadObserved) invalidate("foreign-thread", new IllegalStateException("Paid-wear mutation outside native server thread"));
        if (firstFailure != null) throw new ObservationRejected(firstFailure);
    }

    public void checkpoint(int activeFrame, Map<String, List<ItemStack>> items) {
        requireQualified();
        try {
            observe(() -> {
                require(!closed && pending.isEmpty() && activeFrame == checkpoints.size() && activeFrame == frame.getAsInt(),
                    "Paid-wear END sequence differs");
                Map<String, ItemStack> present = present(items);
                JsonObject states = new JsonObject();
                for (int i = 0; i < ROLES.length; i++) {
                    JsonObject entry = new JsonObject();
                    ItemStack stack = present.get(ROLES[i]);
                    if (stack == null) {
                        JsonObject missing = new JsonObject();
                        missing.addProperty("damage", 0);
                        missing.addProperty("count", 0);
                        missing.addProperty("maxDamage", maximums[i]);
                        entry.add("state", missing);
                        entry.addProperty("location", "broken");
                        entry.add("locations", new JsonArray());
                        entry.add("reference", JsonNull.INSTANCE);
                    } else {
                        entry.add("state", state(stack));
                        String location = null;
                        JsonArray locations = new JsonArray();
                        for (Map.Entry<String, List<ItemStack>> group : items.entrySet()) {
                            for (int slot = 0; slot < group.getValue().size(); slot++) {
                                if (group.getValue().get(slot) != stack) continue;
                                location = group.getKey();
                                locations.add(new com.google.gson.JsonPrimitive(location + ":" + slot));
                            }
                        }
                        entry.addProperty("location", location);
                        entry.add("locations", locations);
                        entry.addProperty("reference", reference(stack));
                    }
                    states.add(ROLES[i], entry);
                }
                JsonObject row = new JsonObject();
                row.addProperty("sequence", ++sequence);
                row.addProperty("frame", activeFrame);
                row.add("items", states);
                checkpoints.add(row);
            });
        } catch (RuntimeException | Error problem) {
            invalidate("checkpoint", problem);
            throw new ObservationRejected(firstFailure);
        }
    }

    public JsonObject finish(int expectedFrames) {
        requireQualified();
        JsonObject[] result = new JsonObject[1];
        try {
            observe(() -> {
                require(!closed && active == this && pending.isEmpty() && checkpoints.size() == expectedFrames,
                    "Paid-wear observation did not close");
                closed = true;
                result[0] = snapshot();
                active = null;
            });
        } catch (RuntimeException | Error problem) {
            closed = false;
            invalidate("finish", problem);
            throw new ObservationRejected(firstFailure);
        }
        addDiagnostics(result[0]);
        return result[0];
    }

    /** 失败retain使用同一原字节记录，缺失/未闭合边界继续可见 */
    public JsonObject snapshot() {
        JsonObject result = new JsonObject();
        result.addProperty("schema", "tlmnh-native-paid-wear-v2");
        result.addProperty("closed", closed && firstFailure == null && pending.isEmpty());
        if (firstFailure == null) result.add("failure", JsonNull.INSTANCE);
        else {
            JsonObject failure = throwable(firstFailure);
            failure.addProperty("boundary", failureBoundary);
            result.add("failure", failure);
        }
        if (nativeFailure == null) result.add("nativeException", JsonNull.INSTANCE);
        else {
            JsonObject failure = throwable(nativeFailure);
            failure.add("callId", nativeFailureCall == null ? JsonNull.INSTANCE : nativeFailureCall.row.get("id"));
            result.add("nativeException", failure);
        }
        result.addProperty("applicationCount", PaidWearApplication.count());
        result.addProperty("foreignThreadObserved", foreignThreadObserved);
        result.add("sourceArtifacts", artifacts);
        result.add("nativeStackSource", nativeStackSource);
        result.add("nativeItemSource", nativeItemSource);
        result.add("observerApiSources", observerApiSources);
        result.add("roles", roles);
        result.add("calls", calls);
        JsonArray unclosed = new JsonArray();
        for (Call call : pending) unclosed.add(call.row);
        result.add("pending", unclosed);
        result.add("checkpoints", checkpoints);
        JsonObject totals = new JsonObject(), restored = new JsonObject();
        for (int i = 0; i < ROLES.length; i++) {
            totals.addProperty(ROLES[i], paid[i]);
            restored.addProperty(ROLES[i], repairs[i]);
        }
        result.add("totals", totals);
        result.add("repairs", restored);
        addDiagnostics(result);
        return result;
    }

    private void addDiagnostics(JsonObject result) {
        result.addProperty("observerCalls", observerCalls);
        result.addProperty("observerCpuNanos", observerCpuNanos);
        result.addProperty("observerWallNanos", observerWallNanos);
        result.addProperty("observerClockScope", "Body inside original meteredClosure; begin, callbacks, END checkpoints and finish; overlaps phase/window costs");
    }

    private static JsonObject throwable(Throwable problem) {
        JsonObject row = new JsonObject();
        row.addProperty("class", problem.getClass().getName());
        row.addProperty("message", problem.getMessage());
        JsonArray trace = new JsonArray();
        for (StackTraceElement frame : problem.getStackTrace()) trace.add(new com.google.gson.JsonPrimitive(frame.toString()));
        row.add("stackTrace", trace);
        return row;
    }

    public void abort() {
        if (!closed && firstFailure == null) invalidate("abort", new IllegalStateException("Paid-wear session aborted before closure"));
        if (active == this) active = null;
    }

    private void observe(Runnable action) {
        require(Thread.currentThread() == thread, "Paid-wear observer outside native server thread");
        requireQualified();
        EffectEmptyWorldFixture.current().meterObservedProtocol(() -> observeBody(action));
    }

    private void observeBody(Runnable action) {
        require(!foreignThreadObserved, "Paid-wear mutation outside native server thread");
        long cpu = CLOCK.getCurrentThreadCpuTime(), wall = System.nanoTime();
        require(cpu >= 0, "Paid-wear CPU clock unavailable");
        try {
            action.run();
        } finally {
            long elapsedCpu = CLOCK.getCurrentThreadCpuTime() - cpu;
            long elapsedWall = System.nanoTime() - wall;
            require(elapsedCpu >= 0 && elapsedWall >= 0, "Paid-wear diagnostic clock invalid");
            observerCalls++;
            observerCpuNanos += elapsedCpu;
            observerWallNanos += elapsedWall;
        }
    }

    private Map<String, ItemStack> present(Map<String, List<ItemStack>> groups) {
        Map<String, ItemStack> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<ItemStack>> group : groups.entrySet()) {
            require(group.getKey().equals("maid") || group.getKey().equals("grave"), "Unknown paid-wear location");
            for (ItemStack stack : group.getValue()) {
                int role = role(stack);
                if (role < 0 || stack.stackSize <= 0) continue;
                ItemStack old = result.put(ROLES[role], stack);
                require(old == null || old == stack, "Duplicated paid-wear role/stock");
            }
        }
        return result;
    }

    private int reference(ItemStack stack) {
        Integer old = references.get(stack);
        if (old != null) return old;
        require(references.size() < LIMIT, "Paid-wear reference ledger limit exceeded");
        int id = references.size() + 1;
        references.put(stack, id);
        Set<ItemStack> published = Collections.newSetFromMap(new IdentityHashMap<ItemStack, Boolean>());
        published.addAll(references.keySet());
        publishedReferences = Collections.unmodifiableSet(published);
        return id;
    }

    private boolean physical(ItemStack stack) {
        if (!pending.isEmpty() && pending.peek().stack == stack) return true;
        for (ItemStack actual : physicalInventory.get()) if (actual == stack) return true;
        return false;
    }

    private static int role(ItemStack stack) {
        if (stack == null || !stack.hasTagCompound()) return -1;
        String label = stack.getTagCompound().getString("QACombatEffectPayload");
        for (int i = 0; i < ROLES.length; i++) if (ROLES[i].equals(label)) {
            require(ITEMS[i].equals(Item.itemRegistry.getNameForObject(stack.getItem()))
                && CLASSES[i].equals(stack.getItem().getClass().getName()),
                "Paid-wear physical provider or registered maximum changed");
            return i;
        }
        return -1;
    }

    private static JsonObject state(ItemStack stack) {
        JsonObject row = new JsonObject();
        row.addProperty("damage", stack.getItemDamage());
        row.addProperty("count", stack.stackSize);
        row.addProperty("maxDamage", stack.getMaxDamage());
        return row;
    }

    private static JsonObject source(Class<?> type) {
        JsonObject old = SOURCES.get(type);
        if (old != null) return old;
        try {
            JsonObject captured = PaidWearProviderIdentity.capture(type);
            SOURCES.put(type, captured);
            return captured;
        } catch (Exception failure) {
            throw new IllegalStateException("Paid-wear physical CodeSource unavailable", failure);
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}
