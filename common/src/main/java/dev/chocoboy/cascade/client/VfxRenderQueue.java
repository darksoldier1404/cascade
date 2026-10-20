package dev.chocoboy.cascade.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.rendertype.RenderType;

// collects a frame's draw work grouped by render type, then plays it back one type at a time. effects with
// mixed types interleaved would cost a draw per switch; grouping makes it one draw per type per frame
public final class VfxRenderQueue {

    // playback tiers: opaque geometry first, alpha blends over it, additive glow on top. within a tier,
    // types and writers keep submission order, which the manager feeds nearest first
    private static final int OPAQUE = 0;
    private static final int BLENDED = 1;
    private static final int GLOW = 2;

    public interface Writer {
        void write(VertexConsumer vc);
    }

    // reports a writer that threw during playback, so the owning effect can be removed without the
    // whole pass going down
    interface FailureSink {
        void failed(Object owner, RuntimeException e);
    }

    record Submission(Object owner, Writer writer) {
    }

    private record SelfSubmission(Object owner, Runnable draw) {
    }

    private final Map<RenderType, List<Submission>> groups = new LinkedHashMap<>();
    private final List<SelfSubmission> self = new ArrayList<>();
    private Object owner;

    // tags following submissions with the effect making them, so a playback failure traces back to it
    void owner(Object owner) {
        this.owner = owner;
    }

    public void submit(RenderType type, Writer writer) {
        groups.computeIfAbsent(type, t -> new ArrayList<>()).add(new Submission(owner, writer));
    }

    // for effects that own their whole draw call (gpu-resident geometry). they play after the typed
    // tiers, so their glow composites over everything the frame batched
    public void submitSelf(Runnable draw) {
        self.add(new SelfSubmission(owner, draw));
    }

    void play(FailureSink failures) {
        // the sort is stable, so types in the same tier keep first-submission order
        List<RenderType> order = new ArrayList<>(groups.keySet());
        order.sort(Comparator.comparingInt(VfxRenderQueue::tier));
        CascadeBatcher.draw(order, groups, failures);
        for (SelfSubmission s : self) {
            try {
                s.draw().run();
            } catch (RuntimeException e) {
                failures.failed(s.owner(), e);
            }
        }
    }

    private static int tier(RenderType type) {
        if (type == CascadeRenderTypes.solid() || type == CascadeRenderTypes.solidLit()) {
            return OPAQUE;
        }
        if (type == CascadeRenderTypes.additive() || type == CascadeRenderTypes.texturedAdditive()
                || type == CascadeRenderTypes.texturedAdditiveLit()) {
            return GLOW;
        }
        return BLENDED;
    }
}
