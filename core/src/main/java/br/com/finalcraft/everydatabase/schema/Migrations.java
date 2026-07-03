package br.com.finalcraft.everydatabase.schema;

import java.util.List;

/** Static helpers shared by the per-backend migration runners. */
public final class Migrations {

    private Migrations() {
    }

    /**
     * Rejects duplicate migration versions up front, before anything is applied.
     *
     * <p>{@link Migration#version()} must be unique across all registered migrations. Without
     * this check the runners would diverge on a duplicate: some backends silently skip the
     * second migration, others apply both and then fail halfway through when recording the
     * ledger entry - after the first one already ran.
     *
     * @param sorted the registered migrations, already sorted by version
     * @throws IllegalStateException when two migrations share the same version
     */
    public static void requireUniqueVersions(List<Migration> sorted) {
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).version().equals(sorted.get(i - 1).version())) {
                throw new IllegalStateException(
                    "Duplicate migration version '" + sorted.get(i).version() + "': '"
                        + sorted.get(i - 1).description() + "' and '"
                        + sorted.get(i).description() + "'");
            }
        }
    }
}
