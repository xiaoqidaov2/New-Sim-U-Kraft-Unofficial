package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.client.gui.BankLoanScreen;
import com.xiaoliang.simukraft.world.CityLoanService;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

@SuppressWarnings("null")
public class BankFinanceInfoResponsePacket {
    private final BlockPos controlBoxPos;
    private final CityLoanService.FinanceSnapshot snapshot;

    public BankFinanceInfoResponsePacket(BlockPos controlBoxPos, CityLoanService.FinanceSnapshot snapshot) {
        this.controlBoxPos = controlBoxPos;
        this.snapshot = snapshot;
    }

    public BankFinanceInfoResponsePacket(FriendlyByteBuf buf) {
        this.controlBoxPos = buf.readBlockPos();
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
        buf.writeBlockPos(controlBoxPos);
        buf.writeDouble(snapshot.cityFunds());
        buf.writeDouble(snapshot.outstandingDebt());
        buf.writeDouble(snapshot.maxLoanAmount());
        buf.writeDouble(snapshot.availableLoanAmount());
        buf.writeDouble(snapshot.dailyInterestRate());
        buf.writeInt(snapshot.cityLevel());
    }

    public static BankFinanceInfoResponsePacket decode(FriendlyByteBuf buf) {
        return new BankFinanceInfoResponsePacket(buf);
    }

    public static void handle(BankFinanceInfoResponsePacket message, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.screen instanceof BankLoanScreen screen) {
                screen.updateFinanceInfo(message.controlBoxPos, message.snapshot);
            }
        });
        context.get().setPacketHandled(true);
    }
}
