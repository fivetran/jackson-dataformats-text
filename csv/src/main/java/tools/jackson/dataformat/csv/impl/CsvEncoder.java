package tools.jackson.dataformat.csv.impl;

import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;

import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.core.io.CharTypes;
import tools.jackson.core.io.CharacterEscapes;
import tools.jackson.core.io.IOContext;
import tools.jackson.core.io.NumberOutput;
import tools.jackson.dataformat.csv.CsvSchema;
import tools.jackson.dataformat.csv.CsvWriteException;
import tools.jackson.dataformat.csv.CsvWriteFeature;

/**
 * Helper class that handles actual low-level construction of
 * CSV output, based only on indexes given without worrying about reordering,
 * or binding from logical properties.
 */
public class CsvEncoder
{
    // Default set of escaped characters (none)
    private static final int [] sOutputEscapes = new int[0];

    // Upper case hex chars:
    protected final static char[] HEX_CHARS = CharTypes.copyHexChars(true);

    /**
     * As an optimization we try coalescing short writes into
     * buffer; but pass longer directly.
     */
    protected final static int SHORT_WRITE = 32;

    /**
     * Also: only do check for optional quotes for short
     * values; longer ones will always be quoted.
     */
    protected final static int MAX_QUOTE_CHECK = 24;
    
    protected final BufferedValue[] NO_BUFFERED = new BufferedValue[0];

    private final static char[] TRUE_CHARS = "true".toCharArray();
    private final static char[] FALSE_CHARS = "false".toCharArray();

    /**
     * Currently active set of output escape code definitions (whether
     * and how to escape or not).
     */
    protected int[] _outputEscapes = sOutputEscapes;

    /*
    /**********************************************************************
    /* Configuration
    /**********************************************************************
     */

    protected final IOContext _ioContext;

    /**
     * Underlying {@link Writer} used for output.
     */
    protected final Writer _out;
    
    protected final char _cfgColumnSeparator;

    protected final int _cfgQuoteCharacter;

    protected final int _cfgEscapeCharacter;
    
    protected final char[] _cfgLineSeparator;

    protected final char[] _cfgNullValue;

    protected final int _cfgLineSeparatorLength;

    protected final int _cfgMaxQuoteCheckChars;
    
    /**
     * Lowest-valued character that is safe to output without using
     * quotes around value, NOT including possible escape character.
     */
    protected final int _cfgMinSafeChar;

    protected int _csvFeatures;

    /**
     * Marker flag used to determine if to do optimal (aka "strict") quoting
     * checks or not (looser conservative check)
     */
    protected boolean _cfgOptimalQuoting;

    protected final boolean _cfgAllowsComments;

    protected boolean _cfgIncludeMissingTail;

    protected boolean _cfgAlwaysQuoteStrings;

    protected boolean _cfgAlwaysQuoteEmptyStrings;

    // @since 2.16
    protected boolean _cfgAlwaysQuoteNumbers;

    protected boolean _cfgEscapeQuoteCharWithEscapeChar;

    protected boolean _cfgEscapeControlCharWithEscapeChar;

    /**
     * @since 2.14
     */
    protected boolean _cfgUseFastDoubleWriter;

    protected final char _cfgQuoteCharEscapeChar;

    protected final char _cfgControlCharEscapeChar;

    /*
    /**********************************************************************
    /* Output state
    /**********************************************************************
     */

    protected int _columnCount;
    
    /**
     * Index of column we expect to write next
     */
    protected int _nextColumnToWrite = 0;

    /**
     * And if output comes in shuffled order we will need to do 
     * bit of ordering.
     */
    protected BufferedValue[] _buffered = NO_BUFFERED;

    /**
     * Index of the last buffered value
     */
    protected int _lastBuffered = -1;

    protected boolean _trailingLFRemoved = false;

    /*
    /**********************************************************************
    /* Output buffering, low-level
    /**********************************************************************
     */

    /**
     * Intermediate buffer in which contents are buffered before
     * being written using {@link #_out}.
     */
    protected char[] _outputBuffer;

    /**
     * Flag that indicates whether the <code>_outputBuffer</code> is recyclable (and
     * needs to be returned to recycler once we are done) or not.
     */
    protected boolean _bufferRecyclable;
    
    /**
     * Pointer to the next available char position in {@link #_outputBuffer}
     */
    protected int _outputTail = 0;

    /**
     * Offset to index after the last valid index in {@link #_outputBuffer}.
     * Typically same as length of the buffer.
     */
    protected final int _outputEnd;
    
    /**
     * Let's keep track of how many bytes have been output, may prove useful
     * when debugging. This does <b>not</b> include bytes buffered in
     * the output buffer, just bytes that have been written using underlying
     * stream writer.
     */
    protected int _charsWritten;

    /*
    /**********************************************************************
    /* Construction, (re)configuration
    /**********************************************************************
     */

