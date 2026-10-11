package net.taxsmp.packloader;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.player.ResourcePackInfo;
import com.velocitypowered.api.scheduler.ScheduledTask;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

@Plugin(
    id = "taxsmp-packloader",
    name = "TaxSMP PackLoader",
    version = "1.0.0",
    description = "Sends the TaxSMP resource pack once when a player joins the network",
    authors = {"TaxSMP"},
    dependencies = {@Dependency(id = "floodgate", optional = true)})
public final class PackLoader {

  private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
  private static final String PERMISSION = "taxsmp.packloader.admin";

  private final ProxyServer proxy;
  private final Logger logger;
  private final Path dataDirectory;
  private final HttpClient http = HttpClient.newBuilder()
      .followRedirects(HttpClient.Redirect.NORMAL)
      .connectTimeout(Duration.ofSeconds(10))
      .build();
  private final AtomicBoolean checking = new AtomicBoolean();

  private volatile Settings settings;
  private volatile Pack pack;
  private ScheduledTask checkTask;

  record Settings(String repo, String branch, String path, UUID packId, boolean force,
                  Component prompt, int checkEveryMinutes, boolean skipBedrock) {}

  /** The pack zip at one exact GitHub commit, and the SHA-1 of that file. */
  record Pack(String commit, String url, byte[] sha1) {
    String shortCommit() {
      return commit.substring(0, 7);
    }

    String sha1Hex() {
      return HexFormat.of().formatHex(sha1);
    }
  }

  @Inject
  public PackLoader(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
    this.proxy = proxy;
    this.logger = logger;
    this.dataDirectory = dataDirectory;
  }

  @Subscribe
  public void onProxyInitialize(ProxyInitializeEvent event) {
    try {
      settings = loadSettings();
    } catch (IOException | RuntimeException e) {
      logger.error("plugins/taxsmp-packloader/config.yml is broken, using the default settings", e);
      settings = defaultSettings();
    }
    proxy.getCommandManager().register(
        proxy.getCommandManager().metaBuilder("taxpack").plugin(this).build(), new TaxPackCommand());
    startChecks(null);
  }

  @Subscribe
  public void onServerPostConnect(ServerPostConnectEvent event) {
    // Moving between NA and EU keeps the pack loaded, so only send it for the first server after joining.
    if (event.getPreviousServer() == null) {
      sendPack(event.getPlayer());
    }
  }

  @Subscribe
  public void onPackStatus(PlayerResourcePackStatusEvent event) {
    if (!settings.packId().equals(event.getPackId())) {
      return;
    }
    switch (event.getStatus()) {
      case FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD -> logger.warn(
          "{} couldn't load the TaxSMP pack ({})", event.getPlayer().getUsername(), event.getStatus());
      default -> { }
    }
  }

  private void sendPack(Player player) {
    Pack current = pack;
    if (current == null) {
      logger.warn("Not sending the pack to {}: it hasn't been downloaded from GitHub yet", player.getUsername());
      return;
    }
    if (settings.skipBedrock() && isBedrock(player)) {
      return;
    }
    ResourcePackInfo info = proxy.createResourcePackBuilder(current.url())
        .setId(settings.packId())
        .setHash(current.sha1())
        .setShouldForce(settings.force())
        .setPrompt(settings.prompt())
        .build();
    player.sendResourcePackOffer(info);
  }

  private boolean isBedrock(Player player) {
    return proxy.getPluginManager().getPlugin("floodgate")
        .flatMap(PluginContainer::getInstance)
        .map(floodgate -> {
          try {
            Class<?> api = Class.forName(
                "org.geysermc.floodgate.api.FloodgateApi", true, floodgate.getClass().getClassLoader());
            Object instance = api.getMethod("getInstance").invoke(null);
            return (Boolean) api.getMethod("isFloodgatePlayer", UUID.class).invoke(instance, player.getUniqueId());
          } catch (ReflectiveOperationException | ClassCastException e) {
            return false;
          }
        })
        .orElse(false);
  }

  /** Checks GitHub now, then every check-every-minutes. */
  private void startChecks(CommandSource notify) {
    if (checkTask != null) {
      checkTask.cancel();
    }
    proxy.getScheduler().buildTask(this, () -> checkGitHub(notify)).schedule();
    int minutes = settings.checkEveryMinutes();
    checkTask = minutes <= 0 ? null : proxy.getScheduler().buildTask(this, () -> checkGitHub(null))
        .delay(minutes, TimeUnit.MINUTES)
        .repeat(minutes, TimeUnit.MINUTES)
        .schedule();
  }

  private void checkGitHub(CommandSource notify) {
    if (!checking.compareAndSet(false, true)) {
      tell(notify, "&eAlready checking GitHub, try again in a moment.");
      return;
    }
    try {
      Pack latest = fetchLatest(settings);
      boolean changed = pack == null || !pack.commit().equals(latest.commit());
      pack = latest;
      if (changed) {
        logger.info("Using the TaxSMP pack from commit {} (SHA-1 {})", latest.shortCommit(), latest.sha1Hex());
      }
      tell(notify, changed
          ? "&aNow using the pack from commit &f" + latest.shortCommit() + "&a. New joins get this version."
          : "&aThe pack is already up to date (&f" + latest.shortCommit() + "&a).");
    } catch (Exception e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      String kept = pack == null ? "" : " Still using commit " + pack.shortCommit() + ".";
      logger.warn("Couldn't check GitHub for the TaxSMP pack: {}{}", e.getMessage(), kept);
      tell(notify, "&cCouldn't check GitHub: " + e.getMessage() + "&7" + kept);
    } finally {
      checking.set(false);
    }
  }

