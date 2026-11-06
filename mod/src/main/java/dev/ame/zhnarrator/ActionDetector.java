package dev.ame.zhnarrator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Map.Entry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.HitResult.Type;
import org.jetbrains.annotations.Nullable;

public final class ActionDetector {
   private static final Set<String> ANIMAL_FOOD = Set.of(
      "wheat",
      "wheat_seeds",
      "beetroot_seeds",
      "melon_seeds",
      "pumpkin_seeds",
      "torchflower_seeds",
      "carrot",
      "golden_carrot",
      "potato",
      "apple",
      "golden_apple",
      "hay_block",
      "sugar",
      "sweet_berries",
      "glow_berries",
      "bamboo",
      "cactus",
      "dandelion",
      "seagrass",
      "cod",
      "salmon",
      "tropical_fish",
      "bone",
      "rotten_flesh",
      "honeycomb",
      "cake",
      "beef",
      "porkchop",
      "chicken",
      "mutton",
      "rabbit",
      "cooked_beef",
      "cooked_porkchop",
      "cooked_chicken",
      "cooked_mutton",
      "cooked_rabbit",
      "cooked_cod",
      "cooked_salmon",
      "egg",
      "kelp",
      "spider_eye",
      "slime_ball",
      "warped_fungus",
      "crimson_fungus"
   );
   private final PhraseBook phrases;
   private final Speaker speaker;
   private static final long STATE_GRACE_MS = 1000L;
   private static final long CLIMATE_DEBOUNCE_MS = 3000L;
   private static final long KILL_CREDIT_MS = 6000L;
   private static final long BURNING_REPEAT_MS = 3000L;
   private static final float HOT_TEMPERATURE = 1.5F;
   @Nullable
   private String stateId;
   private long stateSaidAt;
   private long stateSeenAt;
   @Nullable
   private String pendingId;
   private long pendingSince;
   @Nullable
   private ActionDetector.PendingPlace pendingPlace;
   @Nullable
   private Map<Item, Integer> lastCounts;
   @Nullable
   private Item dropCheckItem;
   @Nullable
   private Item lastHeld;
   private ItemStack prevHeld = ItemStack.EMPTY;
   private boolean dropWasDown;
   private long burningSaidAt;
   private float lastHealth = -1.0F;
   private boolean wasDead;
   private boolean wasFalling;
   private boolean wasOnGround;
   private boolean hungrySaid;
   private long lastJumpAt;
   @Nullable
   private Boolean wasDark;
   @Nullable
   private String precip;
   @Nullable
   private String climate;
   @Nullable
   private String pendingClimate;
   private long pendingClimateSince;
   private long climateSaidAt;
   private final Map<LivingEntity, Long> recentlyHit = new HashMap<>();
   private long lastWeatherReportAt;
   private long joinedAt;
   @Nullable
   private Object lastLevel;
   @Nullable
   private String lastDimension;
   @Nullable
   private String lastWorldKey;
   private static final Set<String> NO_PLURAL = Set.of(
      "bread",
      "wheat",
      "paper",
      "string",
      "leather",
      "coal",
      "iron",
      "copper",
      "gold",
      "sugar",
      "snow",
      "ice",
      "sand",
      "dirt",
      "gravel",
      "glass",
      "wool",
      "stone",
      "wood",
      "planks",
      "mud",
      "clay",
      "moss",
      "redstone",
      "quartz",
      "netherite",
      "flint",
      "bone meal",
      "gunpowder",
      "kelp",
      "honey",
      "milk",
      "soup",
      "watermelon",
      "steak",
      "raw beef",
      "raw pork",
      "cooked pork",
      "raw chicken",
      "cooked chicken",
      "raw mutton",
      "cooked mutton",
      "cooked rabbit",
      "cooked fish",
      "raw fish",
      "rotten flesh",
      "dried kelp",
      "seeds",
      "boots",
      "leggings",
      "shears",
      "scaffolding",
      "hay",
      "lapis lazuli",
      "cobblestone",
      "deepslate",
      "sandstone",
      "terracotta",
      "amethyst",
      "glowstone",
      "bricks",
      "stone bricks",
      "obsidian",
      "netherrack",
      "soul sand",
      "end stone",
      "concrete"
   );

