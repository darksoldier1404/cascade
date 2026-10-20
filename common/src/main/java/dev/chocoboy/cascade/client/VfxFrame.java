package dev.chocoboy.cascade.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

// the per-frame render inputs an effect needs, so render methods take one argument instead of four.
// effects submit their draw work to the queue, which groups it by render type before anything is drawn.
// target is the framebuffer this frame draws into: the main one, or the bloom capture
public record VfxFrame(PoseStack pose, Quaternionf cameraRotation, Vec3 cameraPos, RenderTarget target,
        VfxRenderQueue queue) {
}
