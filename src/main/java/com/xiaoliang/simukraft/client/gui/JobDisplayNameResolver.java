package com.xiaoliang.simukraft.client.gui;

import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一解析职业显示名，优先使用建筑 JSON 配置中的 jobName，
 * 避免商业/工业通用职业或缺失翻译时直接显示键名。
 */
@OnlyIn(Dist.CLIENT)
public final class JobDisplayNameResolver {
    private static final Map<String, String> DISPLAY_NAME_CACHE = new ConcurrentHashMap<>();

    private JobDisplayNameResolver() {
    }

    public static String resolve(@Nullable String jobType, @Nullable UUID npcUuid) {
        if (jobType == null || jobType.isBlank() || "unemployed".equalsIgnoreCase(jobType)) {
            return translatedOrDefault("job.unemployed", "unemployed");
        }

        String cacheKey = jobType + "|" + (npcUuid != null ? npcUuid : "none");
        String cached = DISPLAY_NAME_CACHE.get(cacheKey);
        if (cached != null && !cached.isBlank()) {
            return cached;
        }

        String resolved = firstNonBlank(
                resolveCommercialJobName(jobType),
                resolveIndustrialJobName(jobType),
                resolveByNpcAssignment(npcUuid),
                resolveBuiltInJobName(jobType),
                resolveJobTranslation(jobType),
                jobType
        );

        DISPLAY_NAME_CACHE.put(cacheKey, resolved);
        return resolved;
    }

    @Nullable
    private static String resolveCommercialJobName(String jobType) {
        return CommercialClientData.getJobNameByJobType(jobType);
    }

    @Nullable
    private static String resolveIndustrialJobName(String jobType) {
        return IndustrialClientData.getJobNameByJobType(jobType);
    }

    @Nullable
    private static String resolveByNpcAssignment(@Nullable UUID npcUuid) {
        if (npcUuid == null) {
            return null;
        }

        String commercial = resolveCommercialByNpc(npcUuid);
        if (commercial != null && !commercial.isBlank()) {
            return commercial;
        }
        return resolveIndustrialByNpc(npcUuid);
    }

    @Nullable
    private static String resolveCommercialByNpc(UUID npcUuid) {
        for (CommercialClientData.HireInfo info : CommercialClientData.getAllHiredEmployeeUuids().values()) {
            if (info == null || !Objects.equals(info.getNpcUuid(), npcUuid)) {
                continue;
            }
            String byBuilding = CommercialClientData.getJobName(info.getBuildingFileName());
            if (byBuilding != null && !byBuilding.isBlank()) {
                return byBuilding;
            }
            String byJobType = CommercialClientData.getJobNameByJobType(info.getJobType(), info.getBuildingFileName());
            if (byJobType != null && !byJobType.isBlank()) {
                return byJobType;
            }
        }
        return null;
    }

    @Nullable
    private static String resolveIndustrialByNpc(UUID npcUuid) {
        for (IndustrialClientData.HireInfo info : IndustrialClientData.getAllHiredEmployeeUuids().values()) {
            if (info == null || !Objects.equals(info.getNpcUuid(), npcUuid)) {
                continue;
            }
            String byBuilding = IndustrialClientData.getJobName(info.getBuildingFileName());
            if (byBuilding != null && !byBuilding.isBlank()) {
                return byBuilding;
            }
            String byJobType = IndustrialClientData.getJobNameByJobType(info.getJobType());
            if (byJobType != null && !byJobType.isBlank()) {
                return byJobType;
            }
        }
        return null;
    }

    @Nullable
    private static String resolveBuiltInJobName(String jobType) {
        return switch (jobType) {
            case "builder" -> translatedOrDefault("job.builder", jobType);
            case "planner" -> translatedOrDefault("job.planner", jobType);
            case "shepherd" -> translatedOrDefault("job.shepherd", jobType);
            case "butcher" -> translatedOrDefault("job.butcher", jobType);
            case "farmer" -> translatedOrDefault("job.farmer", jobType);
            case "banker" -> translatedOrDefault("job.banker", jobType);
            case "doctor" -> firstNonBlank(
                    translateIfPresent("job.doctor"),
                    translateIfPresent("gui.employee_info.job.doctor")
            );
            case "warehouse_manager" -> translatedOrDefault("job.warehouse_manager", jobType);
            default -> null;
        };
    }

    @Nullable
    private static String resolveJobTranslation(String jobType) {
        return translateIfPresent("job." + jobType);
    }

    private static String translatedOrDefault(@Nonnull String key, @Nonnull String fallback) {
        String translated = translateIfPresent(key);
        return translated != null && !translated.isBlank() ? translated : fallback;
    }

    @Nullable
    private static String translateIfPresent(@Nonnull String key) {
        final String translated = Objects.requireNonNull(Component.translatable(key).getString());
        return key.equals(translated) ? null : translated;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
