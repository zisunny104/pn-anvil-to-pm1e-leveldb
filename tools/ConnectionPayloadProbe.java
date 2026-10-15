import java.io.*;
import java.util.*;
import cn.nukkit.block.*;
import cn.nukkit.level.*;
import cn.nukkit.level.format.leveldb.*;
import cn.nukkit.level.format.leveldb.structure.*;
import cn.nukkit.nbt.tag.*;
import cn.nukkit.utils.*;

/** Isolated fixture only: inspect bytes emitted by the actual storage serializer. */
public class ConnectionPayloadProbe extends cn.nukkit.plugin.PluginBase {
 public void onEnable() {
  try { inspect(); } catch(Throwable error) { error.printStackTrace(); }
  finally { getServer().shutdown(); new Thread(()->{try{Thread.sleep(1500);}catch(Exception ignored){}System.exit(0);}).start(); }
 }
 static Map<Integer,CompoundTag> states(int protocol) {
  Map<Integer,CompoundTag> result=new HashMap<>();
  ListTag<CompoundTag> tags=(ListTag<CompoundTag>)Utils.loadTagResource("runtime_block_states_"+protocol+".dat");
  for(CompoundTag tag:tags.getAll())result.put(tag.getInt("runtimeId"),tag);
  return result;
 }
 static int emitted(StateBlockStorage storage,int protocol,int expected) throws Exception {
  BinaryStream out=new BinaryStream(); storage.writeTo(protocol,out,false);
  BinaryStream in=new BinaryStream();in.setBuffer(out.getBuffer());
  int header=in.getByte()&255;
  if(header!=3)throw new IOException("Expected V1 network storage, header="+header);
  for(int i=0;i<128;i++)in.getLInt();
  int count=in.getVarInt(); if(count!=2)throw new IOException("Unexpected palette "+count);
  in.getVarInt(); int runtime=in.getVarInt();
  if(runtime!=expected)throw new IOException("Emitted runtime mismatch "+runtime+"/"+expected);
  return runtime;
 }
 static void inspect()throws Exception {
  Level level=cn.nukkit.Server.getInstance().getDefaultLevel();
  Map<Integer,CompoundTag> latest=states(2192),old=states(729); int cases=0;
  for(int x:new int[]{8,15,-1})for(int id:new int[]{102,160,101,85,113}) {
   for(int cx:new int[]{Math.floorDiv(x,16),Math.floorDiv(x+1,16)}) {
    var chunk=level.getChunk(cx,0,true);chunk.setGenerated(true);chunk.setPopulated(true);
   }
   level.setBlock(x,100,8,Block.get(id,0),false,false);
   level.setBlock(x+1,100,8,Block.get(id,0),false,false);
   Block left=level.getBlock(x,100,8),right=level.getBlock(x+1,100,8);
   boolean connects=left instanceof BlockThin?((BlockThin)left).canConnect(right):((BlockFence)left).canConnect(right);
   if(!connects)throw new IOException("Pair adjacency failed");
   var snapshot=BlockStateMapping.get().getState(id,0);
   var air=BlockStateMapping.get().getState(0,0);
   // The real serializer has no neighbor/world argument: prepare each affected section.
   for(int bx:new int[]{x,x+1}) {
    StateBlockStorage storage=new StateBlockStorage(cn.nukkit.level.util.BitArrayVersion.V1.createPalette(4096),new ArrayList<>(Collections.singletonList(air)));
    storage.setBlockStateUnsafe(ChunkBuilder.getSectionIndex(Math.floorMod(bx,16),4,8),snapshot);
    if(Math.floorDiv(x,16)==Math.floorDiv(x+1,16))storage.setBlockStateUnsafe(ChunkBuilder.getSectionIndex(Math.floorMod(bx==x?x+1:x,16),4,8),snapshot);
    for(int protocol:new int[]{729,2193}) {
     int expected=GlobalBlockPalette.getOrCreateRuntimeId(protocol,id,0);
     int runtime=emitted(storage,protocol,expected);
     cn.nukkit.network.protocol.UpdateBlockPacket update=new cn.nukkit.network.protocol.UpdateBlockPacket();
     update.protocol=protocol;update.x=bx;update.y=100;update.z=8;update.blockRuntimeId=expected;
     update.flags=cn.nukkit.network.protocol.UpdateBlockPacket.FLAG_ALL_PRIORITY;update.encode();
     BinaryStream updateInput=new BinaryStream();updateInput.setBuffer(update.getBuffer());
     updateInput.getUnsignedVarInt();updateInput.getBlockVector3(protocol);
     int updateRuntime=(int)updateInput.getUnsignedVarInt();int updateFlags=(int)updateInput.getUnsignedVarInt();
     if(updateRuntime!=runtime||updateFlags!=11)throw new IOException("Update payload differs");
     CompoundTag state=(protocol==729?old:latest).get(runtime);
     if(state==null)throw new IOException("Missing emitted state");
     CompoundTag flags=state.getCompound("states");
     boolean zero=true;for(String direction:new String[]{"north","east","south","west"})if(flags.getByte("minecraft:connection_"+direction)!=0)zero=false;
     if(protocol==2193&&(!flags.contains("minecraft:connection_east")||!zero))throw new IOException("Expected baseline disconnected flags");
     System.out.println("CONNECTION_PAYLOAD PairX="+x+" BlockX="+bx+" LegacyId="+id+" Protocol="+protocol+" Runtime="+runtime+" UpdateRuntime="+updateRuntime+" UpdateFlags="+updateFlags+" ServerConnects="+connects+" State="+flags);
     cases++;
    }
   }
  }
  System.out.println("CONNECTION_PAYLOAD_PASS Cases="+cases+" Pairs=15 LatestDisconnectedStates=30");
 }
}
