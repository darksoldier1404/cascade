package dev.chocoboy.cascade.client;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.chocoboy.cascade.engine.effect.AttractorSpec;
import dev.chocoboy.cascade.engine.effect.BlendMode;
import dev.chocoboy.cascade.engine.effect.ComponentSpec;
import dev.chocoboy.cascade.engine.effect.DragSpec;
import dev.chocoboy.cascade.engine.effect.EmissionSpec;
import dev.chocoboy.cascade.engine.effect.EmitterSpec;
import dev.chocoboy.cascade.engine.effect.GravitySpec;
import dev.chocoboy.cascade.engine.effect.MeshId;
import dev.chocoboy.cascade.engine.effect.VortexSpec;
import java.nio.ByteBuffer;
import java.util.OptionalDouble;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// the gpu particle backend and the eligibility rules that decide whether a spec runs here or on the cpu
// sim. opt-in and off by default; the cpu path stays the reference. 26.1's gpu api exposes no compute, so
// the backend was rebuilt stateless: spawn state rides a per-effect vertex buffer and the vertex shader
// re-integrates the force stack from spawn to the burst's age each frame, which frees the old gl 4.3
// requirement entirely. any gpu failure disables the backend for the session and everything falls back
// silently. public because the loader's pipeline registration lives in another package
public final class GpuSim {

    private static final Logger LOGGER = LoggerFactory.getLogger("Cascade");

    // force and gradient slot bounds in the shader's config block
    static final int MAX_FORCES = 8;
    static final int MAX_COLOR_STOPS = 8;

    // mat4 + six vec4-sized fields + ivec4[8] + vec4[8] x3 + vec4[4], in std140 layout
    static final int CONFIG_BYTES = 64 + 6 * 16 + 4 * 8 * 16 + 4 * 16;

    // spawn state as vertex attributes: xyz position + roll, xyz velocity + spin, per vertex. attributes
    // are named per format now, so there is no shared element id space to collide in
    static final VertexFormat FORMAT = VertexFormat.builder(0)
            .addAttribute("SpawnPos", GpuFormat.RGBA32_FLOAT)
            .addAttribute("SpawnVel", GpuFormat.RGBA32_FLOAT)
            .build();

    private static final BindGroupLayout CONFIG_LAYOUT = BindGroupLayout.builder()
            .withUniform("GpuBurstConfig", UniformType.UNIFORM_BUFFER)
            .withSampler("Sampler0")
            .build();

    public static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("cascade", "gpu_burst"))
            .withVertexShader(Identifier.fromNamespaceAndPath("cascade", "core/gpu_burst"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("cascade", "core/gpu_burst"))
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withBindGroupLayout(CONFIG_LAYOUT)
            .withVertexBinding(0, FORMAT)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(BlendFunction.ADDITIVE))
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
            .withCull(false)
            .build();

    private static boolean enabled;
    private static boolean failed;
    private static GpuBuffer config;

    private GpuSim() {
    }

    public static void setEnabled(boolean on) {
        enabled = on;
    }

    public static boolean enabled() {
        return enabled;
    }

    static boolean shouldRun(EmitterSpec spec) {
        return enabled && !failed && eligible(spec);
    }

    // the honest v1 cut: stateless additive billboard bursts only. anything the gpu shader does not
    // mirror exactly routes to the cpu sim instead of getting a lookalike
    static boolean eligible(EmitterSpec spec) {
        if (spec.emission().mode() != EmissionSpec.Mode.BURST || spec.count() < 1) {
            return false;
        }
        if (spec.render().blend() != BlendMode.ADDITIVE || spec.render().mesh() != MeshId.NONE
                || spec.render().lit() || spec.render().soft() || spec.render().stretch() != 0f) {
            return false;
        }
        if (spec.trail().enabled() || spec.subEmitter() != null || spec.collision().enabled()) {
            return false;
        }
        if (spec.modifiers().size() > MAX_FORCES || spec.color().stops().size() > MAX_COLOR_STOPS) {
            return false;
        }
        for (ComponentSpec c : spec.modifiers()) {
            if (!(c instanceof GravitySpec || c instanceof DragSpec
                    || c instanceof AttractorSpec || c instanceof VortexSpec)) {
                return false;
            }
        }
        return true;
    }

    // draws one burst from its spawn buffer and packed config; throws to the caller's fail-soft on any
    // gpu error. one shared config buffer serves every burst, the gl backend executes passes in order
    static void draw(GpuBuffer spawns, ByteBuffer packed, int vertices) {
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        if (config == null) {
            config = RenderSystem.getDevice().createBuffer(() -> "cascade gpu burst config",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, CONFIG_BYTES);
        }
        encoder.writeToBuffer(config.slice(), packed);
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        GpuTextureView color = RenderSystem.outputColorTextureOverride != null
                ? RenderSystem.outputColorTextureOverride : main.getColorTextureView();
        GpuTextureView depth = RenderSystem.outputDepthTextureOverride != null
                ? RenderSystem.outputDepthTextureOverride : main.getDepthTextureView();
        try (RenderPass pass = encoder.createRenderPass(
                () -> "cascade gpu burst", color, Optional.empty(), depth, OptionalDouble.empty())) {
            pass.setPipeline(PIPELINE);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("GpuBurstConfig", config);
            pass.bindTexture("Sampler0",
                    Minecraft.getInstance().getTextureManager()
                            .getTexture(ParticleAtlas.textureId()).getTextureView(),
                    RenderSystem.getSamplerCache().getSampler(
                            AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                            FilterMode.NEAREST, FilterMode.NEAREST, false));
            pass.setVertexBuffer(0, spawns.slice());
            pass.draw(vertices, 1, 0, 0);
        }
    }

    // permanently drops to the cpu path; live gpu effects keep no-op ticking until their lifetime ends
    static void fail(String what, RuntimeException e) {
        if (!failed) {
            failed = true;
            enabled = false;
            LOGGER.error("Cascade gpu sim failed while {}, falling back to the cpu sim", what, e);
        }
    }

    static boolean failed() {
        return failed;
    }
}
