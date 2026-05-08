package com.xiaoliang.simukraft.client.gui;

import com.lowdragmc.lowdraglib.gui.modular.IUIHolder;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.modular.ModularUIGuiContainer;
import com.lowdragmc.lowdraglib.gui.texture.ColorBorderTexture;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.ResourceTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.utils.Size;
import com.mojang.blaze3d.systems.RenderSystem;
import com.xiaoliang.simukraft.client.CityNameCache;
import com.xiaoliang.simukraft.client.NPCFamilyInfoCache;
import com.xiaoliang.simukraft.client.NPCResidenceCache;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.entity.Gender;
import com.xiaoliang.simukraft.network.GetCityNamePacket;
import com.xiaoliang.simukraft.network.NetworkManager;
import com.xiaoliang.simukraft.network.RequestNPCFamilyInfoPacket;
import com.xiaoliang.simukraft.network.RequestNPCResidencePacket;
import com.xiaoliang.simukraft.utils.SkinManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@OnlyIn(Dist.CLIENT)
public class NPCCardScreen extends ModularUIGuiContainer {

    private final CustomEntity npc;

    private static final int TAB_W = 136;
    private static final int TAB_H = 80;
    private static final int TAB_OFFSET = 45;
    private static final int TAB_HOVER_SHIFT = 8;

    private static final int CARD_W = 280;
    private static final int CARD_H = 170;
    private static final int HEAD_SIZE = 36;

    private static final int CLOTH_W = 350;
    private static final int CLOTH_H = 310;

    private static final float ANIM_SPEED = 0.07f;

    private static final int CLOTH_BORDER = 0x00000000; // 透明边框

    private static final int CARD_PAPER = 0xFFF5F0E1;
    private static final int CARD_HEADER = 0xFF6D4C41;
    private static final int CARD_BORDER_OUTER = 0xFFD4C5A9;
    private static final int CARD_BORDER_INNER = 0xFFBEB09A;

    private static final int TAB_PAPER = 0xFFEDE8D9;
    private static final int TAB_HOVER = 0xFFF8F4EC;
    private static final int TAB_BORDER = 0xFFB0A48E;

    private static final int TEXT_DARK = 0xFF3E2723;
    private static final int TEXT_LABEL = 0xFF8D6E63;
    private static final int TEXT_VALUE = 0xFF33291E;
    private static final int ACCENT_GOLD = 0xFFC8A260;

    private int selectedCardIndex = -1;
    private float animTimer = 0f;
    private boolean animClosing = false;

    private String cityName = null;
    private String residenceText = null;
    private boolean familyInfoRequested = false;

    private final List<CardDef> cards = new ArrayList<>();

    @Nonnull
    private static <T> T nn(@Nullable T value) {
        return Objects.requireNonNull(value);
    }

    @Nonnull
    private static String safeString(@Nullable String value) {
        return nn(value);
    }

    public NPCCardScreen(CustomEntity npc) {
        super(createModularUI(), 0);
        this.npc = npc;
        initCards();
        loadAsyncData();
    }

    private static ModularUI createModularUI() {
        int screenW = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int screenH = Minecraft.getInstance().getWindow().getGuiScaledHeight();

        Player player = Minecraft.getInstance().player;
        CardUIHolder holder = new CardUIHolder();
        ModularUI modularUI = new ModularUI(new Size(screenW, screenH), holder, player);

        WidgetGroup rootGroup = new WidgetGroup();
        rootGroup.setSize(screenW, screenH);

        int clothX = (screenW - CLOTH_W) / 2;
        int clothY = (screenH - CLOTH_H) / 2;

        WidgetGroup clothPanel = new WidgetGroup();
        clothPanel.setSelfPosition(clothX, clothY);
        clothPanel.setSize(CLOTH_W, CLOTH_H);
        clothPanel.setBackground(new GuiTextureGroup(
                new ResourceTexture("simukraft:textures/gui/npc_background.png"),
                new ColorBorderTexture(2, CLOTH_BORDER)
        ));

        TextTexture titleTexture = new TextTexture("gui.npc_interaction.title");
        titleTexture.setWidth(CLOTH_W);
        titleTexture.setDropShadow(true);
        ImageWidget titleWidget = new ImageWidget(0, 6, CLOTH_W, 16, titleTexture);
        clothPanel.addWidget(titleWidget);

        rootGroup.addWidget(clothPanel);
        modularUI.widget(rootGroup);
        modularUI.initWidgets();
        return modularUI;
    }

