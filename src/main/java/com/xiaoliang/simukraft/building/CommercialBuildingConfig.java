package com.xiaoliang.simukraft.building;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 商业建筑配置类
 * 用于存储从JSON和SK文件读取的商业建筑配置
 * 完全配置化，无硬编码
 */
public class CommercialBuildingConfig {

    // ==================== 商店模式枚举 ====================

    public enum ShopMode {
        NPC_SELL,    // NPC出售商品给玩家
        PLAYER_SELL, // 玩家出售商品给NPC
        MIXED        // 混合模式（双向交易）
    }

    // ==================== 默认消息模板 ====================

    private static final Map<String, String> DEFAULT_MESSAGES = new HashMap<>();

    static {
        DEFAULT_MESSAGES.put("hired", "§a{npcName} {jobName} 已开始工作");
        DEFAULT_MESSAGES.put("restock", "§6{npcName} {jobName}：商店已补货");
        DEFAULT_MESSAGES.put("outOfStock", "§c{npcName} {jobName}：{itemName} 已售罄");
        DEFAULT_MESSAGES.put("purchaseSuccess", "§a成功购买 {itemName} x{count}，花费 {price} 元");
        DEFAULT_MESSAGES.put("sellSuccess", "§a成功出售 {itemName} x{count}，获得 {price} 元");
        DEFAULT_MESSAGES.put("notEnoughMoney", "§c余额不足，需要 {price} 元");
        DEFAULT_MESSAGES.put("notEnoughItems", "§c物品不足，需要 {itemName} x{count}");
        DEFAULT_MESSAGES.put("xpGained", "{npcName} 完成了一天的工作，获得5点经验值");
        DEFAULT_MESSAGES.put("levelUp", "§d§l{npcName} 恭喜升级到等级 {level}！");
    }

    // ==================== 基础信息 ====================

    private String buildingId;
    private String buildingName;
    private String jobType;
    private String jobName;

    // ==================== 商业特性配置 ====================

    private ShopMode shopMode = ShopMode.NPC_SELL;
    private List<TradeItem> trades = new ArrayList<>();
    private List<BuyTradeItem> buyTrades = new ArrayList<>();
    private int restockTime = 0;  // 补货时间点（游戏刻，0-24000），到达该时间点就会补货
    private String heldItem;
    private int workStartTime = 0;
    private int workEndTime = 24000;
    private String workBlockHint; // 工作方块提示，用于雇佣系统
    private boolean hasLunchBreak = true; // menglannnn: 是否午休，默认true

    // ==================== 原料需求配置 ====================

    private List<MaterialRequirement> materials = new ArrayList<>();
    private boolean requireMaterialsForSale = false;

    // ==================== SK文件中的其他信息 ====================

    private String size;
    private double amount;
    private String author;
    private String description;

    // ==================== 消息配置 ====================

    private Map<String, String> messages = new HashMap<>();

    // ==================== Getter 和 Setter 方法 ====================

    public String getBuildingId() {
        return buildingId;
    }

    public void setBuildingId(String buildingId) {
        this.buildingId = buildingId;
    }

    public String getBuildingName() {
        return buildingName;
    }

    public void setBuildingName(String buildingName) {
        this.buildingName = buildingName;
    }

    public String getJobType() {
        return jobType;
    }

    public void setJobType(String jobType) {
        this.jobType = jobType;
    }

    public String getJobName() {
        return jobName;
    }

    public void setJobName(String jobName) {
        this.jobName = jobName;
    }

    public ShopMode getShopMode() {
        return shopMode;
    }

    public void setShopMode(ShopMode shopMode) {
        this.shopMode = shopMode;
    }

    public void setShopMode(String mode) {
        try {
            this.shopMode = ShopMode.valueOf(mode.toUpperCase());
        } catch (IllegalArgumentException e) {
            this.shopMode = ShopMode.NPC_SELL;
        }
    }

    public List<TradeItem> getTrades() {
        return trades;
    }

    public void setTrades(List<TradeItem> trades) {
        this.trades = trades;
    }

    public void addTrade(TradeItem trade) {
        this.trades.add(trade);
    }

    public List<BuyTradeItem> getBuyTrades() {
        return buyTrades;
    }

    public void setBuyTrades(List<BuyTradeItem> buyTrades) {
        this.buyTrades = buyTrades;
    }

    public void addBuyTrade(BuyTradeItem buyTrade) {
        this.buyTrades.add(buyTrade);
    }

    public int getRestockTime() {
        return restockTime;
    }

