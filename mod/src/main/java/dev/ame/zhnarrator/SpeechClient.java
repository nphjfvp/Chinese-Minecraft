package dev.ame.zhnarrator;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class SpeechClient {
   private static final long HEALTH_EVERY_MS = 5000L;
   private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500L)).build();
   private volatile boolean healthy;
   private volatile boolean up;
   private volatile boolean checked;
   private long lastHealthCheck;
   private CompletableFuture<Void> healthInFlight;

   private String base() {
      return ZhNarratorClient.config.speechHelperUrl.replaceAll("/+$", "");
   }

   private static String language() {
      return URLEncoder.encode(ZhNarratorClient.language(), StandardCharsets.UTF_8);
   }

   public boolean isAvailable() {
      long now = System.currentTimeMillis();
      if (now - this.lastHealthCheck > 5000L && (this.healthInFlight == null || this.healthInFlight.isDone())) {
         this.lastHealthCheck = now;
         HttpRequest req = HttpRequest.newBuilder(URI.create(this.base() + "/health?language=" + language())).timeout(Duration.ofSeconds(2L)).GET().build();
         this.healthInFlight = this.http.sendAsync(req, BodyHandlers.ofString()).thenAccept(r -> {
            boolean ready = false;
            this.up = r.statusCode() == 200;
            if (r.statusCode() == 200) {
               try {
                  JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
                  ready = !o.has("ready") || o.get("ready").getAsBoolean();
               } catch (RuntimeException var4) {
                  ready = true;
               }
            }

            this.healthy = ready;
            this.checked = true;
         }).exceptionally(e -> {
            this.healthy = false;
            this.up = false;
            this.checked = true;
            return null;
         });
      }

      return this.healthy;
   }

   public boolean loadingModel() {
      return this.up && !this.healthy;
   }

   public boolean checked() {
      return this.checked;
   }

   public CompletableFuture<Boolean> status() {
      HttpRequest req = HttpRequest.newBuilder(URI.create(this.base() + "/status")).timeout(Duration.ofSeconds(1L)).GET().build();
      return this.http
         .sendAsync(req, BodyHandlers.ofString())
         .thenApply(r -> r.statusCode() == 200 && JsonParser.parseString(r.body()).getAsJsonObject().get("done").getAsBoolean())
         .exceptionally(e -> false);
   }

   /** Starts recording from the microphone chosen in the settings; completes with the device's name, "" on failure. */
   public CompletableFuture<String> start() {
      JsonObject body = new JsonObject();
      String device = ZhNarratorClient.config.microphone;
      if (device != null && !device.isEmpty()) {
         body.addProperty("device", device);
      }

      HttpRequest req = HttpRequest.newBuilder(URI.create(this.base() + "/start"))
         .timeout(Duration.ofSeconds(2L))
         .header("Content-Type", "application/json")
         .POST(BodyPublishers.ofString(body.toString()))
         .build();
      return this.http.sendAsync(req, BodyHandlers.ofString()).thenApply(r -> {
         if (r.statusCode() != 200) {
            return "";
         } else {
            JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
            return o.has("device") ? o.get("device").getAsString() : "";
         }
      }).exceptionally(e -> "");
   }

   /** The microphones the helper can use; empty if it is not running. Asks the helper and waits briefly. */
   public List<String> devices() {
      List<String> out = new ArrayList<>();

      try {
         HttpRequest req = HttpRequest.newBuilder(URI.create(this.base() + "/devices")).timeout(Duration.ofMillis(800L)).GET().build();
         String reply = this.http.send(req, BodyHandlers.ofString()).body();
         JsonParser.parseString(reply).getAsJsonObject().getAsJsonArray("devices").forEach(e -> out.add(e.getAsString()));
      } catch (Exception var4) {
         if (var4 instanceof InterruptedException) {
            Thread.currentThread().interrupt();
         }
      }

      return out;
   }

   public CompletableFuture<SpeechClient.Heard> stop(String expected, float threshold) {
      JsonObject body = new JsonObject();
      body.addProperty("language", ZhNarratorClient.language());
      if (expected != null) {
         body.addProperty("expected", expected);
         body.addProperty("threshold", threshold);
      }

      HttpRequest req = HttpRequest.newBuilder(URI.create(this.base() + "/stop"))
         .timeout(Duration.ofSeconds(20L))
         .header("Content-Type", "application/json")
         .POST(BodyPublishers.ofString(body.toString()))
         .build();
      return this.http
         .sendAsync(req, BodyHandlers.ofString())
         .thenApply(
            r -> {
               if (r.statusCode() != 200) {
                  throw new IllegalStateException("speech helper answered " + r.statusCode());
               } else {
                  JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
                  JsonObject cmp = o.getAsJsonObject("compare");
                  StringBuilder reading = new StringBuilder();
                  String readingKey = o.has("reading") ? "reading" : "pinyin";
                  if (o.has(readingKey) && o.get(readingKey).isJsonArray()) {
                     o.getAsJsonArray(readingKey).forEach(s -> reading.append(reading.isEmpty() ? "" : " ").append(s.getAsString()));
                  }

                  return new SpeechClient.Heard(
                     o.get("text").getAsString(),
                     reading.toString(),
                     o.get("confidence").getAsFloat(),
                     o.has("silent") && o.get("silent").getAsBoolean(),
                     cmp == null ? "wrong" : cmp.get("verdict").getAsString(),
                     cmp == null ? 0.0F : cmp.get("score").getAsFloat(),
                     cmp == null ? 99 : cmp.get("syllable_errors").getAsInt(),
                     cmp == null ? 0 : cmp.get("tone_errors").getAsInt(),
                     o.has("device") ? o.get("device").getAsString() : "",
                     o.has("peak") ? o.get("peak").getAsFloat() : -1.0F
                  );
               }
            }
         );
   }

   public record Heard(
      String text,
      String reading,
      float confidence,
      boolean silent,
      String verdict,
      float score,
      int syllableErrors,
      int toneErrors,
      String device,
      float peak
   ) {
   }
}
