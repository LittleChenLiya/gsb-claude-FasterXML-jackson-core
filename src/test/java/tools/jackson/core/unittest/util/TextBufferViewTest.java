package tools.jackson.core.unittest.util;

import org.junit.jupiter.api.Test;

import tools.jackson.core.util.BufferRecycler;
import tools.jackson.core.util.TextBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the explicit token-buffer borrow life-cycle of
 * {@link TextBuffer}: sealing (read-only after {@code seal()}),
 * generation-bound parser {@link TextBuffer.View}s (buffer clear+reuse
 * invalidates old views) and ownership-aware view closing.
 *<p>
 * Targeted run:
 *<pre>
 *  ./mvnw test -Dtest=TextBufferViewTest
 *  ./mvnw test -Dtest=TextBufferViewTest#twoGenerationsOfViews
 *</pre>
 */
class TextBufferViewTest
    extends tools.jackson.core.unittest.JacksonCoreTestBase
{
    /*
    /******************************************************************
    /* Sealing: writes rejected, reads still work
    /******************************************************************
     */

    @Test
    void sealedBufferRejectsWrites() throws Exception
    {
        TextBuffer tb = new TextBuffer(new BufferRecycler());
        tb.append("abc", 0, 3);
        assertFalse(tb.isSealed());

        tb.seal();
        assertTrue(tb.isSealed());

        // Reads still fine after sealing
        assertEquals("abc", tb.contentsAsString());
        assertEquals(3, tb.size());

        // All content-mutating methods rejected
        assertThrows(IllegalStateException.class, () -> tb.append('x'));
        assertThrows(IllegalStateException.class, () -> tb.append("x", 0, 1));
        assertThrows(IllegalStateException.class, () -> tb.append(new char[] { 'x' }, 0, 1));
        assertThrows(IllegalStateException.class, () -> tb.resetWithEmpty());
        assertThrows(IllegalStateException.class, () -> tb.resetWith('x'));
        assertThrows(IllegalStateException.class, () -> tb.resetWithCopy("x", 0, 1));
        assertThrows(IllegalStateException.class, () -> tb.resetWithCopy(new char[] { 'x' }, 0, 1));
        assertThrows(IllegalStateException.class, () -> tb.resetWithShared(new char[] { 'x' }, 0, 1));
        assertThrows(IllegalStateException.class, () -> tb.resetWithString("x"));
        assertThrows(IllegalStateException.class, () -> tb.resetWithASCII(new byte[] { 0x41 }, 0, 1));
        assertThrows(IllegalStateException.class, () -> tb.resetWithUTF8(new byte[] { 0x41 }, 0, 1));
        assertThrows(IllegalStateException.class, () -> tb.ensureNotShared());
        assertThrows(IllegalStateException.class, () -> tb.getCurrentSegment());
        assertThrows(IllegalStateException.class, () -> tb.emptyAndGetCurrentSegment());
        assertThrows(IllegalStateException.class, () -> tb.setCurrentLength(0));
        assertThrows(IllegalStateException.class, () -> tb.setCurrentAndReturn(1));
        assertThrows(IllegalStateException.class, () -> tb.finishCurrentSegment());
        assertThrows(IllegalStateException.class, () -> tb.finishAndReturn(1, false));
        assertThrows(IllegalStateException.class, () -> tb.expandCurrentSegment());
        assertThrows(IllegalStateException.class, () -> tb.expandCurrentSegment(100));
        assertThrows(IllegalStateException.class, () -> tb.releaseBuffers());

        // And content must be untouched by all those rejected writes
        assertEquals("abc", tb.contentsAsString());
        assertEquals(3, tb.size());
    }

    @Test
    void sealingDoesNotInvalidateExistingViews() throws Exception
    {
        TextBuffer tb = new TextBuffer(null);
        tb.append("token", 0, 5);
        TextBuffer.View view = tb.view();
        tb.seal();
        // sealing is not a clear/reuse: generation unchanged, view still valid
        assertTrue(view.isValid());
        assertEquals("token", view.contentsAsString());
    }

    /*
    /******************************************************************
    /* Generations: two-generation example, old view cannot see new token
    /******************************************************************
     */

    /**
     * Two-generation example: a parser view created in generation 0 must not
     * observe the token buffered in generation 1 after clear+reuse; a view
     * created in generation 1 works normally.
     */
    @Test
    void twoGenerationsOfViews() throws Exception
    {
        TextBuffer tb = new TextBuffer(new BufferRecycler());

        // -- Generation 0: first token buffered
        tb.append("abc", 0, 3);
        final long gen0 = tb.generation();
        TextBuffer.View oldView = tb.view();
        assertEquals(gen0, oldView.generation());
        assertTrue(oldView.isValid());
        assertEquals("abc", oldView.contentsAsString());

        // -- Clear + reuse: buffer moves to generation 1, new token buffered
        tb.resetWithEmpty();
        tb.append("xyz", 0, 3);
        final long gen1 = tb.generation();
        assertNotEquals(gen0, gen1);

        // Old (generation 0) view is stale: must NOT read the new token
        assertFalse(oldView.isValid());
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> oldView.contentsAsString());
        assertTrue(e.getMessage().contains("stale"));
        assertThrows(IllegalStateException.class, () -> oldView.size());
        assertThrows(IllegalStateException.class, () -> oldView.contentsAsArray());
        assertThrows(IllegalStateException.class, () -> oldView.getTextBuffer());

        // New (generation 1) view works normally
        TextBuffer.View newView = tb.view();
        assertEquals(gen1, newView.generation());
        assertTrue(newView.isValid());
        assertEquals("xyz", newView.contentsAsString());
        assertEquals(3, newView.size());
    }

    @Test
    void anyResetAdvancesGeneration() throws Exception
    {
        TextBuffer tb = new TextBuffer(null);
        long gen = tb.generation();

        tb.resetWithEmpty();
        assertNotEquals(gen, tb.generation());
        gen = tb.generation();

        tb.resetWith('x');
        assertNotEquals(gen, tb.generation());
        gen = tb.generation();

        tb.resetWithString("abc");
        assertNotEquals(gen, tb.generation());
        gen = tb.generation();

        tb.resetWithShared(new char[] { 'a' }, 0, 1);
        assertNotEquals(gen, tb.generation());
        gen = tb.generation();

        tb.resetWithCopy("ab", 0, 2);
        assertNotEquals(gen, tb.generation());
        gen = tb.generation();

        tb.emptyAndGetCurrentSegment();
        assertNotEquals(gen, tb.generation());
    }

    /*
    /******************************************************************
    /* Ownership: borrowed vs owning views on close()
    /******************************************************************
     */

    @Test
    void borrowedViewCloseDoesNotDestroyBuffer() throws Exception
    {
        TextBuffer tb = new TextBuffer(new BufferRecycler());
        tb.append("keep", 0, 4);

        TextBuffer.View view = tb.view(); // borrowed
        assertFalse(view.ownsBuffer());
        assertEquals("keep", view.contentsAsString());

        view.close();
        assertTrue(view.isClosed());
        assertFalse(view.isValid());
        assertThrows(IllegalStateException.class, () -> view.contentsAsString());

        // Buffer was NOT destroyed: contents intact, still readable, not released
        assertEquals("keep", tb.contentsAsString());
        assertEquals(4, tb.size());
        assertNotNull(tb.getBufferWithoutReset());

        // Borrowed buffer identity preserved by the (now closed) view
        assertSame(tb, view.buffer());

        // And the buffer is still fully usable for new tokens
        tb.resetWithEmpty();
        tb.append("next", 0, 4);
        assertEquals("next", tb.contentsAsString());
    }

    @Test
    void owningViewCloseReleasesBuffer() throws Exception
    {
        TextBuffer tb = new TextBuffer(new BufferRecycler());
        tb.append("data", 0, 4);

        TextBuffer.View view = tb.owningView();
        assertTrue(view.ownsBuffer());
        assertEquals("data", view.contentsAsString());

        view.close();
        assertTrue(view.isClosed());
        assertFalse(view.isValid());
        assertThrows(IllegalStateException.class, () -> view.contentsAsString());

        // Owning close DID release the buffer resources
        assertNull(tb.getBufferWithoutReset());

        // Double close is a harmless no-op
        view.close();
        assertTrue(view.isClosed());
    }

    @Test
    void owningViewCloseWorksOnSealedBuffer() throws Exception
    {
        TextBuffer tb = new TextBuffer(new BufferRecycler());
        tb.append("sealed", 0, 6);
        tb.seal();

        TextBuffer.View view = tb.owningView();
        assertEquals("sealed", view.contentsAsString());
        // Resource ownership was transferred: close releases even though sealed
        view.close();
        assertTrue(view.isClosed());
        assertNull(tb.getBufferWithoutReset());
    }

    @Test
    void resourceExitErrorDoesNotLeaveViewReadable() throws Exception
    {
        // BufferRecycler that fails on resource exit (release)
        BufferRecycler failingRecycler = new BufferRecycler() {
            @Override
            public void releaseCharBuffer(int ix, char[] buffer) {
                throw new IllegalStateException("simulated resource exit failure");
            }
        };
        TextBuffer tb = new TextBuffer(failingRecycler);
        tb.append("boom", 0, 4);

        TextBuffer.View view = tb.owningView();
        assertTrue(view.isValid());

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> view.close());
        assertTrue(e.getMessage().contains("simulated resource exit failure"));

        // Even though close() failed, the view must NOT remain readable
        assertTrue(view.isClosed());
        assertFalse(view.isValid());
        assertThrows(IllegalStateException.class, () -> view.contentsAsString());
        assertThrows(IllegalStateException.class, () -> view.size());
    }

    /*
    /******************************************************************
    /* Null token is not an empty buffer
    /******************************************************************
     */

    @Test
    void nullTokenIsNotEmptyBuffer() throws Exception
    {
        // The JSON null token: real 4-character token text, NOT an empty buffer
        TextBuffer tb = new TextBuffer(null);
        tb.append("null", 0, 4);
        tb.seal();

        TextBuffer.View view = tb.view();
        assertFalse(view.isEmpty());
        assertEquals(4, view.size());
        assertEquals("null", view.contentsAsString());

        // Whereas a buffer with no content at all IS empty
        TextBuffer empty = new TextBuffer(null);
        empty.seal();
        TextBuffer.View emptyView = empty.view();
        assertTrue(emptyView.isEmpty());
        assertEquals(0, emptyView.size());
        assertEquals("", emptyView.contentsAsString());
    }
}
