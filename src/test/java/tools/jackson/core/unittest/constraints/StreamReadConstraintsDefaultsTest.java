package tools.jackson.core.unittest.constraints;

import org.junit.jupiter.api.Test;

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.exc.StreamConstraintsException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class StreamReadConstraintsDefaultsTest
{
    @Test
    void override() {
        final long maxDocLen = 10_000_000L;
        final int numLen = 1234;
        final int strLen = 12345;
        final int depth = 123;
        final int nameLen = 2000;
        final int bigDecimalPrecision = 250;
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxDocumentLength(maxDocLen)
                .maxNumberLength(numLen)
                .maxStringLength(strLen)
                .maxNameLength(nameLen)
                .maxNestingDepth(depth)
                .maxBigDecimalPrecision(bigDecimalPrecision)
                .build();
        try {
            StreamReadConstraints.overrideDefaultStreamReadConstraints(constraints);
            assertEquals(maxDocLen, StreamReadConstraints.defaults().getMaxDocumentLength());
            assertEquals(depth, StreamReadConstraints.defaults().getMaxNestingDepth());
            assertEquals(strLen, StreamReadConstraints.defaults().getMaxStringLength());
            assertEquals(nameLen, StreamReadConstraints.defaults().getMaxNameLength());
            assertEquals(numLen, StreamReadConstraints.defaults().getMaxNumberLength());
            assertEquals(bigDecimalPrecision, StreamReadConstraints.defaults().getMaxBigDecimalPrecision());
        } finally {
            StreamReadConstraints.overrideDefaultStreamReadConstraints(null);
            assertEquals(StreamReadConstraints.DEFAULT_MAX_DOC_LEN,
                    StreamReadConstraints.defaults().getMaxDocumentLength());
            assertEquals(StreamReadConstraints.DEFAULT_MAX_DEPTH,
                    StreamReadConstraints.defaults().getMaxNestingDepth());
            assertEquals(StreamReadConstraints.DEFAULT_MAX_STRING_LEN,
                    StreamReadConstraints.defaults().getMaxStringLength());
            assertEquals(StreamReadConstraints.DEFAULT_MAX_NAME_LEN,
                    StreamReadConstraints.defaults().getMaxNameLength());
            assertEquals(StreamReadConstraints.DEFAULT_MAX_NUM_LEN,
                    StreamReadConstraints.defaults().getMaxNumberLength());
            assertEquals(StreamReadConstraints.DEFAULT_MAX_BIGDECIMAL_PRECISION,
                    StreamReadConstraints.defaults().getMaxBigDecimalPrecision());
        }
    }

    // [core#1715]: accessor for maximum BigInteger (BigDecimal) scale magnitude
    @Test
    void maxBigIntegerScale() throws Exception
    {
        final StreamReadConstraints constraints = StreamReadConstraints.defaults();
        final int limit = constraints.getMaxBigIntegerScale();

        assertEquals(100_000, limit);

        // Within limit (both signs) must pass:
        constraints.validateBigIntegerScale(limit);
        constraints.validateBigIntegerScale(-limit);

        // But just past it must fail, and message should refer to the accessor.
        // Note: `Integer.MIN_VALUE` included since `Math.abs()` of it is negative
        for (int scale : new int[] { limit + 1, -(limit + 1),
                Integer.MAX_VALUE, Integer.MIN_VALUE }) {
            try {
                constraints.validateBigIntegerScale(scale);
                fail("Should not pass, scale "+scale);
            } catch (StreamConstraintsException e) {
                final String msg = e.getMessage();
                assertTrue(msg.contains("BigDecimal scale ("+scale+")"),
                        "Unexpected message: "+msg);
                assertTrue(msg.contains("StreamReadConstraints.getMaxBigIntegerScale()"),
                        "Unexpected message: "+msg);
            }
        }
    }

    // Accessor/validation for maximum BigDecimal precision/exponent magnitude
    @Test
    void maxBigDecimalPrecision() throws Exception
    {
        // Default: unlimited
        final StreamReadConstraints defaults = StreamReadConstraints.defaults();
        assertEquals(StreamReadConstraints.DEFAULT_MAX_BIGDECIMAL_PRECISION,
                defaults.getMaxBigDecimalPrecision());
        assertEquals(-1, defaults.getMaxBigDecimalPrecision());
        assertFalse(defaults.hasMaxBigDecimalPrecision());
        // ... in which case validation is a no-op
        defaults.validateBigDecimalPrecision(Integer.MAX_VALUE, Integer.MAX_VALUE);

        final int limit = 100;
        final StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxBigDecimalPrecision(limit)
                .build();
        assertEquals(limit, constraints.getMaxBigDecimalPrecision());
        assertTrue(constraints.hasMaxBigDecimalPrecision());

        // Non-positive values mean "unlimited" as well
        assertEquals(-1, StreamReadConstraints.builder()
                .maxBigDecimalPrecision(0).build().getMaxBigDecimalPrecision());
        // And rebuild() retains the setting
        assertEquals(limit, constraints.rebuild().build().getMaxBigDecimalPrecision());

        // Within limit (both signs of exponent) must pass:
        constraints.validateBigDecimalPrecision(limit, limit);
        constraints.validateBigDecimalPrecision(1, -limit);

        // But just past it must fail, and message should refer to the accessor.
        try {
            constraints.validateBigDecimalPrecision(limit + 1, 0);
            fail("Should not pass, precision "+(limit + 1));
        } catch (StreamConstraintsException e) {
            final String msg = e.getMessage();
            assertTrue(msg.contains("BigDecimal precision ("+(limit + 1)+")"),
                    "Unexpected message: "+msg);
            assertTrue(msg.contains("StreamReadConstraints.getMaxBigDecimalPrecision()"),
                    "Unexpected message: "+msg);
        }
        // Note: `Integer.MIN_VALUE` included since `Math.abs()` of it is negative
        for (int exp : new int[] { limit + 1, -(limit + 1),
                Integer.MAX_VALUE, Integer.MIN_VALUE }) {
            try {
                constraints.validateBigDecimalPrecision(1, exp);
                fail("Should not pass, exponent "+exp);
            } catch (StreamConstraintsException e) {
                final String msg = e.getMessage();
                assertTrue(msg.contains("BigDecimal exponent ("+exp+")"),
                        "Unexpected message: "+msg);
                assertTrue(msg.contains("StreamReadConstraints.getMaxBigDecimalPrecision()"),
                        "Unexpected message: "+msg);
            }
        }
    }
}