    private void initCards() {
        cards.add(new CardDef(Component.translatable("gui.npc_card.identity_card").getString()));
        cards.add(new CardDef(Component.translatable("gui.npc_card.work_card").getString()));
        cards.add(new CardDef(Component.translatable("gui.npc_card.residence_card").getString()));
    }

    // --- async data (unchanged) ---

    private void loadAsyncData() {
        String cityIdStr = npc.getCityIdString();
        if (cityIdStr != null && !cityIdStr.isEmpty()) {
            try {
                UUID cityId = UUID.fromString(cityIdStr);
                String cached = CityNameCache.get(cityId);
                if (cached != null) {
                    cityName = cached;
                } else {
                    NetworkManager.INSTANCE.sendToServer(new GetCityNamePacket(cityId));
                    Minecraft.getInstance().tell(() -> new Thread(() -> {
                        try {
                            Thread.sleep(500);
                            Minecraft.getInstance().tell(() -> {
                                String n = CityNameCache.get(cityId);
                                cityName = n != null ? n : Component.translatable("gui.city.unknown").getString();
                            });
                        } catch (InterruptedException ignored) {
                            cityName = Component.translatable("gui.city.unknown").getString();
                        }
                    }).start());
                }
            } catch (IllegalArgumentException e) {
                cityName = Component.translatable("gui.city.none").getString();
            }
        } else {
            cityName = Component.translatable("gui.city.none").getString();
        }

        String npcNameStr = npc.getFullName();
        NPCResidenceCache.ResidenceInfo cachedInfo = NPCResidenceCache.getResidenceInfo(npcNameStr);
        if (cachedInfo != null) {
            residenceText = formatResidence(cachedInfo);
        } else {
            NetworkManager.INSTANCE.sendToServer(new RequestNPCResidencePacket(npcNameStr));
            Minecraft.getInstance().tell(() -> new Thread(() -> {
                try {
                    Thread.sleep(600);
                    Minecraft.getInstance().tell(() -> {
                        NPCResidenceCache.ResidenceInfo info = NPCResidenceCache.getResidenceInfo(npcNameStr);
                        residenceText = info != null
                                ? formatResidence(info)
                                : Component.translatable("gui.npc_interaction.no_residence").getString();
                    });
                } catch (InterruptedException ignored) {}
            }).start());
        }
    }

    private String formatResidence(NPCResidenceCache.ResidenceInfo info) {
        if (info.hasResidence) {
            if (info.position != null && !info.position.isEmpty()) {
                return Component.translatable("gui.npc_interaction.has_residence", info.position).getString();
            }
            return Component.translatable("gui.npc_interaction.has_residence_unknown").getString();
        }
        return Component.translatable("gui.npc_interaction.no_residence").getString();
    }

    private String getCityDisplay() {
        return cityName != null ? cityName : Component.translatable("gui.city.loading").getString();
    }

    private String getResidenceDisplay() {
        return residenceText != null ? residenceText : Component.translatable("gui.npc_interaction.loading").getString();
    }

    private String getGenderText() {
        Gender g = npc.getGender();
        return Component.translatable("gui.npc.gender." + (g != null ? g.name().toLowerCase() : "unknown")).getString();
    }

    private String getJobText() {
        return JobDisplayNameResolver.resolve(npc.getJob(), npc.getUUID());
    }

