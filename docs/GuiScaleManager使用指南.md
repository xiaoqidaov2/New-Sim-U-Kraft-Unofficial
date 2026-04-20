# GUI缩放管理指南

## 概述

`GuiScaleManager` 是一个统一管理 Minecraft GUI 缩放的工具类，用于在配置界面中临时使用 3x 缩放，退出时恢复原始缩放。

## 核心类

### GuiScaleManager

位置：`com.xiaoliang.simukraft.client.gui.GuiScaleManager`

#### 主要方法

| 方法 | 说明 |
|------|------|
| `apply3x()` | 应用 3x 缩放，自动保存原始值 |
| `restore()` | 恢复原始缩放 |
| `readOriginalScale()` | 从 options.txt 读取原始缩放值 |
| `setScale(int scale)` | 设置指定缩放值 |

## 使用方式

### 1. LDLib 界面集成

在 LDLib 实现的界面中使用固定 3x 缩放：

```java
public class ConfigScreen extends ModularUIGuiContainer {
    private final Screen parent;
    private static ConfigScreen currentInstance;

    public ConfigScreen(Screen parent) {
        super(createHolderAndUI(parent), 0);
        this.parent = parent;
        currentInstance = this;
        // 构造函数中应用 3x 缩放
        GuiScaleManager.apply3x();
    }

    @Override
    public void onClose() {
        // 关闭时恢复原始缩放
        GuiScaleManager.restore();
        currentInstance = null;
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        // 渲染时保持 3x 缩放
        GuiScaleManager.apply3x();
        super.render(graphics, mouseX, mouseY, partialTicks);
    }
}
```

### 2. 返回按钮处理

确保返回按钮也触发缩放恢复：

```java
private void closeScreen() {
    // 调用 onClose 来恢复缩放
    if (currentInstance != null) {
        currentInstance.onClose();
    }
}
```

## 关键点

### 1. 静态实例变量

```java
private static ConfigScreen currentInstance;
```

- 供内部类访问外部实例
- 在构造函数中设置：`currentInstance = this`
- 在 onClose 中清除：`currentInstance = null`

### 2. 缩放设置时机

| 时机 | 操作 | 说明 |
|------|------|------|
| 构造函数 | `apply3x()` | 界面打开立即应用 3x |
| render | `apply3x()` | 保持 3x 缩放状态 |
| onClose | `restore()` | 恢复原始缩放 |

### 3. 原始值读取

从 `options.txt` 读取 `guiScale` 配置：

```java
private int readOriginalScale() {
    File optionsFile = new File(Minecraft.getInstance().gameDirectory, "options.txt");
    // 解析 guiScale:xxx 行
}
```

## 现有实现

### ModConfigScreenLDLib

客户端配置界面，包含 HUD 位置调整入口。

### ModMenuIntegration.ConfigSelectionScreenLDLib

ModMenu 配置选择界面，提供客户端/服务器配置入口。

## 注意事项

1. **构造函数中立即应用**：确保界面打开就是 3x 缩放
2. **所有关闭方式走 onClose()**：ESC 和返回按钮行为一致
3. **从 options.txt 读取原始值**：确保恢复正确的缩放
4. **HUDPositionEditorScreen 保持原始比例**：不参与 3x 缩放管理

## 示例流程

假设设置文件中 `guiScale:6`：

1. 打开 ModMenuIntegration：读取 6，设置 3x
2. 打开 ModConfigScreenLDLib：读取 6，设置 3x
3. 打开 HUDPositionEditorScreen：保持 3x（继承）
4. 关闭 HUDPositionEditorScreen：返回 ModConfigScreenLDLib
5. 关闭 ModConfigScreenLDLib：恢复为 6
6. 关闭 ModMenuIntegration：恢复为 6
