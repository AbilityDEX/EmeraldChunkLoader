
package com.abilitydex.emeraldchunkloader;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneWallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class EmeraldChunkLoader implements ModInitializer {
    public static final String MOD_ID = "emerald_chunk_loader";
    public static final Logger LOGGER =
            LoggerFactory.getLogger(MOD_ID);

    private final Map<String, Set<Long>> activeLoaders =
            new HashMap<>();

    private final Map<String, Map<Long, Integer>> chunkReferences =
            new HashMap<>();

    private final Map<String, Set<Long>> ownedChunks =
            new HashMap<>();

    private final Map<String, Set<BlockPos>> candidates =
            new HashMap<>();

    private int tickCounter;

    @Override
    public void onInitialize() {
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!world.isClientSide()
                    && world instanceof ServerLevel level
                    && !player.isSpectator()) {
                String dimension =
                        level.dimension().identifier().toString();

                Set<BlockPos> positions = candidates.computeIfAbsent(
                        dimension, ignored -> new HashSet<>());

                BlockPos clicked = hit.getBlockPos();

                positions.add(clicked.immutable());

                for (Direction direction : Direction.values()) {
                    positions.add(clicked.relative(direction).immutable());
                }
            }

            return InteractionResult.PASS;
        });

        ServerTickEvents.END_SERVER_TICK.register(this::tick);

        LOGGER.info("Emerald Chunk Loader initialized.");
    }

    private void tick(MinecraftServer server) {
        if (++tickCounter < 20) {
            return;
        }

        tickCounter = 0;

        for (ServerLevel level : server.getAllLevels()) {
            scanLevel(level);
        }
    }

    private void scanLevel(ServerLevel level) {
        String dimension = level.dimension().identifier().toString();

        Set<Long> current = new HashSet<>();
        Set<BlockPos> positions = candidates.computeIfAbsent(
                dimension, ignored -> new HashSet<>());

        for (BlockPos pos : positions) {
            if (!level.hasChunkAt(pos)) {
                continue;
            }

            if (!level.getBlockState(pos).is(Blocks.EMERALD_BLOCK)) {
                continue;
            }

            if (hasAttachedRedstoneTorch(level, pos)) {
                current.add(new ChunkPos(pos).toLong());
            }
        }

        positions.clear();

        Set<Long> previous = activeLoaders.computeIfAbsent(
                dimension, ignored -> new HashSet<>());

        for (long loader : new HashSet<>(previous)) {
            if (!current.contains(loader)) {
                releaseArea(level, loader);
            }
        }

        for (long loader : current) {
            if (!previous.contains(loader)) {
                forceArea(level, loader);
            }
        }

        previous.clear();
        previous.addAll(current);
    }

    private boolean hasAttachedRedstoneTorch(
            ServerLevel level, BlockPos emerald) {

        for (Direction direction : Direction.values()) {
            BlockPos torchPos = emerald.relative(direction);
            BlockState state = level.getBlockState(torchPos);

            if (direction == Direction.UP
                    && state.is(Blocks.REDSTONE_TORCH)) {
                return true;
            }

            if (state.is(Blocks.REDSTONE_WALL_TORCH)
                    && state.hasProperty(
                    RedstoneWallTorchBlock.FACING)
                    && state.getValue(
                    RedstoneWallTorchBlock.FACING) == direction.getOpposite()) {
                return true;
            }
        }

        return false;
    }

    private void forceArea(ServerLevel level, long packed) {
        ChunkPos center = new ChunkPos(packed);
        String dimension = level.dimension().identifier().toString();

        Map<Long, Integer> references = chunkReferences.computeIfAbsent(
                dimension, ignored -> new HashMap<>());

        Set<Long> owned = ownedChunks.computeIfAbsent(
                dimension, ignored -> new HashSet<>());

        for (int x = center.x() - 1; x <= center.x() + 1; x++) {
            for (int z = center.z() - 1; z <= center.z() + 1; z++) {
                long chunk = new ChunkPos(x, z).toLong();
                int count = references.getOrDefault(chunk, 0);

                references.put(chunk, count + 1);

                if (count == 0
                        && !level.getChunkSource()
                        .getForcedChunks().contains(chunk)) {
                    level.setChunkForced(x, z, true);
                    owned.add(chunk);
                }
            }
        }
    }

    private void releaseArea(ServerLevel level, long packed) {
        ChunkPos center = new ChunkPos(packed);
        String dimension = level.dimension().identifier().toString();

        Map<Long, Integer> references = chunkReferences.get(
                dimension);

        Set<Long> owned = ownedChunks.get(dimension);

        if (references == null || owned == null) {
            return;
        }

        for (int x = center.x() - 1; x <= center.x() + 1; x++) {
            for (int z = center.z() - 1; z <= center.z() + 1; z++) {
                long chunk = new ChunkPos(x, z).toLong();
                Integer count = references.get(chunk);

                if (count == null) {
                    continue;
                }

                if (count > 1) {
                    references.put(chunk, count - 1);
                } else {
                    references.remove(chunk);

                    if (owned.remove(chunk)) {
                        level.setChunkForced(x, z, false);
                    }
                }
            }
        }
    }
}
