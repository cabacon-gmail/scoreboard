package com.carolinarollergirls.scoreboard.json;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.carolinarollergirls.scoreboard.json.JSONStateListener.StateTrie;

import io.prometheus.client.Histogram;

public class JSONStateManager {
    public JSONStateManager(boolean useMetrics) {
        this.useMetrics = useMetrics;
        updateStateDuration = useMetrics ? Histogram.build()
                                               .name("crg_json_state_disk_snapshot_duration_seconds")
                                               .help("Time spent writing JSON state snapshots to disk")
                                               .register()
                                         : null;
        updateStateUpdates = useMetrics ? Histogram.build()
                                              .name("crg_json_update_state_updates")
                                              .help("Updates sent to JSONStateManager.updateState function")
                                              .exponentialBuckets(1, 2, 10)
                                              .register()
                                        : null;
    }

    public void register(JSONStateListener source) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        // Under the same lock as updateState, so the snapshot is queued before any later update: the listener
        // sees the full state first, then every change after it, in order.
        synchronized (lock) {
            sources.put(source, executor);
            final StateTrie localState = state;
            enqueue(executor, new Runnable() {
                @Override
                public void run() {
                    source.sendUpdates(localState, localState);
                }
            });
        }
    }

    public void unregister(JSONStateListener source) {
        synchronized (lock) {
            ExecutorService executor = sources.remove(source);
            if (executor != null) {
                // Updates still queued for this listener will never run.
                pending.addAndGet(-executor.shutdownNow().size());
            }
        }
    }

    public void updateState(String key, Object value) {
        StateTrie updates = new StateTrie();
        updates.add(key, value);
        updateState(updates);
    }

    public void updateState(StateTrie updates) {
        Histogram.Timer timer = useMetrics ? updateStateDuration.startTimer() : null;

        // One lock for the state and the listener map: listeners can connect and disconnect from other threads
        // while an update is being fanned out. Queuing is cheap; sending happens on each listener's own thread,
        // as the WS connections can block if the kernel TCP send buffer fills up.
        synchronized (lock) {
            state = state.cloneAndMergeChangeTrie(updates);
            if (!updates.isEmpty()) {
                final StateTrie localState = state;
                final StateTrie localChanged = updates;
                for (Map.Entry<JSONStateListener, ExecutorService> e : sources.entrySet()) {
                    final JSONStateListener localSource = e.getKey();
                    enqueue(e.getValue(), new Runnable() {
                        @Override
                        public void run() {
                            localSource.sendUpdates(localState, localChanged);
                        }
                    });
                }
            }
        }

        if (useMetrics) { timer.observeDuration(); }
        if (useMetrics) { updateStateUpdates.observe(updates.size()); }
    }

    /** Queues {@code task} on a listener's executor, counting it until it has run. */
    private void enqueue(ExecutorService executor, final Runnable task) {
        pending.incrementAndGet();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    task.run();
                } finally { pending.decrementAndGet(); }
            }
        });
    }

    public Map<String, Object> getState(boolean filterSecrets) {
        StateTrie s;
        synchronized (lock) { s = state; }
        return s.getAll(filterSecrets);
    }
    public Map<String, Object> getState() { return getState(false); }

    /** Wait for all pending updates to be sent out. This is intended for unit tests. */
    protected void waitForSent() {
        while (pending.get() > 0) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {};
        }
    }

    /** Guards {@code state} and {@code sources}. */
    private final Object lock = new Object();
    private final Map<JSONStateListener, ExecutorService> sources = new HashMap<>();
    private StateTrie state = new StateTrie();
    private final AtomicInteger pending = new AtomicInteger();

    private boolean useMetrics;
    private final Histogram updateStateDuration;
    private final Histogram updateStateUpdates;
}