    public CsvEncoder(IOContext ctxt, int csvFeatures, Writer out, CsvSchema schema,
            CharacterEscapes esc, boolean useFastDoubleWriter)
    {
        _ioContext = ctxt;
        _csvFeatures = csvFeatures;
        _cfgUseFastDoubleWriter = useFastDoubleWriter;
        _cfgOptimalQuoting = CsvWriteFeature.STRICT_CHECK_FOR_QUOTING.enabledIn(csvFeatures);
        _cfgIncludeMissingTail = !CsvWriteFeature.OMIT_MISSING_TAIL_COLUMNS.enabledIn(_csvFeatures);
        _cfgAlwaysQuoteStrings = CsvWriteFeature.ALWAYS_QUOTE_STRINGS.enabledIn(csvFeatures);
        _cfgAlwaysQuoteEmptyStrings = CsvWriteFeature.ALWAYS_QUOTE_EMPTY_STRINGS.enabledIn(csvFeatures);
        _cfgAlwaysQuoteNumbers = CsvWriteFeature.ALWAYS_QUOTE_NUMBERS.enabledIn(csvFeatures);
        _cfgEscapeQuoteCharWithEscapeChar = CsvWriteFeature.ESCAPE_QUOTE_CHAR_WITH_ESCAPE_CHAR.enabledIn(csvFeatures);
        _cfgEscapeControlCharWithEscapeChar = CsvWriteFeature.ESCAPE_CONTROL_CHARS_WITH_ESCAPE_CHAR.enabledIn(csvFeatures);

        _outputBuffer = ctxt.allocConcatBuffer();
        _bufferRecyclable = true;
        _outputEnd = _outputBuffer.length;
        _out = out;

        _cfgColumnSeparator = schema.getColumnSeparator();
        _cfgQuoteCharacter = schema.getQuoteChar();
        _cfgEscapeCharacter = schema.getEscapeChar();
        _cfgLineSeparator = schema.getLineSeparator();
        _cfgLineSeparatorLength = (_cfgLineSeparator == null) ? 0 : _cfgLineSeparator.length;
        _cfgNullValue = schema.getNullValueOrEmpty();
        _cfgAllowsComments = schema.allowsComments();

        _columnCount = schema.size();
        _outputEscapes = (esc == null) ? sOutputEscapes : esc.getEscapeCodesForAscii();

        _cfgMinSafeChar = _calcSafeChar();

        _cfgMaxQuoteCheckChars = MAX_QUOTE_CHECK;

        _cfgQuoteCharEscapeChar = _getQuoteCharEscapeChar(
                _cfgEscapeQuoteCharWithEscapeChar,
                _cfgQuoteCharacter,
                _cfgEscapeCharacter
        );

        _cfgControlCharEscapeChar = _cfgEscapeCharacter > 0 ? (char) _cfgEscapeCharacter : '\\';

        _verifyConfiguration(schema);
    }

    public CsvEncoder(CsvEncoder base, CsvSchema newSchema)
    {
        _ioContext = base._ioContext;
        _csvFeatures = base._csvFeatures;
        _cfgUseFastDoubleWriter = base._cfgUseFastDoubleWriter;
        _cfgOptimalQuoting = base._cfgOptimalQuoting;
        _cfgIncludeMissingTail = base._cfgIncludeMissingTail;
        _cfgAlwaysQuoteStrings = base._cfgAlwaysQuoteStrings;
        _cfgAlwaysQuoteEmptyStrings = base._cfgAlwaysQuoteEmptyStrings;
        _cfgAlwaysQuoteNumbers = base._cfgAlwaysQuoteNumbers;

        _cfgEscapeQuoteCharWithEscapeChar = base._cfgEscapeQuoteCharWithEscapeChar;
        _cfgEscapeControlCharWithEscapeChar = base._cfgEscapeControlCharWithEscapeChar;

        _outputBuffer = base._outputBuffer;
        _bufferRecyclable = base._bufferRecyclable;
        _outputEnd = base._outputEnd;
        _out = base._out;
        _cfgMaxQuoteCheckChars = base._cfgMaxQuoteCheckChars;
        _outputEscapes = base._outputEscapes;

        _cfgColumnSeparator = newSchema.getColumnSeparator();
        _cfgQuoteCharacter = newSchema.getQuoteChar();
        _cfgEscapeCharacter = newSchema.getEscapeChar();
        _cfgLineSeparator = newSchema.getLineSeparator();
        _cfgLineSeparatorLength = _cfgLineSeparator.length;
        _cfgNullValue = newSchema.getNullValueOrEmpty();
        _cfgAllowsComments = newSchema.allowsComments();
        _cfgMinSafeChar = _calcSafeChar();
        _columnCount = newSchema.size();
        _cfgQuoteCharEscapeChar = _getQuoteCharEscapeChar(
                base._cfgEscapeQuoteCharWithEscapeChar,
                newSchema.getQuoteChar(),
                newSchema.getEscapeChar()
        );
        _cfgControlCharEscapeChar = _cfgEscapeCharacter > 0 ? (char) _cfgEscapeCharacter : '\\';

        _verifyConfiguration(newSchema);
    }

    private void _verifyConfiguration(CsvSchema schema) 
    {
        // 21-Feb-2023, tatu: [dataformats-text#374]: Need to verify that Escape character
        //   is defined if need to use it
        if (_cfgEscapeQuoteCharWithEscapeChar || _cfgEscapeControlCharWithEscapeChar) {
            if (!schema.usesEscapeChar()) {
                throw CsvWriteException.from(null,
"Cannot use `CsvGenerator.Feature.ESCAPE_QUOTE_CHAR_WITH_ESCAPE_CHAR` or `CsvGenerator.Feature.ESCAPE_CONTROL_CHARS_WITH_ESCAPE_CHAR`"
                        +" if no escape character defined in `CsvSchema`",
                        schema);
            }
        }
    }
    
    
    private final char _getQuoteCharEscapeChar(
            final boolean escapeQuoteCharWithEscapeChar,
            final int quoteCharacter,
            final int escapeCharacter)
    {
        final char quoteEscapeChar;

        if (escapeQuoteCharWithEscapeChar && escapeCharacter > 0) {
            quoteEscapeChar = (char) escapeCharacter;
        }
        else if (quoteCharacter > 0) {
            quoteEscapeChar = (char) quoteCharacter;
        }
        else {
            quoteEscapeChar = '\\';
        }

        return quoteEscapeChar;
    }

