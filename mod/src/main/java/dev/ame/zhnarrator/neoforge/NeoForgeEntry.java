package dev.ame.zhnarrator.neoforge;

import dev.ame.zhnarrator.ActionDetector;
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
import net.neoforged.neoforge.common.NeoForge;

@Mod(
   value = "zhnarrator",
   dist = {Dist.CLIENT}
)
public final class NeoForgeEntry {
   public NeoForgeEntry(IEventBus modBus, ModContainer container) {
      ZhNarratorClient mod = new ZhNarratorClient();
      if (mod.init(FMLPaths.CONFIGDIR.get(), FMLPaths.GAMEDIR.get())) {
         modBus.addListener(event -> event.addListener(LanguagePacks.ID, ZhNarratorClient.packs));
         modBus.addListener(event -> ZhNarratorClient.keys().forEach(event::register));
         modBus.addListener(event -> event.registerAboveAll(NarratorHud.LAYER, (graphics, delta) -> NarratorHud.render(graphics, ZhNarratorClient.speaker)));
         container.registerExtensionPoint(IConfigScreenFactory.class, (IConfigScreenFactory)(modContainer, parent) -> new NarratorSettingsScreen(parent));
         IEventBus game = NeoForge.EVENT_BUS;
         game.addListener(event -> mod.tick(Minecraft.getInstance()));
         game.addListener(event -> ZhNarratorClient.appendTooltip(event.getItemStack(), event.getToolTip()));
         game.addListener(event -> {
            ActionDetector detector = ZhNarratorClient.detector();
            if (detector != null) {
               detector.onAttackEntity(event.getEntity(), event.getEntity().level(), event.getTarget());
            }
         });
         game.addListener(event -> {
            ActionDetector detector = ZhNarratorClient.detector();
            if (detector != null) {
               detector.onUseEntity(event.getEntity(), event.getLevel(), event.getHand(), event.getTarget());
            }
         });
         game.addListener(event -> {
            ActionDetector detector = ZhNarratorClient.detector();
            if (detector != null) {
               detector.onUseBlock(event.getEntity(), event.getLevel(), event.getHand(), event.getHitVec());
            }
         });
         game.addListener(event -> ZhNarratorClient.stopping());
      }
   }
}
