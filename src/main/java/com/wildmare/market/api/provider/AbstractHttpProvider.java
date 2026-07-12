package com.wildmare.market.api.provider;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.wildmare.market.api.MarketDataProvider;
import com.wildmare.market.api.ProviderException;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

abstract class AbstractHttpProvider implements MarketDataProvider {
    protected final JavaPlugin plugin;
    protected final String providerId;
    protected final String baseUrl;
    protected final String apiKey;
    protected final boolean enabled;
    private final int retries;
    private final long retryDelayMillis;
    private final Duration timeout;
    private final Semaphore concurrency;
    private final ExecutorService executor;
    private final HttpClient client;
    private final String userAgent;

    protected AbstractHttpProvider(JavaPlugin plugin, String providerId,
                                   ConfigurationSection providerSection,
                                   ConfigurationSection httpSection) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.providerId = providerId;
        this.baseUrl = stripTrailingSlash(providerSection.getString("base-url", ""));
        this.apiKey = providerSection.getString("api-key", "");
        this.enabled = providerSection.getBoolean("enabled", false);
        this.retries = Math.max(0, httpSection.getInt("retries", 2));
        this.retryDelayMillis = Math.max(100L, httpSection.getLong("retry-delay-milliseconds", 750L));
        this.timeout = Duration.ofSeconds(Math.max(2, httpSection.getLong("timeout-seconds", 10L)));
        this.concurrency = new Semaphore(Math.max(1, httpSection.getInt("max-concurrent-requests", 4)));
        this.userAgent = httpSection.getString("user-agent", "WildMareMarket/1.0");
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .executor(executor)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String id() {
        return providerId;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    protected CompletableFuture<JsonElement> getJson(URI uri, Map<String, String> headers) {
        if (!enabled) {
            return CompletableFuture.failedFuture(new ProviderException(providerId + " is disabled"));
        }
        return attempt(uri, headers, 0);
    }

    private CompletableFuture<JsonElement> attempt(URI uri, Map<String, String> headers, int attempt) {
        return CompletableFuture.runAsync(() -> {
            try {
                concurrency.acquire();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ProviderException("Interrupted while waiting for API request capacity", exception);
            }
        }, executor).thenCompose(ignored -> {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .GET()
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .header("User-Agent", userAgent);
            headers.forEach(builder::header);
            return client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                    .whenComplete((response, throwable) -> concurrency.release());
        }).thenCompose(response -> {
            int code = response.statusCode();
            if (code >= 200 && code < 300) {
                try {
                    return CompletableFuture.completedFuture(JsonParser.parseString(response.body()));
                } catch (Exception exception) {
                    return CompletableFuture.failedFuture(
                            new ProviderException("Invalid JSON returned by " + providerId, code, exception));
                }
            }
            if ((code == 429 || code >= 500) && attempt < retries) {
                long delay = retryDelayMillis * (1L << attempt);
                String retryAfter = response.headers().firstValue("Retry-After").orElse("");
                try {
                    if (!retryAfter.isBlank()) delay = Math.max(delay, Long.parseLong(retryAfter) * 1000L);
                } catch (NumberFormatException ignored) {
                }
                return delayed(() -> attempt(uri, headers, attempt + 1), delay);
            }
            return CompletableFuture.failedFuture(
                    new ProviderException(providerId + " returned HTTP " + code, code));
        }).exceptionallyCompose(throwable -> {
            Throwable cause = unwrap(throwable);
            if (attempt < retries && !(cause instanceof ProviderException pe && pe.statusCode() >= 400 && pe.statusCode() < 500 && pe.statusCode() != 429)) {
                long delay = retryDelayMillis * (1L << attempt);
                return delayed(() -> attempt(uri, headers, attempt + 1), delay);
            }
            return CompletableFuture.failedFuture(sanitize(cause));
        });
    }

    private ProviderException sanitize(Throwable throwable) {
        int statusCode = throwable instanceof ProviderException providerException
                ? providerException.statusCode() : -1;
        String message = throwable.getMessage() == null
                ? throwable.getClass().getSimpleName() : throwable.getMessage();
        if (apiKey != null && !apiKey.isBlank()) {
            message = message.replace(apiKey, "***");
        }
        message = message.replaceAll(
                "(?i)(token|api[_-]?key)=([^&\\s]+)", "$1=***");
        return new ProviderException(message, statusCode, throwable);
    }

    private <T> CompletableFuture<T> delayed(Supplier<CompletableFuture<T>> supplier, long delayMillis) {
        return CompletableFuture.supplyAsync(
                () -> null,
                CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS, executor)
        ).thenCompose(ignored -> supplier.get());
    }

    protected static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null &&
                (current instanceof java.util.concurrent.CompletionException ||
                 current instanceof java.util.concurrent.ExecutionException)) {
            current = current.getCause();
        }
        return current;
    }

    private static String stripTrailingSlash(String input) {
        String value = input == null ? "" : input.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
