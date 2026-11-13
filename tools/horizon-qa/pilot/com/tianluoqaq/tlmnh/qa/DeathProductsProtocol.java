package com.tianluoqaq.tlmnh.qa;

import java.lang.management.ThreadMXBean;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tianluoqaq.tlmnh.common.development.telemetry.NativeCombatLedgerReader;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventBus;

/** Exact provenance only. Hook failures are deferred until native execution has finished. */
public final class DeathProductsProtocol {

    interface Host {
        WorldServer world();
        MinecraftServer server();
        Thread thread();
        ThreadMXBean clock();
        boolean active();
        boolean completeWindow();
        String role(EntityLivingBase actor);
        boolean owned(Entity entity);
        void adopt(Entity entity);
        void harvest(EntityLivingBase actor);
        void meter(Runnable observation);
        int frame();
    }

    private static final int[] XP_SPLITS = { 2477, 1237, 617, 307, 149, 73, 37, 17, 7, 3, 1 };
    private static final String DEATH_DESC = "(Lnet/minecraft/util/DamageSource;)V";
    private static final String TC_DESC = "(Lnet/minecraftforge/event/entity/living/LivingDeathEvent;)V";
    private static volatile DeathProductsProtocol current;

    private final DeathProductsIdentity identity;
    private final Host host;
    private final EffectArrowRandomInput.StackBridge stack = new EffectArrowRandomInput.StackBridge();
    private final AtomicReference<Throwable> problem = new AtomicReference<>();
    private final ArrayDeque<Death> eventScopes = new ArrayDeque<>();
    private final ThreadLocal<ArrayDeque<Update>> updates = ThreadLocal.withInitial(ArrayDeque::new);
    private final IdentityHashMap<EntityLivingBase, Death> deaths = new IdentityHashMap<>();
    private final IdentityHashMap<Entity, Product> constructed = new IdentityHashMap<>();
    private final List<Death> deathOrder = new ArrayList<>();
    private final List<Update> updateOrder = new ArrayList<>();
    private final List<Product> products = new ArrayList<>();
    private final JsonArray observations = new JsonArray();
    private Death harvesting;
    private JsonObject finalHarvest;
    private boolean stopped;
    private int eventSerial;
    private long observerCalls, observerCpuNanos, observerWallNanos;

    DeathProductsProtocol(DeathProductsIdentity identity, Host host) {
        this.identity = identity;
        this.host = host;
        require(current == null, "A previous death-products session is still registered");
        current = this;
    }

    /** Called by the passive wrapper before the one original invocation. Never throws. */
    public static Object beginUpdate(EntityLivingBase actor) {
        DeathProductsProtocol session = current;
        if (session == null || session.stopped) return null;
        Object[] token = { null };
        session.observed(() -> token[0] = session.enter(actor));
        return token[0];
    }

    /** Called in the native wrapper's finally. Never masks the native exception. */
    public static void endUpdate(Object token, boolean returnedNormally) {
        if (!(token instanceof Update update)) return;
        update.session.observed(() -> update.session.leave(update, returnedNormally));
    }

    public static void lateAmount(EntityLivingBase actor, int residual) {
        DeathProductsProtocol session = current;
        if (session == null || session.stopped || (!session.host.active() && session.updates.get().isEmpty())) return;
        session.observed(() -> session.captureLate(actor, residual));
    }

    public static void constructed(EntityXPOrb orb, World world, double x, double y, double z, int amount) {
        DeathProductsProtocol session = current;
        if (session == null || session.stopped || world != session.host.world()
            || (!session.host.active() && session.updates.get().isEmpty())) return;
        session.observed(() -> session.captureConstructor(orb, world, x, y, z, amount));
    }

