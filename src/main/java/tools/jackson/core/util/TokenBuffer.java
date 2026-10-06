package tools.jackson.core.util;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;

import tools.jackson.core.*;
import tools.jackson.core.base.GeneratorBase;
import tools.jackson.core.base.ParserMinimalBase;
import tools.jackson.core.io.ContentReference;
import tools.jackson.core.io.IOContext;
import tools.jackson.core.json.PackageVersion;

/**
 * Utility class used for efficient buffering of {@link JsonToken}s and their
 * associated values, so that buffered content can later be replayed through
 * a {@link JsonParser} view (see {@link #asParser()}).
 *<p>
 * Unlike regular streaming parsers/generators, a {@code TokenBuffer} has an
 * explicit life-cycle:
 *<ol>
 *  <li><b>OPEN</b>: buffer accepts writes (initial state, and state after
 *    {@link #clear()}).
 *  <li><b>SEALED</b>: after a call to {@link #seal()} the buffer becomes
 *    read-only: all write attempts are rejected with
 *    {@link IllegalStateException}, and {@link JsonParser} views may be
 *    created with {@link #asParser()}.
 *  <li><b>REUSED</b>: {@link #clear()} discards buffered content, re-opens
 *    the buffer for writing and advances the buffer <i>generation</i>.
 *    Parser views are bound to the generation of the buffer at the time of
 *    their creation: once the buffer has been cleared and reused, stale
 *    views are invalidated and will throw {@link IllegalStateException}
 *    instead of accidentally reading tokens of the new generation.
 *</ol>
 *<p>
 * Ownership notes:
 *<ul>
 *  <li>{@link ObjectReadContext} / {@link ObjectWriteContext} (the 3.x
 *    "object codec" replacement) passed to constructors -- or borrowed from
 *    a source {@link JsonParser} via {@link #TokenBuffer(JsonParser)} --
 *    are used by-reference: their identity is retained and they are never
 *    closed or otherwise mutated by this buffer or its views.
 *  <li>{@link Parser} views do <b>not</b> own the buffer: closing a view
 *    never destroys, clears or otherwise invalidates buffered content, and
 *    other views remain usable. Conversely, closing a view is terminal for
 *    the view itself: even if resource-release fails during close, the view
 *    is marked closed and will not remain readable.
 *</ul>
 *<p>
 * Note on {@code null} tokens: {@link JsonToken#VALUE_NULL} written via
 * {@link #writeNull()} is a real buffered token and must not be confused
 * with an empty buffer: {@link #firstToken()} returns {@code null} only
 * when no tokens have been buffered.
 *<p>
 * Existing streaming parsers are not affected by this class in any way.
 */
