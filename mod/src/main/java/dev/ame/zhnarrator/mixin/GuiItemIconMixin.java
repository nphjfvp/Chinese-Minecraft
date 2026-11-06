package dev.ame.zhnarrator.mixin;

import dev.ame.zhnarrator.CharacterIcons;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({GuiGraphicsExtractor.class})
abstract class GuiItemIconMixin {
   @Inject(
      method = {"item(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;III)V"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void zhnarrator$drawCharactersInstead(@Nullable LivingEntity owner, @Nullable Level level, ItemStack stack, int x, int y, int seed, CallbackInfo ci) {
      if (CharacterIcons.draw((GuiGraphicsExtractor)(Object)this, stack, x, y)) {
         ci.cancel();
      }
   }
}
