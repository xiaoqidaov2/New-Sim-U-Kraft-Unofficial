package com.xiaoliang.simukraft.client.gui.guidebook;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.xiaoliang.simukraft.Simukraft;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nonnull;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 指南书独立语言系统
 * 从 guidebook/lang/ 目录加载语言文件
 */
@SuppressWarnings("null")
@OnlyIn(Dist.CLIENT)
public class GuideBookLang {

    private static final Gson GSON = new Gson();
    private static final String LANG_PATH = "guidebook/lang";
    private static final Map<String, String> TRANSLATIONS = new ConcurrentHashMap<>();
    private static boolean loaded = false;

    /**
     * 加载语言文件
     */
    public static void load(@Nonnull ResourceManager resourceManager) {
        TRANSLATIONS.clear();

        // 获取当前语言代码
        String langCode = Minecraft.getInstance().getLanguageManager().getSelected();

        // 尝试加载当前语言
        ResourceLocation langLocation = ResourceLocation.fromNamespaceAndPath(
                Simukraft.MOD_ID, LANG_PATH + "/" + langCode + ".json");

        resourceManager.getResource(langLocation).ifPresentOrElse(
                resource -> {
                    try (InputStreamReader reader = new InputStreamReader(resource.open(), StandardCharsets.UTF_8)) {
                        JsonObject json = GSON.fromJson(reader, JsonObject.class);
                        parseTranslations(json);
                        Simukraft.LOGGER.info("GuideBook: Loaded language file for '{}' with {} entries", langCode, TRANSLATIONS.size());
                    } catch (Exception e) {
                        Simukraft.LOGGER.error("GuideBook: Failed to load language file for '{}'", langCode, e);
                        loadFallback(resourceManager);
                    }
                },
                () -> {
                    Simukraft.LOGGER.warn("GuideBook: Language file not found for '{}', loading fallback", langCode);
                    loadFallback(resourceManager);
                }
        );

        loaded = true;
    }

    /**
     * 加载备用语言（英文）
     */
    private static void loadFallback(@Nonnull ResourceManager resourceManager) {
        ResourceLocation fallbackLocation = ResourceLocation.fromNamespaceAndPath(
                Simukraft.MOD_ID, LANG_PATH + "/en_us.json");

        resourceManager.getResource(fallbackLocation).ifPresent(resource -> {
            try (InputStreamReader reader = new InputStreamReader(resource.open(), StandardCharsets.UTF_8)) {
                JsonObject json = GSON.fromJson(reader, JsonObject.class);
                parseTranslations(json);
                Simukraft.LOGGER.info("GuideBook: Loaded fallback language with {} entries", TRANSLATIONS.size());
            } catch (Exception e) {
                Simukraft.LOGGER.error("GuideBook: Failed to load fallback language", e);
            }
        });
    }

    /**
     * 解析翻译条目
     */
    private static void parseTranslations(@Nonnull JsonObject json) {
        for (var entry : json.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("_")) continue; // 跳过注释

            if (entry.getValue().isJsonPrimitive()) {
                TRANSLATIONS.put(key, entry.getValue().getAsString());
            }
        }
    }

    /**
     * 获取翻译文本，支持变量替换
     */
    @Nonnull
    public static String get(@Nonnull String key) {
        String text = TRANSLATIONS.getOrDefault(key, key);
        return replaceVariables(text);
    }

    /**
     * 替换文本中的变量
     * $player_name - 当前玩家名
     */
    @Nonnull
    private static String replaceVariables(@Nonnull String text) {
        if (text.contains("$player_name")) {
            String playerName = getPlayerName();
            text = text.replace("$player_name", playerName);
        }
        return text;
    }

    /**
     * 获取当前玩家名称
     */
    @Nonnull
    private static String getPlayerName() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            return minecraft.player.getName().getString();
        }
        return "玩家";
    }

    /**
     * 获取翻译文本，支持格式化参数
     */
    @Nonnull
    public static String get(@Nonnull String key, Object... args) {
        String text = get(key);
        return String.format(text, args);
    }

    /**
     * 检查是否已加载
     */
    public static boolean isLoaded() {
        return loaded;
    }

    /**
     * 重新加载语言
     */
    public static void reload(@Nonnull ResourceManager resourceManager) {
        loaded = false;
        load(resourceManager);
    }
}
