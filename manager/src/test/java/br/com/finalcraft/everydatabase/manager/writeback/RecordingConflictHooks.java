package br.com.finalcraft.everydatabase.manager.writeback;

import br.com.finalcraft.everydatabase.manager.cache.IDirtyable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A {@link ConflictHooks} decorator recording which seams a resolution drove, so a test can pin the
 * BRANCH the flusher took and not merely the state it left behind - "adopted the winner" and "kept
 * the local values because it was re-dirtied" can otherwise be told apart only by luck.
 */
class RecordingConflictHooks<K, V extends IDirtyable> implements ConflictHooks<K, V> {

    private final ConflictHooks<K, V> delegate;
    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());

    RecordingConflictHooks(ConflictHooks<K, V> delegate) {
        this.delegate = delegate;
    }

    /** The seams driven so far, in order. */
    List<String> calls() {
        return new ArrayList<>(calls);
    }

    @Override
    public K storageKey(V live) {
        return delegate.storageKey(live);
    }

    @Override
    public ReentrantLock lock(V live) {
        return delegate.lock(live);
    }

    @Override
    public void adoptStoredState(V live, V stored) {
        calls.add("adoptStoredState");
        delegate.adoptStoredState(live, stored);
    }

    @Override
    public void adoptStoredLockVersion(V live, V stored) {
        calls.add("adoptStoredLockVersion");
        delegate.adoptStoredLockVersion(live, stored);
    }

    @Override
    public void resetLockForRecreate(V live) {
        calls.add("resetLockForRecreate");
        delegate.resetLockForRecreate(live);
    }

    @Override
    public void afterAdopt(V live) {
        calls.add("afterAdopt");
        delegate.afterAdopt(live);
    }

    @Override
    public boolean mergesOnConflict() {
        return delegate.mergesOnConflict();
    }
}
