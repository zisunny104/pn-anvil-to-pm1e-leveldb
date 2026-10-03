import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import com.google.gson.Gson;
import cn.nukkit.block.Block;
import cn.nukkit.blockstate.BlockState;
import cn.nukkit.level.format.anvil.ChunkSection;
import cn.nukkit.nbt.NBTIO;
import cn.nukkit.nbt.tag.*;

public class CompactExport {
    static Map<String,Object> describe(BlockState state) {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("name", state.getPersistenceName());
        row.put("stateId", state.getStateId());
        row.put("legacyId", state.getBlockId());
        row.put("legacyData", state.getDataStorage().toString());
        Map<String,String> properties = new TreeMap<>();
        for (String key : state.getPropertyNames())
            properties.put(key, state.getPersistenceValue(key));
        row.put("properties", properties);
        return row;
    }

    public static void main(String[] args) {
        try {
            run(args);
            System.exit(0);
        } catch (Throwable error) {
            error.printStackTrace(System.err);
            System.exit(1);
        }
    }

    static void run(String[] args) throws Exception {
        if (args.length != 2)
            throw new IOException("Usage: CompactExport region.mca output.jsonl.gz");
        Block.init();
        Gson gson = new Gson();
        long chunks = 0, positions = 0, layers = 0;
        Path output = Paths.get(args[1]);
        try (RandomAccessFile region = new RandomAccessFile(args[0], "r");
             BufferedWriter writer = new BufferedWriter(
                 new OutputStreamWriter(new GZIPOutputStream(
                     Files.newOutputStream(output, StandardOpenOption.CREATE_NEW)),
                     java.nio.charset.StandardCharsets.UTF_8))) {
            if (region.length() < 8192)
                throw new IOException("Region header too short");
            byte[] header = new byte[4096];
            region.readFully(header);
            String filename = Paths.get(args[0]).getFileName().toString();
            java.util.regex.Matcher match = java.util.regex.Pattern.compile(
                "(?:aquatic-)?r\\.(-?\\d+)\\.(-?\\d+)\\.mca").matcher(filename);
            if (!match.matches())
                throw new IOException("Unrecognized region name: " + filename);
            int rx = Integer.parseInt(match.group(1));
            int rz = Integer.parseInt(match.group(2));

            for (int slot = 0; slot < 1024; slot++) {
                int p = slot * 4;
                int sector = ((header[p]&255)<<16) |
                    ((header[p+1]&255)<<8) | (header[p+2]&255);
                int count = header[p+3]&255;
                if (sector == 0 && count == 0) continue;
                if (sector < 2 || count == 0 ||
                    ((long)sector + count)*4096 > region.length())
                    throw new IOException("Invalid sector at slot " + slot);
                region.seek((long)sector*4096);
                int length = region.readInt();
                int compression = region.readUnsignedByte();
                if (length < 1 || length+4L > count*4096L)
                    throw new IOException("Invalid length at slot " + slot);
                byte[] packed = new byte[length-1];
                region.readFully(packed);
                InputStream bytes = new ByteArrayInputStream(packed);
                InputStream decoded;
                if (compression == 1) decoded = new GZIPInputStream(bytes);
                else if (compression == 2) decoded = new InflaterInputStream(bytes);
                else if (compression == 3) decoded = bytes;
                else throw new IOException("Unknown compression " + compression);
                CompoundTag root;
                try (InputStream input = decoded) {
                    root = NBTIO.read(input);
                }
                CompoundTag level = root.getCompound("Level");
                int cx = level.getInt("xPos"), cz = level.getInt("zPos");
                if (cx != rx*32+(slot&31) || cz != rz*32+(slot>>>5))
                    throw new IOException("Chunk coordinate mismatch at slot " + slot);

                Map<String,Object> row = new LinkedHashMap<>();
                row.put("schema", 2);
                row.put("chunkX", cx);
                row.put("chunkZ", cz);
                row.put("nbtBase64", Base64.getEncoder().encodeToString(
                    NBTIO.write(root)));
                List<Map<String,Object>> exportedSections = new ArrayList<>();
                Set<Integer> sectionYs = new HashSet<>();
                ListTag<CompoundTag> sections =
                    level.getList("Sections", CompoundTag.class);
                for (CompoundTag section : sections.getAll()) {
                    ChunkSection decodedSection = new ChunkSection(section);
                    int sy = decodedSection.getY();
                    if (!sectionYs.add(sy))
                        throw new IOException("Duplicate section Y=" + sy);
                    int layerCount = Math.max(1,
                        section.getList("Storage", CompoundTag.class).size());
                    if (layerCount > 2)
                        throw new IOException("Unsupported layer count " + layerCount);
                    for (int layer = 0; layer < layerCount; layer++) {
                        List<Map<String,Object>> palette = new ArrayList<>();
                        Map<BlockState,Integer> ids = new HashMap<>();
                        int[] indices = new int[4096];
                        for (int x=0; x<16; x++)
                            for (int z=0; z<16; z++)
                                for (int y=0; y<16; y++) {
                                    BlockState state = decodedSection.getBlockState(
                                        x,y,z,layer);
                                    Integer id = ids.get(state);
                                    if (id == null) {
                                        id = palette.size();
                                        ids.put(state,id);
                                        palette.add(describe(state));
                                    }
                                    // Explicit XZY order; target uses its own index API.
                                    indices[(x<<8)|(z<<4)|y] = id;
                                }
                        Map<String,Object> storage = new LinkedHashMap<>();
                        storage.put("y",sy);
                        storage.put("layer",layer);
                        storage.put("palette",palette);
                        storage.put("indices",indices);
                        exportedSections.add(storage);
                        positions += 4096;
                        layers++;
                    }
                }

                List<Map<String,Object>> exportedTicks = new ArrayList<>();
                for (CompoundTag tick :
                        level.getList("TileTicks", CompoundTag.class).getAll()) {
                    if (!(tick.get("i") instanceof StringTag))
                        throw new IOException("Unsupported tick identifier");
                    String className = tick.getString("i");
                    if (!className.matches("Block[A-Za-z0-9_]+"))
                        throw new IOException("Invalid tick class: " + className);
                    Object object = Class.forName("cn.nukkit.block." + className)
                        .getDeclaredConstructor().newInstance();
                    if (!(object instanceof Block))
                        throw new IOException("Tick class is not a block");
                    Block block = (Block)object;
                    Map<String,Object> entry = new LinkedHashMap<>();
                    entry.put("x",tick.getInt("x"));
                    entry.put("y",tick.getInt("y"));
                    entry.put("z",tick.getInt("z"));
                    entry.put("time",tick.getInt("t"));
                    entry.put("priority",tick.getInt("p"));
                    entry.put("state",describe(BlockState.of(
                        block.getId(),block.getDamage())));
                    exportedTicks.add(entry);
                }
                row.put("ticks", exportedTicks);
row.put("storages",exportedSections);
                writer.write(gson.toJson(row));
                writer.newLine();
                chunks++;
                if (chunks%100 == 0)
                    System.err.println("EXPORT_PROGRESS Chunks=" + chunks);
            }
        }
        System.err.println("EXPORT_PASS Chunks=" + chunks +
            " Positions=" + positions + " Layers=" + layers);
    }
}