public class TokenBuffer
    extends GeneratorBase
{
    /**
     * Read context ("object codec") to use for parser views; either
     * explicitly provided or borrowed from a source parser. Identity
     * is retained; never closed or mutated by this buffer.
     */
    protected final ObjectReadContext _objectReadContext;

    /**
     * Constraints to apply to parser views; typically borrowed from the
     * parser content was read from (so that they are retained for replay).
     */
    protected final StreamReadConstraints _streamReadConstraints;

    /**
     * Write-side context used for validating balanced content.
     */
    protected SimpleStreamWriteContext _writeContext;

    /**
     * Buffered tokens, parallel to {@link #_values}.
     */
    protected final ArrayList<JsonToken> _tokens = new ArrayList<>();

    /**
     * Buffered values (name for {@link JsonToken#PROPERTY_NAME},
     * {@link String} for {@link JsonToken#VALUE_STRING},
     * {@link Number} for numbers, {@link Boolean} for booleans,
     * embedded {@code Object} for {@link JsonToken#VALUE_EMBEDDED_OBJECT});
     * {@code null} for tokens without associated value.
     */
    protected final ArrayList<Object> _values = new ArrayList<>();

    /**
     * Whether this buffer has been sealed (read-only phase).
     */
    protected boolean _sealed;

    /**
     * Generation of this buffer; starts at 0 and is incremented every
     * time {@link #clear()} recycles the buffer for reuse. Parser views
     * capture the generation at construction and refuse to operate once
     * the buffer has moved to a newer generation.
     */
    protected int _generation;

    /*
    /******************************************************************
    /* Life-cycle
    /******************************************************************
     */

    /**
     * Default constructor: uses {@link ObjectReadContext#empty()} and
     * {@link ObjectWriteContext#empty()} contexts and default constraints.
     */
    public TokenBuffer() {
        this(ObjectReadContext.empty(), ObjectWriteContext.empty());
    }

    /**
     * Constructor that borrows the {@link ObjectReadContext} and
     * {@link StreamReadConstraints} from given parser: identity of both
     * is retained so that buffered content is replayed under the same
     * "codec" and constraints as the content was read with.
     *
     * @param src Parser to borrow read context and constraints from
     */
    public TokenBuffer(JsonParser src) {
        this(src.objectReadContext(), ObjectWriteContext.empty(),
                src.streamReadConstraints());
    }

    public TokenBuffer(ObjectReadContext readCtxt, ObjectWriteContext writeCtxt) {
        this(readCtxt, writeCtxt, StreamReadConstraints.defaults());
    }

    public TokenBuffer(ObjectReadContext readCtxt, ObjectWriteContext writeCtxt,
            StreamReadConstraints readConstraints) {
        super(writeCtxt, _dummyIOContext(), StreamWriteFeature.collectDefaults());
        _objectReadContext = readCtxt;
        _streamReadConstraints = readConstraints;
        _writeContext = SimpleStreamWriteContext.createRootContext(null);
    }

    // Buffer is fully in-memory: no I/O resources to manage; mark recycler
    // as externally managed so IOContext.close() will not try to release it.
    private static IOContext _dummyIOContext() {
        return new IOContext(StreamReadConstraints.defaults(),
                StreamWriteConstraints.defaults(),
                ErrorReportConfiguration.defaults(),
                null, ContentReference.unknown(), false, null)
                .markBufferRecyclerReleased();
    }

    /*
    /******************************************************************
    /* Explicit life-cycle: seal / clear / generations
    /******************************************************************
     */

    /**
     * @return Whether this buffer has been sealed (and is now read-only)
     */
    public boolean isSealed() {
        return _sealed;
    }

    /**
     * @return Current generation of this buffer; incremented by {@link #clear()}
     */
    public int generation() {
        return _generation;
    }

    /**
     * Seals this buffer: after sealing, all write attempts fail with
     * {@link IllegalStateException} and parser views may be created with
     * {@link #asParser()}. Idempotent.
     *
     * @return This buffer, for chaining
     *
     * @throws IllegalStateException if buffered content is unbalanced
     *   (unclosed Object or Array context)
     */
    public TokenBuffer seal() {
        if (!_sealed) {
            if (!_writeContext.inRoot()) {
                throw new IllegalStateException(String.format(
                        "Can not seal TokenBuffer: unclosed %s context (content not balanced)",
                        _writeContext.typeDesc()));
            }
            _sealed = true;
        }
        return this;
    }

    /**
     * Discards all buffered content and re-opens this buffer for writing,
     * advancing the buffer generation. All existing parser views (bound to
     * the previous generation) are invalidated by this call: they will fail
     * with {@link IllegalStateException} rather than observe tokens written
     * after reuse.
     */
    public void clear() {
        _tokens.clear();
        _values.clear();
        _writeContext = SimpleStreamWriteContext.createRootContext(null);
        _sealed = false;
        _closed = false; // re-open write side (GeneratorBase._closed)
        ++_generation;
    }

    /**
     * @return Number of tokens currently buffered
     */
    public int tokenCount() {
        return _tokens.size();
    }

    /**
     * Accessor for the first buffered token, if any. Note that
     * {@link JsonToken#VALUE_NULL} is a real token: {@code null} is
     * returned only when the buffer contains no tokens at all.
     *
     * @return First buffered token, or {@code null} if buffer is empty
     */
    public JsonToken firstToken() {
        return _tokens.isEmpty() ? null : _tokens.get(0);
    }

    /**
     * @return The (possibly borrowed) read context parser views are created with
     */
    public ObjectReadContext objectReadContext() {
        return _objectReadContext;
    }

    /**
     * Factory method for creating a {@link JsonParser} view over the sealed
     * contents of this buffer. The view borrows (does not own) the buffered
     * content and is bound to the current {@linkplain #generation()
     * generation} of this buffer.
     *
     * @return Parser view over current buffer contents
     *
     * @throws IllegalStateException if buffer has not been sealed yet
     */
    public Parser asParser() {
        if (!_sealed) {
            throw new IllegalStateException(
                    "TokenBuffer not sealed: must call seal() before creating parser views");
        }
        return new Parser(this, _objectReadContext, _streamReadConstraints);
    }

    /*
    /******************************************************************
    /* JsonGenerator (GeneratorBase) implementation: config
    /******************************************************************
     */

    @Override
    public Version version() {
        return PackageVersion.VERSION;
    }

    @Override
    public TokenStreamContext streamWriteContext() {
        return _writeContext;
    }

    @Override
    public Object streamWriteOutputTarget() {
        return this;
    }

    @Override
    public int streamWriteOutputBuffered() {
        return -1;
    }

    @Override
    public Object currentValue() {
        return _writeContext.currentValue();
    }

    @Override
    public void assignCurrentValue(Object v) {
        _writeContext.assignCurrentValue(v);
    }

    @Override
    public JacksonFeatureSet<StreamWriteCapability> streamWriteCapabilities() {
        return DEFAULT_WRITE_CAPABILITIES;
    }

    /*
    /******************************************************************
    /* JsonGenerator implementation: structural tokens
    /******************************************************************
     */

    @Override
    public JsonGenerator writeStartArray() throws JacksonException {
        _verifyValueWrite("start an array");
        _writeContext = _writeContext.createChildArrayContext(null);
        _append(JsonToken.START_ARRAY);
        return this;
    }

    @Override
    public JsonGenerator writeStartArray(Object currentValue) throws JacksonException {
        _verifyValueWrite("start an array");
        _writeContext = _writeContext.createChildArrayContext(currentValue);
        _append(JsonToken.START_ARRAY);
        return this;
    }

    @Override
    public JsonGenerator writeEndArray() throws JacksonException {
        _verifyWritable();
        if (!_writeContext.inArray()) {
            _reportError("Current context not Array but " + _writeContext.typeDesc());
        }
        _append(JsonToken.END_ARRAY);
        SimpleStreamWriteContext parent = _writeContext.clearAndGetParent();
        if (parent != null) {
            _writeContext = parent;
        }
        return this;
    }

    @Override
    public JsonGenerator writeStartObject() throws JacksonException {
        _verifyValueWrite("start an object");
        _writeContext = _writeContext.createChildObjectContext(null);
        _append(JsonToken.START_OBJECT);
        return this;
    }

    @Override
    public JsonGenerator writeStartObject(Object currentValue) throws JacksonException {
        _verifyValueWrite("start an object");
        _writeContext = _writeContext.createChildObjectContext(currentValue);
        _append(JsonToken.START_OBJECT);
        return this;
    }

    @Override
    public JsonGenerator writeEndObject() throws JacksonException {
        _verifyWritable();
        if (!_writeContext.inObject()) {
            _reportError("Current context not Object but " + _writeContext.typeDesc());
        }
        _append(JsonToken.END_OBJECT);
        SimpleStreamWriteContext parent = _writeContext.clearAndGetParent();
        if (parent != null) {
            _writeContext = parent;
        }
        return this;
    }

    @Override
    public JsonGenerator writeName(String name) throws JacksonException {
        _verifyWritable();
        if (!_writeContext.writeName(name)) {
            _reportError("Can not write a property name, expecting a value");
        }
        _append(JsonToken.PROPERTY_NAME, name);
        return this;
    }

    @Override
    public JsonGenerator writePropertyId(long id) throws JacksonException {
        throw new UnsupportedOperationException(
                "TokenBuffer does not support native property ids");
    }

    /*
    /******************************************************************
    /* JsonGenerator implementation: scalar values
    /******************************************************************
     */

    @Override
    public JsonGenerator writeString(String value) throws JacksonException {
        if (value == null) {
            writeNull();
        } else {
            _verifyValueWrite("write a string");
            _append(JsonToken.VALUE_STRING, value);
        }
        return this;
    }

    @Override
    public JsonGenerator writeString(char[] buffer, int offset, int len) throws JacksonException {
        return writeString(new String(buffer, offset, len));
    }

    @Override
    public JsonGenerator writeRawUTF8String(byte[] buffer, int offset, int len) {
        return writeString(new String(buffer, offset, len,
                java.nio.charset.StandardCharsets.UTF_8));
    }

    @Override
    public JsonGenerator writeUTF8String(byte[] buffer, int offset, int len) {
        return writeString(new String(buffer, offset, len,
                java.nio.charset.StandardCharsets.UTF_8));
    }

    @Override
    public JsonGenerator writeNumber(short v) throws JacksonException {
        _verifyValueWrite("write a number");
        _append(JsonToken.VALUE_NUMBER_INT, Short.valueOf(v));
        return this;
    }

    @Override
    public JsonGenerator writeNumber(int v) throws JacksonException {
        _verifyValueWrite("write a number");
        _append(JsonToken.VALUE_NUMBER_INT, Integer.valueOf(v));
        return this;
    }

    @Override
    public JsonGenerator writeNumber(long v) throws JacksonException {
        _verifyValueWrite("write a number");
        _append(JsonToken.VALUE_NUMBER_INT, Long.valueOf(v));
        return this;
    }

    @Override
    public JsonGenerator writeNumber(BigInteger v) throws JacksonException {
        if (v == null) {
            writeNull();
        } else {
            _verifyValueWrite("write a number");
            _append(JsonToken.VALUE_NUMBER_INT, v);
        }
        return this;
    }

    @Override
    public JsonGenerator writeNumber(double v) throws JacksonException {
        _verifyValueWrite("write a number");
        _append(JsonToken.VALUE_NUMBER_FLOAT, Double.valueOf(v));
        return this;
    }

    @Override
    public JsonGenerator writeNumber(float v) throws JacksonException {
        _verifyValueWrite("write a number");
        _append(JsonToken.VALUE_NUMBER_FLOAT, Float.valueOf(v));
        return this;
    }

    @Override
    public JsonGenerator writeNumber(BigDecimal v) throws JacksonException {
        if (v == null) {
            writeNull();
        } else {
            _verifyValueWrite("write a number");
            _append(JsonToken.VALUE_NUMBER_FLOAT, v);
        }
        return this;
    }

    @Override
    public JsonGenerator writeNumber(String encodedValue) throws JacksonException {
        if (encodedValue == null) {
            writeNull();
        } else {
            _verifyValueWrite("write a number");
            try {
                _append(JsonToken.VALUE_NUMBER_INT, Long.valueOf(encodedValue));
            } catch (NumberFormatException e) {
                _append(JsonToken.VALUE_NUMBER_FLOAT, new BigDecimal(encodedValue));
            }
        }
        return this;
    }

    @Override
    public JsonGenerator writeBoolean(boolean state) throws JacksonException {
        _verifyValueWrite("write a boolean value");
        _append(state ? JsonToken.VALUE_TRUE : JsonToken.VALUE_FALSE,
                Boolean.valueOf(state));
        return this;
    }

    /**
     * Writes a {@link JsonToken#VALUE_NULL} token. Note that this is a real
     * buffered token: a buffer containing only a {@code null} token is
     * <b>not</b> empty (see {@link #firstToken()}).
     */
    @Override
    public JsonGenerator writeNull() throws JacksonException {
        _verifyValueWrite("write a null");
        _append(JsonToken.VALUE_NULL);
        return this;
    }

    @Override
    public JsonGenerator writeBinary(Base64Variant b64variant,
            byte[] data, int offset, int len) throws JacksonException {
        _verifyValueWrite("write a binary value");
        _append(JsonToken.VALUE_EMBEDDED_OBJECT,
                Arrays.copyOfRange(data, offset, offset + len));
        return this;
    }

    /**
     * For POJO values: if a real (non-empty) {@link ObjectWriteContext} is
     * available, serialization is delegated to it; otherwise the value is
     * buffered as an embedded object.
     */
    @Override
    public JsonGenerator writePOJO(Object value) throws JacksonException {
        if (value == null) {
            writeNull();
        } else if (_objectWriteContext == ObjectWriteContext.empty()) {
            _verifyValueWrite("write an embedded object");
            _append(JsonToken.VALUE_EMBEDDED_OBJECT, value);
        } else {
            super.writePOJO(value);
        }
        return this;
    }

    @Override
    public JsonGenerator writeTree(TreeNode rootNode) throws JacksonException {
        if (rootNode == null) {
            writeNull();
        } else if (_objectWriteContext == ObjectWriteContext.empty()) {
            _verifyValueWrite("write an embedded object");
            _append(JsonToken.VALUE_EMBEDDED_OBJECT, rootNode);
        } else {
            super.writeTree(rootNode);
        }
        return this;
    }

    /*
    /******************************************************************
    /* JsonGenerator implementation: raw output unsupported
    /******************************************************************
     */

    @Override
    public JsonGenerator writeRaw(String text) {
        throw new UnsupportedOperationException("Raw output not supported by TokenBuffer");
    }

    @Override
    public JsonGenerator writeRaw(String text, int offset, int len) {
        throw new UnsupportedOperationException("Raw output not supported by TokenBuffer");
    }

    @Override
    public JsonGenerator writeRaw(char[] buffer, int offset, int len) {
        throw new UnsupportedOperationException("Raw output not supported by TokenBuffer");
    }

    @Override
    public JsonGenerator writeRaw(char c) {
        throw new UnsupportedOperationException("Raw output not supported by TokenBuffer");
    }

    /*
    /******************************************************************
    /* JsonGenerator implementation: low-level life-cycle
    /******************************************************************
     */

    @Override
    public void flush() {
        // No underlying stream: nothing to flush
    }

    /**
     * Closing the write side of the buffer seals it (making buffered
     * content available for reading through {@link #asParser()}); buffered
     * content itself is retained.
     */
    @Override
    public void close() {
        seal();
        super.close();
    }

    @Override
    protected void _closeInput() {
        // Nothing to close: buffer is fully in-memory
    }

    /**
     * Note: buffered tokens are intentionally <b>not</b> released here:
     * they constitute the readable content of this buffer (to be consumed
     * via parser views) and are only discarded by {@link #clear()} or
     * by dropping the buffer itself.
     */
    @Override
    protected void _releaseBuffers() {
        // Nothing to release: see javadoc
    }

    @Override
    protected void _verifyValueWrite(String typeMsg) throws JacksonException {
        _verifyWritable();
        if (!_writeContext.writeValue()) {
            _reportError("Can not " + typeMsg + ", expecting a property name");
        }
    }

    /*
    /******************************************************************
    /* Internal helpers
    /******************************************************************
     */

    /**
     * Life-cycle check for write operations: writes are only allowed while
     * the buffer is open (neither sealed nor closed).
     */
    protected final void _verifyWritable() {
        if (_sealed) {
            throw new IllegalStateException(
                    "TokenBuffer has been sealed: writes no longer allowed"
                    + " (call clear() to discard content and reuse the buffer)");
        }
        if (_closed) {
            throw new IllegalStateException("TokenBuffer has been closed");
        }
    }

    protected final void _append(JsonToken token) {
        _tokens.add(token);
        _values.add(null);
    }

    protected final void _append(JsonToken token, Object value) {
        _tokens.add(token);
        _values.add(value);
    }

    /*
    /******************************************************************
    /* Parser view implementation
    /******************************************************************
     */

    /**
     * {@link JsonParser} implementation that exposes sealed buffer contents
     * as a token stream. Views borrow (never own) the underlying buffer:
     * closing a view does not destroy buffered content. Each view is bound
     * to the {@linkplain TokenBuffer#generation() generation} of the buffer
     * at creation time; once the buffer is recycled via
     * {@link TokenBuffer#clear()}, the view is stale and refuses further
     * access with {@link IllegalStateException}.
     */
    public static class Parser
        extends ParserMinimalBase
    {
        /**
         * Buffer this view reads from; borrowed, never owned: not cleared,
         * released or otherwise invalidated by this view.
         */
        protected final TokenBuffer _buffer;

        /**
         * Buffer generation this view is bound to.
         */
        protected final int _generation;

        protected SimpleStreamReadContext _readContext;

        /**
         * Index of the current token within the buffer; -1 before the
         * first token.
         */
        protected int _ptr = -1;

        protected Parser(TokenBuffer buffer, ObjectReadContext readCtxt,
                StreamReadConstraints src) {
            super(readCtxt, src);
            _buffer = buffer;
            _generation = buffer._generation;
            _readContext = SimpleStreamReadContext.createRootContext(null);
        }

        /**
         * @return Buffer generation this view is bound to
         */
        public int boundGeneration() {
            return _generation;
        }

        /**
         * Life-cycle guard for read operations: a view must not be used
         * after it has been closed, nor after its buffer has been recycled
         * (generation advanced by {@link TokenBuffer#clear()}).
         */
        protected final void _checkReadable() {
            if (_closed) {
                throw new IllegalStateException(
                        "TokenBuffer.Parser view has been closed");
            }
            if (_buffer._generation != _generation) {
                throw new IllegalStateException(String.format(
                        "Stale TokenBuffer.Parser view: bound to buffer generation %d"
                        + " but buffer is now at generation %d"
                        + " (buffer was cleared and reused)",
                        _generation, _buffer._generation));
            }
        }

        protected final Object _currentObject() {
            return (_ptr < 0) ? null : _buffer._values.get(_ptr);
        }

        /*
        /**********************************************************
        /* JsonParser implementation: basic state
        /**********************************************************
         */

        @Override
        public Version version() {
            return PackageVersion.VERSION;
        }

        @Override
        public TokenStreamContext streamReadContext() {
            return _readContext;
        }

        @Override
        public TokenStreamLocation currentTokenLocation() {
            return TokenStreamLocation.NA;
        }

        @Override
        public TokenStreamLocation currentLocation() {
            return TokenStreamLocation.NA;
        }

        @Override
        public Object streamReadInputSource() {
            return _buffer;
        }

        @Override
        public Object currentValue() {
            return _readContext.currentValue();
        }

        @Override
        public void assignCurrentValue(Object v) {
            _readContext.assignCurrentValue(v);
        }

        /*
        /**********************************************************
        /* JsonParser implementation: token iteration
        /**********************************************************
         */

        @Override
        public JsonToken nextToken() throws JacksonException {
            _checkReadable();
            int ptr = _ptr + 1;
            if (ptr >= _buffer._tokens.size()) {
                _ptr = _buffer._tokens.size();
                return (_currToken = null);
            }
            _ptr = ptr;
            ++_tokenCount;
            if (_trackMaxTokenCount) {
                _streamReadConstraints.validateTokenCount(_tokenCount);
            }
            JsonToken t = _buffer._tokens.get(ptr);
            _currToken = t;
            switch (t) {
            case START_ARRAY:
                _readContext = _readContext.createChildArrayContext(-1, -1);
                break;
            case START_OBJECT:
                _readContext = _readContext.createChildObjectContext(-1, -1);
                break;
            case END_ARRAY:
            case END_OBJECT: {
                SimpleStreamReadContext parent = _readContext.clearAndGetParent();
                if (parent != null) {
                    _readContext = parent;
                }
                break;
            }
            case PROPERTY_NAME:
                _readContext.setCurrentName((String) _buffer._values.get(ptr));
                break;
            default:
                _readContext.valueRead();
            }
            return t;
        }

        @Override
        public void finishToken() {
            // No deferred decoding: nothing to finish
        }

        @Override
        public JsonToken currentToken() {
            return _currToken;
        }

        @Override
        public int currentTokenId() {
            JsonToken t = _currToken;
            return (t == null) ? JsonTokenId.ID_NO_TOKEN : t.id();
        }

        @Override
        public boolean hasCurrentToken() {
            return _currToken != null;
        }

        @Override
        public boolean hasTokenId(int id) {
            JsonToken t = _currToken;
            return (t != null) && (t.id() == id);
        }

        @Override
        public boolean isExpectedStartArrayToken() {
            return _currToken == JsonToken.START_ARRAY;
        }

        @Override
        public boolean isExpectedStartObjectToken() {
            return _currToken == JsonToken.START_OBJECT;
        }

        @Override
        public boolean isExpectedNumberIntToken() {
            return _currToken == JsonToken.VALUE_NUMBER_INT;
        }

        @Override
        public boolean isNaN() {
            if (_currToken == JsonToken.VALUE_NUMBER_FLOAT) {
                Object v = _currentObject();
                if (v instanceof Double d) {
                    return d.isNaN();
                }
                if (v instanceof Float f) {
                    return f.isNaN();
                }
            }
            return false;
        }

        @Override
        public void clearCurrentToken() {
            _lastClearedToken = _currToken;
            _currToken = null;
        }

        @Override
        public JsonToken getLastClearedToken() {
            return _lastClearedToken;
        }

        @Override
        public String currentName() {
            _checkReadable();
            if (_currToken == JsonToken.PROPERTY_NAME) {
                Object v = _currentObject();
                return (v == null) ? null : v.toString();
            }
            return _readContext.currentName();
        }

        /*
        /**********************************************************
        /* JsonParser implementation: textual access
        /**********************************************************
         */

        @Override
        public String getString() {
            _checkReadable();
            JsonToken t = _currToken;
            if (t == null) {
                return null;
            }
            switch (t) {
            case PROPERTY_NAME:
            case VALUE_STRING:
            case VALUE_NUMBER_INT:
            case VALUE_NUMBER_FLOAT:
            case VALUE_TRUE:
            case VALUE_FALSE: {
                Object v = _currentObject();
                return (v == null) ? null : v.toString();
            }
            case VALUE_NULL:
                return null;
            default:
                return t.asString();
            }
        }

        @Override
        public char[] getStringCharacters() {
            String str = getString();
            return (str == null) ? null : str.toCharArray();
        }

        @Override
        public int getStringLength() {
            String str = getString();
            return (str == null) ? 0 : str.length();
        }

        @Override
        public int getStringOffset() {
            return 0;
        }

        @Override
        public boolean hasStringCharacters() {
            // We have no shared/accessible char[] to expose
            return false;
        }

        /*
        /**********************************************************
        /* JsonParser implementation: numeric access
        /**********************************************************
         */

        @Override
        public Number getNumberValue() {
            _checkReadable();
            Object v = _currentObject();
            if (v instanceof Number n) {
                return n;
            }
            if (v instanceof String s) {
                if (_currToken == JsonToken.VALUE_NUMBER_INT) {
                    return new BigInteger(s);
                }
                return new BigDecimal(s);
            }
            throw new IllegalStateException(String.format(
                    "Current token (%s) not numeric, can not use numeric value accessors",
                    _currToken));
        }

        @Override
        public NumberType getNumberType() {
            Number n = getNumberValue();
            if (n instanceof Integer || n instanceof Short || n instanceof Byte) {
                return NumberType.INT;
            }
            if (n instanceof Long) {
                return NumberType.LONG;
            }
            if (n instanceof BigInteger) {
                return NumberType.BIG_INTEGER;
            }
            if (n instanceof Float) {
                return NumberType.FLOAT;
            }
            if (n instanceof BigDecimal) {
                return NumberType.BIG_DECIMAL;
            }
            return NumberType.DOUBLE;
        }

        @Override
        public int getIntValue() {
            Number n = getNumberValue();
            if (n instanceof Integer || n instanceof Short || n instanceof Byte) {
                return n.intValue();
            }
            if (n instanceof Long l) {
                if (l < MIN_INT_L || l > MAX_INT_L) {
                    _reportOverflowInt(n.toString());
                }
                return n.intValue();
            }
            if (n instanceof BigInteger bi) {
                if (BI_MIN_INT.compareTo(bi) > 0 || BI_MAX_INT.compareTo(bi) < 0) {
                    _reportOverflowInt(n.toString());
                }
                return n.intValue();
            }
            return n.intValue();
        }

        @Override
        public long getLongValue() {
            Number n = getNumberValue();
            if (n instanceof Integer || n instanceof Short || n instanceof Byte
                    || n instanceof Long) {
                return n.longValue();
            }
            if (n instanceof BigInteger bi) {
                if (BI_MIN_LONG.compareTo(bi) > 0 || BI_MAX_LONG.compareTo(bi) < 0) {
                    _reportOverflowLong(n.toString());
                }
                return n.longValue();
            }
            return n.longValue();
        }

        @Override
        public BigInteger getBigIntegerValue() {
            Number n = getNumberValue();
            if (n instanceof BigInteger bi) {
                return bi;
            }
            if (n instanceof BigDecimal bd) {
                return bd.toBigInteger();
            }
            return BigInteger.valueOf(n.longValue());
        }

        @Override
        public float getFloatValue() {
            return getNumberValue().floatValue();
        }

        @Override
        public double getDoubleValue() {
            return getNumberValue().doubleValue();
        }

        @Override
        public BigDecimal getDecimalValue() {
            Number n = getNumberValue();
            if (n instanceof BigDecimal bd) {
                return bd;
            }
            if (n instanceof Double || n instanceof Float) {
                return BigDecimal.valueOf(n.doubleValue());
            }
            if (n instanceof BigInteger bi) {
                return new BigDecimal(bi);
            }
            return BigDecimal.valueOf(n.longValue());
        }

        /*
        /**********************************************************
        /* JsonParser implementation: other value access
        /**********************************************************
         */

        @Override
        public byte[] getBinaryValue(Base64Variant b64variant) throws JacksonException {
            _checkReadable();
            Object v = _currentObject();
            if (v == null) {
                return null;
            }
            if (v instanceof byte[] b) {
                return b;
            }
            if (v instanceof String s) {
                ByteArrayBuilder builder = new ByteArrayBuilder(100);
                _decodeBase64(s, builder, b64variant);
                return builder.toByteArray();
            }
            return _reportError(String.format(
                    "Current token (%s) value not of type byte[] or String:"
                    + " can not access as binary",
                    _currToken));
        }

        @Override
        public Object getEmbeddedObject() {
            _checkReadable();
            if (_currToken == JsonToken.VALUE_EMBEDDED_OBJECT) {
                return _currentObject();
            }
            return null;
        }

        /*
        /**********************************************************
        /* ParserMinimalBase implementation: life-cycle
        /**********************************************************
         */

        @Override
        protected void _closeInput() {
            // Nothing to close: content is in-memory, and the buffer is
            // only borrowed -- must NOT be cleared or released here.
        }

        /**
         * Intentionally a no-op: this view does not own the underlying
         * buffer, so closing the view must not destroy buffered content.
         * Note also that {@link ParserMinimalBase#close()} marks the view
         * closed <i>before</i> calling this method, so even a failure
         * during resource release can not leave the view readable.
         */
        @Override
        protected void _releaseBuffers() {
            // Nothing to release: buffer is borrowed, not owned
        }

        @Override
        protected void _handleEOF() {
            // Buffered content is always complete: no unexpected EOF
        }
    }
}
