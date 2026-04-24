package com.xiaoliang.simukraft.client.gui.guidebook;

import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.nbt.TagParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 指南书页面数据类
 * 支持多页、物品引用、图片等丰富内容
 */
@SuppressWarnings("null")
public class GuideBookPage {

    private final String id;
    private final String parentId;
    private final String tabIcon;
    private final int tabIndex;
    private final List<BookPage> pages; // 支持多页

    public GuideBookPage(String id, List<BookPage> pages,
                         @Nullable String parentId, @Nullable String tabIcon, int tabIndex) {
        this.id = id;
        this.pages = pages;
        this.parentId = parentId;
        this.tabIcon = tabIcon;
        this.tabIndex = tabIndex;
    }

    public String getId() {
        return id;
    }

    @Nullable
    public String getParentId() {
        return parentId;
    }

    @Nullable
    public String getTabIcon() {
        return tabIcon;
    }

    public int getTabIndex() {
        return tabIndex;
    }

    public boolean isDirectory() {
        return parentId == null;
    }

    public List<BookPage> getPages() {
        return pages;
    }

    public int getTotalPages() {
        return pages.size();
    }

    public BookPage getPage(int index) {
        if (index < 0 || index >= pages.size()) {
            return pages.isEmpty() ? BookPage.EMPTY : pages.get(0);
        }
        return pages.get(index);
    }

    /**
     * 从 JSON 解析页面
     */
    public static GuideBookPage fromJson(String id, JsonObject json) {
        String parentId = json.has("parent") ? GsonHelper.getAsString(json, "parent") : null;
        String tabIcon = json.has("tab_icon") ? GsonHelper.getAsString(json, "tab_icon") : null;
        int tabIndex = GsonHelper.getAsInt(json, "tab_index", -1);

        List<BookPage> pages = new ArrayList<>();

        // 解析多页配置
        if (json.has("pages") && json.get("pages").isJsonArray()) {
            var pagesArray = json.getAsJsonArray("pages");
            for (int i = 0; i < pagesArray.size(); i++) {
                JsonObject pageObj = pagesArray.get(i).getAsJsonObject();
                pages.add(BookPage.fromJson(pageObj));
            }
        } else {
            // 兼容旧版单页配置
            pages.add(BookPage.fromJson(json));
        }

        if (pages.isEmpty()) {
            pages.add(BookPage.EMPTY);
        }

        return new GuideBookPage(id, pages, parentId, tabIcon, tabIndex);
    }

    /**
     * 单页数据结构（包含左右两页）
     */
    public static class BookPage {
        public static final BookPage EMPTY = new BookPage("", "", List.of(), List.of());

        private final String leftTitleKey;
        private final String rightTitleKey;
        private final List<PageElement> leftElements;
        private final List<PageElement> rightElements;

        public BookPage(String leftTitleKey, String rightTitleKey,
                        List<PageElement> leftElements, List<PageElement> rightElements) {
            this.leftTitleKey = leftTitleKey;
            this.rightTitleKey = rightTitleKey;
            this.leftElements = leftElements;
            this.rightElements = rightElements;
        }

        public Component getLeftTitle() {
            return translateTitle(leftTitleKey);
        }

        public Component getRightTitle() {
            return translateTitle(rightTitleKey);
        }

        private Component translateTitle(String key) {
            // 优先使用指南书独立语言系统
            String translated = GuideBookLang.get(key);
            if (!translated.equals(key)) {
                return Component.literal(translated);
            }
            // 回退到 Minecraft 语言系统
            return Component.translatable(key);
        }

        public List<PageElement> getLeftElements() {
            return leftElements;
        }

        public List<PageElement> getRightElements() {
            return rightElements;
        }

        public static BookPage fromJson(JsonObject json) {
            String leftTitleKey = GsonHelper.getAsString(json, "left_title", "gui.guide_book.left_title");
            String rightTitleKey = GsonHelper.getAsString(json, "right_title", "gui.guide_book.right_title");

            List<PageElement> leftElements = parseElements(GsonHelper.getAsJsonArray(json, "left_content", null));
            List<PageElement> rightElements = parseElements(GsonHelper.getAsJsonArray(json, "right_content", null));

            return new BookPage(leftTitleKey, rightTitleKey, leftElements, rightElements);
        }