    private void observed(Runnable action) {
        // This includes dispatch overhead separately. These diagnostics overlap the fixture closure.
        long wall = System.nanoTime();
        long cpu = -1;
        try {
            cpu = host.clock().getCurrentThreadCpuTime();
            host.meter(() -> {
                try {
                    thread();
                    action.run();
                } catch (Throwable failure) {
                    problem.compareAndSet(null, failure);
                }
            });
        } catch (Throwable failure) {
            problem.compareAndSet(null, failure);
        } finally {
            observerCalls++;
            observerWallNanos += System.nanoTime() - wall;
            try {
                long after = host.clock().getCurrentThreadCpuTime();
                if (cpu >= 0 && after >= cpu) observerCpuNanos += after - cpu;
                else problem.compareAndSet(null, new IllegalStateException("Death-products observer CPU clock invalid"));
            } catch (Throwable failure) {
                problem.compareAndSet(null, failure);
            }
        }
    }

    void beginDeath(LivingDeathEvent event) {
        observed(() -> {
            if (eventScopes.isEmpty() && (!host.active() || event.entityLiving.worldObj != host.world())) return;
            require(eventScopes.size() < 1024, "Death event nesting exceeded");
            String role = host.role(event.entityLiving);
            Death death = new Death(event, role, eventSerial++);
            eventScopes.push(death);
            deathOrder.add(death);
            if (death.eligible) {
                require(!deaths.containsKey(event.entityLiving), "Controlled actor received a second native death event");
                deaths.put(event.entityLiving, death);
                if (role.equals("maid")) {
                    com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid =
                        (com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid) event.entityLiving;
                    require(maid.getExperience() == 0 && !maid.canPickupExperience(),
                        "Fixed maid zero-balance/no-pickup input changed");
                }
            }
            observations.add(death.row);
        });
    }

    void endDeath(LivingDeathEvent event) {
        observed(() -> {
            if (eventScopes.isEmpty()) return;
            Death death = eventScopes.pop();
            require(death.event == event, "Native death event nesting changed");
            death.lowest = true;
            death.row.addProperty("lowestObserved", true);
            death.row.addProperty("canceledAtLowest", event.isCanceled());
            if (!death.eligible) return;
            require(death.deathHarvest != null && death.exactLethalSource,
                "Controlled death lacks its actual complete lethal ledger source");
            death.row.add("deathLedger", death.deathHarvest);
            death.row.addProperty("exactLethalSource", true);
            for (Product product : death.aspects) confirmJoined(product, "after-tc-callback");
        });
    }

    void ledger(EntityLivingBase actor, NativeCombatLedgerReader.Harvest harvest, JsonObject observation, boolean death) {
        observed(() -> {
            if (harvesting != null && harvesting.actor == actor) finalHarvest = copy(observation);
            if (!death || eventScopes.isEmpty()) return;
            Death token = eventScopes.peek();
            if (!token.eligible || token.actor != actor) return;
            token.deathHarvest = copy(observation);
            token.exactLethalSource = false;
            for (NativeCombatLedgerReader.Entry entry : harvest.entries)
                if (entry.source == token.event.source && entry.victimTick == actor.ticksExisted
                    && entry.healthBefore > 0 && entry.nativeRecordedDamage > 0) token.exactLethalSource = true;
        });
    }

    private Update enter(EntityLivingBase actor) {
        ArrayDeque<Update> scopes = updates.get();
        if (!host.active() && scopes.isEmpty()) return null;
        require(scopes.size() < 1024, "Native death-update nesting exceeded");
        Death token = deaths.get(actor);
        boolean eligible = host.active() && host.role(actor) != null && host.owned(actor)
            && actor.worldObj == host.world() && token != null;
        Update update = new Update(this, actor, token, eligible, updateOrder.size());
        scopes.push(update);
        updateOrder.add(update);
        observations.add(update.row);
        if (eligible) require(actor.getHealth() <= 0 && !actor.isEntityAlive(), "Death update has no actual dead actor");
        return update;
    }

    private void captureLate(EntityLivingBase actor, int residual) {
        Update update = updateFor(actor);
        if (!update.eligible) return;
        require(update.entryDeathTime == 19 && actor.deathTime == 20 && !update.amountObserved,
            "Native residual capture has the wrong receiver/position/count");
        update.amountObserved = true;
        update.nativeAmount = residual;
        update.remaining = Math.max(0, residual);
        update.row.addProperty("nativeResidual", residual);
        update.row.addProperty("lateAmountObserved", true);
    }