    private final int _calcSafeChar()
    {
        // note: quote char may be -1 to signify "no quoting":
        int min = Math.max(_cfgColumnSeparator, _cfgQuoteCharacter);
        // 06-Nov-2015, tatu: We will NOT apply escape character, because it usually
        //    has higher ascii value (with backslash); better handle separately.
        // 23-Sep-2020, tatu: Should not actually need to consider anything but the
        //    first character when checking... but leaving rest for now
        for (int i = 0; i < _cfgLineSeparatorLength; ++i) {
            min = Math.max(min, _cfgLineSeparator[i]);
        }
        return min+1;
    }

    public CsvEncoder withSchema(CsvSchema schema) {
        return new CsvEncoder(this, schema);
    }

    /*
    public CsvEncoder overrideFormatFeatures(int feat) {
        if (feat != _csvFeatures) {
            _csvFeatures = feat;
            _cfgOptimalQuoting = CsvGenerator.Feature.STRICT_CHECK_FOR_QUOTING.enabledIn(feat);
            _cfgIncludeMissingTail = !CsvGenerator.Feature.OMIT_MISSING_TAIL_COLUMNS.enabledIn(feat);
            _cfgAlwaysQuoteStrings = CsvGenerator.Feature.ALWAYS_QUOTE_STRINGS.enabledIn(feat);
            _cfgAlwaysQuoteEmptyStrings = CsvGenerator.Feature.ALWAYS_QUOTE_EMPTY_STRINGS.enabledIn(feat);
            _cfgAlwaysQuoteNumbers = CsvGenerator.Feature.ALWAYS_QUOTE_NUMBERS.enabledIn(feat);
            _cfgEscapeQuoteCharWithEscapeChar = CsvGenerator.Feature.ESCAPE_QUOTE_CHAR_WITH_ESCAPE_CHAR.enabledIn(feat);
            _cfgEscapeControlCharWithEscapeChar = Feature.ESCAPE_CONTROL_CHARS_WITH_ESCAPE_CHAR.enabledIn(feat);
        }
        return this;
    }
    */

    public CsvEncoder setOutputEscapes(int[] esc) {
        _outputEscapes = (esc != null) ? esc : sOutputEscapes;
        return this;
    }

    /*
    /**********************************************************************
    /* Read-access to output state
    /**********************************************************************
     */

    public Object getOutputTarget() {
        return _out;
    }

    /**
     * NOTE: while value does indeed indicate amount that has been written in the buffer,
     * there may be more intermediate data that is buffered as values but not yet in
     * buffer.
     */
    public int getOutputBuffered() {
        return _outputTail;
    }

    public int nextColumnIndex() {
        return _nextColumnToWrite;
    }

    /*
    /**********************************************************************
    /* Writer API, writes from generator
    /**********************************************************************
     */

    public final void write(int columnIndex, String value) throws JacksonException
    {
        // easy case: all in order
        if (columnIndex == _nextColumnToWrite) {
            // inlined 'appendValue(String)`
            if (_outputTail >= _outputEnd) {
                _flushBuffer();
            }
            if (_nextColumnToWrite > 0) {
                appendColumnSeparator();
            }
            final int len = value.length();
            if (_cfgAlwaysQuoteStrings || _mayNeedQuotes(value, len, columnIndex)) {
                if (_cfgEscapeCharacter > 0) {
                    _writeQuotedAndEscaped(value, (char) _cfgEscapeCharacter);
                } else {
                    _writeQuoted(value);
                }
            } else {
                writeRaw(value);
            }
            ++_nextColumnToWrite;
            return;
        }
        _buffer(columnIndex, BufferedValue.buffered(value));
    }

    public final void write(int columnIndex, char[] ch, int offset, int len) throws JacksonException
    {
        // !!! TODO: optimize
        write(columnIndex, new String(ch, offset, len));
    }

    public final void write(int columnIndex, int value) throws JacksonException
    {
        // easy case: all in order
        if (columnIndex == _nextColumnToWrite) {
            // inlined 'appendValue(int)'
            // up to 10 digits and possible minus sign, leading comma, possible quotes
            if ((_outputTail + 14) > _outputEnd) {
                _flushBuffer();
            }
            if (_nextColumnToWrite > 0) {
                _outputBuffer[_outputTail++] = _cfgColumnSeparator;
            }
            if (_cfgAlwaysQuoteNumbers) {
                _outputBuffer[_outputTail++] = (char) _cfgQuoteCharacter;
            }
            _outputTail = NumberOutput.outputInt(value, _outputBuffer, _outputTail);
            if (_cfgAlwaysQuoteNumbers) {
                _outputBuffer[_outputTail++] = (char) _cfgQuoteCharacter;
            }
            ++_nextColumnToWrite;
            return;
        }
        _buffer(columnIndex, BufferedValue.buffered(value));
    }

