package com.xiaoliang.simukraft.entity.ai.path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xiaoliang.simukraft.Simukraft;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 代价配置加载器：支持默认配置落盘与运行时自动重载
 */
public final class PathCostConfigLoader {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String DEFAULT_RESOURCE = "defaultconfigs/simukraft-path-costs.json";
    private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("simukraft-path-costs.json");

    private static volatile CacheEntry cacheEntry;

    private PathCostConfigLoader() {
    }

    public static synchronized PathCostRules getRules() {
        try {
            ensureConfigExists();
            long lastModified = Files.exists(CONFIG_PATH) ? Files.getLastModifiedTime(CONFIG_PATH).toMillis() : -1L;
            CacheEntry cached = cacheEntry;
            if (cached != null && cached.lastModified == lastModified) {
                return cached.rules;
            }

            PathCostRules loaded = loadRulesFromDisk();
            cacheEntry = new CacheEntry(lastModified, loaded);
            return loaded;
        } catch (Exception e) {
            Simukraft.LOGGER.error("[PathCostConfigLoader] 读取寻路代价配置失败，改用默认值", e);
            return PathCostRules.defaults();
        }
    }

    private static void ensureConfigExists() throws IOException {
        if (Files.exists(CONFIG_PATH)) {
            return;
        }
        Files.createDirectories(CONFIG_PATH.getParent());
        try (InputStream stream = PathCostConfigLoader.class.getClassLoader().getResourceAsStream(DEFAULT_RESOURCE)) {
            if (stream != null) {
                Files.copy(stream, CONFIG_PATH, StandardCopyOption.REPLACE_EXISTING);
                return;
            }
        }
        try (Writer writer = Files.newBufferedWriter(CONFIG_PATH, StandardCharsets.UTF_8)) {
            GSON.toJson(createDefaultJson(), writer);
        }
    }

    private static PathCostRules loadRulesFromDisk() throws IOException {
        PathCostRules defaults = PathCostRules.defaults();
        try (Reader reader = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            return new PathCostRules(
                    getDouble(json, "normalTerrainCost", defaults.normalTerrainCost()),
                    getDouble(json, "maxPenaltyCost", defaults.maxPenaltyCost()),
                    getDouble(json, "carpetFenceHopCost", defaults.carpetFenceHopCost()),
                    getDouble(json, "ascendSurcharge", defaults.ascendSurcharge()),
                    getDouble(json, "jumpOverSurcharge", defaults.jumpOverSurcharge()),
                    getDouble(json, "descendSurcharge", defaults.descendSurcharge()),
                    getDouble(json, "fallSurcharge", defaults.fallSurcharge()),
                    getDouble(json, "doorSurcharge", defaults.doorSurcharge())
            );
        }
    }

    private static double getDouble(JsonObject json, String key, double fallback) {
        return json != null && json.has(key) ? json.get(key).getAsDouble() : fallback;
    }

    private static JsonObject createDefaultJson() {
        PathCostRules defaults = PathCostRules.defaults();
        JsonObject json = new JsonObject();
        json.addProperty("_comment", "NPC 智能寻路代价配置");
        json.addProperty("normalTerrainCost", defaults.normalTerrainCost());
        json.addProperty("maxPenaltyCost", defaults.maxPenaltyCost());
        json.addProperty("carpetFenceHopCost", defaults.carpetFenceHopCost());
        json.addProperty("ascendSurcharge", defaults.ascendSurcharge());
        json.addProperty("jumpOverSurcharge", defaults.jumpOverSurcharge());
        json.addProperty("descendSurcharge", defaults.descendSurcharge());
        json.addProperty("fallSurcharge", defaults.fallSurcharge());
        json.addProperty("doorSurcharge", defaults.doorSurcharge());
        return json;
    }

    private static final class CacheEntry {
        private final long lastModified;
        private final PathCostRules rules;

        private CacheEntry(long lastModified, PathCostRules rules) {
            this.lastModified = lastModified;
            this.rules = rules;
        }
    }
}