    private void captureConstructor(EntityXPOrb orb, World world, double x, double y, double z, int amount) {
        JsonObject row = row("xp-constructor");
        row.addProperty("constructorAmount", amount);
        row.add("state", state(orb));
        observations.add(row);
        require(orb.getClass() == EntityXPOrb.class && world == host.world() && orb.worldObj == world,
            "XP constructor has a foreign exact class/world");
        ArrayDeque<Update> scopes = updates.get();
        require(!scopes.isEmpty(), "XP constructor has no exact native receiver scope");
        Update update = scopes.peek();
        require(update.eligible && update.actor == update.death.actor && host.active()
            && same(x, update.actor.posX) && same(y, update.actor.posY) && same(z, update.actor.posZ),
            "XP constructor is outside its exact controlled death update");
        require(!host.owned(orb) && !constructed.containsKey(orb)
            && !host.world().loadedEntityList.contains(orb) && host.world().getEntityByID(orb.getEntityId()) != orb,
            "XP constructor instance is not fresh");
        List<EffectArrowRandomInput.Frame> frames = stack.capture();
        require(find(frames, EntityXPOrb.class, "<init>", "(Lnet/minecraft/world/World;DDDI)V", 0) >= 0,
            "Actual XP constructor typed frame absent");
        boolean early = find(frames, EntityLivingBase.class, identity.early.getName(),
            "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", 0) >= 0;
        if (update.entryDeathTime == 0) {
            require(early, "Early XP constructor has no exact merged Archaic producer");
            int cached = identity.cachedXp(update.actor);
            if (!update.amountObserved) {
                update.nativeAmount = cached;
                update.remaining = Math.max(0, cached);
                update.amountObserved = true;
                update.row.addProperty("nativeEarlyCachedAmount", cached);
            }
            require(cached == update.nativeAmount, "Native early XP cache changed within its batch");
        } else {
            require(update.entryDeathTime == 19 && !early && update.amountObserved,
                "Late XP constructor lacks the actual post-assignment amount");
        }
        require(update.remaining > 0 && amount == split(update.remaining), "XP constructor differs from native split");
        Product product = new Product(orb, "xp", update.death,
            update.death.role + "-xp-" + update.serial + "-" + update.products.size());
        product.update = update;
        product.constructorAmount = amount;
        product.row = row;
        update.products.add(product);
        products.add(product);
        constructed.put(orb, product);
        update.remaining -= amount;
        update.sum += amount;
        row.addProperty("update", update.serial);
        row.addProperty("death", update.death.serial);
        row.addProperty("alias", product.alias);
        row.add("typedStack", typed(frames));
    }

    private void leave(Update update, boolean returnedNormally) {
        require(!updates.get().isEmpty() && updates.get().pop() == update, "Native death-update scope order changed");
        update.closed = true;
        update.returned = returnedNormally;
        update.row.addProperty("returnedNormally", returnedNormally);
        update.row.addProperty("exitDeathTime", update.actor.deathTime);
        if (!update.eligible) return;
        require(returnedNormally, "Native death update did not return normally");
        if (update.entryDeathTime == 0 && !update.amountObserved) {
            update.nativeAmount = identity.cachedXp(update.actor);
            update.amountObserved = true;
            update.remaining = Math.max(0, update.nativeAmount);
            update.row.addProperty("nativeEarlyCachedAmount", update.nativeAmount);
        }
        if (update.entryDeathTime == 19 && !update.amountObserved)
            update.row.addProperty("lateAmountStatus", "NATIVE_BRANCH_DID_NOT_CALL_GETTER");
        require(update.remaining == 0 && update.sum == Math.max(0L, (long) update.nativeAmount),
            "Native XP batch total/split sequence did not close");
        update.row.addProperty("constructorSum", update.sum);
        update.row.addProperty("remaining", update.remaining);
        for (Product product : update.products) confirmJoined(product, "native-death-update-return");
    }