    public final void write(int columnIndex, long value) throws JacksonException
    {
        // easy case: all in order
        if (columnIndex == _nextColumnToWrite) {
            // inlined 'appendValue(int)'
            // up to 20 digits, minus sign, leading comma, possible quotes
            if ((_outputTail + 24) > _outputEnd) {
                _flushBuffer();
            }
            if (_nextColumnToWrite > 0) {
                _outputBuffer[_outputTail++] = _cfgColumnSeparator;
            }
            if (_cfgAlwaysQuoteNumbers) {
                _outputBuffer[_outputTail++] = (char) _cfgQuoteCharacter;
            }
            _outputTail = NumberOutput.outputLong(value, _outputBuffer, _outputTail);
            if (_cfgAlwaysQuoteNumbers) {
                _outputBuffer[_outputTail++] = (char) _cfgQuoteCharacter;
            }
            ++_nextColumnToWrite;
            return;
        }
        _buffer(columnIndex, BufferedValue.buffered(value));
    }

    public final void write(int columnIndex, BigInteger value) throws JacksonException
    {
        // easy case: all in order
        final String numStr = value.toString();
        if (columnIndex == _nextColumnToWrite) {
            appendNumberValue(numStr);
            ++_nextColumnToWrite;
            return;
        }
        _buffer(columnIndex, BufferedValue.bufferedNumber(numStr));
    }
    
    public final void write(int columnIndex, float value) throws JacksonException
    {
        // easy case: all in order
        if (columnIndex == _nextColumnToWrite) {
            appendValue(value);
            ++_nextColumnToWrite;
            return;
        }
        _buffer(columnIndex, BufferedValue.buffered(value));
    }

    public final void write(int columnIndex, double value) throws JacksonException
    {
        // easy case: all in order
        if (columnIndex == _nextColumnToWrite) {
            appendValue(value);
            ++_nextColumnToWrite;
            return;
        }
        _buffer(columnIndex, BufferedValue.buffered(value));
    }

    public final void write(int columnIndex, BigDecimal value, boolean plain) throws JacksonException
    {
        final String numStr = plain ? value.toPlainString() : value.toString();

        // easy case: all in order
        if (columnIndex == _nextColumnToWrite) {
            appendNumberValue(numStr);
            ++_nextColumnToWrite;
            return;
        }
        _buffer(columnIndex, BufferedValue.bufferedNumber(numStr));
    }

    public final void write(int columnIndex, boolean value) throws JacksonException
    {
        // easy case: all in order
        if (columnIndex == _nextColumnToWrite) {
            appendValue(value);
            ++_nextColumnToWrite;
            return;
        }
        _buffer(columnIndex, BufferedValue.buffered(value));
    }

    public final void writeNonEscaped(int columnIndex, String rawValue) throws JacksonException
    {
        if (columnIndex == _nextColumnToWrite) {
            appendRawValue(rawValue);
            ++_nextColumnToWrite;
            return;
        }
        _buffer(columnIndex, BufferedValue.bufferedRaw(rawValue));
    }
        
    public final void writeNull(int columnIndex) throws JacksonException
    {
        if (columnIndex == _nextColumnToWrite) {
            appendNull();
            ++_nextColumnToWrite;
            return;
        }
        _buffer(columnIndex, BufferedValue.bufferedNull());
    }

    public final void writeColumnName(String name) throws JacksonException
    {
        appendValue(name);
        ++_nextColumnToWrite;
    }

    public void endRow() throws JacksonException
    {
        // First things first; any buffered?
        if (_lastBuffered >= 0) {
            final int last = _lastBuffered;
            _lastBuffered = -1;
            for (; _nextColumnToWrite <= last; ++_nextColumnToWrite) {
                BufferedValue value = _buffered[_nextColumnToWrite];
                if (value != null) {
                    _buffered[_nextColumnToWrite] = null;
                    value.write(this);
                } else if (_nextColumnToWrite > 0) { // ) {
                    // note: write method triggers prepending of separator; but for missing
                    // values we need to do it explicitly.
                    appendColumnSeparator();
                } 
            }
        } else if (_nextColumnToWrite <= 0) { // empty line; do nothing
            return;
        }
        // Any missing values?
        if (_nextColumnToWrite < _columnCount) {
            if (_cfgIncludeMissingTail) {
                do {
                    appendColumnSeparator();
                } while (++_nextColumnToWrite < _columnCount);
            }
        }
        // write line separator
        _nextColumnToWrite = 0;
        if ((_outputTail + _cfgLineSeparatorLength) > _outputEnd) {
            _flushBuffer();
        }
        System.arraycopy(_cfgLineSeparator, 0, _outputBuffer, _outputTail, _cfgLineSeparatorLength);
        _outputTail += _cfgLineSeparatorLength;
    }

    /*
    /**********************************************************************
    /* Writer API, writes via buffered values
    /**********************************************************************
     */

    protected void appendValue(String value) throws JacksonException
    {
        if (_outputTail >= _outputEnd) {
            _flushBuffer();
        }
        if (_nextColumnToWrite > 0) {
            appendColumnSeparator();
        }
        // First: determine if we need quotes; simple heuristics;
        // only check for short Strings, stop if something found
        final int len = value.length();
        if (_cfgAlwaysQuoteStrings || _mayNeedQuotes(value, len, _nextColumnToWrite)) {
            if (_cfgEscapeCharacter > 0) {
                _writeQuotedAndEscaped(value, (char) _cfgEscapeCharacter);
            } else {
                _writeQuoted(value);
            }
        } else {
            writeRaw(value);
        }
    }

    protected void appendRawValue(String value) throws JacksonException
    {
        if (_outputTail >= _outputEnd) {
            _flushBuffer();
        }
        if (_nextColumnToWrite > 0) {
            appendColumnSeparator();
        }
        writeRaw(value);
    }

