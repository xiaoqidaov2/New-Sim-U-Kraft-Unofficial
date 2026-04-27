package com.xiaoliang.simukraft.building;

import java.util.ArrayList;
import java.util.List;

/**
 * 配方配置类
 * 用于工业建筑的多配方支持
 */
public class RecipeConfig {
    private String recipeId;           // 配方ID
    private String recipeName;         // 配方名称（显示用）
    private String heldItem;           // 手持物品（可选，覆盖建筑默认）

    // 原料配置
    private List<IndustrialBuildingConfig.MaterialRequirement> materials = new ArrayList<>();

    // 产物配置
    private List<IndustrialBuildingConfig.ProductOutput> products = new ArrayList<>();
    
    // 工作时间配置（可选，覆盖建筑默认）
    private Integer workStartTime;     // 开始时间（tick）
    private Integer workEndTime;       // 结束时间（tick）
    private Boolean hasLunchBreak;     // menglannnn: 是否午休（可选，覆盖建筑默认）
    
    public RecipeConfig(String recipeId, String recipeName) {
        this.recipeId = recipeId;
        this.recipeName = recipeName;
    }
    
    public String getRecipeId() { return recipeId; }
    public void setRecipeId(String recipeId) { this.recipeId = recipeId; }
    
    public String getRecipeName() { return recipeName; }
    public void setRecipeName(String recipeName) { this.recipeName = recipeName; }
    
    public String getHeldItem() { return heldItem; }
    public void setHeldItem(String heldItem) { this.heldItem = heldItem; }
    
    public List<IndustrialBuildingConfig.MaterialRequirement> getMaterials() { return materials; }
    public void setMaterials(List<IndustrialBuildingConfig.MaterialRequirement> materials) { this.materials = materials; }

    public void addMaterial(IndustrialBuildingConfig.MaterialRequirement material) {
        this.materials.add(material);
    }

    public List<IndustrialBuildingConfig.ProductOutput> getProducts() { return products; }
    public void setProducts(List<IndustrialBuildingConfig.ProductOutput> products) { this.products = products; }

    public void addProduct(IndustrialBuildingConfig.ProductOutput product) {
        this.products.add(product);
    }
    
    public Integer getWorkStartTime() { return workStartTime; }
    public void setWorkStartTime(Integer workStartTime) { this.workStartTime = workStartTime; }
    
    public Integer getWorkEndTime() { return workEndTime; }
    public void setWorkEndTime(Integer workEndTime) { this.workEndTime = workEndTime; }
    
    /**
     * 检查此配方是否配置了自定义工作时间
     */
    public boolean hasCustomWorkTime() {
        return workStartTime != null && workEndTime != null;
    }
    
    /**
     * 获取实际工作开始时间（优先使用配方配置，否则使用默认值）
     */
    public int getEffectiveWorkStartTime(int defaultStartTime) {
        return workStartTime != null ? workStartTime : defaultStartTime;
    }
    
    /**
     * 获取实际工作结束时间（优先使用配方配置，否则使用默认值）
     */
    public int getEffectiveWorkEndTime(int defaultEndTime) {
        return workEndTime != null ? workEndTime : defaultEndTime;
    }
    
    /**
     * 获取实际手持物品（优先使用配方配置，否则使用默认值）
     */
    public String getEffectiveHeldItem(String defaultHeldItem) {
        return heldItem != null && !heldItem.isEmpty() ? heldItem : defaultHeldItem;
    }

    /**
     * 获取实际是否午休（优先使用配方配置，否则使用默认值）
     * menglannnn: 支持配方级别的午休配置
     */
    public boolean isHasLunchBreak(boolean defaultHasLunchBreak) {
        return hasLunchBreak != null ? hasLunchBreak : defaultHasLunchBreak;
    }

    public Boolean getHasLunchBreak() { return hasLunchBreak; }
    public void setHasLunchBreak(Boolean hasLunchBreak) { this.hasLunchBreak = hasLunchBreak; }

    @Override
    public String toString() {
        return "RecipeConfig{" +
                "recipeId='" + recipeId + '\'' +
                ", recipeName='" + recipeName + '\'' +
                ", materials=" + materials.size() +
                ", products=" + products.size() +
                '}';
    }
}
