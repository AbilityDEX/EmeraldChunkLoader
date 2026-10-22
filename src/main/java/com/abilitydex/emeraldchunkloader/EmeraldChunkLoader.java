
package com.abilitydex.emeraldchunkloader;

import com.mojang.serialization.Codec;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneWallTorchBlock;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

public final class EmeraldChunkLoader implements ModInitializer {
    public static final String MOD_ID = "emerald_chunk_loader";
    public static final Logger LOGGER =
            LoggerFactory.getLogger(MOD_ID);

    private static final Codec<LoaderData> CODEC =
            Codec.STRING.listOf().xmap(
                    LoaderData::new,
                    LoaderData::entries
            );

    private static final SavedDataType<LoaderData> DATA_TYPE =
            new SavedDataType<>(
                    Identifier.fromNamespaceAndPath(MOD_ID, "loaders"),
                    LoaderData::new,
                    CODEC,
                    null
            );

    private static final Set<String> CANDIDATES = new HashSet<>();
    private int ticks;

    @Override
    public void onInitialize() {
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!world.isClientSide()
                    && world instanceof ServerLevel level
                    && !player.isSpectator()) {
                BlockPos clicked = hit.getBlockPos();

                queue(level, clicked);
                for (Direction direction : Direction.values()) {
                    queue(level, clicked.relative(direction));
                }
            }

            return InteractionResult.PASS;
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            processCandidates(server);

            if (++ticks >= 20) {
                ticks = 0;
                getData(server).validate(server);
            }
        });

        LOGGER.info("Emerald Chunk Loader initialized.");
    }

    private static void queue(ServerLevel level, BlockPos pos) {
        CANDIDATES.add(key(level, pos));
    }

    private static void processCandidates(MinecraftServer server) {
        if (CANDIDATES.isEmpty()) {
            return;
        }

        Set<String> pending = new HashSet<>(CANDIDATES);
        CANDIDATES.clear();

        LoaderData data = getData(server);

        for (String entry : pending) {
            String[] parts = entry.split("\\|", 5);
            if (parts.length != 4) {
                continue;
            }

            ServerLevel level = findLevel(server, parts[0]);
            if (level == null) {
                continue;
            }

            try {
                BlockPos pos = new BlockPos(
                        Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]),
                        Integer.parseInt(parts[3]));

                if (level.hasChunkAt(pos)) {
                    data.add(level, pos);
                }
            } catch (NumberFormatException ignored) {
                // Ignore malformed candidate positions.
            }
        }
    }

    private static LoaderData getData(MinecraftServer server) {
        return server.overworld()
                .getDataStorage()
                .computeIfAbsent(DATA_TYPE);
    }

    private static ServerLevel findLevel(
            MinecraftServer server, String dimension) {
        try {
            Identifier id = Identifier.parse(dimension);
            ResourceKey<Level> key =
                    ResourceKey.create(Registries.DIMENSION, id);
            return server.getLevel(key);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String key(ServerLevel level, BlockPos pos) {
        return level.dimension().identifier() + "|"
                + pos.getX() + "|" + pos.getY() + "|" + pos.getZ();
    }

    private static boolean hasTorch(
            ServerLevel level, BlockPos emerald) {
        for (Direction direction : Direction.values()) {
            BlockPos torchPos = emerald.relative(direction);
            var state = level.getBlockState(torchPos);

            if (direction == Direction.UP
                    && state.is(Blocks.REDSTONE_TORCH)) {
                return true;
            }

            if (state.is(Blocks.REDSTONE_WALL_TORCH)
                    && state.hasProperty(RedstoneWallTorchBlock.FACING)
                    && state.getValue(RedstoneWallTorchBlock.FACING)
                            == direction.getOpposite()) {
                return true;
            }
        }

        return false;
    }

    private static final class LoaderData extends SavedData {
        private final Set<String> loaders = new HashSet<>();

        LoaderData() {
        }

        LoaderData(java.util.List<String> entries) {
            loaders.addAll(entries);
        }

        java.util.List<String> entries() {
            return loaders.stream().sorted().toList();
        }

        void add(ServerLevel level, BlockPos pos) {
            if (!level.getBlockState(pos).is(Blocks.EMERALD_BLOCK)
                    || !hasTorch(level, pos)) {
                return;
            }

            if (loaders.add(key(level, pos))) {
                setDirty();
            }
        }

        void validate(MinecraftServer server) {
            Set<String> invalid = new HashSet<>();

            for (String entry : new HashSet<>(loaders)) {
                String[] parts = entry.split("\\|", 5);
                if (parts.length != 4) {
                    invalid.add(entry);
                    continue;
                }

                ServerLevel level = findLevel(server, parts[0]);
                if (level == null) {
                    continue;
                }

                BlockPos pos;
                try {
                    pos = new BlockPos(
                            Integer.parseInt(parts[1]),
                            Integer.parseInt(parts[2]),
                            Integer.parseInt(parts[3]));
                } catch (NumberFormatException exception) {
                    invalid.add(entry);
                    continue;
                }

                if (!level.hasChunkAt(pos)) {
                    continue;
                }

                if (!level.getBlockState(pos).is(Blocks.EMERALD_BLOCK)
                        || !hasTorch(level, pos)) {
                    invalid.add(entry);
                }
            }

            if (!invalid.isEmpty()) {
                loaders.removeAll(invalid);
                setDirty();
            }

            // Reconcile every chunk against all remaining loaders.
            for (ServerLevel level : server.getAllLevels()) {
                Set<Long> required = new HashSet<>();

                for (String entry : loaders) {
                    String[] parts = entry.split("\\|", 5);
                    if (parts.length != 4
                            || !parts[0].equals(
                                    level.dimension().identifier().toString())) {
                        continue;
                    }

                    try {
                        BlockPos pos = new BlockPos(
                                Integer.parseInt(parts[1]),
                                Integer.parseInt(parts[2]),
                                Integer.parseInt(parts[3]));

                        if (!level.hasChunkAt(pos)
                                || !level.getBlockState(pos)
                                        .is(Blocks.EMERALD_BLOCK)
                                || !hasTorch(level, pos)) {
                            continue;
                        }

                        ChunkPos center = ChunkPos.containing(pos);

                        for (int x = center.x() - 1;
                             x <= center.x() + 1; x++) {
                            for (int z = center.z() - 1;
                                 z <= center.z() + 1; z++) {
                                required.add(ChunkPos.pack(x, z));
                            }
                        }
                    } catch (NumberFormatException ignored) {
                        // Invalid entries are discarded during validation.
                    }
                }

                Set<Long> forced = new HashSet<>(
                        level.getChunkSource().getForceLoadedChunks());

                for (long packed : required) {
                    ChunkPos chunk = ChunkPos.unpack(packed);
                    level.setChunkForced(chunk.x(), chunk.z(), true);
                }

                for (long packed : forced) {
                    if (!required.contains(packed)) {
                        ChunkPos chunk = ChunkPos.unpack(packed);
                        level.setChunkForced(chunk.x(), chunk.z(), false);
                    }
                }
            }
        }
    }
}
