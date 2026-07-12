package com.wildmare.market.economy;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Validated Vault economy adapter used by virtual trade settlement. */
public final class EconomyService {
    private final JavaPlugin plugin;
    private final Economy economy;

    public EconomyService(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        RegisteredServiceProvider<Economy> registration =
                plugin.getServer().getServicesManager().getRegistration(Economy.class);
        if (registration == null || registration.getProvider() == null) {
            throw new IllegalStateException("No Vault-compatible economy provider was found");
        }
        this.economy = registration.getProvider();
    }

    public String providerName() {
        return economy.getName();
    }

    public CompletableFuture<Double> balance(OfflinePlayer player) {
        return sync(() -> economy.getBalance(player));
    }

    public CompletableFuture<Boolean> withdraw(OfflinePlayer player, double amount) {
        if (amount < 0 || !Double.isFinite(amount)) return CompletableFuture.completedFuture(false);
        return sync(() -> {
            EconomyResponse response = economy.withdrawPlayer(player, amount);
            return response.transactionSuccess();
        });
    }

    public CompletableFuture<Boolean> deposit(OfflinePlayer player, double amount) {
        if (amount < 0 || !Double.isFinite(amount)) return CompletableFuture.completedFuture(false);
        return sync(() -> {
            EconomyResponse response = economy.depositPlayer(player, amount);
            return response.transactionSuccess();
        });
    }

    public String format(double amount) {
        return economy.format(amount);
    }

    private <T> CompletableFuture<T> sync(Supplier<T> supplier) {
        if (Bukkit.isPrimaryThread()) {
            try {
                return CompletableFuture.completedFuture(supplier.get());
            } catch (Throwable throwable) {
                return CompletableFuture.failedFuture(throwable);
            }
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future;
    }
}
