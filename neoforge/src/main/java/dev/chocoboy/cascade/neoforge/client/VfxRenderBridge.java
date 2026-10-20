package dev.chocoboy.cascade.neoforge.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.chocoboy.cascade.client.PostFx;
import dev.chocoboy.cascade.client.ScreenVfxManager;
import dev.chocoboy.cascade.client.VfxRenderManager;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

// feeds neoforge's client tick and render pass into the shared render manager. the level stages hand mods
// the vanilla render pass that is open while they fire, and uploads, depth copies and passes of our own
// cannot run inside it, so the effects draw at AfterLevel, where no pass is open. the camera is read from the
// client, and vanilla has already popped the view matrix by then, so it is pushed back for the draw
public final class VfxRenderBridge {

    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        VfxRenderManager.get().clientTick();
        ScreenVfxManager.get().tick();
    }

    @SubscribeEvent
    public void onRenderGui(RenderGuiEvent.Post event) {
        ScreenVfxManager.get().render(event.getGuiGraphics());
    }

    @SubscribeEvent
    public void onAfterLevel(RenderLevelStageEvent.AfterLevel event) {
        Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
        RenderSystem.getModelViewStack().pushMatrix().mul(event.getModelViewMatrix());
        try {
            VfxRenderManager.get().render(event.getPoseStack(), camera.rotation(), camera.position());
            // the bloom capture re-renders now, with the frame's matrices still in place, and post fx runs
            // after the effects so it captures them
            PostFx.captureVfx(event.getPoseStack(), camera.rotation(), camera.position());
            PostFx.process(Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false));
        } finally {
            RenderSystem.getModelViewStack().popMatrix();
        }
    }
}
