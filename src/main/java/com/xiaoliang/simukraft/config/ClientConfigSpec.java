package com.xiaoliang.simukraft.config;

import net.minecraftforge.common.ForgeConfigSpec;

public final class ClientConfigSpec {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.ConfigValue<Integer> HUD_POS_X;
    public static final ForgeConfigSpec.ConfigValue<Integer> HUD_POS_Y;
    public static final ForgeConfigSpec.ConfigValue<String> HUD_ANCHOR;
    public static final ForgeConfigSpec.ConfigValue<String> MAP_RENDER_STYLE;
    public static final ForgeConfigSpec.ConfigValue<Boolean> ALWAYS_SHOW_NPC_PATH_DEBUG;

    private static final String DEFAULT_HUD_ANCHOR = "TOP_RIGHT";
    private static final int DEFAULT_HUD_POS_X = -5;
    private static final int DEFAULT_HUD_POS_Y = 5;
    private static final String DEFAULT_MAP_RENDER_STYLE = "SIMUKRAFT";
    private static final boolean DEFAULT_ALWAYS_SHOW_NPC_PATH_DEBUG = false;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.push("hud");
        HUD_ANCHOR = builder
                .comment("HUD anchor position: TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, TOP_CENTER, BOTTOM_CENTER")
                .define("anchor", DEFAULT_HUD_ANCHOR);
        HUD_POS_X = builder
                .comment("HUD X offset from anchor position (can be negative)")
                .define("posX", DEFAULT_HUD_POS_X);
        HUD_POS_Y = builder
                .comment("HUD Y offset from anchor position (can be negative)")
                .define("posY", DEFAULT_HUD_POS_Y);
        builder.pop();

        builder.push("map");
        MAP_RENDER_STYLE = builder
                .comment("Map terrain render style: SIMUKRAFT (default self-rendering), XAERO (Xaero's World Map textures), FTB (FTB Chunks textures)")
                .define("renderStyle", DEFAULT_MAP_RENDER_STYLE);
        builder.pop();

        builder.push("pathDebug");
        ALWAYS_SHOW_NPC_PATH_DEBUG = builder
                .comment("Always render NPC path debug lines without pressing F3+K")
                .define("alwaysShow", DEFAULT_ALWAYS_SHOW_NPC_PATH_DEBUG);
        builder.pop();

        SPEC = builder.build();
    }

    private ClientConfigSpec() {
    }

    public static String getDefaultHudAnchor() {
        return DEFAULT_HUD_ANCHOR;
    }

    public static int getDefaultHudPosX() {
        return DEFAULT_HUD_POS_X;
    }

    public static int getDefaultHudPosY() {
        return DEFAULT_HUD_POS_Y;
    }

    public static String getDefaultMapRenderStyle() {
        return DEFAULT_MAP_RENDER_STYLE;
    }

    public static boolean getDefaultAlwaysShowNpcPathDebug() {
        return DEFAULT_ALWAYS_SHOW_NPC_PATH_DEBUG;
    }
}
