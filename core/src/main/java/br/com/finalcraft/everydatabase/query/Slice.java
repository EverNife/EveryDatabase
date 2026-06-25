package br.com.finalcraft.everydatabase.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * One page of query results plus lightweight navigation, <em>without</em> a total count.
 *
 * <p>A {@code Slice} is the cheap pagination result: it knows whether there is a next page
 * (the repository fetches one extra row to decide) but not how many rows match overall - so it
 * never pays for a {@code COUNT}. Use {@link Page} when you need {@code totalElements}/{@code totalPages}.
 *
 * <p>Two flavors share this type; exactly one continuation is present:
 * <ul>
 *   <li><b>Offset</b> (from {@code querySlice}/{@code queryPage}) - {@link #nextPageRequest()} yields the
 *       {@link QueryOptions} for the next page; {@link #number()}/{@link #hasPrevious()} are page coordinates.</li>
 *   <li><b>Cursor</b> (from {@code queryAfter}) - {@link #nextCursor()} yields the keyset cursor for the
 *       next page; {@link #number()} is {@code 0} (cursor paging has no page index).</li>
 * </ul>
 */
public class Slice<V> {

    protected final List<V> content;
    protected final QueryOptions options;
    private final boolean hasNext;
    private final Cursor nextCursor;   // null for offset slices

    protected Slice(List<V> content, QueryOptions options, boolean hasNext, Cursor nextCursor) {
        this.content = Collections.unmodifiableList(new ArrayList<>(content));
        this.options = options == null ? QueryOptions.none() : options;
        this.hasNext = hasNext;
        this.nextCursor = nextCursor;
    }

    /** An offset-paginated slice (continuation via {@link #nextPageRequest()}). */
    public static <V> Slice<V> of(List<V> content, QueryOptions options, boolean hasNext) {
        return new Slice<>(content, options, hasNext, null);
    }

    /** A keyset-paginated slice (continuation via {@link #nextCursor()}). */
    public static <V> Slice<V> ofCursor(List<V> content, QueryOptions order, boolean hasNext, Cursor nextCursor) {
        return new Slice<>(content, order, hasNext, hasNext ? nextCursor : null);
    }

    public List<V> content()       { return content; }
    /** The request that produced this slice (order + window). */
    public QueryOptions options()  { return options; }
    /** Results per page requested ({@code limit}; falls back to the element count when unbounded). */
    public int size()              { return options.hasLimit() ? options.limit() : content.size(); }
    /** 0-based page index for offset slices; {@code 0} for cursor slices. */
    public int number()            { return options.hasLimit() ? options.offset() / options.limit() : 0; }
    public int numberOfElements()  { return content.size(); }
    public boolean hasNext()       { return hasNext; }
    public boolean hasPrevious()   { return options.offset() > 0; }
    public boolean isFirst()       { return !hasPrevious(); }
    public boolean isLast()        { return !hasNext(); }
    public boolean isEmpty()       { return content.isEmpty(); }

    /** Offset continuation: the {@link QueryOptions} for the next page, or empty if this is a cursor slice or the last page. */
    public Optional<QueryOptions> nextPageRequest() {
        if (!hasNext || nextCursor != null) {
            return Optional.empty();
        }
        return Optional.of(options.withOffset(options.offset() + size()));
    }

    /** Keyset continuation: the cursor for the next page, present only for cursor slices with a next page. */
    public Optional<Cursor> nextCursor() {
        return Optional.ofNullable(nextCursor);
    }

    /** Transforms the content, preserving the navigation metadata. */
    public <R> Slice<R> map(Function<? super V, ? extends R> mapper) {
        List<R> mapped = new ArrayList<>(content.size());
        for (V v : content) mapped.add(mapper.apply(v));
        return new Slice<>(mapped, options, hasNext, nextCursor);
    }
}