    protected void appendValue(int value) throws JacksonException
    {
        // up to 10 digits and possible minus sign, leading comma, possible quotes
        if ((_outputTail + 14) > _outputEnd) {
            _flushBuffer();
        }
        if (_nextColumnToWrite > 0) {
            _outputBuffer[_outputTail++] = _cfgColumnSeparator;
        }
        if (_cfgAlwaysQuoteNumbers) {
            _outputBuffer[_outputTail++] = (char) _cfgQuoteCharacter;
        }
        _outputTail = NumberOutput.outputInt(value, _outputBuffer, _outputTail);
        if (_cfgAlwaysQuoteNumbers) {
            _outputBuffer[_outputTail++] = (char) _cfgQuoteCharacter;
        }
    }

    protected void appendValue(long value) throws JacksonException
    {
        // up to 20 digits, minus sign, leading comma, possible quotes
        if ((_outputTail + 24) > _outputEnd) {
            _flushBuffer();
        }
        if (_nextColumnToWrite > 0) {
            _outputBuffer[_outputTail++] = _cfgColumnSeparator;
        }
        if (_cfgAlwaysQuoteNumbers) {
            _outputBuffer[_outputTail++] = (char) _cfgQuoteCharacter;
        }
        _outputTail = NumberOutput.outputLong(value, _outputBuffer, _outputTail);
        if (_cfgAlwaysQuoteNumbers) {
            _outputBuffer[_outputTail++] = (char) _cfgQuoteCharacter;
        }
    }

    protected void appendValue(float value) throws JacksonException
    {
        String str = NumberOutput.toString(value, _cfgUseFastDoubleWriter);
        final int len = str.length();
        if ((_outputTail + len) >= _outputEnd) { // >= to include possible comma too
            _flushBuffer();
        }
        if (_nextColumnToWrite > 0) {
            _outputBuffer[_outputTail++] = _cfgColumnSeparator;
        }
        writeNumber(str);
    }

    protected void appendValue(double value) throws JacksonException
    {
        String str = NumberOutput.toString(value, _cfgUseFastDoubleWriter);
        final int len = str.length();
        if ((_outputTail + len) >= _outputEnd) { // >= to include possible comma too
            _flushBuffer();
        }
        if (_nextColumnToWrite > 0) {
            _outputBuffer[_outputTail++] = _cfgColumnSeparator;
        }
        writeNumber(str);
    }

    // @since 2.16: pre-encoded BigInteger/BigDecimal value
    protected void appendNumberValue(String numStr) throws JacksonException
    {
        // Same as "appendRawValue()", except may want quoting
        if (_outputTail >= _outputEnd) {
            _flushBuffer();
        }
        if (_nextColumnToWrite > 0) {
            appendColumnSeparator();
        }
        writeNumber(numStr);
    }

    protected void appendValue(boolean value) throws JacksonException {
        _append(value ? TRUE_CHARS : FALSE_CHARS);
    }

    protected void appendNull() throws JacksonException {
        _append(_cfgNullValue);
    }

    protected void _append(char[] ch) throws JacksonException {
        final int len = ch.length;
        if ((_outputTail + len) >= _outputEnd) { // >= to include possible comma too
            _flushBuffer();
        }
        if (_nextColumnToWrite > 0) {
            _outputBuffer[_outputTail++] = _cfgColumnSeparator;
        }
        if (len > 0) {
            System.arraycopy(ch, 0, _outputBuffer, _outputTail, len);
        }
        _outputTail += len;
    }
    
    protected void appendColumnSeparator() throws JacksonException {
        if (_outputTail >= _outputEnd) {
            _flushBuffer();
        }
        _outputBuffer[_outputTail++] = _cfgColumnSeparator;
    }

    /*
    /**********************************************************************
    /* Output methods, unprocessed ("raw")
    /**********************************************************************
     */

    public void writeRaw(String text) throws JacksonException
    {
        // Nothing to check, can just output as is
        int len = text.length();
        int room = _outputEnd - _outputTail;

        if (room == 0) {
            _flushBuffer();
            room = _outputEnd - _outputTail;
        }
        // But would it nicely fit in? If yes, it's easy
        if (room >= len) {
            text.getChars(0, len, _outputBuffer, _outputTail);
            _outputTail += len;
        } else {
            writeRawLong(text);
        }
    }

    public void writeRaw(String text, int start, int len) throws JacksonException
    {
        // Nothing to check, can just output as is
        int room = _outputEnd - _outputTail;

        if (room < len) {
            _flushBuffer();
            room = _outputEnd - _outputTail;
        }
        // But would it nicely fit in? If yes, it's easy
        if (room >= len) {
            text.getChars(start, start+len, _outputBuffer, _outputTail);
            _outputTail += len;
        } else {                
            writeRawLong(text.substring(start, start+len));
        }
    }

    public void writeRaw(char[] text, int offset, int len) throws JacksonException
    {
        // Only worth buffering if it's a short write?
        if (len < SHORT_WRITE) {
            int room = _outputEnd - _outputTail;
            if (len > room) {
                _flushBuffer();
            }
            System.arraycopy(text, offset, _outputBuffer, _outputTail, len);
            _outputTail += len;
            return;
        }
        // Otherwise, better just pass through:
        _flushBuffer();
        try {
            _out.write(text, offset, len);
        } catch (IOException e) {
            throw _wrapIOFailure(e);
        }
    }

