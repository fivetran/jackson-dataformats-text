package tools.jackson.dataformat.yaml;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests to try to ensure that SnakeYAML exceptions are not leaked,
 * both because they are problematic on OSGi runtimes (depending on 
 * whether shading is used) and because it is generally a bad idea
 * to leak implementation details.
 */
public class ExceptionConversionTest extends ModuleTestBase
{
    @Test
    public void testSimpleParsingLeakage() throws Exception
    {
        YAMLMapper mapper = newObjectMapper();
        try {
             mapper.readTree("foo:\nbar: true\n  baz: false");
             fail("Should not pass with invalid YAML");
        } catch (org.snakeyaml.engine.v2.exceptions.ScannerException e) {
            fail("Internal exception type: "+e);
        } catch (JacksonYAMLParseException e) { // as of 2.8, this is the type to expect
            // (subtype of StreamReadException)
            verifyException(e, "mapping values are not allowed here");
        } catch (Exception e) {
            fail("Unknown exception: "+e);
        }
    }
}