  private Pack fetchLatest(Settings s) throws Exception {
    String commit = latestCommit(s);
    Pack current = pack;
    if (current != null && current.commit().equals(commit)) {
      return current;
    }
    // Pinned to the commit, so GitHub's cache can never give players a different file than the one we hashed.
    String url = "https://raw.githubusercontent.com/%s/%s/%s".formatted(s.repo(), commit, s.path());
    HttpResponse<byte[]> response = http.send(request(url).build(), HttpResponse.BodyHandlers.ofByteArray());
    if (response.statusCode() != 200) {
      throw new IOException("downloading " + url + " returned HTTP " + response.statusCode());
    }
    return new Pack(commit, url, MessageDigest.getInstance("SHA-1").digest(response.body()));
  }

  private String latestCommit(Settings s) throws Exception {
    String url = "https://api.github.com/repos/%s/commits?sha=%s&path=%s&per_page=1".formatted(
        s.repo(), URLEncoder.encode(s.branch(), StandardCharsets.UTF_8),
        URLEncoder.encode(s.path(), StandardCharsets.UTF_8));
    HttpResponse<String> response = http.send(
        request(url).header("Accept", "application/vnd.github+json").build(),
        HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IOException("GitHub returned HTTP " + response.statusCode() + " for " + url);
    }
    JsonArray commits = JsonParser.parseString(response.body()).getAsJsonArray();
    if (commits.isEmpty()) {
      throw new IOException(s.path() + " was not found on branch " + s.branch() + " of " + s.repo());
    }
    return commits.get(0).getAsJsonObject().get("sha").getAsString();
  }

  private static HttpRequest.Builder request(String url) {
    return HttpRequest.newBuilder(URI.create(url))
        .header("User-Agent", "TaxSMP-PackLoader")
        .timeout(Duration.ofSeconds(60));
  }

  private Settings loadSettings() throws IOException {
    Path file = dataDirectory.resolve("config.yml");
    if (Files.notExists(file)) {
      Files.createDirectories(dataDirectory);
      try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
        Files.copy(in, file);
      }
    }
    try (Reader reader = Files.newBufferedReader(file)) {
      return parseSettings(new Yaml().load(reader));
    }
  }

  private Settings defaultSettings() {
    try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
      return parseSettings(new Yaml().load(in));
    } catch (IOException e) {
      throw new IllegalStateException("The default config.yml is missing from the plugin jar", e);
    }
  }

  private static Settings parseSettings(Map<String, Object> yaml) {
    if (yaml == null) {
      throw new IllegalArgumentException("config.yml is empty");
    }
    return new Settings(
        String.valueOf(yaml.getOrDefault("github-repo", "jonathankristiansen63-byte/taxsmp-pack-")),
        String.valueOf(yaml.getOrDefault("github-branch", "main")),
        String.valueOf(yaml.getOrDefault("pack-path", "dist/TaxSMP-Pack.zip")),
        UUID.fromString(String.valueOf(yaml.getOrDefault("pack-id", "b1c37d58-7d24-4ea8-abd6-e63cae91af50"))),
        Boolean.parseBoolean(String.valueOf(yaml.getOrDefault("force", true))),
        LEGACY.deserialize(String.valueOf(yaml.getOrDefault("prompt", "&6&lTaxSMP &fneeds its resource pack to play!"))),
        Integer.parseInt(String.valueOf(yaml.getOrDefault("check-every-minutes", 10))),
        Boolean.parseBoolean(String.valueOf(yaml.getOrDefault("skip-bedrock", true))));
  }

  private static void tell(CommandSource source, String message) {
    if (source != null) {
      source.sendMessage(LEGACY.deserialize("&8[&6TaxSMP&8] &r" + message));
    }
  }

  private final class TaxPackCommand implements SimpleCommand {

    @Override
    public void execute(Invocation invocation) {
      CommandSource source = invocation.source();
      String[] args = invocation.arguments();
      if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
        try {
          settings = loadSettings();
        } catch (IOException | RuntimeException e) {
          tell(source, "&cconfig.yml has a mistake, kept the old settings: " + e.getMessage());
          return;
        }
        tell(source, "&7Config reloaded, checking GitHub for the latest pack...");
        startChecks(source);
        return;
      }
      Pack current = pack;
      if (current == null) {
        tell(source, "&cNo pack loaded yet. Check the console, then try &f/taxpack reload&c.");
        return;
      }
      tell(source, "&7Pack commit: &f" + current.shortCommit());
      tell(source, "&7SHA-1: &f" + current.sha1Hex());
      tell(source, "&7URL: &f" + current.url());
      tell(source, "&7Use &f/taxpack reload &7after you push a new pack to GitHub.");
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
      return invocation.source().hasPermission(PERMISSION);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
      return invocation.arguments().length <= 1 ? List.of("reload") : List.of();
    }
  }
}
