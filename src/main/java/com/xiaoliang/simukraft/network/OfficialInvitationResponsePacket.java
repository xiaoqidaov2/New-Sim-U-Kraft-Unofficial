package com.xiaoliang.simukraft.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import com.xiaoliang.simukraft.world.OfficialInvitationService;

import java.util.UUID;
import java.util.function.Supplier;

@SuppressWarnings("null")
public class OfficialInvitationResponsePacket {
    private final UUID invitationId;
    private final boolean accepted;

    public OfficialInvitationResponsePacket(UUID invitationId, boolean accepted) {
        this.invitationId = invitationId;
        this.accepted = accepted;
    }

    public OfficialInvitationResponsePacket(FriendlyByteBuf buf) {
        this.invitationId = buf.readUUID();
        this.accepted = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(invitationId);
        buf.writeBoolean(accepted);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            OfficialInvitationService.handleResponse(player, invitationId, accepted);
        });
        ctx.get().setPacketHandled(true);
    }

}
