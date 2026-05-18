package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.client.gui.CityFinanceScreen;
import com.xiaoliang.simukraft.world.CityInvestmentService;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

@SuppressWarnings("null")
public class CityFinanceInfoResponsePacket {
    private final BlockPos cityCorePos;
    private final CityInvestmentService.CityFinanceSnapshot snapshot;

    public CityFinanceInfoResponsePacket(BlockPos cityCorePos, CityInvestmentService.CityFinanceSnapshot snapshot) {
        this.cityCorePos = cityCorePos;
        this.snapshot = snapshot;
    }

    public CityFinanceInfoResponsePacket(FriendlyByteBuf buf) {
        this.cityCorePos = buf.readBlockPos();
        double cityFunds = buf.readDouble();
        double outstandingDebt = buf.readDouble();
        double maxLoanAmount = buf.readDouble();
        double availableLoanAmount = buf.readDouble();
        double dailyInterestRate = buf.readDouble();
        int cityLevel = buf.readInt();
        int currentDay = buf.readVarInt();

        int productCount = buf.readVarInt();
        List<CityInvestmentService.InvestmentProductSnapshot> products = new ArrayList<>(productCount);
        for (int i = 0; i < productCount; i++) {
            products.add(new CityInvestmentService.InvestmentProductSnapshot(
                    buf.readUtf(64),
                    buf.readUtf(256),
                    buf.readBoolean(),
                    buf.readVarInt(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readDouble()
            ));
        }

        int positionCount = buf.readVarInt();
        List<CityInvestmentService.InvestmentPositionSnapshot> positions = new ArrayList<>(positionCount);
        for (int i = 0; i < positionCount; i++) {
            positions.add(new CityInvestmentService.InvestmentPositionSnapshot(
                    buf.readUUID(),
                    buf.readUtf(64),
                    buf.readUtf(256),
                    buf.readBoolean(),
                    buf.readDouble(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readVarInt()
            ));
        }

        this.snapshot = new CityInvestmentService.CityFinanceSnapshot(
                cityFunds,
                outstandingDebt,
                maxLoanAmount,
                availableLoanAmount,
                dailyInterestRate,
                cityLevel,
                currentDay,
                products,
                positions
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
        buf.writeVarInt(snapshot.currentDay());

        buf.writeVarInt(snapshot.products().size());
        for (CityInvestmentService.InvestmentProductSnapshot product : snapshot.products()) {
            buf.writeUtf(product.productId(), 64);
            buf.writeUtf(product.nameKey(), 256);
            buf.writeBoolean(product.stable());
            buf.writeVarInt(product.cycleDays());
            buf.writeDouble(product.successChance());
            buf.writeDouble(product.positiveMinRate());
            buf.writeDouble(product.positiveMaxRate());
            buf.writeDouble(product.negativeMinRate());
            buf.writeDouble(product.negativeMaxRate());
        }

        buf.writeVarInt(snapshot.positions().size());
        for (CityInvestmentService.InvestmentPositionSnapshot position : snapshot.positions()) {
            buf.writeUUID(position.positionId());
            buf.writeUtf(position.productId(), 64);
            buf.writeUtf(position.nameKey(), 256);
            buf.writeBoolean(position.stable());
            buf.writeDouble(position.principal());
            buf.writeVarInt(position.startDay());
            buf.writeVarInt(position.maturityDay());
            buf.writeVarInt(position.remainingDays());
        }
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
