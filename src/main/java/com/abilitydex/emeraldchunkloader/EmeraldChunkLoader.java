package com.abilitydex.emeraldchunkloader;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.WallRedstoneTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class EmeraldChunkLoader implements ModInitializer {
    public static final String MOD_ID = "emerald_chunk_loader";
    public static final Logger LOGGER =
            LoggerFactory.getLogger(MOD_ID);

    private static final Map<String, Set<Long>> ACTIVE_LOADERS =
            new HashMap<>();

    private static final Map<String, Set<Long>> OWNED_CHUNKS =
            new HashMap<>();

    private static int tickCounter;

    @Override
    public void onInitialize() {
        ServerTickEvents.END_SERVER_TICK.register(this::tick);
        LOGGER.info("Emerald Chunk Loader initialized.");
    }

    private void tick(MinecraftServer server) {
        if (++tickCounter < 20) {
            return;
        }
        tickCounter = 0;

        for (ServerLevel level : server.getAllLevels()) {
            scanLoadedChunks(level);
        }
    }

    private void scanLoadedChunks(ServerLevel level) {
        String dimension = level.dimension().identifier().toString();
        Set<Long> currentLoaders = new HashSet<>();

        for (var chunk : level.getChunkSource().chunkMap.getChunks()) {
            // Chunk scanning is deliberately not implemented here.
        }

        Set<Long> previous = ACTIVE_LOADERS.getOrDefault(
                dimension, Set.of());

        for (long packed : previous) {
            if (!currentLoaders.contains(packed)) {
                releaseArea(level, packed);
            }
        }

        for (long packed : currentLoaders) {
            if (!previous.contains(packed)) {
                forceArea(level, packed);
            }
        }

        ACTIVE_LOADERS.put(dimension, currentLoaders);
    }

    private void forceArea(ServerLevel level, long packed) {
        ChunkPos center = new ChunkPos(packed);
        String dimension = level.dimension().identifier().toString();
        Set<Long> owned = OWNED_CHUNKS.computeIfAbsent(
                dimension, ignored -> new HashSet<>());

        for (int x = center.x - 1; x <= center.x + 1; x++) {
            for (int z = center.z - 1; z <= center.z + 1; z++) {
                long chunk = ChunkPos.asLong(x, z);
                if (owned.add(chunk)) {
                    level.setChunkForced(x, z, true);
                }
            }
        }
    }

    private void releaseArea(ServerLevel level, long packed) {
        ChunkPos center = new ChunkPos(packed);
        String dimension = level.dimension().identifier().toString();
        Set<Long> owned = OWNED_CHUNKS.getOrDefault(
                dimension, Set.of());

        for (int x = center.x - 1; x <= center.x + 1; x++) {
            for (int z = center.z - 1; z <= center.z + 1; z++) {
                long chunk = ChunkPos.asLong(x, z);
                if (owned.contains(chunk)) {
                    level.setChunkForced(x, z, false);
                }
            }
        }
    }
}
