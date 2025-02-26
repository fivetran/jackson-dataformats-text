package tools.jackson.dataformat.csv;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashSet;
import java.util.Set;

import tools.jackson.core.*;
import tools.jackson.core.base.ParserMinimalBase;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.core.io.IOContext;
import tools.jackson.core.json.DupDetector;
import tools.jackson.core.util.ByteArrayBuilder;
import tools.jackson.core.util.JacksonFeatureSet;
import tools.jackson.core.util.SimpleStreamReadContext;
import tools.jackson.core.util.TextBuffer;

import tools.jackson.dataformat.csv.impl.CsvDecoder;

/**
 * {@link JsonParser} implementation used to expose CSV documents
 * in form that allows other Jackson functionality to deal
 * with it.
 *<p>
 * Implementation is based on a state-machine that pulls information
 * using {@link CsvDecoder}.
 */
public class CsvParser
    extends ParserMinimalBase
{
    // Just to protect against bugs, DoS, limit number of column defs we may read
    private final static int MAX_COLUMNS = 99999;

    private final static CsvSchema EMPTY_SCHEMA;
    static {
        EMPTY_SCHEMA = CsvSchema.emptySchema();
    }

    /**
     * CSV is slightly different from defaults, having essentially untyped
     * scalars except if indicated by schema
     *
     * @since 2.12
     */
    protected final static JacksonFeatureSet<StreamReadCapability> STREAM_READ_CAPABILITIES =
            DEFAULT_READ_CAPABILITIES
                .with(StreamReadCapability.UNTYPED_SCALARS)
            ;

    /*
    /**********************************************************************
    /* State constants
    /**********************************************************************
     */

    /**
     * Initial state before anything is read from document.
     */
    protected final static int STATE_DOC_START = 0;
    
    /**
     * State before logical start of a record, in which next
     * token to return will be {@link JsonToken#START_OBJECT}
     * (or if no Schema is provided, {@link JsonToken#START_ARRAY}).
     */
    protected final static int STATE_RECORD_START = 1;

    /**
     * State in which next entry will be available, returning
     * either {@link JsonToken#PROPERTY_NAME} or value
     * (depending on whether entries are expressed as
     * Objects or just Arrays); or
     * matching close marker.
     */
    protected final static int STATE_NEXT_ENTRY = 2;

    /**
     * State in which value matching property name will
     * be returned.
     */
    protected final static int STATE_NAMED_VALUE = 3;

    /**
     * State in which "unnamed" value (entry in an array)
     * will be returned, if one available; otherwise
     * end-array is returned.
     */
    protected final static int STATE_UNNAMED_VALUE = 4;

    /**
     * State in which a column value has been determined to be of
     * an array type, and will need to be split into multiple
     * values. This can currently only occur for named values.
     */
    protected final static int STATE_IN_ARRAY = 5;

    /**
     * State in which we have encountered more column values than there should be,
     * and need to basically skip extra values if callers tries to advance parser
     * state.
     */
    protected final static int STATE_SKIP_EXTRA_COLUMNS = 6;

    /**
     * State in which we should expose name token for a "missing column"
     * (for which placeholder `null` value is to be added as well);
     * see {@link CsvReadFeature#INSERT_NULLS_FOR_MISSING_COLUMNS} for details.
     */
    protected final static int STATE_MISSING_NAME = 7;

    /**
     * State in which we should expose `null` value token as a value for
     * "missing" column;
     * see {@link CsvReadFeature#INSERT_NULLS_FOR_MISSING_COLUMNS} for details.
     */
    protected final static int STATE_MISSING_VALUE = 8;

    /**
     * State in which end marker is returned; either
     * null (if no array wrapping), or
     * {@link JsonToken#END_ARRAY} for wrapping.
     * This step will loop, returning series of nulls
     * if {@link #nextToken} is called multiple times.
     */
    protected final static int STATE_DOC_END = 9;

    /*
    /**********************************************************************
    /* Configuration
    /**********************************************************************
     */

    protected int _formatFeatures;

    /**
     * Definition of columns being read.
     */
    protected CsvSchema _schema;

    /**
     * Number of columns defined by schema.
     */
    protected int _columnCount = 0;

    protected boolean _cfgEmptyStringAsNull;

    /**
     * @since 2.18
     */
    protected boolean _cfgEmptyUnquotedStringAsNull;

    /*
    /**********************************************************************
    /* State
    /**********************************************************************
     */

    /**
     * Information about parser context, context in which
     * the next token is to be parsed (root, array, object).
     */
    protected SimpleStreamReadContext _streamReadContext;

    /**
     * Name of column that we exposed most recently, accessible after
     * {@link JsonToken#PROPERTY_NAME} as well as value tokens immediately
     * following property name.
     */
    protected String _currentName;

    /**
     * String value for the current column, if accessed.
     */
    protected String _currentValue;

    /**
     * Index of the column we are exposing
     */
    protected int _columnIndex;

    /**
     * Current logical state of the parser; one of <code>STATE_</code>
     * constants.
     */
    protected int _state = STATE_DOC_START;

    /**
     * We will hold on to decoded binary data, for duration of
     * current event, so that multiple calls to
     * {@link #getBinaryValue} will not need to decode data more
     * than once.
     */
    protected byte[] _binaryValue;

    /**
     * Pointer to the first character of the next array value to return.
     */
    protected int _arrayValueStart;

    /**
     * Contents of the cell, to be split into distinct array values.
     */
    protected String _arrayValue;

    protected String _arraySeparator;

    protected String _nullValue;
    
    /*
    /**********************************************************************
    /* Helper objects
    /**********************************************************************
     */

    /**
     * Thing that actually reads the CSV content
     */
    protected final CsvDecoder _reader;

    /**
     * Buffer that contains contents of all values after processing
     * of doubled-quotes, escaped characters.
     */
    protected final TextBuffer _textBuffer;

    protected ByteArrayBuilder _byteArrayBuilder;

    /*
    /**********************************************************************
    /* Life-cycle
    /**********************************************************************
     */

    public CsvParser(ObjectReadContext readCtxt, IOContext ioCtxt,
            int stdFeatures, int csvFeatures, CsvSchema schema,
            Reader reader)
    {
        super(readCtxt, ioCtxt, stdFeatures);
        if (reader == null) {
            throw new IllegalArgumentException("Can not pass `null` as `java.io.Reader` to read from");
        }
        _formatFeatures = csvFeatures;
        DupDetector dups = StreamReadFeature.STRICT_DUPLICATE_DETECTION.enabledIn(stdFeatures)
                ? DupDetector.rootDetector(this) : null;
        _streamReadContext = SimpleStreamReadContext.createRootContext(dups);
        _textBuffer = ioCtxt.constructReadConstrainedTextBuffer();
        _reader = new CsvDecoder(ioCtxt, this, reader, schema, _textBuffer,
                stdFeatures, csvFeatures);
        _setSchema(schema);
        _cfgEmptyStringAsNull = CsvReadFeature.EMPTY_STRING_AS_NULL.enabledIn(csvFeatures);
        _cfgEmptyUnquotedStringAsNull = CsvReadFeature.EMPTY_UNQUOTED_STRING_AS_NULL.enabledIn(csvFeatures);
    }

    /*
    /**********************************************************************
    /* Versioned                                                                             
    /**********************************************************************
     */

    @Override
    public Version version() {
        return PackageVersion.VERSION;
    }

    /*
    /**********************************************************************
    /* Overrides: capability introspection methods
    /**********************************************************************
     */

    @Override
    public boolean canReadObjectId() { return false; }

    @Override
    public boolean canReadTypeId() { return false; }

    @Override
    public JacksonFeatureSet<StreamReadCapability> streamReadCapabilities() {
        return STREAM_READ_CAPABILITIES;
    }

    /*
    /**********************************************************************
    /* Overridden methods
    /**********************************************************************
     */

    @Override
    public int releaseBuffered(Writer out) {
        try {
            return _reader.releaseBuffered(out);
        } catch (IOException e) {
            throw _wrapIOFailure(e);
        }
    }

    // Default close() works fine
    //
    // public void close() throws JacksonException

    @Override
    protected void _closeInput() throws IOException {
        _reader.close();
    }

    @Override
    protected void _releaseBuffers() { }

    /*
    /**********************************************************************
    /* Public API, configuration
    /**********************************************************************
     */

    /**
     * Method for enabling specified CSV feature
     * (check {@link CsvReadFeature} for list of features)
     */
    public JsonParser enable(CsvReadFeature f)
    {
        _formatFeatures |= f.getMask();
        _cfgEmptyStringAsNull = CsvReadFeature.EMPTY_STRING_AS_NULL.enabledIn(_formatFeatures);
        _cfgEmptyUnquotedStringAsNull = CsvReadFeature.EMPTY_UNQUOTED_STRING_AS_NULL.enabledIn(_formatFeatures);
        return this;
    }

    /**
     * Method for disabling specified  CSV feature
     * (check {@link CsvReadFeature} for list of features)
     */
    public JsonParser disable(CsvReadFeature f)
    {
        _formatFeatures &= ~f.getMask();
        _cfgEmptyStringAsNull = CsvReadFeature.EMPTY_STRING_AS_NULL.enabledIn(_formatFeatures);
        _cfgEmptyUnquotedStringAsNull = CsvReadFeature.EMPTY_UNQUOTED_STRING_AS_NULL.enabledIn(_formatFeatures);
        return this;
    }

    /**
     * Method for enabling or disabling specified CSV feature
     * (check {@link CsvReadFeature} for list of features)
     */
    public JsonParser configure(CsvReadFeature f, boolean state)
    {
        if (state) {
            enable(f);
        } else {
            disable(f);
        }
        return this;
    }

    /**
     * Method for checking whether specified CSV {@link CsvReadFeature}
     * is enabled.
     */
    public boolean isEnabled(CsvReadFeature f) {
        return (_formatFeatures & f.getMask()) != 0;
    }

    /**
     * Accessor for getting active schema definition: it may be
     * "empty" (no column definitions), but will never be null
     * since it defaults to an empty schema (and default configuration)
     */
    @Override
    public CsvSchema getSchema() {
        return _schema;
    }

    /*
    /**********************************************************************
    /* Location info
    /**********************************************************************
     */

    @Override
    public TokenStreamContext streamReadContext() { return _streamReadContext; }
    @Override public void assignCurrentValue(Object v) { _streamReadContext.assignCurrentValue(v); }
    @Override public Object currentValue() { return _streamReadContext.currentValue(); }

    @Override
    public TokenStreamLocation currentTokenLocation() {
        return _reader.getTokenLocation();
    }

    @Override
    public TokenStreamLocation currentLocation() {
        return _reader.getCurrentLocation();
    }

    @Override
    public Object streamReadInputSource() {
        return _reader.getInputSource();
    }

    /*
    /**********************************************************************
    /* Parsing, basic
    /**********************************************************************
     */

    /**
     * We need to override this method to support coercion from basic
     * String value into array, in cases where schema does not
     * specify actual type.
     */
    @Override
    public boolean isExpectedStartArrayToken() {
        if (_currToken == null) {
            return false;
        }
        switch (_currToken.id()) {
        case JsonTokenId.ID_PROPERTY_NAME:
        case JsonTokenId.ID_START_OBJECT:
        case JsonTokenId.ID_END_OBJECT:
        case JsonTokenId.ID_END_ARRAY:
            return false;
        case JsonTokenId.ID_START_ARRAY:
            return true;
        }
        // Otherwise: may coerce into array, iff we have essentially "untyped" column
        if (_columnIndex < _columnCount) {
            CsvSchema.Column column = _schema.column(_columnIndex);
            if (column.getType() == CsvSchema.ColumnType.STRING) {
                _startArray(column);
                return true;
            }
        }
        // 30-Dec-2014, tatu: Seems like it should be possible to allow this
        //   in non-array-wrapped case too (for 2.5), so let's try that:
        else if (_currToken == JsonToken.VALUE_STRING) {
            _startArray(CsvSchema.Column.PLACEHOLDER);
            return true;
        }
        return false;
    }

    @Override // since 2.12
    public boolean isExpectedNumberIntToken()
    {
        JsonToken t = _currToken;
        if (t == JsonToken.VALUE_STRING) {
            if (_reader.isExpectedNumberIntToken()) {
                _updateToken(JsonToken.VALUE_NUMBER_INT);
                return true;
            }
            return false;
        }
        return (t == JsonToken.VALUE_NUMBER_INT);
    }

    @Override
    public String currentName() {
        return _currentName;
    }

    @Override
    public JsonToken nextToken() throws JacksonException
    {
        _binaryValue = null;
        switch (_state) {
        case STATE_DOC_START:
            return _updateToken(_handleStartDoc());
        case STATE_RECORD_START:
            return _updateToken(_handleRecordStart());
        case STATE_NEXT_ENTRY:
            return _updateToken(_handleNextEntry());
        case STATE_NAMED_VALUE:
            return _updateToken(_handleNamedValue());
        case STATE_UNNAMED_VALUE:
            return _updateToken(_handleUnnamedValue());
        case STATE_IN_ARRAY:
            return _updateToken(_handleArrayValue());
        case STATE_SKIP_EXTRA_COLUMNS:
            // Need to just skip whatever remains
            return _skipUntilEndOfLine();
        case STATE_MISSING_NAME:
            return _updateToken(_handleMissingName());
        case STATE_MISSING_VALUE:
            return _updateToken(_handleMissingValue());
        case STATE_DOC_END:
            try {
                _reader.close();
            } catch (IOException e) {
                throw _wrapIOFailure(e);
            }
            if (_streamReadContext.inRoot()) {
                return null;
            }
            // should always be in array, actually... but:
            boolean inArray = _streamReadContext.inArray();
            _streamReadContext = _streamReadContext.clearAndGetParent();
            return inArray ? JsonToken.END_ARRAY : JsonToken.END_OBJECT;
        default:
            throw new IllegalStateException();
        }
    }

    /*
    /**********************************************************************
    /* Parsing, optimized methods
    /**********************************************************************
     */

    @Override
    public boolean nextName(SerializableString str) throws JacksonException
    {
        // Optimize for expected case of getting PROPERTY_NAME:
        if (_state == STATE_NEXT_ENTRY) {
            _binaryValue = null;
            final JsonToken t = _updateToken(_handleNextEntry());
            if (t == JsonToken.PROPERTY_NAME) {
                return str.getValue().equals(_currentName);
            }
            return false;
        }
        // unlikely, but verify just in case
        return (nextToken() == JsonToken.PROPERTY_NAME) && str.getValue().equals(currentName());
    }

    @Override
    public String nextName() throws JacksonException
    {
        // Optimize for expected case of getting PROPERTY_NAME:
        if (_state == STATE_NEXT_ENTRY) {
            _binaryValue = null;
            final JsonToken t = _updateToken(_handleNextEntry());
            if (t == JsonToken.PROPERTY_NAME) {
                return _currentName;
            }
            return null;
        }
        // unlikely, but verify just in case
        return (nextToken() == JsonToken.PROPERTY_NAME) ? currentName() : null;
    }

    @Override
    public String nextStringValue() throws JacksonException
    {
        _binaryValue = null;
        JsonToken t;
        if (_state == STATE_NAMED_VALUE) {
            t = _updateToken(_handleNamedValue());
            if (t == JsonToken.VALUE_STRING) {
                return _currentValue;
            }
        } else if (_state == STATE_UNNAMED_VALUE) {
            t = _updateToken(_handleUnnamedValue());
            if (t == JsonToken.VALUE_STRING) {
                return _currentValue;
            }
        } else {
            t = nextToken();
            if (t == JsonToken.VALUE_STRING) {
                return getString();
            }
        }
        return null;
    }

    /*
    /**********************************************************************
    /* Parsing, helper methods, regular
    /**********************************************************************
     */

    /**
     * Method called to process the expected header line
     */
    protected void _readHeaderLine() throws JacksonException {
        /*
            When the header line is present and the settings ask for it
            to be processed, two different options are possible:

            a) The schema has been populated.  In this case, build a new
               schema where the order matches the *actual* order in which
               the given CSV file offers its columns, if _schema.reordersColumns()
               is set to true; there are cases where the consumer of the CSV file
               knows about the columns but not necessarily the order in
               which they are defined.

            b) The schema has not been populated.  In this case, build a
               default schema based on the columns found in the header.
         */

        final int schemaColumnCount = _schema.size();
        if (schemaColumnCount > 0 && !_schema.reordersColumns()) {
            if (_schema.strictHeaders()) {
                String name;
                int ix = 0;
                for (CsvSchema.Column column : _schema._columns) {
                    name = _reader.nextString();
                    ++ix;
                    if (name == null) {
                        _reportError(String.format("Missing header column #%d, expecting \"%s\"", ix, column.getName()));
                    } else if (!column.getName().equals(name)) {
                        _reportError(String.format(
"Mismatched header column #%d: expected \"%s\", actual \"%s\"", ix, column.getName(), name));
                }
                }
                if ((name = _reader.nextString()) != null) {
                    _reportError(String.format("Extra header column \"%s\"", name));
                }
            } else {
                int allowed = MAX_COLUMNS;
                while (_reader.nextString() != null) {
                    // If we don't care about validation, just skip. But protect against infinite loop
                    if (--allowed < 0) {
                        _reportError("Internal error: skipped "+MAX_COLUMNS+" header columns");
                    }
                }
            }
            return;
        }

        // either the schema is empty or reorder columns flag is set
        String name;
        CsvSchema.Builder builder = _schema.rebuild().clearColumns();
        int count = 0;

        final boolean trimHeaderNames = CsvReadFeature.TRIM_HEADER_SPACES.enabledIn(_formatFeatures);
        while ((name = _reader.nextString()) != null) {
            // one more thing: always trim names, regardless of config settings
            // [dataformats-text#31]: Allow disabling of trimming
            if (trimHeaderNames) {
                name = name.trim();
            }
            // See if "old" schema defined type; if so, use that type...
            CsvSchema.Column prev = _schema.column(name);
            if (prev != null) {
                builder.addColumn(name, prev.getType());
            } else {
                builder.addColumn(name);
            }
            if (++count > MAX_COLUMNS) {
                _reportError("Internal error: reached maximum of "+MAX_COLUMNS+" header columns");
            }
        }

        // [dataformats-text#204]: Drop trailing empty name if so instructed
        if (CsvReadFeature.ALLOW_TRAILING_COMMA.enabledIn(_formatFeatures)) {
            builder.dropLastColumnIfEmpty();
        }

        // Ok: did we get any  columns?
        CsvSchema newSchema = builder.build();
        int newColumnCount = newSchema.size();
        if (newColumnCount < 2) { // 1 just because we may get 'empty' header name
            String first = (newColumnCount == 0) ? "" : newSchema.columnName(0).trim();
            if (first.isEmpty()) {
                _reportCsvReadError("Empty header line: can not bind data");
            }
        }
        // [dataformats-text#285]: Are we missing something?
        int diff = schemaColumnCount - newColumnCount;
        if ((diff > 0) && CsvReadFeature.FAIL_ON_MISSING_HEADER_COLUMNS.enabledIn(_formatFeatures)) {
            Set<String> oldColumnNames = new LinkedHashSet<>();
            _schema.getColumnNames(oldColumnNames);
            oldColumnNames.removeAll(newSchema.getColumnNames());
            _reportCsvReadError(String.format("Missing %d header column%s: [\"%s\"]",
                    diff, (diff == 1) ? "" : "s",
                            String.join("\",\"", oldColumnNames)));
        }

        // otherwise we will use what we got
        _setSchema(builder.build());
    }

    /**
     * Method called to handle details of initializing things to return
     * the very first token.
     */
    protected JsonToken _handleStartDoc() throws JacksonException
    {
        // also, if comments enabled, or skip empty lines, may need to skip leading ones
        _reader.skipLinesWhenNeeded();

        // First things first: are we expecting header line? If so, read, process
        if (_schema.usesHeader()) {
            _readHeaderLine();
            _reader.skipLinesWhenNeeded();
        }
        // and if we are to skip the first data line, skip it
        if (_schema.skipsFirstDataRow()) {
            _reader.skipLine();
            _reader.skipLinesWhenNeeded();
        }
        
        // Only one real complication, actually; empty documents (zero bytes).
        // Those have no entries. Should be easy enough to detect like so:
        final boolean wrapAsArray = CsvReadFeature.WRAP_AS_ARRAY.enabledIn(_formatFeatures);
        if (!_reader.hasMoreInput()) {
            _state = STATE_DOC_END;
            // but even empty sequence must still be wrapped in logical array
            if (wrapAsArray) {
                _streamReadContext = _reader.childArrayContext(_streamReadContext);
                return JsonToken.START_ARRAY;
            }
            return null;
        }
        
        if (wrapAsArray) {
            _streamReadContext = _reader.childArrayContext(_streamReadContext);
            _state = STATE_RECORD_START;
            return JsonToken.START_ARRAY;
        }
        // otherwise, same as regular new entry...
        return _handleRecordStart();
    }

    protected JsonToken _handleRecordStart() throws JacksonException
    {
        _columnIndex = 0;
        if (_columnCount == 0) { // no schema; exposed as an array
            _state = STATE_UNNAMED_VALUE;
            _streamReadContext = _reader.childArrayContext(_streamReadContext);
            return JsonToken.START_ARRAY;
        }
        // otherwise, exposed as an Object
        _streamReadContext = _reader.childObjectContext(_streamReadContext);
        _state = STATE_NEXT_ENTRY;
        return JsonToken.START_OBJECT;
    }

    protected JsonToken _handleNextEntry() throws JacksonException
    {
        // NOTE: only called when we do have real Schema
        String next;

        try {
            next = _reader.nextString();
        } catch (RuntimeException e) {
            // 12-Oct-2015, tatu: Need to resync here as well...
            _state = STATE_SKIP_EXTRA_COLUMNS;
            throw e;
        }

        if (next == null) { // end of record or input...
            // 16-Mar-2017, tatu: [dataformat-csv#137] Missing column(s)?
            if (_columnIndex < _columnCount) {
                return _handleMissingColumns();
            }
            return _handleObjectRowEnd();
        }
        if (_columnIndex >= _columnCount) {
            _currentValue = next;
            return _handleExtraColumn(next);
        }
        final CsvSchema.Column column = _schema.column(_columnIndex);
        _state = STATE_NAMED_VALUE;
        _currentName = column.getName();
        // 25-Aug-2024, tatu: [dataformats-text#442] May have value decorator
        CsvValueDecorator dec = column.getValueDecorator();
        if (dec == null) {
            _currentValue = next;
        } else {
            _currentValue = dec.undecorateValue(this, next);
        }
        return JsonToken.PROPERTY_NAME;
    }

    protected JsonToken _handleNamedValue() throws JacksonException
    {
        // 06-Oct-2015, tatu: During recovery, may get past all regular columns,
        //    but we also need to allow access past... sort of.
        if (_columnIndex < _columnCount) {
            CsvSchema.Column column = _schema.column(_columnIndex);
            ++_columnIndex;
            if (column.isArray()) {
                _startArray(column);
                return JsonToken.START_ARRAY;
            }
        }
        _state = STATE_NEXT_ENTRY;
        if (_isNullValue(_currentValue)) {
            return JsonToken.VALUE_NULL;
        }
        return JsonToken.VALUE_STRING;
    }

    protected JsonToken _handleUnnamedValue() throws JacksonException
    {
        String next = _reader.nextString();
        if (next == null) { // end of record or input...
            _streamReadContext = _streamReadContext.clearAndGetParent();
            if (!_reader.startNewLine()) { // end of whole thing...
                _state = STATE_DOC_END;
            } else {
                // no, just end of record
                _state = STATE_RECORD_START;
            }
            return JsonToken.END_ARRAY;
        }
        // state remains the same
        _currentValue = next;
        ++_columnIndex;
        if (_isNullValue(next)) {
            return JsonToken.VALUE_NULL;
        }
        return JsonToken.VALUE_STRING;
    }

    protected JsonToken _handleArrayValue() throws JacksonException
    {
        int offset = _arrayValueStart;
        if (offset < 0) { // just returned last value
            _streamReadContext = _streamReadContext.clearAndGetParent();
            // no arrays in arrays (at least for now), so must be back to named value
            _state = STATE_NEXT_ENTRY;
             return JsonToken.END_ARRAY;
        }
        int end = _arrayValue.indexOf(_arraySeparator, offset);

        if (end < 0) { // last value
            _arrayValueStart = end; // end marker, regardless

            // 11-Feb-2015, tatu: Tricky, As per [dataformat-csv#66]; empty Strings really
            //     should not emit any values. Not sure if trim
            if (offset == 0) { // no separator
                // for now, let's use trimming for checking
                if (_arrayValue.isEmpty() || _arrayValue.trim().isEmpty()) {
                    _streamReadContext = _streamReadContext.clearAndGetParent();
                    _state = STATE_NEXT_ENTRY;
                    return JsonToken.END_ARRAY;
                }
                _currentValue = _arrayValue;
            } else {
                _currentValue = _arrayValue.substring(offset);
            }
        } else {
            _currentValue = _arrayValue.substring(offset, end);
            _arrayValueStart = end+_arraySeparator.length();
        }
        if (isEnabled(CsvReadFeature.TRIM_SPACES)) {
            _currentValue = _currentValue.trim();
        }
        if (_isNullValue(_currentValue)) {
            return JsonToken.VALUE_NULL;
        }
        return JsonToken.VALUE_STRING;
    }

    /*
    /**********************************************************************
    /* Parsing, helper methods, extra column(s)
    /**********************************************************************
     */

    /**
     * Helper method called when an extraneous column value is found.
     * What happens then depends on configuration, but there are three
     * main choices: ignore value (and rest of line); expose extra value
     * as "any property" using configured name, or throw an exception.
     */
    protected JsonToken _handleExtraColumn(String value) throws JacksonException
    {
        // If "any properties" enabled, expose as such
        String anyProp = _schema.getAnyPropertyName();
        if (anyProp != null) {
            _currentName = anyProp;
            _state = STATE_NAMED_VALUE;
            return JsonToken.PROPERTY_NAME;
        }
        _currentName = null;
        // With [dataformat-csv#95] we'll simply ignore extra
        if (CsvReadFeature.IGNORE_TRAILING_UNMAPPABLE.enabledIn(_formatFeatures)) {
            _state = STATE_SKIP_EXTRA_COLUMNS;
            return _skipUntilEndOfLine();
        }

        // 14-Mar-2012, tatu: As per [dataformat-csv#1], let's allow one specific case
        // of extra: if we get just one all-whitespace entry, that can be just skipped
        _state = STATE_SKIP_EXTRA_COLUMNS;
        if (_columnIndex == _columnCount && CsvReadFeature.ALLOW_TRAILING_COMMA.enabledIn(_formatFeatures)) {
            value = value.trim();
            if (value.isEmpty()) {
                // if so, need to verify we then get the end-of-record;
                // easiest to do by just calling ourselves again...
                String next = _reader.nextString();
                if (next == null) { // should end of record or input
                    return _handleObjectRowEnd();
                }
            }
        }
        // 21-May-2015, tatu: Need to enter recovery mode, to skip remainder of the line
        return _reportCsvReadError("Too many entries: expected at most %d (value #%d (%d chars) \"%s\")",
                _columnCount, _columnIndex, value.length(), value);
    }

    /*
    /**********************************************************************
    /* Parsing, helper methods, missing column(s)
    /**********************************************************************
     */

    /**
     * Helper method called when end of row occurs before finding values for
     * all schema-specified columns.
     */
    protected JsonToken _handleMissingColumns() throws JacksonException
    {
        if (CsvReadFeature.FAIL_ON_MISSING_COLUMNS.enabledIn(_formatFeatures)) {
            // First: to allow recovery, set states to expose next line, if any
            _handleObjectRowEnd();
            // and then report actual problem
            return _reportCsvReadError("Not enough column values: expected %d, found %d",
                    _columnCount, _columnIndex);
        }
        if (CsvReadFeature.INSERT_NULLS_FOR_MISSING_COLUMNS.enabledIn(_formatFeatures)) {
            _state = STATE_MISSING_VALUE;
            _currentName = _schema.columnName(_columnIndex);
            _currentValue = null;
            return JsonToken.PROPERTY_NAME;
        }
        return _handleObjectRowEnd();
    }

    protected JsonToken _handleMissingName() throws JacksonException
    {
        if (++_columnIndex < _columnCount) {
            _state = STATE_MISSING_VALUE;
            _currentName = _schema.columnName(_columnIndex);
            // _currentValue already set to null earlier
            return JsonToken.PROPERTY_NAME;
        }
        return _handleObjectRowEnd();
    }

    protected JsonToken _handleMissingValue() throws JacksonException
    {
        _state = STATE_MISSING_NAME;
        return JsonToken.VALUE_NULL;
    }

    /*
    /**********************************************************************
    /* Parsing, helper methods: row end handling, recover
    /**********************************************************************
     */

    /**
     * Helper method called to handle details of state update when end of logical
     * record occurs.
     */
    protected final JsonToken _handleObjectRowEnd() throws JacksonException
    {
        _streamReadContext = _streamReadContext.clearAndGetParent();
        if (!_reader.startNewLine()) {
            _state = STATE_DOC_END;
        } else {
            _state = STATE_RECORD_START;
        }
        return JsonToken.END_OBJECT;
    }

    protected final JsonToken _skipUntilEndOfLine() throws JacksonException
    {
        while (_reader.nextString() != null) { }

        // But once we hit the end of the logical line, get out
        // NOTE: seems like we should always be within Object, but let's be conservative
        // and check just in case
        _streamReadContext = _streamReadContext.clearAndGetParent();
        _state = _reader.startNewLine() ? STATE_RECORD_START : STATE_DOC_END;
        return _updateToken(_streamReadContext.inArray()
                ? JsonToken.END_ARRAY : JsonToken.END_OBJECT);
    }

    /*
    /**********************************************************************
    /* String value handling
    /**********************************************************************
     */
    
    
    // For now we do not store char[] representation...
    @Override
    public boolean hasStringCharacters() {
        if (_currToken == JsonToken.PROPERTY_NAME) {
            return false;
        }
        return _textBuffer.hasTextAsCharacters();
    }

    @Override
    public String getString() throws JacksonException {
        if (_currToken == JsonToken.PROPERTY_NAME) {
            return _currentName;
        }
        // 08-Sep-2020, tatu: Used to check for empty String wrt EMPTY_STRING_AS_NULL
        //    here, but now demoted to actual "nextToken()" handling
        return _currentValue;
    }

    @Override
    public char[] getStringCharacters() throws JacksonException {
        if (_currToken == JsonToken.PROPERTY_NAME) {
            return _currentName.toCharArray();
        }
        return _textBuffer.contentsAsArray();
    }

    @Override
    public int getStringLength() throws JacksonException {
        if (_currToken == JsonToken.PROPERTY_NAME) {
            return _currentName.length();
        }
        return _textBuffer.size();
    }

    @Override
    public int getStringOffset() throws JacksonException {
        return 0;
    }

    @Override
    public int getString(Writer w) throws JacksonException {
        String value = (_currToken == JsonToken.PROPERTY_NAME) ?
                _currentName : _currentValue;
        if (value == null) {
            return 0;
        }
        try {
            w.write(value);
        } catch (IOException e) {
            throw _wrapIOFailure(e);
        }
        return value.length();
    }
    
    /*
    /**********************************************************************
    /* Binary (base64)
    /**********************************************************************
     */

    @Override
    public Object getEmbeddedObject() throws JacksonException {
        // in theory may access binary data using this method so...
        return _binaryValue;
    }

    @SuppressWarnings("resource")
    @Override
    public byte[] getBinaryValue(Base64Variant variant) throws JacksonException
    {
        if (_binaryValue == null) {
            if (_currToken != JsonToken.VALUE_STRING) {
                _reportError("Current token (%s) not VALUE_STRING, can not access as binary", _currToken);
            }
            ByteArrayBuilder builder = _getByteArrayBuilder();
            _decodeBase64(_currentValue, builder, variant);
            _binaryValue = builder.toByteArray();
        }
        return _binaryValue;
    }

    /*
    /**********************************************************************
    /* Number accessors
    /**********************************************************************
     */

    @Override
    public NumberType getNumberType() throws JacksonException {
        return _reader.getNumberType();
    }

    @Override
    public Number getNumberValue() throws JacksonException {
        return _reader.getNumberValue(false);
    }

    @Override
    public Number getNumberValueExact() throws JacksonException {
        return _reader.getNumberValue(true);
    }

    @Override
    public int getIntValue() throws JacksonException {
        return _reader.getIntValue();
    }

    @Override
    public long getLongValue() throws JacksonException {
        return _reader.getLongValue();
    }

    @Override
    public BigInteger getBigIntegerValue() throws JacksonException {
        return _reader.getBigIntegerValue();
    }

    @Override
    public float getFloatValue() throws JacksonException {
        return _reader.getFloatValue();
    }

    @Override
    public double getDoubleValue() throws JacksonException {
        return _reader.getDoubleValue();
    }

    @Override
    public BigDecimal getDecimalValue() throws JacksonException {
        return _reader.getDecimalValue();
    }

    // not yet supported...
    @Override
    public boolean isNaN() {
        return false;
    }

    /*
    /**********************************************************************
    /* Helper methods from base class
    /**********************************************************************
     */

    @Override
    protected void _handleEOF() throws StreamReadException {
        // I don't think there's problem with EOFs usually; except maybe in quoted stuff?
        _reportInvalidEOF(": expected closing quote character", null);
    }

    /*
    /**********************************************************************
    /* Internal methods, error reporting
    /**********************************************************************
     */

    /**
     * Method called when there is a problem related to mapping CSV columns
     * to property names, i.e. is CSV-specific aspect
     */
    public <T> T _reportCsvReadError(String msg, Object... args) throws JacksonException {
        if (args.length > 0) {
            msg = String.format(msg, args);
        }
        throw CsvReadException.from(this, msg, _schema);
    }

    public <T> T _reportUnexpectedCsvChar(int ch, String msg)  throws JacksonException {
        return super._reportUnexpectedChar(ch, msg);
    }

    @Override // just to make visible to decoder
    public <T> T _reportError(String msg) throws StreamReadException {
        return super._reportError(msg);
    }

    @Override // just to make visible to decoder
    public JacksonException _wrapIOFailure(IOException e)  {
        return super._wrapIOFailure(e);
    }

    /*
    /**********************************************************************
    /* Internal methods
    /**********************************************************************
     */

    protected void _setSchema(CsvSchema schema)
    {
        if (schema == null) {
            _schema = EMPTY_SCHEMA;
        } else {
            _schema = schema;
            _nullValue = _schema.getNullValueString();
        }
        _columnCount = _schema.size();            
        _reader.setSchema(_schema);
    }

    public ByteArrayBuilder _getByteArrayBuilder()
    {
        if (_byteArrayBuilder == null) {
            _byteArrayBuilder = new ByteArrayBuilder();
        } else {
            _byteArrayBuilder.reset();
        }
        return _byteArrayBuilder;
    }

    protected void _startArray(CsvSchema.Column column)
    {
        _updateToken(JsonToken.START_ARRAY);
        _streamReadContext = _streamReadContext.createChildArrayContext(_reader.getCurrentRow(),
                _reader.getCurrentColumn());
        _state = STATE_IN_ARRAY;
        _arrayValueStart = 0;
        _arrayValue = _currentValue;
        String sep = column.getArrayElementSeparator();
        if (sep.isEmpty()) {
            sep = _schema.getArrayElementSeparator();
        }
        _arraySeparator = sep;
    }

    /**
     * Helper method called to check whether specified String value should be considered
     * "null" value, if so configured.
     * 
     * @since 2.17.1
     */
    protected boolean _isNullValue(String value) {
        if (_nullValue != null) {
            if (_nullValue.equals(value)) {
                return true;
            }
        }
        if (_cfgEmptyStringAsNull && value.isEmpty()) {
            return true;
        }
        return _cfgEmptyUnquotedStringAsNull && value.isEmpty() && !_reader.isCurrentTokenQuoted();
    }
}
