package br.com.finalcraft.evernifecore.storage.query;

/**
 * Composable filter specification for {@link QueryableRepository#findWhere(Spec)}.
 *
 * <p>Create leaf specs with factory methods and compose them with {@link #and}/{@link #or}:
 * <pre>{@code
 * Spec<PlayerData> filter = Spec.<PlayerData>eq("rank", "ADMIN")
 *         .and(Spec.gt("score", 1000));
 * repo.findWhere(filter).thenAccept(list -> ...);
 * }</pre>
 *
 * @param <V> the entity type this spec applies to
 */
public abstract class Spec<V> {

    // ------------------------------------------------------------------
    //  Factory
    // ------------------------------------------------------------------

    public static <V> Spec<V> eq(String field, Object value)  { return new FieldSpec<>(field, Op.EQ, value); }
    public static <V> Spec<V> neq(String field, Object value) { return new FieldSpec<>(field, Op.NEQ, value); }
    public static <V> Spec<V> gt(String field, Object value)  { return new FieldSpec<>(field, Op.GT, value); }
    public static <V> Spec<V> gte(String field, Object value) { return new FieldSpec<>(field, Op.GTE, value); }
    public static <V> Spec<V> lt(String field, Object value)  { return new FieldSpec<>(field, Op.LT, value); }
    public static <V> Spec<V> lte(String field, Object value) { return new FieldSpec<>(field, Op.LTE, value); }

    // ------------------------------------------------------------------
    //  Combinators
    // ------------------------------------------------------------------

    public Spec<V> and(Spec<V> other) { return new CompositeSpec<>(this, other, LogicOp.AND); }
    public Spec<V> or(Spec<V> other)  { return new CompositeSpec<>(this, other, LogicOp.OR); }

    // ------------------------------------------------------------------
    //  Enums
    // ------------------------------------------------------------------

    public enum Op { EQ, NEQ, GT, GTE, LT, LTE }
    public enum LogicOp { AND, OR }

    // ------------------------------------------------------------------
    //  Subtypes
    // ------------------------------------------------------------------

    public static final class FieldSpec<V> extends Spec<V> {
        private final String field;
        private final Op op;
        private final Object value;

        FieldSpec(String field, Op op, Object value) {
            this.field = field;
            this.op    = op;
            this.value = value;
        }

        public String field() { return field; }
        public Op op()        { return op; }
        public Object value() { return value; }
    }

    public static final class CompositeSpec<V> extends Spec<V> {
        private final Spec<V> left;
        private final Spec<V> right;
        private final LogicOp logicOp;

        CompositeSpec(Spec<V> left, Spec<V> right, LogicOp logicOp) {
            this.left    = left;
            this.right   = right;
            this.logicOp = logicOp;
        }

        public Spec<V> left()    { return left; }
        public Spec<V> right()   { return right; }
        public LogicOp logicOp() { return logicOp; }
    }
}
