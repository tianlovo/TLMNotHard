package com.tianluoqaq.tlmnh.qa;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingEvent.LivingUpdateEvent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.compat.botania.development.shedding.BotaniaSheddingBootstrap;

/** Observes the native synchronous call only. A join witness grants no cleanup authority. */
public final class BotaniaSheddingProtocol {

    interface Host {

        World world();

        Thread thread();

        boolean active();

        boolean actor(Entity actor);

        boolean owned(Entity entity);

        void adopt(Entity entity);

        void meter(Runnable action);

        int frame();
    }

    private static volatile BotaniaSheddingProtocol current;
    private final Host host;
    private final EffectArrowRandomInput.StackBridge stack = new EffectArrowRandomInput.StackBridge();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final ThreadLocal<ArrayDeque<Scope>> scopes = ThreadLocal.withInitial(ArrayDeque::new);
    private final IdentityHashMap<EntityItem, Scope> witnessed = new IdentityHashMap<>();
    private final JsonArray receipts = new JsonArray();
    private boolean stopped;

    BotaniaSheddingProtocol(Host host) {
        check(current == null, "Previous Botania observer still registered");
        BotaniaSheddingBootstrap.requireApplied();
        this.host = host;
        current = this;
    }

    private void observe(Runnable action) {
        try {
            host.meter(() -> {
                try {
                    check(Thread.currentThread() == host.thread(), "Foreign Botania observer thread");
                    action.run();
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                }
            });
        } catch (Throwable problem) {
            failure.compareAndSet(null, problem);
        }
    }

    public static Object begin(Object provider, LivingUpdateEvent event) {
        BotaniaSheddingProtocol p = current;
        if (p == null || p.stopped) {
            // The first real handler call also authenticates in N/P, before delegating to the original.
            // This grants no actor scope or ownership and uses the existing fixture observation meter.
            try {
                if (!BotaniaSheddingBootstrap.isProvider(provider.getClass())) {
                    EffectEmptyWorldFixture.current()
                        .meterObservedProtocol(() -> {
                            try {
                                BotaniaSheddingBootstrap.authenticate(provider.getClass());
                            } catch (Throwable problem) {
                                BotaniaSheddingBootstrap.observationFailed(problem);
                            }
                        });
                }
            } catch (Throwable problem) {
                BotaniaSheddingBootstrap.observationFailed(problem);
            }
            return null;
        }
        Scope[] result = { null };
        p.observe(() -> {
            BotaniaSheddingBootstrap.authenticate(provider.getClass());
            p.caller(
                "shedding$handler",
                "(Lnet/minecraftforge/event/entity/living/LivingEvent$LivingUpdateEvent;Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)V",
                false);
            check(event != null && event.entity == event.entityLiving, "Foreign LivingUpdate receiver");
            Scope scope = new Scope(p, event.entityLiving);
            // Ineligible nested calls mask the outer scope and cannot borrow its provenance.
            scope.eligible = p.host.active() && p.host.actor(scope.actor)
                && p.host.owned(scope.actor)
                && scope.actor.worldObj == p.host.world()
                && !scope.actor.worldObj.isRemote
                && !scope.actor.isDead
                && scope.actor.getHealth() > 0;
            check(
                p.scopes.get()
                    .size() < 64,
                "Botania scope nesting exceeded");
            p.scopes.get()
                .push(scope);
            result[0] = scope;
        });
        return result[0];
    }

    public static Object drop(Entity actor, ItemStack stack, float offset) {
        BotaniaSheddingProtocol p = current;
        if (p == null || p.stopped) return null;
        Scope[] result = { null };
        p.observe(() -> {
            Scope s = p.scopes.get()
                .peek();
            if (s == null || !s.eligible) return;
            p.caller(
                "shedding$drop",
                "(Lnet/minecraft/entity/Entity;Lnet/minecraft/item/ItemStack;FLcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)Lnet/minecraft/entity/item/EntityItem;",
                false);
            check(s.actor == actor && !s.dropStarted && p.host.active(), "Unbound/repeated Botania drop");
            check(
                stack != null && stack.getItem() != null
                    && stack.stackSize > 0
                    && Float.floatToIntBits(offset) == Float.floatToIntBits(0F),
                "Invalid native shedding arguments");
            s.dropStarted = true;
            s.input = stack;
            s.snapshot = stack.copy();
            s.frame = p.host.frame();
            result[0] = s;
        });
        return result[0];
    }

