package tools.jackson.core.unittest.util;

import org.junit.jupiter.api.Test;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.JsonTokenId;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.unittest.JacksonCoreTestBase;
import tools.jackson.core.util.TokenBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Targeted tests for the explicit life-cycle of
 * {@link TokenBuffer}: sealing, generation-bound parser views,
 * borrowed-context identity and view/buffer ownership separation.
 *<p>
 * Run with (targeted command):
 *<pre>
 *   ./mvnw test -Dtest=TokenBufferLifecycleTest
 *</pre>
 */
class TokenBufferLifecycleTest extends JacksonCoreTestBase
{
    /*
    /******************************************************************
    /* Seal: writes rejected after sealing
    /******************************************************************
     */

    @Test
    void writesRejectedAfterSeal() {
        TokenBuffer buf = new TokenBuffer();
        buf.writeStartObject();
        buf.writeName("a");
        buf.writeNumber(1);
        buf.writeEndObject();
        assertFalse(buf.isSealed());

        buf.seal();
        assertTrue(buf.isSealed());
        // seal() is idempotent
        buf.seal();

        // All write attempts must now be rejected
        assertThrows(IllegalStateException.class, () -> buf.writeNumber(2));
        assertThrows(IllegalStateException.class, () -> buf.writeString("x"));
        assertThrows(IllegalStateException.class, () -> buf.writeBoolean(true));
        assertThrows(IllegalStateException.class, () -> buf.writeNull());
        assertThrows(IllegalStateException.class, () -> buf.writeStartObject());
        assertThrows(IllegalStateException.class, () -> buf.writeStartArray());
        assertThrows(IllegalStateException.class, () -> buf.writeName("b"));
        assertThrows(IllegalStateException.class, () -> buf.writeEndObject());

        // ... but content is still intact and readable
        assertEquals(4, buf.tokenCount());
        assertEquals(JsonToken.START_OBJECT, buf.firstToken());
    }

    @Test
    void cannotSealUnbalancedContent() {
        TokenBuffer buf = new TokenBuffer();
        buf.writeStartObject();
        buf.writeName("a");
        IllegalStateException e = assertThrows(IllegalStateException.class, buf::seal);
        assertTrue(e.getMessage().contains("unclosed"), e.getMessage());
    }

    @Test
    void parserViewRequiresSealedBuffer() {
        TokenBuffer buf = new TokenBuffer();
        buf.writeBoolean(true);
        // Explicit life-cycle: no views before sealing
        assertThrows(IllegalStateException.class, buf::asParser);
        buf.seal();
        TokenBuffer.Parser p = buf.asParser();
        assertEquals(JsonToken.VALUE_TRUE, p.nextToken());
        assertNull(p.nextToken());
        p.close();
    }

    /*
    /******************************************************************
    /* Generations: reuse invalidates old views, new views work
    /******************************************************************
     */

    /**
     * Two-generation example: generation 0 view must not observe
     * generation 1 tokens after the buffer has been cleared and reused.
     */
    @Test
    void bufferReuseInvalidatesOldParserViews() {
        TokenBuffer buf = new TokenBuffer();
        final int gen0 = buf.generation();

        // -- Generation 0: write an Object, seal, create a view
        buf.writeStartObject();
        buf.writeName("gen");
        buf.writeNumber(0);
        buf.writeEndObject();
        buf.seal();

        TokenBuffer.Parser oldView = buf.asParser();
        assertEquals(gen0, oldView.boundGeneration());
        assertEquals(JsonToken.START_OBJECT, oldView.nextToken());
        assertEquals(JsonToken.PROPERTY_NAME, oldView.nextToken());
        assertEquals("gen", oldView.currentName());

        // -- Recycle buffer: generation advances, old view invalidated
        buf.clear();
        assertEquals(gen0 + 1, buf.generation());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                oldView::nextToken);
        assertTrue(e.getMessage().contains("Stale"), e.getMessage());
        // Value access on stale view must fail as well (no leaking old/new data)
        assertThrows(IllegalStateException.class, oldView::getString);
        assertThrows(IllegalStateException.class, oldView::currentName);

        // -- Generation 1: buffer writable again, new view works fine
        buf.writeStartArray();
        buf.writeString("gen-1");
        buf.writeNull();
        buf.writeEndArray();
        buf.seal();

        TokenBuffer.Parser newView = buf.asParser();
        assertEquals(gen0 + 1, newView.boundGeneration());
        assertEquals(JsonToken.START_ARRAY, newView.nextToken());
        assertEquals(JsonToken.VALUE_STRING, newView.nextToken());
        assertEquals("gen-1", newView.getString());
        assertEquals(JsonToken.VALUE_NULL, newView.nextToken());
        assertEquals(JsonToken.END_ARRAY, newView.nextToken());
        assertNull(newView.nextToken());
        newView.close();

