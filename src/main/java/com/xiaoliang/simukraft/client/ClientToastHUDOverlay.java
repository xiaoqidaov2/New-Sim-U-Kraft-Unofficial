package com.xiaoliang.simukraft.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("null")
public class ClientToastHUDOverlay implements IGuiOverlay {
    public static final ClientToastHUDOverlay INSTANCE = new ClientToastHUDOverlay();
    private static final long TOAST_DURATION_MS = 3000L;
    private static final long CLEANUP_INTERVAL_MS = 5000L;
    private static final ResourceLocation W1_TEXTURE = ResourceLocation.parse("simukraft:textures/gui/w1.png");
    private static final ResourceLocation W2_TEXTURE = ResourceLocation.parse("simukraft:textures/gui/w2.png");
    private static final ResourceLocation G1_TEXTURE = ResourceLocation.parse("simukraft:textures/gui/g1.png");
    private static final float TITLE_MAX_SCALE = 4.0F;
    private static final float TITLE_MIN_SCALE = 1.2F;
    private static final float DESCRIPTION_MAX_SCALE = 2.2F;
    private static final float DESCRIPTION_MIN_SCALE = 1.0F;
    
    // 存储每个玩家的toast显示状态
    private static final Map<UUID, ToastInfo> playerToasts = new ConcurrentHashMap<>();
    private static volatile long lastCleanupTime = 0L;
    
    private static class ToastInfo {
        ResourceLocation texture;
        long startTime;
        long duration;
        com.xiaoliang.simukraft.world.CityUpgradeManager.CityUpgrade upgrade;
        
        ToastInfo(ResourceLocation texture, long duration, com.xiaoliang.simukraft.world.CityUpgradeManager.CityUpgrade upgrade) {
            this.texture = texture;
            this.startTime = System.currentTimeMillis();
            this.duration = duration;
            this.upgrade = upgrade;
        }
        
        boolean isExpired() {
            return System.currentTimeMillis() - startTime > duration;
        }
        
        void update(ResourceLocation texture, long duration, com.xiaoliang.simukraft.world.CityUpgradeManager.CityUpgrade upgrade) {
            this.texture = texture;
            this.duration = duration;
            this.upgrade = upgrade;
            this.startTime = System.currentTimeMillis();
        }
        
        /**
         * 获取展开动画进度（0.0表示完全收起，1.0表示完全展开）
         */
        float getExpandProgress() {
            // 展开动画持续500毫秒
            long animationDuration = 500;
            long currentTime = System.currentTimeMillis() - startTime;
            
            if (currentTime >= animationDuration) {
                // 动画结束，完全展开
                return 1.0f;
            }
            
            // 使用缓动函数，让动画更流畅
            float progress = (float) currentTime / animationDuration;
            // 使用三次缓动函数：progress = progress * progress * (3 - 2 * progress)，这是一个常用的缓动函数，让动画先快后慢
            return progress * progress * (3 - 2 * progress);
        }
    }
    