   static String worldKey(Minecraft mc) {
      if (mc.getSingleplayerServer() != null) {
         String folder = mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString();
         return "save:" + folder + ":" + Long.toHexString(mc.getSingleplayerServer().overworld().getSeed());
      } else {
         return mc.getCurrentServer() != null ? "server:" + mc.getCurrentServer().ip : "unknown";
      }
   }

   public ActionDetector(PhraseBook phrases, Speaker speaker) {
      this.phrases = phrases;
      this.speaker = speaker;
   }

   public void onAttackEntity(Player player, Level level, Entity entity) {
      if (level.isClientSide() && player.isLocalPlayer()) {
         this.phrases.forEntity("hit", entityPath(entity)).or(() -> this.phrases.fixed("attack")).ifPresent(this.speaker::say);
         if (entity instanceof LivingEntity living) {
            this.recentlyHit.put(living, System.currentTimeMillis());
         }
      }
   }

   public void onUseEntity(Player player, Level level, InteractionHand hand, Entity entity) {
      if (level.isClientSide() && player.isLocalPlayer() && hand == InteractionHand.MAIN_HAND && isAnimalFood(player.getItemInHand(hand))) {
         this.phrases.forEntity("feed", entityPath(entity)).ifPresent(this.speaker::say);
      }
   }

