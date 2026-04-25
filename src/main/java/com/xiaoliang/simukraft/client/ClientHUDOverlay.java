package com.xiaoliang.simukraft.client;

import com.xiaoliang.simukraft.client.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

import java.util.Objects;

public class ClientHUDOverlay implements IGuiOverlay {
    public static final ClientHUDOverlay INSTANCE = new ClientHUDOverlay();
    private static final String[] WEEKDAYS = {
            "weekday.sunday",
            "weekday.monday", 
            "weekday.tuesday",
            "weekday.wednesday",
            "weekday.thursday",
            "weekday.friday",
            "weekday.saturday"
    };

    @Override
    public void render(ForgeGui gui, GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight) {
        Minecraft mc = Objects.requireNonNull(Minecraft.getInstance());
        if (mc.player == null || mc.screen != null || mc.options.renderDebug) {
            return;
        }

        try {
            // 从客户端缓存获取数据
            int currentDay = ClientSimukraftData.getCurrentDay();
            int worldPopulation = ClientSimukraftData.getCurrentPopulation();
            String cityName = ClientSimukraftData.getCurrentCityName();
            double funds = ClientSimukraftData.getCurrentCityFunds();
            int cityPopulation = ClientSimukraftData.getCurrentCityPopulation();

            // 计算周几
            String weekDayKey = Objects.requireNonNull(WEEKDAYS[(currentDay - 1) % 7]);
            Component weekDay = Component.translatable(weekDayKey);

            // 检查创造模式
            boolean isCreativeMode = ClientSimukraftData.isCreativeMode();

            // 构建显示文本 - 只显示周几，不显示天数
            StringBuilder statusLine = new StringBuilder();
            if (!cityName.isEmpty()) {
                // 有城市时显示完整信息
                statusLine.append(Component.translatable("hud.simukraft.city", cityName).getString()).append(" | ");
                // 创造模式下显示∞符号，否则显示正常金额
                String fundsDisplay = isCreativeMode ? "∞" : String.format("%.2f", funds);
                statusLine.append(Component.translatable("hud.simukraft.funds", fundsDisplay).getString()).append(" | ");
                statusLine.append(weekDay.getString()).append(" | ");
                statusLine.append(Component.translatable("hud.simukraft.world_population", worldPopulation).getString()).append(" | ");
                statusLine.append(Component.translatable("hud.simukraft.city_population", cityPopulation).getString());
            } else {
                // 无城市时显示简化信息
                statusLine.append(weekDay.getString()).append(" | ");
                statusLine.append(Component.translatable("hud.simukraft.world_population", worldPopulation).getString());
            }

            String displayText = Objects.requireNonNull(statusLine.toString());
            int textWidth = Objects.requireNonNull(mc.font).width(displayText);

            // 使用配置的锚点位置
            int[] position = ClientConfig.calculatePosition(screenWidth, screenHeight, textWidth);
            int x = position[0];
            int y = position[1];

            // 使用白色统一显示，简化颜色处理
            var font = Objects.requireNonNull(mc.font);
            guiGraphics.drawString(
                    font,
                    displayText,
                    x,
                    y,
                    0xFFFFFF,
                    true
            );

        } catch (Exception e) {
            // 静默处理异常，确保HUD不影响游戏运行
        }
    }
}
