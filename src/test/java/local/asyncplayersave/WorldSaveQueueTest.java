package local.asyncplayersave;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WorldSaveQueueTest {
    public static void main(String[] args) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch release = new CountDownLatch(1);
        try {
            WorldSaveQueue queue = new WorldSaveQueue(executor, 256);
            CompletableFuture<Void> first = queue.submit(List.of("level"), () -> release.await(), () -> {});
            CompletableFuture<Void> captured = queue.checkpoint();
            if (captured.isDone()) throw new AssertionError("world checkpoint completed before file durability");
            release.countDown(); first.get(2, TimeUnit.SECONDS); captured.get(2, TimeUnit.SECONDS);
            AtomicBoolean dirty = new AtomicBoolean();
            queue.submit(List.of("scoreboard", "raids"), () -> { throw new IOException("disk failure"); }, () -> dirty.set(true))
                .handle((ignored, error) -> null).get(2, TimeUnit.SECONDS);
            failed(queue.checkpoint()); failed(queue.checkpoint());
            if (!dirty.get()) throw new AssertionError("failure did not request dirty restoration");
            CompletableFuture<Void> failedCapture = queue.checkpoint();
            queue.submit(List.of("scoreboard"), () -> {}, () -> {}).get(2, TimeUnit.SECONDS);
            failed(queue.checkpoint()); // raids still failed; partial recovery must not hide it.
            queue.submit(List.of("raids"), () -> {}, () -> {}).get(2, TimeUnit.SECONDS);
            queue.checkpoint().get(2, TimeUnit.SECONDS); failed(failedCapture);
        } finally { release.countDown(); executor.shutdown(); executor.awaitTermination(3, TimeUnit.SECONDS); }
        List<Runnable> work = new ArrayList<>();
        WorldSaveQueue full = new WorldSaveQueue(work::add, 2);
        full.submit(List.of("a"), () -> {}, () -> {}); full.submit(List.of("b"), () -> {}, () -> {});
        try { full.submit(List.of("c"), () -> {}, () -> {}); throw new AssertionError("capacity limit ignored"); }
        catch (RejectedExecutionException expected) {}
        failedAfterDraining(full, work);
        work.clear(); full.submit(List.of("c"), () -> {}, () -> {}); work.getFirst().run(); full.checkpoint().join();
        AtomicBoolean reject = new AtomicBoolean(true);
        WorldSaveQueue rejection = new WorldSaveQueue(r -> { if (reject.get()) throw new RejectedExecutionException("closed"); r.run(); }, 2);
        try { rejection.submit(List.of("x"), () -> {}, () -> {}); throw new AssertionError("executor rejection ignored"); }
        catch (RejectedExecutionException expected) {}
        failed(rejection.checkpoint()); reject.set(false);
        rejection.submit(List.of("x"), () -> {}, () -> {}).join(); rejection.checkpoint().join();
        System.out.println("PASS: world durability waits, persistent failures, dirty restoration, immutable checkpoints, per-path recovery, capacity and rejection");
    }
    private static void failedAfterDraining(WorldSaveQueue q, List<Runnable> work) { for (Runnable r : work) r.run(); failed(q.checkpoint()); }
    private static void failed(CompletableFuture<Void> future) {
        try { future.join(); throw new AssertionError("world failure hidden"); } catch (CompletionException expected) {}
    }
}