    private String getWorkStatusText() {
        NPCFamilyInfoCache.FamilyInfo familyInfo = getFamilyInfo();
        if (familyInfo != null) {
            if ("pregnant".equalsIgnoreCase(familyInfo.pregnancyStage())) {
                return Component.translatable("gui.npc.status.pregnant_home").getString();
            }
            if ("labor".equalsIgnoreCase(familyInfo.pregnancyStage())) {
                return Component.translatable("gui.npc.status.in_labor").getString();
            }
        }
        return safeString(npc.getStatusDisplayComponent().getString());
    }

    private String getMarriageText() {
        NPCFamilyInfoCache.FamilyInfo familyInfo = getFamilyInfo();
        if (familyInfo == null) {
            return Component.translatable("gui.npc_interaction.loading").getString();
        }
        if (familyInfo.spouseName() == null || familyInfo.spouseName().isBlank()) {
            return safeString(Component.translatable("gui.npc_marriage.single").getString());
        }
        return Component.translatable("gui.npc_marriage.married_format", familyInfo.spouseName()).getString();
    }

    private String getHungerText() {
        return safeString(Component.translatable(safeString(npc.getHungerLevelKey())).getString());
    }

    private float easeOut(float t) {
        return 1f - (1f - t) * (1f - t);
    }

    @Nullable
    private NPCFamilyInfoCache.FamilyInfo getFamilyInfo() {
        NPCFamilyInfoCache.FamilyInfo cachedInfo = NPCFamilyInfoCache.get(npc.getUUID());
        if (cachedInfo == null && !familyInfoRequested) {
            familyInfoRequested = true;
            NetworkManager.INSTANCE.sendToServer(new RequestNPCFamilyInfoPacket(npc.getUUID()));
        }
        return cachedInfo;
    }

    // --- render ---

    @Override
    public void render(@Nonnull GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fillGradient(0, 0, this.width, this.height, 0xC0101010, 0xD0101010);

        super.render(g, mouseX, mouseY, partialTick);

        if (selectedCardIndex >= 0 || animClosing) {
            if (animClosing) {
                animTimer = Math.max(0f, animTimer - ANIM_SPEED);
                if (animTimer <= 0f) {
                    animClosing = false;
                    selectedCardIndex = -1;
                    animTimer = 0f;
                }
            } else {
                animTimer = Math.min(1f, animTimer + ANIM_SPEED);
            }
        }

        float progress = easeOut(animTimer);

        int clothX = (this.width - CLOTH_W) / 2;
        int clothY = (this.height - CLOTH_H) / 2;

        renderTabs(g, mouseX, mouseY, clothX, clothY);

        if ((selectedCardIndex >= 0 || animClosing) && animTimer > 0f) {
            renderCard(g, progress, clothX, clothY);
        }

        if (selectedCardIndex < 0 && !animClosing) {
            renderTabTooltip(g, mouseX, mouseY, clothX, clothY);
        }
    }

    // --- tab positioning relative to tablecloth ---

    private int getStackTotalW() {
        return (cards.size() - 1) * TAB_OFFSET + TAB_W;
    }

    private int getTabsStartX(int clothX) {
        return clothX + (CLOTH_W - getStackTotalW()) / 2;
    }

    private int getTabsStartY(int clothY) {
        return clothY + 24;
    }

    private int getHoveredTabIndex(int mouseX, int mouseY, int clothX, int clothY) {
        int startX = getTabsStartX(clothX);
        int startY = getTabsStartY(clothY);
        for (int i = cards.size() - 1; i >= 0; i--) {
            if (i == selectedCardIndex && !animClosing) continue;
            int tx = startX + i * TAB_OFFSET;
            if (mouseX >= tx && mouseX < tx + TAB_W && mouseY >= startY && mouseY < startY + TAB_H) {
                return i;
            }
        }
        return -1;
    }

    // --- tabs ---

