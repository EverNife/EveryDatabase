package br.com.finalcraft.everydatabase.query;

import br.com.finalcraft.everydatabase.EntityDescriptor;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Declarative hint for the storage backend to create a secondary index on a field
 * inside the serialised entity.
 *
 * <p>Index hints are attached to an {@code EntityDescriptor} via
 * {@code .index(IndexHint.string("name"))}. Each backend interprets the hint
 * differently:
 * <ul>
 *   <li><b>SQL</b> (MySQL/MariaDB/PostgreSQL/H2) - adds a stored column
 *       {@code _idx_<field>} populated at save time and a B-tree index over it.</li>
 *   <li><b>MongoDB</b> - stores the value in {@code _idx_<field>} alongside the
 *       JSON blob and calls {@code createIndex}.</li>
 *   <li><b>InMemory</b> - keeps an in-memory {@code Map<value, Set<key>>}.</li>
 *   <li><b>LocalFile</b> - no index; falls back to a full scan + filter
 *       (correct but slow).</li>
 * </ul>
 *
 * <h3>Building hints</h3>
 * <pre>{@code
 * IndexHint.string ("type")           // VARCHAR field, ascending
 * IndexHint.integer("level")          // INT field
 * IndexHint.bigInt ("timestamp")      // BIGINT (Java long)
 * IndexHint.decimal("balance")        // DOUBLE
 * IndexHint.bool   ("active")         // BOOLEAN
 *
 * // Nested paths (dot-separated):
 * IndexHint.string ("location.world")
 * IndexHint.integer("location.x")
 *
 * // Modifiers:
 * IndexHint.integer("score").asDescending()
 * }</pre>
 *
 * <h3>Field-path syntax</h3>
 * Field paths use dot notation. Each segment must start with a letter or underscore
 * and contain only letters, digits, or underscores. Examples:
 * {@code "name"}, {@code "user_id"}, {@code "location.world"}, {@code "stats.kd_ratio"}.
 */
public final class IndexHint {

    /**
     * Java type of the indexed field. Determines the SQL column type used for the
     * backing index column and how the value is parsed/compared.
     */
    public enum FieldType {
        /** Java {@code String} → SQL {@code TEXT}. */
        STRING,
        /** Java {@code int}/{@code Integer} → SQL {@code INT}. */
        INT,
        /** Java {@code long}/{@code Long} → SQL {@code BIGINT}. */
        LONG,
        /** Java {@code double}/{@code Float} → SQL {@code DOUBLE}. */
        DOUBLE,
        /** Java {@code boolean}/{@code Boolean} → SQL {@code BOOLEAN}. */
        BOOLEAN,
        /**
         * Java {@link java.time.Instant} or {@link java.time.LocalDateTime} → SQL
         * {@code DATETIME(3)}/{@code TIMESTAMPTZ}, MongoDB BSON {@code Date}.
         * Stored and compared as epoch-milliseconds ({@code long}) in every backend.
         * The index column in SQL uses a native date type so values appear human-readable
         * in DB tools; InMemory and LocalFile use {@code Long} internally.
         */
        TIMESTAMP
    }

    /** Sort order of the index. */
    public enum Order {
        ASCENDING,
        DESCENDING
    }

