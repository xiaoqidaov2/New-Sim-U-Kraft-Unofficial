package com.xiaoliang.simukraft.client.gui;

import com.lowdragmc.lowdraglib.gui.modular.IUIHolder;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.modular.ModularUIGuiContainer;
import com.lowdragmc.lowdraglib.gui.texture.ColorBorderTexture;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.ResourceTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.util.ClickData;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.utils.Size;
import com.xiaoliang.simukraft.client.gui.map.CityMapCanvas;
import com.xiaoliang.simukraft.client.map.SimuMapManager;
import com.xiaoliang.simukraft.init.ModSoundEvents;
import com.xiaoliang.simukraft.network.GetCityInfoPacket;
import com.xiaoliang.simukraft.network.NetworkManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nonnull;
import java.util.Objects;

@OnlyIn(Dist.CLIENT)
public class CityManagementScreen extends ModularUIGuiContainer {
    private static final int BUTTON_WIDTH = 120;
    private static final int BUTTON_HEIGHT = 24;
    private static final int BACK_BUTTON_WIDTH = 50;
    private static final int BACK_BUTTON_HEIGHT = 20;
    private static final int BUTTON_SPACING = 5;
    private static final int SHORT_BUTTON_WIDTH_THRESHOLD = 60;
    private static final int SHORT_BUTTON_HEIGHT_THRESHOLD = 24;
    private static final int HOVER_BORDER_COLOR = 0xFFADD8E6;

    private static final String LONG_BUTTON_TEXTURE = "simukraft:textures/gui/long_button.png";
    private static final String LONG_LOCK_BUTTON_TEXTURE = "simukraft:textures/gui/lock_long_button.png";
    private static final String BUTTON_TEXTURE = "simukraft:textures/gui/button.png";
    private static final String LOCK_BUTTON_TEXTURE = "simukraft:textures/gui/lock_button.png";

    public CityManagementScreen(BlockPos cityCorePos) {
        super(createModularUI(cityCorePos), 0);
        Minecraft.getInstance().getSoundManager().play(nn(SimpleSoundInstance.forUI(nn(ModSoundEvents.CITY_CORE_OPEN.get()), 1.0F)));
    }

    private static ModularUI createModularUI(BlockPos cityCorePos) {
        int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int screenHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        Player player = nn(Minecraft.getInstance().player);
        CityManagementUIHolder holder = new CityManagementUIHolder();
        ModularUI modularUI = new ModularUI(new Size(screenWidth, screenHeight), holder, player);

        WidgetGroup rootGroup = new WidgetGroup();
        rootGroup.setSize(screenWidth, screenHeight);

        TextTexture titleTexture = new TextTexture("gui.city_management.title");
        titleTexture.setWidth(screenWidth);
        titleTexture.setDropShadow(true);
        ImageWidget titleWidget = new ImageWidget(0, 10, screenWidth, 20, titleTexture);
        rootGroup.addWidget(titleWidget);

        int leftX = 20;
        int startY = 36;

        rootGroup.addWidget(createButton(leftX, startY, BUTTON_WIDTH, BUTTON_HEIGHT,
                "gui.city_management.city_info",
                true,
                clickData -> openCityInfo(cityCorePos)));

        rootGroup.addWidget(createButton(leftX, startY + (BUTTON_HEIGHT + BUTTON_SPACING), BUTTON_WIDTH, BUTTON_HEIGHT,
                "gui.city_management.edit_info",
                true,
                clickData -> openCityEdit(cityCorePos)));

        rootGroup.addWidget(createButton(leftX, startY + 2 * (BUTTON_HEIGHT + BUTTON_SPACING), BUTTON_WIDTH, BUTTON_HEIGHT,
                "gui.city_management.upgrade",
                false,
                clickData -> openCityUpgrade()));

        rootGroup.addWidget(createButton(leftX, startY + 3 * (BUTTON_HEIGHT + BUTTON_SPACING), BUTTON_WIDTH, BUTTON_HEIGHT,
                "gui.city_management.citizens",
                true,
                clickData -> openCityCitizens(cityCorePos)));

        rootGroup.addWidget(createButton(leftX, startY + 4 * (BUTTON_HEIGHT + BUTTON_SPACING), BUTTON_WIDTH, BUTTON_HEIGHT,
                "gui.city_management.officials",
                true,
                clickData -> openCityOfficials(cityCorePos)));

        rootGroup.addWidget(createButton(leftX, startY + 5 * (BUTTON_HEIGHT + BUTTON_SPACING), BUTTON_WIDTH, BUTTON_HEIGHT,
                "gui.city_management.finance",
                true,
                clickData -> openCityFinance(cityCorePos)));

        rootGroup.addWidget(createButton(leftX, screenHeight - 30, BACK_BUTTON_WIDTH, BACK_BUTTON_HEIGHT,
                "gui.city_management.back",
                true,
                clickData -> closeScreen()));

        int canvasX = leftX + BUTTON_WIDTH + BUTTON_SPACING * 2;
        int canvasY = 20;
        int canvasWidth = screenWidth - canvasX - 20;
        int canvasHeight = screenHeight - 40;

        rootGroup.addWidget(new CityMapCanvasAdapterWidget(canvasX, canvasY, canvasWidth, canvasHeight, cityCorePos));

        modularUI.widget(rootGroup);
        modularUI.initWidgets();
        return modularUI;
    }

