package dev.chocoboy.cascade.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// the sdf volume backend: one raymarching pipeline and one shared uniform buffer. on 26.1 the volume
// draws through a raw render pass against the main target's color and depth (or the bloom capture's,
// when its overrides are up), with all per-volume data in a std140 block; the gl backend executes
// passes in order, so one shared buffer rewritten before each draw serves every volume in a frame.
// any failure still disables sdf volumes for the session and live effects remove themselves. public
// because the loader's pipeline registration lives in another package
public final class SdfFx {

    static final int MAX_COLOR_STOPS = 8;
    // mat4 + five vec4-sized fields + four vec4[8] arrays, in std140 layout
    static final int CONFIG_BYTES = 64 + 5 * 16 + 4 * 8 * 16;

    private static final BindGroupLayout CONFIG_LAYOUT =
            BindGroupLayout.builder().withUniform("SdfConfig", UniformType.UNIFORM_BUFFER).build();

    public static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("cascade", "sdf_volume"))
            .withVertexShader(Identifier.fromNamespaceAndPath("cascade", "core/sdf_volume"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("cascade", "core/sdf_volume"))
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withBindGroupLayout(CONFIG_LAYOUT)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(BlendFunction.ADDITIVE))
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
            .withCull(false)
            .build();

    private static final Logger LOGGER = LoggerFactory.getLogger("Cascade");

    private static boolean failed;
    private static GpuBuffer config;

    private SdfFx() {
    }

    // draws one volume from its packed std140 config; throws to the caller's fail-soft on any gpu error
    static void draw(ByteBuffer packed, RenderTarget target) {
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        if (config == null) {
            config = RenderSystem.getDevice().createBuffer(() -> "cascade sdf config",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, CONFIG_BYTES);
        }
        encoder.writeToBuffer(config.slice(), packed);
        // the frame's target is the main one or the bloom capture, so volumes bloom too
        try (RenderPass pass = encoder.createRenderPass(
                () -> "cascade sdf volume", target.getColorTextureView(), Optional.empty(),
                target.getDepthTextureView(), OptionalDouble.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("SdfConfig", config);
            pass.draw(6, 1, 0, 0);
        }
    }

    // permanently drops sdf volumes for the session; the rest of the library is unaffected
    static void fail(String what, RuntimeException e) {
        if (!failed) {
            failed = true;
            LOGGER.error("Cascade sdf rendering failed while {}, disabling sdf volumes", what, e);
        }
    }

    static boolean failed() {
        return failed;
    }
}
