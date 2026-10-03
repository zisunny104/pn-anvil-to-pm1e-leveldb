import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.iq80.leveldb.*;
import org.iq80.leveldb.impl.Iq80DBFactory;
import org.cloudburstmc.nbt.*;
import cn.nukkit.level.format.leveldb.LevelDBKey;

public class FixEntityPositions {
    static String coord(int x,int z) { return x+","+z; }
    static byte[] key(String coord) {
        String[] p=coord.split(",");
        return LevelDBKey.ENTITIES.getKey(
            Integer.parseInt(p[0]),Integer.parseInt(p[1]),0);
    }
    static Options options() {
        return new Options().createIfMissing(false)
            .cacheSize(8L*1024*1024);
    }
    public static void main(String[] args) throws Exception {
        Path backupRoot=Paths.get(args[0]);
        Files.createDirectory(backupRoot);
        for(int w=1;w<args.length;w++) {
            Path world=Paths.get(args[w]);
            Path database=world.resolve("db");
            Map<String,byte[]> originals=new TreeMap<>();
            Map<String,ByteArrayOutputStream> grouped=new TreeMap<>();
            Map<String,byte[]> changes=new TreeMap<>();
            long total=0,moved=0;

            try(DB db=Iq80DBFactory.factory.open(database.toFile(),options())) {
                try(DBIterator iterator=db.iterator()) {
                    iterator.seekToFirst();
                    while(iterator.hasNext()) {
                        Map.Entry<byte[],byte[]> entry=iterator.next();
                        byte[] k=entry.getKey();
                        if(k.length!=9 || k[8]!=(byte)'2') continue;
                        int sx=AuditEntityPositions.intLE(k,0);
                        int sz=AuditEntityPositions.intLE(k,4);
                        String source=coord(sx,sz);
                        byte[] bytes=entry.getValue();
                        originals.put(source,bytes);
                        ByteArrayInputStream input=new ByteArrayInputStream(bytes);
                        try(NBTInputStream reader=NbtUtils.createReaderLE(input)) {
                            while(input.available()>0) {
                                int start=bytes.length-input.available();
                                Object tag=reader.readTag();
                                int end=bytes.length-input.available();
                                if(!(tag instanceof NbtMap))
                                    throw new IOException("Invalid entity compound");
                                Object raw=((NbtMap)tag).get("Pos");
                                if(!(raw instanceof List) ||
                                    ((List<?>)raw).size()!=3)
                                    throw new IOException("Invalid Pos "+source);
                                List<?> pos=(List<?>)raw;
                                int dx=AuditEntityPositions.chunk(pos.get(0));
                                int dz=AuditEntityPositions.chunk(pos.get(2));
                                String target=coord(dx,dz);
                                if(!source.equals(target)) {
                                    byte[] version=db.get(
                                        LevelDBKey.VERSION.getKey(dx,dz,0));
                                    if(version==null || version.length!=1)
                                        throw new IOException(
                                            "Missing destination "+target);
                                    moved++;
                                }
                                grouped.computeIfAbsent(target,
                                    p->new ByteArrayOutputStream())
                                    .write(bytes,start,end-start);
                                total++;
                            }
                        }
                    }
                }

                Set<String> coordinates=new TreeSet<>(originals.keySet());
                coordinates.addAll(grouped.keySet());
                for(String c:coordinates) {
                    byte[] old=originals.get(c);
                    byte[] next=grouped.containsKey(c)
                        ?grouped.get(c).toByteArray():new byte[0];
                    if(old==null && next.length==0) continue;
                    if(!Arrays.equals(old,next)) changes.put(c,next);
                }

                Path backup=backupRoot.resolve(
                    world.getFileName()+"-entities.tsv");
                try(BufferedWriter out=Files.newBufferedWriter(
                        backup,StandardOpenOption.CREATE_NEW)) {
                    out.write("coordinate\toriginalBase64OrABSENT\n");
                    for(String c:changes.keySet()) {
                        byte[] old=db.get(key(c));
                        out.write(c+"\t"+(old==null?"ABSENT":
                            Base64.getEncoder().encodeToString(old)));
                        out.newLine();
                    }
                }
                System.out.println("BACKUP_PASS "+backup+
                    " ChangedKeys="+changes.size());

                try(WriteBatch batch=db.createWriteBatch()) {
                    for(Map.Entry<String,byte[]> e:changes.entrySet())
                        batch.put(key(e.getKey()),e.getValue());
                    db.write(batch,new WriteOptions().sync(true));
                }
            }

            try(DB db=Iq80DBFactory.factory.open(database.toFile(),options())) {
                for(Map.Entry<String,byte[]> e:changes.entrySet())
                    if(!Arrays.equals(db.get(key(e.getKey())),e.getValue()))
                        throw new IOException("Readback mismatch "+e.getKey());
            }
            System.out.println("ENTITY_REHOME_PASS "+world+
                " PreservedRecords="+total+" Moved="+moved+
                " VerifiedKeys="+changes.size());
        }
        System.exit(0);
    }
}
