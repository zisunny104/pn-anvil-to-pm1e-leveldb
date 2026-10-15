import java.io.*;
/** Test-only launcher: keep console input open until the isolated probe shuts down. */
public class BehaviorLauncher {
    private static PipedOutputStream writer;
    public static void main(String[] args) throws Exception {
        writer=new PipedOutputStream();
        System.setIn(new PipedInputStream(writer));
        cn.nukkit.Nukkit.main(args);
    }
}
