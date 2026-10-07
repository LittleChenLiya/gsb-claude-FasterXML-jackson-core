package tools.jackson.core.unittest.base64;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Random;

import org.junit.jupiter.api.Test;

import tools.jackson.core.Base64Variants;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.core.exc.BinaryWriteException;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.unittest.JacksonCoreTestBase;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for contextual error reporting when writing of decoded Base64
 * content to caller-provided {@link OutputStream} fails during
 * {@link JsonParser#readBinaryValue(OutputStream)}: token location and
 * number of bytes written must be retained, the underlying
 * {@link IOException} must not be swallowed, and the borrowed stream
 * must never be closed. Invalid Base64 content must still be reported
 * as a parse error and the success path must remain unchanged.
 */
public class Base64WriteFailureTest
    extends JacksonCoreTestBase
{
    private final JsonFactory JSON_F = newStreamFactory();

    /**
     * In-memory fixture: {@link OutputStream} that fails on the Nth
     * (1-based) {@code write()} call, tracking successfully written
     * bytes and {@code close()} invocations.
     */
    public static class FailingOutputStream extends OutputStream {
        public final IOException failure = new IOException("mock write failure");
        public final int failOnWrite;
        public int writeCalls;
        public int closeCalls;
        public long bytesWritten;

        public FailingOutputStream(int failOnWrite) {
            this.failOnWrite = failOnWrite;
        }

        @Override
        public void write(int b) throws IOException { _write(1); }

        @Override
        public void write(byte[] b) throws IOException { _write(b.length); }

        @Override
        public void write(byte[] b, int off, int len) throws IOException { _write(len); }

        private void _write(int len) throws IOException {
            ++writeCalls;
            if (writeCalls >= failOnWrite) {
                throw failure;
            }
            bytesWritten += len;
        }

        @Override
        public void close() {
            ++closeCalls;
        }
    }

    // Small in-memory fixture: never-failing stream that counts close() calls
    static class CloseCountingStream extends ByteArrayOutputStream {
        int closeCalls;

        @Override
        public void close() {
            ++closeCalls;
        }
    }

    /*
    /**********************************************************************
    /* Test methods
    /**********************************************************************
     */

    // Second write to the target stream fails: context (token location,
    // bytes written so far) must be retained, cause not swallowed,
    // borrowed stream not closed
    @Test
    void secondWriteFailsRetainsContext() throws Exception
    {
        // big enough to require more than one write to the target stream
        // (decode buffer is 4000 bytes), small enough to stay in memory
        final byte[] data = _generateData(5000);
        final String doc = "{\"b\":\""
                + Base64Variants.getDefaultVariant().encode(data) + "\"}";

        for (int mode : ALL_MODES) {
            JsonParser p = createParser(JSON_F, mode, doc);
            assertToken(JsonToken.START_OBJECT, p.nextToken());
            assertToken(JsonToken.PROPERTY_NAME, p.nextToken());
            assertToken(JsonToken.VALUE_STRING, p.nextToken());
            final TokenStreamLocation tokenLoc = p.currentTokenLocation();

            FailingOutputStream out = new FailingOutputStream(2);
            BinaryWriteException e = assertThrows(BinaryWriteException.class,
                    () -> p.readBinaryValue(out),
                    "Expected BinaryWriteException for mode "+mode);

            // error category: JacksonIOException (and thereby JacksonException)
            assertTrue(e instanceof JacksonIOException);
            assertTrue(e instanceof JacksonException);
            // underlying IOException not swallowed
            assertSame(out.failure, e.getCause());
            // bytes-written count retained: exactly what the stream received
            assertEquals(out.bytesWritten, e.getBytesWritten());
            assertTrue(e.getBytesWritten() > 0);
            assertTrue(e.getBytesWritten() < data.length);
            // token location retained
            assertEquals(tokenLoc, e.getLocation());
            assertEquals(1, e.getLocation().getLineNr());
            // message should carry the context as well
            verifyException(e, "after "+out.bytesWritten+" bytes");
            verifyException(e, "mock write failure");
            // borrowed stream not closed, not even by parser close
            assertEquals(0, out.closeCalls);
            p.close();
            assertEquals(0, out.closeCalls);
        }
    }

    // First (and only) write fails: zero bytes written, both for the
    // incremental path and the already-materialized path
    @Test
    void firstWriteFailsRetainsZeroCount() throws Exception
    {
        final String doc = "{\"b\":\"QUJD\"}"; // "ABC"

        for (int mode : ALL_MODES) {
            // incremental decoding path
            JsonParser p = createParser(JSON_F, mode, doc);
            assertToken(JsonToken.START_OBJECT, p.nextToken());
            assertToken(JsonToken.PROPERTY_NAME, p.nextToken());
            assertToken(JsonToken.VALUE_STRING, p.nextToken());
            FailingOutputStream out = new FailingOutputStream(1);
            BinaryWriteException e = assertThrows(BinaryWriteException.class,
                    () -> p.readBinaryValue(out));
            assertSame(out.failure, e.getCause());
            assertEquals(0, e.getBytesWritten());
            assertEquals(0, out.closeCalls);
            p.close();
            assertEquals(0, out.closeCalls);

            // non-incremental path (token already materialized via getString())
            JsonParser p2 = createParser(JSON_F, mode, doc);
            assertToken(JsonToken.START_OBJECT, p2.nextToken());
            assertToken(JsonToken.PROPERTY_NAME, p2.nextToken());
            assertToken(JsonToken.VALUE_STRING, p2.nextToken());
            assertEquals("QUJD", p2.getString());
            final FailingOutputStream out2 = new FailingOutputStream(1);
            e = assertThrows(BinaryWriteException.class,
                    () -> p2.readBinaryValue(out2));
            assertSame(out2.failure, e.getCause());
            assertEquals(0, e.getBytesWritten());
            p2.close();
            assertEquals(0, out2.closeCalls);
        }
    }

    // Invalid Base64 must still be reported as a parse error
    // (StreamReadException), not as a write failure
    @Test
    void invalidBase64StillParseError() throws Exception
    {
        final String doc = "{\"b\":\"QUJ$\"}";

        for (int mode : ALL_MODES) {
            JsonParser p = createParser(JSON_F, mode, doc);
            assertToken(JsonToken.START_OBJECT, p.nextToken());
            assertToken(JsonToken.PROPERTY_NAME, p.nextToken());
            assertToken(JsonToken.VALUE_STRING, p.nextToken());
            FailingOutputStream out = new FailingOutputStream(1);
            StreamReadException e = assertThrows(StreamReadException.class,
                    () -> p.readBinaryValue(out));
            // parse error category, not an I/O write failure
            assertSame(StreamReadException.class, e.getClass());
            // failure detected before anything was written
            assertEquals(0, out.writeCalls);
            assertEquals(0, out.closeCalls);
            p.close();
            assertEquals(0, out.closeCalls);
        }
    }

    // Success path unchanged: decoded bytes written, count returned,
    // borrowed stream not closed
    @Test
    void successPathUnchanged() throws Exception
    {
        final String doc = "{\"b\":\"QUJD\"}";

        for (int mode : ALL_MODES) {
            JsonParser p = createParser(JSON_F, mode, doc);
            assertToken(JsonToken.START_OBJECT, p.nextToken());
            assertToken(JsonToken.PROPERTY_NAME, p.nextToken());
            assertToken(JsonToken.VALUE_STRING, p.nextToken());
            CloseCountingStream out = new CloseCountingStream();
            assertEquals(3, p.readBinaryValue(out));
            assertArrayEquals(new byte[] { 'A', 'B', 'C' }, out.toByteArray());
            assertEquals(0, out.closeCalls);
            p.close();
            assertEquals(0, out.closeCalls);
        }
    }

    /*
    /**********************************************************************
    /* Helper methods
    /**********************************************************************
     */

    private byte[] _generateData(int size) {
        byte[] data = new byte[size];
        new Random(12345L).nextBytes(data);
        return data;
    }
}
