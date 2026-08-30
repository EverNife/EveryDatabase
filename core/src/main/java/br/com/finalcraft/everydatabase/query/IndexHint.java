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
 * IndexHint.decimal("ratio")          // DOUBLE (double/float)
 * IndexHint.bigDecimal("balance")     // NUMERIC (java.math.BigDecimal, exact)
 * IndexHint.bool   ("active")         // BOOLEAN
 * IndexHint.uuid   ("guildId")        // java.util.UUID
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
        /** Java {@code float}/{@code Float}/{@code double}/{@code Double} → SQL {@code DOUBLE}. */
        DOUBLE,
        /**
         * Java {@link java.math.BigDecimal} → SQL {@code NUMERIC} (MySQL/MariaDB:
         * {@code DECIMAL(65,30)}, the widest their engine offers), MongoDB BSON
         * {@code Decimal128}, and a {@code BigDecimal} in the map/scan backends.
         *
         * <p>The whole point over {@link #DOUBLE} is that the comparison is decimal, not binary:
         * {@code 0.1 + 0.2} equals {@code 0.3} here, and two amounts that differ in the 20th digit
         * are two different values instead of one. Values are compared numerically, so a stored
         * {@code 2.50} is matched by a query for {@code 2.5} - trailing zeros are a rendering of
         * the same number, on every backend.
         *
         * <p>Each backend's numeric type bounds the <em>index</em>, never the stored entity:
         * MySQL/MariaDB round the index column to 30 decimal places and reject more than 35 integer
         * digits, MongoDB's {@code Decimal128} holds 34 significant digits, PostgreSQL and H2 are
         * unbounded. A value the backend cannot index fails its {@code save} with a message naming
         * the field, never a silently different number.
         */
        DECIMAL,
        /** Java {@code boolean}/{@code Boolean} → SQL {@code BOOLEAN}. */
        BOOLEAN,
        /**
         * A moment in time - Java {@link java.time.Instant}, {@link java.time.LocalDateTime},
         * {@link java.time.ZonedDateTime}, {@link java.time.OffsetDateTime}, {@link java.util.Date}
         * or a {@code long} of epoch millis → SQL {@code DATETIME(3)}/{@code TIMESTAMPTZ}, MongoDB
         * BSON {@code Date}. Stored and compared as epoch-milliseconds ({@code long}) in every
         * backend. The index column in SQL uses a native date type so values appear human-readable
         * in DB tools; InMemory and LocalFile use {@code Long} internally.
         *
         * <p>A {@link java.time.LocalDate} is <b>not</b> one of these: it names a day, not a moment,
         * and turning it into one requires inventing a time zone. Use {@link #DATE}.
         */
        TIMESTAMP,
        /**
         * A calendar day with no time and no zone - Java {@link java.time.LocalDate} → SQL
         * {@code DATE}, and the canonical ISO-8601 text ({@code "2026-08-29"}) on MongoDB and the
         * map/scan backends.
         *
         * <p>Text on those backends rather than a number for the same reason {@link #UUID} is text:
         * ISO-8601 dates sort lexicographically in chronological order, so ordering agrees with the
         * native {@code DATE} columns on all seven backends. Storing a day as an instant would need
         * a zone, and the value has none - two processes in different zones would disagree about
         * which day a row is on.
         */
        DATE,
        /**
         * Java {@link java.util.UUID} → SQL {@code CHAR(36)} (PostgreSQL: its native
         * {@code UUID} type), MongoDB and the file backends: the canonical 36-character
         * lowercase string.
         *
         * <p>Values are canonicalised on both the write and the query side, so
         * {@code eq}/{@code in} accept a {@link java.util.UUID} or any string spelling of one
         * interchangeably. Ordering also agrees on every backend: lexicographic order over
         * lowercase hex is byte-wise order over the 16 bytes, which is what PostgreSQL's
         * native type compares. (Note {@link java.util.UUID#compareTo} is <b>not</b> that
         * order - it compares the two longs signed - which is why the value is carried as a
         * string, and why H2's native {@code UUID} type is deliberately not used.)
         */
        UUID
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

    /**
     * {@link FieldType#DOUBLE} index on {@code fieldPath}, ascending - a binary floating-point
     * index for {@code double}/{@code float} fields.
     *
     * <p>For a {@link java.math.BigDecimal} field use {@link #bigDecimal(String)} instead: this one
     * would round every value through a {@code double} first, so two amounts differing beyond the
     * 17th digit collapse into one index entry.
     */
    public static IndexHint decimal(String fieldPath) {
        return new IndexHint(fieldPath, FieldType.DOUBLE, Order.ASCENDING);
    }

    /**
     * {@link FieldType#DECIMAL} index on {@code fieldPath}, ascending - the exact decimal index
     * for {@link java.math.BigDecimal} fields (money, balances, weights).
     *
     * <p>Accepts a {@link java.math.BigDecimal}, any other {@link Number}, or a string spelling a
     * number in queries, whichever the call site happens to hold:
     * <pre>{@code
     * .index(IndexHint.bigDecimal("balance"))
     *
     * repo.query(Query.eq("balance", new BigDecimal("2.50")));
     * repo.query(Query.eq("balance", "2.5"));                    // the same value
     * repo.query(Query.range("balance", new BigDecimal("10"), null));
     * }</pre>
     *
     * <p>A value that does not spell a number matches nothing - the same outcome a fractional value
     * has against an {@link FieldType#INT} index - rather than raising an error.
     */
    public static IndexHint bigDecimal(String fieldPath) {
        return new IndexHint(fieldPath, FieldType.DECIMAL, Order.ASCENDING);
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

    /**
     * {@link FieldType#DATE} index on {@code fieldPath}, ascending - a calendar day, for a
     * {@link java.time.LocalDate} field.
     *
     * <p>Accepts a {@link java.time.LocalDate}, any date-time that carries one
     * ({@link java.time.LocalDateTime}, {@link java.time.ZonedDateTime},
     * {@link java.time.OffsetDateTime} - each read in its own zone, never a guessed one), or an
     * ISO-8601 string:
     * <pre>{@code
     * .index(IndexHint.date("releasedOn"))
     *
     * repo.query(Query.eq("releasedOn", LocalDate.of(2026, 8, 29)));
     * repo.query(Query.range("releasedOn", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)));
     * }</pre>
     *
     * <p>A value that does not spell a date matches nothing rather than raising an error. An
     * {@link java.time.Instant} is such a value on purpose: which day it falls on depends on a zone
     * it does not carry, so converting it here would be a guess - do it at the call site
     * ({@code instant.atZone(zone).toLocalDate()}) where the right zone is known.
     */
    public static IndexHint date(String fieldPath) {
        return new IndexHint(fieldPath, FieldType.DATE, Order.ASCENDING);
    }

    /**
     * {@link FieldType#UUID} index on {@code fieldPath}, ascending.
     *
     * <p>Accepts a {@link java.util.UUID} or a string spelling one in queries, whichever the
     * call site happens to hold:
     * <pre>{@code
     * .index(IndexHint.uuid("guildId"))
     *
     * repo.query(Query.eq("guildId", guild.getId()));          // a java.util.UUID
     * repo.query(Query.eq("guildId", "6b1e...-...-...")); // the same value as text
     * }</pre>
     *
     * <p>A value that does not spell a UUID matches nothing - the same outcome a fractional
     * value has against an {@link FieldType#INT} index - rather than raising an error.
     */
    public static IndexHint uuid(String fieldPath) {
        return new IndexHint(fieldPath, FieldType.UUID, Order.ASCENDING);
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