    void join(EntityJoinWorldEvent event) {
        observed(() -> {
            if (!host.active() || event.world != host.world()) return;
            if (event.entity.getClass() == EntityXPOrb.class) {
                Product product = constructed.get(event.entity);
                require(product != null && product.update != null && !product.update.closed,
                    "XP join has no exact original constructor/update witness");
                attachJoin(product, event);
            } else if (event.entity.getClass() == identity.aspectOrb) {
                JsonObject raw = row("aspect-join");
                raw.add("state", state(event.entity));
                observations.add(raw);
                require(!eventScopes.isEmpty() && eventScopes.peek().eligible, "AspectOrb has no exact owned death event");
                Death death = eventScopes.peek();
                require(!death.lowest && death.actor.worldObj == event.world && !event.isCanceled()
                    && !host.owned(event.entity) && !constructed.containsKey(event.entity)
                    && !host.world().loadedEntityList.contains(event.entity)
                    && host.world().getEntityByID(event.entity.getEntityId()) != event.entity
                    && same(event.entity.posX, death.actor.posX) && same(event.entity.posY, death.actor.posY)
                    && same(event.entity.posZ, death.actor.posZ), "AspectOrb lacks fresh exact victim inputs");
                List<EffectArrowRandomInput.Frame> frames = stack.capture();
                int callback = find(frames, identity.tcHandler, "livingTick", TC_DESC, 0);
                int bus = find(frames, EventBus.class, "post", "(Lcpw/mods/fml/common/eventhandler/Event;)Z", callback + 1);
                int forge = find(frames, ForgeHooks.class, "onLivingDeath",
                    "(Lnet/minecraft/entity/EntityLivingBase;Lnet/minecraft/util/DamageSource;)Z", bus + 1);
                require(callback >= 0 && bus > callback && forge > bus && nativeDeathAfter(frames, forge),
                    "AspectOrb actual TC death/native ancestry differs");
                Product product = new Product(event.entity, "aspect", death,
                    death.role + "-aspect-" + death.serial + "-" + death.aspects.size());
                product.row = raw;
                death.aspects.add(product);
                products.add(product);
                constructed.put(event.entity, product);
                raw.addProperty("death", death.serial);
                raw.addProperty("alias", product.alias);
                raw.add("typedStack", typed(frames));
                attachJoin(product, event);
            }
        });
    }

    private void attachJoin(Product product, EntityJoinWorldEvent event) {
        require(product.join == null && product.entity == event.entity && !event.isCanceled(),
            "Duplicate/canceled death-product join");
        product.join = event;
        product.row.addProperty("joinEventIdentity", System.identityHashCode(event));
        product.row.addProperty("joinCanceledAtObserver", event.isCanceled());
        product.row.add("stateAtJoin", state(event.entity));
    }

    private void confirmJoined(Product product, String boundary) {
        require(product.join != null && !product.join.isCanceled() && product.entity.worldObj == host.world()
            && host.world().loadedEntityList.contains(product.entity)
            && host.world().getEntityByID(product.entity.getEntityId()) == product.entity,
            "Exact product did not finish its real world join");
        product.joinConfirmed = true;
        product.row.addProperty("actualJoinConfirmed", true);
        product.row.addProperty("joinConfirmationBoundary", boundary);
        product.row.add("stateAfterProducer", state(product.entity));
    }

