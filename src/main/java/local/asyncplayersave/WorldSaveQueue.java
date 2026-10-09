package local.asyncplayersave;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Per-path results stay visible until an actual new submission replaces them. */
final class WorldSaveQueue {
    interface IOAction { void run() throws Exception; }
    private final Executor executor;
    private final int capacity;
    private final AtomicInteger outstanding = new AtomicInteger();
    private final Map<String, CompletableFuture<Void>> latest = new HashMap<>();

    WorldSaveQueue(Executor executor, int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.executor = Objects.requireNonNull(executor);
        this.capacity = capacity;
    }

    synchronized CompletableFuture<Void> submit(Collection<String> keys, IOAction action, Runnable onFailure) {
        Set<String> paths = Set.copyOf(keys);
        if (paths.isEmpty()) throw new IllegalArgumentException("save needs at least one path");
        CompletableFuture<Void> future = new CompletableFuture<>();
        paths.forEach(key -> latest.put(key, future));
        if (outstanding.get() >= capacity) {
            RejectedExecutionException error = new RejectedExecutionException("world save queue full");
            future.completeExceptionally(error);
            throw error;
        }
        outstanding.incrementAndGet();
        try {
            executor.execute(() -> {
                try {
                    action.run();
                    future.complete(null);
                } catch (Throwable error) {
                    try { onFailure.run(); } catch (Throwable callbackError) { error.addSuppressed(callbackError); }
                    future.completeExceptionally(error);
                } finally { outstanding.decrementAndGet(); }
            });
        } catch (RejectedExecutionException error) {
            outstanding.decrementAndGet(); future.completeExceptionally(error); throw error;
        }
        return future;
    }

    synchronized void recordFailure(String key, Throwable error) {
        latest.put(key, CompletableFuture.failedFuture(error));
    }

    synchronized CompletableFuture<Void> checkpoint() {
        return CompletableFuture.allOf(latest.values().toArray(CompletableFuture<?>[]::new));
    }
}
