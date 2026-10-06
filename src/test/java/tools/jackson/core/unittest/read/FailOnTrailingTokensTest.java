package tools.jackson.core.unittest.read;

import org.junit.jupiter.api.Test;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.core.async.ByteArrayFeeder;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.unittest.JacksonCoreTestBase;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the explicit "single root value, then EOF" read mode:
 * {@link JsonParser#verifyNoTrailingTokens()} and
 * {@link StreamReadFeature#FAIL_ON_TRAILING_TOKENS}.
 *<p>
 * Four input shapes are covered:
 *<ol>
 * <li>Root value followed by legal trailing whitespace only: accepted</li>
 * <li>Root value followed by a second root value: rejected, location
 *   pointing at the actual start of trailing content</li>
 * <li>Root value followed by garbage: rejected</li>
 * <li>Async feeder with temporarily-empty chunks: not mistaken for EOF</li>
 *</ol>
 */
class FailOnTrailingTokensTest extends JacksonCoreTestBase
{
    private final JsonFactory JSON_F = newStreamFactory();

    private final JsonFactory STRICT_F = JsonFactory.builder()
            .enable(StreamReadFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    // Minimal "databind" stand-in: consumes exactly one root-level value
    static class TestReadContext extends ObjectReadContext.Base {
        @Override
        @SuppressWarnings("unchecked")
        public <T> T readValue(JsonParser p, Class<T> valueType) {
            JsonToken t = p.nextToken();
            if ((t != null) && t.isStructStart()) {
                p.skipChildren();
            }
            return (T) t;
        }
    }

    // Input 1: root value + legal trailing whitespace -> accepted

    @Test
    void trailingWhitespaceAllowed() throws Exception {
        for (int mode : ALL_MODES) {
            try (JsonParser p = createParser(JSON_F, mode, "{\"a\":1}  \n\t ")) {
                assertToken(JsonToken.START_OBJECT, p.nextToken());
                p.skipChildren();
                assertToken(JsonToken.END_OBJECT, p.currentToken());
                assertTrue(p.verifyNoTrailingTokens());
                assertNull(p.nextToken());
            }
        }
    }

    // Input 2: root value + second root value -> rejected,
    // location at actual trailing start

    @Test
    void secondRootValueRejected() throws Exception {
        // second START_OBJECT is at offset 8 (line 1, column 9)
        for (int mode : ALL_MODES) {
            try (JsonParser p = createParser(JSON_F, mode, "{\"a\":1} {\"b\":2}")) {
                assertToken(JsonToken.START_OBJECT, p.nextToken());
                p.skipChildren();
                StreamReadException e = assertThrows(StreamReadException.class,
                        p::verifyNoTrailingTokens);
                verifyException(e, "Trailing token");
                _assertTrailingLocation(e.getLocation(), mode, 8);
            }
        }
    }

    // Input 3: root value + garbage -> rejected

    @Test
    void garbageAfterRootValueRejected() throws Exception {
        for (int mode : ALL_MODES) {
            try (JsonParser p = createParser(JSON_F, mode, "{\"a\":1} ???")) {
                assertToken(JsonToken.START_OBJECT, p.nextToken());
                p.skipChildren();
                StreamReadException e = assertThrows(StreamReadException.class,
                        p::verifyNoTrailingTokens);
                // location at actual start of trailing garbage (offset 8)
                _assertTrailingLocation(e.getLocation(), mode, 8);
            }
        }
    }

    // Location of trailing content must be its actual start; different
    // backends track different coordinates (DataInput only tracks line)
    private void _assertTrailingLocation(TokenStreamLocation loc, int mode, long expOffset) {
        assertEquals(1, loc.getLineNr(), "line for mode "+mode);
        switch (mode) {
        case MODE_INPUT_STREAM:
        case MODE_INPUT_STREAM_THROTTLED:
            assertEquals(expOffset, loc.getByteOffset(), "byte offset for mode "+mode);
            assertEquals(expOffset + 1, loc.getColumnNr(), "column for mode "+mode);
            break;
        case MODE_READER:
        case MODE_READER_THROTTLED:
            assertEquals(expOffset, loc.getCharOffset(), "char offset for mode "+mode);
            assertEquals(expOffset + 1, loc.getColumnNr(), "column for mode "+mode);
            break;
        default: // MODE_DATA_INPUT: only line number tracked
        }
    }

    // Input 4: async feeder, temporarily-empty chunks are not EOF

    @Test
    void asyncTemporaryEmptinessIsNotEof() throws Exception {
        byte[] doc = utf8Bytes("{\"a\":1}");
        JsonParser p = JSON_F.createNonBlockingByteArrayParser(ObjectReadContext.empty());
        ByteArrayFeeder feeder = (ByteArrayFeeder) p.nonBlockingInputFeeder();

        // feed document in 2-byte chunks, consume the root value
        int offset = 0;
        while (true) {
            JsonToken t = p.nextToken();
            if (t == JsonToken.NOT_AVAILABLE) {
                int amount = Math.min(2, doc.length - offset);
                feeder.feedInput(doc, offset, offset + amount);
                offset += amount;
            } else if (t == JsonToken.END_OBJECT) {
                break;
            }
        }

        // temporarily no input, end-of-input not signalled: inconclusive,
        // must NOT be taken as EOF/success
        assertFalse(p.verifyNoTrailingTokens());

        // legal trailing whitespace alone still does not conclude anything
        byte[] ws = utf8Bytes("  ");
        feeder.feedInput(ws, 0, ws.length);
        assertFalse(p.verifyNoTrailingTokens());

        // only explicit end-of-input completes verification successfully
        feeder.endOfInput();
        assertTrue(p.verifyNoTrailingTokens());
        p.close();
    }

    @Test
    void asyncSecondRootValueRejected() throws Exception {
        byte[] doc = utf8Bytes("{\"a\":1}");
        JsonParser p = JSON_F.createNonBlockingByteArrayParser(ObjectReadContext.empty());
        ByteArrayFeeder feeder = (ByteArrayFeeder) p.nonBlockingInputFeeder();

        feeder.feedInput(doc, 0, doc.length);
        assertToken(JsonToken.START_OBJECT, p.nextToken());
        p.skipChildren();
        assertToken(JsonToken.END_OBJECT, p.currentToken());

        // inconclusive while more input may still arrive
        assertFalse(p.verifyNoTrailingTokens());

        // second root value arrives: rejected, location at its start.
        // NOTE: async parsers report token byte offset one past the token
        // start (locked in by [core#531], see AsyncLocationTest), so the
        // 1-based column (9) is the exact indicator here
        byte[] trailing = utf8Bytes(" {\"b\":2}");
        feeder.feedInput(trailing, 0, trailing.length);
        feeder.endOfInput();
        StreamReadException e = assertThrows(StreamReadException.class,
                p::verifyNoTrailingTokens);
        verifyException(e, "Trailing token");
        assertEquals(1, e.getLocation().getLineNr());
        assertEquals(9, e.getLocation().getColumnNr());
        assertEquals(9, e.getLocation().getByteOffset());
        p.close();
    }

    // Feature integration: strict failure must not hand out the value;
    // default mode keeps consecutive-root-value capability

    @Test
    void readValueAsWithFeatureRejectsTrailing() throws Exception {
        // trailing whitespace only: value constructed and returned
        try (JsonParser p = STRICT_F.createParser(new TestReadContext(), "{\"a\":1} \n")) {
            assertNotNull(p.readValueAs(Object.class));
        }
        // second root value: strict failure, no half-success value returned
        try (JsonParser p = STRICT_F.createParser(new TestReadContext(), "{\"a\":1} {\"b\":2}")) {
            StreamReadException e = assertThrows(StreamReadException.class,
                    () -> p.readValueAs(Object.class));
            verifyException(e, "Trailing token");
            assertEquals(8, e.getLocation().getCharOffset());
        }
        // garbage: strict failure as well
        try (JsonParser p = STRICT_F.createParser(new TestReadContext(), "{\"a\":1} ???")) {
            assertThrows(StreamReadException.class, () -> p.readValueAs(Object.class));
        }
    }

    @Test
    void consecutiveRootValuesPreserved() throws Exception {
        // default (feature disabled): consecutive root values still readable
        for (int mode : ALL_MODES) {
            try (JsonParser p = createParser(JSON_F, mode, "{\"a\":1} {\"b\":2}")) {
                assertToken(JsonToken.START_OBJECT, p.nextToken());
                p.skipChildren();
                assertToken(JsonToken.START_OBJECT, p.nextToken());
                p.skipChildren();
                assertNull(p.nextToken());
            }
        }
        // and even with the feature enabled, plain token streaming is unaffected
        // (strict check only applies to readValueAs()/explicit verification)
        try (JsonParser p = STRICT_F.createParser(ObjectReadContext.empty(), "{} {}")) {
            assertToken(JsonToken.START_OBJECT, p.nextToken());
            p.skipChildren();
            assertToken(JsonToken.START_OBJECT, p.nextToken());
            p.skipChildren();
            assertNull(p.nextToken());
        }
    }
}
