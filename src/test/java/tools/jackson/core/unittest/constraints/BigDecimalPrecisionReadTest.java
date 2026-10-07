package tools.jackson.core.unittest.constraints;

import java.math.BigDecimal;
import java.math.BigInteger;

import org.junit.jupiter.api.Test;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.core.json.JsonFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for verifying that the (optional) {@code BigDecimal}
 * precision/exponent limit of {@link StreamReadConstraints}
 * (see {@link StreamReadConstraints.Builder#maxBigDecimalPrecision(int)})
 * is enforced when JSON numbers are converted to {@link BigDecimal},
 * consistently across input source types, without affecting other
 * numeric accessors.
 */
@SuppressWarnings("resource")
class BigDecimalPrecisionReadTest
    extends tools.jackson.core.unittest.JacksonCoreTestBase
{
    private final JsonFactory JSON_F = newStreamFactory();

    /*
    /**********************************************************************
    /* Tests, default settings (unlimited)
    /**********************************************************************
     */

    @Test
    void defaultIsUnlimited() throws Exception
    {
        final StreamReadConstraints constraints = StreamReadConstraints.defaults();
        assertEquals(-1, constraints.getMaxBigDecimalPrecision());
        assertFalse(constraints.hasMaxBigDecimalPrecision());
        // And with no limit, validation is a no-op
        constraints.validateBigDecimalPrecision(Integer.MAX_VALUE, Integer.MAX_VALUE);
        constraints.validateBigDecimalPrecision(Integer.MAX_VALUE, Integer.MIN_VALUE);

        // Forged huge exponent is fine by default (but do not toString() it!)
        final String DOC = "1e999999999";
        for (int mode : new int[] { MODE_INPUT_STREAM, MODE_READER }) {
            try (JsonParser p = createParser(JSON_F, mode, DOC)) {
                assertToken(JsonToken.VALUE_NUMBER_FLOAT, p.nextToken());
                assertEquals(new BigDecimal(DOC), p.getDecimalValue());
            }
        }
    }

    /*
    /**********************************************************************
    /* Tests, precision (significant digit) limits
    /**********************************************************************
     */

    @Test
    void shortNumberAtExactBoundary() throws Exception
    {
        final JsonFactory f = _factoryWithLimit(4);
        // Precision exactly at the limit must pass, both input paths
        for (String doc : new String[] { "12.34", "123.4", "-1.234", "0.1234", "1.234e2" }) {
            for (int mode : new int[] { MODE_INPUT_STREAM, MODE_READER }) {
                try (JsonParser p = createParser(f, mode, doc)) {
                    assertToken(JsonToken.VALUE_NUMBER_FLOAT, p.nextToken());
                    assertEquals(new BigDecimal(doc), p.getDecimalValue());
                    assertEquals(doc, p.getString());
                }
            }
        }
    }

    @Test
    void precisionExceeded() throws Exception
    {
        final JsonFactory f = _factoryWithLimit(4);
        // One significant digit over the limit must fail, both input paths
        for (String doc : new String[] { "123.45", "1.2345" }) {
            for (int mode : new int[] { MODE_INPUT_STREAM, MODE_READER }) {
                try (JsonParser p = createParser(f, mode, doc)) {
                    assertToken(JsonToken.VALUE_NUMBER_FLOAT, p.nextToken());
                    StreamConstraintsException e = assertThrows(StreamConstraintsException.class,
                            () -> p.getDecimalValue());
                    verifyException(e, "BigDecimal precision (5) exceeds the maximum allowed (4");
                    verifyException(e, "StreamReadConstraints.getMaxBigDecimalPrecision()");
                    // Original token text must still be available
                    assertEquals(doc, p.getString());
                }
            }
        }
    }

    /*
    /**********************************************************************
    /* Tests, exponent magnitude limits ("forged" exponents)
    /**********************************************************************
     */

    @Test
    void exponentAtExactBoundary() throws Exception
    {
        final JsonFactory f = _factoryWithLimit(100);
        for (String doc : new String[] { "1e100", "1E+100", "1e-100", "-2.5e100" }) {
            for (int mode : new int[] { MODE_INPUT_STREAM, MODE_READER }) {
                try (JsonParser p = createParser(f, mode, doc)) {
                    assertToken(JsonToken.VALUE_NUMBER_FLOAT, p.nextToken());
                    assertEquals(new BigDecimal(doc), p.getDecimalValue());
                }
            }
        }
    }

    @Test
    void forgedExponentExceeded() throws Exception
    {
        final JsonFactory f = _factoryWithLimit(100);
        // Exponent magnitude just over the limit, and absurdly large forged
        // exponents (including one that does not fit in 31 bits as written)
        for (String doc : new String[] { "1e101", "1e-101", "1e999999999",
                "1e-999999999", "3.14159E+2147483648" }) {
            for (int mode : new int[] { MODE_INPUT_STREAM, MODE_READER }) {
                try (JsonParser p = createParser(f, mode, doc)) {
                    assertToken(JsonToken.VALUE_NUMBER_FLOAT, p.nextToken());
                    StreamConstraintsException e = assertThrows(StreamConstraintsException.class,
                            () -> p.getDecimalValue());
                    verifyException(e, "magnitude exceeds the maximum allowed (100");
                    verifyException(e, "StreamReadConstraints.getMaxBigDecimalPrecision()");
                    // Original token text must still be available
                    assertEquals(doc, p.getString());
                }
            }
        }
    }

    /*
    /**********************************************************************
    /* Tests, exception location and token text retention
    /**********************************************************************
     */

    @Test
    void exceptionHasTokenLocation() throws Exception
    {
        final JsonFactory f = _factoryWithLimit(10);
        final String DOC = "[ 1e500 ]";
        for (int mode : new int[] { MODE_INPUT_STREAM, MODE_READER }) {
            try (JsonParser p = createParser(f, mode, DOC)) {
                assertToken(JsonToken.START_ARRAY, p.nextToken());
                assertToken(JsonToken.VALUE_NUMBER_FLOAT, p.nextToken());
                StreamConstraintsException e = assertThrows(StreamConstraintsException.class,
                        () -> p.getDecimalValue());
                TokenStreamLocation loc = e.getLocation();
                assertNotNull(loc);
                assertEquals(1, loc.getLineNr());
                assertEquals(3, loc.getColumnNr());
                // Token text remains intact after the failed conversion
                assertEquals("1e500", p.getString());
            }
        }
    }

    /*
    /**********************************************************************
    /* Tests, other numeric accessors not affected
    /**********************************************************************
     */

    @Test
    void integerGettersNotAffected() throws Exception
    {
        // Very strict limit: should not apply to integer accessors at all
        final JsonFactory f = _factoryWithLimit(2);
        for (int mode : new int[] { MODE_INPUT_STREAM, MODE_READER }) {
            // Regular int/long values way past the precision limit
            try (JsonParser p = createParser(f, mode, "123456789")) {
                assertToken(JsonToken.VALUE_NUMBER_INT, p.nextToken());
                assertEquals(123456789, p.getIntValue());
                assertEquals(123456789L, p.getLongValue());
                assertEquals(BigInteger.valueOf(123456789L), p.getBigIntegerValue());
                assertEquals(123456789, p.getNumberValue());
            }
            // BigInteger well past the limit
            final String big = "123456789012345678901234567890";
            try (JsonParser p = createParser(f, mode, big)) {
                assertToken(JsonToken.VALUE_NUMBER_INT, p.nextToken());
                assertEquals(new BigInteger(big), p.getBigIntegerValue());
            }
            // double/float accessors on floating-point tokens not limited either
            try (JsonParser p = createParser(f, mode, "1.2345e50")) {
                assertToken(JsonToken.VALUE_NUMBER_FLOAT, p.nextToken());
                assertEquals(1.2345e50d, p.getDoubleValue());
                assertEquals("1.2345e50", p.getString());
            }
        }
    }

    @Test
    void lazyAndDeferredAccessAlsoChecked() throws Exception
    {
        final JsonFactory f = _factoryWithLimit(10);
        for (int mode : new int[] { MODE_INPUT_STREAM, MODE_READER }) {
            // Deferred access exposes String, conversion happens later
            try (JsonParser p = createParser(f, mode, "5e200")) {
                assertToken(JsonToken.VALUE_NUMBER_FLOAT, p.nextToken());
                assertEquals("5e200", p.getNumberValueDeferred());
                StreamConstraintsException e = assertThrows(StreamConstraintsException.class,
                        () -> p.getDecimalValue());
                verifyException(e, "BigDecimal exponent (200) magnitude exceeds");
                assertEquals("5e200", p.getString());
            }
            // Exact-number access goes through BigDecimal as well
            try (JsonParser p = createParser(f, mode, "5e200")) {
                assertToken(JsonToken.VALUE_NUMBER_FLOAT, p.nextToken());
                StreamConstraintsException e = assertThrows(StreamConstraintsException.class,
                        () -> p.getNumberValueExact());
                verifyException(e, "BigDecimal exponent (200) magnitude exceeds");
            }
        }
    }

    /*
    /**********************************************************************
    /* Helper methods
    /**********************************************************************
     */

    private JsonFactory _factoryWithLimit(int maxPrecision) {
        return JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxBigDecimalPrecision(maxPrecision)
                        .build())
                .build();
    }
}
