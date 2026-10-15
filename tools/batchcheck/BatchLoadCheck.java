import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.lang.reflect.*;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.level.Level;
import cn.nukkit.level.format.leveldb.*;
import cn.nukkit.level.format.leveldb.structure.*;
import io.netty.buffer.*;
import org.iq80.leveldb.DB;

public class BatchLoadCheck extends PluginBase {
    final Set<org.cloudburstmc.nbt.NbtMap> checkedGameplayStates=new HashSet<>();

    void checkLeafSemantics(BlockStateSnapshot state) throws IOException {
        org.cloudburstmc.nbt.NbtMap tag=state.getVanillaState();
        if(!tag.getString("name").contains("leaves") || !checkedGameplayStates.add(tag)) return;
        cn.nukkit.block.Block block=cn.nukkit.block.Block.get(state.getLegacyId(),state.getLegacyData());
        if(!(block instanceof cn.nukkit.block.BlockLeaves))
            throw new IOException("CORE_LEAF_SEMANTICS unsupported leaf: "+tag);
        cn.nukkit.block.BlockLeaves leaf=(cn.nukkit.block.BlockLeaves)block;
        org.cloudburstmc.nbt.NbtMap flags=tag.getCompound("states");
        if(!flags.containsKey("persistent_bit") || !flags.containsKey("update_bit")
                || leaf.isPersistent()!=(flags.getByte("persistent_bit")!=0)
                || leaf.isCheckDecay()!=(flags.getByte("update_bit")!=0))
            throw new IOException("CORE_LEAF_SEMANTICS flag mismatch: "+tag);
    }
    Object sectionAt(Object chunk,int y) throws Exception {
        for(Method method:chunk.getClass().getMethods()) {
            if(!method.getName().equals("getSection") ||
               method.getParameterCount()!=1) continue;
            Class<?> type=method.getParameterTypes()[0];
            Object value;
            if(type==byte.class) value=Byte.valueOf((byte)y);
            else if(type==short.class) value=Short.valueOf((short)y);
            else if(type==int.class) value=Integer.valueOf(y);
            else continue;
            Object section=method.invoke(chunk,value);
            if(section!=null &&
                ((Number)section.getClass().getMethod("getY")
                    .invoke(section)).intValue()==y)
                return section;
        }
        Object array=chunk.getClass().getMethod("getSections").invoke(chunk);
        for(int i=0;i<Array.getLength(array);i++) {
            Object section=Array.get(array,i);
            if(section!=null &&
               ((Number)section.getClass().getMethod("getY")
                    .invoke(section)).intValue()==y)
                return section;
        }
        return null;
    }
    DB database(Object provider) throws Exception {
        for(Class<?> type=provider.getClass();type!=null;type=type.getSuperclass()) {
            try {
                Field field=type.getDeclaredField("db");
                field.setAccessible(true);
                return (DB)field.get(provider);
            } catch(NoSuchFieldException ignored) {}
        }
        throw new IOException("Database field not found");
    }

    public static int countStoredEntities(byte[] bytes)
            throws java.io.IOException {
        if(bytes==null || bytes.length==0) return 0;
        int count=0;
        java.io.ByteArrayInputStream input=
            new java.io.ByteArrayInputStream(bytes);
        try(org.cloudburstmc.nbt.NBTInputStream reader=
                org.cloudburstmc.nbt.NbtUtils.createReaderLE(input)) {
            while(input.available()>0) {
                Object tag=reader.readTag();
                if(!(tag instanceof org.cloudburstmc.nbt.NbtMap))
                    throw new java.io.IOException("Invalid entity record");
                org.cloudburstmc.nbt.NbtMap entity=
                    (org.cloudburstmc.nbt.NbtMap)tag;
                Object id=entity.get("id");
                if(!(id instanceof String) || ((String)id).trim().isEmpty()) {
                    System.out.println("ENTITY_INVALID_ID_PRESERVED Pos="+
                        entity.get("Pos")+" id="+id);
                    continue;
                }
                count++;
            }
        }
        return count;
    }

