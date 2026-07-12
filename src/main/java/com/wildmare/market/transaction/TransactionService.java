package com.wildmare.market.transaction;

import com.wildmare.market.database.DatabaseService;
import com.wildmare.market.model.TransactionRecord;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Read-oriented transaction history service. */
public final class TransactionService {
    private final DatabaseService databaseService;

    public TransactionService(DatabaseService databaseService) {
        this.databaseService = databaseService;
    }

    public CompletableFuture<List<TransactionRecord>> history(UUID playerId, int limit) {
        return databaseService.getTransactions(playerId, limit);
    }

    public CompletableFuture<Void> log(TransactionRecord record) {
        return databaseService.recordNonCompletedTransaction(record);
    }

    public CompletableFuture<Void> logFailure(TransactionRecord record) {
        return log(record);
    }
}
