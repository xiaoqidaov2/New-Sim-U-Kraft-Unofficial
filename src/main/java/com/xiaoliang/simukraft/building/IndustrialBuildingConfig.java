package com.xiaoliang.simukraft.building;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工业建筑配置类
 * 用于存储从JSON和SK文件读取的工业建筑配置
 * 完全配置化，无硬编码
 */
public class IndustrialBuildingConfig {
    
    // 基础信息
    private String buildingId;           // 建筑ID（文件名）
    private String buildingName;         // 建筑名称
    private String jobType;              // 工作类型标识
    private String jobName;              // 工作名称（显示用）
    
    // 原料配置（向后兼容，单配方模式）
    private List<MaterialRequirement> materials = new ArrayList<>();
    
    // 产物配置（向后兼容，单配方模式）
    private List<ProductOutput> products = new ArrayList<>();
    
    // 多配方配置
    private List<RecipeConfig> recipes = new ArrayList<>();
    private boolean multiRecipe = false; // 是否启用多配方模式
    
    // 工作时间配置（tick）
    private int workStartTime = 0;       // 默认0tick（早上6:00）
    private int workEndTime = 12000;     // 默认12000tick（傍晚18:00），共12小时工作时间
    private boolean hasLunchBreak = true; // menglannnn: 是否午休，默认true
    
    // 生物生成配置（附加项）
    private boolean spawnEntity = false; // 是否生成生物
    private String entityType;           // 生物类型
    private int entityCount = 0;         // 生物数量
    
    // 手持物品配置
    private String heldItem;             // 手持物品ID
    
    // 消息配置
    private Map<String, String> messages = new HashMap<>();
    
    // SK文件中的其他信息
    private String size;                 // 建筑尺寸
    private double amount;               // 金额/租金
    private String author;               // 作者
    private String description;          // 描述
    
    // 默认消息模板
    private static final Map<String, String> DEFAULT_MESSAGES = new HashMap<>();
    static {
        DEFAULT_MESSAGES.put("hired", "§a{npcName} {jobName} 已开始工作");
        DEFAULT_MESSAGES.put("workComplete", "§6{npcName} {jobName}：工作完成，产物已放入箱子");
        DEFAULT_MESSAGES.put("chestFull", "§c{npcName} {jobName}：工作完成，但箱子已满！");
        DEFAULT_MESSAGES.put("xpGained", "{npcName} 完成了一天的工作，获得5点经验值");
        DEFAULT_MESSAGES.put("levelUp", "！恭喜升级到等级 {level}！");
    }
    
    /**
     * 原料需求类
     */
    public static class MaterialRequirement {
        private String itemId;           // 物品ID
        private int count;               // 数量
        private boolean consume = false; // 是否消耗（预留）
        
        public MaterialRequirement(String itemId, int count) {
            this.itemId = itemId;
            this.count = count;
        }
        
        public String getItemId() { return itemId; }
        public void setItemId(String itemId) { this.itemId = itemId; }
        
        public int getCount() { return count; }
        public void setCount(int count) { this.count = count; }
        
        public boolean isConsume() { return consume; }
        public void setConsume(boolean consume) { this.consume = consume; }
    }
    
    /**
     * 产物输出类
     */
    public static class ProductOutput {
        private String itemId;           // 物品ID
        private int baseAmount;          // 基础数量
        private int randomRange;         // 随机范围
        private double probability = 1.0; // 概率（0.0-1.0）
        
        public ProductOutput(String itemId, int baseAmount, int randomRange) {
            this.itemId = itemId;
            this.baseAmount = baseAmount;
            this.randomRange = randomRange;
        }
        
        public String getItemId() { return itemId; }
        public void setItemId(String itemId) { this.itemId = itemId; }
        
        public int getBaseAmount() { return baseAmount; }
        public void setBaseAmount(int baseAmount) { this.baseAmount = baseAmount; }
        
        public int getRandomRange() { return randomRange; }
        public void setRandomRange(int randomRange) { this.randomRange = randomRange; }
        