        private static List<PageElement> parseElements(@Nullable com.google.gson.JsonArray array) {
            List<PageElement> elements = new ArrayList<>();
            if (array == null) return elements;

            for (int i = 0; i < array.size(); i++) {
                JsonObject obj = array.get(i).getAsJsonObject();
                String type = GsonHelper.getAsString(obj, "type", "text");
                String content = GsonHelper.getAsString(obj, "content", "");
                int spacing = GsonHelper.getAsInt(obj, "spacing", 4);
                String itemId = obj.has("item_id") ? GsonHelper.getAsString(obj, "item_id") : null;
                String itemNbt = obj.has("item_nbt") ? GsonHelper.getAsString(obj, "item_nbt") : null;
                String target = obj.has("target") ? GsonHelper.getAsString(obj, "target") : null;
                String imageUrl = obj.has("image_url") ? GsonHelper.getAsString(obj, "image_url") : null;
                int imageWidth = GsonHelper.getAsInt(obj, "image_width", 64);
                int imageHeight = GsonHelper.getAsInt(obj, "image_height", 64);

                elements.add(new PageElement(type, content, spacing, itemId, itemNbt, target, imageUrl, imageWidth, imageHeight));
            }
            return elements;
        }
    }

    /**
     * 页面元素（文本、标题、提示、物品、链接、图片等）
     */
    public static class PageElement {
        private final String type;
        private final String contentKey;
        private final int spacing;
        private final String itemId; // 物品ID
        private final String itemNbt; // 物品NBT
        private final String target; // 链接目标章节ID
        private final String imageUrl; // 图片地址（本地ResourceLocation或远程URL）
        private final int imageWidth; // 图片显示宽度
        private final int imageHeight; // 图片显示高度
        private ItemStack cachedItem; // 缓存的物品栈

        public PageElement(String type, String contentKey, int spacing,
                           @Nullable String itemId, @Nullable String itemNbt, @Nullable String target,
                           @Nullable String imageUrl, int imageWidth, int imageHeight) {
            this.type = type;
            this.contentKey = contentKey;
            this.spacing = spacing;
            this.itemId = itemId;
            this.itemNbt = itemNbt;
            this.target = target;
            this.imageUrl = imageUrl;
            this.imageWidth = Math.max(1, imageWidth);
            this.imageHeight = Math.max(1, imageHeight);
        }

        public String getType() {
            return type;
        }

        public String getContentKey() {
            return contentKey;
        }

        public int getSpacing() {
            return spacing;
        }

        public Component getContent() {
            // 优先使用指南书独立语言系统
            String translated = GuideBookLang.get(contentKey);
            if (!translated.equals(contentKey)) {
                return Component.literal(translated);
            }
            // 回退到 Minecraft 语言系统
            return Component.translatable(contentKey);
        }

        public boolean isTitle() {
            return "title".equals(type);
        }

        public boolean isHint() {
            return "hint".equals(type);
        }

        public boolean isText() {
            return "text".equals(type);
        }

        public boolean isItem() {
            return "item".equals(type);
        }

        public boolean isLink() {
            return "link".equals(type);
        }

        public boolean isImage() {
            return "image".equals(type);
        }

        public boolean hasItem() {
            return itemId != null && !itemId.isEmpty();
        }

        @Nullable
        public String getItemId() {
            return itemId;
        }

        @Nullable
        public String getItemNbt() {
            return itemNbt;
        }

        @Nullable
        public String getTarget() {
            return target;
        }

        @Nullable
        public String getImageUrl() {
            return imageUrl;
        }

        public int getImageWidth() {
            return imageWidth;
        }

        public int getImageHeight() {
            return imageHeight;
        }

        /**
         * 获取物品栈（带缓存）
         */
        public ItemStack getItemStack() {
            if (cachedItem != null) {
                return cachedItem;
            }

            if (itemId == null || itemId.isEmpty()) {
                return ItemStack.EMPTY;
            }

            try {
                net.minecraft.world.item.Item item = ForgeRegistries.ITEMS.getValue(
                        net.minecraft.resources.ResourceLocation.tryParse(itemId));
                if (item == null) {
                    item = Items.AIR;
                }

                ItemStack stack = new ItemStack(item);

                // 解析NBT
                if (itemNbt != null && !itemNbt.isEmpty()) {
                    try {
                        CompoundTag tag = TagParser.parseTag(itemNbt);
                        stack.setTag(tag);
                    } catch (Exception e) {
                        // NBT解析失败，使用默认物品
                    }
                }

                cachedItem = stack;
                return stack;
            } catch (Exception e) {
                return ItemStack.EMPTY;
            }
        }
    }
}
