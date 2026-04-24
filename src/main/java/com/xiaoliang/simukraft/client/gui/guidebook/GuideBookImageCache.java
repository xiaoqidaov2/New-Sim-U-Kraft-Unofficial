package com.xiaoliang.simukraft.client.gui.guidebook;

import com.mojang.blaze3d.platform.NativeImage;
import com.xiaoliang.simukraft.Simukraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 指南书图片缓存管理器
 * 支持本地 ResourceLocation 和远程 URL 图片加载
 */
@SuppressWarnings("null")
@OnlyIn(Dist.CLIENT)
public class GuideBookImageCache {

    private static final Map<String, ImageEntry> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, CompletableFuture<ImageEntry>> LOADING = new ConcurrentHashMap<>();
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 15000;
    private static int textureIdCounter = 0;
    
    // 图片加载完成回调
    private static Consumer<String> onImageLoaded = null;
    
    /**
     * 设置图片加载完成回调
     * @param callback 回调函数，参数为图片URL
     */
    public static void setOnImageLoadedCallback(Consumer<String> callback) {
        onImageLoaded = callback;
    }

    /**
     * 图片条目，封装纹理和尺寸信息
     */
    public static class ImageEntry {
        public final ResourceLocation texture;
        public final int width;
        public final int height;
        public final boolean loaded;
        public final boolean isRemote;

        public ImageEntry(ResourceLocation texture, int width, int height, boolean loaded, boolean isRemote) {
            this.texture = texture;
            this.width = width;
            this.height = height;
            this.loaded = loaded;
            this.isRemote = isRemote;
        }

        public static ImageEntry missing() {
            return new ImageEntry(null, 16, 16, false, false);
        }
    }

    /**
     * 获取或加载图片
     *
     * @param imageUrl 图片地址（本地 ResourceLocation 字符串或 http/https URL）
     * @return 图片条目（可能尚未加载完成）
     */
    @Nonnull
    public static ImageEntry getOrLoad(@Nonnull String imageUrl) {
        // 检查缓存
        ImageEntry cached = CACHE.get(imageUrl);
        if (cached != null) {
            return cached;
        }

        // 检查是否正在加载
        CompletableFuture<ImageEntry> future = LOADING.get(imageUrl);
        if (future != null) {
            if (!future.isDone()) {
                // 正在加载中
                return ImageEntry.missing();
            }
            // 加载已完成，移除标记
            LOADING.remove(imageUrl);
        }

        // 判断是本地资源还是远程 URL
        if (imageUrl.startsWith("http://") || imageUrl.startsWith("https://")) {
            loadRemoteImage(imageUrl);
        } else {
            loadLocalImage(imageUrl);
        }

        return ImageEntry.missing();
    }

    /**
     * 加载本地 ResourceLocation 图片
     */
    private static void loadLocalImage(@Nonnull String path) {
        try {
            ResourceLocation location = parseResourceLocation(path);
            if (location == null) {
                CACHE.put(path, ImageEntry.missing());
                return;
            }

            Minecraft.getInstance().getResourceManager().getResource(location).ifPresentOrElse(
                    resource -> {
                        try (InputStream stream = resource.open()) {
                            NativeImage image = NativeImage.read(stream);
                            ResourceLocation texture = registerTexture(image, path);
                            CACHE.put(path, new ImageEntry(texture, image.getWidth(), image.getHeight(), true, false));
                            image.close();
                        } catch (Exception e) {
                            Simukraft.LOGGER.warn("GuideBook: Failed to load local image {}", path, e);
                            CACHE.put(path, ImageEntry.missing());
                        }
                    },
                    () -> {
                        Simukraft.LOGGER.warn("GuideBook: Local image not found: {}", path);
                        CACHE.put(path, ImageEntry.missing());
                    }
            );
        } catch (Exception e) {
            Simukraft.LOGGER.warn("GuideBook: Failed to load local image {}", path, e);
            CACHE.put(path, ImageEntry.missing());
        }
    }

