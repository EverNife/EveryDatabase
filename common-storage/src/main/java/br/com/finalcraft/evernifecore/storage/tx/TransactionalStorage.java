package br.com.finalcraft.evernifecore.storage.tx;

import br.com.finalcraft.evernifecore.storage.Storage;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Optional capability: executes a unit of work as an atomic transaction.
 *
 * <p>Backends that do not support transactions simply do not implement this interface.
 * The compiler prevents callers from using transactions on non-transactional backends
 * without an explicit {@code instanceof} check.</p>
 *
 * <p>Usage:
 * <pre>{@code
 * if (storage instanceof TransactionalStorage tx) {
 *     tx.inTransaction(scope -> {
 *         Repository<UUID, PlayerData> pdRepo  = scope.repository(PD_DESCRIPTOR);
 *         Repository<UUID, Economy>    ecoRepo = scope.repository(ECO_DESCRIPTOR);
 *
 *         return pdRepo.save(player)
 *                 .thenCompose(__ -> ecoRepo.save(eco))
 *                 .thenApply(__ -> "committed");
 *     }).join();
 * }
 * }</pre>
 */
public interface TransactionalStorage extends Storage {

    /**
     * Executes {@code work} inside a transaction.
     *
     * <ul>
     *   <li>If the future completes <em>successfully</em> and {@link TransactionScope#rollback()} was
     *       <strong>not</strong> called, the transaction is <strong>committed</strong>.</li>
     *   <li>If the future completes <em>exceptionally</em>, or {@link TransactionScope#rollback()} was
     *       called, the transaction is <strong>rolled back</strong>.</li>
     * </ul>
     *
     * @param <R>  the result type produced by {@code work}
     * @param work the unit of work; receives a {@link TransactionScope} that provides
     *             repositories bound to the current transaction
     * @return a future that completes with the result of {@code work}
     */
    <R> CompletableFuture<R> inTransaction(Function<TransactionScope, CompletableFuture<R>> work);
}
