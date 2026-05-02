package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.entity.CustomEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

@SuppressWarnings("null")
public class FeedNPCPacket {
    private final UUID npcUuid;
    private final boolean offhand;

    public FeedNPCPacket(UUID npcUuid, boolean offhand) {
        this.npcUuid = npcUuid;
        this.offhand = offhand;
    }

    public static void encode(FeedNPCPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.npcUuid);
        buf.writeBoolean(packet.offhand);
    }

    public static FeedNPCPacket decode(FriendlyByteBuf buf) {
        return new FeedNPCPacket(buf.readUUID(), buf.readBoolean());
    }

    public static void handle(FeedNPCPacket packet, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer player = context.get().getSender();
            if (player == null) return;

            var level = player.serverLevel();
            var npcEntity = level.getEntity(packet.npcUuid);
            if (!(npcEntity instanceof CustomEntity npc) || !npc.isAlive()) {
                return;
            }

            if (player.distanceToSqr(npc) > 36.0) {
                return;
            }

            InteractionHand hand = packet.offhand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
            ItemStack stack = player.getItemInHand(hand);
            if (!stack.isEdible()) {
                return;
            }

            var food = stack.getFoodProperties(player);
            if (food == null || food.getNutrition() <= 0) {
                return;
            }

            int nutrition = food.getNutrition();
            npc.addHunger(nutrition);

            if (!player.getAbilities().instabuild) {
                stack.shrink(1);
            }

            player.sendSystemMessage(Component.translatable("message.simukraft.npc.fed", npc.getFullName()));
        });

        context.get().setPacketHandled(true);
    }
}

