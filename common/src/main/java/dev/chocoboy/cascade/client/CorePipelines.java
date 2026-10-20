package dev.chocoboy.cascade.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import java.util.List;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;

// the render pipelines for the cascade render types, kept apart from CoreRenderTypes on purpose. the gpu
// device compiles pipelines lazily on first draw (or upfront where a loader pre-registers them), while
// CoreRenderTypes builds its RenderTypes on the first draw so the particle atlas is registered before a
// RenderSetup resolves it; touching this class must not drag the RenderTypes along, so the two live in
// separate classes.
//
// everything here is built from scratch on the public vanilla builder: the vanilla snippets and the
// toBuilder derivation are loader-widened api (neoforge access transformers) that fabric cannot see, so
// the uniform blocks and shader stages the snippets would contribute are declared explicitly instead,
// matching the vanilla pipelines they mirror (position_color for the untextured types, particle for the
// lit ones)
public final class CorePipelines {

    static final DepthStencilState DEPTH_NO_WRITE = new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false);
    static final DepthStencilState DEPTH_WRITE = new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true);

    private static final Identifier POSITION_COLOR = Identifier.withDefaultNamespace("core/position_color");
    private static final Identifier POSITION_TEX_COLOR = Identifier.withDefaultNamespace("core/position_tex_color");
    private static final Identifier PARTICLE = Identifier.withDefaultNamespace("core/particle");

    // the pre-26.1 position_tex_color fragment stage, vendored: it discarded texels below alpha 0.1 where
    // the 26.1 one only discards exact zero. cascade's sprites carry their shape in the alpha channel over
    // white rgb and blend one-one, so without that cutout the faint halo tail of a soft sprite like GLOW
    // adds full white across its whole quad and reads as a square
    private static final Identifier POSITION_TEX_CUTOUT =
            Identifier.fromNamespaceAndPath("cascade", "core/position_tex_cutout");

    // the soft particle fragment stage: fades alpha where the quad nears scene geometry, read from
    // cascade's depth copy bound as DepthSampler. one fragment serves the lit and unlit soft pipelines
    private static final Identifier POSITION_TEX_SOFT =
            Identifier.fromNamespaceAndPath("cascade", "core/position_tex_soft");

    // the scene depth copy the soft fragment stage reads
    private static final BindGroupLayout DEPTH_SAMPLER = BindGroupLayout.builder().withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER).build();

    private CorePipelines() {
    }

    // the world matrix and projection blocks every world pipeline reads, vanilla's matrices snippet inlined
    static RenderPipeline.Builder worldBuilder(String name) {
        return RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("cascade", name))
                .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
                .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                .withPrimitiveTopology(PrimitiveTopology.QUADS);
    }

    // untextured vertex-colored quads, the vanilla position_color stages
    private static RenderPipeline plain(String name, ColorTargetState color, DepthStencilState depth) {
        return worldBuilder(name)
                .withVertexShader(POSITION_COLOR)
                .withFragmentShader(POSITION_COLOR)
                .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
                .withColorTargetState(color)
                .withDepthStencilState(depth)
                .withCull(false)
                .build();
    }

    private static RenderPipeline textured(String name, ColorTargetState color, DepthStencilState depth) {
        return worldBuilder(name)
                .withVertexShader(POSITION_TEX_COLOR)
                .withFragmentShader(POSITION_TEX_CUTOUT)
                .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
                .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
                .withColorTargetState(color)
                .withDepthStencilState(depth)
                .withCull(false)
                .build();
    }

    // lit sprites ride the vanilla particle stages, whose vertex shader folds the lightmap into the vertex
    // color; the fog block is part of that shader's interface even though cascade leaves fog defaults alone
    private static RenderPipeline.Builder litBuilder(String name) {
        return worldBuilder(name)
                .withBindGroupLayout(BindGroupLayouts.FOG)
                .withVertexShader(PARTICLE)
                .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER2)
                .withVertexBinding(0, DefaultVertexFormat.PARTICLE)
                .withDepthStencilState(DEPTH_NO_WRITE)
                .withCull(false);
    }

    // untextured additive quads for beams: additive blend, depth tested so terrain occludes, no depth write
    static final RenderPipeline ADDITIVE = plain("additive",
            new ColorTargetState(BlendFunction.ADDITIVE), DEPTH_NO_WRITE);

    // textured atlas sprites in world, additive (glows) and translucent (smoke); depth tested, no depth write
    static final RenderPipeline TEXTURED_ADDITIVE = textured("textured_additive",
            new ColorTargetState(BlendFunction.ADDITIVE), DEPTH_NO_WRITE);

    static final RenderPipeline TEXTURED_ALPHA = textured("textured_alpha",
            new ColorTargetState(BlendFunction.TRANSLUCENT), DEPTH_NO_WRITE);

    // double sided solid geometry for mesh particles: opaque, depth tested and written so cubes read as 3D
    static final RenderPipeline SOLID = plain("solid", ColorTargetState.DEFAULT, DEPTH_WRITE);

    // its lit twin: vanilla dropped position_color_lightmap in 26.1, so a vendored vertex stage folds the
    // lightmap into the vertex color and the mesh takes scene light
    static final RenderPipeline SOLID_LIT = worldBuilder("solid_lit")
            .withVertexShader(Identifier.fromNamespaceAndPath("cascade", "core/position_color_lightmap"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("cascade", "core/position_color_lightmap"))
            .withBindGroupLayout(BindGroupLayouts.SAMPLER2)
            .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR_LIGHTMAP)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withDepthStencilState(DEPTH_WRITE)
            .withCull(false)
            .build();

    static final RenderPipeline TEXTURED_ADDITIVE_LIT = litBuilder("textured_additive_lit")
            .withFragmentShader(PARTICLE)
            .withColorTargetState(new ColorTargetState(BlendFunction.ADDITIVE))
            .build();

    static final RenderPipeline TEXTURED_ALPHA_LIT = litBuilder("textured_alpha_lit")
            .withFragmentShader(PARTICLE)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .build();

    // soft twins of the alpha types: the soft fragment stage fades the quad near scene geometry, reading
    // cascade's depth copy through the extra DepthSampler
    static final RenderPipeline TEXTURED_ALPHA_SOFT = worldBuilder("textured_alpha_soft")
            .withVertexShader(POSITION_TEX_COLOR)
            .withFragmentShader(POSITION_TEX_SOFT)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
            .withBindGroupLayout(DEPTH_SAMPLER)
            .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(DEPTH_NO_WRITE)
            .withCull(false)
            .build();

    // the lit twin rides the particle vertex stage, whose outputs sit after the fog distances, so the fragment
    // stage reads them from later locations under the LIT define
    static final RenderPipeline TEXTURED_ALPHA_LIT_SOFT = litBuilder("textured_alpha_lit_soft")
            .withFragmentShader(POSITION_TEX_SOFT)
            .withShaderDefine("LIT")
            .withBindGroupLayout(DEPTH_SAMPLER)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .build();

    // the gpu device compiles pipelines lazily on first draw; a loader that can pre-register them for an
    // upfront compile (neoforge's register event) feeds this list through. built on call rather than held in
    // a static: the hud pipelines build on worldBuilder above, so a static list naming them would read them
    // half-initialized whenever the hud class is the first one touched
    public static List<RenderPipeline> all() {
        return List.of(
                ADDITIVE, TEXTURED_ADDITIVE, TEXTURED_ALPHA, SOLID, SOLID_LIT,
                TEXTURED_ADDITIVE_LIT, TEXTURED_ALPHA_LIT,
                TEXTURED_ALPHA_SOFT, TEXTURED_ALPHA_LIT_SOFT,
                ScreenVfxPipelines.ADDITIVE, ScreenVfxPipelines.ALPHA,
                BloomBlit.PIPELINE, SdfFx.PIPELINE, GpuSim.PIPELINE);
    }
}
