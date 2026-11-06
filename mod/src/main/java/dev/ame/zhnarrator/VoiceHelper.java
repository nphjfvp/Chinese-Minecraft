package dev.ame.zhnarrator;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class VoiceHelper {
   private static final String UV_VERSION = "0.11.23";
   private static final Map<String, VoiceHelper.UvBuild> UV = Map.of(
      "mac-arm64",
      new VoiceHelper.UvBuild("uv-aarch64-apple-darwin.tar.gz", "71ef9de85db820749b3b12b7585624ee279e9c5afcbc6f8236bc3d628c4305b0", "uv"),
      "windows-x64",
      new VoiceHelper.UvBuild("uv-x86_64-pc-windows-msvc.zip", "02ad29f07e674d68726ba3bb1ff25b335d83515756e2b1a194bb56c3cc30e07c", "uv.exe"),
      "linux-x64",
      new VoiceHelper.UvBuild("uv-x86_64-unknown-linux-gnu.tar.gz", "e12c4cda2fe8c305510a78380a88f2c32a27e90cdcd123cefd2873388f0ebb5f", "uv"),
      "linux-arm64",
      new VoiceHelper.UvBuild("uv-aarch64-unknown-linux-gnu.tar.gz", "1873a77350f6621279ae1a0d2227f2bd8b67131598f14a7eb0ba2215d3da2c98", "uv")
   );
   private static final List<String> FILES = List.of("server.py", "recognizer.py", "pyproject.toml", "uv.lock", ".python-version");
   private static final String RESOURCES = "/zhnarrator_voicehelper/";
   private static final long START_TIMEOUT_MS = 2700000L;
   private static final long FIRST_START_BYTES = 2800000000L;
   private static final long MODEL_BYTES = 1270000000L;
   private static final long MODEL_LOAD_MS = 14000L;
   private final SpeechClient speech;
   private volatile VoiceHelper.State state = VoiceHelper.State.IDLE;
   private volatile boolean startedByUs;
   private volatile long startedAt;
   private volatile long setupDone = -1L;
   private volatile long setupTotal;
   private volatile boolean modelLoading;
   private long silentSince;
   private volatile long modelLoadingSince;
   private boolean autoTried;
   private final List<Component> notices = new ArrayList<>();

   VoiceHelper(SpeechClient speech) {
      this.speech = speech;
   }

   VoiceHelper(SpeechClient speech, VoiceHelper previous) {
      this(speech);
      if (previous != null) {
         this.state = previous.state == VoiceHelper.State.RUNNING ? VoiceHelper.State.IDLE : previous.state;
         this.modelLoading = previous.state == VoiceHelper.State.RUNNING || previous.modelLoading;
         this.modelLoadingSince = System.currentTimeMillis();
         this.startedByUs = previous.startedByUs;
         this.startedAt = previous.startedAt;
         this.autoTried = previous.autoTried;
      }
   }

   public VoiceHelper.State state() {
      return this.state;
   }

   public boolean busy() {
      return this.state == VoiceHelper.State.PREPARING || this.state == VoiceHelper.State.STARTING;
   }

   public static Path dir() {
      return ZhNarratorClient.gameDir().resolve("learn-or-die").resolve("voice-helper");
   }

   static String platform() {
      String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
      String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
      boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
      boolean arm = arch.equals("aarch64") || arch.equals("arm64");
      if (!os.startsWith("mac")) {
         if (os.startsWith("windows")) {
            return x64 ? "windows-x64" : null;
         } else if (os.contains("linux")) {
            return x64 ? "linux-x64" : (arm ? "linux-arm64" : null);
         } else {
            return null;
         }
      } else {
         return appleSilicon() && macMajor() >= 14 ? "mac-arm64" : null;
      }
   }

   public static boolean supported() {
      return platform() != null;
   }

   private static boolean appleSilicon() {
      if (System.getProperty("os.arch", "").equals("aarch64")) {
         return true;
      } else {
         try {
            Process p = new ProcessBuilder("sysctl", "-n", "hw.optional.arm64").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.US_ASCII).trim();
            return out.equals("1");
         } catch (IOException var2) {
            return false;
         }
      }
   }

   private static int macMajor() {
      try {
         return Integer.parseInt(System.getProperty("os.version", "0").split("\\.")[0]);
      } catch (NumberFormatException var1) {
         return 0;
      }
   }

   public boolean installed() {
      String p = platform();
      return p != null && Files.isRegularFile(dir().resolve("uv").resolve(UV.get(p).program()));
   }

   void tick(Minecraft mc) {
      this.flushNotices(mc);
      NarratorConfig c = ZhNarratorClient.config;
      boolean voiceOn = c.enabled && (c.quizMode.equals("speech") || c.quizMode.equals("mixed"));
      if (voiceOn || this.busy() || this.state == VoiceHelper.State.RUNNING) {
         boolean up = this.speech.isAvailable();
         if (this.speech.checked()) {
            switch (this.state) {
               case IDLE:
                  if (up) {
                     this.state = VoiceHelper.State.RUNNING;
                     this.modelLoading = false;
                  } else if (this.speech.loadingModel()) {
                     if (!this.modelLoading) {
                        this.modelLoadingSince = System.currentTimeMillis();
                     }

                     this.modelLoading = true;
                     this.silentSince = 0L;
                     if (this.setupDone < 0L) {
                        this.watchSetup(dir(), 1270000000L, () -> this.modelLoading && ZhNarratorClient.voiceHelper == this);
                     }
                  } else if (!this.modelLoading || this.silentSince != 0L && System.currentTimeMillis() - this.silentSince >= 15000L) {
                     if (c.voiceHelperAuto && !this.autoTried && supported()) {
                        this.modelLoading = false;
                        this.autoTried = true;
                        this.start(mc);
                     } else {
                        this.modelLoading = false;
                     }
                  } else if (this.silentSince == 0L) {
                     this.silentSince = System.currentTimeMillis();
                  }
               case PREPARING:
               default:
                  break;
               case STARTING:
                  if (up) {
                     this.state = VoiceHelper.State.RUNNING;
                     this.notice(Component.translatable("zhnarrator.voice.ready", new Object[]{ZhNarratorClient.keyName(ZhNarratorClient.keyTalk)}));
                  } else if (System.currentTimeMillis() - this.startedAt > 2700000L) {
                     this.state = VoiceHelper.State.FAILED;
                     this.notice(Component.translatable("zhnarrator.voice.timeout"));
                  }
                  break;
               case RUNNING:
                  if (!up) {
                     this.state = VoiceHelper.State.IDLE;
                     this.startedByUs = false;
                  }
            }
         }
      }
   }

   public void start(Minecraft mc) {
      if (!this.busy()) {
         String platform = platform();
         if (platform == null) {
            this.state = VoiceHelper.State.FAILED;
            this.notice(Component.translatable("zhnarrator.voice.unsupported"));
         } else {
            this.autoTried = true;
            this.state = VoiceHelper.State.PREPARING;
            Thread worker = new Thread(() -> {
               try {
                  Path dir = dir();
                  Files.createDirectories(dir);
                  boolean firstStart = !Files.isDirectory(dir.resolve(".venv"));

                  for (String name : FILES) {
                     copyResource(name, dir.resolve(name));
                  }

                  VoiceHelper.UvBuild build = UV.get(platform);
                  Path uv = dir.resolve("uv").resolve(build.program());
                  if (!Files.isRegularFile(uv)) {
                     this.notice(Component.translatable("zhnarrator.voice.downloading"));
                     downloadUv(build, uv);
                  }

                  Path script = writeLauncher(platform, dir, port(), ZhNarratorClient.language());
                  this.startedAt = System.currentTimeMillis();
                  this.startedByUs = true;
                  this.state = VoiceHelper.State.STARTING;
                  openWindow(platform, dir, script);
                  this.watchSetup(dir, firstStart ? 2800000000L : 1270000000L, () -> this.state == VoiceHelper.State.STARTING);
                  this.notice(Component.translatable(firstStart ? "zhnarrator.voice.first_start" : "zhnarrator.voice.starting"));
                  ZhNarratorClient.LOGGER.info("voice helper window opened: {}", script);
               } catch (Exception var7) {
                  this.state = VoiceHelper.State.FAILED;
                  this.startedByUs = false;
                  ZhNarratorClient.LOGGER.error("voice helper could not start", var7);
                  this.notice(Component.translatable("zhnarrator.voice.failed", new Object[]{String.valueOf(var7.getMessage())}));
               }
            }, "Learn or Die voice helper");
            worker.setDaemon(true);
            worker.start();
         }
      }
   }

   private void watchSetup(Path dir, long total, BooleanSupplier going) {
      long baseline = folderSize(dir);
      this.setupTotal = total;
      this.setupDone = 0L;
      Thread watcher = new Thread(() -> {
         while (going.getAsBoolean()) {
            this.setupDone = Math.max(0L, folderSize(dir) - baseline);

            try {
               Thread.sleep(2000L);
            } catch (InterruptedException var6) {
               break;
            }
         }

         this.setupDone = -1L;
      }, "Learn or Die voice helper progress");
      watcher.setDaemon(true);
      watcher.start();
   }

   public boolean modelLoading() {
      return this.modelLoading;
   }

   public int loadingPercent() {
      return (int)Math.min(99L, Math.max(0L, System.currentTimeMillis() - this.modelLoadingSince) * 100L / 14000L);
   }

   public long setupDone() {
      return this.setupDone;
   }

   public long setupTotal() {
      return this.setupTotal;
   }

   public int setupPercent() {
      long done = this.setupDone;
      return done >= 0L && this.setupTotal > 0L ? (int)Math.min(99L, done * 100L / this.setupTotal) : -1;
   }

   private static long folderSize(Path dir) {
      try {
         long var2;
         try (Stream<Path> files = Files.walk(dir)) {
            var2 = files.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).mapToLong(p -> {
               try {
                  return Files.size(p);
               } catch (IOException var2x) {
                  return 0L;
               }
            }).sum();
         }

         return var2;
      } catch (UncheckedIOException | IOException var6) {
         return 0L;
      }
   }

   void stopIfOurs() {
      if (this.startedByUs) {
         try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500L)).build();
            http.send(
               HttpRequest.newBuilder(URI.create(base() + "/quit")).timeout(Duration.ofSeconds(1L)).POST(BodyPublishers.noBody()).build(),
               BodyHandlers.discarding()
            );
         } catch (Exception var2) {
         }
      }
   }

   private static String base() {
      return ZhNarratorClient.config.speechHelperUrl.replaceAll("/+$", "");
   }

   private static int port() {
      try {
         int port = URI.create(base()).getPort();
         return port > 0 ? port : 17645;
      } catch (IllegalArgumentException var1) {
         return 17645;
      }
   }

   private void notice(Component message) {
      synchronized (this.notices) {
         this.notices.add(message);
      }
   }

   private void flushNotices(Minecraft mc) {
      if (mc.player != null) {
         synchronized (this.notices) {
            for (Component m : this.notices) {
               mc.player.sendSystemMessage(Component.literal("[Teacher] ").append(m).withStyle(ChatFormatting.YELLOW));
            }

            this.notices.clear();
         }
      }
   }

   private static void copyResource(String name, Path target) throws IOException {
      try (InputStream in = VoiceHelper.class.getResourceAsStream("/zhnarrator_voicehelper/" + name)) {
         if (in == null) {
            throw new IOException(name + " is missing from the mod");
         }

         Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
      }
   }

   private static void downloadUv(VoiceHelper.UvBuild build, Path target) throws Exception {
      URI uri = URI.create("https://github.com/astral-sh/uv/releases/download/0.11.23/" + build.archive());
      HttpClient http = HttpClient.newBuilder().followRedirects(Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20L)).build();
      HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(10L)).GET().build(), BodyHandlers.ofByteArray());
      if (response.statusCode() != 200) {
         throw new IOException("uv download answered HTTP " + response.statusCode());
      } else {
         byte[] archive = response.body();
         String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archive));
         if (!sha256.equals(build.sha256())) {
            throw new IOException("the uv download is damaged (checksum mismatch)");
         } else {
            byte[] program = build.archive().endsWith(".zip") ? fromZip(archive, build.program()) : fromTarGz(archive, build.program());
            Files.createDirectories(target.getParent());
            Path part = target.resolveSibling(build.program() + ".part");
            Files.write(part, program);
            if (!build.program().endsWith(".exe")) {
               Files.setPosixFilePermissions(part, PosixFilePermissions.fromString("rwxr-xr-x"));
            }

            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
         }
      }
   }

   private static byte[] fromZip(byte[] archive, String program) throws IOException {
      try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
         for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
            if (!e.isDirectory() && baseName(e.getName()).equals(program)) {
               return zip.readAllBytes();
            }
         }
      }

      throw new IOException("no " + program + " in the uv archive");
   }

   private static byte[] fromTarGz(byte[] archive, String program) throws IOException {
      try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(archive))) {
         byte[] header = new byte[512];
         String longName = null;

         while (in.readNBytes(header, 0, 512) == 512 && !allZero(header)) {
            String name = longName != null ? longName : cString(header, 0, 100);
            longName = null;
            if (new String(header, 257, 6, StandardCharsets.US_ASCII).equals("ustar\u0000")) {
               String prefix = cString(header, 345, 155);
               if (!prefix.isEmpty()) {
                  name = prefix + "/" + name;
               }
            }

            long size = octal(header, 124, 12);
            byte type = header[156];
            boolean wanted = (type == 48 || type == 0) && baseName(name).equals(program);
            byte[] data = type != 76 && !wanted ? null : in.readNBytes((int)size);
            if (data == null) {
               in.skipNBytes(size);
            }

            in.skipNBytes((512L - size % 512L) % 512L);
            if (type == 76) {
               longName = cString(data, 0, data.length);
            } else if (wanted) {
               return data;
            }
         }
      }

      throw new IOException("no " + program + " in the uv archive");
   }

   private static boolean allZero(byte[] block) {
      for (byte b : block) {
         if (b != 0) {
            return false;
         }
      }

      return true;
   }

   private static String cString(byte[] bytes, int offset, int length) {
      int end = offset;

      while (end < offset + length && end < bytes.length && bytes[end] != 0) {
         end++;
      }

      return new String(bytes, offset, end - offset, StandardCharsets.UTF_8);
   }

   private static long octal(byte[] bytes, int offset, int length) {
      String s = cString(bytes, offset, length).trim();
      return s.isEmpty() ? 0L : Long.parseLong(s, 8);
   }

   private static String baseName(String path) {
      String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
      return p.substring(p.lastIndexOf(47) + 1);
   }

   private static Path writeLauncher(String platform, Path dir, int port, String language) throws IOException {
      boolean windows = platform.startsWith("windows");
      Path script = dir.resolve(windows ? "start-voice-helper.cmd" : (platform.startsWith("mac") ? "start-voice-helper.command" : "start-voice-helper.sh"));
      String text;
      if (windows) {
         text = String.join(
            "\r\n",
            "@echo off",
            "title Learn or Die - voice helper",
            "cd /d \"%~dp0\"",
            "echo Learn or Die voice helper",
            "echo Keep this window open while you play. Close it to turn voice answers off.",
            "echo The first start downloads about 1.5 GB: Python, PyTorch and the speech model of your language. Please wait.",
            "echo.",
            "set \"UV_CACHE_DIR=%~dp0cache\\uv\"",
            "set \"UV_PYTHON_INSTALL_DIR=%~dp0python\"",
            "set \"UV_PYTHON_PREFERENCE=only-managed\"",
            "set \"HF_HOME=%~dp0cache\\huggingface\"",
            "set \"HF_HUB_DISABLE_TELEMETRY=1\"",
            "set \"HF_HUB_DISABLE_XET=1\"",
            "set \"ZH_HELPER_PORT=" + port + "\"",
            "set \"ZH_HELPER_LANG=" + language + "\"",
            "\"%~dp0uv\\uv.exe\" run --frozen python server.py",
            "if errorlevel 1 (",
            "  echo.",
            "  echo The voice helper stopped with an error, see the messages above.",
            "  pause",
            ")",
            ""
         );
      } else {
         text = String.join(
            "\n",
            "#!/bin/bash",
            "cd \"$(dirname \"$0\")\" || exit 1",
            "printf '\\033]0;Learn or Die - voice helper\\007'",
            "echo 'Learn or Die voice helper'",
            "echo 'Keep this window open while you play. Close it to turn voice answers off.'",
            "echo 'The first start downloads about 1.5 GB: Python, PyTorch and the speech model of your language. Please wait.'",
            "echo",
            "export UV_CACHE_DIR=\"$PWD/cache/uv\" UV_PYTHON_INSTALL_DIR=\"$PWD/python\" UV_PYTHON_PREFERENCE=only-managed",
            "export HF_HOME=\"$PWD/cache/huggingface\" HF_HUB_DISABLE_TELEMETRY=1 HF_HUB_DISABLE_XET=1 ZH_HELPER_PORT=" + port + " ZH_HELPER_LANG=" + language,
            "\"$PWD/uv/uv\" run --frozen python server.py",
            "code=$?",
            "if [ \"$code\" -ne 0 ]; then",
            "  echo",
            "  echo 'The voice helper stopped with an error, see the messages above.'",
            "  read -r -p 'Press Enter to close this window. ' _",
            "fi",
            "exit \"$code\"",
            ""
         );
      }

      Files.writeString(script, text, StandardCharsets.UTF_8);
      if (!windows) {
         Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
      }

      return script;
   }

   private static void openWindow(String platform, Path dir, Path script) throws IOException {
      String s = script.toString();
      if (platform.startsWith("windows")) {
         new ProcessBuilder("cmd.exe", "/c", "start", "\"Learn or Die voice helper\"", s).directory(dir.toFile()).start();
      } else if (platform.startsWith("mac")) {
         new ProcessBuilder("open", "-a", "Terminal", s).start();
      } else {
         for (List<String> command : List.of(
            List.of("x-terminal-emulator", "-e", s),
            List.of("gnome-terminal", "--", s),
            List.of("konsole", "-e", s),
            List.of("xfce4-terminal", "-x", s),
            List.of("kitty", s),
            List.of("alacritty", "-e", s),
            List.of("xterm", "-e", s)
         )) {
            try {
               new ProcessBuilder(command).directory(dir.toFile()).start();
               return;
            } catch (IOException var8) {
            }
         }

         new ProcessBuilder(s).directory(dir.toFile()).redirectErrorStream(true).redirectOutput(dir.resolve("helper.log").toFile()).start();
      }
   }

   public static enum State {
      IDLE,
      PREPARING,
      STARTING,
      RUNNING,
      FAILED;
   }

   private record UvBuild(String archive, String sha256, String program) {
   }
}
