package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.client.gui.CityFinanceScreen;
import com.xiaoliang.simukraft.world.CityLoanService;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

@SuppressWarnings("null")
public class CityFinanceInfoResponsePacket {
    private final BlockPos cityCorePos;
    private final CityLoanService.FinanceSnapshot snapshot;

    public CityFinanceInfoResponsePacket(BlockPos cityCorePos, CityLoanService.FinanceSnapshot snapshot) {
        this.cityCorePos = cityCorePos;
        this.snapshot = snapshot;
    }

    public CityFinanceInfoResponsePacket(FriendlyByteBuf buf) {
        this.cityCorePos = buf.readBlockPos();
        this.snapshot = new CityLoanService.FinanceSnapshot(
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readInt()
        );
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(cityCorePos);
        buf.writeDouble(snapshot.cityFunds());
        buf.writeDouble(snapshot.outstandingDebt());
        buf.writeDouble(snapshot.maxLoanAmount());
        buf.writeDouble(snapshot.availableLoanAmount());
        buf.writeDouble(snapshot.dailyInterestRate());
        buf.writeInt(snapshot.cityLevel());
    }

    public static CityFinanceInfoResponsePacket decode(FriendlyByteBuf buf) {
        return new CityFinanceInfoResponsePacket(buf);
    }

    public static void handle(CityFinanceInfoResponsePacket message, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.screen instanceof CityFinanceScreen screen) {
                screen.updateFinanceInfo(message.cityCorePos, message.snapshot);
            }
        });
        context.get().setPacketHandled(true);
    }
}
