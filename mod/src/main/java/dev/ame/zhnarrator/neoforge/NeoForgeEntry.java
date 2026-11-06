package dev.ame.zhnarrator.neoforge;

import dev.ame.zhnarrator.ActionDetector;
import dev.ame.zhnarrator.Consequences;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import dev.ame.zhnarrator.LanguagePacks;
import dev.ame.zhnarrator.NarratorHud;
import dev.ame.zhnarrator.NarratorSettingsScreen;
import dev.ame.zhnarrator.ZhNarratorClient;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

@Mod(
   value = "zhnarrator",
   dist = {Dist.CLIENT}
)
public final class NeoForgeEntry {
   public NeoForgeEntry(IEventBus modBus, ModContainer container) {
      ZhNarratorClient mod = new ZhNarratorClient();
      if (mod.init(FMLPaths.CONFIGDIR.get(), FMLPaths.GAMEDIR.get())) {
         modBus.addListener((AddClientReloadListenersEvent event) -> event.addListener(LanguagePacks.ID, ZhNarratorClient.packs));
         modBus.addListener((RegisterKeyMappingsEvent event) -> ZhNarratorClient.keys().forEach(event::register));
         modBus.addListener((RegisterGuiLayersEvent event) -> event.registerAboveAll(NarratorHud.LAYER, (graphics, delta) -> NarratorHud.render(graphics, ZhNarratorClient.speaker)));
         container.registerExtensionPoint(IConfigScreenFactory.class, (IConfigScreenFactory)(modContainer, parent) -> new NarratorSettingsScreen(parent));
         IEventBus game = NeoForge.EVENT_BUS;
         game.addListener((ClientTickEvent.Post event) -> mod.tick(Minecraft.getInstance()));
         game.addListener((ItemTooltipEvent event) -> ZhNarratorClient.appendTooltip(event.getItemStack(), event.getToolTip()));
         game.addListener((AttackEntityEvent event) -> {
            ActionDetector detector = ZhNarratorClient.detector();
            if (detector != null) {
               detector.onAttackEntity(event.getEntity(), event.getEntity().level(), event.getTarget());
            }
         });
         game.addListener((PlayerInteractEvent.EntityInteract event) -> {
            ActionDetector detector = ZhNarratorClient.detector();
            if (detector != null) {
               detector.onUseEntity(event.getEntity(), event.getLevel(), event.getHand(), event.getTarget());
            }
         });
         game.addListener((PlayerInteractEvent.RightClickBlock event) -> {
            ActionDetector detector = ZhNarratorClient.detector();
            if (detector != null) {
               detector.onUseBlock(event.getEntity(), event.getLevel(), event.getHand(), event.getHitVec());
            }
         });
         // Singleplayer: on death the items go to the quiz pledge instead of onto the ground.
         game.addListener((LivingDropsEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
               List<ItemStack> stacks = event.getDrops().stream().map(ItemEntity::getItem).toList();
               if (Consequences.pledgeDeathDrops(player, stacks)) {
                  event.getDrops().clear();
               }
            }
         });
         game.addListener((GameShuttingDownEvent event) -> ZhNarratorClient.stopping());
      }
   }
}