    void settle() {
        // END is the first point at which a recorded observer failure is allowed to reject the attempt.
        if (problem.get() != null) throw new IllegalStateException("Death-products observation failed", problem.get());
        observed(() -> {
            require(eventScopes.isEmpty() && updates.get().isEmpty(), "Native death scopes are still open at END");
            stack.requireServerThread();
            List<EffectArrowRandomInput.Frame> frames = stack.capture();
            require(find(frames, FMLCommonHandler.class, "onPostServerTick", "()V", 0) >= 0,
                "Death-products settlement lacks the actual post-server-tick boundary");
            for (EffectArrowRandomInput.Frame frame : frames)
                require(!(frame.type == identity.tcHandler && frame.method.equals("livingTick") && frame.descriptor.equals(TC_DESC))
                    && !(frame.type == ForgeHooks.class && frame.method.equals("onLivingDeath"))
                    && !frame.descriptor.equals(DEATH_DESC), "Native death dispatch has not exited");
            for (Death death : deathOrder) if (death.eligible && !death.finalized) closeDeath(death);
            for (Product product : products) {
                if (product.adopted) continue;
                Death death = product.death;
                require(death.finalized && product.joinConfirmed && !product.join.isCanceled()
                    && product.uuid.equals(product.entity.getUniqueID()) && product.entity.worldObj == host.world()
                    && !host.owned(product.entity), "Exact death-product provenance did not close");
                if (product.update != null) {
                    require(product.update.closed && product.update.returned, "XP producer did not finish normally");
                    closeDeath(death);
                }
                host.adopt(product.entity);
                product.adopted = true;
                JsonObject raw = row("exact-instance-adopted");
                raw.addProperty("alias", product.alias);
                raw.addProperty("uuid", product.uuid.toString());
                raw.addProperty("death", death.serial);
                raw.addProperty("joinWasConfirmed", true);
                raw.addProperty("finalDeathCanceled", death.event.isCanceled());
                raw.addProperty("finalJoinCanceled", product.join.isCanceled());
                raw.add("typedStack", typed(frames));
                raw.add("state", state(product.entity));
                observations.add(raw);
            }
        });
        if (problem.get() != null) throw new IllegalStateException("Death-products closure failed", problem.get());
    }

    private void closeDeath(Death death) {
        require(death.lowest && !death.event.isCanceled() && death.exactLethalSource && complete(death.deathHarvest)
            && death.actor.getHealth() <= 0 && !death.actor.isEntityAlive()
            && death.actor.worldObj == host.world() && host.role(death.actor).equals(death.role),
            "Controlled native death token did not close");
        harvesting = death;
        finalHarvest = null;
        try {
            host.harvest(death.actor);
        } finally {
            harvesting = null;
        }
        require(complete(finalHarvest), "Controlled native post-death ledger incomplete");
        JsonObject raw = row("death-token-closed");
        raw.addProperty("death", death.serial);
        raw.addProperty("finalDeathCanceled", death.event.isCanceled());
        raw.addProperty("victimHealth", death.actor.getHealth());
        raw.addProperty("victimAlive", death.actor.isEntityAlive());
        raw.add("postNativeLedger", finalHarvest);
        observations.add(raw);
        death.finalized = true;
    }

    void observeState() {
        observed(() -> {
            for (Product product : products) if (product.adopted) {
                JsonObject raw = row("native-product-state");
                raw.addProperty("alias", product.alias);
                raw.add("state", state(product.entity));
                observations.add(raw);
            }
        });
    }

    void stopCapture() {
        observed(() -> {
            stopped = true;
            for (Death death : deathOrder) if (death.eligible) {
                boolean reached20 = false;
                for (Update update : updateOrder)
                    if (update.death == death && update.entryDeathTime == 19 && update.closed && update.returned
                        && update.row.get("exitDeathTime").getAsInt() == 20) reached20 = true;
                death.row.addProperty("native20ReachedWithinWindow", reached20);
                death.row.addProperty("actualDeathTimeAtCaptureEnd", death.actor.deathTime);
                death.row.addProperty("native20Status", reached20 ? "OBSERVED_NATIVE_EXIT"
                    : "WINDOW_TRUNCATED_BEFORE_NATIVE_20_EXIT");
                death.row.addProperty("windowComplete", host.completeWindow());
                death.row.addProperty("captureEndReason", host.completeWindow() ? "ORIGINAL_400_FRAME_WINDOW_END" : "ORIGINAL_FAILURE_BOUNDARY");
            }
        });
    }

