package tools.jackson.core.unittest.read;

import org.junit.jupiter.api.Test;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.core.async.ByteArrayFeeder;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.unittest.JacksonCoreTestBase;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link JsonParser#verifyNoTrailingContent()}: the explicit
 * "read one full root value, nothing else" mode. Four input examples
 * covered:
 *<ol>
 *  <li>{@code {"a":1} <white space>} -- accepted
 *  <li>{@code {"a":1} {"b":2}} -- rejected (second root value)
 *  <li>{@code {"a":1} xyz} -- rejected (trailing garbage)
 *  <li>async feeder with temporarily-empty input -- not treated as EOF
 * </ol>
 */
@SuppressWarnings("resource")
class RootValueFullReadTest extends JacksonCoreTestBase
{
    private final JsonFactory F = JSON_FACTORY;

    // Example 1: one root object followed by legal trailing white space
    // only: must pass (white space skipped, EOF concludes input).
    @Test
    void objectWithTrailingWhitespace() throws Exception
    {
        for (int mode : ALL_MODES) {
            JsonParser p = createParser(mode, "{ \"a\" : 1 }  \n\t ");
            assertToken(JsonToken.START_OBJECT, p.nextToken());
            p.skipChildren();
            // clean EOF: null returned, no exception
            assertNull(p.verifyNoTrailingContent());
            p.close();
        }
    }

    // Example 2: second root-level value after the first one: must fail,
    // with location pointing at the real start of trailing content.
    @Test
    void secondRootValueRejected() throws Exception
    {
        final String doc = "{\"a\":1} {\"b\":2}";
        for (int mode : ALL_MODES) {
            JsonParser p = createParser(mode, doc);
            assertToken(JsonToken.START_OBJECT, p.nextToken());
            p.skipChildren();
            try {
                p.verifyNoTrailingContent();
                fail("Should not pass (mode " + mode + ")");
            } catch (StreamReadException e) {
                verifyException(e, "Trailing content after the root value");
                TokenStreamLocation loc = e.getLocation();
                assertNotNull(loc);
                if (mode != MODE_DATA_INPUT) { // DataInput parser does not track locations
                    // real trailing start: second '{' at offset 8, column 9
                    assertEquals(1, loc.getLineNr());
                    assertEquals(9, loc.getColumnNr());
                }
            }
            p.close();
        }
    }

    // Example 3: trailing garbage (not a valid token at all): must fail.
    @Test
    void trailingGarbageRejected() throws Exception
    {
        for (int mode : ALL_MODES) {
            JsonParser p = createParser(mode, "{\"a\":1} xyz");
            assertToken(JsonToken.START_OBJECT, p.nextToken());
            p.skipChildren();
            try {
                p.verifyNoTrailingContent();
                fail("Should not pass (mode " + mode + ")");
            } catch (StreamReadException e) {
                // decoding problem within trailing garbage
                verifyException(e, "Unrecognized token");
            }
            p.close();
        }
    }

    // Scalar root value: trailing scalar value is a second root value.
    @Test
    void scalarRootValueVariants() throws Exception
    {
        for (int mode : ALL_MODES) {
            JsonParser p = createParser(mode, "123  ");
            assertToken(JsonToken.VALUE_NUMBER_INT, p.nextToken());
            assertNull(p.verifyNoTrailingContent());
            p.close();

            p = createParser(mode, "123 456 ");
            assertToken(JsonToken.VALUE_NUMBER_INT, p.nextToken());
            try {
                p.verifyNoTrailingContent();
                fail("Should not pass (mode " + mode + ")");
            } catch (StreamReadException e) {
                verifyException(e, "Trailing content after the root value");
            }
            p.close();
        }
    }

    // Default (non-strict) behavior retained: consecutive root-level
    // values remain readable with plain nextToken() calls.
    @Test
    void consecutiveRootValuesStillAllowed() throws Exception
    {
        for (int mode : ALL_MODES) {
            JsonParser p = createParser(mode, "1 2 ");
            assertToken(JsonToken.VALUE_NUMBER_INT, p.nextToken());
            assertEquals(1, p.getIntValue());
            assertToken(JsonToken.VALUE_NUMBER_INT, p.nextToken());
            assertEquals(2, p.getIntValue());
            assertNull(p.nextToken());
            p.close();
        }
    }

    // Example 4: non-blocking parser -- temporarily empty feeder is NOT
    // end-of-input; verification only concludes after endOfInput().
    @Test
    void asyncFeederTemporaryEmptyInput() throws Exception
    {
        JsonParser p = F.createNonBlockingByteArrayParser(ObjectReadContext.empty());
        ByteArrayFeeder feeder = (ByteArrayFeeder) p.nonBlockingInputFeeder();

        // feed the root object in chunks
        byte[] part1 = utf8Bytes("{\"a\":");
        byte[] part2 = utf8Bytes("1}");
        feeder.feedInput(part1, 0, part1.length);
        assertToken(JsonToken.START_OBJECT, p.nextToken());
        assertToken(JsonToken.PROPERTY_NAME, p.nextToken());
        // mid-value, no more data right now: NOT_AVAILABLE, not EOF
        assertToken(JsonToken.NOT_AVAILABLE, p.nextToken());
        feeder.feedInput(part2, 0, part2.length);
        assertToken(JsonToken.VALUE_NUMBER_INT, p.nextToken());
        assertToken(JsonToken.END_OBJECT, p.nextToken());

        // root value complete, but feeder temporarily empty: verification
        // must NOT conclude (neither success nor EOF), just NOT_AVAILABLE
        assertToken(JsonToken.NOT_AVAILABLE, p.verifyNoTrailingContent());
        // still NOT_AVAILABLE on repeated calls: input not terminated
        assertToken(JsonToken.NOT_AVAILABLE, p.verifyNoTrailingContent());

        // feed legal trailing white space only: still cannot conclude
        byte[] ws = utf8Bytes("  \n ");
        feeder.feedInput(ws, 0, ws.length);
        assertToken(JsonToken.NOT_AVAILABLE, p.verifyNoTrailingContent());

        // only explicit end-of-input concludes: clean pass
        feeder.endOfInput();
        assertNull(p.verifyNoTrailingContent());
        p.close();
    }

    // Async variant of example 2: trailing second root value fed in
    // chunks must fail once available, not be silently ignored.
    @Test
    void asyncFeederTrailingContentRejected() throws Exception
    {
        JsonParser p = F.createNonBlockingByteArrayParser(ObjectReadContext.empty());
        ByteArrayFeeder feeder = (ByteArrayFeeder) p.nonBlockingInputFeeder();

        byte[] part1 = utf8Bytes("{\"a\":1} ");
        feeder.feedInput(part1, 0, part1.length);
        assertToken(JsonToken.START_OBJECT, p.nextToken());
        p.skipChildren();
        // only white space fed so far, no end-of-input: cannot conclude
        assertToken(JsonToken.NOT_AVAILABLE, p.verifyNoTrailingContent());

        byte[] part2 = utf8Bytes("{\"b\":2}");
        feeder.feedInput(part2, 0, part2.length);
        try {
            p.verifyNoTrailingContent();
            fail("Should not pass");
        } catch (StreamReadException e) {
            verifyException(e, "Trailing content after the root value");
        }
        p.close();
    }
}
