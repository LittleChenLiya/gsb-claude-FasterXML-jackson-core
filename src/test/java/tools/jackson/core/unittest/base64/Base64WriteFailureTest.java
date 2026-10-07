package tools.jackson.core.unittest.base64;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import org.junit.jupiter.api.Test;

import tools.jackson.core.Base64Variants;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.unittest.JacksonCoreTestBase;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for verifying that failures to write decoded Base64 content into
 * a caller-provided {@link OutputStream} (see
 * {@link JsonParser#readBinaryValue(OutputStream)}) are reported with
 * proper context: token location and number of bytes written so far,
 * without swallowing the underlying {@link IOException}.
 * Also verifies that invalid Base64 content is still reported as a
 * parsing problem and that the borrowed stream is never closed.
 */
class Base64WriteFailureTest
    extends JacksonCoreTestBase
{
    private final JsonFactory JSON_F = newStreamFactory();

    // Decoded size (5000 bytes) exceeds the Base64 codec buffer (4000 bytes)
    // so that incremental decoding flushes twice: 3999 bytes, then 1001 bytes
    private final static int BINARY_LEN = 5000;
    private final static int EXPECTED_FIRST_FLUSH = 3999;

    static class FailingOutputStream extends OutputStream {
        /** 1-based number of write() call that should fail; negative = never */
        final int failOnWrite;

        int writeCalls;
        int closeCalls;
        final ByteArrayOutputStream received = new ByteArrayOutputStream();

        FailingOutputStream(int failOnWrite) {
            this.failOnWrite = failOnWrite;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] { (byte) b }, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (++writeCalls == failOnWrite) {
                throw new IOException("Simulated failure on write #" + writeCalls);
            }
            received.write(b, off, len);
        }

        @Override
        public void close() {
            ++closeCalls;
        }
    }

    @Test
    void incrementalWriteFailureAddsContext() throws Exception
    {
        for (int mode : ALL_MODES) {
            _incrementalWriteFailureAddsContext(mode);
        }
    }

    private void _incrementalWriteFailureAddsContext(int mode) throws Exception
    {
        byte[] data = _binary(BINARY_LEN);
        String doc = "{\"data\":\"" + Base64Variants.getDefaultVariant().encode(data) + "\"}";
        JsonParser p = createParser(JSON_F, mode, doc);
        assertToken(JsonToken.START_OBJECT, p.nextToken());
        assertToken(JsonToken.PROPERTY_NAME, p.nextToken());
        assertToken(JsonToken.VALUE_STRING, p.nextToken());
        TokenStreamLocation tokenLoc = p.currentTokenLocation();

        // First write (flush of full codec buffer) succeeds, second fails
        FailingOutputStream out = new FailingOutputStream(2);
        JacksonIOException e = assertThrows(JacksonIOException.class,
                () -> p.readBinaryValue(out));

        // Error category: I/O problem, not a parsing problem
        assertEquals(JacksonIOException.class, e.getClass());

        // Underlying IOException not swallowed
        assertTrue(e.getCause() instanceof IOException);
        assertEquals("Simulated failure on write #2", e.getCause().getMessage());

        // Context: number of bytes written before failure, in message
        assertEquals(2, out.writeCalls);
        assertEquals(EXPECTED_FIRST_FLUSH, out.received.size());
        String msg = e.getMessage();
        assertTrue(msg.contains("after " + EXPECTED_FIRST_FLUSH + " bytes"),
                "message should mention bytes written, got: " + msg);

        // Context: token location retained
        TokenStreamLocation loc = e.getLocation();
        assertNotNull(loc);
        assertEquals(tokenLoc.getLineNr(), loc.getLineNr());
        assertEquals(tokenLoc.getColumnNr(), loc.getColumnNr());
        assertEquals(tokenLoc.getCharOffset(), loc.getCharOffset());

        // Borrowed stream must not be closed, not by failure nor by parser close
        assertEquals(0, out.closeCalls);
        p.close();
        assertEquals(0, out.closeCalls);
    }

    @Test
    void nonIncrementalWriteFailureAddsContext() throws Exception
    {
        for (int mode : ALL_MODES) {
            _nonIncrementalWriteFailureAddsContext(mode);
        }
    }

    private void _nonIncrementalWriteFailureAddsContext(int mode) throws Exception
    {
        byte[] data = _binary(10);
        String doc = "[\"" + Base64Variants.getDefaultVariant().encode(data) + "\"]";
        JsonParser p = createParser(JSON_F, mode, doc);
        assertToken(JsonToken.START_ARRAY, p.nextToken());
        assertToken(JsonToken.VALUE_STRING, p.nextToken());
        TokenStreamLocation tokenLoc = p.currentTokenLocation();
        // Materialize binary value first to force non-incremental code path
        assertArrayEquals(data, p.getBinaryValue());

        FailingOutputStream out = new FailingOutputStream(1);
        JacksonIOException e = assertThrows(JacksonIOException.class,
                () -> p.readBinaryValue(out));

        assertEquals(JacksonIOException.class, e.getClass());
        assertTrue(e.getCause() instanceof IOException);
        assertEquals("Simulated failure on write #1", e.getCause().getMessage());
        String msg = e.getMessage();
        assertTrue(msg.contains("after 0 bytes"),
                "message should mention zero bytes written, got: " + msg);
        TokenStreamLocation loc = e.getLocation();
        assertNotNull(loc);
        assertEquals(tokenLoc.getLineNr(), loc.getLineNr());
        assertEquals(tokenLoc.getColumnNr(), loc.getColumnNr());
        assertEquals(0, out.closeCalls);
        p.close();
        assertEquals(0, out.closeCalls);
    }

    @Test
    void invalidBase64StillParseError() throws Exception
    {
        for (int mode : ALL_MODES) {
            _invalidBase64StillParseError(mode);
        }
    }

    private void _invalidBase64StillParseError(int mode) throws Exception
    {
        String doc = "{\"data\":\"AB!C\"}";
        JsonParser p = createParser(JSON_F, mode, doc);
        assertToken(JsonToken.START_OBJECT, p.nextToken());
        assertToken(JsonToken.PROPERTY_NAME, p.nextToken());
        assertToken(JsonToken.VALUE_STRING, p.nextToken());

        FailingOutputStream out = new FailingOutputStream(-1);
        StreamReadException e = assertThrows(StreamReadException.class,
                () -> p.readBinaryValue(out));
        String msg = e.getMessage();
        assertTrue(msg.contains("in base64 content"),
                "message should describe Base64 problem, got: " + msg);
        // Nothing was or should have been written; stream not closed
        assertEquals(0, out.writeCalls);
        assertEquals(0, out.closeCalls);
        p.close();
        assertEquals(0, out.closeCalls);
    }

    @Test
    void successfulWriteUnchanged() throws Exception
    {
        for (int mode : ALL_MODES) {
            _successfulWriteUnchanged(mode, 5);
            _successfulWriteUnchanged(mode, BINARY_LEN);
        }
    }

    private void _successfulWriteUnchanged(int mode, int len) throws Exception
    {
        byte[] data = _binary(len);
        String doc = "[\"" + Base64Variants.getDefaultVariant().encode(data) + "\"]";
        JsonParser p = createParser(JSON_F, mode, doc);
        assertToken(JsonToken.START_ARRAY, p.nextToken());
        assertToken(JsonToken.VALUE_STRING, p.nextToken());

        FailingOutputStream out = new FailingOutputStream(-1);
        int gotten = p.readBinaryValue(out);
        assertEquals(len, gotten);
        assertArrayEquals(data, out.received.toByteArray());
        assertEquals(0, out.closeCalls);
        assertToken(JsonToken.END_ARRAY, p.nextToken());
        p.close();
        assertEquals(0, out.closeCalls);
    }

    private byte[] _binary(int len) {
        byte[] data = new byte[len];
        for (int i = 0; i < len; i++) {
            data[i] = (byte) (i * 7 + 3);
        }
        return data;
    }
}
