package com.xiaoliang.simukraft.entity;

/**
 * NPC工作子状态枚举
 * 用于细分WORKING状态下的具体行为
 */
public enum WorkSubState {
    NONE("work_sub_state.none"),
    WORKING("work_sub_state.working"),
    RESTING("work_sub_state.resting"),
    LUNCH_BREAK("work_sub_state.lunch_break"); // menglannnn: 午休状态，中午6000-8000tick

    private final String displayName;

    WorkSubState(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static WorkSubState fromString(String status) {
        if (status == null) return NONE;
        if (status.equals("work_sub_state.working") || status.equals("工作中")) return WORKING;
        if (status.equals("work_sub_state.resting") || status.equals("休息中")) return RESTING;
        if (status.equals("work_sub_state.lunch_break") || status.equals("午休中")) return LUNCH_BREAK;
        return NONE;
    }
}
