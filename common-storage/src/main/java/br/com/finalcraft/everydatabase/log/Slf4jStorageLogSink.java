package br.com.finalcraft.everydatabase.log;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link StorageLogSink} that routes events to SLF4J loggers.
 *
 * <p><b>This is the only class in {@code common-storage} that directly imports
 * {@code org.slf4j.*}.</b> It is referenced solely from {@link StorageLogSinks.Slf4jHolder},
 * whose lazy class-loading isolates the reference: the holder probes for
 * {@code org.slf4j.LoggerFactory} reflectively and only instantiates this sink when SLF4J
 * is confirmed present on the runtime classpath - so the JVM never tries to link this
 * class (and throw {@code NoClassDefFoundError}) when SLF4J is absent.
 *
 * <p>Each {@link StorageLogTopic} maps to its own named SLF4J logger:
 * {@code evernifecore.storage.<topic>} (all lower-case).
 * This allows fine-grained SLF4J / Logback configuration per topic without any
 * extra storage-side configuration.
 *
 * <p>Level mapping:
 * <ul>
 *   <li>{@link StorageLogLevel#ERROR} → {@code logger.error()}</li>
 *   <li>{@link StorageLogLevel#WARN}  → {@code logger.warn()}</li>
 *   <li>{@link StorageLogLevel#INFO}  → {@code logger.info()}</li>
 *   <li>{@link StorageLogLevel#DEBUG} → {@code logger.debug()}</li>
 *   <li>{@link StorageLogLevel#TRACE} → {@code logger.trace()}</li>
 *   <li>{@link StorageLogLevel#OFF}   → treated as trace (should not reach the sink)</li>
 * </ul>
 *
 * <p>The {@link StorageLogEvent#error() error} throwable (if present) is passed as the
 * final argument to the SLF4J call, so appenders that support it (Logback, Log4j2) will
 * print the full stack trace.
 */
final class Slf4jStorageLogSink implements StorageLogSink {

    // One logger per topic - resolved at accept() time via the topic name.
    // SLF4J caches LoggerFactory.getLogger() internally, so this is not expensive.

    @Override
    public void accept(StorageLogEvent event) {
        Logger logger = LoggerFactory.getLogger("evernifecore.storage." + event.topic().name().toLowerCase());
        String msg = event.format();
        Throwable err = event.error();

        switch (event.level()) {
            case ERROR:
                if (err != null) logger.error(msg, err); else logger.error(msg);
                break;
            case WARN:
                if (err != null) logger.warn(msg, err);  else logger.warn(msg);
                break;
            case INFO:
                if (err != null) logger.info(msg, err);  else logger.info(msg);
                break;
            case DEBUG:
                if (err != null) logger.debug(msg, err); else logger.debug(msg);
                break;
            default: // TRACE and OFF
                if (err != null) logger.trace(msg, err); else logger.trace(msg);
                break;
        }
    }
}
