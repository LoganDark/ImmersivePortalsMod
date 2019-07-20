package com.qouteall.immersive_portals.chunk_loading;

import com.qouteall.immersive_portals.Globals;
import com.qouteall.immersive_portals.my_util.Helper;
import net.minecraft.client.network.packet.ChunkDataS2CPacket;
import net.minecraft.client.network.packet.LightUpdateS2CPacket;
import net.minecraft.client.network.packet.UnloadChunkS2CPacket;
import net.minecraft.network.Packet;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;

//the chunks near player are managed by vanilla
//we only manage the chunks that's seen by portal and not near player
//it is not multi-threaded like vanilla
public class ChunkDataSyncManager {
    
    private static final int unloadWaitingTickTime = 20 * 10;
    
    public ChunkDataSyncManager() {
        Globals.chunkTracker.beginWatchChunkSignal.connectWithWeakRef(
            this, ChunkDataSyncManager::onBeginWatch
        );
        Globals.chunkTracker.endWatchChunkSignal.connectWithWeakRef(
            this, ChunkDataSyncManager::onEndWatch
        );
    }
    
    /**
     * {@link ThreadedAnvilChunkStorage#sendChunkDataPackets(ServerPlayerEntity, Packet[], WorldChunk)}
     */
    private void onBeginWatch(ServerPlayerEntity player, DimensionalChunkPos chunkPos) {
        if (isChunkManagedByVanilla(player, chunkPos)) {
            return;
        }
    
        if (Globals.chunkTracker.isChunkDataSent(player, chunkPos)) {
            return;
        }
    
        Globals.chunkTracker.onChunkDataSent(player, chunkPos);
        
        Chunk chunk = Helper.getServer()
            .getWorld(chunkPos.dimension)
            .getChunk(chunkPos.x, chunkPos.z);
        player.networkHandler.sendPacket(
            RedirectedMessageManager.createRedirectedMessage(
                chunkPos.dimension,
                new ChunkDataS2CPacket(
                    ((WorldChunk) chunk),
                    65535
                )
            )
        );
        
        player.networkHandler.sendPacket(
            RedirectedMessageManager.createRedirectedMessage(
                chunkPos.dimension,
                new LightUpdateS2CPacket(
                    chunkPos.getChunkPos(),
                    Helper.getIEStorage(chunkPos.dimension).getLightingProvider()
                )
            )
        );
    
        //this is to update the entity trackers
        //performance may be slowed down
        ((ThreadedAnvilChunkStorage) Helper.getIEStorage(chunkPos.dimension))
            .updateCameraPosition(player);
    }
    
    private void onEndWatch(ServerPlayerEntity player, DimensionalChunkPos chunkPos) {
        if (isChunkManagedByVanilla(player, chunkPos)) {
            return;
        }
        
        //do not send unload packet instantly
        //watch for a period of time.
        //if player still needs the chunk, stop unloading.
    
        sendUnloadPacket(player, chunkPos);
    }
    
    public void sendUnloadPacket(ServerPlayerEntity player, DimensionalChunkPos chunkPos) {
        if (isChunkManagedByVanilla(player, chunkPos)) {
            //give up unloading
            return;
        }
        
        if (Globals.chunkTracker.isPlayerWatchingChunk(player, chunkPos)) {
            //give up unloading
            return;
        }
        
        player.networkHandler.sendPacket(
            RedirectedMessageManager.createRedirectedMessage(
                chunkPos.dimension,
                new UnloadChunkS2CPacket(
                    chunkPos.x, chunkPos.z
                )
            )
        );
    }
    
    public static boolean isChunkManagedByVanilla(
        ServerPlayerEntity player,
        DimensionalChunkPos chunkPos
    ) {
        if (player.dimension != chunkPos.dimension) {
            return false;
        }
        
        int watchDistance = ChunkTracker.getRenderDistanceOnServer();
        
        //NOTE do not use entity.chunkX
        //it's not updated
        
        ChunkPos playerChunkPos = new ChunkPos(player.getBlockPos());
        
        int chebyshevDistance = Math.max(
            Math.abs(playerChunkPos.x - chunkPos.x),
            Math.abs(playerChunkPos.z - chunkPos.z)
        );
        
        return chebyshevDistance <= watchDistance;
    }
}
