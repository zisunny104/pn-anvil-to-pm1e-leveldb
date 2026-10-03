import cn.nukkit.nbt.NBTIO;
import cn.nukkit.nbt.tag.CompoundTag;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

public class SourcePaletteVersion {
    public static void main(String[] args) throws Exception {
        Set<Integer> versions = new TreeSet<>();
        int count = 0;
        InputStream resource = SourcePaletteVersion.class.getClassLoader()
            .getResourceAsStream("canonical_block_states.nbt");
        if (resource == null)
            throw new IOException("Missing canonical_block_states.nbt");

        try (BufferedInputStream input = new BufferedInputStream(resource)) {
            while (true) {
                input.mark(1);
                if (input.read() == -1) break;
                input.reset();
                CompoundTag state =
                    NBTIO.read(input, ByteOrder.BIG_ENDIAN, true);
                if (!state.contains("version"))
                    throw new IOException("Missing version: " +
                        state.getString("name"));
                versions.add(state.getInt("version"));
                count++;
            }
        }
        System.out.println("CanonicalStates=" + count);
        System.out.println("Versions=" + versions);
        if (versions.size() != 1)
            throw new IOException("Multiple versions; require per-state handling");

        int version = versions.iterator().next();
        Files.writeString(Paths.get("reports/source-palette-version.txt"),
                          Integer.toString(version));
        System.out.println("SourcePaletteVersion=" + version);
    }
}
