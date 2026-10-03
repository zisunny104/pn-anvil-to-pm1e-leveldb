import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import com.google.gson.*;
import org.cloudburstmc.nbt.*;
import io.netty.buffer.*;
import cn.nukkit.level.format.leveldb.*;
import cn.nukkit.level.format.leveldb.structure.*;
import cn.nukkit.level.util.BitArrayVersion;

public class CompactTarget {
    static final Gson GSON = new Gson();
    static final Map<NbtMap,BlockStateSnapshot> canonical = new HashMap<>();
    static final Map<String,BlockStateSnapshot> cache = new HashMap<>();
    static JsonObject schemas;
    static int sourceVersion;
    static long migrated;

    static NbtMap identity(NbtMap state) {
        return NbtMap.builder().putString("name",state.getString("name"))
            .putCompound("states",state.getCompound("states")).build();
    }

    static BlockStateSnapshot resolve(JsonObject row) throws Exception {
        String key = row.get("stateId").getAsString();
        BlockStateSnapshot found = cache.get(key);
        if (found != null) return found;
        String name = row.get("name").getAsString();
        JsonObject schema = schemas.getAsJsonObject(name);
        NbtMapBuilder properties = NbtMap.builder();
        for (Map.Entry<String,JsonElement> entry :
                row.getAsJsonObject("properties").entrySet()) {
            if (schema == null || !schema.has(entry.getKey()))
                throw new IOException("Missing property schema: " + key);
            String value = entry.getValue().getAsString();
            int type = schema.get(entry.getKey()).getAsInt();
            if (type == 1) properties.putByte(entry.getKey(),Byte.parseByte(value));
            else if (type == 3) properties.putInt(entry.getKey(),Integer.parseInt(value));
            else if (type == 8) properties.putString(entry.getKey(),value);
            else throw new IOException("Unsupported property type: " + key);
        }
        NbtMap state = NbtMap.builder().putString("name",name)
            .putCompound("states",properties.build()).build();
        found = canonical.get(state);
        if (found == null) {
            NbtMap updated = BlockStateMapping.get().updateVanillaState(
                state.toBuilder().putInt("version",sourceVersion).build());
            found = BlockStateMapping.get().getStateUnsafe(updated);
            if (found != null) migrated++;
        }
        if (found == null) throw new IOException("UNRESOLVED " + key);
        String targetName = found.getVanillaState().getString("name");
        if ((!name.equals("minecraft:air") && targetName.equals("minecraft:air")) ||
            targetName.equals("minecraft:info_update") ||
            targetName.equals("minecraft:unknown"))
            throw new IOException("Rejected replacement: " + key);
        cache.put(key,found);
        return found;
    }

    public static void main(String[] args) {
        try { run(args); System.exit(0); }
        catch (Throwable error) {
            error.printStackTrace(System.err);
            System.exit(1);
        }
    }

