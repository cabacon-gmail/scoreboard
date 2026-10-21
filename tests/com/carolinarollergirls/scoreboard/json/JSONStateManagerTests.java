package com.carolinarollergirls.scoreboard.json;

import static org.junit.Assert.assertEquals;

import java.util.HashMap;

import org.junit.Before;
import org.junit.Test;

import com.carolinarollergirls.scoreboard.json.JSONStateListener.StateTrie;

public class JSONStateManagerTests {
    private JSONStateManager jsm;
    private TestListener listener;

    @Before
    public void setup() {
        jsm = new JSONStateManager(false);
        listener = new TestListener();
    }

    public class TestListener implements JSONStateListener {
        public StateTrie state;
        public StateTrie changed;
        public int num_updates;

        @SuppressWarnings("hiding")
        @Override
        public void sendUpdates(StateTrie state, StateTrie changed) {
            this.state = state;
            this.changed = changed;
            num_updates++;
        }
    }

    @Test
    public void listener_gets_update_on_register() {
        jsm.updateState("foo", "bar");
        jsm.register(listener);
        HashMap<String, Object> hm = new HashMap<>();
        hm.put("foo", "bar");

        jsm.waitForSent();
        assertEquals(1, listener.num_updates);
        assertEquals(hm, listener.state.getAll(false));
    }

    @Test
    public void no_update_on_noop_change() {
        jsm.register(listener);
        jsm.waitForSent();
        assertEquals(1, listener.num_updates);
        jsm.updateState("foo", "bar");
        jsm.waitForSent();
        assertEquals(2, listener.num_updates);
        jsm.updateState("foo", "bar");
        jsm.waitForSent();
        assertEquals(2, listener.num_updates);
    }

    @Test
    public void delete_subtree() {
        jsm.updateState("foo.12.34", "bar");
        jsm.updateState("foo.12.34.56", "bar");
        jsm.updateState("foo.78.90", "bar");
        jsm.register(listener);
        jsm.waitForSent();
        assertEquals(3, listener.state.size());

        jsm.updateState("foo.12", null);
        jsm.waitForSent();
        assertEquals(1, listener.state.size());
        assertEquals(2, listener.changed.size());
    }

    @Test
    public void replace_subtree() {
        jsm.updateState("foo.12.34", "bar");
        jsm.updateState("foo.12.34.56", "bar");
        jsm.updateState("foo.78.90", "bar");
        jsm.register(listener);
        jsm.waitForSent();
        assertEquals(3, listener.state.size());

        StateTrie updates = new StateTrie();
        updates.add("foo.12", null);
        updates.add("foo.12.78", "bar");
        jsm.updateState(updates);
        jsm.waitForSent();
        assertEquals(2, listener.state.size());
        assertEquals(3, listener.changed.size());
    }

    @Test
    public void do_not_delete_prefix() {
        jsm.updateState("foo.12.34", "bar");
        jsm.updateState("foo.12.34.56", "bar");
        jsm.updateState("foo.78.90", "bar");
        jsm.register(listener);
        jsm.waitForSent();
        assertEquals(3, listener.state.size());
        assertEquals(1, listener.num_updates);

        jsm.updateState("foo.1", null);
        jsm.waitForSent();
        assertEquals(3, listener.state.size());
        assertEquals(1, listener.num_updates);
    }

    @Test
    public void no_update_when_nothing_deleted() {
        jsm.register(listener);
        jsm.waitForSent();
        assertEquals(1, listener.num_updates);
        jsm.updateState("foo", null);
        jsm.waitForSent();
        assertEquals(1, listener.num_updates);
    }

    @Test
    public void delete_and_recreate_in_one_update() {
        jsm.register(listener);
        jsm.updateState("foo.1", "bar");
        StateTrie updates = new StateTrie();
        updates.add("foo", null);
        updates.add("foo.1", "baz");
        jsm.updateState(updates);

        HashMap<String, Object> hm = new HashMap<>();
        hm.put("foo.1", "baz");
        jsm.waitForSent();
        assertEquals(hm, listener.state.getAll(false));
        assertEquals(1, listener.changed.size());
    }

    /** Applies what it is sent the way a WebSocket client does: the first call is the full state, then changes. */
    public class MirrorListener implements JSONStateListener {
        public final java.util.Map<String, Object> mirror = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public void sendUpdates(StateTrie state, StateTrie changed) {
            mirror.putAll(changed.getAll(false));
        }
    }

    @Test
    public void listeners_can_connect_and_disconnect_during_updates() throws Exception {
        final java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.List<MirrorListener> stayed = new java.util.concurrent.CopyOnWriteArrayList<>();
        Thread[] churn = new Thread[4];
        for (int t = 0; t < churn.length; t++) {
            churn[t] = new Thread() {
                @Override
                public void run() {
                    while (!done.get()) {
                        MirrorListener l = new MirrorListener();
                        jsm.register(l);
                        if (stayed.size() < 50) {
                            stayed.add(l);
                        } else {
                            jsm.unregister(l);
                        }
                    }
                }
            };
            churn[t].start();
        }
        try {
            for (int i = 0; i < 3000; i++) { jsm.updateState("key" + (i % 20), i); }
        } finally {
            done.set(true);
            for (Thread t : churn) { t.join(); }
        }
        jsm.waitForSent();
        // Every listener that stayed must end up with exactly the manager's state: no update lost or overtaken by
        // the snapshot it got when it connected.
        for (MirrorListener l : stayed) { assertEquals(jsm.getState(), l.mirror); }
    }
}
