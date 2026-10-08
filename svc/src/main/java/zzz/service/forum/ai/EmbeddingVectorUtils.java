package zzz.service.forum.ai;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** pgvector 字面量转换工具。 */
public final class EmbeddingVectorUtils {

    private EmbeddingVectorUtils() {
    }

    public static String toPgVectorLiteral(List<Double> vector, int dimension) {
        if (vector == null || vector.isEmpty()) {
            return null;
        }
        if (dimension > 0 && vector.size() != dimension) {
            throw new IllegalArgumentException("embedding dimension mismatch, expected=" + dimension
                    + ", actual=" + vector.size());
        }
        StringBuilder builder = new StringBuilder(vector.size() * 8 + 2);
        builder.append('[');
        for (int i = 0; i < vector.size(); i++) {
            if (i > 0) builder.append(',');
            Double value = vector.get(i);
            if (value == null || value.isNaN() || value.isInfinite()) {
                throw new IllegalArgumentException("embedding vector contains invalid number at " + i);
            }
            builder.append(BigDecimal.valueOf(value).stripTrailingZeros().toPlainString());
        }
        builder.append(']');
        return builder.toString();
    }

    public static List<Double> parseNumbers(List<?> values) {
        List<Double> result = new ArrayList<>();
        if (values == null) return result;
        for (Object value : values) {
            if (value instanceof Number) {
                result.add(((Number) value).doubleValue());
            }
        }
        return result;
    }
}