        public double getProbability() { return probability; }
        public void setProbability(double probability) { this.probability = probability; }
        
        /**
         * 计算实际产出数量
         */
        public int calculateAmount(net.minecraft.util.RandomSource random, float levelMultiplier) {
            if (random.nextDouble() > probability) {
                return 0; // 未命中概率，不产出
            }
            // 修复：确保 randomRange 至少为 1，避免 nextInt(0) 抛出异常
            int amount = baseAmount + (randomRange > 0 ? random.nextInt(randomRange) : 0);
            return (int) (amount * levelMultiplier);
        }
    }
    
    // Getters and Setters
    
    public String getBuildingId() { return buildingId; }
    public void setBuildingId(String buildingId) { this.buildingId = buildingId; }
    
    public String getBuildingName() { return buildingName; }
    public void setBuildingName(String buildingName) { this.buildingName = buildingName; }
    
    public String getJobType() { return jobType; }
    public void setJobType(String jobType) { this.jobType = jobType; }
    
    public String getJobName() { return jobName; }
    public void setJobName(String jobName) { this.jobName = jobName; }
    
    public List<MaterialRequirement> getMaterials() { return materials; }
    public void setMaterials(List<MaterialRequirement> materials) { this.materials = materials; }
    
    public void addMaterial(MaterialRequirement material) {
        this.materials.add(material);
    }
    
    public List<ProductOutput> getProducts() { return products; }
    public void setProducts(List<ProductOutput> products) { this.products = products; }
    
    public void addProduct(ProductOutput product) {
        this.products.add(product);
    }
    
    // 多配方相关方法
    public List<RecipeConfig> getRecipes() { return recipes; }
    public void setRecipes(List<RecipeConfig> recipes) { 
        this.recipes = recipes;
        this.multiRecipe = (recipes != null && !recipes.isEmpty());
    }
    
    public void addRecipe(RecipeConfig recipe) {
        if (this.recipes == null) {
            this.recipes = new ArrayList<>();
        }
        this.recipes.add(recipe);
        this.multiRecipe = true;
    }
    
    public boolean isMultiRecipe() { return multiRecipe; }
    public void setMultiRecipe(boolean multiRecipe) { this.multiRecipe = multiRecipe; }
    
    /**
     * 根据配方ID获取配方配置
     */
    public RecipeConfig getRecipeById(String recipeId) {
        if (recipes == null || recipeId == null) return null;
        for (RecipeConfig recipe : recipes) {
            if (recipeId.equals(recipe.getRecipeId())) {
                return recipe;
            }
        }
        return null;
    }
    
    /**
     * 获取默认配方（第一个配方，或单配方模式下的默认配置）
     */
    public RecipeConfig getDefaultRecipe() {
        if (multiRecipe && recipes != null && !recipes.isEmpty()) {
            return recipes.get(0);
        }
        return null;
    }
    
    /**
     * 获取指定配方或默认配置的原料列表
     * @param recipeId 配方ID，如果为null则使用默认配置
     */
    public List<MaterialRequirement> getEffectiveMaterials(String recipeId) {
        if (recipeId != null && multiRecipe) {
            RecipeConfig recipe = getRecipeById(recipeId);
            if (recipe != null) {
                return recipe.getMaterials();
            }
        }
        return materials;
    }
    
    /**
     * 获取指定配方或默认配置的产物列表
     * @param recipeId 配方ID，如果为null则使用默认配置
     */
    public List<ProductOutput> getEffectiveProducts(String recipeId) {
        if (recipeId != null && multiRecipe) {
            RecipeConfig recipe = getRecipeById(recipeId);
            if (recipe != null) {
                return recipe.getProducts();
            }
        }
        return products;
    }
    
    /**
     * 获取指定配方或默认配置的手持物品
     * @param recipeId 配方ID，如果为null则使用默认配置
     */
    public String getEffectiveHeldItem(String recipeId) {
        if (recipeId != null && multiRecipe) {
            RecipeConfig recipe = getRecipeById(recipeId);
            if (recipe != null) {
                return recipe.getEffectiveHeldItem(heldItem);
            }
        }
        return heldItem;
    }
    