    @Override
    public void render(@Nonnull ForgeGui gui, @Nonnull GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight) {
        Minecraft mc = Objects.requireNonNull(Minecraft.getInstance());
        pruneExpiredToasts(false);
        if (mc.player == null) {
            return;
        }
        
        UUID playerId = mc.player.getUUID();
        ToastInfo toastInfo = playerToasts.get(playerId);
        
        if (toastInfo != null) {
            if (toastInfo.isExpired()) {
                // 移除过期的toast
                playerToasts.remove(playerId);
            } else {
                // 原图片尺寸
                int textureWidth = 256;
                int textureHeight = 64;
                
                // 使用固定的16:9宽高比
                double aspectRatio = 16.0 / 9.0;
                
                // 保证背景图始终完整显示，并以屏幕中心为锚点布局
                int displayWidth = Math.min(screenWidth, (int) Math.round(screenHeight * aspectRatio));
                // 按16:9比例计算高度
                int displayHeight = (int) Math.round(displayWidth / aspectRatio);
                
                // 水平和垂直均以屏幕中心定位
                int xPos = (screenWidth - displayWidth) / 2;
                int yPos = (screenHeight - displayHeight) / 2;
                
                // 计算屏幕中心位置
                int centerX = screenWidth / 2;
                int centerY = screenHeight / 2;
                
                // 获取展开动画进度
                float expandProgress = toastInfo.getExpandProgress();
                
                // 保存当前矩阵，准备应用展开动画
                guiGraphics.pose().pushPose();
                
                // 1. 全透明背景，不绘制任何背景色
                // 移除半透明背景，实现全透明效果
                
                // 2. 应用缩放变换：从中心向四周缓慢展开
                guiGraphics.pose().translate(centerX, centerY, 0);
                guiGraphics.pose().scale(expandProgress, expandProgress, 1.0F);
                guiGraphics.pose().translate(-centerX, -centerY, 0);
                
                // 3. 绘制图片，使用展开进度控制
                guiGraphics.blit(
                        Objects.requireNonNull(toastInfo.texture),
                        xPos, yPos, 
                        displayWidth, displayHeight, 
                        0.0F, 0.0F, 
                        textureWidth, textureHeight, 
                        textureWidth, textureHeight
                );
                
                // 4. 绘制文本：以屏幕中心为锚点，根据可用区域自适应缩放与换行
                Font font = Objects.requireNonNull(Minecraft.getInstance().font);
                
                // 获取升级信息，用于显示文本
                String largeText = "未知";
                String mediumText = "";
                if (toastInfo.upgrade != null) {
                    largeText = Objects.requireNonNullElse(toastInfo.upgrade.name(), "未知");
                    mediumText = Objects.requireNonNullElse(toastInfo.upgrade.description(), "");
                }
                
                // 标题和描述使用不同的可用宽度，避免长描述把标题一起压得过小。
                int titleAreaWidth = Math.max(120, (int) (displayWidth * 0.55F));
                int descriptionAreaWidth = Math.max(160, (int) (displayWidth * 0.68F));
                float preferredTitleScale = Math.min(TITLE_MAX_SCALE, Math.max(2.0F, displayWidth / 320.0F));
                float titleScale = computeBoundedScale(font.width(largeText), titleAreaWidth, preferredTitleScale, TITLE_MIN_SCALE);
                List<FormattedCharSequence> titleLines = requireFormattedLines(font.split(
                        Component.literal(largeText),
                        Math.max(1, (int) (titleAreaWidth / titleScale))
                ));

                float preferredDescriptionScale = Math.min(DESCRIPTION_MAX_SCALE, Math.max(DESCRIPTION_MIN_SCALE, displayWidth / 360.0F));
                TextLayout descriptionLayout = splitToFit(font, Component.literal(mediumText),
                        descriptionAreaWidth, preferredDescriptionScale);
                List<FormattedCharSequence> descriptionLines = descriptionLayout.lines();
                float descriptionScale = descriptionLayout.scale();

                int gap = Math.max(10, displayHeight / 24);
                int titleLineHeight = Math.max(1, Math.round(font.lineHeight * titleScale));
                int descriptionLineHeight = Math.max(1, Math.round(font.lineHeight * descriptionScale));
                int titleBlockHeight = titleLines.size() * titleLineHeight;
                int descriptionBlockHeight = descriptionLines.size() * descriptionLineHeight;
                // 以屏幕中心为锚点，把标题块和描述块作为一个整体做垂直居中。
                int contentHeight = titleBlockHeight + gap + descriptionBlockHeight;
                int contentTop = centerY - contentHeight / 2;

                drawCenteredScaledLines(guiGraphics, font, titleLines, centerX, contentTop, titleScale, 0xFFFFFF, true);
                drawCenteredScaledLines(guiGraphics, font, descriptionLines, centerX,
                        contentTop + titleBlockHeight + gap, descriptionScale, 0xFFFFFF, true);
                
                // 恢复矩阵
                guiGraphics.pose().popPose();
            }
        }
    }
    
