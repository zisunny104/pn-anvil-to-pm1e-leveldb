import cn.nukkit.nbt.NBTIO;
import cn.nukkit.nbt.tag.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import com.google.gson.Gson;

public class SourcePropertyTypes {
    public static void main(String[] args) throws Exception {
        Map<String,Map<String,Integer>> schemas = new TreeMap<>();
        InputStream resource = SourcePropertyTypes.class.getClassLoader()
            .getResourceAsStream("canonical_block_states.nbt");
        if (resource == null) throw new IOException("Missing palette");
        try (BufferedInputStream in = new BufferedInputStream(resource)) {
            while (true) {
                in.mark(1);
                if (in.read() == -1) break;
                in.reset();
                CompoundTag state = NBTIO.read(in, ByteOrder.BIG_ENDIAN, true);
                String name = state.getString("name");
                Map<String,Integer> schema =
                    schemas.computeIfAbsent(name, k -> new TreeMap<>());
                for (Map.Entry<String,Tag> entry :
                        state.getCompound("states").getTags().entrySet()) {
                    int type = entry.getValue().getId();
                    Integer previous = schema.putIfAbsent(entry.getKey(), type);
                    if (previous != null && previous != type)
                        throw new IOException("Conflicting type: " +
                            name + "/" + entry.getKey());
                }
            }
        }
        Files.writeString(Paths.get("reports/source-property-types.json"),
                          new Gson().toJson(schemas));
        System.out.println("Source block schemas: " + schemas.size());
    }
}
