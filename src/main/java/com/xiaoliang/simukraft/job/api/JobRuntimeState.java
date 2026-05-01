package com.xiaoliang.simukraft.job.api;

import com.mojang.serialization.Codec;

public enum JobRuntimeState {
    IDLE,
    GOING_TO_WORK,
    WORKING,
    PAUSED,
    GOING_HOME,
    RESTING,
    BLOCKED,
    INVALID;
    
    public static final Codec<JobRuntimeState> CODEC = Codec.STRING.xmap(
        name -> {
            try {
                return valueOf(name);
            } catch (IllegalArgumentException e) {
                return IDLE;
            }
        },
        Enum::name
    );
}
