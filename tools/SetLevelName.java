import java.nio.*;
import java.nio.file.*;
import cn.nukkit.nbt.NBTIO;
import cn.nukkit.nbt.tag.CompoundTag;

/** Sets LevelName in a LevelDB level.dat (8-byte header + little-endian NBT). */
public class SetLevelName {
    public static void main(String[] args) throws Exception {
        Path path = Paths.get(args[0]);
        byte[] file = Files.readAllBytes(path);
        ByteBuffer header = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        int version = header.getInt(), length = header.getInt();
        if (length != file.length - 8)
            throw new IllegalStateException("Unexpected level.dat length");
        byte[] body = new byte[length];
        System.arraycopy(file, 8, body, 0, length);
        CompoundTag tag = NBTIO.read(body, ByteOrder.LITTLE_ENDIAN);
        tag.putString("LevelName", args[1]);
        byte[] nbt = NBTIO.write(tag, ByteOrder.LITTLE_ENDIAN);
        Files.write(path, ByteBuffer.allocate(8 + nbt.length).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(version).putInt(nbt.length).put(nbt).array());
        System.out.println("LEVEL_NAME_SET " + args[1]);
    }
}
