import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import cn.nukkit.nbt.NBTIO;
import cn.nukkit.nbt.tag.*;

public class WorldInventory {
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(".").toAbsolutePath().normalize();
        Map<String,Long> entityTypes = new TreeMap<>();
        long totalChunks = 0;
        try (BufferedWriter report = Files.newBufferedWriter(
                root.resolve("reports/chunks.tsv"))) {
            report.write("world\tregion\tslot\tx\tz\tsections\tmultiLayerSections\tblockEntities\tentities\tpendingTicks\n");
            for (String world : args) {
                List<Path> regions = new ArrayList<>();
                try (DirectoryStream<Path> files = Files.newDirectoryStream(
                        root.resolve("before").resolve(world).resolve("region"), "*.mca")) {
                    for (Path file : files) regions.add(file);
                }
                regions.sort(Comparator.comparing(Path::toString));
                long chunks = 0, multi = 0, tiles = 0, entities = 0, ticks = 0;
                int completed = 0;
                for (Path region : regions) {
                    try (RandomAccessFile file = new RandomAccessFile(region.toFile(), "r")) {
                        if (file.length() < 8192)
                            throw new IOException("Region too short: " + region);
                        byte[] header = new byte[4096];
                        file.readFully(header);
                        for (int slot = 0; slot < 1024; slot++) {
                            int p = slot*4;
                            int sector = ((header[p]&255)<<16) |
                                ((header[p+1]&255)<<8) | (header[p+2]&255);
                            int count = header[p+3]&255;
                            if (sector == 0 && count == 0) continue;
                            long start = sector*4096L;
                            if (sector < 2 || count == 0 ||
                                start + count*4096L > file.length())
                                throw new IOException("Bad index: " + region + "/" + slot);
                            file.seek(start);
                            int length = file.readInt();
                            int compression = file.readUnsignedByte();
                            if (length < 1 || (long)length+4 > count*4096L)
                                throw new IOException("Bad length: " + region + "/" + slot);
                            byte[] bytes = new byte[length-1];
                            file.readFully(bytes);
                            InputStream input = new ByteArrayInputStream(bytes);
                            if (compression == 2) input = new InflaterInputStream(input);
                            else if (compression == 1) input = new GZIPInputStream(input);
                            else if (compression != 3)
                                throw new IOException("Unknown compression: " + region);
                            CompoundTag level;
                            try (InputStream decoded = input) {
                                level = NBTIO.read(decoded).getCompound("Level");
                            }
                            if (!level.contains("xPos") || !level.contains("zPos"))
                                throw new IOException("Missing chunk coordinates: " + region);
                            ListTag<CompoundTag> sections =
                                level.getList("Sections", CompoundTag.class);
                            int layered = 0;
                            for (int i = 0; i < sections.size(); i++)
                                if (sections.get(i).getList("Storage", CompoundTag.class).size() > 1)
                                    layered++;
                            ListTag<CompoundTag> blockEntities =
                                level.getList("TileEntities", CompoundTag.class);
                            for (int i = 0; i < blockEntities.size(); i++) {
                                String id = blockEntities.get(i).getString("id");
                                entityTypes.merge(id, 1L, Long::sum);
                            }
                            int entityCount = level.getList("Entities", CompoundTag.class).size();
                            int tickCount = level.getList("TileTicks", CompoundTag.class).size();
                            report.write(world + "\t" + region.getFileName() + "\t" +
                                slot + "\t" + level.getInt("xPos") + "\t" +
                                level.getInt("zPos") + "\t" + sections.size() + "\t" +
                                layered + "\t" + blockEntities.size() + "\t" +
                                entityCount + "\t" + tickCount + "\n");
                            chunks++; multi += layered;
                            tiles += blockEntities.size();
                            entities += entityCount; ticks += tickCount;
                        }
                    }
                    report.flush();
                    completed++;
                    System.out.println(world + " Regions=" + completed + "/" +
                        regions.size() + " Chunks=" + chunks);
                }
                totalChunks += chunks;
                System.out.println("WORLD_SUMMARY " + world + " Chunks=" + chunks +
                    " MultiLayerSections=" + multi + " BlockEntities=" + tiles +
                    " Entities=" + entities + " PendingTicks=" + ticks);
            }
        }
        for (Map.Entry<String,Long> entry : entityTypes.entrySet())
            System.out.println("BLOCK_ENTITY_TYPE " + entry.getKey() + "=" + entry.getValue());
        System.out.println("INVENTORY_PASS TotalChunks=" + totalChunks);
        Files.writeString(root.resolve("reports/inventory.ok"),
            "TotalChunks=" + totalChunks + "\n");
    }
}