    /**
     * Regex enforcing a safe, cross-backend field-path syntax: dot-separated
     * segments, each segment is a valid identifier (letter/underscore start,
     * letters/digits/underscores body).
     */
    static final Pattern VALID_FIELD_PATH =
        Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)*$");

    private final String    fieldPath;
    private final FieldType fieldType;
    private final Order     order;

    private IndexHint(String fieldPath, FieldType fieldType, Order order) {
        if (fieldPath == null || !VALID_FIELD_PATH.matcher(fieldPath).matches()) {
            throw new IllegalArgumentException(
                "IndexHint field path '" + fieldPath + "' is invalid. " +
                "Must be dot-separated identifiers (e.g. 'name', 'location.world', 'stats.kd_ratio'). " +
                "Each segment must start with a letter or underscore and contain only letters, digits, or underscores."
            );
        }
        this.fieldPath = fieldPath;
        this.fieldType = Objects.requireNonNull(fieldType, "fieldType");
        this.order     = Objects.requireNonNull(order,     "order");
    }

    // ------------------------------------------------------------------
    //  Factory methods - one per primitive type
    // ------------------------------------------------------------------

    /** {@link FieldType#STRING} index on {@code fieldPath}, ascending. */
    public static IndexHint string(String fieldPath) {
        return new IndexHint(fieldPath, FieldType.STRING, Order.ASCENDING);
    }

    /** {@link FieldType#INT} index on {@code fieldPath}, ascending. */
    public static IndexHint integer(String fieldPath) {
        return new IndexHint(fieldPath, FieldType.INT, Order.ASCENDING);
    }

    /** {@link FieldType#LONG} index on {@code fieldPath}, ascending. */
    public static IndexHint bigInt(String fieldPath) {
        return new IndexHint(fieldPath, FieldType.LONG, Order.ASCENDING);
    }

    /** {@link FieldType#DOUBLE} index on {@code fieldPath}, ascending. */
    public static IndexHint decimal(String fieldPath) {
        return new IndexHint(fieldPath, FieldType.DOUBLE, Order.ASCENDING);
    }

    /** {@link FieldType#BOOLEAN} index on {@code fieldPath}, ascending. */
    public static IndexHint bool(String fieldPath) {
        return new IndexHint(fieldPath, FieldType.BOOLEAN, Order.ASCENDING);
    }

    /**
     * {@link FieldType#TIMESTAMP} index on {@code fieldPath}, ascending.
     *
     * <p>Accepts {@link java.time.Instant} and {@link java.time.LocalDateTime} in queries.
     * The entity field itself can be either a Java {@code long} (epoch millis), an
     * {@link java.time.Instant}, or a {@link java.time.LocalDateTime}.
     *
     * <p>Example:
     * <pre>{@code
     * // Declaration
     * .index(IndexHint.timestamp("createdAt"))
     *
     * // Range query
     * repo.query(Query.range("createdAt", Instant.now().minus(7, ChronoUnit.DAYS), Instant.now()))
     *
     * // Before / after
     * repo.query(Query.range("createdAt", someInstant, null))   // after
     * repo.query(Query.range("createdAt", null,        someInstant))  // before
     * }</pre>
     */
    public static IndexHint timestamp(String fieldPath) {
        return new IndexHint(fieldPath, FieldType.TIMESTAMP, Order.ASCENDING);
    }

    // ------------------------------------------------------------------
    //  Backward-compatibility shortcuts (default to STRING)
    // ------------------------------------------------------------------

    /** Shortcut for {@link #string(String)}. Kept for backward compatibility. */
    public static IndexHint by(String fieldPath) {
        return string(fieldPath);
    }

    /** STRING descending index on {@code fieldPath}. */
    public static IndexHint descending(String fieldPath) {
        return string(fieldPath).asDescending();
    }

    // ------------------------------------------------------------------
    //  Modifier methods (return new instance - IndexHint is immutable)
    // ------------------------------------------------------------------

    /** Returns a copy with descending order. */
    public IndexHint asDescending() {
        return new IndexHint(fieldPath, fieldType, Order.DESCENDING);
    }

    /** Returns a copy with ascending order. */
    public IndexHint asAscending() {
        return new IndexHint(fieldPath, fieldType, Order.ASCENDING);
    }

    // ------------------------------------------------------------------
    //  Accessors
    // ------------------------------------------------------------------

    public String    fieldPath() { return fieldPath; }
    public FieldType fieldType() { return fieldType; }
    public Order     order()     { return order; }

    /**
     * Returns a safe column / field name derived from the field path.
     * Dots are replaced with underscores and a {@code _idx_} prefix is added.
     * <p>Example: {@code "location.world"} → {@code "_idx_location_world"}.
     */
    public String indexColumnName() {
        return "_idx_" + fieldPath.replace('.', '_');
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IndexHint)) return false;
        IndexHint that = (IndexHint) o;
        return fieldPath.equals(that.fieldPath)
            && fieldType == that.fieldType
            && order     == that.order;
    }

    @Override
    public int hashCode() {
        return Objects.hash(fieldPath, fieldType, order);
    }

    // ------------------------------------------------------------------
    //  Annotation-driven factory
    // ------------------------------------------------------------------

    /**
     * Scans {@code clazz} and all its superclasses for {@link Indexed} annotations and
     * returns the corresponding {@link IndexHint} list.
     *
     * <p>This is the public entry point for annotation-driven index discovery; the
     * implementation delegates to the package-private {@link IndexHintScanner}.
     * Called automatically by
     * {@link EntityDescriptor.Builder#build()}.
     *
     * @throws IllegalArgumentException if an annotated field's type cannot be mapped
     *         to a supported {@link FieldType} and no explicit {@link Indexed#type()} was set
     */
    public static List<IndexHint> fromAnnotations(Class<?> clazz) {
        return IndexHintScanner.scan(clazz);
    }

    @Override
    public String toString() {
        return "IndexHint{" + fieldType + " " + order
            + " on '" + fieldPath + "'}";
    }
}
