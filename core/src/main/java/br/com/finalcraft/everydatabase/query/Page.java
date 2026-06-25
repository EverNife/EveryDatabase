package br.com.finalcraft.everydatabase.query;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * A {@link Slice} that also carries the total match count, so a UI can show "page X of N,
 * T results". Produced by {@code queryPage}, which runs an extra {@code count} - prefer
 * {@link Slice} (no count) when you only need next/previous navigation.
 *
 * <p>The total and the page content are read in separate steps and are <em>not</em> atomic: a
 * concurrent write between them can make {@link #totalElements()} disagree with {@link #content()}
 * by one. This matches Spring Data's {@code Page} and is harmless for typical paging.
 */
public final class Page<V> extends Slice<V> {

    private final long totalElements;

    private Page(List<V> content, QueryOptions options, long totalElements) {
        super(content, options, computeHasNext(content, options, totalElements), null);
        this.totalElements = totalElements;
    }

    public static <V> Page<V> of(List<V> content, QueryOptions options, long totalElements) {
        return new Page<>(content, options, totalElements);
    }

    private static boolean computeHasNext(List<?> content, QueryOptions options, long totalElements) {
        long consumed = (long) options.offset() + content.size();
        return consumed < totalElements;
    }

    /** Total entities matching the query, ignoring pagination. */
    public long totalElements() {
        return totalElements;
    }

    /** Number of pages at this {@link #size()}; {@code 0} when there are no results. */
    public int totalPages() {
        int s = size();
        return s == 0 ? (totalElements == 0 ? 0 : 1)
                      : (int) Math.ceil((double) totalElements / s);
    }

    @Override
    public <R> Page<R> map(Function<? super V, ? extends R> mapper) {
        List<R> mapped = new ArrayList<>(content.size());
        for (V v : content) mapped.add(mapper.apply(v));
        return new Page<>(mapped, options, totalElements);
    }
}