    /**
     * 显示toast图片
     */
    public static void showToast(String type, int upgradeLevel, UUID playerId) {
        ResourceLocation texture;
        switch (Objects.requireNonNull(type, "type").toLowerCase()) {
            case "w1":
                texture = W1_TEXTURE;
                break;
            case "w2":
                texture = W2_TEXTURE;
                break;
            case "g1":
                texture = G1_TEXTURE;
                break;
            default:
                return;
        }
        
        // 获取升级信息，用于显示文本
        com.xiaoliang.simukraft.world.CityUpgradeManager upgradeManager = com.xiaoliang.simukraft.world.CityUpgradeManager.getInstance();
        com.xiaoliang.simukraft.world.CityUpgradeManager.CityUpgrade upgrade = upgradeManager.getUpgrade(upgradeLevel);
        
        // 播放完成音效
        Minecraft mc = Objects.requireNonNull(Minecraft.getInstance());
        mc.getSoundManager().play(Objects.requireNonNull(SimpleSoundInstance.forUI(Objects.requireNonNull(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE), 1.0F, 1.0F)));
        
        // 显示toast
        ToastInfo toastInfo = playerToasts.get(playerId);
        if (toastInfo != null) {
            toastInfo.update(texture, TOAST_DURATION_MS, upgrade);
        } else {
            playerToasts.put(playerId, new ToastInfo(texture, TOAST_DURATION_MS, upgrade));
        }
        pruneExpiredToasts(false);
    }
    
    /**
     * 显示toast图片（兼容旧版本）
     */
    public static void showToast(String type, UUID playerId) {
        showToast(type, 0, playerId);
    }
    
    /**
     * 清除指定玩家的toast
     */
    public static void clearToast(UUID playerId) {
        playerToasts.remove(playerId);
    }

    public static void clearAllToasts() {
        playerToasts.clear();
        lastCleanupTime = 0L;
    }

    private static void pruneExpiredToasts(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastCleanupTime < CLEANUP_INTERVAL_MS) {
            return;
        }
        lastCleanupTime = now;
        playerToasts.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    private static float computeBoundedScale(int textWidth, int maxWidth, float preferredScale, float minScale) {
        if (textWidth <= 0) {
            return preferredScale;
        }
        // 单行能放下时保持偏大的展示比例，放不下时再压到最小可接受比例。
        float fitScale = (float) maxWidth / (float) textWidth;
        return Math.max(minScale, Math.min(preferredScale, fitScale));
    }

    private static TextLayout splitToFit(Font font, Component text, int maxWidth, float preferredScale) {
        float scale = preferredScale;
        List<FormattedCharSequence> lines = requireFormattedLines(font.split(text, Math.max(1, (int) (maxWidth / scale))));
        // 最多保留三行；如果行数过多，就逐步缩小字号而不是直接裁字。
        while (lines.size() > 3 && scale > DESCRIPTION_MIN_SCALE) {
            scale = Math.max(DESCRIPTION_MIN_SCALE, scale - 0.15F);
            lines = requireFormattedLines(font.split(text, Math.max(1, (int) (maxWidth / scale))));
        }
        return new TextLayout(lines, scale);
    }

    private static void drawCenteredScaledLines(@Nonnull GuiGraphics guiGraphics, @Nonnull Font font, @Nonnull List<FormattedCharSequence> lines,
                                                int centerX, int startY, float scale, int color, boolean shadow) {
        int lineHeight = Math.max(1, Math.round(font.lineHeight * scale));
        int currentY = startY;
        for (FormattedCharSequence line : lines) {
            int lineWidth = font.width(line);
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(centerX, currentY, 0);
            guiGraphics.pose().scale(scale, scale, 1.0F);
            guiGraphics.drawString(font, line, -lineWidth / 2, 0, color, shadow);
            guiGraphics.pose().popPose();
            currentY += lineHeight;
        }
    }

    @Nonnull
    private static List<FormattedCharSequence> requireFormattedLines(List<FormattedCharSequence> lines) {
        return Objects.requireNonNull(lines);
    }

    private record TextLayout(List<FormattedCharSequence> lines, float scale) {
    }
}