    private void renderTabs(GuiGraphics g, int mouseX, int mouseY, int clothX, int clothY) {
        int startX = getTabsStartX(clothX);
        int startY = getTabsStartY(clothY);
        int hoveredIdx = (selectedCardIndex < 0 || animClosing)
                ? getHoveredTabIndex(mouseX, mouseY, clothX, clothY)
                : getHoveredTabIndex(mouseX, mouseY, clothX, clothY);

        for (int i = 0; i < cards.size(); i++) {
            if (i == selectedCardIndex && !animClosing) continue;

            CardDef card = cards.get(i);
            int tx = startX + i * TAB_OFFSET;
            int ty = startY;

            boolean hovered = (hoveredIdx == i);
            int yShift = hovered ? TAB_HOVER_SHIFT : 0;

            int bg = hovered ? TAB_HOVER : TAB_PAPER;

            // shadow
            g.fill(tx + 2, ty + yShift + 2, tx + TAB_W + 2, ty + TAB_H + yShift + 2, 0x30000000);

            // card body
            g.fill(tx, ty + yShift, tx + TAB_W, ty + TAB_H + yShift, bg);
            drawRect(g, tx, ty + yShift, TAB_W, TAB_H, TAB_BORDER);

            // top accent stripe
            g.fill(tx + 1, ty + yShift + 1, tx + TAB_W - 1, ty + yShift + 3, ACCENT_GOLD);

            // vertical text
            String title = card.title;
            int visibleW = (i < cards.size() - 1) ? TAB_OFFSET : TAB_W;
            int textX = tx + 8;
            int totalTextH = title.length() * 12;
            int charY = ty + yShift + (TAB_H - totalTextH) / 2 + 4;
            for (int ci = 0; ci < title.length(); ci++) {
                String ch = safeString(String.valueOf(title.charAt(ci)));
                g.drawString(nn(this.font), ch, textX, charY, TEXT_DARK, false);
                charY += 12;
            }

            // bottom decorative line
            g.fill(tx + 6, ty + TAB_H + yShift - 3, tx + visibleW - 6, ty + TAB_H + yShift - 2, CARD_BORDER_INNER);
        }
    }

    private void renderTabTooltip(GuiGraphics g, int mouseX, int mouseY, int clothX, int clothY) {
        int hoveredIdx = getHoveredTabIndex(mouseX, mouseY, clothX, clothY);
        if (hoveredIdx >= 0) {
            List<Component> lines = getTooltipLines(hoveredIdx);
            g.renderTooltip(nn(this.font), nn(lines), nn(java.util.Optional.empty()), mouseX, mouseY);
        }
    }

    private List<Component> getTooltipLines(int index) {
        List<Component> lines = new ArrayList<>();
        switch (index) {
            case 0 -> {
                lines.add(Component.literal("\u00a76" + cards.get(0).title));
                lines.add(Component.literal("\u00a77" + Component.translatable("gui.npc_interaction.name").getString() + "\u00a7f" + npc.getFullName()));
                lines.add(Component.literal("\u00a77" + Component.translatable("gui.npc_interaction.gender").getString() + "\u00a7f" + getGenderText()));
                lines.add(Component.literal("\u00a77" + Component.translatable("gui.npc_interaction.age").getString() + "\u00a7f" + npc.getNpcAge() + Component.translatable("gui.npc_interaction.age_unit").getString()));
                lines.add(Component.literal("\u00a77" + Component.translatable("gui.npc_interaction.hunger").getString() + "\u00a7f" + getHungerText()));
            }
            case 1 -> {
                lines.add(Component.literal("\u00a76" + cards.get(1).title));
                lines.add(Component.literal("\u00a77" + Component.translatable("gui.npc_interaction.work_status").getString() + "\u00a7f" + getWorkStatusText()));
                lines.add(Component.literal("\u00a77" + Component.translatable("gui.npc_interaction.job").getString() + "\u00a7f" + getJobText()));
                lines.add(Component.literal("\u00a77" + Component.translatable("gui.npc_interaction.residence").getString() + "\u00a7f" + getResidenceDisplay()));
                lines.add(Component.literal("\u00a77" + Component.translatable("gui.npc_interaction.marriage").getString() + "\u00a7f" + getMarriageText()));
            }
            case 2 -> {
                lines.add(Component.literal("\u00a76" + cards.get(2).title));
                lines.add(Component.literal("\u00a77" + Component.translatable("gui.npc_interaction.residence").getString() + "\u00a7f" + getResidenceDisplay()));
            }
        }
        return lines;
    }

