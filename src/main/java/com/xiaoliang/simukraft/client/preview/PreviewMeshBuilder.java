package com.xiaoliang.simukraft.client.preview;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraftforge.client.model.data.ModelData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 将 SchematicBlockData 列表一次性构建为 GPU VertexBuffer（VBO），
 * 按 RenderType 分为 solid / cutout / translucent 三个 VBO。
 * 构建时执行面级剔除（邻居为实心则跳过该面）并处理方块着色。
 * ENTITYBLOCK_ANIMATED 方块（床、箱子等）放入单独列表，渲染时回退到 renderSingleBlock。
 */
@SuppressWarnings({"null", "deprecation"})
public class PreviewMeshBuilder {

    private static final int FULL_BRIGHT = 15728880;
    private static final float[] UNIT_BRIGHTNESS = {1.0f, 1.0f, 1.0f, 1.0f};
    private static final int[] FULL_BRIGHT_ARRAY = {FULL_BRIGHT, FULL_BRIGHT, FULL_BRIGHT, FULL_BRIGHT};

    public static PreviewMesh build(List<SchematicBlockData> allBlocks) {
        if (allBlocks == null || allBlocks.isEmpty()) {
            return PreviewMesh.EMPTY;
        }

        Minecraft mc = Minecraft.getInstance();

        Map<BlockPos, BlockState> stateMap = new HashMap<>(allBlocks.size());
        List<SchematicBlockData> modelBlocks = new ArrayList<>();
        List<SchematicBlockData> entityBlocks = new ArrayList<>();

        for (SchematicBlockData block : allBlocks) {
            BlockState state = block.blockState();
            if (state.isAir()) continue;
            stateMap.put(block.pos(), state);

            RenderShape shape = state.getRenderShape();
            if (shape == RenderShape.MODEL) {
                modelBlocks.add(block);
            } else if (shape == RenderShape.ENTITYBLOCK_ANIMATED) {
                entityBlocks.add(block);
            }
        }

        if (modelBlocks.isEmpty()) {
            return new PreviewMesh(null, null, null, entityBlocks);
        }

        BufferBuilder solidBuf = new BufferBuilder(256 * 1024);
        BufferBuilder cutoutBuf = new BufferBuilder(64 * 1024);
        BufferBuilder translucentBuf = new BufferBuilder(64 * 1024);

        solidBuf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
        cutoutBuf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
        translucentBuf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);

        int solidQuads = 0, cutoutQuads = 0, translucentQuads = 0;
        PoseStack buildPose = new PoseStack();
        RandomSource random = RandomSource.create(42L);
        PreviewBlockGetter previewBlockGetter = new PreviewBlockGetter(stateMap.keySet(), stateMap);

        for (SchematicBlockData block : modelBlocks) {
            BlockState state = block.blockState();
            BlockPos pos = block.pos();
            BakedModel model = mc.getBlockRenderer().getBlockModel(state);

            RenderType primaryRT = RenderType.solid();
            for (RenderType rt : model.getRenderTypes(state, random, ModelData.EMPTY)) {
                primaryRT = rt;
                break;
            }

            int cat = categorize(primaryRT);
            BufferBuilder target = cat == 1 ? cutoutBuf : cat == 2 ? translucentBuf : solidBuf;

            buildPose.pushPose();
            buildPose.translate(pos.getX(), pos.getY(), pos.getZ());
            PoseStack.Pose entry = buildPose.last();

            boolean hasVisibleDirectionalFace = false;
            for (Direction dir : Direction.values()) {
                if (!shouldRenderPreviewFace(state, previewBlockGetter, pos, dir)) {
                    continue;
                }

                hasVisibleDirectionalFace = true;
                List<BakedQuad> quads = model.getQuads(state, dir, random);
                for (BakedQuad quad : quads) {
                    writeQuad(target, entry, quad, state, mc);
                    if (cat == 0) solidQuads++;
                    else if (cat == 1) cutoutQuads++;
                    else translucentQuads++;
                }
            }

            List<BakedQuad> generalQuads = model.getQuads(state, null, random);
            if (hasVisibleDirectionalFace || shouldRenderGeneralQuads(state, previewBlockGetter, pos)) {
                for (BakedQuad quad : generalQuads) {
                    writeQuad(target, entry, quad, state, mc);
                    if (cat == 0) solidQuads++;
                    else if (cat == 1) cutoutQuads++;
                    else translucentQuads++;
                }
            }

            buildPose.popPose();
        }