        // -- Old view stays stale even though buffer is sealed/readable again
        assertThrows(IllegalStateException.class, oldView::nextToken);
        // ... but closing the stale view is always allowed
        oldView.close();
        assertTrue(oldView.isClosed());
    }

    /**
     * Second two-generation example, with scalar root values: verifies
     * generation tracking across multiple clear() cycles.
     */
    @Test
    void multipleGenerationsEachViewBoundToItsOwn() {
        TokenBuffer buf = new TokenBuffer();
        TokenBuffer.Parser[] views = new TokenBuffer.Parser[3];

        for (int gen = 0; gen < 3; ++gen) {
            assertEquals(gen, buf.generation());
            buf.writeNumber(100 + gen);
            buf.seal();
            views[gen] = buf.asParser();
            assertEquals(gen, views[gen].boundGeneration());
            // Each view reads the token written in its own generation
            assertEquals(JsonToken.VALUE_NUMBER_INT, views[gen].nextToken());
            assertEquals(100 + gen, views[gen].getIntValue());
            buf.clear();
        }
        assertEquals(3, buf.generation());

        // All earlier views are now stale and must not read newer tokens
        for (TokenBuffer.Parser view : views) {
            assertThrows(IllegalStateException.class, view::nextToken);
            view.close();
        }

        // And the buffer itself is fully reusable afterwards
        buf.writeBoolean(true);
        buf.seal();
        TokenBuffer.Parser p = buf.asParser();
        assertEquals(JsonToken.VALUE_TRUE, p.nextToken());
        p.close();
    }

    /*
    /******************************************************************
    /* Borrowed "object codec" (read context) identity
    /******************************************************************
     */

    @Test
    void borrowedReadContextIdentityRetained() {
        JsonFactory f = new JsonFactory();
        JsonParser src = f.createParser("[{\"a\":1},true]");
        ObjectReadContext borrowedCtxt = src.objectReadContext();
        StreamReadConstraints borrowedConstraints = src.streamReadConstraints();

        // Buffer borrows "codec" (read context) and constraints from source parser
        TokenBuffer buf = new TokenBuffer(src);
        assertSame(borrowedCtxt, buf.objectReadContext());

        // Buffer a balanced part of the content from the source parser
        src.nextToken(); // START_ARRAY: not buffered
        src.nextToken();
        buf.copyCurrentEvent(src); // START_OBJECT
        src.nextToken();
        buf.copyCurrentEvent(src); // PROPERTY_NAME "a"
        src.nextToken();
        buf.copyCurrentEvent(src); // VALUE_NUMBER_INT 1
        src.nextToken();
        buf.copyCurrentEvent(src); // END_OBJECT
        buf.seal();

        // Views see the very same borrowed instances (identity, not copies)
        TokenBuffer.Parser view = buf.asParser();
        assertSame(borrowedCtxt, view.objectReadContext());
        assertSame(borrowedConstraints, view.streamReadConstraints());

        // And replay works as expected
        assertEquals(JsonToken.START_OBJECT, view.nextToken());
        assertEquals(JsonToken.PROPERTY_NAME, view.nextToken());
        assertEquals("a", view.currentName());
        assertEquals(JsonToken.VALUE_NUMBER_INT, view.nextToken());
        assertEquals(1, view.getIntValue());
        assertEquals(JsonToken.END_OBJECT, view.nextToken());
        assertNull(view.nextToken());

        // Closing the view must not touch the borrowed context/parser:
        // the source parser remains open and fully functional.
        view.close();
        assertTrue(view.isClosed());
        assertFalse(src.isClosed());
        assertEquals(JsonToken.VALUE_TRUE, src.nextToken());
        assertEquals(JsonToken.END_ARRAY, src.nextToken());
        src.close();
    }

    @Test
    void explicitContextsIdentityRetained() {
        ObjectReadContext readCtxt = ObjectReadContext.empty();
        TokenBuffer buf = new TokenBuffer(readCtxt, ObjectWriteContext.empty());
        assertSame(readCtxt, buf.objectReadContext());
        buf.writeNumber(7);
        buf.seal();
        assertSame(readCtxt, buf.asParser().objectReadContext());
    }

    /*
    /******************************************************************
    /* View close vs buffer ownership
    /******************************************************************
     */

    @Test
    void viewCloseDoesNotDestroyBorrowedBuffer() {
        TokenBuffer buf = new TokenBuffer();
        buf.writeStartArray();
        buf.writeNumber(1);
        buf.writeNumber(2);
        buf.writeEndArray();
        buf.seal();

        TokenBuffer.Parser v1 = buf.asParser();
        assertEquals(JsonToken.START_ARRAY, v1.nextToken());
        v1.close();
        assertTrue(v1.isClosed());

        // Buffer content survives view close: same token count, new view
        // can be created and reads everything from the start
        assertEquals(4, buf.tokenCount());
        assertTrue(buf.isSealed());

        TokenBuffer.Parser v2 = buf.asParser();
        assertEquals(JsonToken.START_ARRAY, v2.nextToken());
        assertEquals(JsonToken.VALUE_NUMBER_INT, v2.nextToken());
        assertEquals(1, v2.getIntValue());
        assertEquals(JsonToken.VALUE_NUMBER_INT, v2.nextToken());
        assertEquals(2, v2.getIntValue());
        assertEquals(JsonToken.END_ARRAY, v2.nextToken());
        assertNull(v2.nextToken());
        v2.close();

        // Closed views are not readable
        assertThrows(IllegalStateException.class, v1::nextToken);
        assertThrows(IllegalStateException.class, v2::getString);
    }

    /**
     * Even if resource release fails during close, the view must end up
     * marked closed -- it must not remain readable.
     */
    @Test
    void resourceExitFailureDoesNotLeaveViewReadable() {
        TokenBuffer buf = new TokenBuffer();
        buf.writeNumber(42);
        buf.seal();

        TokenBuffer.Parser p = new FailingReleaseParser(buf);
        assertEquals(JsonToken.VALUE_NUMBER_INT, p.nextToken());
        assertEquals(42, p.getIntValue());

        IllegalStateException e = assertThrows(IllegalStateException.class, p::close);
        assertEquals("simulated release failure", e.getMessage());

        // Despite the release failure the view is closed and unreadable
        assertTrue(p.isClosed());
        assertThrows(IllegalStateException.class, p::nextToken);
        assertThrows(IllegalStateException.class, p::getString);

        // Buffer itself unaffected: another view still works
        TokenBuffer.Parser p2 = buf.asParser();
        assertEquals(JsonToken.VALUE_NUMBER_INT, p2.nextToken());
        p2.close();
    }

    // Parser view whose (normally no-op) buffer release fails: used to
    // verify close() error paths can not resurrect readability.
    static class FailingReleaseParser extends TokenBuffer.Parser {
        FailingReleaseParser(TokenBuffer buf) {
            super(buf, ObjectReadContext.empty(), StreamReadConstraints.defaults());
        }

        @Override
        protected void _releaseBuffers() {
            throw new IllegalStateException("simulated release failure");
        }
    }

    /*
    /******************************************************************
    /* null token vs empty buffer
    /******************************************************************
     */

    @Test
    void nullTokenIsNotEmptyBuffer() {
        // Empty buffer: no tokens at all
        TokenBuffer empty = new TokenBuffer();
        assertNull(empty.firstToken());
        assertEquals(0, empty.tokenCount());
        empty.seal();
        TokenBuffer.Parser ep = empty.asParser();
        assertNull(ep.nextToken());
        ep.close();

        // Buffer containing a single VALUE_NULL token: NOT empty
        TokenBuffer buf = new TokenBuffer();
        buf.writeNull();
        assertEquals(JsonToken.VALUE_NULL, buf.firstToken());
        assertEquals(1, buf.tokenCount());
        buf.seal();

        TokenBuffer.Parser p = buf.asParser();
        JsonToken t = p.nextToken();
        assertEquals(JsonToken.VALUE_NULL, t);
        assertTrue(p.hasToken(JsonToken.VALUE_NULL));
        assertTrue(p.hasTokenId(JsonTokenId.ID_NULL));
        // then the stream is exhausted
        assertNull(p.nextToken());
        p.close();
    }

    /*
    /******************************************************************
    /* Sanity: default token parser behavior unchanged
    /******************************************************************
     */

    @Test
    void defaultStreamingParserUnaffected() {
        JsonFactory f = new JsonFactory();
        JsonParser p = f.createParser("[1,2,3]");
        assertEquals(JsonToken.START_ARRAY, p.nextToken());
        assertEquals(JsonToken.VALUE_NUMBER_INT, p.nextToken());
        assertEquals(1, p.getIntValue());
        assertEquals(JsonToken.VALUE_NUMBER_INT, p.nextToken());
        assertEquals(JsonToken.VALUE_NUMBER_INT, p.nextToken());
        assertEquals(JsonToken.END_ARRAY, p.nextToken());
        assertNull(p.nextToken());
        p.close();
    }
}
