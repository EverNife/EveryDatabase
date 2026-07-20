package br.com.finalcraft.everydatabase;

/**
 * Whether a backend publishes cache-invalidation signals onto an explicit pub/sub transport.
 *
 * <p>This governs only the <b>publish</b> side of the transport-based cache-sync path (a signal a
 * local write pushes out for other instances to apply). It does not touch the native change-feed
 * path, and it never suppresses <b>receiving</b>: a backend that does not publish still subscribes
 * to and applies signals other instances publish for the same store and collection.
 *
 * <p>The default, {@link #RECOMMENDED}, leaves every existing configuration behaving exactly as it
 * did: a shareable store keeps publishing, and a machine-local store (a local file directory, an
 * in-memory store, or a {@code localhost} database, none of which another machine can reach) stops
 * publishing signals no peer could ever match - saved traffic, no correctness change.
 *
 * <ul>
 *   <li>{@link #RECOMMENDED} - publish only when the store is shareable (a routable coordinate, or
 *       any store given an explicit shared identity). A machine-local store does not publish.</li>
 *   <li>{@link #ALWAYS} - publish unconditionally. Binding a machine-local store that has no shared
 *       identity fails fast instead, because it would publish onto a channel no other machine
 *       subscribes to.</li>
 *   <li>{@link #NEVER} - never publish, not even from a shareable store.</li>
 * </ul>
 */
public enum SyncParticipation {

    /**
     * The default: publish from a shareable store, stay silent on a machine-local one. No existing
     * configuration changes behaviour without being edited to select another value.
     */
    RECOMMENDED,

    /**
     * Publish from every store unconditionally. A machine-local store without an explicit shared
     * identity is rejected at bind time rather than allowed to publish onto a channel no peer hears.
     */
    ALWAYS,

    /**
     * Never publish, regardless of whether the store is shareable.
     */
    NEVER
}
