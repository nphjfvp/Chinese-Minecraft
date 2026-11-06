package dev.ame.zhnarrator.neoforge.mixin;

import dev.ame.zhnarrator.ActionDetector;
import dev.ame.zhnarrator.ZhNarratorClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({MultiPlayerGameMode.class})
abstract class MultiPlayerGameModeMixin {
   @Shadow
   @Final
   private Minecraft minecraft;
   @Unique
   private BlockState zhnarrator$breaking;

   @Inject(
      method = {"destroyBlock"},
      at = {@At("HEAD")}
   )
   private void zhnarrator$rememberBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
      this.zhnarrator$breaking = this.minecraft.level == null ? null : this.minecraft.level.getBlockState(pos);
   }

   @Inject(
      method = {"destroyBlock"},
      at = {@At("RETURN")}
   )
   private void zhnarrator$blockBroken(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
      BlockState state = this.zhnarrator$breaking;
      this.zhnarrator$breaking = null;
      ActionDetector detector = ZhNarratorClient.detector();
      if (cir.getReturnValueZ() && state != null && this.minecraft.player != null && detector != null) {
         detector.onBlockBroken(this.minecraft.player, state);
      }
   }
}
