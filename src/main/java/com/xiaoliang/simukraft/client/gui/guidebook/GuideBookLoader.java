package com.xiaoliang.simukraft.client.gui.guidebook;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xiaoliang.simukraft.Simukraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nonnull;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 指南书 JSON 加载器
 * 从 assets/simukraft/guidebook/ 目录加载页面配置
 */
@SuppressWarnings("null")
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = Simukraft.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class GuideBookLoader extends SimplePreparableReloadListener<Map<String, GuideBookPage>> {

    private static final String GUIDEBOOK_PATH = "guidebook";
    private static final String INDEX_FILE = "index.json";

    private static final Map<String, GuideBookPage> PAGES = new ConcurrentHashMap<>();
    private static final Map<String, String> TAB_ICONS = new ConcurrentHashMap<>();
    private static String indexPageId = "directory";

    @Override
    @Nonnull
    protected Map<String, GuideBookPage> prepare(@Nonnull ResourceManager resourceManager, @Nonnull ProfilerFiller profiler) {
        Map<String, GuideBookPage> loadedPages = new HashMap<>();

        // 加载索引文件
        ResourceLocation indexLocation = ResourceLocation.fromNamespaceAndPath(Simukraft.MOD_ID, GUIDEBOOK_PATH + "/" + INDEX_FILE);
        try {
            resourceManager.getResource(indexLocation).ifPresent(resource -> {
                try (InputStreamReader reader = new InputStreamReader(resource.open(), StandardCharsets.UTF_8)) {
                    JsonObject indexJson = JsonParser.parseReader(reader).getAsJsonObject();
                    if (indexJson.has("index_page")) {
                        indexPageId = indexJson.get("index_page").getAsString();
                    }
                    // 加载页面文件列表
                    if (indexJson.has("pages")) {
                        for (var pageEntry : indexJson.getAsJsonArray("pages")) {
                            String pageFile = pageEntry.getAsString();
                            loadPageFile(resourceManager, pageFile, loadedPages);
                        }
                    }
                } catch (Exception e) {
                    Simukraft.LOGGER.error("Failed to load guidebook index", e);
                }
            });
        } catch (Exception e) {
            Simukraft.LOGGER.error("Failed to load guidebook index", e);
        }

        // 如果没有索引或加载失败，尝试直接加载所有页面文件
        if (loadedPages.isEmpty()) {
            loadAllPages(resourceManager, loadedPages);
        }

        return loadedPages;
    }

    private static void loadPageFile(ResourceManager resourceManager, String fileName, Map<String, GuideBookPage> loadedPages) {
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(Simukraft.MOD_ID, GUIDEBOOK_PATH + "/" + fileName);
        Simukraft.LOGGER.info("GuideBookLoader: Attempting to load {}", location);

        var resourceOpt = resourceManager.getResource(location);
        if (resourceOpt.isEmpty()) {
            Simukraft.LOGGER.warn("GuideBookLoader: Resource not found: {}", location);
            return;
        }

        resourceOpt.ifPresent(resource -> {
            try (InputStreamReader reader = new InputStreamReader(resource.open(), StandardCharsets.UTF_8)) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                String pageId = fileName.replace(".json", "");
                Simukraft.LOGGER.info("GuideBookLoader: Parsing page '{}', JSON keys: {}", pageId, json.keySet());

                GuideBookPage page = GuideBookPage.fromJson(pageId, json);
                loadedPages.put(pageId, page);
                Simukraft.LOGGER.info("GuideBookLoader: Successfully loaded page '{}' with {} pages", pageId, page.getTotalPages());

                // 记录 tab 图标
                if (page.getTabIcon() != null) {
                    TAB_ICONS.put(pageId, page.getTabIcon());
                    Simukraft.LOGGER.info("GuideBookLoader: Page '{}' has tab icon: {}", pageId, page.getTabIcon());
                }
            } catch (Exception e) {
                Simukraft.LOGGER.error("GuideBookLoader: Failed to load guidebook page: {}", fileName, e);
            }
        });
    }

    private static void loadAllPages(ResourceManager resourceManager, Map<String, GuideBookPage> loadedPages) {
        for (var entry : resourceManager.listResources(GUIDEBOOK_PATH, path -> path.getPath().endsWith(".json")).entrySet()) {
            String path = entry.getKey().getPath();
            if (path.endsWith(".json") && !path.endsWith(INDEX_FILE)) {
                String fileName = path.substring(path.lastIndexOf('/') + 1);
                loadPageFile(resourceManager, fileName, loadedPages);
            }
        }
    }

    @Override
    protected void apply(@Nonnull Map<String, GuideBookPage> pages, @Nonnull ResourceManager resourceManager, @Nonnull ProfilerFiller profiler) {
        PAGES.clear();
        PAGES.putAll(pages);
        // 资源重载时清除图片缓存，避免内存泄漏和旧纹理残留
        GuideBookImageCache.clear();
        Simukraft.LOGGER.info("Loaded {} guidebook pages", PAGES.size());
        for (Map.Entry<String, GuideBookPage> entry : PAGES.entrySet()) {
            GuideBookPage page = entry.getValue();
            Simukraft.LOGGER.info("  - {}: {} pages, parent={}, tabIcon={}",
                    entry.getKey(), page.getTotalPages(), page.getParentId(), page.getTabIcon());
        }
    }

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new GuideBookLoader());
    }

    // 公共 API

    public static Optional<GuideBookPage> getPage(String id) {
        return Optional.ofNullable(PAGES.get(id));
    }

    public static GuideBookPage getIndexPage() {
        return PAGES.getOrDefault(indexPageId, PAGES.values().stream().filter(GuideBookPage::isDirectory).findFirst().orElse(null));
    }

    public static Collection<GuideBookPage> getAllPages() {
        return Collections.unmodifiableCollection(PAGES.values());
    }

    public static List<GuideBookPage> getChildPages(String parentId) {
        List<GuideBookPage> children = new ArrayList<>();
        for (GuideBookPage page : PAGES.values()) {
            if (parentId.equals(page.getParentId())) {
                children.add(page);
            }
        }
        children.sort(Comparator.comparingInt(GuideBookPage::getTabIndex));
        return children;
    }

    public static Optional<String> getTabIcon(String pageId) {
        return Optional.ofNullable(TAB_ICONS.get(pageId));
    }

    public static boolean hasPage(String id) {
        return PAGES.containsKey(id);
    }

    /**
     * 热重载指南书内容
     * @param resourceManager 资源管理器
     * @return 重载的页面数量
     */
    public static int reload(net.minecraft.server.packs.resources.ResourceManager resourceManager) {
        Simukraft.LOGGER.info("GuideBookLoader: Hot reloading guidebook pages...");
        Simukraft.LOGGER.info("GuideBookLoader: ResourceManager type = {}", resourceManager.getClass().getName());

        // 先清空现有页面，确保重新加载
        int oldPageCount = PAGES.size();
        PAGES.clear();
        TAB_ICONS.clear();
        Simukraft.LOGGER.info("GuideBookLoader: Cleared {} old pages", oldPageCount);

        Map<String, GuideBookPage> loadedPages = new HashMap<>();

        // 加载索引文件
        ResourceLocation indexLocation = ResourceLocation.fromNamespaceAndPath(Simukraft.MOD_ID, GUIDEBOOK_PATH + "/" + INDEX_FILE);
        Simukraft.LOGGER.info("GuideBookLoader: Loading index from {}", indexLocation);

        try {
            var resourceOpt = resourceManager.getResource(indexLocation);
            Simukraft.LOGGER.info("GuideBookLoader: Index resource present = {}", resourceOpt.isPresent());

            resourceOpt.ifPresent(resource -> {
                try (InputStreamReader reader = new InputStreamReader(resource.open(), StandardCharsets.UTF_8)) {
                    JsonObject indexJson = JsonParser.parseReader(reader).getAsJsonObject();
                    Simukraft.LOGGER.info("GuideBookLoader: Index JSON keys = {}", indexJson.keySet());

                    if (indexJson.has("index_page")) {
                        indexPageId = indexJson.get("index_page").getAsString();
                        Simukraft.LOGGER.info("GuideBookLoader: Index page ID = {}", indexPageId);
                    }
                    // 加载页面文件列表
                    if (indexJson.has("pages")) {
                        var pagesArray = indexJson.getAsJsonArray("pages");
                        Simukraft.LOGGER.info("GuideBookLoader: Found {} page files in index", pagesArray.size());
                        for (var pageEntry : pagesArray) {
                            String pageFile = pageEntry.getAsString();
                            Simukraft.LOGGER.info("GuideBookLoader: Loading page file: {}", pageFile);
                            loadPageFile(resourceManager, pageFile, loadedPages);
                        }
                    }
                } catch (Exception e) {
                    Simukraft.LOGGER.error("GuideBookLoader: Failed to reload index file", e);
                }
            });
        } catch (Exception e) {
            Simukraft.LOGGER.error("GuideBookLoader: Failed to reload guidebook", e);
            return 0;
        }

        // 应用加载的页面
        PAGES.putAll(loadedPages);

        Simukraft.LOGGER.info("GuideBookLoader: Hot reloaded {} guidebook pages", PAGES.size());
        for (String pageId : PAGES.keySet()) {
            Simukraft.LOGGER.info("GuideBookLoader: - Loaded page: {}", pageId);
        }
        return PAGES.size();
    }

    /**
     * 检查页面是否已加载
     */
    public static boolean isLoaded() {
        return !PAGES.isEmpty();
    }

    /**
     * 手动加载页面（用于资源尚未加载时）
     */
    public static void loadIfEmpty(net.minecraft.server.packs.resources.ResourceManager resourceManager) {
        if (PAGES.isEmpty()) {
            Simukraft.LOGGER.info("GuideBookLoader: PAGES is empty, attempting manual load...");
            Map<String, GuideBookPage> loadedPages = new HashMap<>();

            // 加载索引文件
            ResourceLocation indexLocation = ResourceLocation.fromNamespaceAndPath(Simukraft.MOD_ID, GUIDEBOOK_PATH + "/" + INDEX_FILE);
            try {
                resourceManager.getResource(indexLocation).ifPresent(resource -> {
                    try (InputStreamReader reader = new InputStreamReader(resource.open(), StandardCharsets.UTF_8)) {
                        JsonObject indexJson = JsonParser.parseReader(reader).getAsJsonObject();
                        if (indexJson.has("index_page")) {
                            indexPageId = indexJson.get("index_page").getAsString();
                        }
                        // 加载页面文件列表
                        if (indexJson.has("pages")) {
                            for (var pageEntry : indexJson.getAsJsonArray("pages")) {
                                String pageFile = pageEntry.getAsString();
                                loadPageFile(resourceManager, pageFile, loadedPages);
                            }
                        }
                    } catch (Exception e) {
                        Simukraft.LOGGER.error("Failed to load guidebook index manually", e);
                    }
                });
            } catch (Exception e) {
                Simukraft.LOGGER.error("Failed to load guidebook index manually", e);
            }

            // 如果索引加载失败，尝试加载所有页面文件
            if (loadedPages.isEmpty()) {
                loadAllPages(resourceManager, loadedPages);
            }

            PAGES.putAll(loadedPages);
            Simukraft.LOGGER.info("GuideBookLoader: Manually loaded {} guidebook pages", PAGES.size());
        }
    }
}