    public void setRestockTime(int restockTime) {
        this.restockTime = restockTime;
    }

    // 为了兼容旧配置，保留旧方法名但指向新字段
    public int getRestockInterval() {
        return restockTime;
    }

    public void setRestockInterval(int restockInterval) {
        this.restockTime = restockInterval;
    }

    public String getHeldItem() {
        return heldItem;
    }

    public void setHeldItem(String heldItem) {
        this.heldItem = heldItem;
    }

    public int getWorkStartTime() {
        return workStartTime;
    }

    public void setWorkStartTime(int workStartTime) {
        this.workStartTime = workStartTime;
    }

    public String getWorkBlockHint() {
        return workBlockHint;
    }

    public void setWorkBlockHint(String workBlockHint) {
        this.workBlockHint = workBlockHint;
    }

    public int getWorkEndTime() {
        return workEndTime;
    }

    public void setWorkEndTime(int workEndTime) {
        this.workEndTime = workEndTime;
    }

    public boolean isHasLunchBreak() {
        return hasLunchBreak;
    }

    public void setHasLunchBreak(boolean hasLunchBreak) {
        this.hasLunchBreak = hasLunchBreak;
    }

    public List<MaterialRequirement> getMaterials() {
        return materials;
    }

    public void setMaterials(List<MaterialRequirement> materials) {
        this.materials = materials;
    }

    public void addMaterial(MaterialRequirement material) {
        this.materials.add(material);
    }

    public boolean isRequireMaterialsForSale() {
        return requireMaterialsForSale;
    }

    public void setRequireMaterialsForSale(boolean requireMaterialsForSale) {
        this.requireMaterialsForSale = requireMaterialsForSale;
    }

    public String getSize() {
        return size;
    }

    public void setSize(String size) {
        this.size = size;
    }

    public double getAmount() {
        return amount;
    }

