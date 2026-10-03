import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import com.google.gson.*;
import io.netty.buffer.*;
import cn.nukkit.nbt.NBTIO;
import cn.nukkit.nbt.tag.*;
import cn.nukkit.level.format.leveldb.*;
import cn.nukkit.level.format.leveldb.serializer.ChunkSectionSerializers;
import cn.nukkit.level.format.leveldb.structure.*;
import org.iq80.leveldb.*;
import org.iq80.leveldb.impl.Iq80DBFactory;

public class PackWorld {
    static byte[] decode(String value) {
        return Base64.getDecoder().decode(value);
    }
    static BufferedReader open(Path input) throws IOException {
        return new BufferedReader(new InputStreamReader(
            new GZIPInputStream(Files.newInputStream(input)),
            java.nio.charset.StandardCharsets.UTF_8));
    }
    static byte[] entityBytes(ListTag<CompoundTag> list, boolean tile,
            int x, int z) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (CompoundTag entity : list.getAll()) {
            if (!entity.contains("id"))
                throw new IOException("Entity missing id");
            if (tile) {
                if (!entity.contains("x") || !entity.contains("y") ||
                    !entity.contains("z") ||
                    Math.floorDiv(entity.getInt("x"),16) != x ||
                    Math.floorDiv(entity.getInt("z"),16) != z)
                    throw new IOException("Invalid block entity coordinates");
            } else if (!entity.contains("Pos")) {
                throw new IOException("Entity missing Pos");
            }
            NBTIO.write(entity,bytes,ByteOrder.LITTLE_ENDIAN);
        }
        return bytes.toByteArray();
    }
    static Map<String,byte[]> values(JsonObject row) throws Exception {
        int x = row.get("chunkX").getAsInt();
        int z = row.get("chunkZ").getAsInt();
        if (row.get("schema").getAsInt() != 3)
            throw new IOException("Unexpected schema");
        CompoundTag level = OfflineNbt.read(decode(
            row.get("nbtBase64").getAsString()))
            .getCompound("Level");
        if (level.getInt("xPos") != x || level.getInt("zPos") != z)
            throw new IOException("NBT coordinate mismatch");

        Map<String,byte[]> values = new LinkedHashMap<>();
        values.put("version",new byte[]{
            (byte)LevelDBConstants.LATEST_CHUNK_VERSION});
        // Preserve generation flags; do not skip indexed source chunks.
        int state = level.getBoolean("TerrainPopulated") ? 2 :
            level.getBoolean("TerrainGenerated") ? 1 : 0;
        values.put("finalization",ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN).putInt(state-1).array());

        int[] heights = level.getIntArray("HeightMap");
        byte[] biomes;
        if (level.contains("BiomeColors")) {
            biomes = LegacyBiomes.decode(level.getIntArray("BiomeColors"));
        } else {
            biomes = level.getByteArray("Biomes");
        }
        if (heights.length != 256 || biomes.length != 256)
            throw new IOException("Unsupported height map or biomes at " + x+","+z);
        ByteBuffer terrain = ByteBuffer.allocate(768).order(ByteOrder.LITTLE_ENDIAN);
        for (int height : heights) {
            if (height < 0 || height > 65535)
                throw new IOException("Height outside range");
            terrain.putShort((short)height);
        }
        terrain.put(biomes);
        values.put("terrain",terrain.array());
        values.put("tiles",entityBytes(
            level.getList("TileEntities",CompoundTag.class),true,x,z));
        values.put("entities",entityBytes(
            level.getList("Entities",CompoundTag.class),false,x,z));
        values.put("ticks",decode(row.get("ticksBase64").getAsString()));

        Map<Integer,Map<Integer,StateBlockStorage>> sections = new TreeMap<>();
        for (JsonElement element : row.getAsJsonArray("storages")) {
            JsonObject storage = element.getAsJsonObject();
            int y = storage.get("y").getAsInt();
            int layer = storage.get("layer").getAsInt();
            ByteBuf buffer = Unpooled.wrappedBuffer(
                decode(storage.get("storageBase64").getAsString()));
            try {
                StateBlockStorage restored = RoundTripTarget.strictRead(buffer);
                if (buffer.isReadable()) throw new IOException("Trailing bytes");
                if (sections.computeIfAbsent(y,k->new TreeMap<>())
                        .put(layer,restored) != null)
                    throw new IOException("Duplicate storage");
            } finally { buffer.release(); }
        }
        for (Map.Entry<Integer,Map<Integer,StateBlockStorage>> section :
                sections.entrySet()) {
            int count = Collections.max(section.getValue().keySet())+1;
            StateBlockStorage[] storage = new StateBlockStorage[count];
            for (int layer=0; layer<count; layer++) {
                storage[layer] = section.getValue().get(layer);
                if (storage[layer] == null)
                    throw new IOException("Missing layer");
            }
            ByteBuf buffer = Unpooled.buffer();
            try {
                buffer.writeByte(LevelDBConstants.LATEST_SUBCHUNK_VERSION);
                ChunkSectionSerializers.serialize(buffer,storage,section.getKey(),
                    LevelDBConstants.LATEST_SUBCHUNK_VERSION);
                byte[] payload = new byte[buffer.readableBytes()];
                buffer.readBytes(payload);
                values.put("section:"+section.getKey(),payload);
            } finally { buffer.release(); }
        }
        for (CompoundTag section :
                level.getList("Sections",CompoundTag.class).getAll()) {
            byte[] light = section.getByteArray("BlockLight");
            if (light.length != 0 && light.length != 2048)
                throw new IOException("Invalid block light");
            if (light.length != 0)
                values.put("light:"+section.getByte("Y"),light);
        }
        return values;
    }
    static byte[] key(String name,int x,int z) {
        if (name.startsWith("section:"))
            return LevelDBKey.SUB_CHUNK_PREFIX.getKey(x,z,
                Integer.parseInt(name.substring(8)),0);
        if (name.startsWith("light:"))
            return LevelDBKey.NUKKIT_BLOCK_LIGHT.getKey(x,z,
                Integer.parseInt(name.substring(6)),0);
        LevelDBKey type;
        switch(name) {
            case "version": type=LevelDBKey.VERSION; break;
            case "finalization": type=LevelDBKey.STATE_FINALIZATION; break;
            case "terrain": type=LevelDBKey.DATA_2D; break;
            case "tiles": type=LevelDBKey.BLOCK_ENTITIES; break;
            case "entities": type=LevelDBKey.ENTITIES; break;
            case "ticks": type=LevelDBKey.PENDING_TICKS; break;
            default: throw new IllegalArgumentException(name);
        }
        return type.getKey(x,z,0);
    }
    public static void main(String[] args) {
        try { run(args); System.exit(0); }
        catch(Throwable error) {
            error.printStackTrace(System.err);
            System.exit(1);
        }
    }
    static void run(String[] args) throws Exception {
        Path input=Paths.get(args[0]);
        Path output=Paths.get(args[1]).toAbsolutePath().normalize();
        if (!output.startsWith(Paths.get("after").toRealPath()))
            throw new IOException("Output outside after directory");
        Path source=Paths.get(args[2]);
        Files.createDirectories(output);
        Path metadata=output.resolve("level.dat");
        if (!Files.exists(metadata)) {
            CompoundTag world=OfflineNbt.readCompressed(Files.readAllBytes(source))
                .getCompound("Data");
            world.putString("LevelName",output.getFileName().toString());
            // No new terrain generation during conversion validation.
            world.putString("generatorName","void");
            world.putString("generatorOptions","");
            world.putInt("NetworkVersion",LevelDBConstants.PALETTE_VERSION);
            world.putInt("StorageVersion",LevelDBConstants.LATEST_SUBCHUNK_VERSION);
            byte[] nbt=NBTIO.write(world,ByteOrder.LITTLE_ENDIAN);
            byte[] file=ByteBuffer.allocate(8+nbt.length)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(8).putInt(nbt.length).put(nbt).array();
            Files.write(metadata,file,StandardOpenOption.CREATE_NEW);
        }
        Options options=new Options().createIfMissing(true)
            .compressionType(CompressionType.ZLIB_RAW)
            .cacheSize(8L*1024*1024).blockSize(64*1024);
        long count=0;
        try (DB db=Iq80DBFactory.factory.open(output.resolve("db").toFile(),options);
             BufferedReader reader=open(input)) {
            String line;
            while ((line=reader.readLine()) != null) {
                JsonObject row=JsonParser.parseString(line).getAsJsonObject();
                int x=row.get("chunkX").getAsInt(),z=row.get("chunkZ").getAsInt();
                Map<String,byte[]> values=values(row);
                try (WriteBatch batch=db.createWriteBatch()) {
                    for (Map.Entry<String,byte[]> entry:values.entrySet())
                        batch.put(key(entry.getKey(),x,z),entry.getValue());
                    db.write(batch);
                }
                count++;
                if (count%100==0)
                    System.err.println("DB_PROGRESS Chunks="+count);
            }
            // Flush the region before recording completion.
            try (WriteBatch flush=db.createWriteBatch()) {
                db.write(flush,new WriteOptions().sync(true));
            }
        }
        long verified=0;
        try (DB db=Iq80DBFactory.factory.open(output.resolve("db").toFile(),
                 new Options().createIfMissing(false)
                    .compressionType(CompressionType.ZLIB_RAW)
                    .cacheSize(8L*1024*1024));
             BufferedReader reader=open(input)) {
            String line;
            while ((line=reader.readLine()) != null) {
                JsonObject row=JsonParser.parseString(line).getAsJsonObject();
                int x=row.get("chunkX").getAsInt(),z=row.get("chunkZ").getAsInt();
                for (Map.Entry<String,byte[]> entry:values(row).entrySet())
                    if (!Arrays.equals(entry.getValue(),
                            db.get(key(entry.getKey(),x,z))))
                        throw new IOException("DB readback mismatch "+x+","+z+
                            " "+entry.getKey());
                verified++;
            }
        }
        if (verified != count) throw new IOException("Count mismatch");
        System.err.println("PACK_PASS Chunks="+count+" Verified="+verified);
    }
}
