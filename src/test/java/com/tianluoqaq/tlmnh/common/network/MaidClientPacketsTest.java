package com.tianluoqaq.tlmnh.common.network;

import static org.junit.Assert.*;

import net.minecraft.network.INetHandler;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.github.tartaricacid.touhoulittlemaid.network.message.PlayMaidSoundMessage;
import com.github.tartaricacid.touhoulittlemaid.network.message.SendEffectMessage;

import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;

public class MaidClientPacketsTest {

    private MaidClientPackets.Sink previous;

    @Before
    public void rememberBinding() {
        previous = MaidClientPackets.current();
    }

    @After
    public void restoreBinding() {
        MaidClientPackets.bind(previous);
    }

    @Test
    public void clientHandlersDeliverOriginalMessagesToBoundSink() throws ReflectiveOperationException {
        var sink = new RecordingSink();
        MaidClientPackets.bind(sink);
        var sound = new PlayMaidSoundMessage();
        var effects = new SendEffectMessage();
        var snapshot = new MaidAttackSnapshot();
        var context = context(Side.CLIENT);

        assertNull(new MaidSoundNetwork.Handler().onMessage(sound, context));
        assertNull(new MaidEffectNetwork.Handler().onMessage(effects, context));
        assertNull(new MaidTaskConfigNetwork.SnapshotHandler().onMessage(snapshot, context));

        assertSame(sound, sink.sound);
        assertSame(effects, sink.effects);
        assertSame(snapshot, sink.snapshot);
    }

    @Test
    public void serverHandlersDoNotDeliverClientMessages() throws ReflectiveOperationException {
        var sink = new RecordingSink();
        MaidClientPackets.bind(sink);
        var context = context(Side.SERVER);

        assertNull(new MaidSoundNetwork.Handler().onMessage(new PlayMaidSoundMessage(), context));
        assertNull(new MaidEffectNetwork.Handler().onMessage(new SendEffectMessage(), context));
        assertNull(new MaidTaskConfigNetwork.SnapshotHandler().onMessage(new MaidAttackSnapshot(), context));

        assertNull(sink.sound);
        assertNull(sink.effects);
        assertNull(sink.snapshot);
    }

    @Test
    public void nullBindingRestoresSafeCommonSideReceiver() {
        var sink = new RecordingSink();
        MaidClientPackets.bind(sink);
        MaidClientPackets.bind(null);

        assertNotNull(MaidClientPackets.current());
        MaidClientPackets.enqueueMaidSound(new PlayMaidSoundMessage());
        MaidClientPackets.enqueueMaidEffects(new SendEffectMessage());
        MaidClientPackets.enqueueMaidAttackSnapshot(new MaidAttackSnapshot());

        assertNull(sink.sound);
        assertNull(sink.effects);
        assertNull(sink.snapshot);
    }

    private static MessageContext context(Side side) throws ReflectiveOperationException {
        var constructor = MessageContext.class.getDeclaredConstructor(INetHandler.class, Side.class);
        constructor.setAccessible(true);
        return constructor.newInstance(null, side);
    }

    private static final class RecordingSink implements MaidClientPackets.Sink {

        private PlayMaidSoundMessage sound;
        private SendEffectMessage effects;
        private MaidAttackSnapshot snapshot;

        @Override
        public void enqueueMaidSound(PlayMaidSoundMessage message) {
            sound = message;
        }

        @Override
        public void enqueueMaidEffects(SendEffectMessage message) {
            effects = message;
        }

        @Override
        public void enqueueMaidAttackSnapshot(MaidAttackSnapshot message) {
            snapshot = message;
        }
    }
}
