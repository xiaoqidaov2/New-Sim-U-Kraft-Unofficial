package com.xiaoliang.simukraft.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 通用工作方块雇佣状态同步网络包
 * 服务器 -> 客户端
 * 用于同步各种工作方块的雇佣数据给客户端
 */
public class SyncWorkBlockHireStatusPacket {
    private final BlockPos workBlockPos;
    private final String workBlockType;  // 工作方块类型: wool_farm, beef_farm, meat_shop等
    private final UUID employeeUuid;     // null表示没有雇佣员工
    private final String employeeName;
    private final String jobType;        // 职业类型: shepherd, butcher等
    private final String buildingFileName; // 建筑文件名（用于加载配置）

    public SyncWorkBlockHireStatusPacket(BlockPos pos, String type, UUID employeeUuid, String employeeName) {
        this(pos, type, employeeUuid, employeeName, "worker", "");
    }

    public SyncWorkBlockHireStatusPacket(BlockPos pos, String type, UUID employeeUuid, String employeeName, String jobType) {
        this(pos, type, employeeUuid, employeeName, jobType, "");
    }

    public SyncWorkBlockHireStatusPacket(BlockPos pos, String type, UUID employeeUuid, String employeeName, String jobType, String buildingFileName) {
        this.workBlockPos = pos;
        this.workBlockType = type;
        this.employeeUuid = employeeUuid;
        this.employeeName = employeeName;
        this.jobType = jobType != null ? jobType : "worker";
        this.buildingFileName = buildingFileName != null ? buildingFileName : "";
    }

    public SyncWorkBlockHireStatusPacket(FriendlyByteBuf buf) {
        this.workBlockPos = Objects.requireNonNull(buf.readBlockPos());
        this.workBlockType = Objects.requireNonNull(buf.readUtf());
        this.employeeUuid = buf.readBoolean() ? Objects.requireNonNull(buf.readUUID()) : null;
        this.employeeName = Objects.requireNonNull(buf.readUtf());
        this.jobType = Objects.requireNonNull(buf.readUtf());
        this.buildingFileName = Objects.requireNonNull(buf.readUtf());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(Objects.requireNonNull(workBlockPos));
        buf.writeUtf(Objects.requireNonNull(workBlockType));
        buf.writeBoolean(employeeUuid != null);
        if (employeeUuid != null) {
            buf.writeUUID(employeeUuid);
        }
        buf.writeUtf(employeeName != null ? employeeName : "");
        buf.writeUtf(jobType != null ? jobType : "worker");
        buf.writeUtf(buildingFileName != null ? buildingFileName : "");
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
            if (minecraft.level == null) return;

            // 根据工作方块类型更新对应的客户端数据
            switch (workBlockType) {
                case "commercial":
                    if (employeeUuid != null) {
                        com.xiaoliang.simukraft.client.gui.CommercialClientData.setHiredEmployee(workBlockPos, employeeUuid, jobType, buildingFileName);
                    } else {
                        com.xiaoliang.simukraft.client.gui.CommercialClientData.clearHiredEmployee(workBlockPos);
                    }
                    // 设置建筑文件名（即使未雇佣也需要）
                    if (buildingFileName != null && !buildingFileName.isEmpty()) {
                        com.xiaoliang.simukraft.client.gui.CommercialClientData.setBuildingFileName(workBlockPos, buildingFileName);
                    }
                    // 设置职业类型（即使未雇佣也需要，用于正确显示雇佣按钮）
                    if (jobType != null && !jobType.isEmpty() && !"worker".equals(jobType)) {
                        com.xiaoliang.simukraft.client.gui.CommercialClientData.setJobType(workBlockPos, jobType);
                    }
                    if (minecraft.screen instanceof com.xiaoliang.simukraft.client.gui.CommercialControlBoxScreen screen) {
                        if (screen.getControlBoxPos().equals(workBlockPos)) {
                            screen.refreshButtonStates();
                        }
                    }
                    break;

                case "industrial":
                    if (employeeUuid != null) {
                        com.xiaoliang.simukraft.client.gui.IndustrialClientData.setHiredEmployee(workBlockPos, employeeUuid, jobType, buildingFileName);
                    } else {
                        com.xiaoliang.simukraft.client.gui.IndustrialClientData.clearHiredEmployee(workBlockPos);
                    }
                    // 设置建筑文件名（即使未雇佣也需要）
                    if (buildingFileName != null && !buildingFileName.isEmpty()) {
                        com.xiaoliang.simukraft.client.gui.IndustrialClientData.setBuildingFileName(workBlockPos, buildingFileName);
                    }
                    if (minecraft.screen instanceof com.xiaoliang.simukraft.client.gui.IndustrialControlBoxLDLibScreen screen) {
                        if (screen.getControlBoxPos().equals(workBlockPos)) {
                            screen.refreshButtonStates();
                        }
                    }
                    break;

                default:
                    return;
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
