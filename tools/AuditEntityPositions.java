import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.iq80.leveldb.*;
import org.iq80.leveldb.impl.Iq80DBFactory;
import org.cloudburstmc.nbt.*;
import cn.nukkit.level.format.leveldb.LevelDBKey;

public class AuditEntityPositions {
    static int intLE(byte[] k, int p) {
        return (k[p]&255) | ((k[p+1]&255)<<8)
            | ((k[p+2]&255)<<16) | ((k[p+3]&255)<<24);
    }
    static int chunk(Object value) throws IOException {
        if (!(value instanceof Number))
            throw new IOException("Invalid entity coordinate");
        double n=((Number)value).doubleValue();
        if (!Double.isFinite(n) || n<Integer.MIN_VALUE || n>Integer.MAX_VALUE)
            throw new IOException("Entity coordinate outside range");
        return Math.floorDiv((int)Math.floor(n),16);
    }
    public static void main(String[] args) throws Exception {
        for (String world : args) {
            long total=0, misplaced=0, missing=0, invalid=0;
            Options options=new Options().createIfMissing(false)
                .cacheSize(8L*1024*1024);
            try (DB db=Iq80DBFactory.factory.open(
                    Paths.get(world,"db").toFile(),options);
                 DBIterator iterator=db.iterator()) {
                iterator.seekToFirst();
                while (iterator.hasNext()) {
                    Map.Entry<byte[],byte[]> entry=iterator.next();
                    byte[] key=entry.getKey();
                    if (key.length!=9 || key[8]!=(byte)'2') continue;
                    int sx=intLE(key,0), sz=intLE(key,4);
                    ByteArrayInputStream input=
                        new ByteArrayInputStream(entry.getValue());
                    try (NBTInputStream reader=NbtUtils.createReaderLE(input)) {
                        while (input.available()>0) {
                            Object tag=reader.readTag();
                            if (!(tag instanceof NbtMap))
                                throw new IOException("Invalid entity compound");
                            NbtMap entity=(NbtMap)tag;
                            Object id=entity.get("id");
                            if(!(id instanceof String) || ((String)id).trim().isEmpty()) {
                                invalid++;
                                System.out.println("ENTITY_INVALID_ID_PRESERVED "+world+" Pos="+entity.get("Pos"));
                            }
                            Object raw=entity.get("Pos");
                            if (!(raw instanceof List) || ((List<?>)raw).size()!=3)
                                throw new IOException("Invalid Pos at "+sx+","+sz);
                            List<?> pos=(List<?>)raw;
                            int dx=chunk(pos.get(0)), dz=chunk(pos.get(2));
                            total++;
                            if (dx==sx && dz==sz) continue;
                            misplaced++;
                            byte[] version=db.get(
                                LevelDBKey.VERSION.getKey(dx,dz,0));
                            boolean exists=version!=null && version.length==1;
                            if (!exists) missing++;
                            if (misplaced<=20)
                                System.out.println("MISPLACED "+world+
                                    " id="+entity.get("id")+
                                    " From="+sx+","+sz+" To="+dx+","+dz+
                                    " DestinationExists="+exists);
                        }
                    }
                }
            }
            System.out.println("ENTITY_POSITION_AUDIT "+world+
                " Total="+total+" Misplaced="+misplaced+
                " MissingDestination="+missing+" InvalidId="+invalid);
            if(misplaced!=0 || missing!=0)
                throw new IOException("Entity position audit failed: "+world);
        }
        System.exit(0);
    }
}
