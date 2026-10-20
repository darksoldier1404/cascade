package dev.chocoboy.cascade.client;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import java.util.function.Supplier;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;

// the render types, shared by both loaders: each type is a RenderPipeline (see CorePipelines) paired with a
// RenderSetup that binds the particle atlas, all public vanilla api. exposed to the shared render code
// through the provider seam.
//
// this class is loaded lazily, on the first draw that asks for a type, never at loader init or pipeline
// registration. a RenderSetup looks its textures up by id when a draw is prepared, so the atlas only has to
// be registered by the first draw (the static block below uploads it), but the upload needs the gpu device,
// which does not exist yet when loader entrypoints install the provider. the nested Provider exists exactly
// for that: instantiating a nested class does not initialize its enclosing class, so this class loads only
// when the first provider method runs, on the first draw.
public final class CoreRenderTypes {

    // upload the atlas before the textured types below resolve it. runs first because it is declared first
    static {
        ParticleAtlas.ensureUploaded();
    }

    // the atlas is a single mip level built for nearest sampling, so it must be bound with a nearest,
    // no-mipmap sampler. the default sampler mipmaps, and sampling a missing mip reads as white, which drew
    // every sprite as a flat vertex-coloured quad. clamp to edge so a cell cannot bleed into its neighbour.
    private static final Supplier<GpuSampler> ATLAS_SAMPLER = () ->
            RenderSystem.getSamplerCache().getSampler(
                    AddressMode.CLAMP_TO_EDGE,
                    AddressMode.CLAMP_TO_EDGE,
                    FilterMode.NEAREST,
                    FilterMode.NEAREST,
                    false);



    private CoreRenderTypes() {
    }

    // an untextured type: just the pipeline and a vertex buffer
    private static RenderType plain(String name, RenderPipeline pipeline, boolean sort) {
        RenderSetup.RenderSetupBuilder setup = RenderSetup.builder(pipeline);
        if (sort) {
            setup.sortOnUpload();
        }
        return RenderType.create(name, setup.createRenderSetup());
    }

    // an untextured lit type: no atlas, but the lightmap bound for the vendored lightmap vertex stage
    private static RenderType lightmapPlain(String name, RenderPipeline pipeline) {
        return RenderType.create(name, RenderSetup.builder(pipeline)
                .useLightmap()
                .createRenderSetup());
    }

    // a textured type: binds the particle atlas to Sampler0, and the lightmap too when the shader is lit
    private static RenderType textured(String name, RenderPipeline pipeline,
            boolean sort, boolean lightmap) {
        RenderSetup.RenderSetupBuilder setup = RenderSetup.builder(pipeline)
                .withTexture("Sampler0", ParticleAtlas.textureId(), ATLAS_SAMPLER);
        if (lightmap) {
            setup.useLightmap();
        }
        if (sort) {
            setup.sortOnUpload();
        }
        return RenderType.create(name, setup.createRenderSetup());
    }

    static final RenderType ADDITIVE = plain("cascade_additive", CorePipelines.ADDITIVE, false);
    static final RenderType SOLID = plain("cascade_solid", CorePipelines.SOLID, false);
    static final RenderType SOLID_LIT = lightmapPlain("cascade_solid_lit", CorePipelines.SOLID_LIT);
    static final RenderType TEXTURED_ADDITIVE = textured("cascade_textured_additive", CorePipelines.TEXTURED_ADDITIVE, false, false);
    static final RenderType TEXTURED_ALPHA = textured("cascade_textured_alpha", CorePipelines.TEXTURED_ALPHA, true, false);
    static final RenderType TEXTURED_ADDITIVE_LIT = textured("cascade_textured_additive_lit", CorePipelines.TEXTURED_ADDITIVE_LIT, false, true);
    static final RenderType TEXTURED_ALPHA_LIT = textured("cascade_textured_alpha_lit", CorePipelines.TEXTURED_ALPHA_LIT, true, true);
    static final RenderType GUI_TEXTURED_ADDITIVE = textured("cascade_gui_textured_additive", ScreenVfxPipelines.ADDITIVE, false, false);
    static final RenderType GUI_TEXTURED_ALPHA = textured("cascade_gui_textured_alpha", ScreenVfxPipelines.ALPHA, true, false);

    // the soft types also bind cascade's scene depth copy. its texture is recreated on window resize, which
    // is safe here because the setup only holds the id and the draw resolves the current texture
    static final RenderType TEXTURED_ALPHA_SOFT = softTextured("cascade_textured_alpha_soft",
            CorePipelines.TEXTURED_ALPHA_SOFT, false);
    static final RenderType TEXTURED_ALPHA_LIT_SOFT = softTextured("cascade_textured_alpha_lit_soft",
            CorePipelines.TEXTURED_ALPHA_LIT_SOFT, true);

    private static RenderType softTextured(String name, RenderPipeline pipeline, boolean lightmap) {
        RenderSetup.RenderSetupBuilder setup = RenderSetup.builder(pipeline)
                .withTexture("Sampler0", ParticleAtlas.textureId(), ATLAS_SAMPLER)
                .withTexture("DepthSampler", SoftDepth.textureId(), ATLAS_SAMPLER)
                .sortOnUpload();
        if (lightmap) {
            setup.useLightmap();
        }
        return RenderType.create(name, setup.createRenderSetup());
    }

    // the provider each loader installs at client init. a nested class so constructing it does not
    // class-load the enclosing types; they load when the first method here runs, on the first draw
    public static final class Provider implements CascadeRenderTypes.Provider {

        @Override
        public RenderType additive() {
            return ADDITIVE;
        }

        @Override
        public RenderType texturedAdditive() {
            return TEXTURED_ADDITIVE;
        }

        @Override
        public RenderType texturedAlpha() {
            return TEXTURED_ALPHA;
        }

        @Override
        public RenderType texturedAlphaSoft() {
            SoftDepth.ensureReady();
            return TEXTURED_ALPHA_SOFT;
        }

        @Override
        public RenderType solid() {
            return SOLID;
        }

        @Override
        public RenderType solidLit() {
            return SOLID_LIT;
        }

        @Override
        public RenderType texturedAdditiveLit() {
            return TEXTURED_ADDITIVE_LIT;
        }

        @Override
        public RenderType texturedAlphaLit() {
            return TEXTURED_ALPHA_LIT;
        }

        @Override
        public RenderType texturedAlphaLitSoft() {
            SoftDepth.ensureReady();
            return TEXTURED_ALPHA_LIT_SOFT;
        }

        @Override
        public RenderType guiTexturedAdditive() {
            return GUI_TEXTURED_ADDITIVE;
        }

        @Override
        public RenderType guiTexturedAlpha() {
            return GUI_TEXTURED_ALPHA;
        }
    }
}
