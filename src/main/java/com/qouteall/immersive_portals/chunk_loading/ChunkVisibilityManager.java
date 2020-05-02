package com.qouteall.immersive_portals.chunk_loading;

import com.google.common.collect.Streams;
import com.qouteall.immersive_portals.Global;
import com.qouteall.immersive_portals.McHelper;
import com.qouteall.immersive_portals.portal.Portal;
import com.qouteall.immersive_portals.portal.global_portals.GlobalPortalStorage;
import com.qouteall.immersive_portals.portal.global_portals.GlobalTrackedPortal;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.dimension.DimensionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

public class ChunkVisibilityManager {
    private static final int portalLoadingRange = 48;
    public static final int secondaryPortalLoadingRange = 16;
    
    public static interface ChunkPosConsumer {
        void consume(DimensionType dimensionType, int x, int z, int distanceToSource);
    }
    
    //the players and portals are chunk loaders
    public static class ChunkLoader {
        public DimensionalChunkPos center;
        public int radius;
        
        public ChunkLoader(DimensionalChunkPos center, int radius) {
            this.center = center;
            this.radius = radius;
        }
        
        public void foreachChunkPos(ChunkPosConsumer func) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    func.consume(
                        center.dimension,
                        center.x + dx,
                        center.z + dz,
                        Math.max(Math.abs(dx), Math.abs(dz))
                    );
                }
            }
        }
        
        public ChunkRegion createChunkRegion() {
            ServerWorld world = McHelper.getServer().getWorld(center.dimension);
            
            int width = radius * 2 + 1;
            List<Chunk> chunks = new ArrayList<>();
            
            for (int z = center.z - radius; z <= center.z + radius; z++) {
                for (int x = center.x - radius; x <= center.x + radius; x++) {
                    chunks.add(world.getChunk(x, z));
                }
            }
            
            return new ChunkRegion(
                world, chunks
            );
        }
        
        @Override
        public String toString() {
            return "{" +
                "center=" + center +
                ", radius=" + radius +
                '}';
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ChunkLoader that = (ChunkLoader) o;
            return radius == that.radius &&
                center.equals(that.center);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(center, radius);
        }
    }
    
    private static int getChebyshevDistance(
        int x1, int z1,
        int x2, int z2
    ) {
        return Math.max(
            Math.abs(x1 - x2),
            Math.abs(z1 - z2)
        );
    }
    
    private static ChunkLoader playerDirectLoader(ServerPlayerEntity player) {
        return new ChunkLoader(
            new DimensionalChunkPos(
                player.dimension,
                player.chunkX, player.chunkZ
            ),
            McHelper.getRenderDistanceOnServer()
            //ServerPerformanceAdjust.getPlayerLoadingDistance(player)
        );
    }
    
    private static int getDirectLoadingDistance(int renderDistance, double distanceToPortal) {
        if (Global.loadFewerChunks) {
            return 1;
        }
        if (distanceToPortal < 5) {
            return renderDistance;
        }
        if (distanceToPortal < 15) {
            return (renderDistance * 2) / 3;
        }
        return renderDistance / 3;
    }
    
    private static ChunkLoader portalDirectLoader(
        Portal portal,
        ServerPlayerEntity player
    ) {
        int renderDistance = McHelper.getRenderDistanceOnServer();
        double distance = portal.getDistanceToNearestPointInPortal(player.getPos());
        return new ChunkLoader(
            new DimensionalChunkPos(
                portal.dimensionTo,
                new ChunkPos(new BlockPos(portal.destination))
            ),
            getDirectLoadingDistance(renderDistance, distance)
        );
    }
    
    private static ChunkLoader portalIndirectLoader(Portal portal) {
        int renderDistance = McHelper.getRenderDistanceOnServer();
        return new ChunkLoader(
            new DimensionalChunkPos(
                portal.dimensionTo,
                new ChunkPos(new BlockPos(portal.destination))
            ),
            (renderDistance / 4)
        );
    }
    
    private static ChunkLoader globalPortalDirectLoader(
        ServerPlayerEntity player,
        GlobalTrackedPortal portal
    ) {
        int renderDistance = Math.max(
            2,
            McHelper.getRenderDistanceOnServer() -
                Math.floorDiv((int) portal.getDistanceToNearestPointInPortal(player.getPos()), 16)
        );
        
        return new ChunkLoader(
            new DimensionalChunkPos(
                portal.dimensionTo,
                new ChunkPos(new BlockPos(
                    portal.transformPointRough(player.getPos())
                ))
            ),
            renderDistance
        );
    }
    
    private static ChunkLoader globalPortalIndirectLoader(
        ServerPlayerEntity player,
        GlobalTrackedPortal outerPortal,
        GlobalTrackedPortal remotePortal
    ) {
        int renderDistance = McHelper.getRenderDistanceOnServer() / 2;
        return new ChunkLoader(
            new DimensionalChunkPos(
                remotePortal.dimensionTo,
                new ChunkPos(new BlockPos(
                    remotePortal.transformPointRough(
                        outerPortal.transformPointRough(player.getPos())
                    )
                ))
            ),
            renderDistance
        );
    }
    
    private static Stream<GlobalTrackedPortal> getGlobalPortals(
        DimensionType dimension
    ) {
        List<GlobalTrackedPortal> data = GlobalPortalStorage.get(
            McHelper.getServer().getWorld(dimension)
        ).data;
        if (data == null) {
            return Stream.empty();
        }
        return data.stream();
    }
    
    //includes:
    //1.player direct loader
    //2.portal direct loader
    //3.portal secondary loader
    //4.global portal direct loader
    //5.global portal secondary loader
    public static Stream<ChunkLoader> getChunkLoaders(
        ServerPlayerEntity player
    ) {
        return Streams.concat(
            Stream.of(playerDirectLoader(player)),
            
            McHelper.getServerEntitiesNearbyWithoutLoadingChunk(
                player.world,
                player.getPos(),
                Portal.class,
                Global.loadFewerChunks ? portalLoadingRange / 2 : portalLoadingRange
            ).filter(
                portal -> portal.canBeSpectated(player)
            ).flatMap(
                portal -> Stream.concat(
                    Stream.of(portalDirectLoader(portal, player)),
                    
                    Global.loadFewerChunks ?
                        Stream.empty() :
                        McHelper.getServerEntitiesNearbyWithoutLoadingChunk(
                            McHelper.getServer().getWorld(portal.dimensionTo),
                            portal.destination,
                            Portal.class,
                            secondaryPortalLoadingRange
                        ).filter(
                            remotePortal -> remotePortal.canBeSpectated(player)
                        ).map(
                            remotePortal -> portalIndirectLoader(remotePortal)
                        )
                )
            ),
            
            getGlobalPortals(player.dimension)
                .flatMap(
                    portal -> Stream.concat(
                        Stream.of(globalPortalDirectLoader(
                            player, portal
                        )),
                        
                        Global.loadFewerChunks ?
                            Stream.empty() :
                            getGlobalPortals(
                                portal.dimensionTo
                            ).filter(
                                remotePortal -> remotePortal.getDistanceToNearestPointInPortal(
                                    portal.transformPointRough(player.getPos())
                                ) < (Global.loadFewerChunks ? portalLoadingRange / 2 : portalLoadingRange)
                            ).map(
                                remotePortal -> globalPortalIndirectLoader(
                                    player, portal, remotePortal
                                )
                            )
                    )
                )
        ).distinct();
    }
    
}
