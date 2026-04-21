package com.xiaoliang.simukraft.client.gui;

import com.xiaoliang.simukraft.client.gui.ldlib.ConfigSelectionMenuScreen;
import com.xiaoliang.simukraft.client.update.UpdateHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.jetbrains.annotations.Nullable;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Objects;

/**
 * 配置按钮事件处理器
 * 在主菜单的Mod按钮左侧和暂停菜单的选项按钮左侧添加配置按钮
 * 集成更新通知功能
 */
@OnlyIn(Dist.CLIENT)
public class ConfigButtonHandler {

    // 按钮之间的间距
    private static final int BUTTON_SPACING = 5;
    // 按钮尺寸（方形按钮）
    private static final int BUTTON_SIZE = 20;
    // 配置按钮引用（集成更新提示功能）
    private AnimatedIconButton configButton;

    /**
     * 主菜单初始化事件 - 在Mod按钮左侧添加按钮
     */
    @SubscribeEvent
    public void onTitleScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TitleScreen titleScreen)) {
            return;
        }

        // 查找Mod按钮
        Button modButton = findModButton(titleScreen);
        if (modButton == null) return;

        // 在Mod按钮左侧添加配置按钮（集成更新提示）
        addConfigButtonWithUpdateCheck(event, modButton);

        // 启动时检查更新
        UpdateHandler.getInstance().checkForUpdatesOnStartup();
    }

    /**
     * 屏幕渲染事件 - 更新按钮状态（每帧调用）
     */
    @SubscribeEvent
    public void onScreenRender(ScreenEvent.Render.Post event) {
        if (event.getScreen() instanceof TitleScreen) {
            updateButtonState();
        }
    }

    /**
     * 暂停菜单初始化事件 - 在统计信息按钮右侧添加按钮
     */
    @SubscribeEvent
    public void onPauseScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof PauseScreen pauseScreen)) {
            return;
        }

        // 查找统计信息按钮
        Button statsButton = findStatsButton(pauseScreen);
        if (statsButton == null) return;

        // 在统计信息按钮右侧添加配置按钮
        addAnimatedButtonRightOf(event, statsButton,
            button -> {
                Minecraft.getInstance().setScreen(
                    new ConfigSelectionMenuScreen(event.getScreen())
                );
            },
            Component.translatable("gui.config_button.title"));
    }

    /**
     * 添加配置按钮（集成更新提示功能）
     */
    private void addConfigButtonWithUpdateCheck(ScreenEvent.Init.Post event, Button targetButton) {
        int buttonSize = BUTTON_SIZE;
        int buttonY = targetButton.getY();

        if (targetButton.getHeight() != buttonSize) {
            buttonY = targetButton.getY() + (targetButton.getHeight() - buttonSize) / 2;
        }

        int buttonX = targetButton.getX() - buttonSize - BUTTON_SPACING + 1;

        if (buttonX < 5) {
            buttonX = 5;
            targetButton.setX(buttonX + buttonSize + BUTTON_SPACING - 1);
        }

        // 创建配置按钮（集成更新提示）
        configButton = new AnimatedIconButton(
            buttonX, buttonY,
            buttonSize, buttonSize,
            btn -> {
                // 点击按钮打开配置界面或更新界面
                if (UpdateHandler.getInstance().isUpdateAvailable()) {
                    // 如果有更新，打开更新界面
                    Minecraft.getInstance().setScreen(
                        new UpdateScreenLDLib(event.getScreen(), UpdateHandler.getInstance().getUpdateChecker())
                    );
                } else {
                    // 否则打开配置选择界面
                    Minecraft.getInstance().setScreen(
                        new ConfigSelectionMenuScreen(event.getScreen())
                    );
                }
            }
        );

        // 设置提示文本
        configButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
            nn(Component.translatable("gui.config_button.title"))
        ));

        event.addListener(nn(configButton));
    }

    /**
     * 在目标按钮右侧添加动画图标按钮
     */
    private void addAnimatedButtonRightOf(ScreenEvent.Init.Post event, Button targetButton,
                                         Button.OnPress onPress,
                                         Component tooltip) {
        int buttonSize = BUTTON_SIZE;
        int buttonY = targetButton.getY();

        if (targetButton.getHeight() != buttonSize) {
            buttonY = targetButton.getY() + (targetButton.getHeight() - buttonSize) / 2;
        }

        int buttonX = targetButton.getX() + targetButton.getWidth() + BUTTON_SPACING;

        AnimatedIconButton configButtonTmp = new AnimatedIconButton(
            buttonX, buttonY,
            buttonSize, buttonSize,
            onPress
        );

        configButtonTmp.setTooltip(net.minecraft.client.gui.components.Tooltip.create(nn(tooltip)));
        event.addListener(nn(configButtonTmp));
    }

    /**
     * 专门查找主菜单中的Mod按钮
     */
    @Nullable
    private Button findModButton(TitleScreen screen) {
        int screenWidth = screen.width;
        List<? extends GuiEventListener> children = screen.children();
        Button leftButton = null;

        for (GuiEventListener listener : children) {
            if (listener instanceof Button button) {
                if (button.getX() < screenWidth / 2) {
                    int expectedY = screen.height / 4 + 48 + 48;
                    if (Math.abs(button.getY() - expectedY) < 10) {
                        return button;
                    }
                    leftButton = button;
                }
            }
        }

        return leftButton;
    }

    /**
     * 查找暂停菜单中的统计信息按钮
     */
    @Nullable
    private Button findStatsButton(PauseScreen screen) {
        List<? extends GuiEventListener> children = screen.children();

        for (GuiEventListener listener : children) {
            if (listener instanceof Button button) {
                String text = button.getMessage().getString();

                // 优先匹配统计信息按钮文本
                if (text.contains("统计信息") || text.contains("Statistics") ||
                    text.contains("统计") || text.contains("Stats")) {
                    return button;
                }
            }
        }
        return null;
    }

    /**
     * 查找暂停菜单中的选项按钮
     */
    @Nullable
    private Button findOptionsButton(PauseScreen screen) {
        int screenWidth = screen.width;
        int screenHeight = screen.height;
        List<? extends GuiEventListener> children = screen.children();

        for (GuiEventListener listener : children) {
            if (listener instanceof Button button) {
                String text = button.getMessage().getString();

                if (text.contains("选项") || text.contains("Options") ||
                    text.contains("设置") || text.contains("Settings")) {
                    return button;
                }

                int buttonCenterY = button.getY() + button.getHeight() / 2;
                if (buttonCenterY > screenHeight * 0.6f && buttonCenterY < screenHeight * 0.8f) {
                    int buttonCenterX = button.getX() + button.getWidth() / 2;
                    if (Math.abs(buttonCenterX - screenWidth / 2) < 50) {
                        return button;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 更新按钮状态（每帧调用）
     */
    public void updateButtonState() {
        if (configButton != null) {
            boolean hasUpdate = UpdateHandler.getInstance().isUpdateAvailable();
            configButton.setHasUpdate(hasUpdate);



            // 更新提示文本
            if (hasUpdate) {
                configButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                    nn(Component.translatable("gui.update.tooltip.new_version"))
                ));
            } else {
                configButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                    nn(Component.translatable("gui.config_button.title"))
                ));
            }
        }
    }

    @Nonnull
    private static <T> T nn(T value) {
        return Objects.requireNonNull(value);
    }

}
