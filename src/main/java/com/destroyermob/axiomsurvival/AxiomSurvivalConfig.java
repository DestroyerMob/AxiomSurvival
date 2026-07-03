package com.destroyermob.axiomsurvival;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

public final class AxiomSurvivalConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int DEFAULT_MAX_PENDING_BLOCKS = 100_000;
    private static Config config = new Config();

    private AxiomSurvivalConfig() {
    }

    public static void load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("axiom-survival.json");
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                Config loaded = GSON.fromJson(reader, Config.class);
                if (loaded != null) {
                    config = loaded.normalized();
                    return;
                }
            } catch (IOException | JsonSyntaxException exception) {
                AxiomSurvival.LOGGER.warn("Could not read Axiom Survival config. Rewriting defaults.", exception);
            }
        }

        config = new Config();
        save(path);
    }

    public static boolean captureEnabled() {
        return config.enableAxiomVanillaEditCapture;
    }

    public static int maxPendingBlocks() {
        return config.axiomVanillaEditMaxPendingBlocks;
    }

    private static void save(Path path) {
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(config, writer);
            }
        } catch (IOException exception) {
            AxiomSurvival.LOGGER.warn("Could not write Axiom Survival config.", exception);
        }
    }

    private static final class Config {
        private boolean enableAxiomVanillaEditCapture = false;
        private int axiomVanillaEditMaxPendingBlocks = DEFAULT_MAX_PENDING_BLOCKS;

        private Config normalized() {
            axiomVanillaEditMaxPendingBlocks = Math.max(1, Math.min(axiomVanillaEditMaxPendingBlocks, 1_000_000));
            return this;
        }
    }
}
