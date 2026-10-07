package tools.jackson.core.exc;

import java.io.IOException;
import java.io.OutputStream;

import tools.jackson.core.JsonParser;
import tools.jackson.core.TokenStreamLocation;

/**
 * Exception type used to signal that writing of decoded binary (Base64)
 * content into caller-provided {@link OutputStream} failed during
 * {@link JsonParser#readBinaryValue(OutputStream)} (or its variant
 * with explicit Base64 variant).
 *<p>
 * In addition to the underlying {@link IOException} (retained as
 * {@link #getCause()}, never swallowed), this exception carries context
 * about the failure: the location of the token being decoded
 * (see {@link #getLocation()}) and the number of bytes that had been
 * successfully written to the target stream before the failure
 * (see {@link #getBytesWritten()}).
 *<p>
 * Note that the target {@link OutputStream} is never closed by the
 * parser: ownership remains with the caller.
 *
 * @since 3.3
 */
public class BinaryWriteException extends JacksonIOException
{
    private final static long serialVersionUID = 1L;

    /**
     * Number of bytes that had been successfully written to the target
     * {@link OutputStream} before the failure occurred.
     */
    protected final long _bytesWritten;

    protected BinaryWriteException(JsonParser processor, String msg,
            TokenStreamLocation loc, IOException source, long bytesWritten) {
        super(processor, msg, loc, source);
        _bytesWritten = bytesWritten;
    }

    /**
     * Factory method for constructing an instance to signal failure
     * of writing decoded binary content to given target stream.
     *
     * @param p Parser that was decoding binary content
     * @param source Underlying I/O failure (retained as the cause)
     * @param loc Location of the token being decoded
     * @param bytesWritten Number of bytes successfully written before failure
     *
     * @return Exception instance constructed
     */
    public static BinaryWriteException construct(JsonParser p, IOException source,
            TokenStreamLocation loc, long bytesWritten) {
        final String msg = String.format(
                "Failed to write decoded binary content to OutputStream "
                +"(after %d bytes successfully written): %s",
                bytesWritten, source.getMessage());
        return new BinaryWriteException(p, msg, loc, source, bytesWritten);
    }

    /**
     * Accessor for the number of bytes that had been successfully written
     * to the target {@link OutputStream} before the failure.
     *
     * @return Number of bytes successfully written before the failure
     */
    public long getBytesWritten() {
        return _bytesWritten;
    }

    // Overridden for co-variant type
    @Override
    public JsonParser processor() {
        return (JsonParser) _processor;
    }
}
