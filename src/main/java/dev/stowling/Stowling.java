package dev.stowling;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Stowling implements ModInitializer {
    public static final String MOD_ID = "stowling";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Stowling foundation initialized");
    }
}
