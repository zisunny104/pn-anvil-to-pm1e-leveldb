import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.iq80.leveldb.*;
import org.iq80.leveldb.impl.Iq80DBFactory;
import cn.nukkit.level.format.leveldb.*;

public class AuditWorlds {
    static long coordinate(int x,int z) {
        return ((long)x << 32) | (z & 0xffffffffL);
    }
    static int integer(byte[] key,int offset) {
        return (key[offset]&255) |
            ((key[offset+1]&255)<<8) |
            ((key[offset+2]&255)<<16) |
            ((key[offset+3]&255)<<24);
    }
    public static void main(String[] args) {
        try {
            run(args);
            System.exit(0);
        } catch(Throwable error) {
            error.printStackTrace();
            System.exit(1);
        }
    }
    static void run(String[] args) throws Exception {
        for(String world:args) {
            Set<Long> expected=new HashSet<>();
            try(BufferedReader input=Files.newBufferedReader(
                    Paths.get("reports/chunks.tsv"))) {
                String line;
                while((line=input.readLine())!=null) {
                    String[] fields=line.split("\t");
                    if(fields.length>=5 && fields[0].equals(world)) {
                        long coordinate=coordinate(
                            Integer.parseInt(fields[3]),
                            Integer.parseInt(fields[4]));
                        if(!expected.add(coordinate))
                            throw new IOException("Duplicate source coordinate");
                    }
                }
            }
            int sourceCount=expected.size();
            if(sourceCount==0) throw new IOException("Empty inventory");
            long versions=0, sections=0, tiles=0, entities=0, ticks=0;
            Options options=new Options().createIfMissing(false)
                .compressionType(CompressionType.ZLIB_RAW)
                .cacheSize(4L*1024*1024);
            System.out.println("AUDIT_START "+world+" SourceChunks="+sourceCount);
            try(DB db=Iq80DBFactory.factory.open(
                    Paths.get("after",world,"db").toFile(),options);
                DBIterator iterator=db.iterator()) {
                iterator.seekToFirst();
                while(iterator.hasNext()) {
                    Map.Entry<byte[],byte[]> entry=iterator.next();
                    byte[] key=entry.getKey();
                    byte[] value=entry.getValue();
                    if(key.length==9 && key[8]==',') {
                        if(value.length!=1 ||
                           (value[0]&255)!=LevelDBConstants.LATEST_CHUNK_VERSION)
                            throw new IOException("Unexpected chunk version");
                        long coordinate=coordinate(integer(key,0),integer(key,4));
                        if(!expected.remove(coordinate))
                            throw new IOException("Unexpected database coordinate");
                        versions++;
                    } else if(key.length==10 && key[8]=='/') {
                        if(value.length==0)
                            throw new IOException("Empty section record");
                        sections++;
                    } else if(key.length==9 && value.length>0) {
                        if(key[8]=='1') tiles++;
                        if(key[8]=='2') entities++;
                        if(key[8]=='3') ticks++;
                    }
                }
            }
            if(!expected.isEmpty() || versions!=sourceCount)
                throw new IOException(world+" missing="+expected.size()+
                    " source="+sourceCount+" database="+versions);
            System.out.println("AUDIT_PASS "+world+
                " SourceChunks="+sourceCount+" DatabaseChunks="+versions+
                " Sections="+sections+" BlockEntityRecords="+tiles+
                " EntityRecords="+entities+" TickRecords="+ticks);
        }
        Files.writeString(Paths.get("reports/database-audit.ok"),
            java.time.Instant.now().toString()+"\n");
        System.out.println("ALL_COORDINATES_PASS");
    }
}
