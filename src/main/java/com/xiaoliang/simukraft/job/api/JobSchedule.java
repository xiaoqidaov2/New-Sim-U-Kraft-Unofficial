package com.xiaoliang.simukraft.job.api;

public record JobSchedule(int workStartTime, int workEndTime) {
    public static final JobSchedule ALWAYS = new JobSchedule(0, 0);

    public boolean isWorkTime(long dayTime) {
        int time = (int) (Math.floorMod(dayTime, 24000L));
        if (workStartTime == workEndTime) {
            return true;
        }
        if (workStartTime < workEndTime) {
            return time >= workStartTime && time < workEndTime;
        }
        return time >= workStartTime || time < workEndTime;
    }
}
