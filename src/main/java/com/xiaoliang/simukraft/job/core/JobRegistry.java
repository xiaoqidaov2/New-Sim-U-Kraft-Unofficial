package com.xiaoliang.simukraft.job.core;

import com.xiaoliang.simukraft.employment.domain.JobType;
import com.xiaoliang.simukraft.job.api.JobDefinition;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class JobRegistry {
    private static final Map<JobType, JobDefinition> JOBS = new ConcurrentHashMap<>();

    private JobRegistry() {
    }

    public static void register(JobDefinition definition) {
        if (definition == null || definition.type() == null) {
            return;
        }
        JOBS.put(definition.type(), definition);
    }

    public static Optional<JobDefinition> get(JobType type) {
        if (type == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(JOBS.get(type));
    }

    public static Collection<JobDefinition> values() {
        return JOBS.values().stream()
                .sorted(Comparator.comparing(definition -> definition.type().name()))
                .toList();
    }

    public static List<JobType> registeredTypes() {
        return JOBS.keySet().stream()
                .sorted(Comparator.comparing(Enum::name))
                .toList();
    }
}
