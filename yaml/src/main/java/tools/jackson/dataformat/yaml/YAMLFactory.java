package tools.jackson.dataformat.yaml;

import java.io.*;

import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.common.SpecVersion;

import tools.jackson.core.*;
import tools.jackson.core.base.TextualTSFactory;
import tools.jackson.core.io.IOContext;

import tools.jackson.dataformat.yaml.util.StringQuotingChecker;

@SuppressWarnings("resource")
public class YAMLFactory
    extends TextualTSFactory
    implements java.io.Serializable
{
    private static final long serialVersionUID = 1L;

    /**
     * Name used to identify YAML format.
     * (and returned by {@link #getFormatName()}
     */
    public final static String FORMAT_NAME_YAML = "YAML";

    /**
     * Bitfield (set of flags) of all generator features that are enabled
     * by default.
     */
    protected final static int DEFAULT_YAML_PARSER_FEATURE_FLAGS = YAMLReadFeature.collectDefaults();

    /**
     * Bitfield (set of flags) of all generator features that are enabled
     * by default.
     */
    protected final static int DEFAULT_YAML_GENERATOR_FEATURE_FLAGS = YAMLWriteFeature.collectDefaults();

    /*
    /**********************************************************************
    /* Configuration
    /**********************************************************************
     */

    /**
     * YAML version for underlying generator to follow, if specified.
     */
    protected final SpecVersion _version;

    /**
     * Helper object used to determine whether property names, String values
     * must be quoted or not.
     */
    protected final StringQuotingChecker _quotingChecker;

    /**
     * Configuration for underlying parser to follow, if specified;
     * left as {@code null} for backwards compatibility (which means
     * whatever default settings {@code snakeyaml-engine} deems best).
     */
    protected final LoadSettings _loadSettings;    

    /**
     * Configuration for underlying generator to follow, if specified;
     * left as {@code null} for backwards compatibility (which means
     * the dumper options are derived based on {@link YAMLWriteFeature}s).
     * <p>
     *     These {@link YAMLWriteFeature}s are ignored if you provide your own DumperOptions:
     *     <ul>
     *         <li>{@code YAMLGenerator.Feature.ALLOW_LONG_KEYS}</li>
     *         <li>{@code YAMLGenerator.Feature.CANONICAL_OUTPUT}</li>
     *         <li>{@code YAMLGenerator.Feature.INDENT_ARRAYS}</li>
     *         <li>{@code YAMLGenerator.Feature.INDENT_ARRAYS_WITH_INDICATOR}</li>
     *         <li>{@code YAMLGenerator.Feature.SPLIT_LINES}</li>
     *         <li>{@code YAMLGenerator.Feature.USE_PLATFORM_LINE_BREAKS}</li>
     *     </ul>
     * </p>
     */
    protected final DumpSettings _dumpSettings;

    /*
    /**********************************************************************
    /* Factory construction, configuration
    /**********************************************************************
     */

    /**
     * Default constructor used to create factory instances that may be
     * used to construct an instance with default settings, instead of
     * using {@link YAMLFactoryBuilder}.
     */
    public YAMLFactory()
    {
        super(StreamReadConstraints.defaults(), StreamWriteConstraints.defaults(),
                ErrorReportConfiguration.defaults(),
                DEFAULT_YAML_PARSER_FEATURE_FLAGS, DEFAULT_YAML_GENERATOR_FEATURE_FLAGS);
        // 26-Jul-2013, tatu: Seems like we should force output as 1.1 but
        //  that adds version declaration which looks ugly...
        _version = null;
        _quotingChecker = StringQuotingChecker.Default.instance();
        _loadSettings = null;
        _dumpSettings = null;
    }

    public YAMLFactory(YAMLFactory src)
    {
        super(src);
        _version = src._version;
        _quotingChecker = src._quotingChecker;
        _loadSettings = src._loadSettings;
        _dumpSettings = src._dumpSettings;
    }

    /**
     * Constructors used by {@link YAMLFactoryBuilder} for instantiation.
     *
     * @since 3.0
     */
    protected YAMLFactory(YAMLFactoryBuilder b)
    {
        super(b);
        _version = b.yamlVersionToWrite();
        _quotingChecker = b.stringQuotingChecker();
        _loadSettings = b.loadSettings();
        _dumpSettings = b.dumpSettings();
    }

    @Override
    public YAMLFactoryBuilder rebuild() {
        return new YAMLFactoryBuilder(this);
    }

    /**
     * Main factory method to use for constructing {@link YAMLFactory} instances with
     * different configuration.
     */
    public static YAMLFactoryBuilder builder() {
        return new YAMLFactoryBuilder();
    }

    @Override
    public YAMLFactory copy() {
        return new YAMLFactory(this);
    }

    /**
     * Instances are immutable so just return `this`
     */
    @Override
    public TokenStreamFactory snapshot() {
        return this;
    }

    /*
    /**********************************************************************
    /* Serializable overrides
    /**********************************************************************
     */

    /**
     * Method that we need to override to actually make restoration go
     * through constructors etc.
     */
    protected Object readResolve() {
        return new YAMLFactory(this);
    }

    /*
    /**********************************************************************
    /* Capability introspection
    /**********************************************************************
     */

    @Override
    public Version version() {
        return PackageVersion.VERSION;
    }

    // No, we can't make use of char[] optimizations
    @Override
    public boolean canUseCharArrays() { return false; }

    @Override
    public boolean canParseAsync() {
        // 31-May-2017, tatu: No async parsing yet
        return false;
    }

    /*
    /**********************************************************************
    /* Format support
    /**********************************************************************
     */

    @Override
    public String getFormatName() {
        return FORMAT_NAME_YAML;
    }

    @Override
    public boolean canUseSchema(FormatSchema schema) {
        return false;
    }

    @Override
    public Class<YAMLReadFeature> getFormatReadFeatureType() {
        return YAMLReadFeature.class;
    }

    @Override
    public Class<YAMLWriteFeature> getFormatWriteFeatureType() {
        return YAMLWriteFeature.class;
    }

    @Override
    public int getFormatReadFeatures() { return _formatReadFeatures; }

    @Override
    public int getFormatWriteFeatures() { return _formatWriteFeatures; }

    public boolean isEnabled(YAMLReadFeature f) {
        return (_formatReadFeatures & f.getMask()) != 0;
    }

    public boolean isEnabled(YAMLWriteFeature f) {
        return (_formatWriteFeatures & f.getMask()) != 0;
    }

    /*
    /**********************************************************************
    /* Factory methods: parsers
    /**********************************************************************
     */

    @Override
    protected YAMLParser _createParser(ObjectReadContext readCtxt, IOContext ioCtxt,
            InputStream in) {
        return new YAMLParser(readCtxt, ioCtxt,
                _getBufferRecycler(),
                readCtxt.getStreamReadFeatures(_streamReadFeatures),
                readCtxt.getFormatReadFeatures(_formatReadFeatures),
                _loadSettings,
                _createReader(in, null, ioCtxt));
    }

    @Override
    protected YAMLParser _createParser(ObjectReadContext readCtxt, IOContext ioCtxt,
            Reader r) {
        return new YAMLParser(readCtxt, ioCtxt,
                _getBufferRecycler(), 
                readCtxt.getStreamReadFeatures(_streamReadFeatures),
                readCtxt.getFormatReadFeatures(_formatReadFeatures),
                _loadSettings,
                r);
    }

    @Override
    protected YAMLParser _createParser(ObjectReadContext readCtxt, IOContext ioCtxt,
            char[] data, int offset, int len,
            boolean recyclable) {
        return new YAMLParser(readCtxt, ioCtxt, _getBufferRecycler(),
                readCtxt.getStreamReadFeatures(_streamReadFeatures),
                readCtxt.getFormatReadFeatures(_formatReadFeatures),
                _loadSettings,
                new CharArrayReader(data, offset, len));
    }

    @Override
    protected YAMLParser _createParser(ObjectReadContext readCtxt, IOContext ioCtxt,
            byte[] data, int offset, int len) {
        return new YAMLParser(readCtxt, ioCtxt, _getBufferRecycler(),
                readCtxt.getStreamReadFeatures(_streamReadFeatures),
                readCtxt.getFormatReadFeatures(_formatReadFeatures),
                _loadSettings,
                _createReader(data, offset, len, null, ioCtxt));
    }

    @Override
    protected JsonParser _createParser(ObjectReadContext readCtxt, IOContext ioCtxt,
            DataInput input) {
        return _unsupported();
    }

    /*
    /**********************************************************************
    /* Factory methods: generators
    /**********************************************************************
     */

    @Override
    protected YAMLGenerator _createGenerator(ObjectWriteContext writeCtxt,
            IOContext ioCtxt, Writer out)
    {
        return new YAMLGenerator(writeCtxt, ioCtxt,
                writeCtxt.getStreamWriteFeatures(_streamWriteFeatures),
                writeCtxt.getFormatWriteFeatures(_formatWriteFeatures),
                _quotingChecker,
                out, _version, _dumpSettings);
    }

    @Override
    protected YAMLGenerator _createUTF8Generator(ObjectWriteContext writeCtxt,
            IOContext ioCtxt, OutputStream out)
    {
        return _createGenerator(writeCtxt, ioCtxt,
                _createWriter(ioCtxt, out, JsonEncoding.UTF8));
    }

    @Override
    protected Writer _createWriter(IOContext ioCtxt, OutputStream out, JsonEncoding enc) {
        if (enc == JsonEncoding.UTF8) {
            return new UTF8Writer(out);
        }
        try {
            return new OutputStreamWriter(out, enc.getJavaName());
        } catch (IOException e) {
            throw _wrapIOFailure(e);
        }
    }

    /*
    /**********************************************************************
    /* Internal methods
    /**********************************************************************
     */

    protected Reader _createReader(InputStream in, JsonEncoding enc, IOContext ctxt)
    {
        if (enc == null) {
            enc = JsonEncoding.UTF8;
        }
        // default to UTF-8 if encoding missing
        if (enc == JsonEncoding.UTF8) {
            boolean autoClose = ctxt.isResourceManaged() || isEnabled(StreamReadFeature.AUTO_CLOSE_SOURCE);
            return new UTF8Reader(in, autoClose);
//          return new InputStreamReader(in, UTF8);
        }
        try {
            return new InputStreamReader(in, enc.getJavaName());
        } catch (IOException e) {
            throw _wrapIOFailure(e);
        }
    }

    protected Reader _createReader(byte[] data, int offset, int len,
            JsonEncoding enc, IOContext ctxt)
    {
        if (enc == null) {
            enc = JsonEncoding.UTF8;
        }
        // default to UTF-8 if encoding missing
        if (enc == null || enc == JsonEncoding.UTF8) {
            return new UTF8Reader(data, offset, len, true);
        }
        ByteArrayInputStream in = new ByteArrayInputStream(data, offset, len);
        try {
            return new InputStreamReader(in, enc.getJavaName());
        } catch (IOException e) {
            throw _wrapIOFailure(e);
        }
    }
}
