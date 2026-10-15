import java.util.*;
import cn.nukkit.block.*;
import cn.nukkit.level.format.leveldb.*;
import cn.nukkit.level.format.leveldb.structure.*;
import org.cloudburstmc.nbt.*;
public class ProbeBehavior extends cn.nukkit.plugin.PluginBase { public void onEnable(){ try { inspect(); } catch(Throwable e){e.printStackTrace();} finally{getServer().shutdown(); new Thread(()->{try{Thread.sleep(1500);}catch(Exception e){} System.exit(0);}).start();} }
 public static void inspect()throws Exception {

  var field=BlockStateMapping.class.getDeclaredField("paletteMap"); field.setAccessible(true);
  Map<?,?> map=(Map<?,?>)field.get(BlockStateMapping.get());
  int leaves=0,mismatch=0,setters=0,setterBad=0;
  for(Object object:map.keySet()){
   NbtMap state=(NbtMap)object; String name=state.getString("name");
   if(name.contains("leaves")){
    BlockStateSnapshot snap=BlockStateMapping.get().getStateUnsafe(state);

    boolean expected=state.getCompound("states").getByte("persistent_bit",(byte)0)!=0;
    Block block=Block.get(snap.getLegacyId(),snap.getLegacyData()); boolean actual=(boolean)block.getClass().getMethod("isPersistent").invoke(block);
    boolean expectedUpdate=state.getCompound("states").getByte("update_bit",(byte)0)!=0;
    BlockLeaves mutated=(BlockLeaves)Block.get(snap.getLegacyId(),snap.getLegacyData());
    mutated.setPersistent(!expected);
    NbtMap changed=BlockStateMapping.get().getState(mutated.getId(),mutated.getDamage()).getVanillaState();
    setters++;if(!changed.getString("name").equals(name)||changed.getCompound("states").getByte("persistent_bit")!=(expected?0:1)||changed.getCompound("states").getByte("update_bit")!=(expectedUpdate?1:0)){setterBad++;System.out.println("LEAF_SETTER_MISMATCH persistent name="+name+" before="+state.getCompound("states")+" after="+changed.getCompound("states"));}
    mutated=(BlockLeaves)Block.get(snap.getLegacyId(),snap.getLegacyData());mutated.setCheckDecay(!expectedUpdate);
    changed=BlockStateMapping.get().getState(mutated.getId(),mutated.getDamage()).getVanillaState();
    setters++;if(!changed.getString("name").equals(name)||changed.getCompound("states").getByte("persistent_bit")!=(expected?1:0)||changed.getCompound("states").getByte("update_bit")!=(expectedUpdate?0:1)){setterBad++;System.out.println("LEAF_SETTER_MISMATCH update name="+name+" before="+state.getCompound("states")+" after="+changed.getCompound("states"));}
    leaves++; if(expected!=actual){mismatch++;System.out.println("LEAF_MISMATCH "+state+" id="+snap.getLegacyId()+" meta="+snap.getLegacyData());}
   }
   if(name.equals("minecraft:ice")||name.equals("minecraft:packed_ice")||name.equals("minecraft:blue_ice")||name.equals("minecraft:frosted_ice")){
    var snap=BlockStateMapping.get().getStateUnsafe(state);

    System.out.println("ICE "+state+" id="+snap.getLegacyId()+" meta="+snap.getLegacyData());
   }
  }
  var level=cn.nukkit.Server.getInstance().getDefaultLevel();
  var chunk=level.getChunk(0,0,true);chunk.setGenerated(true);chunk.setPopulated(true);
  int iceChecks=0;
  for(Object object:map.keySet()){
   NbtMap state=(NbtMap)object;String name=state.getString("name");
   if(name.equals("minecraft:oak_leaves") && state.getCompound("states").getByte("persistent_bit")==1 && state.getCompound("states").getByte("update_bit")==0){
    var s=BlockStateMapping.get().getStateUnsafe(state);
    level.setBlock(8,80,8,Block.get(s.getLegacyId(),s.getLegacyData()),false,false);
    BlockLeaves leaf=(BlockLeaves)level.getBlock(8,80,8);
    System.out.println("PERSISTENT_LEAF_REPRO Before="+leaf.getId()+" persistent="+leaf.isPersistent()+" checkDecay="+leaf.isCheckDecay());
    leaf.onUpdate(cn.nukkit.level.Level.BLOCK_UPDATE_RANDOM);
    System.out.println("PERSISTENT_LEAF_REPRO After="+level.getBlockIdAt(8,80,8));
   }
   if(name.equals("minecraft:ice")||name.equals("minecraft:packed_ice")||name.equals("minecraft:blue_ice")){
    var s=BlockStateMapping.get().getStateUnsafe(state);int x=3+iceChecks*3;
    level.setBlock(x,80,8,Block.get(s.getLegacyId(),s.getLegacyData()),false,false);level.setBlockLightAt(x,80,8,15);
    Block block=level.getBlock(x,80,8);block.onUpdate(cn.nukkit.level.Level.BLOCK_UPDATE_RANDOM);
    int after=level.getBlockIdAt(x,80,8);boolean correct=name.equals("minecraft:ice")?(after==BlockID.WATER):(after==s.getLegacyId());
    System.out.println("ICE_BEHAVIOR "+name+" Before="+s.getLegacyId()+" After="+after+" Correct="+correct);if(!correct)throw new IllegalStateException("Ice behavior mismatch");iceChecks++;
   }
  }
  for(int x:new int[]{8,15,-1}) {
   var leftChunk=level.getChunk(Math.floorDiv(x,16),0,true);leftChunk.setGenerated(true);leftChunk.setPopulated(true);
   var rightChunk=level.getChunk(Math.floorDiv(x+1,16),0,true);rightChunk.setGenerated(true);rightChunk.setPopulated(true);
   for(int id:new int[]{BlockID.GLASS_PANE,BlockID.STAINED_GLASS_PANE,BlockID.IRON_BARS,BlockID.FENCE,BlockID.NETHER_BRICK_FENCE}) {
    level.setBlock(x,100,8,Block.get(id,0),false,false);level.setBlock(x+1,100,8,Block.get(id,0),false,false);
    Block left=level.getBlock(x,100,8),right=level.getBlock(x+1,100,8);
    boolean connects=left instanceof BlockThin?((BlockThin)left).canConnect(left.east()):((BlockFence)left).canConnect(left.east());
    if(!connects)throw new IllegalStateException("Same-material connection failed "+id+" "+x);
    System.out.println("ADJACENCY_PASS id="+id+" x="+x+" same-material=true");
   }
  }
  BlockThin clear=(BlockThin)Block.get(BlockID.GLASS_PANE),colored=(BlockThin)Block.get(BlockID.STAINED_GLASS_PANE);
  System.out.println("MIXED_PANE_RULE clearToColored="+clear.canConnect(colored)+" coloredToClear="+colored.canConnect(clear));
  BlockFence wood=(BlockFence)Block.get(BlockID.FENCE),nether=(BlockFence)Block.get(BlockID.NETHER_BRICK_FENCE);
  System.out.println("MIXED_FENCE_RULE woodToNether="+wood.canConnect(nether)+" netherToWood="+nether.canConnect(wood));
  System.out.println("LEAF_SETTER_AUDIT Cases="+setters+" Mismatches="+setterBad);
  System.out.println("LEAF_AUDIT States="+leaves+" Mismatches="+mismatch);

 }
}