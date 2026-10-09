package local.asyncplayersave;
import java.io.IOException;
import java.nio.file.*;
final class AtomicSave {
 interface Writer {void write(Path path)throws IOException;}
 static void write(Path target,Writer writer)throws IOException{
  Path tmp=Files.createTempFile(target.getParent(),target.getFileName()+"-", ".tmp"),backup=null;
  try{
   writer.write(tmp);
   if(Files.exists(target)){
    backup=Files.createTempFile(target.getParent(),target.getFileName()+"-backup-", ".tmp");
    Files.copy(target,backup,StandardCopyOption.REPLACE_EXISTING);
    move(backup,target.resolveSibling(target.getFileName()+"_old"));
   }
   move(tmp,target);
  }finally{Files.deleteIfExists(tmp);if(backup!=null)Files.deleteIfExists(backup);}
 }
 private static void move(Path src,Path dst)throws IOException{
  try{Files.move(src,dst,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
  catch(AtomicMoveNotSupportedException e){Files.move(src,dst,StandardCopyOption.REPLACE_EXISTING);}
 }
}