    public void writeRaw(char c) throws JacksonException
    {
        if (_outputTail >= _outputEnd) {
            _flushBuffer();
        }
        _outputBuffer[_outputTail++] = c;
    }

    private void writeRawLong(String text) throws JacksonException
    {
        int room = _outputEnd - _outputTail;
        // If not, need to do it by looping
        text.getChars(0, room, _outputBuffer, _outputTail);
        _outputTail += room;
        _flushBuffer();
        int offset = room;
        int len = text.length() - room;

        while (len > _outputEnd) {
            int amount = _outputEnd;
            text.getChars(offset, offset+amount, _outputBuffer, 0);
            _outputTail = amount;
            _flushBuffer();
            offset += amount;
            len -= amount;
        }
        // And last piece (at most length of buffer)
        text.getChars(offset, offset+len, _outputBuffer, 0);
        _outputTail = len;
    }

    // @since 2.16
    private void writeNumber(String text) throws JacksonException
    {
        final int len = text.length();
        if ((_outputTail + len + 2) > _outputEnd) {
            _flushBuffer();
        }

        if (_cfgAlwaysQuoteNumbers) {
            _outputBuffer[_outputTail++] = (char) _cfgQuoteCharacter;
            text.getChars(0, len, _outputBuffer, _outputTail);
            _outputTail += len;
            _outputBuffer[_outputTail++] = (char) _cfgQuoteCharacter;
        } else {
            text.getChars(0, len, _outputBuffer, _outputTail);
            _outputTail += len;
        }
    }

    /*
    /**********************************************************************
    /* Output methods, with quoting and escaping
    /**********************************************************************
     */

    public void _writeQuoted(String text) throws JacksonException
    {
        final int[] escCodes = _outputEscapes;
        final int escLen = escCodes.length;

        if (_outputTail >= _outputEnd) {
            _flushBuffer();
        }
        // NOTE: caller should guarantee quote char is valid (not -1) at this point:
        final char q = (char) _cfgQuoteCharacter;
        _outputBuffer[_outputTail++] = q;
        // simple case: if we have enough room, no need for boundary checks
        final int len = text.length();
        if ((_outputTail + len + len) >= _outputEnd) {
            _writeLongQuoted(text, q);
            return;
        }
        // 22-Jan-2015, tatu: Common case is that of no quoting needed, so let's
        //     make a speculative copy, then scan
        // 06-Nov-2015, tatu: Not sure if copy actually improves perf; it did with
        //   older JVMs (1.5 at least), but not sure about 1.8 and later
        final char[] buf = _outputBuffer;
        int ptr = _outputTail;

        text.getChars(0, len, buf, ptr);

        final int end = ptr+len;

        for (; ptr < end; ++ptr) {
            char c = buf[ptr];
            // see if any of the characters need escaping.
            // if yes, fall back to the more convoluted write method
            if ((c == q) || (c < escLen && escCodes[c] != 0)) {
                break; // for
            }
        }

        if (ptr == end) { // all good, no quoting or escaping!
            _outputBuffer[ptr] = q;
            _outputTail = ptr+1;
        } else { // doh. do need quoting
            _writeQuoted(text, q, ptr - _outputTail);
        }
    }

    protected void _writeQuoted(String text, char q, int i) throws JacksonException
    {
        final int[] escCodes = _outputEscapes;
        final int escLen = escCodes.length;

        final char[] buf = _outputBuffer;
        _outputTail += i;
        final int len = text.length();
        for (; i < len; ++i) {
            char c = text.charAt(i);
            if (c < escLen) {
                int escCode = escCodes[c];
                if (escCode != 0) { // for escape control and double quotes, c will be 0
                    _appendCharacterEscape(c, escCode);
                    continue; // for
                }
            }

            if (c == q) { // double up
                if (_outputTail >= _outputEnd) {
                    _flushBuffer();
                }

                buf[_outputTail++] = _cfgQuoteCharEscapeChar; // this will be the quote
            }
            if (_outputTail >= _outputEnd) {
                _flushBuffer();
            }
            buf[_outputTail++] = c;
        }
        if (_outputTail >= _outputEnd) {
            _flushBuffer();
        }
        buf[_outputTail++] = q;
    }

    private final void _writeLongQuoted(String text, char q) throws JacksonException
    {
        final int[] escCodes = _outputEscapes;
        final int escLen = escCodes.length;

        final int len = text.length();
        for (int i = 0; i < len; ++i) {
            if (_outputTail >= _outputEnd) {
                _flushBuffer();
            }
            char c = text.charAt(i);
            if (c < escLen) {
                int escCode = escCodes[c];
                if (escCode != 0) { // for escape control and double quotes, c will be 0
                    _appendCharacterEscape(c, escCode);
                    continue; // for
                }
            }

            if (c == q) { // double up
                _outputBuffer[_outputTail++] = _cfgQuoteCharEscapeChar;
                if (_outputTail >= _outputEnd) {
                    _flushBuffer();
                }
            }
            _outputBuffer[_outputTail++] = c;
        }
        if (_outputTail >= _outputEnd) {
            _flushBuffer();
        }
        _outputBuffer[_outputTail++] = q;
    }