    public void setAmount(double amount) {
        this.amount = amount;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Map<String, String> getMessages() {
        return messages;
    }

    public void setMessages(Map<String, String> messages) {
        this.messages = messages;
    }

    // ==================== 业务方法 ====================

    /**
     * 检查是否有原料需求
     */
    public boolean hasMaterialRequirements() {
        return requireMaterialsForSale && !materials.isEmpty();
    }

    /**
     * 检查是否有足够的原料可以出售商品
     *
     * @param availableMaterials 可用的原料数量映射（itemId -> count）
     * @return 是否可以出售
     */
    public boolean canSellWithMaterials(Map<String, Integer> availableMaterials) {
        if (!requireMaterialsForSale || materials.isEmpty()) {
            return true;
        }

        for (MaterialRequirement req : materials) {
            int available = availableMaterials.getOrDefault(req.getItemId(), 0);
            if (available < req.getCount()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 获取消息模板，如果不存在则返回默认值
     */
    public String getMessage(String key) {
        return messages.getOrDefault(key, DEFAULT_MESSAGES.getOrDefault(key, ""));
    }

    /**
     * 格式化消息
     */
    public String formatMessage(String key, Map<String, String> params) {
        String message = getMessage(key);
        for (Map.Entry<String, String> entry : params.entrySet()) {
            message = message.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return message;
    }

    /**
     * 检查当前时间是否在工作时间内
     */
    public boolean isWorkTime(long dayTime) {
        long timeOfDay = dayTime % 24000;
        return timeOfDay >= workStartTime && timeOfDay <= workEndTime;
    }

    /**
     * 根据物品ID查找交易配置
     */
    public TradeItem getTradeByItemId(String itemId) {
        for (TradeItem trade : trades) {
            if (trade.getItemId().equalsIgnoreCase(itemId)) {
                return trade;
            }
        }
        return null;
    }

    // ==================== 内部类 ====================

    /**
     * 交易物品类
     */
    public static class TradeItem {
        private String itemId;
        private double buyPrice;
        private double sellPrice;
        private int maxStock;
        private int restockAmount;
        private String requiredMaterial;
        private int requiredMaterialCount = 1;
        private boolean retail = false; // 零售模式：开启后可以一个一个卖

        public TradeItem(String itemId, double buyPrice, double sellPrice, int maxStock, int restockAmount) {
            this.itemId = itemId;
            this.buyPrice = buyPrice;
            this.sellPrice = sellPrice;
            this.maxStock = maxStock;
            this.restockAmount = restockAmount;
        }

        public String getItemId() {
            return itemId;
        }

        public void setItemId(String itemId) {
            this.itemId = itemId;
        }

        public double getBuyPrice() {
            return buyPrice;
        }

        public void setBuyPrice(double buyPrice) {
            this.buyPrice = buyPrice;
        }

        public double getSellPrice() {
            return sellPrice;
        }

        public void setSellPrice(double sellPrice) {
            this.sellPrice = sellPrice;
        }

        public int getMaxStock() {
            return maxStock;
        }

        public void setMaxStock(int maxStock) {
            this.maxStock = maxStock;
        }

        public int getRestockAmount() {
            return restockAmount;
        }

        public void setRestockAmount(int restockAmount) {
            this.restockAmount = restockAmount;
        }

        public String getRequiredMaterial() {
            return requiredMaterial;
        }

        public void setRequiredMaterial(String requiredMaterial) {
            this.requiredMaterial = requiredMaterial;
        }

        public int getRequiredMaterialCount() {
            return requiredMaterialCount;
        }

        public void setRequiredMaterialCount(int requiredMaterialCount) {
            this.requiredMaterialCount = requiredMaterialCount;
        }

        public boolean isRetail() {
            return retail;
        }

        public void setRetail(boolean retail) {
            this.retail = retail;
        }

        /**
         * 检查是否需要原料来生产此物品
         */
        public boolean requiresMaterial() {
            return requiredMaterial != null && !requiredMaterial.isEmpty();
        }

        /**
         * 检查是否允许NPC出售（售价大于0）
         */
        public boolean canSell() {
            return sellPrice > 0;
        }

        /**
         * 检查是否允许NPC购买（收购价大于0）
         */
        public boolean canBuy() {
            return buyPrice > 0;
        }

        /**
         * 检查是否对玩家可见（可以出售给玩家）
         */
        public boolean isVisibleToPlayer() {
            return sellPrice > 0;
        }
    }

    /**
     * 原料需求类
     * 用于配置出售商品所需的原料
     */
    public static class MaterialRequirement {
        @SerializedName("item")
        private String itemId;
        private int count;
        private boolean consume = true;

        public MaterialRequirement(String itemId, int count) {
            this.itemId = itemId;
            this.count = count;
        }

        public String getItemId() {
            return itemId;
        }

        public void setItemId(String itemId) {
            this.itemId = itemId;
        }

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }

        public boolean isConsume() {
            return consume;
        }

        public void setConsume(boolean consume) {
            this.consume = consume;
        }
    }

    /**
     * 收购物品类（NPC从玩家购买）
     */
    public static class BuyTradeItem {
        private String itemId;
        private double buyPrice;
        private int maxBuyAmount;
        private int currentBuyAmount;

        public BuyTradeItem(String itemId, double buyPrice, int maxBuyAmount) {
            this.itemId = itemId;
            this.buyPrice = buyPrice;
            this.maxBuyAmount = maxBuyAmount;
            this.currentBuyAmount = maxBuyAmount;
        }

        public String getItemId() {
            return itemId;
        }

        public void setItemId(String itemId) {
            this.itemId = itemId;
        }

        public double getBuyPrice() {
            return buyPrice;
        }

        public void setBuyPrice(double buyPrice) {
            this.buyPrice = buyPrice;
        }

        public int getMaxBuyAmount() {
            return maxBuyAmount;
        }

        public void setMaxBuyAmount(int maxBuyAmount) {
            this.maxBuyAmount = maxBuyAmount;
        }

        public int getCurrentBuyAmount() {
            return currentBuyAmount;
        }

        public void setCurrentBuyAmount(int currentBuyAmount) {
            this.currentBuyAmount = currentBuyAmount;
        }

        /**
         * 计算出售价格（收购价的85%），保留两位小数
         */
        public double getSellPrice() {
            return Math.round(buyPrice * 0.85 * 100.0) / 100.0;
        }

        /**
         * 检查是否还可以收购
         */
        public boolean canBuy() {
            return currentBuyAmount > 0;
        }

        /**
         * 重置收购数量（补货时调用）
         */
        public void restock() {
            this.currentBuyAmount = maxBuyAmount;
        }
    }

    // ==================== 对象方法 ====================

    @Override
    public String toString() {
        return "CommercialBuildingConfig{" +
                "buildingId='" + buildingId + '\'' +
                ", buildingName='" + buildingName + '\'' +
                ", jobType='" + jobType + '\'' +
                ", jobName='" + jobName + '\'' +
                ", shopMode=" + shopMode +
                ", trades=" + trades.size() +
                ", workTime=[" + workStartTime + "-" + workEndTime + "]" +
                '}';
    }
}
