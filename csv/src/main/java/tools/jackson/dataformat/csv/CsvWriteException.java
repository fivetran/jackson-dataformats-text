package tools.jackson.dataformat.csv;

import tools.jackson.core.exc.StreamWriteException;

/**
 * Format-specific exception used to indicate problems regarding low-level
 * generation issues specific to CSV content;
 * usually problems with field-to-column mapping as defined by {@link CsvSchema}.
 */
public class CsvWriteException
    extends StreamWriteException
{
    private static final long serialVersionUID = 3L;

    protected final CsvSchema _schema;

    public CsvWriteException(CsvGenerator gen, String msg, CsvSchema schema) {
        super(gen, msg);
        _schema = schema;
    }

    public static CsvWriteException from(CsvGenerator gen, String msg, CsvSchema schema) {
        return new CsvWriteException(gen, msg, schema);
    }

    public CsvSchema getSchema() { return _schema; }
}