    public void _writeQuotedAndEscaped(String text, char esc) throws JacksonException
    {
        final int[] escCodes = _outputEscapes;
        final int escLen = escCodes.length;

        if (_outputTail >= _outputEnd) {
            _flushBuffer();
        }
        // NOTE: caller should guarantee quote char is valid (not -1) at this point:
        final char q = (char) _cfgQuoteCharacter;
        _outputBuffer[_outputTail++] = q;
        final int len = text.length();
        if ((_outputTail + len + len) >= _outputEnd) {
            _writeLongQuotedAndEscaped(text, esc);
            return;
        }
        final char[] buf = _outputBuffer;
        int ptr = _outputTail;

        text.getChars(0, len, buf, ptr);

        final int end = ptr+len;
        for (; ptr < end; ++ptr) {
            char c = buf[ptr];
            if ((c == q) || (c == esc) || (c < escLen && escCodes[c] != 0)) {
                break;
            }
        }

        if (ptr == end) { // all good, no quoting or escaping!
            _outputBuffer[ptr] = q;
            _outputTail = ptr+1;
        } else { // quoting AND escaping
            _writeQuotedAndEscaped(text, q, esc, ptr - _outputTail);
        }
    }

    protected void _writeQuotedAndEscaped(String text, char q, char esc, int i) throws JacksonException
    {
        final int[] escCodes = _outputEscapes;
        final int escLen = escCodes.length;

        final char[] buf = _outputBuffer;
        _outputTail += i;
        final int len = text.length();
        for (; i < len; ++i) {
            char c = text.charAt(i);
            if (c < escLen) {
                int escCode = escCodes[c];
                if (escCode != 0) { // for escape control and double quotes, c will be 0
                    _appendCharacterEscape(c, escCode);
                    continue; // for
                }
            }

            if (c == q) { // double up
                if (_outputTail >= _outputEnd) {
                    _flushBuffer();
                }

                _outputBuffer[_outputTail++] = _cfgQuoteCharEscapeChar;
            } else if (c == esc) { // double up
                if (_outputTail >= _outputEnd) {
                    _flushBuffer();
                }

                _outputBuffer[_outputTail++] = _cfgControlCharEscapeChar;
            }

            if (_outputTail >= _outputEnd) {
                _flushBuffer();
            }
            buf[_outputTail++] = c;
        }
        if (_outputTail >= _outputEnd) {
            _flushBuffer();
        }
        buf[_outputTail++] = q;
    }
    
    private final void _writeLongQuotedAndEscaped(String text, char esc) throws JacksonException
    {
        final int[] escCodes = _outputEscapes;
        final int escLen = escCodes.length;

        final int len = text.length();
        // NOTE: caller should guarantee quote char is valid (not -1) at this point:
        final char q = (char) _cfgQuoteCharacter;
        // 23-Sep-2020, tatu: Why was this defined but not used? Commented out in 2.11.3
//        final char quoteEscape = _cfgEscapeQuoteCharWithEscapeChar ? esc : q;
        for (int i = 0; i < len; ++i) {
            if (_outputTail >= _outputEnd) {
                _flushBuffer();
            }
            char c = text.charAt(i);
            if (c < escLen) {
                int escCode = escCodes[c];
                if (escCode != 0) { // for escape control and double quotes, c will be 0
                    _appendCharacterEscape(c, escCode);
                    continue; // for
                }
            }

            if (c == q) { // double up
                _outputBuffer[_outputTail++] = _cfgQuoteCharEscapeChar;
                if (_outputTail >= _outputEnd) {
                    _flushBuffer();
                }
            } else if (c == esc) { // double up
                _outputBuffer[_outputTail++] = _cfgControlCharEscapeChar;
                if (_outputTail >= _outputEnd) {
                    _flushBuffer();
                }
            }

            _outputBuffer[_outputTail++] = c;
        }
        if (_outputTail >= _outputEnd) {
            _flushBuffer();
        }
        _outputBuffer[_outputTail++] = q;
    }

    /*
    /**********************************************************************
    /* Writer API, state changes
    /**********************************************************************
     */
    
    public void flush(boolean flushStream) throws IOException
    {
        _flushBuffer();
        if (flushStream) {
            _out.flush();
        }
    }

    public void close(boolean autoClose, boolean flushStream) throws IOException
    {
        // May need to remove the linefeed appended after the last row written
        // (if not yet done)
        if (!CsvWriteFeature.WRITE_LINEFEED_AFTER_LAST_ROW.enabledIn(_csvFeatures)) {
            _removeTrailingLF();
        }
        _flushBuffer();
        if (autoClose) {
            _out.close();
        } else if (flushStream) {
            // If we can't close it, we should at least flush
            _out.flush();
        }
        // Internal buffer(s) generator has can now be released as well
        _releaseBuffers();
    }

    private void _removeTrailingLF() throws IOException {
        if (!_trailingLFRemoved) {
            _trailingLFRemoved = true;
            // Remove trailing LF if (but only if) it appears to be in output
            // buffer (may not be possible if `flush()` has been called)
            _outputTail = Math.max(0, _outputTail - _cfgLineSeparatorLength);
        }
    }

    /*
    /**********************************************************************
    /* Internal methods
    /**********************************************************************
     */

    /**
     * Helper method that determines whether given String is likely
     * to require quoting; check tries to optimize for speed.
     */
    protected boolean _mayNeedQuotes(String value, int length, int columnIndex)
    {
        // 21-Mar-2014, tatu: If quoting disabled, don't quote
        if (_cfgQuoteCharacter < 0) {
            return false;
        }
        // may skip checks unless we want exact checking
        if (_cfgOptimalQuoting) {
            // 31-Dec-2014, tatu: Comment lines start with # so quote if starts with #
            // 28-May-2021, tatu: As per [dataformats-text#270] only check if first column
            if (_cfgAllowsComments && (columnIndex == 0)
                && (length > 0) && (value.charAt(0) == '#')) {
                return true;
            }
            if (_cfgEscapeCharacter > 0) {
                return _needsQuotingStrict(value, _cfgEscapeCharacter);
            }
            return _needsQuotingStrict(value);
        }
        if (length > _cfgMaxQuoteCheckChars) {
            return true;
        }
        if (_cfgEscapeCharacter > 0) {
            return _needsQuotingLoose(value, _cfgEscapeCharacter);
        }
        if (_cfgAlwaysQuoteEmptyStrings && length == 0) {
            return true;
        }
        return _needsQuotingLoose(value);
    }

