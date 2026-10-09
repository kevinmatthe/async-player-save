package local.asyncplayersave;
import java.nio.file.*;
public class AtomicSaveTest {
 public static void main(String[] a)throws Exception{
  Path d=Files.createTempDirectory("player-save-test"),p=d.resolve("p.dat");Files.writeString(p,"old");
  try{AtomicSave.write(p,t->{Files.writeString(t,"broken");throw new java.io.IOException("injected");});throw new AssertionError("failure hidden");}catch(java.io.IOException expected){}
  if(!Files.readString(p).equals("old"))throw new AssertionError("failed save replaced valid data");
  AtomicSave.write(p,t->Files.writeString(t,"new"));
  if(!Files.readString(p).equals("new")||!Files.readString(d.resolve("p.dat_old")).equals("old"))throw new AssertionError("replacement/backup invalid");
  try(var s=Files.list(d)){if(s.count()!=2)throw new AssertionError("temporary files leaked");}
  System.out.println("PASS: failed write preserves live data, successful replacement retains previous save, temporary files cleaned");
 }
}
