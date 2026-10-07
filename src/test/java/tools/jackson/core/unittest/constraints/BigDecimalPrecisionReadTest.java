package tools.jackson.core.unittest.constraints;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.unittest.JacksonCoreTestBase;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link StreamReadConstraints.Builder#maxBigDecimalPrecision(int)}:
 * limit on precision (significant digit count) and exponent magnitude, applied
 * when number tokens are converted to {@link BigDecimal} -- checked against the
 * original token text before the {@code BigDecimal} is constructed.
 */
@SuppressWarnings("resource")
class BigDecimalPrecisionReadTest extends JacksonCoreTestBase
{
    private final static int MAX_PRECISION = 100;

    private final JsonFactory F_LIMITED = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxBigDecimalPrecision(MAX_PRECISION)
                    .build())
            .build();

    private final JsonFactory F_DEFAULT = newStreamFactory();

    @Test
    void constraintDefaultsAndAccessors() throws Exception
    {
        // Default: unlimited
        assertEquals(-1, StreamReadConstraints.DEFAULT_MAX_BIG_DECIMAL_PRECISION);
        final StreamReadConstraints defs = StreamReadConstraints.defaults();
        assertEquals(-1, defs.getMaxBigDecimalPrecision());
        assertFalse(defs.hasMaxBigDecimalPrecision());
        // ... and no validation happens without a limit
        defs.validateBigDecimalPrecision(Integer.MAX_VALUE, Long.MAX_VALUE - 1);

        // Configured limit
        final StreamReadConstraints constr = StreamReadConstraints.builder()
                .maxBigDecimalPrecision(MAX_PRECISION)
                .build();
        assertEquals(MAX_PRECISION, constr.getMaxBigDecimalPrecision());
        assertTrue(constr.hasMaxBigDecimalPrecision());
        // rebuild() must retain the setting
        assertEquals(MAX_PRECISION, constr.rebuild().build().getMaxBigDecimalPrecision());

        // Non-positive values mean "unlimited"
        assertEquals(-1, StreamReadConstraints.builder()
                .maxBigDecimalPrecision(0).build().getMaxBigDecimalPrecision());
        assertEquals(-1, StreamReadConstraints.builder()
                .maxBigDecimalPrecision(-100).build().getMaxBigDecimalPrecision());

        // Direct validation calls: precision and exponent, both signs, boundary
        constr.validateBigDecimalPrecision(MAX_PRECISION, MAX_PRECISION);
        constr.validateBigDecimalPrecision(1, -MAX_PRECISION);
        try {
            constr.validateBigDecimalPrecision(MAX_PRECISION + 1, 0);
            fail("Should not pass");
        } catch (StreamConstraintsException e) {
            verifyException(e, "BigDecimal precision (101) exceeds the maximum allowed (100");
        }
        try {
            constr.validateBigDecimalPrecision(1, MAX_PRECISION + 1);
            fail("Should not pass");
        } catch (StreamConstraintsException e) {
            verifyException(e, "BigDecimal exponent (101) magnitude exceeds the maximum allowed (100");
        }
        try {
            constr.validateBigDecimalPrecision(1, -(MAX_PRECISION + 1));
            fail("Should not pass");
        } catch (StreamConstraintsException e) {
            verifyException(e, "BigDecimal exponent (-101) magnitude exceeds the maximum allowed (100");
        }
    }

    @Test
    void unlimitedByDefault() throws Exception
    {
        // Short token with absurd exponent: fine without a limit
        final String doc = "1e999999999";
        for (int mode : new int[] { MODE_INPUT_STREAM, MODE_READER }) {
            try (JsonParser p = createParser(F_DEFAULT, mode, doc)) {
                assertToken(JsonToken.VALUE_NUMBER_FLOAT, p.nextToken());
                assertEquals(new BigDecimal(doc), p.getDecimalValue());
            }
        }
    }

    @Test
    void shortNumbersAllowed() throws Exception
    {
        for (String doc : new String[] {
                "0.25", "-3.14", "1.5e3", "2.5e-2", "42.0", "0.0001", "123456789.5"
        }) {
            for (int mode : new int[] { MODE_INPUT_STREAM, MODE_READER }) {
                try (JsonParser p = createParser(F_LIMITED, mode, doc)) {
                    assertToken(JsonToken.VALUE_NUMBER_FLOAT, p.nextToken());
                    assertEquals(new BigDecimal(doc), p.getDecimalValue());
                }
            }
        }
    }

    @Test
    void precisionBoundary() throws Exception
    {
        // Exactly at the limit: 100 significant digits
        final String atLimit = "1." + "0".repeat(MAX_PRECISION - 1);
        assertEquals(MAX_PRECISION, new BigDecimal(atLimit).precision()); // sanity check
        // One over: 101 significant digits
        final String overLimit = "1." + "0".repeat(MAX_PRECISION);
        assertEquals(MAX_PRECISION + 1, new BigDecimal(overLimit).precision()); // sanity check
        // Leading zeroes are not significant: precision of 1
        final String leadingZeroes = "0." + "0".repeat(150) + "1";
        assertEquals(1, new BigDecimal(leadingZeroes).precision()); // sanity check

        for (int mode : new int[] { MODE_INPUT_STREAM, MODE_READER }) {
            try (JsonParser p = createParser(F_LIMITED, mode, atLimit)) {
                assertToken(JsonToken.VALUE_NUMBER_FLOAT, p.nextToken());
                assertEquals(new BigDecimal(atLimit), p.getDecimalValue());
            }
            try (JsonParser p = createParser(F_LIMITED, mode, leadingZeroes)) {
                assertToken(JsonToken.VALUE_NUMBER_FLOAT, p.nextToken());
                assertEquals(new BigDecimal(leadingZeroes), p.getDecimalValue());
            }
            try (JsonParser p = createParser(F_LIMITED, mode, overLimit)) {
                assertToken(JsonToken.VALUE_NUMBER_FLOAT, p.nextToken());
                p.getDecimalValue();
                fail("Should not pass (mode=" + mode + ")");
            } catch (StreamConstraintsException e) {
                verifyException(e, "BigDecimal precision (101) exceeds the maximum allowed (100");
            }
        }
    }

    @Test
    void exponentBoundary() throws Exception
    {
        for (int mode : new int[] { MODE_INPUT_STREAM, MODE_READER }) {
            // Exactly at the limit (both signs)
            for (String doc : new String[] { "1e100", "1e-100", "2.5E100" }) {
                try (JsonParser p = createParser(F_LIMITED, mode, doc)) {
                    assertToken(JsonToken.VALUE_NUMBER_FLOAT, p.nextToken());
                    assertEquals(new BigDecimal(doc), p.getDecimalValue());
                }
            }
            // One over (both signs)
            for (String doc : new String[] { "1e101", "1e-101" }) {
                try (JsonParser p = createParser(F_LIMITED, mode, doc)) {
                    assertToken(JsonToken.VALUE_NUMBER_FLOAT, p.nextToken());
                    p.getDecimalValue();
                    fail("Should not pass (mode=" + mode + ", doc=" + doc + ")");
                } catch (StreamConstraintsException e) {
                    verifyException(e, "magnitude exceeds the maximum allowed (100");
                }
            }
        }
    }

    @Test
    void forgedHugeExponentFails() throws Exception
    {
        // Short token text (well under maxNumberLength) with huge exponent
        for (String doc : new String[] { "1e999999999", "1e-999999999", "2.5e1000000000000" }) {
            for (int mode : new int[] { MODE_INPUT_STREAM, MODE_READER }) {
                try (JsonParser p = createParser(F_LIMITED, mode, doc)) {
                    assertToken(JsonToken.VALUE_NUMBER_FLOAT, p.nextToken());
                    p.getDecimalValue();
                    fail("Should not pass (mode=" + mode + ", doc=" + doc + ")");
                } catch (StreamConstraintsException e) {
                    verifyException(e, "BigDecimal exponent (", "magnitude exceeds the maximum allowed (100");
                }
            }
        }
    }

    @Test
    void errorLocationAndTokenText() throws Exception
    {
        final String doc = "[ 1e101 ]";
        for (int mode : new int[] { MODE_INPUT_STREAM, MODE_READER }) {
            try (JsonParser p = createParser(F_LIMITED, mode, doc)) {
                assertToken(JsonToken.START_ARRAY, p.nextToken());
                assertToken(JsonToken.VALUE_NUMBER_FLOAT, p.nextToken());
                final TokenStreamLocation tokenLoc = p.currentTokenLocation();
                try {
                    p.getDecimalValue();
                    fail("Should not pass (mode=" + mode + ")");
                } catch (StreamConstraintsException e) {
                    // Location of the failing token
                    final TokenStreamLocation loc = e.getLocation();
                    assertNotNull(loc);
                    assertEquals(tokenLoc.getLineNr(), loc.getLineNr());
                    assertEquals(tokenLoc.getColumnNr(), loc.getColumnNr());
                    assertEquals(1, loc.getLineNr());
                    assertEquals(3, loc.getColumnNr());
                    // Original token text retained in the message
                    verifyException(e, "1e101");
                }
                // Original token text still intact/accessible after the failure
                assertEquals("1e101", p.getString());
            }
        }
    }

    @Test
    void alternateDecimalAccessors() throws Exception
    {
        final String doc = "1e101";
        for (int mode : new int[] { MODE_INPUT_STREAM, MODE_READER }) {
            // getNumberValueExact() (BigDecimal for floats)
            try (JsonParser p = createParser(F_LIMITED, mode, doc)) {
                assertToken(JsonToken.VALUE_NUMBER_FLOAT, p.nextToken());
                p.getNumberValueExact();
                fail("Should not pass (mode=" + mode + ")");
            } catch (StreamConstraintsException e) {
                verifyException(e, "BigDecimal exponent (101)");
            }
            // getDoubleValue() first, then getDecimalValue()
            try (JsonParser p = createParser(F_LIMITED, mode, doc)) {
                assertToken(JsonToken.VALUE_NUMBER_FLOAT, p.nextToken());
                assertEquals(1e101, p.getDoubleValue());
                p.getDecimalValue();
                fail("Should not pass (mode=" + mode + ")");
            } catch (StreamConstraintsException e) {
                verifyException(e, "BigDecimal exponent (101)");
            }
        }
    }

    @Test
    void integerAccessUnaffected() throws Exception
    {
        for (int mode : new int[] { MODE_INPUT_STREAM, MODE_READER }) {
            // Regular int getters work as before
            try (JsonParser p = createParser(F_LIMITED, mode, "12345")) {
                assertToken(JsonToken.VALUE_NUMBER_INT, p.nextToken());
                assertEquals(12345, p.getIntValue());
                assertEquals(12345L, p.getLongValue());
                assertEquals(java.math.BigInteger.valueOf(12345L), p.getBigIntegerValue());
                // ... including conversion to BigDecimal (precision 5, within limit)
                assertEquals(new BigDecimal("12345"), p.getDecimalValue());
            }
            // Long integer (over 100 digits, within maxNumberLength): BigInteger
            // access not affected by BigDecimal precision limit
            final String bigInt = "1" + "7".repeat(199);
            try (JsonParser p = createParser(F_LIMITED, mode, bigInt)) {
                assertToken(JsonToken.VALUE_NUMBER_INT, p.nextToken());
                assertEquals(new java.math.BigInteger(bigInt), p.getBigIntegerValue());
            }
        }
    }
}
