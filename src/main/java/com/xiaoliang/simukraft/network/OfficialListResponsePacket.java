package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.client.gui.CityOfficialScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

@SuppressWarnings("null")
public class OfficialListResponsePacket {
    private final BlockPos cityCorePos;
    private final List<OfficialInfo> officials;

    public OfficialListResponsePacket(BlockPos cityCorePos, List<OfficialInfo> officials) {
        this.cityCorePos = cityCorePos;
        this.officials = officials;
    }

    public OfficialListResponsePacket(FriendlyByteBuf buf) {
        this.cityCorePos = buf.readBlockPos();
        int count = buf.readVarInt();
        this.officials = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String playerName = buf.readUtf(32767);
            boolean isMayor = buf.readBoolean();
            officials.add(new OfficialInfo(playerName, isMayor));
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(cityCorePos);
        buf.writeVarInt(officials.size());
        for (OfficialInfo info : officials) {
            buf.writeUtf(info.playerName());
            buf.writeBoolean(info.isMayor());
        }
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.screen instanceof CityOfficialScreen screen) {
                screen.updateOfficialList(officials);
            }
        });
        context.get().setPacketHandled(true);
    }

    // 使用玩家名而非UUID
    public record OfficialInfo(String playerName, boolean isMayor) {}
}