    /**
     * 异步加载远程图片
     */
    private static void loadRemoteImage(@Nonnull String url) {
        Simukraft.LOGGER.info("GuideBook: Starting to load remote image: {}", url);
        
        // 创建 CompletableFuture 并放入 LOADING
        CompletableFuture<ImageEntry> future = CompletableFuture.supplyAsync(() -> {
            Simukraft.LOGGER.info("GuideBook: Downloading image from: {}", url);
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setRequestProperty("User-Agent", "Simukraft-GuideBook/1.0");
                connection.setInstanceFollowRedirects(true);

                int responseCode = connection.getResponseCode();
                Simukraft.LOGGER.info("GuideBook: HTTP response code: {} for URL: {}", responseCode, url);
                
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    Simukraft.LOGGER.warn("GuideBook: HTTP error {} for image URL: {}", responseCode, url);
                    return ImageEntry.missing();
                }

                String contentType = connection.getContentType();
                Simukraft.LOGGER.info("GuideBook: Content-Type: {} for URL: {}", contentType, url);
                
                if (contentType != null && !contentType.startsWith("image/")) {
                    Simukraft.LOGGER.warn("GuideBook: Non-image content type '{}' for URL: {}", contentType, url);
                    return ImageEntry.missing();
                }

                try (InputStream stream = connection.getInputStream()) {
                    Simukraft.LOGGER.info("GuideBook: Reading image data from: {}", url);
                    NativeImage image = NativeImage.read(stream);
                    final int width = image.getWidth();
                    final int height = image.getHeight();
                    Simukraft.LOGGER.info("GuideBook: Image size: {}x{} from: {}", width, height, url);

                    // 在主线程注册纹理并更新缓存
                    Minecraft.getInstance().execute(() -> {
                        try {
                            Simukraft.LOGGER.info("GuideBook: Registering texture for: {}", url);
                            ResourceLocation texture = registerTexture(image, url);
                            CACHE.put(url, new ImageEntry(texture, width, height, true, true));
                            Simukraft.LOGGER.info("GuideBook: Successfully loaded remote image: {} ({}x{})", url, width, height);
                            // 触发加载完成回调
                            if (onImageLoaded != null) {
                                onImageLoaded.accept(url);
                            }
                        } catch (Exception e) {
                            Simukraft.LOGGER.warn("GuideBook: Failed to register texture for URL: {}", url, e);
                            CACHE.put(url, ImageEntry.missing());
                        } finally {
                            image.close();
                            LOADING.remove(url);
                        }
                    });

                    return new ImageEntry(null, width, height, false, true);
                }
            } catch (Exception e) {
                Simukraft.LOGGER.error("GuideBook: Failed to download image from URL: {}", url, e);
                return ImageEntry.missing();
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
                // 确保从 LOADING 中移除
                LOADING.remove(url);
            }
        });
        
        LOADING.put(url, future);
        Simukraft.LOGGER.info("GuideBook: Image loading started for: {}", url);
    }

    /**
     * 解析 ResourceLocation 字符串
     * 支持格式：
     * - "modid:guidebook/image/image.png"（完整格式）
     * - "image.png"（简写，自动补全 modid 和 guidebook/image/ 前缀）
     * - "guidebook/image/image.png"（自动补全 modid）
     */
    @Nullable
    private static ResourceLocation parseResourceLocation(@Nonnull String path) {
        if (path.contains(":")) {
            return ResourceLocation.tryParse(path);
        }
        // 自动补全 guidebook/image/ 前缀
        String fullPath = path.startsWith("guidebook/image/") ? path : "guidebook/image/" + path;
        return ResourceLocation.fromNamespaceAndPath(Simukraft.MOD_ID, fullPath);
    }

    /**
     * 注册动态纹理
     */
    @Nonnull
    private static synchronized ResourceLocation registerTexture(@Nonnull NativeImage image, @Nonnull String key) {
        String textureName = "guidebook_image_" + textureIdCounter++ + "_" + Math.abs(key.hashCode());
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(Simukraft.MOD_ID, textureName);
        DynamicTexture dynamicTexture = new DynamicTexture(image);
        Minecraft.getInstance().getTextureManager().register(location, dynamicTexture);
        return location;
    }

    /**
     * 清除所有缓存（用于资源重载时）
     */
    public static void clear() {
        for (ImageEntry entry : CACHE.values()) {
            if (entry.texture != null) {
                Minecraft.getInstance().execute(() ->
                        Minecraft.getInstance().getTextureManager().release(entry.texture)
                );
            }
        }
        CACHE.clear();
        LOADING.clear();
        textureIdCounter = 0;
        Simukraft.LOGGER.info("GuideBook: Image cache cleared");
    }

    /**
     * 移除单个图片缓存（用于重新加载失败的图片）
     * @param imageUrl 图片地址
     */
    public static void removeFromCache(@Nonnull String imageUrl) {
        ImageEntry entry = CACHE.remove(imageUrl);
        if (entry != null && entry.texture != null) {
            Minecraft.getInstance().execute(() ->
                    Minecraft.getInstance().getTextureManager().release(entry.texture)
            );
            Simukraft.LOGGER.info("GuideBook: Removed image from cache: {}", imageUrl);
        }
        LOADING.remove(imageUrl);
    }

    /**
     * 强制重新加载图片（清除缓存后重新加载）
     * @param imageUrl 图片地址
     */
    public static void reloadImage(@Nonnull String imageUrl) {
        removeFromCache(imageUrl);
        getOrLoad(imageUrl);
        Simukraft.LOGGER.info("GuideBook: Reloading image: {}", imageUrl);
    }

    /**
     * 检查图片是否已加载完成
     */
    public static boolean isLoaded(@Nonnull String imageUrl) {
        ImageEntry entry = CACHE.get(imageUrl);
        return entry != null && entry.loaded;
    }
}