        VertexBuffer solidVBO = upload(solidBuf, solidQuads);
        VertexBuffer cutoutVBO = upload(cutoutBuf, cutoutQuads);
        VertexBuffer translucentVBO = upload(translucentBuf, translucentQuads);

        return new PreviewMesh(solidVBO, cutoutVBO, translucentVBO, entityBlocks);
    }

    private static void writeQuad(BufferBuilder buf, PoseStack.Pose entry, BakedQuad quad,
                                   BlockState state, Minecraft mc) {
        float r = 1f, g = 1f, b = 1f;
        if (quad.isTinted()) {
            try {
                int color = mc.getBlockColors().getColor(state, null, null, quad.getTintIndex());
                r = (color >> 16 & 0xFF) / 255f;
                g = (color >> 8 & 0xFF) / 255f;
                b = (color & 0xFF) / 255f;
            } catch (Exception ignored) {
            }
        }
        buf.putBulkData(entry, quad, UNIT_BRIGHTNESS, r, g, b,
                FULL_BRIGHT_ARRAY, OverlayTexture.NO_OVERLAY, true);
    }

    private static int categorize(RenderType rt) {
        if (rt == RenderType.cutout() || rt == RenderType.cutoutMipped()) return 1;
        if (rt == RenderType.translucent() || rt == RenderType.tripwire()) return 2;
        return 0;
    }

    private static boolean shouldRenderPreviewFace(BlockState state, BlockGetter previewBlockGetter,
                                                   BlockPos pos, Direction direction) {
        BlockPos neighborPos = pos.relative(direction);
        if (Block.shouldRenderFace(state, previewBlockGetter, pos, direction, neighborPos)) {
            return true;
        }

        BlockState neighborState = previewBlockGetter.getBlockState(neighborPos);
        return neighborState.isAir() || !neighborState.canOcclude();
    }

    private static boolean shouldRenderGeneralQuads(BlockState state, BlockGetter previewBlockGetter, BlockPos pos) {
        if (!state.canOcclude()) {
            return true;
        }

        for (Direction direction : Direction.values()) {
            if (shouldRenderPreviewFace(state, previewBlockGetter, pos, direction)) {
                return true;
            }
        }
        return false;
    }

    private static VertexBuffer upload(BufferBuilder buf, int quadCount) {
        BufferBuilder.RenderedBuffer rendered = buf.end();
        if (quadCount == 0) {
            rendered.release();
            return null;
        }
        VertexBuffer vbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
        vbo.bind();
        vbo.upload(rendered);
        VertexBuffer.unbind();
        return vbo;
    }

    private static final class PreviewBlockGetter implements BlockGetter {
        private final int minY;
        private final int maxY;
        private final Map<BlockPos, BlockState> states;

        private PreviewBlockGetter(Collection<BlockPos> positions, Map<BlockPos, BlockState> states) {
            this.states = states;
            int localMinY = 0;
            int localMaxY = 0;
            boolean first = true;
            for (BlockPos pos : positions) {
                if (first) {
                    localMinY = pos.getY();
                    localMaxY = pos.getY();
                    first = false;
                    continue;
                }
                localMinY = Math.min(localMinY, pos.getY());
                localMaxY = Math.max(localMaxY, pos.getY());
            }
            this.minY = localMinY;
            this.maxY = localMaxY;
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return states.getOrDefault(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            return maxY + 1;
        }

        @Override
        public int getMinBuildHeight() {
            return minY;
        }
    }
}
