package com.xiaoliang.simukraft.job.api.services;

import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.job.api.JobContext;
import net.minecraft.server.level.ServerLevel;

import java.util.UUID;

public interface IWorkService {
    
    void onServerStart(ServerLevel level);
    
    void onServerStop(ServerLevel level);
    
    void handleDailyXp(ServerLevel level);
    
    void restoreWorkState(CustomEntity npc, UUID npcUuid, ServerLevel level);
    
    void handleContinuousWork(JobContext context);
    
    boolean isDataInitialized();
    
    void setDataInitialized(boolean initialized);
}