    /**
     * @since 2.4
     */
    protected final boolean _needsQuotingLoose(String value)
    {
        char esc1 = _cfgQuoteCharEscapeChar;
        char esc2 = _cfgControlCharEscapeChar;

        for (int i = 0, len = value.length(); i < len; ++i) {
            char c = value.charAt(i);
            if ((c < _cfgMinSafeChar)
                    || (c == esc1)
                    || (c == esc2)) {
                return true;
            }
        }
        return false;
    }

    protected final boolean _needsQuotingLoose(String value, int esc)
    {
        for (int i = 0, len = value.length(); i < len; ++i) {
            int ch = value.charAt(i);
            if ((ch < _cfgMinSafeChar) || (ch == esc)) {
                return true;
            }
        }
        return false;
    }

    protected boolean _needsQuotingStrict(String value)
    {
        final int minSafe = _cfgMinSafeChar;

        final int[] escCodes = _outputEscapes;
        final int escLen = escCodes.length;
        // 23-Sep-2020, tatu: [dataformats-text#217] Must also ensure line separator
        //   leads to quoting
        final int lfFirst = (_cfgLineSeparatorLength == 0) ? 0 : _cfgLineSeparator[0];

        for (int i = 0, len = value.length(); i < len; ++i) {
            int c = value.charAt(i);
            if (c < minSafe) {
                if (c == _cfgColumnSeparator || c == _cfgQuoteCharacter
                        || (c < escLen && escCodes[c] != 0)
                        || (c == lfFirst)) {
                    return true;
                }
            }
        }
        return false;
    }

    protected boolean _needsQuotingStrict(String value, int esc)
    {
        final int minSafe = _cfgMinSafeChar;
        final int[] escCodes = _outputEscapes;
        final int escLen = escCodes.length;
        // 23-Sep-2020, tatu: [dataformats-text#217] Must also ensure line separator
        //   leads to quoting
        final int lfFirst = (_cfgLineSeparatorLength == 0) ? 0 : _cfgLineSeparator[0];

        for (int i = 0, len = value.length(); i < len; ++i) {
            int c = value.charAt(i);
            if (c < minSafe) {
                if (c == _cfgColumnSeparator || c == _cfgQuoteCharacter
                        || (c < escLen && escCodes[c] != 0)
                        || (c == lfFirst)) {
                    return true;
                }
            } else if (c == esc) {
                return true;
            }
        }
        return false;
    }
    
    protected void _buffer(int index, BufferedValue v)
    {
        _lastBuffered = Math.max(_lastBuffered, index);
        if (index >= _buffered.length) {
            _buffered = Arrays.copyOf(_buffered, Math.max(index+1, _columnCount));
        }
        _buffered[index] = v;
    }

    protected void _flushBuffer() throws JacksonException
    {
        if (_outputTail > 0) {
            _charsWritten += _outputTail;
            try {
                _out.write(_outputBuffer, 0, _outputTail);
            } catch (IOException e) {
                throw _wrapIOFailure(e);
            }
            _outputTail = 0;
        }
    }

    public void _releaseBuffers()
    {
        char[] buf = _outputBuffer;
        if (buf != null && _bufferRecyclable) {
            _outputBuffer = null;
            _ioContext.releaseConcatBuffer(buf);
        }
    }

    /**
     * Method called to append escape sequence for given character, at the
     * end of standard output buffer; or if not possible, write out directly.
     */
    private void _appendCharacterEscape(char ch, int escCode)
        throws JacksonException
    {
        if (escCode >= 0) { // \\N (2 char)
            if ((_outputTail + 2) > _outputEnd) {
                _flushBuffer();
            }
            _outputBuffer[_outputTail++] = _cfgControlCharEscapeChar;
            _outputBuffer[_outputTail++] = (char) escCode;
            return;
        }

        if ((_outputTail + 5) >= _outputEnd) {
            _flushBuffer();
        }
        int ptr = _outputTail;
        char[] buf = _outputBuffer;
        buf[ptr++] = '\\';
        buf[ptr++] = 'u';
        // We know it's a control char, so only the last 2 chars are non-0
        if (ch > 0xFF) { // beyond 8 bytes
            int hi = (ch >> 8) & 0xFF;
            buf[ptr++] = HEX_CHARS[hi >> 4];
            buf[ptr++] = HEX_CHARS[hi & 0xF];
            ch &= 0xFF;
        } else {
            buf[ptr++] = '0';
            buf[ptr++] = '0';
        }
        buf[ptr++] = HEX_CHARS[ch >> 4];
        buf[ptr++] = HEX_CHARS[ch & 0xF];
        _outputTail = ptr;
        return;
    }

    // @since 3.0: defined by basic JsonParser/JsonGenerator but since we are
    //   not extending need to copy here
    protected JacksonException _wrapIOFailure(IOException e) {
        return JacksonIOException.construct(e);
    }
}
