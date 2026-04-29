package com.xiaoliang.simukraft.client.gui;

import com.xiaoliang.simukraft.farmland.CropDefinition;
import com.xiaoliang.simukraft.farmland.CropRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;

public class SelectCropScreen extends Screen {
    private static final int PAGE_SIZE = 8;
    private final BlockPos farmlandBoxPos;
    private List<CropDefinition> crops = List.of();
    private int page = 0;
    private Button previousButton;
    private Button nextButton;

    public SelectCropScreen(BlockPos pos) {
        super(Component.translatable("gui.select_crop.title"));
        this.farmlandBoxPos = pos;
    }

    @Nonnull
    private static <T> T nn(@Nullable T value) {
        return Objects.requireNonNull(value);
    }

    @Override
    protected void init() {
        super.init();
        crops = CropRegistry.getSelectableCrops();

        this.addRenderableWidget(nn(Button.builder(
                        nn(Component.translatable("gui.button.back")),
                        button -> this.onClose())
                .bounds(5, 5, 45, 20)
                .build()));

        int totalPages = Math.max(1, (int) Math.ceil(crops.size() / (double) PAGE_SIZE));
        page = Math.max(0, Math.min(page, totalPages - 1));
        int start = page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, crops.size());
        int centerX = this.width / 2;
        int startY = Math.max(70, this.height / 2 - 80);

        for (int i = start; i < end; i++) {
            CropDefinition crop = crops.get(i);
            int index = i - start;
            int column = index % 2;
            int row = index / 2;
            this.addRenderableWidget(nn(Button.builder(
                            cropButtonText(crop),
                            button -> selectCrop(crop))
                    .bounds(centerX - 125 + column * 130, startY + row * 28, 120, 20)
                    .build()));
        }

        previousButton = this.addRenderableWidget(nn(Button.builder(
                        nn(Component.translatable("gui.pagination.previous")),
                        button -> changePage(-1))
                .bounds(centerX - 100, this.height - 35, 80, 20)
                .build()));
        nextButton = this.addRenderableWidget(nn(Button.builder(
                        nn(Component.translatable("gui.pagination.next")),
                        button -> changePage(1))
                .bounds(centerX + 20, this.height - 35, 80, 20)
                .build()));

        previousButton.active = page > 0;
        nextButton.active = page < totalPages - 1;
    }

    private Component cropButtonText(CropDefinition crop) {
        String selectedCrop = CropRegistry.normalizeSelectionId(FarmlandData.getSelectedCrop(farmlandBoxPos));
        Component name = crop.displayName();
        if (crop.selectionId().equals(selectedCrop)) {
            return Component.literal("✓ ").append(name).withStyle(style -> style.withColor(0x55FF55));
        }
        return name.copy().withStyle(style -> style.withColor(0xFFFFFF));
    }

    private void changePage(int delta) {
        page += delta;
        rebuildWidgets();
    }

    private void selectCrop(CropDefinition crop) {
        FarmlandData.setSelectedCrop(farmlandBoxPos, crop.selectionId());

        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(
                    nn(Component.translatable("message.simukraft.crop.selected", crop.displayName().getString())
                            .withStyle(style -> style.withColor(0x55FF55))),
                    false
            );
        }

        this.onClose();
    }

    @Override
    public void render(@Nonnull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        guiGraphics.fillGradient(0, 0, this.width, this.height, 0xC8000000, 0xC8000000);

        int titleColor = 0xFFFFFF;
        Component title = Component.translatable("gui.select_crop.title").withStyle(style -> style.withColor(titleColor));
        guiGraphics.drawCenteredString(nn(this.font), nn(title), this.width / 2, 30, titleColor);

        int textColor = 0xFFF5F5A0;
        Component hint = Component.translatable("gui.select_crop.hint").withStyle(style -> style.withColor(textColor));
        guiGraphics.drawCenteredString(nn(this.font), nn(hint), this.width / 2, 50, textColor);

        int totalPages = Math.max(1, (int) Math.ceil(crops.size() / (double) PAGE_SIZE));
        Component pageText = Component.literal((page + 1) + " / " + totalPages).withStyle(style -> style.withColor(0xAAAAAA));
        guiGraphics.drawCenteredString(nn(this.font), pageText, this.width / 2, this.height - 50, 0xAAAAAA);

        super.render(guiGraphics, mouseX, mouseY, partialTicks);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(new FarmlandBoxScreen(farmlandBoxPos));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
