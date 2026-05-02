package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.Simukraft;
import com.xiaoliang.simukraft.entity.CustomEntity;
import com.xiaoliang.simukraft.init.ModEntities;
import com.xiaoliang.simukraft.world.CityChunkData;
import com.xiaoliang.simukraft.world.CityData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

@SuppressWarnings("null")
public class CreateCityPacket {
    private final String cityName;
    private final BlockPos cityCorePos;

    public CreateCityPacket(String cityName, BlockPos cityCorePos) {
        this.cityName = cityName;
        this.cityCorePos = cityCorePos;
    }

    public CreateCityPacket(FriendlyByteBuf buf) {
        this.cityName = buf.readUtf(32767);
        this.cityCorePos = buf.readBlockPos();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(this.cityName);
        buf.writeBlockPos(this.cityCorePos);
    }

    public static CreateCityPacket decode(FriendlyByteBuf buf) {
        return new CreateCityPacket(buf);
    }

    public static void handle(CreateCityPacket message, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) {
                ServerLevel level = player.serverLevel();
                
                // 获取城市数据管理�?
                CityData cityData = CityData.get(level);
                
                // 检查玩家是否已有城�?
                String playerName = player.getName().getString();
                if (!cityData.hasCity(playerName)) {
                    String cityName = message.cityName.trim();
                    
                    // 服务器端数据验证
                    if (cityName.isEmpty() || cityName.length() < 2 || cityName.length() > 20) {
                        player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("message.simukraft.city_name_invalid"));
                        return;
                    }
                    
                    // 检查城市名称是否已存在
                    boolean nameExists = false;
                    for (CityData.CityInfo existingCity : cityData.getCitiesByMayor(player.getUUID())) {
                        if (existingCity.getCityName().equals(cityName)) {
                            nameExists = true;
                            break;
                        }
                    }
                    
                    if (nameExists) {
                        player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("message.simukraft.city_name_exists"));
                        return;
                    }
                    
                    // 计算城市核心所在的区块位置
                    ChunkPos cityCoreChunk = new ChunkPos(message.cityCorePos);
                    
                    // 获取城市区块数据管理�?
                    CityChunkData cityChunkData = CityChunkData.get(level);
                    
                    // Debug info
                    Simukraft.LOGGER.info("[CreateCity] Attempting to create city, checking chunk availability...");
                    Simukraft.LOGGER.info("[CreateCity] City core position: {}", message.cityCorePos);
                    Simukraft.LOGGER.info("[CreateCity] City core chunk: {}", cityCoreChunk);
                    
                    // 检查以城市核心所在区块为中心的九格区块是否已被占�?
                    boolean isAvailable = cityChunkData.isAreaAvailable(cityCoreChunk);
                    Simukraft.LOGGER.info("[CreateCity] Chunk area available: {}", isAvailable);
                    
                    if (!isAvailable) {
                        player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("message.simukraft.chunks_occupied"));
                        return;
                    }
                    
                    // 创建城市，传入玩家名和UUID
                    CityData.CityInfo cityInfo = cityData.createCity(playerName, player.getUUID(), message.cityName, message.cityCorePos, level);
                    
                    // 将以城市核心所在区块为中心的九格区块分配给该城�?
                    Simukraft.LOGGER.info("[CreateCity] City created successfully, city ID: {}", cityInfo.getCityId());
                    cityChunkData.assignAreaToCity(cityInfo.getCityId(), cityCoreChunk);
                    Simukraft.LOGGER.info("[CreateCity] Chunks assigned to city, chunk count: {}", cityChunkData.getCityChunks(cityInfo.getCityId()).size());
                    
                    // 调用模组集成：认领区块并广播更新
                    com.xiaoliang.simukraft.integration.IntegrationBridge.onCityChunksClaimed(
                        player.getServer(),
                        cityInfo.getCityId(),
                        cityInfo.getMayorId(),
                        cityChunkData.getCityChunks(cityInfo.getCityId())
                    );

                    // 同步城市核心位置到所有客户端
                    NetworkManager.broadcastAllCityCores(player.getServer());

                    // 强制保存数据
                    cityChunkData.setDirty();
                    Simukraft.LOGGER.info("[CreateCity] City chunk data marked as dirty for saving");
                    
                    UUID cityId = cityInfo.getCityId();

                    // 市长名称已在创建时设置，聊天系统已拆分，群组初始化逻辑移除。
                    
                    // 创建第一个市民NPC
                    CustomEntity npc = new CustomEntity(ModEntities.CUSTOM_ENTITY.get(), level);
                    npc.setPos(message.cityCorePos.getX() + 0.5, message.cityCorePos.getY() + 1, message.cityCorePos.getZ() + 0.5);
                    
                    // 设置NPC属�?
                    npc.setCityId(cityId);
                    npc.initializeName();
                    
                    // 添加NPC到城�?
                    cityData.addCitizenToCity(cityId, npc.getUUID());
                    
                    // 生成NPC
                    level.addFreshEntity(npc);
                    
                    // 发送成功消息
                    player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("message.simukraft.city_created", cityName));
                    player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("message.simukraft.first_citizen_joined", npc.getCustomName()));
                } else {
                    // 玩家已有城市，发送错误消�?
                    player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("message.simukraft.already_has_city"));
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }
}