    /** Called immediately before the single original World.spawnEntityInWorld invocation. */
    public static Object beforeSpawn(Entity actor, World world, Entity product) {
        BotaniaSheddingProtocol p = current;
        if (p == null || p.stopped) return null;
        Scope[] result = { null };
        p.observe(() -> {
            Scope s = p.scopes.get()
                .peek();
            if (s == null || !s.eligible || !s.dropStarted) return;
            p.caller(
                "shedding$spawn",
                "(Lnet/minecraft/world/World;Lnet/minecraft/entity/Entity;Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)Z",
                true);
            check(s.actor == actor && s.product == null && !s.dropReturned, "Foreign/repeated native spawn");
            check(
                world == p.host.world() && product.worldObj == world && product.getClass() == EntityItem.class,
                "Foreign shedding world/product class");
            EntityItem item = (EntityItem) product;
            check(
                item.getEntityItem() == s.input && ItemStack.areItemStacksEqual(s.snapshot, item.getEntityItem()),
                "Native item arguments changed");
            check(
                !p.host.owned(item) && !p.witnessed.containsKey(item)
                    && !item.isDead
                    && !world.loadedEntityList.contains(item)
                    && world.getEntityByID(item.getEntityId()) != item,
                "Shedding product is not fresh");
            check(p.witnessed.size() < 128, "Botania exact witness bound exceeded");
            s.product = item;
            result[0] = s;
        });
        return result[0];
    }

    public static void afterSpawn(Object token, boolean returned, boolean accepted) {
        if (!(token instanceof Scope s)) return;
        s.session.observe(() -> {
            s.session.caller(
                "shedding$spawn",
                "(Lnet/minecraft/world/World;Lnet/minecraft/entity/Entity;Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)Z",
                true);
            check(
                s.session.scopes.get()
                    .peek() == s && returned && accepted && s.joined,
                "Native shedding spawn failed or lacked exact join");
            s.spawnReturned = true;
        });
    }

    public static void afterDrop(Object token, boolean returned, EntityItem item) {
        if (!(token instanceof Scope s)) return;
        s.session.observe(() -> {
            s.session.caller(
                "shedding$drop",
                "(Lnet/minecraft/entity/Entity;Lnet/minecraft/item/ItemStack;FLcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)Lnet/minecraft/entity/item/EntityItem;",
                false);
            check(
                s.session.scopes.get()
                    .peek() == s && returned && item == s.product && item != null && s.spawnReturned,
                "Native drop returned a different/failed product");
            s.dropReturned = true;
        });
    }

    public static void end(Object token, boolean returned) {
        if (!(token instanceof Scope s)) return;
        BotaniaSheddingProtocol p = s.session;
        p.observe(() -> {
            p.caller(
                "shedding$handler",
                "(Lnet/minecraftforge/event/entity/living/LivingEvent$LivingUpdateEvent;Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)V",
                false);
            check(
                p.scopes.get()
                    .peek() == s,
                "Native shedding scope nesting changed");
            p.scopes.get()
                .pop();
            check(returned, "Native shedding handler threw");
            if (!s.eligible || !s.dropStarted) return;
            check(
                p.failure.get() == null && s.dropReturned
                    && s.spawnReturned
                    && s.joined
                    && p.host.active()
                    && p.host.frame() == s.frame
                    && p.host.actor(s.actor)
                    && p.host.owned(s.actor)
                    && s.actor.worldObj == p.host.world()
                    && !s.actor.isDead
                    && s.actor.getHealth() > 0
                    && !s.product.isDead
                    && p.host.world().loadedEntityList.contains(s.product)
                    && p.host.world()
                        .getEntityByID(s.product.getEntityId()) == s.product
                    && ItemStack.areItemStacksEqual(s.snapshot, s.product.getEntityItem()),
                "Incomplete/changed shedding provenance");
            check(!p.host.owned(s.product), "Duplicate shedding adoption");
            p.host.adopt(s.product);
            s.committed = true;
            JsonObject row = new JsonObject();
            row.addProperty(
                "actor",
                s.actor.getUniqueID()
                    .toString());
            row.addProperty(
                "actorClass",
                s.actor.getClass()
                    .getName());
            row.addProperty(
                "product",
                s.product.getUniqueID()
                    .toString());
            row.addProperty("itemId", net.minecraft.item.Item.getIdFromItem(s.snapshot.getItem()));
            row.addProperty("damage", s.snapshot.getItemDamage());
            row.addProperty("count", s.snapshot.stackSize);
            row.addProperty(
                "nbt",
                s.snapshot.hasTagCompound() ? s.snapshot.getTagCompound()
                    .toString() : "");
            row.addProperty("frame", s.frame);
            row.addProperty("normalHandlerReturn", true);
            p.receipts.add(row);
        });
    }

