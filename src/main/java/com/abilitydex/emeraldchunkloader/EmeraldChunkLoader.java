
package com.abilitydex.emeraldchunkloader;

import com.mojang.serialization.Codec;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.ChunkPos;
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
                    net.minecraft.resources.Identifier.fromNamespaceAndPath(
                            MOD_ID, "loaders"),
                    LoaderData::new,
                    CODEC,
                    null
            );

    private int ticks;

    @Override
    public void onInitialize() {
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!world.isClientSide()
                    && world instanceof ServerLevel level
                    && !player.isSpectator()) {
                LoaderData data = getData(level.getServer());

                for (Direction direction : Direction.values()) {
                    BlockPos candidate =
                            hit.getBlockPos().relative(direction);
                    if (level.getBlockState(candidate)
                            .is(Blocks.EMERALD_BLOCK)) {
                        data.add(level, candidate);
                    }
                }

                data.add(level, hit.getBlockPos());
            }

            return InteractionResult.PASS;
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++ticks < 20) {
                return;
            }
            ticks = 0;

            LoaderData data = getData(server);
            data.validate(server);
        });

        LOGGER.info("Emerald Chunk Loader initialized.");
    }

    private static LoaderData getData(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage().computeIfAbsent(DATA_TYPE);
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

            String entry = key(level, pos);
            if (loaders.add(entry)) {
                setDirty();
                updateArea(level, pos, true);
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

                ServerLevel level = server.getLevel(
                        net.minecraft.resources.Identifier
                                .parse(parts[0]).equals(
                                        server.overworld().dimension()
                                                .identifier())
                                ? server.overworld().dimension()
                                : net.minecraft.resources.Identifier
                                        .parse(parts[0])
                );

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
                    updateArea(level, pos, false);
                    invalid.add(entry);
                }
            }

            if (loaders.removeAll(invalid)) {
                setDirty();
            }

            for (String entry : new HashSet<>(loaders)) {
                String[] parts = entry.split("\\|", 5);
                if (parts.length != 4) {
                    continue;
                }

                net.minecraft.resources.Identifier dimension =
                        net.minecraft.resources.Identifier.parse(parts[0]);
                ServerLevel level = server.getLevel(
                        net.minecraft.resources.ResourceKey.create(
                                net.minecraft.core.registries.Registries.DIMENSION,
                                dimension));
                if (level == null) {
                    continue;
                }

                try {
                    BlockPos pos = new BlockPos(
                            Integer.parseInt(parts[1]),
                            Integer.parseInt(parts[2]),
                            Integer.parseInt(parts[3]));
                    updateArea(level, pos, true);
                } catch (NumberFormatException ignored) {
                    // Invalid saved entry will be removed on validation.
                }
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

        private static void updateArea(
                ServerLevel level, BlockPos pos, boolean force) {
            ChunkPos center = ChunkPos.containing(pos);

            for (int x = center.x() - 1; x <= center.x() + 1; x++) {
                for (int z = center.z() - 1; z <= center.z() + 1; z++) {
                    level.setChunkForced(x, z, force);
                }
            }
        }
    }
}
