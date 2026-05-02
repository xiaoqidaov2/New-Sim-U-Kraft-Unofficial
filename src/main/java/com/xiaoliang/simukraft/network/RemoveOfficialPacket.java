package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.world.CityData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

@SuppressWarnings("null")
public class RemoveOfficialPacket {
    private final BlockPos cityCorePos;
    private final String targetPlayerName;

    public RemoveOfficialPacket(BlockPos cityCorePos, String targetPlayerName) {
        this.cityCorePos = cityCorePos;
        this.targetPlayerName = targetPlayerName;
    }

    public RemoveOfficialPacket(FriendlyByteBuf buf) {
        this.cityCorePos = buf.readBlockPos();
        this.targetPlayerName = buf.readUtf(32767);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(cityCorePos);
        buf.writeUtf(targetPlayerName);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer player = context.get().getSender();
            if (player != null) {
                ServerLevel level = player.serverLevel();
                CityData cityData = CityData.get(level);
                CityData.CityInfo city = cityData.getCityByCorePos(cityCorePos);

                if (city != null) {
                    // 检查发送者是否是市长
                    if (!city.isMayor(player.getName().getString())) {
                        player.displayClientMessage(
                            Component.translatable("message.city_official.not_mayor").withStyle(ChatFormatting.RED),
                            false
                        );
                        return;
                    }

                    // 不能移除市长自己
                    if (city.isMayor(targetPlayerName)) {
                        player.displayClientMessage(
                            Component.translatable("message.city_official.cannot_remove_mayor").withStyle(ChatFormatting.RED),
                            false
                        );
                        return;
                    }

                    // 检查目标玩家是否是官员
                    if (!city.isOfficial(targetPlayerName)) {
                        player.displayClientMessage(
                            Component.translatable("message.city_official.not_official").withStyle(ChatFormatting.YELLOW),
                            false
                        );
                        return;
                    }

                    // 移除官员（使用玩家名）
                    boolean success = cityData.removeOfficialFromCity(city.getCityId(), targetPlayerName);
                    if (success) {
                        player.displayClientMessage(
                            Component.translatable("message.city_official.removed_success", targetPlayerName).withStyle(ChatFormatting.GREEN),
                            false
                        );

                        // 通知被移除的玩家（如果在线）
                        ServerPlayer targetPlayer = level.getServer().getPlayerList().getPlayerByName(targetPlayerName);
                        if (targetPlayer != null) {
                            targetPlayer.displayClientMessage(
                                Component.translatable("message.city_official.no_longer_official", city.getCityName()).withStyle(ChatFormatting.YELLOW),
                                false
                            );
                        }

                        // 刷新官员列表
                        OfficialListRequestPacket refreshPacket = new OfficialListRequestPacket(cityCorePos);
                        refreshPacket.handle(context);
                    } else {
                        player.displayClientMessage(
                            Component.translatable("message.city_official.remove_failed").withStyle(ChatFormatting.RED),
                            false
                        );
                    }
                }
            }
        });
        context.get().setPacketHandled(true);
    }
}
