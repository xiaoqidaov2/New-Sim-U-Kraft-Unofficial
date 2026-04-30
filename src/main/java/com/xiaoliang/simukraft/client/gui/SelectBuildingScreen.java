package com.xiaoliang.simukraft.client.gui;

import com.lowdragmc.lowdraglib.gui.modular.IUIHolder;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.texture.ColorBorderTexture;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.ResourceTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.util.ClickData;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.utils.Size;
import com.xiaoliang.simukraft.client.gui.ldlib.LDLibMenuScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nonnull;

@OnlyIn(Dist.CLIENT)
public class SelectBuildingScreen extends LDLibMenuScreen {
    private static final int HEIGHT = 200;
    private static final int BUTTON_WIDTH = 110;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_SPACING = 5;
    private static final int SHORT_BUTTON_WIDTH_THRESHOLD = 60;
    private static final int SHORT_BUTTON_HEIGHT_THRESHOLD = 24;
    private static final int HOVER_BORDER_COLOR = 0xFFADD8E6;

    private static final String LONG_BUTTON_TEXTURE = "simukraft:textures/gui/long_button.png";
    private static final String BUTTON_TEXTURE = "simukraft:textures/gui/button.png";

    private final BlockPos buildBoxPos;
    
    public SelectBuildingScreen(BlockPos buildBoxPos) {
        super(Component.translatable("gui.select_building.title"), null);
        this.buildBoxPos = buildBoxPos;
    }

    @Override
    protected int getUIWidth() {
        return Minecraft.getInstance().getWindow().getGuiScaledWidth();
    }

    @Override
    protected int getUIHeight() {
        return Minecraft.getInstance().getWindow().getGuiScaledHeight();
    }

    @Override
    protected boolean enableAutoScale() {
        return false;
    }

    @Override
    protected ModularUI createModularUI() {
        return createModularUI(buildBoxPos);
    }

    private static ModularUI createModularUI(BlockPos buildBoxPos) {
        int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int screenHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        int centerX = screenWidth / 2;
        int centerY = screenHeight / 2;

        Player player = Minecraft.getInstance().player;
        SelectBuildingUIHolder holder = new SelectBuildingUIHolder();
        ModularUI modularUI = new ModularUI(new Size(screenWidth, screenHeight), holder, player);

        WidgetGroup rootGroup = new WidgetGroup();
        rootGroup.setSelfPosition(0, 0);
        rootGroup.setSize(screenWidth, screenHeight);

        TextTexture titleTexture = new TextTexture("gui.select_building.title");
        titleTexture.setWidth(screenWidth);
        titleTexture.setDropShadow(true);
        rootGroup.addWidget(new ImageWidget(0, 25, screenWidth, 20, titleTexture));

        TextTexture statusTexture = new TextTexture(() -> Component.translatable("gui.select_building.status_working").getString());
        statusTexture.setWidth(screenWidth);
        statusTexture.setColor(0xADD8E6);
        statusTexture.setDropShadow(true);
        rootGroup.addWidget(new ImageWidget(0, 45, screenWidth, 20, statusTexture));

        TextTexture instructionTexture = new TextTexture("gui.select_building.instruction");
        instructionTexture.setWidth(screenWidth);
        instructionTexture.setColor(0xF5F5A0);
        instructionTexture.setDropShadow(true);
        rootGroup.addWidget(new ImageWidget(0, centerY - HEIGHT / 2 + 65, screenWidth, 20, instructionTexture));

        TextTexture copyrightTexture = new TextTexture("gui.copyright");
        copyrightTexture.setWidth(200);
        copyrightTexture.setType(TextTexture.TextType.RIGHT);
        copyrightTexture.setColor(0x666666);
        rootGroup.addWidget(new ImageWidget(screenWidth - 205, 5, 200, 16, copyrightTexture));

        rootGroup.addWidget(createButton(5, 5, 45, 20, "gui.build_box.done",
                clickData -> Minecraft.getInstance().setScreen(null)));

        int buttonY = 150;
        int totalWidth = BUTTON_WIDTH * 4 + BUTTON_SPACING * 3;
        int startX = centerX - totalWidth / 2;

        rootGroup.addWidget(createButton(startX, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT, "gui.category.residential",
                clickData -> Minecraft.getInstance().setScreen(new BuildingListScreen("residential", Minecraft.getInstance().screen))));
        rootGroup.addWidget(createButton(startX + BUTTON_WIDTH + BUTTON_SPACING, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT, "gui.category.commercial",
                clickData -> Minecraft.getInstance().setScreen(new BuildingListScreen("commercial", Minecraft.getInstance().screen))));
        rootGroup.addWidget(createButton(startX + (BUTTON_WIDTH + BUTTON_SPACING) * 2, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT, "gui.category.industrial",
                clickData -> Minecraft.getInstance().setScreen(new BuildingListScreen("industry", Minecraft.getInstance().screen))));
        rootGroup.addWidget(createButton(startX + (BUTTON_WIDTH + BUTTON_SPACING) * 3, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT, "gui.category.other",
                clickData -> Minecraft.getInstance().setScreen(new BuildingListScreen("other", Minecraft.getInstance().screen))));

        modularUI.widget(rootGroup);
        modularUI.initWidgets();
        return modularUI;
    }

    private static ButtonWidget createButton(int x, int y, int width, int height, String textKey, java.util.function.Consumer<ClickData> onPress) {
        ButtonWidget button = new ButtonWidget();
        button.setSelfPosition(x, y);
        button.setSize(width, height);

        String textureLocation = getButtonTexture(width, height);
        var buttonBackground = new ResourceTexture(textureLocation);
        var buttonHover = new GuiTextureGroup(
                new ResourceTexture(textureLocation),
                new ColorBorderTexture(1, HOVER_BORDER_COLOR)
        );
        var buttonText = new TextTexture(textKey);

        button.setButtonTexture(buttonBackground, buttonText);
        button.setHoverTexture(buttonHover, buttonText);
        button.setOnPressCallback(onPress);
        return button;
    }

    private static String getButtonTexture(int width, int height) {
        boolean useShortTexture = width <= SHORT_BUTTON_WIDTH_THRESHOLD && height <= SHORT_BUTTON_HEIGHT_THRESHOLD;
        if (useShortTexture) {
            return BUTTON_TEXTURE;
        }
        return LONG_BUTTON_TEXTURE;
    }

    @Override
    public void render(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        guiGraphics.fillGradient(0, 0, this.width, this.height, 0xC8000000, 0xC8000000);
        super.render(guiGraphics, mouseX, mouseY, partialTicks);
    }

    private static class SelectBuildingUIHolder implements IUIHolder {
        @Override
        public ModularUI createUI(Player entityPlayer) {
            return null;
        }

        @Override
        public boolean isInvalid() {
            return false;
        }

        @Override
        public boolean isRemote() {
            return true;
        }

        @Override
        public void markAsDirty() {
        }
    }
}
