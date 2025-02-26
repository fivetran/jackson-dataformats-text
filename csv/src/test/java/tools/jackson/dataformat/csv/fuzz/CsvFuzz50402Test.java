package tools.jackson.dataformat.csv.fuzz;

import org.junit.jupiter.api.Test;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.csv.ModuleTestBase;

public class CsvFuzz50402Test extends ModuleTestBase
{
    // https://bugs.chromium.org/p/oss-fuzz/issues/detail?id=50402
    @Test
    public void testReadBoundary50402() throws Exception
    {
        ObjectMapper mapper = mapperForCsv();
        byte[] input = readResource("/data/fuzz-50402.csv");
        try {
            mapper.readTree(input);
            // Ok; don't care about content, just buffer reads
        } catch (JacksonException e) {
            verifyException(e, "foo");
        }
    }
}
