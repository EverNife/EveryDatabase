package br.com.finalcraft.everydatabase.modules.mongo;

import com.fasterxml.jackson.databind.JsonNode;
import org.bson.Document;
import org.bson.types.Binary;
import org.bson.types.Decimal128;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Converts the Jackson tree a codec produced into the BSON {@link Document} MongoDB stores.
 *
 * <p>It exists because the driver's own {@code Document.parse} reads every fractional JSON number
 * as a {@code double}: a {@code BigDecimal} field would come back rounded to ~17 significant digits
 * (and {@code 2.50} indistinguishable from {@code 2.5}), and an integer beyond {@code long} range
 * would not parse at all. Here a decimal becomes a BSON {@link Decimal128}, which is exact and keeps
 * its scale, and every other node keeps the type {@code Document.parse} would have given it, so the
 * stored shape is unchanged for data that never needed the extra precision.
 *
 * <p>The reverse direction needs no code: {@code Document.toJson} writes a {@code Decimal128} back
 * as a plain JSON number through the converter {@code MongoRepository} installs on its writer
 * settings.
 */
final class BsonTrees {

    /**
     * The exact-number ceiling of BSON: {@link Decimal128} holds 34 significant digits, which is
     * what MongoDB itself can compare and index. Reported in the error rather than silently rounded.
     */
    private static final int DECIMAL128_MAX_DIGITS = 34;

    private BsonTrees() {}

    /**
     * The document for an entity tree.
     *
     * @param tree       the encoded entity, parsed with decimal fidelity
     * @param collection the collection name, named in the error when a value does not fit BSON
     * @throws IllegalArgumentException if a number in the tree cannot be stored exactly
     */
    static Document toDocument(JsonNode tree, String collection) {
        if (tree == null || !tree.isObject()) {
            throw new IllegalArgumentException(
                "Mongo: collection '" + collection + "' encoded to " + (tree == null ? "nothing" : tree.getNodeType())
                + " instead of a JSON object. MongoStorage stores each entity as a document, so its codec "
                + "must serialise it as a JSON object (a bare array or scalar has no document to be).");
        }
        return (Document) toBson(tree, collection, "");
    }

    private static Object toBson(JsonNode node, String collection, String path) {
        switch (node.getNodeType()) {
            case OBJECT: {
                Document doc = new Document();
                for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                    Map.Entry<String, JsonNode> field = it.next();
                    doc.append(field.getKey(), toBson(field.getValue(), collection, child(path, field.getKey())));
                }
                return doc;
            }
            case ARRAY: {
                List<Object> list = new ArrayList<>(node.size());
                for (int i = 0; i < node.size(); i++) {
                    list.add(toBson(node.get(i), collection, path + "[" + i + "]"));
                }
                return list;
            }
            case NUMBER:  return number(node, collection, path);
            case STRING:  return node.textValue();
            case BOOLEAN: return node.booleanValue();
            case BINARY:  return new Binary(binaryOf(node, collection, path));
            case NULL:
            case MISSING:
            default:      return null;
        }
    }

    private static Object number(JsonNode node, String collection, String path) {
        if (node.isInt() || node.isShort())    return node.intValue();
        if (node.isLong())                     return node.longValue();
        if (node.isDouble() || node.isFloat()) return node.doubleValue();
        if (node.isBigInteger()) {
            BigInteger value = node.bigIntegerValue();
            return value.bitLength() < Long.SIZE ? (Object) value.longValue()
                                                 : decimal128(new BigDecimal(value), collection, path);
        }
        return decimal128(node.decimalValue(), collection, path);
    }

    private static Decimal128 decimal128(BigDecimal value, String collection, String path) {
        try {
            return new Decimal128(value);
        } catch (NumberFormatException tooWide) {
            throw new IllegalArgumentException(
                "Mongo: the value " + value.toPlainString() + " at '" + path + "' in collection '"
                + collection + "' needs more than the " + DECIMAL128_MAX_DIGITS + " significant digits "
                + "of BSON Decimal128, the widest exact number MongoDB has - storing it would round it "
                + "silently. Round the value to " + DECIMAL128_MAX_DIGITS + " significant digits before "
                + "saving, or declare the field as a String (exact on every backend, at the cost of "
                + "numeric ordering and range queries).", tooWide);
        }
    }

    private static byte[] binaryOf(JsonNode node, String collection, String path) {
        try {
            return node.binaryValue();
        } catch (Exception notBinary) {
            throw new IllegalArgumentException(
                "Mongo: cannot read the binary value at '" + path + "' in collection '" + collection + "'",
                notBinary);
        }
    }

    private static String child(String path, String field) {
        return path.isEmpty() ? field : path + "." + field;
    }
}
