package com.xiaoliang.simukraft.client.memory;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 客户端外部内存清理服务。
 * 仅在 Windows 客户端尝试拉起 Mem Reduct，并交给它按 ini 自动清理。
 */
@OnlyIn(Dist.CLIENT)
public final class ClientMemoryCleanerService {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final String CLEANER_FILE_NAME = "memreduct.exe";
    private static final long START_DELAY_TICKS = 100L;
    private static final long HEALTH_CHECK_INTERVAL_TICKS = 20L * 30L;
    private static final int MAX_LAUNCH_RETRIES = 3;

    private static final AtomicBoolean SESSION_ACTIVE = new AtomicBoolean(false);
    private static final AtomicBoolean STARTED_BY_MOD = new AtomicBoolean(false);
    private static final AtomicBoolean MISSING_EXECUTABLE_REPORTED = new AtomicBoolean(false);
    private static final AtomicLong WORLD_TICKS = new AtomicLong(0L);
    private static final AtomicLong LAST_HEALTH_CHECK_TICK = new AtomicLong(Long.MIN_VALUE);
    private static final AtomicLong LAUNCH_RETRY_COUNT = new AtomicLong(0L);

    private static volatile Path activeExecutablePath;
    private static volatile Process launchedProcess;

    private ClientMemoryCleanerService() {
    }

    public static void onClientLoggedIn() {
        SESSION_ACTIVE.set(true);
        STARTED_BY_MOD.set(false);
        MISSING_EXECUTABLE_REPORTED.set(false);
        WORLD_TICKS.set(0L);
        LAST_HEALTH_CHECK_TICK.set(Long.MIN_VALUE);
        LAUNCH_RETRY_COUNT.set(0L);
        activeExecutablePath = null;
        launchedProcess = null;
    }

    public static void onClientLoggedOut() {
        SESSION_ACTIVE.set(false);
        WORLD_TICKS.set(0L);
        LAST_HEALTH_CHECK_TICK.set(Long.MIN_VALUE);
        LAUNCH_RETRY_COUNT.set(0L);

        Process process = launchedProcess;
        launchedProcess = null;
        if (STARTED_BY_MOD.compareAndSet(true, false) && process != null && process.isAlive()) {
            process.destroy();
            LOGGER.info("[ClientMemoryCleaner] 已在客户端退出世界时关闭模组启动的 Mem Reduct 进程");
        }
    }

    public static void onClientTick(Minecraft minecraft) {
        if (!SESSION_ACTIVE.get() || minecraft == null || minecraft.level == null) {
            return;
        }
        if (!isWindows()) {
            return;
        }

        long currentTick = WORLD_TICKS.incrementAndGet();
        if (currentTick < START_DELAY_TICKS) {
            return;
        }
        if (currentTick - LAST_HEALTH_CHECK_TICK.get() < HEALTH_CHECK_INTERVAL_TICKS) {
            return;
        }
        LAST_HEALTH_CHECK_TICK.set(currentTick);

        Path executablePath = resolveExecutablePath();
        if (executablePath == null) {
            if (MISSING_EXECUTABLE_REPORTED.compareAndSet(false, true)) {
                LOGGER.warn("[ClientMemoryCleaner] 未找到 Mem Reduct，可将 portable 版放到桌面目录 `64/memreduct.exe` 或游戏目录 `memreduct/memreduct.exe`");
            }
            return;
        }

        activeExecutablePath = executablePath;
        if (isCleanerRunning(executablePath)) {
            return;
        }
        if (LAUNCH_RETRY_COUNT.incrementAndGet() > MAX_LAUNCH_RETRIES) {
            LOGGER.warn("[ClientMemoryCleaner] Mem Reduct 连续启动失败已达到上限，本次游戏会话不再重试");
            return;
        }

        preparePortableConfiguration(executablePath);
        launchCleaner(executablePath);
    }

