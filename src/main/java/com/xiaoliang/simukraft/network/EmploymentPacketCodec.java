package com.xiaoliang.simukraft.network;

import com.xiaoliang.simukraft.employment.domain.EmploymentAssignment;
import com.xiaoliang.simukraft.employment.domain.EmploymentStatus;
import com.xiaoliang.simukraft.employment.domain.JobType;
import com.xiaoliang.simukraft.employment.domain.WorkBlockType;
import net.minecraft.network.FriendlyByteBuf;

@SuppressWarnings("null")
final class EmploymentPacketCodec {
    private EmploymentPacketCodec() {
    }

    static void writeAssignment(FriendlyByteBuf buf, EmploymentAssignment assignment) {
        buf.writeUUID(assignment.npcUuid());
        buf.writeUtf(assignment.dimensionId());
        buf.writeBlockPos(assignment.workplacePos());
        buf.writeUtf(assignment.workBlockType().name());
        buf.writeUtf(assignment.jobType().name());
        buf.writeUtf(assignment.status().name());
        buf.writeLong(assignment.version());
        buf.writeLong(assignment.updatedAtEpochMs());
    }

    static EmploymentAssignment readAssignment(FriendlyByteBuf buf) {
        return new EmploymentAssignment(
                buf.readUUID(),
                buf.readUtf(),
                buf.readBlockPos(),
                WorkBlockType.valueOf(buf.readUtf()),
                JobType.valueOf(buf.readUtf()),
                EmploymentStatus.valueOf(buf.readUtf()),
                buf.readLong(),
                buf.readLong()
        );
    }
}