   public void onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
      if (level.isClientSide() && player.isLocalPlayer()) {
         BlockState targetState = level.getBlockState(hit.getBlockPos());
         String target = blockPath(targetState.getBlock());
         if (hand == InteractionHand.MAIN_HAND && !player.isShiftKeyDown()) {
            boolean closing = targetState.hasProperty(BlockStateProperties.OPEN)
               && (Boolean)targetState.getValue(BlockStateProperties.OPEN)
               && this.phrases.forBlock("close", target).isPresent();
            Optional<Phrase> use = closing
               ? this.phrases.forBlock("close", target)
               : this.phrases.forBlock("open", target).or(() -> this.phrases.forBlock("use", target));
            if (use.isPresent()) {
               this.speaker.say(use.get());
               return;
            }
         }

         if (player.getItemInHand(hand).getItem() instanceof BlockItem blockItem) {
            this.pendingPlace = new ActionDetector.PendingPlace(
               blockItem.getBlock(), hit.getBlockPos(), hit.getBlockPos().relative(hit.getDirection()), System.currentTimeMillis() + 500L
            );
         }
      }
   }

   public void onBlockBroken(Player player, BlockState state) {
      this.breakPhrase(player.getMainHandItem(), blockPath(state.getBlock())).ifPresent(this.speaker::say);
   }

   private Optional<Phrase> breakPhrase(ItemStack held, String blockPath) {
      if (!itemPath(held).endsWith("_axe")) {
         Optional<Phrase> byHand = this.phrases.forBlock("break_hand", blockPath);
         if (byHand.isPresent()) {
            return byHand;
         }
      }

      return this.phrases.forBlock("break", blockPath);
   }

   public void tick(Minecraft mc) {
      LocalPlayer player = mc.player;
      if (player != null && mc.level != null) {
         long now = System.currentTimeMillis();
         if (mc.level != this.lastLevel) {
            String dimension = mc.level.dimension().identifier().getPath();
            if (this.lastLevel != null && this.lastDimension != null && !dimension.equals(this.lastDimension)) {
               String id = switch (dimension) {
                  case "the_nether" -> "dim_nether";
                  case "the_end" -> "dim_end";
                  default -> "dim_overworld";
               };
               this.phrases.fixed(id).ifPresent(this.speaker::sayNow);
            }

            boolean newWorld = this.lastLevel == null
               || this.lastDimension == null
               || !dimension.equals(this.lastDimension)
               || !worldKey(mc).equals(this.lastWorldKey);
            this.lastLevel = mc.level;
            this.lastDimension = dimension;
            this.joinedAt = now;
            this.resetTrackers(player);
            String key = worldKey(mc);
            if (!key.equals(this.lastWorldKey)) {
               this.lastWorldKey = key;
               ZhNarratorClient.progress.switchWorld(key);
            }

            if (newWorld) {
               ZhNarratorClient.quiz.onJoinedWorld();
            }
         }

         NarratorConfig c = ZhNarratorClient.config;
         Phrase state = this.currentState(mc, player);
         String id = state == null ? null : state.id();
         if (id == null) {
            if (now - this.stateSeenAt > 1000L) {
               if (this.stateId != null) {
                  this.speaker.dropQueued(this.stateId);
               }

               this.stateId = null;
               this.pendingId = null;
            }
         } else {
            this.stateSeenAt = now;
            if (!id.equals(this.stateId)) {
               if (!id.equals(this.pendingId)) {
                  this.pendingId = id;
                  this.pendingSince = now;
               }

               long delay = state.isMovement() ? (long)(c.movementDelaySeconds * 1000.0F) : 0L;
               if (now - this.pendingSince >= delay) {
                  this.stateId = id;
                  this.stateSaidAt = now;
                  this.speaker.sayOngoing(state, false);
               }
            } else if ((float)(now - this.stateSaidAt) >= c.repeatSeconds * 1000.0F) {
               this.stateSaidAt = now;
               this.speaker.sayOngoing(state, true);
            }
         }

         if (this.pendingPlace != null) {
            ActionDetector.PendingPlace pp = this.pendingPlace;
            if (mc.level.getBlockState(pp.inFront()).is(pp.block()) || mc.level.getBlockState(pp.clicked()).is(pp.block())) {
               this.pendingPlace = null;
               this.phrases.forBlock("place", blockPath(pp.block())).ifPresent(this.speaker::say);
            } else if (now > pp.expiresAt()) {
               this.pendingPlace = null;
            }
         }

         boolean dropDown = mc.options.keyDrop.isDown();
         if (dropDown && !this.dropWasDown && mc.gui.screen() == null && !this.prevHeld.isEmpty()) {
            this.dropCheckItem = this.prevHeld.getItem();
         }

         this.dropWasDown = dropDown;
         this.prevHeld = player.getMainHandItem().copy();
         if ((player.isOnFire() || player.isInLava()) && now - this.burningSaidAt > 3000L) {
            this.burningSaidAt = now;
            this.phrases.fixed("burning").ifPresent(this.speaker::say);
         }

         Map<Item, Integer> counts = new HashMap<>();

         for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (!s.isEmpty()) {
               counts.merge(s.getItem(), s.getCount(), Integer::sum);
            }
         }

         if (this.lastCounts != null && mc.gui.screen() == null) {
            for (Entry<Item, Integer> e : counts.entrySet()) {
               int gained = e.getValue() - this.lastCounts.getOrDefault(e.getKey(), 0);
               if (gained > 0) {
                  this.sayCounted("pickup", BuiltInRegistries.ITEM.getKey(e.getKey()).getPath(), gained);
               }
            }

            if (this.dropCheckItem != null) {
               int lost = this.lastCounts.getOrDefault(this.dropCheckItem, 0) - counts.getOrDefault(this.dropCheckItem, 0);
               if (lost > 0) {
                  this.sayCounted("drop", BuiltInRegistries.ITEM.getKey(this.dropCheckItem).getPath(), lost);
               }

               this.dropCheckItem = null;
            }
         }

         this.lastCounts = counts;
         if (c.sayHeldItem) {
            Item held = player.getMainHandItem().getItem();
            if (held != this.lastHeld) {
               this.lastHeld = held;
               ItemStack inHand = player.getMainHandItem();
               if (!inHand.isEmpty()) {
                  this.sayCounted("hold", itemPath(inHand), inHand.getCount());
               }
            }
         }

         boolean onGround = player.onGround();
         Input keys = player.input.keyPresses;
         if (keys.jump()
            && this.wasOnGround
            && !onGround
            && !player.isInWater()
            && player.getVehicle() == null
            && !player.isSprinting()
            && (float)(now - this.lastJumpAt) > c.jumpCooldownSeconds * 1000.0F) {
            this.lastJumpAt = now;
            this.phrases.fixed("jump").ifPresent(this.speaker::say);
         }

         this.wasOnGround = onGround;
         if (c.sayDamage) {
            float health = player.getHealth();
            boolean dead = player.isDeadOrDying();
            if (dead && !this.wasDead) {
               this.phrases.fixed("die").ifPresent(this.speaker::sayNow);
               ZhNarratorClient.progress.recordDeath(mc.level.getLevelData().isHardcore());
            } else if (!dead && this.lastHealth >= 0.0F && health < this.lastHealth - 0.5F) {
               this.phrases.fixed(this.wasFalling ? "fall" : "hurt").ifPresent(this.speaker::say);
            }

            this.wasDead = dead;
            this.lastHealth = health;
            this.wasFalling = !onGround && player.getDeltaMovement().y < -0.6;
         }

         if (c.sayKills) {
            List<Projectile> ours = null;

            for (Entity ex : mc.level.entitiesForRendering()) {
               if (ex instanceof Projectile p && p.getOwner() == player) {
                  if (ours == null) {
                     ours = new ArrayList<>();
                  }

                  ours.add(p);
               }
            }

            if (ours != null) {
               for (Entity exx : mc.level.entitiesForRendering()) {
                  if (exx instanceof LivingEntity l && l != player && l.hurtTime > 0 && l.hurtTime == l.hurtDuration) {
                     for (Projectile p : ours) {
                        if (p.distanceToSqr(l) < 9.0) {
                           this.recentlyHit.put(l, now);
                           break;
                        }
                     }
                  }
               }
            }
         }

         if (!this.recentlyHit.isEmpty()) {
            Iterator<Entry<LivingEntity, Long>> it = this.recentlyHit.entrySet().iterator();

            while (it.hasNext()) {
               Entry<LivingEntity, Long> exxx = it.next();
               LivingEntity target = exxx.getKey();
               if (target.isDeadOrDying()) {
                  it.remove();
                  if (c.sayKills) {
                     this.phrases.forEntity("kill", entityPath(target)).or(() -> this.phrases.fixed("kill_it")).ifPresent(this.speaker::say);
                  }
               } else if (now - exxx.getValue() > 6000L || target.isRemoved()) {
                  it.remove();
               }
            }
         }

         int food = player.getFoodData().getFoodLevel();
         if (food <= 6 && !this.hungrySaid) {
            this.hungrySaid = true;
            this.phrases.fixed("hungry").ifPresent(this.speaker::say);
         } else if (food >= 10) {
            this.hungrySaid = false;
         }

         if (c.sayWeatherAndTime && now - this.joinedAt > 3000L) {
            boolean dark = mc.level.isDarkOutside();
            if (this.wasDark != null && dark != this.wasDark) {
               this.phrases.fixed(dark ? "night" : "day").ifPresent(this.speaker::say);
            }

            this.wasDark = dark;
            String px = precipitation(mc.level, player.blockPosition());
            if (this.precip != null && !px.equals(this.precip)) {
               String weatherId = switch (px) {
                  case "none" -> this.precip.equals("snow") ? "snow_stop" : "rain_stop";
                  case "rain" -> this.precip.equals("none") ? "rain" : "weather_rain";
                  case "snow" -> this.precip.equals("none") ? "snow" : "weather_snow";
                  default -> "thunder";
               };
               this.phrases.fixed(weatherId).ifPresent(this.speaker::say);
            }

            this.precip = px;
            String cl = climate(mc.level, player.blockPosition());
            if (!cl.equals(this.pendingClimate)) {
               this.pendingClimate = cl;
               this.pendingClimateSince = now;
            }

            if (this.climate == null) {
               this.climate = cl;
               this.climateSaidAt = now;
            } else if (c.sayClimate && !cl.equals(this.climate) && now - this.pendingClimateSince >= 3000L) {
               String climateId = switch (cl) {
                  case "hot" -> "hot";
                  case "cold" -> "cold";
                  default -> this.climate.equals("hot") ? "not_hot" : "not_cold";
               };
               this.climate = cl;
               this.climateSaidAt = now;
               this.phrases.fixed(climateId).ifPresent(this.speaker::say);
            } else if (c.sayClimate
               && c.climateRepeatSeconds > 0.0F
               && !this.climate.equals("mild")
               && (float)(now - this.climateSaidAt) >= c.climateRepeatSeconds * 1000.0F) {
               this.climateSaidAt = now;
               this.phrases.fixed(this.climate).ifPresent(this.speaker::say);
            }

            if (c.weatherReportMinutes > 0.0F && (float)(now - this.lastWeatherReportAt) >= c.weatherReportMinutes * 60000.0F) {
               this.lastWeatherReportAt = now;

               String reportId = switch (px) {
                  case "rain" -> "weather_rain";
                  case "snow" -> "weather_snow";
                  case "thunder" -> "weather_thunder";
                  default -> dark ? "weather_night" : "weather_clear";
               };
               this.phrases.fixed(reportId).ifPresent(this.speaker::say);
               if (c.sayClimate && !cl.equals("mild")) {
                  this.phrases.fixed(cl).ifPresent(this.speaker::say);
               }
            }
         }
      }
   }

   private static String precipitation(Level level, BlockPos pos) {
      if (!level.isRaining()) {
         return "none";
      } else {
         Biome biome = (Biome)level.getBiome(pos).value();
         if (!biome.hasPrecipitation()) {
            return "none";
         } else if (biome.coldEnoughToSnow(pos, level.getSeaLevel())) {
            return "snow";
         } else {
            return level.isThundering() ? "thunder" : "rain";
         }
      }
   }

   private static String climate(Level level, BlockPos pos) {
      Biome biome = (Biome)level.getBiome(pos).value();
      if (biome.coldEnoughToSnow(pos, level.getSeaLevel())) {
         return "cold";
      } else {
         return biome.getBaseTemperature() >= 1.5F ? "hot" : "mild";
      }
   }

   private void sayCounted(String action, String itemPath, int amount) {
      String plain = action.equals("hold") ? "hold" : action;
      Optional<Phrase> noun = this.phrases.forItem("hold", itemPath);
      Optional<Phrase> whole = this.phrases.forItem(plain, itemPath);
      if (!noun.isEmpty() && !whole.isEmpty()) {
         Optional<Phrase> unit = amount > 1 ? this.phrases.countUnit("count", amount, this.phrases.measureOf(noun.get().key())) : Optional.empty();
         if (unit.isEmpty()) {
            Phrase p = whole.get();
            if (action.equals("hold")) {
               NarratorHud.showHeld(p);
            }

            this.speaker.say(p);
         } else {
            Phrase w = whole.get();
            Phrase u = unit.get();
            LanguagePacks.Meta meta = this.phrases.meta();
            boolean cjk = meta.wordSeparator().isEmpty();
            String sep = cjk ? "，" : ", ";
            String text = trimEnd(w.text()) + sep + u.text();
            String reading = w.hasReading() ? w.reading() + " " + u.reading() : "";
            String nounEn = plural(noun.get().en(), amount);

            String en = switch (action) {
               case "pickup" -> "I picked up " + amount + " " + nounEn + ".";
               case "drop" -> "I dropped " + amount + " " + nounEn + ".";
               default -> amount + " " + nounEn;
            };
            if (meta.language().equals("en")) {
               en = "";
            }

            List<Phrase.Part> parts = new ArrayList<>();

            for (Phrase.Part part : w.parts()) {
               parts.add(part);
            }

            if (!parts.isEmpty()) {
               Phrase.Part last = parts.removeLast();
               parts.add(new Phrase.Part(last.text() + (cjk ? "，" : ","), last.reading(), last.role()));
            }

            parts.addAll(u.parts());
            Phrase display = new Phrase(w.id(), plain, w.key(), text, reading, en, parts);
            if (action.equals("hold")) {
               NarratorHud.showHeld(display);
            }

            this.speaker.saySequence(display, List.of(w, u));
         }
      }
   }

   private static String trimEnd(String text) {
      while (!text.isEmpty() && "。！？.!?".indexOf(text.charAt(text.length() - 1)) >= 0) {
         text = text.substring(0, text.length() - 1);
      }

      return text;
   }

   private static String plural(String en, int amount) {
      if (amount == 1 || NO_PLURAL.contains(en) || en.endsWith("s")) {
         return en;
      } else if (en.endsWith("y") && !en.endsWith("ey")) {
         return en.substring(0, en.length() - 1) + "ies";
      } else {
         return !en.endsWith("ch") && !en.endsWith("sh") && !en.endsWith("x") ? en + "s" : en + "es";
      }
   }

   public void sayLookedAt(Minecraft mc) {
      HitResult hit = mc.hitResult;
      if (mc.level != null && hit != null) {
         Optional<Phrase> word = Optional.empty();
         if (hit instanceof BlockHitResult block && hit.getType() == Type.BLOCK) {
            word = this.phrases.word("block", blockPath(mc.level.getBlockState(block.getBlockPos()).getBlock()));
         } else if (hit instanceof EntityHitResult entity) {
            word = this.phrases.word("entity", entityPath(entity.getEntity()));
         }

         word.ifPresent(this.speaker::sayNow);
      }
   }

   @Nullable
   private Phrase currentState(Minecraft mc, LocalPlayer player) {
      if (player.isSleeping()) {
         return this.phrases.fixed("sleep").orElse(null);
      } else {
         Entity vehicle = player.getVehicle();
         if (vehicle != null) {
            return this.phrases.forEntity("ride", entityPath(vehicle)).orElse(null);
         } else {
            if (mc.gameMode != null && mc.gameMode.isDestroying() && mc.hitResult instanceof BlockHitResult hit && mc.level != null) {
               Optional<Phrase> p = this.breakPhrase(player.getMainHandItem(), blockPath(mc.level.getBlockState(hit.getBlockPos()).getBlock()));
               if (p.isPresent()) {
                  return p.get();
               }
            }

            if (player.isUsingItem()) {
               ItemStack stack = player.getUseItem();
               if (stack.has(DataComponents.FOOD) || stack.has(DataComponents.CONSUMABLE)) {
                  String path = itemPath(stack);
                  Optional<Phrase> p = this.phrases.forItem("drink", path).or(() -> this.phrases.forItem("eat", path));
                  if (p.isPresent()) {
                     return p.get();
                  }
               }
            }

            if (player.fishing != null) {
               return this.phrases.fixed("fish").orElse(null);
            } else {
               boolean moving = player.getDeltaMovement().horizontalDistanceSqr() > 1.0E-4;
               if (!player.isFallFlying() && (!player.getAbilities().flying || !moving)) {
                  Input keys = player.input.keyPresses;
                  boolean pressing = keys.forward() || keys.backward() || keys.left() || keys.right();
                  if (!player.isInWater() || player.onGround() || !moving && !keys.jump()) {
                     if (player.onClimbable() && !player.onGround()) {
                        return this.phrases.fixed("climb").orElse(null);
                     } else if (player.isSprinting() && moving) {
                        return this.phrases.fixed("sprint").orElse(null);
                     } else if (player.isShiftKeyDown()) {
                        return this.phrases.fixed("sneak").orElse(null);
                     } else {
                        return pressing && moving && player.onGround() ? this.phrases.fixed("walk").orElse(null) : null;
                     }
                  } else {
                     return this.phrases.fixed("swim").orElse(null);
                  }
               } else {
                  return this.phrases.fixed("fly").orElse(null);
               }
            }
         }
      }
   }

   private void resetTrackers(LocalPlayer player) {
      this.stateId = null;
      this.lastCounts = null;
      this.lastHeld = player.getMainHandItem().getItem();
      this.lastHealth = player.getHealth();
      this.wasDead = player.isDeadOrDying();
      this.wasOnGround = player.onGround();
      this.wasDark = null;
      this.precip = null;
      this.climate = null;
      this.pendingClimate = null;
      this.recentlyHit.clear();
      this.lastWeatherReportAt = System.currentTimeMillis();
      this.hungrySaid = player.getFoodData().getFoodLevel() <= 6;
   }

   private static boolean isAnimalFood(ItemStack stack) {
      return !stack.isEmpty() && (stack.has(DataComponents.FOOD) || ANIMAL_FOOD.contains(itemPath(stack)));
   }

   static String blockPath(Block block) {
      return BuiltInRegistries.BLOCK.getKey(block).getPath();
   }

   static String itemPath(ItemStack stack) {
      return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
   }

   static String entityPath(Entity entity) {
      return entity instanceof Sheep sheep ? sheep.getColor().getSerializedName() + "_sheep" : EntityType.getKey(entity.getType()).getPath();
   }

   private record PendingPlace(Block block, BlockPos clicked, BlockPos inFront, long expiresAt) {
   }
}
