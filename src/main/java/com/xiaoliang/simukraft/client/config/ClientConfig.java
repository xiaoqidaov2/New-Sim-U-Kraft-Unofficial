package com.xiaoliang.simukraft.client.config;

import com.xiaoliang.simukraft.client.map.MapRenderStyle;
import com.xiaoliang.simukraft.config.ClientConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec;

public class ClientConfig {
    private static final String DEFAULT_HUD_ANCHOR = ClientConfigSpec.getDefaultHudAnchor();
    private static final int DEFAULT_HUD_POS_X = ClientConfigSpec.getDefaultHudPosX();
    private static final int DEFAULT_HUD_POS_Y = ClientConfigSpec.getDefaultHudPosY();
    private static final String DEFAULT_MAP_RENDER_STYLE = ClientConfigSpec.getDefaultMapRenderStyle();
    private static final boolean DEFAULT_ALWAYS_SHOW_NPC_PATH_DEBUG = ClientConfigSpec.getDefaultAlwaysShowNpcPathDebug();

    public static final ForgeConfigSpec SPEC = ClientConfigSpec.SPEC;
    public static final ForgeConfigSpec.ConfigValue<Integer> HUD_POS_X = ClientConfigSpec.HUD_POS_X;
    public static final ForgeConfigSpec.ConfigValue<Integer> HUD_POS_Y = ClientConfigSpec.HUD_POS_Y;
    public static final ForgeConfigSpec.ConfigValue<String> HUD_ANCHOR = ClientConfigSpec.HUD_ANCHOR;
    public static final ForgeConfigSpec.ConfigValue<String> MAP_RENDER_STYLE = ClientConfigSpec.MAP_RENDER_STYLE;
    public static final ForgeConfigSpec.ConfigValue<Boolean> ALWAYS_SHOW_NPC_PATH_DEBUG = ClientConfigSpec.ALWAYS_SHOW_NPC_PATH_DEBUG;

    private static MapRenderStyle cachedMapRenderStyle = null;
    private static Anchor cachedAnchor = null;
    private static Integer cachedPosX = null;
    private static Integer cachedPosY = null;
    private static Boolean cachedAlwaysShowNpcPathDebug = null;

    public enum Anchor {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT,
        TOP_CENTER,
        BOTTOM_CENTER
    }

    public static Anchor getAnchor() {
        if (cachedAnchor != null) {
            return cachedAnchor;
        }
        try {
            cachedAnchor = Anchor.valueOf(getConfigValue(HUD_ANCHOR, DEFAULT_HUD_ANCHOR));
        } catch (IllegalArgumentException e) {
            cachedAnchor = Anchor.TOP_RIGHT;
        }
        return cachedAnchor;
    }

    public static void setAnchor(Anchor anchor) {
        cachedAnchor = anchor;
        HUD_ANCHOR.set(anchor.name());
        SPEC.save();
    }

    public static int getPosX() {
        if (cachedPosX != null) {
            return cachedPosX;
        }
        cachedPosX = getConfigValue(HUD_POS_X, DEFAULT_HUD_POS_X);
        return cachedPosX;
    }

    public static void setPosX(int posX) {
        cachedPosX = posX;
        HUD_POS_X.set(posX);
        SPEC.save();
    }

    public static int getPosY() {
        if (cachedPosY != null) {
            return cachedPosY;
        }
        cachedPosY = getConfigValue(HUD_POS_Y, DEFAULT_HUD_POS_Y);
        return cachedPosY;
    }

    public static void setPosY(int posY) {
        cachedPosY = posY;
        HUD_POS_Y.set(posY);
        SPEC.save();
    }

    public static MapRenderStyle getMapRenderStyle() {
        if (cachedMapRenderStyle != null) {
            return cachedMapRenderStyle;
        }
        cachedMapRenderStyle = MapRenderStyle.fromString(getConfigValue(MAP_RENDER_STYLE, DEFAULT_MAP_RENDER_STYLE));
        return cachedMapRenderStyle;
    }

    public static void setMapRenderStyle(MapRenderStyle style) {
        cachedMapRenderStyle = style;
        MAP_RENDER_STYLE.set(style.name());
        SPEC.save();
    }

    public static boolean isAlwaysShowNpcPathDebug() {
        if (cachedAlwaysShowNpcPathDebug != null) {
            return cachedAlwaysShowNpcPathDebug;
        }
        cachedAlwaysShowNpcPathDebug = getConfigValue(ALWAYS_SHOW_NPC_PATH_DEBUG, DEFAULT_ALWAYS_SHOW_NPC_PATH_DEBUG);
        return cachedAlwaysShowNpcPathDebug;
    }

    public static void setAlwaysShowNpcPathDebug(boolean enabled) {
        cachedAlwaysShowNpcPathDebug = enabled;
        ALWAYS_SHOW_NPC_PATH_DEBUG.set(enabled);
        SPEC.save();
    }

    public static int[] calculatePosition(int screenWidth, int screenHeight, int textWidth) {
        Anchor anchor = getAnchor();
        int posX = getPosX();
        int posY = getPosY();

        int x, y;
        switch (anchor) {
            case TOP_LEFT -> {
                x = posX;
                y = posY;
            }
            case TOP_RIGHT -> {
                x = screenWidth - textWidth + posX;
                y = posY;
            }
            case BOTTOM_LEFT -> {
                x = posX;
                y = screenHeight - 10 + posY;
            }
            case BOTTOM_RIGHT -> {
                x = screenWidth - textWidth + posX;
                y = screenHeight - 10 + posY;
            }
            case TOP_CENTER -> {
                x = (screenWidth - textWidth) / 2 + posX;
                y = posY;
            }
            case BOTTOM_CENTER -> {
                x = (screenWidth - textWidth) / 2 + posX;
                y = screenHeight - 10 + posY;
            }
            default -> {
                x = screenWidth - textWidth - 5;
                y = 5;
            }
        }
        return new int[]{x, y};
    }

    public static void clearCache() {
        cachedAnchor = null;
        cachedPosX = null;
        cachedPosY = null;
        cachedMapRenderStyle = null;
        cachedAlwaysShowNpcPathDebug = null;
    }

    private static <T> T getConfigValue(ForgeConfigSpec.ConfigValue<T> configValue, T defaultValue) {
        try {
            return configValue.get();
        } catch (IllegalStateException e) {
            return defaultValue;
        }
    }
}
