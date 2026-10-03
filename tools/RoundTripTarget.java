import java.nio.file.*;
import java.io.*;
import java.util.*;
import com.google.gson.*;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import cn.nukkit.block.Block;
import cn.nukkit.level.format.leveldb.BlockStateMapping;
import cn.nukkit.level.format.leveldb.structure.BlockStateSnapshot;

public class RoundTripTarget {

    static cn.nukkit.level.format.leveldb.structure.StateBlockStorage
    strictRead(io.netty.buffer.ByteBuf buffer) throws Exception {
        int header = buffer.readUnsignedByte();
        if ((header & 1) != 0)
            throw new IOException("Unexpected network palette");
        int bits = header >>> 1;
        cn.nukkit.level.util.BitArrayVersion version = null;
        for (cn.nukkit.level.util.BitArrayVersion candidate :
                cn.nukkit.level.util.BitArrayVersion.values())
            if (candidate.getId() == bits) version = candidate;
        if (version == null)
            throw new IOException("Unknown palette bits: " + bits);

        int[] words = null;
        int paletteSize = 1;
        if (version != cn.nukkit.level.util.BitArrayVersion.V0) {
            words = new int[version.getWordsForSize(4096)];
            for (int i = 0; i < words.length; i++)
                words[i] = buffer.readIntLE();
            paletteSize = buffer.readIntLE();
        }
        if (paletteSize < 1 ||
                paletteSize - 1 > version.getMaxEntryValue())
            throw new IOException("Invalid palette size: " + paletteSize);

        List<BlockStateSnapshot> palette = new ArrayList<>();
        try (io.netty.buffer.ByteBufInputStream stream =
                 new io.netty.buffer.ByteBufInputStream(buffer);
             org.cloudburstmc.nbt.NBTInputStream reader =
                 org.cloudburstmc.nbt.NbtUtils.createReaderLE(stream)) {
            for (int i = 0; i < paletteSize; i++) {
                Object tag = reader.readTag();
                if (!(tag instanceof NbtMap))
                    throw new IOException("Palette entry is not a compound");
                NbtMap state = (NbtMap)tag;
                BlockStateSnapshot snapshot =
                    BlockStateMapping.get().getStateUnsafe(state);
                if (snapshot == null)
                    throw new IOException("Unregistered serialized state: " + state);
                palette.add(snapshot);
            }
        }
        return new cn.nukkit.level.format.leveldb.structure.StateBlockStorage(
            version.createPalette(4096, words), palette);
    }

public static void main(String[] args) throws Exception {

        java.lang.reflect.Field field =
            BlockStateMapping.class.getDeclaredField("paletteMap");
        field.setAccessible(true);
        Map<?,?> palette = (Map<?,?>)field.get(BlockStateMapping.get());
        System.out.println("RegisteredPaletteEntries=" + palette.size());
        if (palette.isEmpty())
            throw new IOException("Target palette is empty; stop before mapping");
        for (Object key : palette.keySet()) {
            NbtMap candidate = (NbtMap)key;
            if (candidate.getString("name").equals("minecraft:air")) {
                System.out.println("TargetAirSchema=" + candidate);
                break;
            }
        }

        JsonObject sourceSchemas = JsonParser.parseString(Files.readString(
            Paths.get("reports/source-property-types.json"))).getAsJsonObject();

        Map<String,BlockStateSnapshot> resolved = new HashMap<>();
        Set<String> seen = new HashSet<>();




        int matched = 0, unresolved = 0;
        int migrated = 0;
        int sourceVersion = Integer.parseInt(Files.readString(
            Paths.get("reports/source-palette-version.txt")).trim());
        if (sourceVersion <= 0)
            throw new IOException("Invalid source palette version");
        System.out.println("SourcePaletteVersion=" + sourceVersion);

        try (BufferedReader input = Files.newBufferedReader(Paths.get(args[0]))) {
            String line;
            while ((line = input.readLine()) != null) {
                JsonObject row = JsonParser.parseString(line).getAsJsonObject();
                if (!row.get("kind").getAsString().equals("block")) continue;
                String stateId = row.get("stateId").getAsString();
                if (!seen.add(stateId)) continue;

                NbtMapBuilder properties = NbtMap.builder();
                for (Map.Entry<String,JsonElement> entry :
                        row.getAsJsonObject("properties").entrySet()) {
                    JsonObject property = entry.getValue().getAsJsonObject();

                    String value = property.get("persistenceValue").getAsString();
                    String blockName = row.get("name").getAsString();
                    JsonObject schema = sourceSchemas.getAsJsonObject(blockName);
                    if (schema == null || !schema.has(entry.getKey()))
                        throw new IOException("Missing source property schema: " +
                            blockName + "/" + entry.getKey());
                    int nbtType = schema.get(entry.getKey()).getAsInt();
                    if (nbtType == 1)
                        properties.putByte(entry.getKey(), Byte.parseByte(value));
                    else if (nbtType == 3)
                        properties.putInt(entry.getKey(), Integer.parseInt(value));
                    else if (nbtType == 8)
                        properties.putString(entry.getKey(), value);
                    else
                        throw new IOException("Unsupported source NBT type: " + nbtType);

                }
                NbtMap state = NbtMap.builder()
                    .putString("name", row.get("name").getAsString())
                    .putCompound("states", properties.build()).build();


                BlockStateSnapshot target = null;
                for (Object key : palette.keySet()) {
                    NbtMap candidate = (NbtMap)key;
                    if (state.getString("name").equals(candidate.getString("name")) &&
                        state.getCompound("states").equals(candidate.getCompound("states"))) {
                        if (target != null)
                            throw new IOException("Ambiguous target state: " + stateId);
                        target = BlockStateMapping.get().getStateUnsafe(candidate);
                    }
                }


                NbtMap expected = state;
                boolean changed = false;
                if (target == null) {
                    NbtMap versioned = state.toBuilder()
                        .putInt("version", sourceVersion).build();
                    NbtMap updated =
                        BlockStateMapping.get().updateVanillaState(versioned);
                    target = BlockStateMapping.get().getStateUnsafe(updated);
                    if (target != null) {
                        String targetName = updated.getString("name");
                        if ((!state.getString("name").equals("minecraft:air")
                             && targetName.equals("minecraft:air")) ||
                            targetName.equals("minecraft:info_update") ||
                            targetName.equals("minecraft:unknown"))
                            throw new IOException("Rejected replacement: " +
                                stateId + " -> " + updated);
                        expected = updated;
                        changed = true;
                    }
                }
                if (target == null) {
                    unresolved++;
                    System.out.println("UNRESOLVED " + stateId);
                } else {
                    NbtMap actual = target.getVanillaState();
                    if (!expected.getString("name").equals(actual.getString("name")) ||
                        !expected.getCompound("states").equals(actual.getCompound("states")))
                        throw new IOException("Unexpected mapping: " + stateId +
                            " -> " + actual);


                    resolved.put(stateId, target);
                    matched++;

                    if (changed) {
                        migrated++;
                        System.out.println("MIGRATED " + stateId + " -> " + actual);
                    }

                    if (stateId.contains("coral") || stateId.contains("kelp") ||
                            stateId.contains("seagrass"))
                        System.out.println("MATCH " + stateId + " -> " + actual);
                }
            }
        }

        if (unresolved != 0)
            throw new IOException("Unresolved states; no serialization allowed");

        Path outputDir = Paths.get(args[1]);
        Files.createDirectory(outputDir);
        Map<String,cn.nukkit.level.format.leveldb.structure.StateBlockStorage>
            storages = new TreeMap<>();
        Map<String,BlockStateSnapshot[]> expected = new TreeMap<>();
        Map<String,boolean[]> occupied = new TreeMap<>();
        BlockStateSnapshot air = resolved.get("minecraft:air");
        if (air == null) throw new IOException("Missing canonical air");

        long positions = 0;
        try (BufferedReader input = Files.newBufferedReader(Paths.get(args[0]))) {
            String line;
            while ((line = input.readLine()) != null) {
                JsonObject row = JsonParser.parseString(line).getAsJsonObject();
                if (!row.get("kind").getAsString().equals("block")) continue;
                int x = row.get("x").getAsInt();
                int y = row.get("y").getAsInt();
                int z = row.get("z").getAsInt();
                int layer = row.get("layer").getAsInt();
                String key = Math.floorDiv(x,16) + "_" +
                    Math.floorDiv(z,16) + "_y" + Math.floorDiv(y,16) +
                    "_layer" + layer;

                if (!storages.containsKey(key)) {
                    storages.put(key,
                        new cn.nukkit.level.format.leveldb.structure.StateBlockStorage(
    cn.nukkit.level.util.BitArrayVersion.V1.createPalette(4096),
    new ArrayList<BlockStateSnapshot>(Collections.singletonList(air))));
                    BlockStateSnapshot[] states = new BlockStateSnapshot[4096];
                    Arrays.fill(states, air);
                    expected.put(key, states);
                    occupied.put(key, new boolean[4096]);
                }
                int index =
                    cn.nukkit.level.format.leveldb.structure.ChunkBuilder
                        .getSectionIndex(Math.floorMod(x,16),
                                         Math.floorMod(y,16),
                                         Math.floorMod(z,16));
                if (occupied.get(key)[index])
                    throw new IOException("Duplicate position: " + key + "/" + index);
                occupied.get(key)[index] = true;
                BlockStateSnapshot state =
                    resolved.get(row.get("stateId").getAsString());
                if (state == null) throw new IOException("Missing resolved state");
                storages.get(key).setBlockStateUnsafe(index, state);
                expected.get(key)[index] = state;
                positions++;
            }
        }

        long verified = 0;
        for (String key : storages.keySet()) {
            io.netty.buffer.ByteBuf buffer =
                io.netty.buffer.Unpooled.buffer();
            try {
                storages.get(key).writeToStorage(buffer);
                byte[] bytes = new byte[buffer.readableBytes()];
                buffer.getBytes(buffer.readerIndex(), bytes);
                Path file = outputDir.resolve(key + ".bin");
                Files.write(file, bytes, StandardOpenOption.CREATE_NEW);

                byte[] reread = Files.readAllBytes(file);
                io.netty.buffer.ByteBuf input =
                    io.netty.buffer.Unpooled.wrappedBuffer(reread);
                try {

                    cn.nukkit.level.format.leveldb.structure.StateBlockStorage restored =
                        strictRead(input);
                    if (input.isReadable())
                        throw new IOException("Trailing bytes: " + key);
                    for (int index = 0; index < 4096; index++) {
                        NbtMap wanted = expected.get(key)[index].getVanillaState();
                        NbtMap actual = restored.getBlockState(index).getVanillaState();
                        if (!wanted.equals(actual))
                            throw new IOException("Round-trip mismatch: " +
                                key + "/" + index + " expected=" + wanted +
                                " actual=" + actual);
                        if (occupied.get(key)[index]) verified++;
                    }
                    System.out.println("ROUNDTRIP PASS " + key);
                } finally {
                    input.release();
                }
            } finally {
                buffer.release();
            }
        }
        if (verified != positions)
            throw new IOException("Position count mismatch");
        System.out.println("SourcePositions=" + positions +
            " VerifiedPositions=" + verified +
            " StorageLayers=" + storages.size());
        System.out.println("UniqueStates=" + seen.size() +
                " Matched=" + matched + " Migrated=" + migrated + " Unresolved=" + unresolved);
        System.out.flush();
        System.exit(0);
    }
}