    public void onEnable() {
        try {
            long positions=0;
            int checked=0;
            for(String world:System.getProperty("converter.worlds", "").split(",")) {
                if(getServer().getLevelByName(world)==null)
                    getServer().loadLevel(world);
                Level level=getServer().getLevelByName(world);
                if(level==null) throw new IOException("World not loaded: "+world);
                LinkedHashMap<String,String[]> selected=new LinkedHashMap<>();
                int[] columns={6,7,8,9};
                int[] counts=new int[columns.length];
                try(BufferedReader input=Files.newBufferedReader(
                        Paths.get(System.getProperty("converter.inventory")))) {
                    String line;
                    while((line=input.readLine())!=null) {
                        String[] row=line.split("\\t");
                        if(row.length<10 || !row[0].equals(world)) continue;
                        boolean choose=false;
                        for(int i=0;i<columns.length;i++) {
                            if(counts[i]<6 &&
                               Integer.parseInt(row[columns[i]])>0) {
                                counts[i]++;
                                choose=true;
                            }
                        }
                        if(choose) selected.put(row[3]+","+row[4],row);
                        boolean complete=true;
                        for(int count:counts)
                            if(count<6) complete=false;
                        if(complete) break;
                    }
                }
                if(selected.isEmpty()) throw new IOException("No test chunks");
                for(String[] row:selected.values()) {
                    int x=Integer.parseInt(row[3]),z=Integer.parseInt(row[4]);
                    Object chunk=level.getChunk(x,z,false);
                    if(chunk==null) throw new IOException("Missing chunk "+x+","+z);
                    chunk.getClass().getMethod("initChunk").invoke(chunk);
                    Object provider=chunk.getClass().getMethod("getProvider")
                        .invoke(chunk);
                    DB db=database(provider);

                    for(int y=-4;y<=19;y++) {
                        byte[] bytes=db.get(LevelDBKey.SUB_CHUNK_PREFIX
                            .getKey(x,z,y,0));
                        if(bytes==null) continue;
                        ByteBuf buffer=Unpooled.wrappedBuffer(bytes);
                        try {
                            int version=buffer.readUnsignedByte();
                            if(version!=8 && version!=9)
                                throw new IOException("Unexpected section version");
                            int layers=buffer.readUnsignedByte();
                            if(version==9 && buffer.readByte()!=y)
                                throw new IOException("Section Y mismatch");
                            Object section=sectionAt(chunk,y);
                            if(!(section instanceof LevelDBChunkSection))
                                throw new IOException("Missing loaded section "+y);
                            StateBlockStorage[] actual=
                                ((LevelDBChunkSection)section).getStorages();
                            for(int layer=0;layer<layers;layer++) {
                                StateBlockStorage expected=
                                    RoundTripTarget.strictRead(buffer);
                                if(layer>=actual.length || actual[layer]==null)
                                    throw new IOException("Missing loaded layer");
                                for(int index=0;index<4096;index++) {
                                    checkLeafSemantics(actual[layer].getBlockState(index));
                                    if(!expected.getBlockState(index).getVanillaState()
                                        .equals(actual[layer].getBlockState(index)
                                            .getVanillaState()))
                                        throw new IOException("Block mismatch "+
                                            world+" "+x+","+z+" Y="+y+
                                            " layer="+layer+" index="+index);
                                    positions++;
                                }
                            }
                            if(buffer.isReadable())
                                throw new IOException("Trailing section bytes");
                        } finally { buffer.release(); }
                    }
                    Map<?,?> tiles=(Map<?,?>)chunk.getClass()
                        .getMethod("getBlockEntities").invoke(chunk);
                    Map<?,?> entities=(Map<?,?>)chunk.getClass()
                        .getMethod("getEntities").invoke(chunk);
                    int wantedTiles=Integer.parseInt(row[7]);
                    int wantedEntities=countStoredEntities(db.get(
                        cn.nukkit.level.format.leveldb.LevelDBKey.ENTITIES
                            .getKey(x,z,0)));
                    if(tiles.size()!=wantedTiles || entities.size()!=wantedEntities)
                        throw new IOException("Entity load count mismatch "+
                            world+" "+x+","+z+" BlockEntities="+tiles.size()+
                            "/"+wantedTiles+" Entities="+entities.size()+
                            "/"+wantedEntities);
                    checked++;
                    getLogger().info("CHUNK_LOAD_PASS "+world+" "+x+","+z+
                        " BlockEntities="+tiles.size()+" Entities="+entities.size());
                }
            }
            getLogger().info("WORLD_LOAD_CHECK PASS Chunks="+checked+
                " VerifiedPositions="+positions);
        } catch(Throwable error) {
            getLogger().error("WORLD_LOAD_CHECK FAIL",error);
        } finally {
            getServer().shutdown();
        }
    }
}