    @Override
    public void removed() {
        releaseMapConsumers();
        super.removed();
    }

    @Override
    public void containerTick() {
        super.containerTick();
        if (SimuMapManager.isAvailable()) {
            SimuMapManager.getInstance().tick();
        }
    }

    private static ButtonWidget createButton(int x, int y, int width, int height, String textKey, boolean active, java.util.function.Consumer<ClickData> onPress) {
        ButtonWidget button = new ButtonWidget();
        button.setSelfPosition(x, y);
        button.setSize(width, height);

        String textureLocation = getButtonTexture(active, width, height);
        var buttonBackground = new ResourceTexture(textureLocation);
        var buttonHover = new GuiTextureGroup(
                new ResourceTexture(textureLocation),
                new ColorBorderTexture(1, HOVER_BORDER_COLOR)
        );
        var buttonText = new TextTexture(textKey);

        button.setButtonTexture(buttonBackground, buttonText);
        button.setHoverTexture(buttonHover, buttonText);
        if (active) {
            button.setOnPressCallback(onPress);
        }
        return button;
    }

    private static String getButtonTexture(boolean active, int width, int height) {
        boolean useShortTexture = width <= SHORT_BUTTON_WIDTH_THRESHOLD && height <= SHORT_BUTTON_HEIGHT_THRESHOLD;
        if (useShortTexture) {
            return active ? BUTTON_TEXTURE : LOCK_BUTTON_TEXTURE;
        }
        return active ? LONG_BUTTON_TEXTURE : LONG_LOCK_BUTTON_TEXTURE;
    }

    private static void openCityInfo(BlockPos cityCorePos) {
        GetCityInfoPacket packet = new GetCityInfoPacket(cityCorePos);
        NetworkManager.INSTANCE.sendToServer(packet);
    }

    private static void openCityEdit(BlockPos cityCorePos) {
        Minecraft.getInstance().setScreen(new CityEditScreen(cityCorePos));
    }

    private static void openCityUpgrade() {
    }

    private static void openCityCitizens(BlockPos cityCorePos) {
        Minecraft.getInstance().setScreen(new CityCitizenScreen(cityCorePos));
    }

    private static void openCityOfficials(BlockPos cityCorePos) {
        Minecraft.getInstance().setScreen(new CityOfficialScreen(cityCorePos));
    }

    private static void openCityFinance(BlockPos cityCorePos) {
        Minecraft.getInstance().setScreen(new CityFinanceScreen(cityCorePos));
    }

    @Override
    public void render(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        guiGraphics.fillGradient(0, 0, this.width, this.height, 0xC8000000, 0xC8000000);
        super.render(guiGraphics, mouseX, mouseY, partialTicks);
    }

    @Override
    public void onClose() {
        closeScreen();
    }

    private static void closeScreen() {
        Minecraft.getInstance().setScreen(null);
    }

    private void releaseMapConsumers() {
        for (Widget widget : ((ModularUI)this.modularUI).mainGroup.widgets) {
            releaseMapConsumers(widget);
        }
    }

    private void releaseMapConsumers(Widget widget) {
        if (widget instanceof CityMapCanvasAdapterWidget mapWidget) {
            mapWidget.releaseMapConsumer();
        }
        if (widget instanceof WidgetGroup widgetGroup) {
            for (Widget child : widgetGroup.widgets) {
                releaseMapConsumers(child);
            }
        }
    }

    @Nonnull
    private static <T> T nn(T value) {
        return Objects.requireNonNull(value);
    }

    private static class CityManagementUIHolder implements IUIHolder {
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

    private static class CityMapCanvasAdapterWidget extends Widget {
        private final CityMapCanvas mapCanvas;
        private boolean dragForwarding;

        public CityMapCanvasAdapterWidget(int x, int y, int width, int height, BlockPos cityCorePos) {
            super(x, y, width, height);
            this.mapCanvas = new CityMapCanvas(x, y, width, height, cityCorePos);
            setClientSideWidget();
        }

        @Override
        public void drawInBackground(@Nonnull GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            mapCanvas.renderWidget(graphics, mouseX, mouseY, partialTicks);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (!isMouseOverElement(mouseX, mouseY)) {
                dragForwarding = false;
                return false;
            }
            boolean handled = mapCanvas.mouseClicked(mouseX, mouseY, button);
            dragForwarding = handled && button == 0;
            return handled;
        }

        @Override
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            if (!dragForwarding && !isMouseOverElement(mouseX, mouseY)) {
                return false;
            }
            boolean handled = mapCanvas.mouseReleased(mouseX, mouseY, button);
            if (button == 0) {
                dragForwarding = false;
            }
            return handled;
        }

        @Override
        public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
            if (!dragForwarding && !isMouseOverElement(mouseX, mouseY)) {
                return false;
            }
            return mapCanvas.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        }

        @Override
        public boolean mouseWheelMove(double mouseX, double mouseY, double wheelDelta) {
            if (!isMouseOverElement(mouseX, mouseY)) {
                return false;
            }
            return mapCanvas.mouseScrolled(mouseX, mouseY, wheelDelta);
        }

        public void releaseMapConsumer() {
            mapCanvas.releaseMapConsumer();
        }
    }
}
