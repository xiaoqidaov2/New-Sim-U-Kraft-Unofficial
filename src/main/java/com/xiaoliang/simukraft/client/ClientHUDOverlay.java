package com.xiaoliang.simukraft.client;

import com.xiaoliang.simukraft.client.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Locale;
import java.util.Objects;

public class ClientHUDOverlay implements IGuiOverlay {
    public static final ClientHUDOverlay INSTANCE = new ClientHUDOverlay();
    private static final int HUD_COLOR = 0xFFFFFF;
    private static final String[] WEEKDAYS = {
            "weekday.sunday",
            "weekday.monday", 
            "weekday.tuesday",
            "weekday.wednesday",
            "weekday.thursday",
            "weekday.friday",
            "weekday.saturday"
    };
    @Nonnull
    private static String cachedDisplayText = "";
    private static int cachedTextWidth = 0;
    private static int cachedDay = Integer.MIN_VALUE;
    private static int cachedWorldPopulation = Integer.MIN_VALUE;
    @Nonnull
    private static String cachedCityName = "";
    private static double cachedFunds = Double.NaN;
    private static int cachedCityPopulation = Integer.MIN_VALUE;
    private static boolean cachedCreativeMode = false;

    @Nonnull
    private static String safeText(@Nullable String value) {
        return value != null ? value : "";
    }

    @Override
    public void render(ForgeGui gui, GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight) {
        Minecraft mc = Objects.requireNonNull(Minecraft.getInstance());
        if (mc.player == null || mc.screen != null || mc.options.renderDebug) {
            return;
        }

        try {
            int currentDay = ClientSimukraftData.getCurrentDay();
            int worldPopulation = ClientSimukraftData.getCurrentPopulation();
            String cityName = ClientSimukraftData.getCurrentCityName();
            double funds = ClientSimukraftData.getCurrentCityFunds();
            int cityPopulation = ClientSimukraftData.getCurrentCityPopulation();
            boolean isCreativeMode = ClientSimukraftData.isCreativeMode();
            var font = Objects.requireNonNull(mc.font);

            String displayText = getOrBuildDisplayText(font, currentDay, worldPopulation, cityName, funds, cityPopulation, isCreativeMode);
            int textWidth = cachedTextWidth;

            // 使用配置的锚点位置
            int[] position = ClientConfig.calculatePosition(screenWidth, screenHeight, textWidth);
            int x = position[0];
            int y = position[1];

            guiGraphics.drawString(
                    font,
                    displayText,
                    x,
                    y,
                    HUD_COLOR,
                    true
            );

        } catch (Exception e) {
            // 静默处理异常，确保HUD不影响游戏运行
        }
    }

    @Nonnull
    private static String getOrBuildDisplayText(net.minecraft.client.gui.Font font, int currentDay, int worldPopulation,
                                                @Nullable String cityName, double funds, int cityPopulation, boolean isCreativeMode) {
        String safeCityName = safeText(cityName);
        if (currentDay == cachedDay
                && worldPopulation == cachedWorldPopulation
                && cityPopulation == cachedCityPopulation
                && isCreativeMode == cachedCreativeMode
                && Double.compare(funds, cachedFunds) == 0
                && safeCityName.equals(cachedCityName)) {
            return safeText(cachedDisplayText);
        }

        cachedDay = currentDay;
        cachedWorldPopulation = worldPopulation;
        cachedCityName = safeCityName;
        cachedFunds = funds;
        cachedCityPopulation = cityPopulation;
        cachedCreativeMode = isCreativeMode;

        String weekDayKey = Objects.requireNonNull(WEEKDAYS[Math.floorMod(currentDay - 1, WEEKDAYS.length)]);
        Component weekDayComponent = Component.translatable(weekDayKey);
        String weekDay = Objects.requireNonNull(weekDayComponent.getString());
        StringBuilder statusLine = new StringBuilder(96);

        if (!safeCityName.isEmpty()) {
            statusLine.append(Component.translatable("hud.simukraft.city", safeCityName).getString()).append(" | ");
            String fundsDisplay = isCreativeMode ? "∞" : String.format(Locale.US, "%.2f", funds);
            statusLine.append(Component.translatable("hud.simukraft.funds", fundsDisplay).getString()).append(" | ");
            statusLine.append(weekDay).append(" | ");
            statusLine.append(Component.translatable("hud.simukraft.world_population", worldPopulation).getString()).append(" | ");
            statusLine.append(Component.translatable("hud.simukraft.city_population", cityPopulation).getString());
        } else {
            statusLine.append(weekDay).append(" | ");
            statusLine.append(Component.translatable("hud.simukraft.world_population", worldPopulation).getString());
        }

        cachedDisplayText = safeText(statusLine.toString());
        cachedTextWidth = font.width(cachedDisplayText);
        return safeText(cachedDisplayText);
    }
}
