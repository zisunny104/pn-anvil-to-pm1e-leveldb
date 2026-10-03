import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import cn.nukkit.nbt.tag.*;

public final class OfflineNbt {
    static String string(DataInputStream in) throws IOException {
        int length=in.readUnsignedShort();
        byte[] bytes=new byte[length];
        in.readFully(bytes);
        return new String(bytes,StandardCharsets.UTF_8);
    }
    static int length(DataInputStream in,int width) throws IOException {
        int count=in.readInt();
        if (count<0 || count>16*1024*1024/width)
            throw new IOException("NBT array too large");
        return count;
    }
    static Tag payload(DataInputStream in,int type,String name,int depth)
            throws IOException {
        if (depth>128) throw new IOException("NBT nesting too deep");
        switch(type) {
            case 1: return new ByteTag(name,in.readByte());
            case 2: return new ShortTag(name,in.readShort());
            case 3: return new IntTag(name,in.readInt());
            case 4: return new LongTag(name,in.readLong());
            case 5: return new FloatTag(name,in.readFloat());
            case 6: return new DoubleTag(name,in.readDouble());
            case 7: {
                byte[] bytes=new byte[length(in,1)];
                in.readFully(bytes);
                return new ByteArrayTag(name,bytes);
            }
            case 8: return new StringTag(name,string(in));
            case 9: {
                int subtype=in.readUnsignedByte();
                int count=length(in,8);
                if (count>0 && (subtype<1 || subtype>11))
                    throw new IOException("Invalid list subtype");
                ListTag<Tag> list=new ListTag<>(name);
                for(int i=0;i<count;i++)
                    list.add(payload(in,subtype,"",depth+1));
                return list;
            }
            case 10: {
                CompoundTag compound=new CompoundTag(name);
                int count=0;
                while(true) {
                    int subtype=in.readUnsignedByte();
                    if(subtype==0) return compound;
                    if(++count>1000000)
                        throw new IOException("Compound too large");
                    String key=string(in);
                    if(compound.contains(key))
                        throw new IOException("Duplicate NBT key: "+key);
                    compound.put(key,payload(in,subtype,key,depth+1));
                }
            }
            case 11: {
                int[] values=new int[length(in,4)];
                for(int i=0;i<values.length;i++) values[i]=in.readInt();
                return new IntArrayTag(name,values);
            }
            default: throw new IOException("Unsupported NBT tag: "+type);
        }
    }
    public static CompoundTag read(byte[] bytes) throws IOException {
        try(DataInputStream in=new DataInputStream(
                new ByteArrayInputStream(bytes))) {
            int type=in.readUnsignedByte();
            if(type!=10) throw new IOException("Root is not compound");
            CompoundTag root=(CompoundTag)payload(in,type,string(in),0);
            if(in.read()!=-1) throw new IOException("Trailing NBT bytes");
            return root;
        }
    }
    public static CompoundTag readCompressed(byte[] bytes) throws IOException {
        try(GZIPInputStream in=new GZIPInputStream(
                new ByteArrayInputStream(bytes))) {
            byte[] raw=in.readNBytes(64*1024*1024+1);
            if(raw.length>64*1024*1024)
                throw new IOException("Decompressed NBT too large");
            return read(raw);
        }
    }
}
