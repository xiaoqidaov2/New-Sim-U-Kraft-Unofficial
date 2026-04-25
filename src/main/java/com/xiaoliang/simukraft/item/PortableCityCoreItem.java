package com.xiaoliang.simukraft.item;

import com.xiaoliang.simukraft.network.CityStatusResponsePacket;
import com.xiaoliang.simukraft.network.NetworkManager;
import com.xiaoliang.simukraft.world.CityData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.UUID;

/**
 * 便携城市核心物品
 * 可以远程打开城市核心界面
 */
@SuppressWarnings("null")
public class PortableCityCoreItem extends Item {

    public PortableCityCoreItem() {
        super(new Item.Properties()
                .stacksTo(1)
                .durability(0));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (level.isClientSide) {
            return InteractionResultHolder.success(stack);
        }

        // 服务器端处理
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.success(stack);
        }

        ServerLevel serverLevel = serverPlayer.serverLevel();
        CityData cityData = CityData.get(serverLevel);
        UUID playerUUID = player.getUUID();
        String playerName = player.getName().getString();

        // 统一走登录自修复后的城市归属，避免离线档因旧映射导致便携核心误判无权限
        UUID playerCityId = cityData.refreshPlayerCityAccess(serverPlayer);
        CityData.CityInfo cityInfo = null;
        if (playerCityId != null) {
            CityData.CityInfo city = cityData.getCity(playerCityId);
            if (city != null && (city.isMayor(playerUUID) || city.isOfficial(playerName))) {
                cityInfo = city;
            }
        }
        
        if (cityInfo == null) {
            player.displayClientMessage(
                    Component.translatable("message.portable_city_core.no_city"),
                    true
            );
            return InteractionResultHolder.fail(stack);
        }

        BlockPos cityCorePos = cityInfo.getCityCorePos();
        if (cityCorePos == null) {
            player.displayClientMessage(
                    Component.translatable("message.portable_city_core.no_core"),
                    true
            );
            return InteractionResultHolder.fail(stack);
        }

        // 直接发送响应包给客户端打开界面
        // 玩家是市长，有城市，有数据
        String cityName = cityInfo.getCityName();
        int population = cityInfo.getCitizenIds().size();
        String mayorName = cityInfo.getMayorName();

        CityStatusResponsePacket responsePacket = new CityStatusResponsePacket(
                true,   // hasCity - 玩家有城市
                true,   // isOwner - 是城市所有者
                true,   // hasData - 城市有数据
                cityCorePos,
                cityName,
                population,
                mayorName
        );
        NetworkManager.sendToPlayer(responsePacket, serverPlayer);

        return InteractionResultHolder.success(stack);
    }

}