    /** True means provisional exact join only; ownership is committed by end(). */
    boolean join(EntityJoinWorldEvent event) {
        boolean[] result = { false };
        observe(() -> {
            Scope s = scopes.get()
                .peek();
            if (!(event.entity instanceof EntityItem item)) return;
            check(!witnessed.containsKey(item), "Repeated shedding join");
            if (s == null || !s.eligible || s.product != item) return;
            caller(
                "shedding$spawn",
                "(Lnet/minecraft/world/World;Lnet/minecraft/entity/Entity;Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)Z",
                true);
            check(
                failure.get() == null && !event.isCanceled()
                    && event.world == host.world()
                    && item.worldObj == event.world
                    && host.active()
                    && host.frame() == s.frame
                    && !s.joined
                    && !s.spawnReturned
                    && !s.dropReturned
                    && ItemStack.areItemStacksEqual(s.snapshot, item.getEntityItem()),
                "Invalid shedding join");
            witnessed.put(item, s);
            s.joined = true;
            result[0] = true;
        });
        return result[0];
    }

    void assertHealthy() {
        check(failure.get() == null, "Botania observation failed: " + failure.get());
        check(
            scopes.get()
                .isEmpty(),
            "Unclosed Botania scope");
        for (Scope s : witnessed.values()) check(s.committed, "Uncommitted shedding product retained");
    }

    JsonObject snapshot() {
        assertHealthy();
        return diagnostics();
    }

    JsonObject diagnostics() {
        JsonObject result = new JsonObject();
        result.addProperty(
            "stickyFailure",
            failure.get() == null ? ""
                : failure.get()
                    .toString());
        result.addProperty(
            "unclosedScopesOnOwnerThread",
            scopes.get()
                .size());
        result.addProperty("witnessedProducts", witnessed.size());
        int committed = 0;
        for (Scope s : witnessed.values()) if (s.committed) committed++;
        result.addProperty("committedProducts", committed);
        result.addProperty("revision", BotaniaSheddingBootstrap.REVISION);
        result.add("providerSelection", BotaniaSheddingBootstrap.selection());
        result.addProperty("providerJarSha256", BotaniaSheddingBootstrap.JAR_SHA);
        result.addProperty("providerClassSha256", BotaniaSheddingBootstrap.CLASS_SHA);
        result.add("receipts", receipts);
        return result;
    }

    void close() {
        stopped = true;
        if (current == this) current = null;
        assertHealthy();
    }

    private void caller(String hook, String descriptor, boolean nativeSpawn) {
        boolean first = true, found = false, handler = false, drop = false;
        for (EffectArrowRandomInput.Frame frame : stack.capture()) {
            boolean provider = BotaniaSheddingBootstrap.isProvider(frame.type);
            if (!provider && frame.type != Entity.class) continue;
            if (first) {
                check(
                    (nativeSpawn ? frame.type == Entity.class : provider)
                        && (frame.method.equals(hook) || frame.method.endsWith("$" + hook))
                        && frame.descriptor.equals(descriptor),
                    "Forged shedding hook call chain");
                first = false;
                found = true;
            }
            if (provider && (frame.method.equals("shedding$handler") || frame.method.endsWith("$shedding$handler")))
                handler = true;
            if (provider && (frame.method.equals("shedding$drop") || frame.method.endsWith("$shedding$drop")))
                drop = true;
        }
        check(found && handler && (!nativeSpawn || drop), "Incomplete synchronous native shedding chain");
    }

    private static void check(boolean good, String message) {
        if (!good) throw new IllegalStateException(message);
    }

    private static final class Scope {

        final BotaniaSheddingProtocol session;
        final EntityLivingBase actor;
        boolean eligible, dropStarted, joined, spawnReturned, dropReturned, committed;
        ItemStack input, snapshot;
        EntityItem product;
        int frame;

        Scope(BotaniaSheddingProtocol session, EntityLivingBase actor) {
            this.session = session;
            this.actor = actor;
        }
    }
}
