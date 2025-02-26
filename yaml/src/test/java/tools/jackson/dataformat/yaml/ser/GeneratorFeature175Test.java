package tools.jackson.dataformat.yaml.ser;

import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.dataformat.yaml.ModuleTestBase;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLWriteFeature;

import static org.junit.jupiter.api.Assertions.*;

// 11-Nov-2020, tatu: Failing for 3.x until (and if) `snakeyaml-engine`
//    adds support ot make feature work.
public class GeneratorFeature175Test extends ModuleTestBase
{
    /*
    /**********************************************************
    /* Test methods
    /**********************************************************
     */

    // [dataformats-text#175]: arrays indentation with indicator
    @Test
    public void testArrayWithIndicatorIndentation() throws Exception {
        String yamlBefore = "---\n" +
            "tags:\n" +
            "  - tag:\n" +
            "      values:\n" +
            "        - \"first\"\n" +
            "        - \"second\"\n" +
            "      name: \"Mathematics\"";

        YAMLMapper defaultArrayMapper = YAMLMapper.builder()
            .enable(YAMLWriteFeature.INDENT_ARRAYS)
            .build();
        Map<?, ?> stuff = defaultArrayMapper.readValue(yamlBefore, Map.class);
        String defaultYaml = defaultArrayMapper.writeValueAsString(stuff);

        //default array indentation set indicator in separate line
        assertNotSame(yamlBefore, defaultYaml);

        YAMLMapper arrayWithIndicatorMapper = YAMLMapper.builder()
            .enable(YAMLWriteFeature.INDENT_ARRAYS)
            .enable(YAMLWriteFeature.INDENT_ARRAYS_WITH_INDICATOR)
            .build();

        String arrayWithIndicatorYaml = arrayWithIndicatorMapper.writeValueAsString(stuff);
        assertEquals(yamlBefore, arrayWithIndicatorYaml.trim());

        // and do it again to ensure it is parseable (no need to be identical)
        Map<?, ?> stuff2 = arrayWithIndicatorMapper.readValue(arrayWithIndicatorYaml, Map.class);
        assertNotNull(stuff2);
        assertEquals(stuff, stuff2);
    }
}