    private static Path resolveExecutablePath() {
        List<Path> candidates = new ArrayList<>();
        String userHome = System.getProperty("user.home", "");
        if (!userHome.isBlank()) {
            candidates.add(Paths.get(userHome, "Desktop", "64", CLEANER_FILE_NAME));
        }
        candidates.add(Paths.get("").toAbsolutePath().resolve("memreduct").resolve(CLEANER_FILE_NAME));

        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    private static void preparePortableConfiguration(Path executablePath) {
        Path iniPath = executablePath.getParent().resolve("memreduct.ini");
        List<String> lines = new ArrayList<>();
        try {
            if (Files.exists(iniPath)) {
                lines.addAll(Files.readAllLines(iniPath, StandardCharsets.UTF_8));
            }
            List<String> updatedLines = upsertMemReductSection(lines);
            Files.write(iniPath, updatedLines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("[ClientMemoryCleaner] 写入 Mem Reduct 配置失败: {}", iniPath, e);
        }
    }

    private static List<String> upsertMemReductSection(List<String> lines) {
        List<String> result = new ArrayList<>();
        boolean inSection = false;
        boolean sectionFound = false;
        boolean startMinimizedSet = false;
        boolean autoReductSet = false;
        boolean confirmationSet = false;

        for (String line : lines) {
            String trimmed = line.trim();
            boolean nextSection = trimmed.startsWith("[") && trimmed.endsWith("]");

            if (nextSection && inSection) {
                if (!startMinimizedSet) {
                    result.add("IsStartMinimized=true");
                }
                if (!autoReductSet) {
                    result.add("AutoreductEnable=true");
                }
                if (!confirmationSet) {
                    result.add("IsShowReductConfirmation=false");
                }
                inSection = false;
            }

            if ("[memreduct]".equalsIgnoreCase(trimmed)) {
                sectionFound = true;
                inSection = true;
                result.add("[memreduct]");
                continue;
            }

            if (inSection) {
                if (trimmed.startsWith("IsStartMinimized=")) {
                    result.add("IsStartMinimized=true");
                    startMinimizedSet = true;
                    continue;
                }
                if (trimmed.startsWith("AutoreductEnable=")) {
                    result.add("AutoreductEnable=true");
                    autoReductSet = true;
                    continue;
                }
                if (trimmed.startsWith("IsShowReductConfirmation=")) {
                    result.add("IsShowReductConfirmation=false");
                    confirmationSet = true;
                    continue;
                }
            }

            result.add(line);
        }

        if (inSection) {
            if (!startMinimizedSet) {
                result.add("IsStartMinimized=true");
            }
            if (!autoReductSet) {
                result.add("AutoreductEnable=true");
            }
            if (!confirmationSet) {
                result.add("IsShowReductConfirmation=false");
            }
        }

        if (!sectionFound) {
            if (!result.isEmpty() && !result.get(result.size() - 1).isBlank()) {
                result.add("");
            }
            result.add("[memreduct]");
            result.add("IsStartMinimized=true");
            result.add("AutoreductEnable=true");
            result.add("IsShowReductConfirmation=false");
        }

        return result;
    }

    private static void launchCleaner(Path executablePath) {
        try {
            Process process = new ProcessBuilder(executablePath.toString())
                    .directory(executablePath.getParent().toFile())
                    .start();
            launchedProcess = process;
            STARTED_BY_MOD.set(true);
            LOGGER.info("[ClientMemoryCleaner] 已启动 Mem Reduct: {}", executablePath);
        } catch (IOException e) {
            LOGGER.error("[ClientMemoryCleaner] 启动 Mem Reduct 失败: {}", executablePath, e);
        }
    }

    private static boolean isCleanerRunning(Path executablePath) {
        String expectedPath = executablePath.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
        try {
            return ProcessHandle.allProcesses().anyMatch(handle -> {
                String command = handle.info().command().orElse("");
                if (command.isBlank()) {
                    return false;
                }
                String normalized = Paths.get(command).toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
                return normalized.equals(expectedPath) || normalized.endsWith("\\" + CLEANER_FILE_NAME);
            });
        } catch (Exception e) {
            LOGGER.warn("[ClientMemoryCleaner] 检查 Mem Reduct 运行状态失败，将退化为仅按模组启动状态判断", e);
            Process process = launchedProcess;
            return process != null && process.isAlive();
        }
    }

    private static boolean isWindows() {
        String osName = System.getProperty("os.name", "");
        return osName.toLowerCase(Locale.ROOT).contains("win");
    }
}
