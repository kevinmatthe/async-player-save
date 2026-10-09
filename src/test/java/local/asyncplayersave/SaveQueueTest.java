package local.asyncplayersave;
import java.util.*;
import java.util.concurrent.*;
public final class SaveQueueTest {
 public static void main(String[] args) throws Exception {
  SaveQueue q=new SaveQueue("test-save");
  CountDownLatch gate=new CountDownLatch(1);List<Integer> writes=Collections.synchronizedList(new ArrayList<>());
  q.submit("p",()->{gate.await();writes.add(1);});q.submit("p",()->writes.add(2));
  if(!writes.isEmpty())throw new AssertionError("submit must not wait for IO");
  CompletableFuture<Void> waiter=CompletableFuture.runAsync(()->q.await("p"));
  Thread.sleep(30);if(waiter.isDone())throw new AssertionError("load barrier returned before write");
  gate.countDown();waiter.get(2,TimeUnit.SECONDS);
  if(!writes.equals(List.of(1,2)))throw new AssertionError("write order changed: "+writes);
  q.submit("bad",()->{throw new java.io.IOException("disk failure");});
  try {q.await("bad");throw new AssertionError("failure was hidden");}catch(CompletionException expected){}
  q.submit("bad",()->writes.add(3));q.await("bad");
  q.close();if(!writes.equals(List.of(1,2,3)))throw new AssertionError("shutdown lost write");
  System.out.println("PASS: nonblocking submission, ordered writes, load barrier, failure propagation, recovery, shutdown drain");
 }
}
