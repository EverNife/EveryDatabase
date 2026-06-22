package br.com.finalcraft.everydatabase.modules.mongo;

import br.com.finalcraft.everydatabase.changefeed.ChangeEvent;
import br.com.finalcraft.everydatabase.changefeed.ChangeFeedSupport;
import br.com.finalcraft.everydatabase.changefeed.ChangeOp;
import br.com.finalcraft.everydatabase.log.StorageLog;
import br.com.finalcraft.everydatabase.log.StorageLogLevel;
import br.com.finalcraft.everydatabase.log.StorageOp;
import com.mongodb.client.ChangeStreamIterable;
import com.mongodb.client.MongoChangeStreamCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.FullDocument;
import com.mongodb.client.model.changestream.OperationType;
import org.bson.BsonDocument;
import org.bson.Document;

/**
 * Translates a MongoDB <b>change stream</b> over the whole database into {@link ChangeEvent}s on a
 * {@link ChangeFeedSupport}. This is the source side of {@code MongoStorage}'s
 * {@link br.com.finalcraft.everydatabase.changefeed.ChangeFeedStorage} capability.
 *
 * <p>A single daemon thread runs one {@code database.watch()} cursor with
 * {@link FullDocument#UPDATE_LOOKUP}, so insert/update/replace events carry the current document
 * (and thus the entity's key - the {@code _id} - and {@code lock_version}). On a recoverable error it
 * resumes from the last token, so an instance that briefly disconnects misses nothing within the
 * oplog window.
 *
 * <h3>Delete events</h3>
 * The entity key is stored as the document {@code _id}, so a delete event's {@code documentKey._id}
 * carries the key directly - {@code DELETE} events propagate with no pre-image configuration needed.
 *
 * <h3>Origin</h3>
 * Oplog events carry no application identity, so emitted events have a {@code null}
 * {@link ChangeEvent#originId()}: a consumer's own-origin skip never fires and an instance also
 * reloads its own writes (harmless - the value is already cached write-through).
 */
final class MongoChangeFeed {

    private final MongoDatabase database;
    private final ChangeFeedSupport feed;
    private final StorageLog log;

    private volatile boolean running = false;
    private volatile Thread thread;
    private volatile MongoChangeStreamCursor<ChangeStreamDocument<Document>> cursor;

    MongoChangeFeed(MongoDatabase database, ChangeFeedSupport feed, StorageLog log) {
        this.database = database;
        this.feed     = feed;
        this.log      = log;
    }

    /** Starts the listener thread. Idempotent. */
    synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        Thread t = new Thread(this::run, "everydatabase-mongo-changefeed");
        t.setDaemon(true);
        this.thread = t;
        t.start();
    }

    /** Stops the listener thread and closes the cursor. Idempotent. */
    synchronized void stop() {
        running = false;
        MongoChangeStreamCursor<?> c = cursor;
        if (c != null) {
            try {
                c.close();
            } catch (RuntimeException ignored) {
                // closing a cursor whose client is already shutting down may throw - ignore
            }
        }
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    // ------------------------------------------------------------------

    private void run() {
        BsonDocument resumeToken = null;
        while (running) {
            try {
                ChangeStreamIterable<Document> stream = database.watch()
                        .fullDocument(FullDocument.UPDATE_LOOKUP);
                if (resumeToken != null) {
                    stream = stream.resumeAfter(resumeToken);
                }
                cursor = stream.cursor();
                while (running) {
                    ChangeStreamDocument<Document> change = cursor.tryNext();
                    if (change == null) {
                        continue;   // server await-time elapsed with no event; re-check running
                    }
                    resumeToken = change.getResumeToken();
                    dispatch(change);
                }
            } catch (RuntimeException e) {
                if (!running) {
                    return;   // expected: the storage is closing
                }
                log.emit(StorageOp.HEALTH, StorageLogLevel.WARN,
                        b -> b.detail("change stream interrupted, resuming").error(e));
                sleepBeforeResume();
            } finally {
                closeCursorQuietly();
            }
        }
    }

    private void dispatch(ChangeStreamDocument<Document> change) {
        OperationType type = change.getOperationType();
        String collection = change.getNamespace() != null
                ? change.getNamespace().getCollectionName() : null;
        if (collection == null || MongoStorage.MIGRATIONS_COLLECTION.equals(collection)) {
            return;
        }
        if (type == OperationType.INSERT || type == OperationType.UPDATE || type == OperationType.REPLACE) {
            Document full = change.getFullDocument();
            if (full == null) {
                return;   // update-lookup found nothing (already deleted) - nothing to invalidate
            }
            String key = full.getString(MongoRepository.COL_KEY);   // COL_KEY is _id
            if (key == null) {
                return;
            }
            feed.emit(new ChangeEvent(collection, key, ChangeOp.SAVE, versionOf(full), null));
        } else if (type == OperationType.DELETE) {
            // The entity key IS the document _id, so the delete event's documentKey carries it
            // directly - no pre-image needed.
            BsonDocument documentKey = change.getDocumentKey();
            if (documentKey != null && documentKey.containsKey("_id") && documentKey.get("_id").isString()) {
                String key = documentKey.getString("_id").getValue();
                feed.emit(new ChangeEvent(collection, key, ChangeOp.DELETE, ChangeEvent.UNKNOWN_VERSION, null));
            }
        }
        // drop / rename / invalidate are not modeled as per-key events.
    }

    private long versionOf(Document full) {
        Object v = full.get(MongoRepository.COL_VERSION);
        return (v instanceof Number) ? ((Number) v).longValue() : ChangeEvent.UNKNOWN_VERSION;
    }

    private void closeCursorQuietly() {
        MongoChangeStreamCursor<?> c = cursor;
        cursor = null;
        if (c != null) {
            try {
                c.close();
            } catch (RuntimeException ignored) {
                // ignore
            }
        }
    }

    private void sleepBeforeResume() {
        try {
            Thread.sleep(1000L);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
