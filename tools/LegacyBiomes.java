import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.zip.*;

public final class LegacyBiomes {
    static URLClassLoader loader;
    static Constructor<?> constructor;
    static Method get;

    static synchronized void init() throws Exception {
        if (constructor != null) return;
        Path jar=Paths.get("runtime/batch/source.jar").toAbsolutePath();
        List<String> candidates=new ArrayList<>();
        try (ZipFile zip=new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries=zip.entries();
            while(entries.hasMoreElements()) {
                String name=entries.nextElement().getName();
                if(name.endsWith("/BiomePalette.class"))
                    candidates.add(name.substring(0,name.length()-6)
                        .replace('/','.'));
            }
        }
        if(candidates.size()!=1)
            throw new IOException("Expected one legacy BiomePalette: "+candidates);
        loader=new URLClassLoader(new URL[]{jar.toUri().toURL()},
            ClassLoader.getPlatformClassLoader());
        Class<?> palette=Class.forName(candidates.get(0),true,loader);
        constructor=palette.getDeclaredConstructor(int[].class);
        constructor.setAccessible(true);
        get=palette.getDeclaredMethod("get",int.class,int.class);
        get.setAccessible(true);
    }

    public static byte[] decode(int[] colors) throws Exception {
        if(colors.length!=256)
            throw new IOException("Invalid BiomeColors length: "+colors.length);
        init();
        Object palette=constructor.newInstance((Object)colors);
        byte[] biomes=new byte[256];
        for(int x=0;x<16;x++)
            for(int z=0;z<16;z++) {
                int value=((Number)get.invoke(palette,x,z)).intValue();
                biomes[(x<<4)|z]=(byte)(value>>24);
            }
        return biomes;
    }
}
