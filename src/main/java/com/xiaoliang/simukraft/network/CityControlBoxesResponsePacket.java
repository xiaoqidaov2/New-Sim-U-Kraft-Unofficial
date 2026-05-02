package com.xiaoliang.simukraft.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * S→C 城市控制盒数据响应
 * 服务端返回城市所有控制盒的位置和名称信息
 */
@SuppressWarnings("null")
public class CityControlBoxesResponsePacket {
    private final List<ControlBoxData> controlBoxes;

    public CityControlBoxesResponsePacket(List<RequestCityControlBoxesPacket.ControlBoxInfo> controlBoxes) {
        this.controlBoxes = new ArrayList<>();
        for (RequestCityControlBoxesPacket.ControlBoxInfo info : controlBoxes) {
            this.controlBoxes.add(new ControlBoxData(info.position, info.type, info.buildingName));
        }
    }

    public CityControlBoxesResponsePacket(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        this.controlBoxes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            BlockPos pos = buf.readBlockPos();
            String type = buf.readUtf();
            String buildingName = buf.readUtf();
            this.controlBoxes.add(new ControlBoxData(pos, type, buildingName));
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(controlBoxes.size());
        for (ControlBoxData data : controlBoxes) {
            buf.writeBlockPos(data.position);
            buf.writeUtf(data.type);
            buf.writeUtf(data.buildingName != null ? data.buildingName : "");
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // 通知当前打开的界面
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.screen instanceof CityControlBoxesReceiver receiver) {
                receiver.onCityControlBoxesReceived(this.controlBoxes);
            } else {
                // 使用静态实例通知 CityMapCanvas
                com.xiaoliang.simukraft.client.gui.map.CityMapCanvas canvas = com.xiaoliang.simukraft.client.gui.map.CityMapCanvas.getCurrentInstance();
                if (canvas != null) {
                    canvas.onCityControlBoxesReceived(this.controlBoxes);
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }

    public List<ControlBoxData> getControlBoxes() {
        return controlBoxes;
    }

    /**
     * 控制盒数据
     */
    public static class ControlBoxData {
        public final BlockPos position;
        public final String type;
        public final String buildingName;

        public ControlBoxData(BlockPos position, String type, String buildingName) {
            this.position = position;
            this.type = type;
            this.buildingName = buildingName != null ? buildingName : "";
        }
    }

    /**
     * GUI 实现此接口以接收控制盒数据
     */
    public interface CityControlBoxesReceiver {
        void onCityControlBoxesReceived(List<ControlBoxData> controlBoxes);
    }
}