    // --- expanded card ---

    private void renderCard(GuiGraphics g, float progress, int clothX, int clothY) {
        int activeIdx = selectedCardIndex >= 0 ? selectedCardIndex : -1;
        if (activeIdx < 0) return;

        CardDef card = cards.get(activeIdx);

        int cardX = clothX + (CLOTH_W - CARD_W) / 2;
        int tabBottom = getTabsStartY(clothY) + TAB_H;
        int targetY = tabBottom + 8;
        int cardY = (int) (targetY - CARD_H * (1f - progress));

        int alpha = (int) (255 * progress);
        if (alpha <= 0) return;

        // shadow
        int shadowA = (int) (0x40 * progress);
        g.fill(cardX + 3, cardY + 3, cardX + CARD_W + 3, cardY + CARD_H + 3, (shadowA << 24));

        // card paper background
        g.fill(cardX, cardY, cardX + CARD_W, cardY + CARD_H, blendAlpha(CARD_PAPER, alpha));

        // outer + inner border
        drawRect(g, cardX, cardY, CARD_W, CARD_H, blendAlpha(CARD_BORDER_OUTER, alpha));
        drawRect(g, cardX + 1, cardY + 1, CARD_W - 2, CARD_H - 2, blendAlpha(CARD_BORDER_INNER, alpha));

        // header bar
        g.fill(cardX + 2, cardY + 2, cardX + CARD_W - 2, cardY + 24, blendAlpha(CARD_HEADER, alpha));

        // title text in header
        String title = card.title;
        int titleW = nn(this.font).width(safeString(title));
        g.drawString(nn(this.font), safeString(title), cardX + (CARD_W - titleW) / 2, cardY + 8, blendAlpha(0xFFFFFFFF, alpha), true);

        // gold accent below header
        g.fill(cardX + 2, cardY + 24, cardX + CARD_W - 2, cardY + 26, blendAlpha(ACCENT_GOLD, alpha));

        // head portrait with frame
        int headX = cardX + 16;
        int headY = cardY + 34;
        renderNpcHead(g, headX, headY, HEAD_SIZE, progress);

        // info rows
        int infoX = headX + HEAD_SIZE + 14;
        int infoY = cardY + 36;
        int lineH = 15;

        List<String[]> rows = getCardContent(activeIdx);
        for (String[] row : rows) {
            String keyText = safeString(row[0]);
            String valueText = safeString(row[1]);
            g.drawString(nn(this.font), keyText, infoX, infoY, blendAlpha(TEXT_LABEL, alpha), false);
            g.drawString(nn(this.font), valueText, infoX + nn(this.font).width(keyText) + 3, infoY, blendAlpha(TEXT_VALUE, alpha), false);
            infoY += lineH;
        }

        // bottom accent line
        g.fill(cardX + 12, cardY + CARD_H - 10, cardX + CARD_W - 12, cardY + CARD_H - 9, blendAlpha(ACCENT_GOLD, (int)(alpha * 0.5f)));
    }

    private void renderNpcHead(GuiGraphics g, int x, int y, int size, float progress) {
        int alpha = (int) (255 * progress);

        // photo frame: gold outer, dark inner
        g.fill(x - 2, y - 2, x + size + 2, y + size + 2, blendAlpha(ACCENT_GOLD, alpha));
        g.fill(x - 1, y - 1, x + size + 1, y + size + 1, blendAlpha(CARD_HEADER, alpha));
        g.fill(x, y, x + size, y + size, blendAlpha(0xFFDDD5C5, alpha));

        String skinPath = npc.getSkinPath();
        if (skinPath == null || skinPath.isEmpty() || !SkinManager.isValidSkinPath(skinPath)) {
            skinPath = SkinManager.getDefaultSkinPath(npc.getGender());
        }

        try {
            ResourceLocation texture = SkinManager.getTextureResourceLocation(skinPath);
            if (texture != null) {
                RenderSystem.setShaderTexture(0, texture);
                RenderSystem.enableBlend();
                RenderSystem.setShaderColor(1f, 1f, 1f, progress);

                g.blit(texture, x, y, size, size, 8, 8, 8, 8, 64, 64);
                g.blit(texture, x, y, size, size, 40, 8, 8, 8, 64, 64);

                RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
                RenderSystem.disableBlend();
            }
        } catch (Exception e) {
            g.fill(x, y, x + size, y + size, blendAlpha(0xFF999999, alpha));
        }
    }

