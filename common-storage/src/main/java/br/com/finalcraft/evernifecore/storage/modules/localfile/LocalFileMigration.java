package br.com.finalcraft.evernifecore.storage.modules.localfile;

import br.com.finalcraft.evernifecore.storage.schema.Migration;
import br.com.finalcraft.evernifecore.storage.schema.MigrationContext;

import java.nio.file.Path;

/**
 * Convenience base class for LocalFile migrations.
 *
 * <p>Subclasses implement {@link #executeOnStorage(LocalFileStorage)} to access:
 * <ul>
 *   <li>The {@link LocalFileStorage} itself - use {@code storage.repository(descriptor)}
 *       for high-level CRUD operations (recommended).</li>
 *   <li>The base directory {@link Path} - use {@code storage.baseDirectory()} for
 *       low-level file operations (renaming files, changing directory structure, etc.).</li>
 * </ul>
 *
 * <pre>{@code
 * public final class V001_FixCorruptedFiles extends LocalFileMigration {
 *
 *     public static final V001_FixCorruptedFiles INSTANCE = new V001_FixCorruptedFiles();
 *     private V001_FixCorruptedFiles() {}
 *
 *     public String version()     { return "001"; }
 *     public String description() { return "Delete files with corrupted JSON"; }
 *
 *     protected void executeOnStorage(LocalFileStorage storage) throws Exception {
 *         Path dir = storage.baseDirectory().resolve("playerdata");
 *         Files.walk(dir, 1)
 *              .filter(p -> p.toString().endsWith(".json") && isCorrupted(p))
 *              .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
 *     }
 * }
 * }</pre>
 */
public abstract class LocalFileMigration implements Migration {

    @Override
    public final void execute(MigrationContext context) throws Exception {
        LocalFileStorage storage = context.getNativeClient(LocalFileStorage.class);
        executeOnStorage(storage);
    }

    /**
     * Performs the migration using the given {@link LocalFileStorage}.
     *
     * <p>Use {@code storage.repository(descriptor)} for standard CRUD, or
     * {@code storage.baseDirectory()} for raw file manipulation.
     *
     * @param storage the storage to migrate
     * @throws Exception if the migration fails
     */
    protected abstract void executeOnStorage(LocalFileStorage storage) throws Exception;
}
