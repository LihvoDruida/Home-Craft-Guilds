package ua.homecraft.guild.server;

import ua.homecraft.guild.HomeCraftGuildMod;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GuildSiteSync {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private static final ExecutorService SYNC_EXECUTOR = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "homecraft-guild-site-sync");
            thread.setDaemon(true);
            thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
            return thread;
        }
    });
    private static final AtomicBoolean SYNC_IN_FLIGHT = new AtomicBoolean(false);
    private static volatile long lastQueuedRevision = -1L;
    private static volatile long lastSuccessfulRevision = -1L;
    private static volatile long lastQueueMs = 0L;
    private static volatile long nextAllowedQueueMs = 0L;
    private static volatile int consecutiveFailures = 0;
    private static volatile int lastBodyHash = 0;

    private GuildSiteSync() {}

    public static void pushTerritoriesAsync() {
        if (!HomeCraftAddonIntegrations.shouldRunTerritorySiteSync(GuildServerEvents.server())) return;
        if (SYNC_EXECUTOR.isShutdown()) return;
        String secret = HomeCraftGuildConfig.serverSecret();
        if (secret == null || secret.length() < 16) return;
        long revision = GuildStore.territoryTopologyRevision();
        long now = System.currentTimeMillis();
        if (now < nextAllowedQueueMs) return;
        if (revision == lastSuccessfulRevision && now - lastQueueMs < 60_000L) return;
        if (revision == lastQueuedRevision && SYNC_IN_FLIGHT.get()) return;
        if (SYNC_IN_FLIGHT.get() && now - lastQueueMs < 15_000L) return;
        lastQueuedRevision = revision;
        lastQueueMs = now;
        if (!SYNC_IN_FLIGHT.compareAndSet(false, true)) return;

        String url = HomeCraftGuildConfig.apiBaseUrl() + "/api/guild/territories/update";
        try {
            SYNC_EXECUTOR.execute(() -> {
                try {
                    // Build the JSON body off the server tick thread. GuildStore access is synchronized,
                    // but serialization itself no longer steals milliseconds from Minecraft's main loop.
                    String body = GuildStore.territoriesJson();
                    int bodyHash = body.hashCode();
                    if (revision == lastSuccessfulRevision && bodyHash == lastBodyHash) {
                        return;
                    }
                    HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(5))
                            .header("Content-Type", "application/json")
                            .header("X-Server-Secret", secret)
                            .header("X-HomeCraft-Secret", secret)
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                            .build();
                    HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        recordFailure(now, "HTTP " + response.statusCode());
                    } else {
                        lastSuccessfulRevision = revision;
                        lastBodyHash = bodyHash;
                        consecutiveFailures = 0;
                        nextAllowedQueueMs = 0L;
                        if (HomeCraftGuildConfig.debug()) HomeCraftGuildMod.LOGGER.debug("Guild territory sync OK: {} revision={}", response.statusCode(), revision);
                    }
                } catch (Exception e) {
                    recordFailure(now, e.toString());
                } finally {
                    SYNC_IN_FLIGHT.set(false);
                }
            });
        } catch (RejectedExecutionException ignored) {
            SYNC_IN_FLIGHT.set(false);
        }
    }

    private static void recordFailure(long baseMs, String reason) {
        int failures = Math.min(8, consecutiveFailures + 1);
        consecutiveFailures = failures;
        long backoff = Math.min(10L * 60_000L, 15_000L << Math.min(5, failures - 1));
        nextAllowedQueueMs = Math.max(nextAllowedQueueMs, baseMs + backoff);
        if (HomeCraftGuildConfig.debug() || failures <= 2 || failures % 4 == 0) {
            HomeCraftGuildMod.LOGGER.warn("Guild territory sync failed; retry in {}s: {}", Math.max(1L, backoff / 1000L), reason);
        }
    }

    public static void shutdown() {
        SYNC_IN_FLIGHT.set(false);
        if (!SYNC_EXECUTOR.isShutdown()) {
            SYNC_EXECUTOR.shutdownNow();
        }
    }
}
