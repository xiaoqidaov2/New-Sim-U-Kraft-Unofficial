package com.xiaoliang.simukraft.job.core.services;

import com.xiaoliang.simukraft.job.api.services.IWorkService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public abstract class AbstractWorkService implements IWorkService {
    
    protected final ConcurrentMap<String, Long> timers = new ConcurrentHashMap<>();
    protected boolean dataInitialized = false;
    
    @Override
    public boolean isDataInitialized() {
        return dataInitialized;
    }
    
    @Override
    public void setDataInitialized(boolean initialized) {
        this.dataInitialized = initialized;
    }
    
    @Override
    public void onServerStart(ServerLevel level) {
        if (level == null) return;
        MinecraftServer server = level.getServer();
        dataInitialized = false;
        timers.clear();
        
        onServerStart0(server, level);
    }
    
    @Override
    public void onServerStop(ServerLevel level) {
        if (level == null) return;
        onServerStop0(level);
    }
    
    @Override
    public void handleDailyXp(ServerLevel level) {
        if (level == null) return;
        handleDailyXp0(level);
    }
    
    protected abstract void onServerStart0(MinecraftServer server, ServerLevel level);
    
    protected abstract void onServerStop0(ServerLevel level);
    
    protected abstract void handleDailyXp0(ServerLevel level);
    
    protected long getTimer(String key, long defaultValue) {
        return timers.getOrDefault(key, defaultValue);
    }
    
    protected void setTimer(String key, long value) {
        timers.put(key, value);
    }
    
    protected boolean shouldProcess(String key, long interval) {
        long lastTime = getTimer(key, 0);
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastTime >= interval) {
            timers.put(key, currentTime);
            return true;
        }
        return false;
    }
}