    static void run(String[] args) throws Exception {
        if (args.length != 2)
            throw new IOException("Usage: CompactTarget input.gz output.gz");
        schemas = JsonParser.parseString(Files.readString(
            Paths.get("reports/source-property-types.json"))).getAsJsonObject();
        sourceVersion = Integer.parseInt(Files.readString(
            Paths.get("reports/source-palette-version.txt")).trim());
        if (sourceVersion != 17959425)
            throw new IOException("Unexpected source palette version");
        java.lang.reflect.Field field =
            BlockStateMapping.class.getDeclaredField("paletteMap");
        field.setAccessible(true);
        Map<?,?> palette = (Map<?,?>)field.get(BlockStateMapping.get());
        for (Object object : palette.keySet()) {
            NbtMap state = (NbtMap)object;
            BlockStateSnapshot previous = canonical.put(identity(state),
                BlockStateMapping.get().getStateUnsafe(state));
            if (previous != null)
                throw new IOException("Ambiguous canonical state");
        }
        BlockStateSnapshot air = canonical.get(NbtMap.builder()
            .putString("name","minecraft:air")
            .putCompound("states",NbtMap.builder().build()).build());
        if (air == null) throw new IOException("Missing target air");
        long chunks = 0, positions = 0, layers = 0;
        try (BufferedReader input = new BufferedReader(new InputStreamReader(
                 new GZIPInputStream(Files.newInputStream(Paths.get(args[0]))),
                 java.nio.charset.StandardCharsets.UTF_8));
             BufferedWriter output = new BufferedWriter(new OutputStreamWriter(
                 new GZIPOutputStream(Files.newOutputStream(Paths.get(args[1]),
                     StandardOpenOption.CREATE_NEW)),
                 java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                JsonObject row = JsonParser.parseString(line).getAsJsonObject();
                if (row.get("schema").getAsInt() != 2)
                    throw new IOException("Unexpected intermediate schema");
                JsonArray serialized = new JsonArray();
                Set<String> seen = new HashSet<>();
                for (JsonElement element : row.getAsJsonArray("storages")) {
                    JsonObject source = element.getAsJsonObject();
                    int y = source.get("y").getAsInt();
                    int layer = source.get("layer").getAsInt();
                    if (y < -4 || y > 19 || layer < 0 || layer > 1 ||
                        !seen.add(y + ":" + layer))
                        throw new IOException("Invalid or duplicate storage layer");
                    List<BlockStateSnapshot> states = new ArrayList<>();
                    for (JsonElement definition : source.getAsJsonArray("palette"))
                        states.add(resolve(definition.getAsJsonObject()));
                    JsonArray indices = source.getAsJsonArray("indices");
                    if (indices.size() != 4096 || states.isEmpty())
                        throw new IOException("Invalid source storage");
                    StateBlockStorage storage = new StateBlockStorage(
                        BitArrayVersion.V1.createPalette(4096),
                        new ArrayList<>(Collections.singletonList(air)));
                    for (int i=0; i<4096; i++) {
                        int id = indices.get(i).getAsInt();
                        if (id < 0 || id >= states.size())
                            throw new IOException("Invalid source palette index");
                        int index = ChunkBuilder.getSectionIndex(
                            i>>>8, i&15, (i>>>4)&15);
                        storage.setBlockStateUnsafe(index,states.get(id));
                    }
                    ByteBuf buffer = Unpooled.buffer();
                    byte[] bytes;
                    try {
                        storage.writeToStorage(buffer);
                        bytes = new byte[buffer.readableBytes()];
                        buffer.getBytes(buffer.readerIndex(),bytes);
                    } finally { buffer.release(); }
                    ByteBuf reread = Unpooled.wrappedBuffer(bytes);
                    try {
                        StateBlockStorage restored = RoundTripTarget.strictRead(reread);
                        if (reread.isReadable())
                            throw new IOException("Trailing storage bytes");
                        for (int i=0; i<4096; i++) {
                            int index = ChunkBuilder.getSectionIndex(
                                i>>>8, i&15, (i>>>4)&15);
                            NbtMap wanted = states.get(indices.get(i).getAsInt())
                                .getVanillaState();
                            if (!wanted.equals(restored.getBlockState(index)
                                    .getVanillaState()))
                                throw new IOException("Storage readback mismatch");
                        }
                    } finally { reread.release(); }
                    JsonObject packed = new JsonObject();
                    packed.addProperty("y",y);
                    packed.addProperty("layer",layer);
                    packed.addProperty("storageBase64",
                        Base64.getEncoder().encodeToString(bytes));
                    serialized.add(packed);
                    positions += 4096;
                    layers++;
                }

                if (!row.has("ticks"))
                    throw new IOException("Missing tick export");
                List<NbtMap> ticks = new ArrayList<>();
                for (JsonElement element : row.getAsJsonArray("ticks")) {
                    JsonObject tick = element.getAsJsonObject();
                    BlockStateSnapshot state = resolve(
                        tick.getAsJsonObject("state"));
                    ticks.add(NbtMap.builder()
                        .putInt("x",tick.get("x").getAsInt())
                        .putInt("y",tick.get("y").getAsInt())
                        .putInt("z",tick.get("z").getAsInt())
                        .putLong("time",tick.get("time").getAsLong())
                        .putInt("p",tick.get("priority").getAsInt())
                        .putCompound("blockState",state.getVanillaState())
                        .build());
                }
                ByteArrayOutputStream tickBytes = new ByteArrayOutputStream();
                if (!ticks.isEmpty()) {
                    try (org.cloudburstmc.nbt.NBTOutputStream writer =
                            NbtUtils.createWriterLE(tickBytes)) {
                        writer.writeTag(NbtMap.builder()
                            .putInt("currentTick",0)
                            .putList("tickList",NbtType.COMPOUND,ticks).build());
                    }
                }
                row.addProperty("ticksBase64",
                    Base64.getEncoder().encodeToString(tickBytes.toByteArray()));
                row.remove("ticks");
row.addProperty("schema",3);
                row.add("storages",serialized);
                output.write(GSON.toJson(row));
                output.newLine();
                chunks++;
                if (chunks%100 == 0)
                    System.err.println("TARGET_PROGRESS Chunks=" + chunks +
                        " VerifiedPositions=" + positions);
            }
        }
        System.err.println("TARGET_PASS Chunks=" + chunks +
            " VerifiedPositions=" + positions + " Layers=" + layers +
            " UniqueStates=" + cache.size() + " Migrated=" + migrated);
    }
}
