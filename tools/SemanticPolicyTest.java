import org.cloudburstmc.nbt.NbtMap;
import java.io.IOException;

public class SemanticPolicyTest {
    static NbtMap state(String name, NbtMap flags) {
        return NbtMap.builder().putString("name",name).putCompound("states",flags).build();
    }
    static void rejects(NbtMap before, NbtMap after) throws Exception {
        try { CompactTarget.validateSemantics(before,after); }
        catch (IOException expected) { return; }
        throw new AssertionError("Accepted changed gameplay semantics");
    }
    public static void main(String[] args) throws Exception {
        for (byte persistent=0;persistent<2;persistent++)
            for (byte update=0;update<2;update++) {
                NbtMap source=state("minecraft:leaves",NbtMap.builder()
                    .putByte("persistent_bit",persistent).putByte("update_bit",update).build());
                NbtMap target=state("minecraft:oak_leaves",source.getCompound("states"));
                CompactTarget.validateSemantics(source,target);
                rejects(source,state("minecraft:oak_leaves",source.getCompound("states")
                    .toBuilder().putByte("persistent_bit",(byte)(1-persistent)).build()));
            }
        for (String name:new String[]{"ice","packed_ice","blue_ice","frosted_ice"}) {
            NbtMap source=state("minecraft:"+name,NbtMap.EMPTY);
            CompactTarget.validateSemantics(source,source);
            rejects(source,state("minecraft:water",NbtMap.EMPTY));
        }
        NbtMap log=state("minecraft:log",NbtMap.builder().putString("pillar_axis","x").build());
        CompactTarget.validateSemantics(log,state("minecraft:oak_log",log.getCompound("states")));
        rejects(log,state("minecraft:oak_log",NbtMap.builder().putString("pillar_axis","y").build()));
        System.out.println("SEMANTIC_POLICY_PASS leaves ice logs");
    }
}
