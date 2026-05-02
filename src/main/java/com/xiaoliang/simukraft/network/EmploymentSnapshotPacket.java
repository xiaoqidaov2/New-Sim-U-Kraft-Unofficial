package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.employment.client.WorkBlockHireClientCache;
import com.xiaoliang.simukraft.employment.domain.EmploymentAssignment;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.Optional;
import java.util.function.Supplier;

@SuppressWarnings("null")
public class EmploymentSnapshotPacket {
    private final boolean success;
    private final String code;
    private final String message;
    private final EmploymentAssignment assignment;
    private final String dimensionId;
    private final BlockPos workplacePos;

    public EmploymentSnapshotPacket(boolean success, String code, String message, EmploymentAssignment assignment, String dimensionId, BlockPos workplacePos) {
        this.success = success;
        this.code = code;
        this.message = message;
        this.assignment = assignment;
        this.dimensionId = dimensionId;
        this.workplacePos = workplacePos;
    }

    public EmploymentSnapshotPacket(FriendlyByteBuf buf) {
        this.success = buf.readBoolean();
        this.code = buf.readUtf();
        this.message = buf.readUtf();
        this.dimensionId = buf.readUtf();
        this.workplacePos = buf.readBlockPos();
        this.assignment = buf.readBoolean() ? EmploymentPacketCodec.readAssignment(buf) : null;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(success);
        buf.writeUtf(code != null ? code : "");
        buf.writeUtf(message != null ? message : "");
        buf.writeUtf(dimensionId != null ? dimensionId : "minecraft:overworld");
        buf.writeBlockPos(workplacePos != null ? workplacePos : BlockPos.ZERO);
        buf.writeBoolean(assignment != null);
        if (assignment != null) {
            EmploymentPacketCodec.writeAssignment(buf, assignment);
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> this::applyClient));
        ctx.get().setPacketHandled(true);
    }

    private void applyClient() {
        if (assignment != null) {
            if (assignment.isAssigned()) {
                WorkBlockHireClientCache.upsert(assignment);
            } else {
                WorkBlockHireClientCache.remove(assignment);
            }
            return;
        }
        WorkBlockHireClientCache.removeByWorkplace(dimensionId, workplacePos);
    }

    public Optional<EmploymentAssignment> getAssignment() {
        return Optional.ofNullable(assignment);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}

