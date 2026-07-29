package br.com.finalcraft.everydatabase.query;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared page assembly for the key-ordered scans.
 *
 * <p>Every backend pages the same way - ask for one row more than requested, and let that extra row
 * answer "is there a next page?" without a second query - so the trimming and the continuation
 * cursor are the same code everywhere rather than the same code seven times.
 */
public final class Slices {

    private Slices() {}

    /**
     * Turns an over-fetched, key-ordered list into a page of at most {@code limit} keys, plus the
     * cursor that resumes after the last one.
     *
     * @param probed keys in ascending storage-key order, fetched with {@code limit + 1} as the bound
     * @param cursor the cursor this page was requested with, whose ordering the next one inherits
     */
    public static Slice<String> keyPage(List<String> probed, Cursor cursor, int limit) {
        boolean hasNext = probed.size() > limit;
        List<String> content = hasNext ? new ArrayList<>(probed.subList(0, limit)) : probed;
        Cursor next = null;
        if (hasNext && !content.isEmpty()) {
            String lastKey = content.get(content.size() - 1);
            next = Cursor.after(cursor.orderBy(), cursor.direction(), lastKey, lastKey);
        }
        return Slice.ofCursor(content, QueryOptions.none(), hasNext, next);
    }

    /**
     * The same, for a list that was <em>not</em> over-fetched: it holds every remaining key, so the
     * page is cut here and the rest becomes the next page.
     */
    public static Slice<String> keyPageOfAll(List<String> all, Cursor cursor, int limit) {
        String afterKey = cursor.isStart() ? null : cursor.lastKey();
        List<String> probed = new ArrayList<>();
        for (String key : all) {
            if (afterKey != null && key.compareTo(afterKey) <= 0) continue;
            if (probed.size() > limit) break;                     // limit + 1 is enough to know
            probed.add(key);
        }
        return keyPage(probed, cursor, limit);
    }
}
