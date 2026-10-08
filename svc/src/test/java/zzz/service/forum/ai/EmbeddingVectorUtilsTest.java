package zzz.service.forum.ai;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class EmbeddingVectorUtilsTest {

    @Test
    public void formatsPgVectorLiteral() {
        assertEquals("[0.1,-2,3.5]",
                EmbeddingVectorUtils.toPgVectorLiteral(Arrays.asList(0.10d, -2.0d, 3.5d), 3));
    }

    @Test
    public void rejectsDimensionMismatch() {
        assertThrows(IllegalArgumentException.class,
                () -> EmbeddingVectorUtils.toPgVectorLiteral(Arrays.asList(1.0d, 2.0d), 3));
    }
}
