package local.asyncplayersave;

import java.io.*;
import java.nio.file.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

/** Compression finishes here; AtomicSave forces the complete temporary file afterwards. */
final class NbtWrites {
    static void write(CompoundTag tag, Path path) throws IOException {
        try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(path,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING))) {
            NbtIo.writeCompressed(tag, output);
        }
    }
}