    void finishCleanup(boolean successful) {
        observed(() -> {
            JsonObject raw = row("original-cleanup-boundary");
            raw.addProperty("cleanupSucceeded", successful);
            for (Product product : products) if (product.adopted) {
                require(!successful || (!host.world().loadedEntityList.contains(product.entity)
                    && host.world().getEntityByID(product.entity.getEntityId()) != product.entity),
                    "Exact adopted product remained after original cleanup");
            }
            observations.add(raw);
        });
        if (current == this) current = null;
    }

    JsonObject snapshot() {
        JsonObject value = copy(identity.proof);
        value.add("observations", new JsonParser().parse(observations.toString()));
        value.addProperty("failure", problem.get() == null ? null : problem.get().toString());
        value.addProperty("observerCalls", observerCalls);
        value.addProperty("observerCpuNanos", observerCpuNanos);
        value.addProperty("observerWallNanos", observerWallNanos);
        value.addProperty("openEventScopes", eventScopes.size());
        value.addProperty("openUpdateScopes", updates.get().size());
        value.addProperty("captureStopped", stopped);
        value.addProperty("nativeGetterCallsByObserver", 0);
        value.addProperty("nativeRngCallsByObserver", 0);
        return value;
    }

    private Update updateFor(EntityLivingBase actor) {
        require(!updates.get().isEmpty() && updates.get().peek().actor == actor,
            "Native amount has no exact current receiver scope");
        return updates.get().peek();
    }

    private final class Death {
        final LivingDeathEvent event;
        final EntityLivingBase actor;
        final String role;
        final int serial;
        final boolean eligible;
        final JsonObject row;
        final List<Product> aspects = new ArrayList<>();
        JsonObject deathHarvest;
        boolean lowest, exactLethalSource, finalized;

        Death(LivingDeathEvent event, String role, int serial) {
            this.event = event;
            this.actor = event.entityLiving;
            this.role = role;
            this.serial = serial;
            eligible = role != null && host.active() && actor.worldObj == host.world() && host.owned(actor);
            row = row("native-death-token");
            row.addProperty("death", serial);
            row.addProperty("role", role);
            row.addProperty("eligible", eligible);
            row.addProperty("victimIdentity", System.identityHashCode(actor));
            row.addProperty("victimUuid", actor.getUniqueID().toString());
            row.addProperty("eventIdentity", System.identityHashCode(event));
            row.addProperty("sourceIdentity", System.identityHashCode(event.source));
            row.addProperty("sourceType", event.source.getDamageType());
            row.addProperty("canceledAtHighest", event.isCanceled());
        }
    }

    private static final class Update {
        final DeathProductsProtocol session;
        final EntityLivingBase actor;
        final Death death;
        final int entryDeathTime, serial;
        final boolean eligible;
        final JsonObject row;
        final List<Product> products = new ArrayList<>();
        int nativeAmount, remaining;
        long sum;
        boolean closed, returned, amountObserved;

        Update(DeathProductsProtocol session, EntityLivingBase actor, Death death, boolean eligible, int serial) {
            this.session = session;
            this.actor = actor;
            this.death = death;
            this.eligible = eligible;
            this.serial = serial;
            entryDeathTime = actor.deathTime;
            row = session.row("native-death-update");
            row.addProperty("update", serial);
            row.addProperty("eligible", eligible);
            row.addProperty("victimIdentity", System.identityHashCode(actor));
            row.addProperty("victimUuid", actor.getUniqueID().toString());
            row.addProperty("entryDeathTime", entryDeathTime);
            if (death != null) row.addProperty("death", death.serial);
        }
    }

    private static final class Product {
        final Entity entity;
        final UUID uuid;
        final String kind, alias;
        final Death death;
        Update update;
        int constructorAmount;
        JsonObject row;
        EntityJoinWorldEvent join;
        boolean joinConfirmed, adopted;

        Product(Entity entity, String kind, Death death, String alias) {
            this.entity = entity;
            this.uuid = entity.getUniqueID();
            this.kind = kind;
            this.death = death;
            this.alias = alias;
        }
    }

