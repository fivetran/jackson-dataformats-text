package tools.jackson.dataformat.csv.deser;

import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.MappingIterator;

import tools.jackson.dataformat.csv.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for cases where one more of schema-declared columns is
 * missing; various handling choices include "null-injection"
 * as well as failure (throw exception) and just skipping (default).
 */
public class MissingColumns285Test extends ModuleTestBase
{
    /*
    /**********************************************************************
    /* Test methods
    /**********************************************************************
     */

    private final CsvMapper MAPPER = mapperForCsv();
    private final CsvSchema csvSchema = CsvSchema.builder()
            .setUseHeader(true)
            .setReorderColumns(true)
            .addColumn("name")
            .addColumn("age")
            .build();
    private final String CSV = "name\nRoger\n";

    // [dataformats-text#285]: fail by default
    @Test
    public void testFailOnMissingWithReorder() throws Exception
    {
        // Need to have it all inside try block since construction tries to read
        // the first token
        try {
            MappingIterator<Map<String, Object>> it = MAPPER
                    .readerFor(Map.class)
                    .with(csvSchema)
                    .readValues(CSV);
            it.nextValue();
            fail("Should not pass with missing columns");
        } catch (CsvReadException e) {
            verifyException(e, "Missing 1 header column: [\"age\"]");
        }
    }

    // [dataformats-text#285]: optionally allow
    @Test
    public void testAllowMissingWithReorder() throws Exception
    {
        MappingIterator<Map<String, Object>> it = MAPPER
                .readerFor(Map.class)
                .with(csvSchema)
                .without(CsvReadFeature.FAIL_ON_MISSING_HEADER_COLUMNS)
                .readValues(CSV);
        assertTrue(it.hasNext());
        Map<?, ?> result = it.nextValue();
        assertEquals(1, result.size());
        assertEquals("Roger", result.get("name"));
        assertNull(result.get("age"));
    }
}
