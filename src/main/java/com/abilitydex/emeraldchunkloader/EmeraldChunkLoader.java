package com.abilitydex.emeraldchunkloader;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EmeraldChunkLoader implements ModInitializer {
    public static final String MOD_ID = "emerald_chunk_loader";
    public static final Logger LOGGER =
            LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Emerald Chunk Loader initialized.");
    }
}
