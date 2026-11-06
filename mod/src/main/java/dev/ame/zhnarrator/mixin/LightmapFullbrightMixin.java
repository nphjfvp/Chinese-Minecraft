package dev.ame.zhnarrator.mixin;

import dev.ame.zhnarrator.ZhNarratorClient;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fullbright: lights the world as night vision does, without the effect. Only on this computer. */
@Mixin({LightmapRenderStateExtractor.class})
abstract class LightmapFullbrightMixin {
   @Inject(method = {"extract"}, at = {@At("TAIL")})
   private void zhnarrator$fullbright(LightmapRenderState state, float partialTicks, CallbackInfo ci) {
      if (ZhNarratorClient.config.fullbright && state.needsUpdate) {
         state.nightVisionEffectIntensity = 1.0F;
      }
   }
}