    private JsonObject row(String kind) {
        require(observations.size() < 24000 && products.size() < 1024, "Death-products original observation capacity exceeded");
        JsonObject value = new JsonObject();
        value.addProperty("kind", kind);
        value.addProperty("frame", host.frame());
        value.addProperty("serverTick", host.server().getTickCounter());
        value.addProperty("worldTick", host.world().getTotalWorldTime());
        return value;
    }

    private JsonObject state(Entity entity) {
        JsonObject value = new JsonObject();
        value.addProperty("class", entity.getClass().getName());
        value.addProperty("classIdentity", System.identityHashCode(entity.getClass()));
        value.addProperty("entityIdentity", System.identityHashCode(entity));
        value.addProperty("worldIdentity", System.identityHashCode(entity.worldObj));
        value.addProperty("uuid", entity.getUniqueID().toString());
        value.addProperty("entityId", entity.getEntityId());
        value.addProperty("xBits", Long.toHexString(Double.doubleToLongBits(entity.posX)));
        value.addProperty("yBits", Long.toHexString(Double.doubleToLongBits(entity.posY)));
        value.addProperty("zBits", Long.toHexString(Double.doubleToLongBits(entity.posZ)));
        value.addProperty("dead", entity.isDead);
        value.addProperty("ticksExisted", entity.ticksExisted);
        value.addProperty("loaded", host.world().loadedEntityList.contains(entity));
        if (entity.getClass() == EntityXPOrb.class) value.addProperty("xpValue", ((EntityXPOrb) entity).xpValue);
        else value.add("aspect", identity.aspectState(entity));
        NBTTagCompound nbt = new NBTTagCompound();
        entity.writeToNBT(nbt);
        value.add("nbt", SleepAttributePortSnapshot.binaryNbt(nbt));
        return value;
    }

    private void thread() {
        require(Thread.currentThread() == host.thread(), "Death-products observer moved off its actual server thread");
    }

    private static boolean complete(JsonObject ledger) {
        return ledger != null && ledger.get("complete").getAsBoolean() && !ledger.get("tailIncomplete").getAsBoolean()
            && (!ledger.get("resetObserved").getAsBoolean() || ledger.get("resetWasExpected").getAsBoolean());
    }

    private static int split(int remaining) {
        for (int threshold : XP_SPLITS) if (remaining >= threshold) return threshold;
        throw new IllegalStateException("Positive native XP remainder required");
    }

    private static boolean same(double first, double second) {
        return Double.doubleToLongBits(first) == Double.doubleToLongBits(second);
    }

    private static int find(List<EffectArrowRandomInput.Frame> frames, Class<?> type, String method,
        String descriptor, int from) {
        for (int i = Math.max(0, from); i < frames.size(); i++) {
            EffectArrowRandomInput.Frame frame = frames.get(i);
            if (frame.type == type && frame.method.equals(method) && frame.descriptor.equals(descriptor)) return i;
        }
        return -1;
    }

    private static boolean nativeDeathAfter(List<EffectArrowRandomInput.Frame> frames, int after) {
        for (int i = after + 1; i < frames.size(); i++) {
            EffectArrowRandomInput.Frame frame = frames.get(i);
            if (EntityLivingBase.class.isAssignableFrom(frame.type) && frame.descriptor.equals(DEATH_DESC)
                && (frame.method.equals("onDeath") || frame.method.equals("func_70645_a"))) return true;
        }
        return false;
    }

    private static JsonArray typed(List<EffectArrowRandomInput.Frame> frames) {
        JsonArray result = new JsonArray();
        for (EffectArrowRandomInput.Frame frame : frames) {
            JsonObject value = new JsonObject();
            value.addProperty("class", frame.type.getName());
            value.addProperty("classIdentity", System.identityHashCode(frame.type));
            value.addProperty("method", frame.method);
            value.addProperty("descriptor", frame.descriptor);
            result.add(value);
        }
        return result;
    }

    private static JsonObject copy(JsonObject value) {
        return DeathProductsIdentity.copy(value);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