    private List<String[]> getCardContent(int index) {
        List<String[]> rows = new ArrayList<>();
        switch (index) {
            case 0 -> {
                rows.add(new String[]{Component.translatable("gui.npc_interaction.name").getString(), npc.getFullName()});
                rows.add(new String[]{Component.translatable("gui.npc_interaction.gender").getString(), getGenderText()});
                rows.add(new String[]{Component.translatable("gui.npc_interaction.age").getString(),
                        npc.getNpcAge() + Component.translatable("gui.npc_interaction.age_unit").getString()});
                rows.add(new String[]{Component.translatable("gui.npc_interaction.hunger").getString(), getHungerText()});
                rows.add(new String[]{Component.translatable("gui.npc_interaction.city").getString(), getCityDisplay()});
            }
            case 1 -> {
                rows.add(new String[]{Component.translatable("gui.npc_interaction.work_status").getString(), getWorkStatusText()});
                rows.add(new String[]{Component.translatable("gui.npc_interaction.job").getString(), getJobText()});
                rows.add(new String[]{Component.translatable("gui.npc_interaction.residence").getString(), getResidenceDisplay()});
                rows.add(new String[]{Component.translatable("gui.npc_interaction.marriage").getString(), getMarriageText()});
            }
            case 2 -> {
                rows.add(new String[]{Component.translatable("gui.npc_interaction.residence").getString(), getResidenceDisplay()});
            }
        }
        return rows;
    }

    // --- drawing helpers ---

    private int blendAlpha(int color, int alpha) {
        int r = (color >> 16) & 0xFF;
        int gr = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        return (alpha << 24) | (r << 16) | (gr << 8) | b;
    }

    private void drawRect(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    // --- input ---

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);

        int clothX = (this.width - CLOTH_W) / 2;
        int clothY = (this.height - CLOTH_H) / 2;

        if (selectedCardIndex >= 0 && !animClosing) {
            int clickedTab = getHoveredTabIndex((int) mouseX, (int) mouseY, clothX, clothY);
            if (clickedTab >= 0 && clickedTab != selectedCardIndex) {
                selectedCardIndex = clickedTab;
                animTimer = 0f;
                return true;
            }
            int cardX = clothX + (CLOTH_W - CARD_W) / 2;
            int tabBottom = getTabsStartY(clothY) + TAB_H;
            int targetY = tabBottom + 8;
            if (mouseX < cardX || mouseX > cardX + CARD_W || mouseY < targetY || mouseY > targetY + CARD_H) {
                animClosing = true;
                return true;
            }
            return true;
        }

        if (selectedCardIndex < 0 && !animClosing) {
            int hoveredIdx = getHoveredTabIndex((int) mouseX, (int) mouseY, clothX, clothY);
            if (hoveredIdx >= 0) {
                selectedCardIndex = hoveredIdx;
                animTimer = 0f;
                animClosing = false;
                return true;
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) {
            if (selectedCardIndex >= 0 && !animClosing) {
                animClosing = true;
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // --- data class ---

    private static class CardDef {
        final String title;
        CardDef(String title) {
            this.title = title;
        }
    }

    private static class CardUIHolder implements IUIHolder {
        @Override public ModularUI createUI(Player entityPlayer) { return null; }
        @Override public boolean isInvalid() { return false; }
        @Override public boolean isRemote() { return true; }
        @Override public void markAsDirty() {}
    }
}
