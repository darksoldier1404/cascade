package dev.chocoboy.cascade.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexSorting;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;

// owns the staging buffer a frame's grouped work is built into, so every render type becomes one append and
// one draw without touching vanilla's shared feature buffers. 26.2 removed the immediate draw: types are
// prepared and their vertices staged first, one upload moves the whole frame to the gpu, then each type
// draws from its slice. the buffer recycles its gpu allocations through a fence, so steady state never
// reallocates
final class CascadeBatcher {

    // fits a few thousand textured quads up front; the staging arena grows on its own if a frame needs more
    private static final int INITIAL_BYTES = 786432;

    private static StagedVertexBuffer staging;

    private CascadeBatcher() {
    }

    private record Staged(PreparedRenderType type, StagedVertexBuffer.Draw draw) {
    }

    static void draw(List<RenderType> order, Map<RenderType, List<VfxRenderQueue.Submission>> groups,
            VfxRenderQueue.FailureSink failures) {
        if (order.isEmpty()) {
            return;
        }
        if (staging == null) {
            staging = new StagedVertexBuffer(() -> "Cascade", INITIAL_BYTES);
        }
        List<Staged> staged = new ArrayList<>(order.size());
        try {
            for (RenderType type : order) {
                stage(type, groups.get(type), failures, staged);
            }
            staging.upload();
            for (Staged s : staged) {
                StagedVertexBuffer.ExecuteInfo info = staging.getExecuteInfo(s.draw());
                if (info != null) {
                    s.type().drawFromBuffer(info);
                }
            }
        } finally {
            staging.endFrame();
        }
    }

    private static void stage(RenderType type, List<VfxRenderQueue.Submission> submissions,
            VfxRenderQueue.FailureSink failures, List<Staged> out) {
        // the geometry is authored camera relative, so distance to origin is distance to the camera
        VertexSorting sorting = type.sortOnUpload() ? RenderSystem.getProjectionType().vertexSorting() : null;
        StagedVertexBuffer.Draw draw = staging.appendDraw(type.format(), type.primitiveTopology(), sorting);
        VertexConsumer builder = staging.getVertexBuilder(draw);
        for (VfxRenderQueue.Submission s : submissions) {
            try {
                s.writer().write(builder);
            } catch (RuntimeException e) {
                failures.failed(s.owner(), e);
            }
        }
        out.add(new Staged(type.prepare(), draw));
    }
}
