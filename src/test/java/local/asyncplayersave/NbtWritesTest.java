package local.asyncplayersave;
import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.*;
public final class NbtWritesTest {
 public static void main(String[] args)throws Exception {
  Path dir=Files.createTempDirectory("durable-nbt-test"),target=dir.resolve("scoreboard.dat");
  CompoundTag expected=new CompoundTag();byte[] bytes=new byte[100000];new Random(13).nextBytes(bytes);
  expected.putByteArray("payload",bytes);expected.putString("complete","gzip must finish before file force");
  int[] count={0,0};
  AtomicSave.Operations operations=new AtomicSave.Operations(){
   @Override void forceFile(Path path)throws java.io.IOException {
    byte[] compressed=Files.readAllBytes(path);
    if(compressed.length<2||(compressed[0]&255)!=31||(compressed[1]&255)!=139)throw new AssertionError("writer did not finish gzip before force");
    if(!NbtIo.readCompressed(path,NbtAccounter.unlimitedHeap()).equals(expected))throw new AssertionError("incomplete NBT at persistence boundary");
    count[0]++;super.forceFile(path);
   }
   @Override void forceDirectory(Path path)throws java.io.IOException {count[1]++;super.forceDirectory(path);}
  };
  AtomicSave.writeBatch(Map.of(target,tmp->NbtWrites.write(expected,tmp)),Set.of(),operations);
  if(count[0]!=1||count[1]!=1)throw new AssertionError("extra durability confirmations: "+Arrays.toString(count));
  if(!NbtIo.readCompressed(target,NbtAccounter.unlimitedHeap()).equals(expected))throw new AssertionError("NBT round trip differs");
  Files.delete(target);Files.delete(dir);
  System.out.println("PASS: large compressed NBT finishes before one file force and one directory force, round-trip content matches");
 }
}
