package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.client.gui.EmployeeInfoScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraftforge.network.NetworkEvent;

import java.util.*;
import java.util.function.Supplier;

/**
 * 雇员列表响应数据包
 * 服务器发送所有雇佣数据给客户端
 */
@SuppressWarnings("null")
public class EmployeeListResponsePacket {
    private final Map<UUID, EmployeeListRequestPacket.EmployeeData> employees;

    public EmployeeListResponsePacket(Map<UUID, EmployeeListRequestPacket.EmployeeData> employees) {
        this.employees = employees;
    }

    public EmployeeListResponsePacket(FriendlyByteBuf buf) {
        this.employees = new HashMap<>();
        int size = buf.readInt();
        for (int i = 0; i < size; i++) {
            UUID uuid = buf.readUUID();
            String job = buf.readUtf();
            BlockPos pos = buf.readBlockPos();
            String workplaceType = buf.readUtf();
            String buildingFileName = buf.readUtf();
            // 如果 buildingFileName 是空字符串，设为 null
            if (buildingFileName.isEmpty()) {
                buildingFileName = null;
            }
            employees.put(uuid, new EmployeeListRequestPacket.EmployeeData(uuid, job, pos, workplaceType, buildingFileName));
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeInt(employees.size());
        for (var entry : employees.entrySet()) {
            var data = entry.getValue();
            buf.writeUUID(data.uuid);
            buf.writeUtf(data.job);
            buf.writeBlockPos(data.workplacePos);
            buf.writeUtf(data.workplaceType);
            buf.writeUtf(data.buildingFileName != null ? data.buildingFileName : "");
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // 在客户端更新EmployeeInfoScreen的数据
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.screen instanceof EmployeeInfoScreen screen) {
                screen.updateEmployeeData(employees);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    public Map<UUID, EmployeeListRequestPacket.EmployeeData> getEmployees() {
        return employees;
    }
}