    /**
     * 获取指定配方或默认配置的工作开始时间
     * @param recipeId 配方ID，如果为null则使用默认配置
     */
    public int getEffectiveWorkStartTime(String recipeId) {
        if (recipeId != null && multiRecipe) {
            RecipeConfig recipe = getRecipeById(recipeId);
            if (recipe != null) {
                return recipe.getEffectiveWorkStartTime(workStartTime);
            }
        }
        return workStartTime;
    }
    
    /**
     * 获取指定配方或默认配置的工作结束时间
     * @param recipeId 配方ID，如果为null则使用默认配置
     */
    public int getEffectiveWorkEndTime(String recipeId) {
        if (recipeId != null && multiRecipe) {
            RecipeConfig recipe = getRecipeById(recipeId);
            if (recipe != null) {
                return recipe.getEffectiveWorkEndTime(workEndTime);
            }
        }
        return workEndTime;
    }
    
    /**
     * 检查指定配方是否在工作时间内
     */
    public boolean isWorkTimeForRecipe(String recipeId, long dayTime) {
        long timeOfDay = dayTime % 24000;
        int start = getEffectiveWorkStartTime(recipeId);
        int end = getEffectiveWorkEndTime(recipeId);
        return timeOfDay >= start && timeOfDay < end;
    }
    
    public int getWorkStartTime() { return workStartTime; }
    public void setWorkStartTime(int workStartTime) { this.workStartTime = workStartTime; }
    
    public int getWorkEndTime() { return workEndTime; }
    public void setWorkEndTime(int workEndTime) { this.workEndTime = workEndTime; }

    public boolean isHasLunchBreak() { return hasLunchBreak; }
    public void setHasLunchBreak(boolean hasLunchBreak) { this.hasLunchBreak = hasLunchBreak; }

    /**
     * 获取指定配方或默认配置的是否午休
     * @param recipeId 配方ID，如果为null则使用默认配置
     */
    public boolean isHasLunchBreakForRecipe(String recipeId) {
        if (recipeId != null && multiRecipe) {
            RecipeConfig recipe = getRecipeById(recipeId);
            if (recipe != null) {
                return recipe.isHasLunchBreak(hasLunchBreak);
            }
        }
        return hasLunchBreak;
    }

    public boolean isSpawnEntity() { return spawnEntity; }
    public void setSpawnEntity(boolean spawnEntity) { this.spawnEntity = spawnEntity; }
    
    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }
    
    public int getEntityCount() { return entityCount; }
    public void setEntityCount(int entityCount) { this.entityCount = entityCount; }
    
    public String getHeldItem() { return heldItem; }
    public void setHeldItem(String heldItem) { this.heldItem = heldItem; }
    
    public Map<String, String> getMessages() { return messages; }
    public void setMessages(Map<String, String> messages) { this.messages = messages; }
    
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
    
    public String getSize() { return size; }
    public void setSize(String size) { this.size = size; }
    
    public double getAmount() { return amount; }
    public void setAmount(double amount) { this.amount = amount; }
    
    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }
    
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    
    /**
     * 检查当前时间是否在工作时间内
     */
    public boolean isWorkTime(long dayTime) {
        long timeOfDay = dayTime % 24000;
        return timeOfDay >= workStartTime && timeOfDay < workEndTime;
    }
    
    @Override
    public String toString() {
        return "IndustrialBuildingConfig{" +
                "buildingId='" + buildingId + '\'' +
                ", buildingName='" + buildingName + '\'' +
                ", jobType='" + jobType + '\'' +
                ", jobName='" + jobName + '\'' +
                ", materials=" + materials.size() +
                ", products=" + products.size() +
                ", workTime=[" + workStartTime + "-" + workEndTime + "]" +
                ", spawnEntity=" + spawnEntity +
                '}';
    }
}
