package dev.chocoboy.cascade.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import java.util.Optional;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;

// the bloom composite: adds the blurred capture over the frame. vanilla's blitAndBlendToTexture does this
// exact draw with the entity outline pipeline, whose alpha blend would dim the frame under the capture's
// empty pixels; this is the same fullscreen-triangle draw on an additive twin of that pipeline, so black
// stays invisible and glow only ever brightens. public because the loader's pipeline registration lives in
// another package
public final class BloomBlit {

    // vanilla's entity_outline_blit config (screenquad + blit_screen over a fullscreen triangle, sampling
    // InSampler) rebuilt on the public builder, with the blend swapped to additive. the derivation cannot
    // ride toBuilder, which is loader-widened api fabric does not see
    public static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("cascade", "bloom_blit"))
            .withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
            .withFragmentShader(Identifier.withDefaultNamespace("core/blit_screen"))
            .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(BlendFunction.ADDITIVE))
            .build();

    private BloomBlit() {
    }

    static void addTo(RenderTarget source, RenderTarget target) {
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "cascade bloom composite", target.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("InSampler", source.getColorTextureView(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.draw(3, 1, 0, 0);
        }
    }
}
