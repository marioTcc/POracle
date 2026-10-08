package com.mtcc.project.extractor.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.treesitter.TreeSitterHtml;
import org.treesitter.TreeSitterJava;
import org.treesitter.TreeSitterJavascript;
import org.treesitter.TreeSitterKotlin;
import org.treesitter.TreeSitterPython;
import org.treesitter.TreeSitterSql;
import org.treesitter.TreeSitterTypescript;

import java.util.Map;

@Configuration
public class TreeSitterConfig {

    private static final String JAVA_QUERY = """
            (class_declaration) @chunk
            (method_declaration) @chunk
            (constructor_declaration) @chunk
            (static_initializer) @chunk
            (interface_declaration) @chunk
            (record_declaration) @chunk
            (enum_declaration) @chunk
            """;

    private static final String KOTLIN_QUERY = """
            (class_declaration) @chunk
            (object_declaration) @chunk
            (companion_object) @chunk
            (function_declaration) @chunk
            (secondary_constructor) @chunk
            (source_file (property_declaration) @chunk)
            """;

    private static final String PYTHON_QUERY = """
            (function_definition) @chunk
            (class_definition) @chunk
            """;

    private static final String JAVASCRIPT_QUERY = """
            (function_declaration) @chunk
            (generator_function_declaration) @chunk
            (class_declaration) @chunk
            (method_definition) @chunk
            (program (lexical_declaration) @chunk)
            (program (variable_declaration) @chunk)
            (program (expression_statement) @chunk)
            (export_statement declaration: (lexical_declaration) @chunk)
            (export_statement declaration: (variable_declaration) @chunk)
            (export_statement value: (_) @chunk)
            """;

    private static final String TYPESCRIPT_QUERY = JAVASCRIPT_QUERY + """
            (abstract_class_declaration) @chunk
            (interface_declaration) @chunk
            (type_alias_declaration) @chunk
            (enum_declaration) @chunk
            """;

    private static final String SQL_QUERY = """
            (statement) @chunk
            """;

    private static final String HTML_QUERY = """
            [(element) (script_element) (style_element)] @chunk
            """;

    @Bean
    public Map<String, TSLanguageConfig> tsConfig() {
        final TSLanguageConfig javaConfig = new TSLanguageConfig(new TreeSitterJava(), JAVA_QUERY);
        final TSLanguageConfig kotlinConfig = new TSLanguageConfig(new TreeSitterKotlin(), KOTLIN_QUERY);
        final TSLanguageConfig pythonConfig = new TSLanguageConfig(new TreeSitterPython(), PYTHON_QUERY);
        final TSLanguageConfig javascriptConfig = new TSLanguageConfig(new TreeSitterJavascript(), JAVASCRIPT_QUERY);
        final TSLanguageConfig typescriptConfig = new TSLanguageConfig(new TreeSitterTypescript(), TYPESCRIPT_QUERY);
        final TSLanguageConfig sqlConfig = new TSLanguageConfig(new TreeSitterSql(), SQL_QUERY);
        final TSLanguageConfig htmlConfig = new TSLanguageConfig(new TreeSitterHtml(), HTML_QUERY);

        return Map.ofEntries(
                Map.entry("java", javaConfig),
                Map.entry("kt", kotlinConfig),
                Map.entry("kts", kotlinConfig),
                Map.entry("py", pythonConfig),
                Map.entry("js", javascriptConfig),
                Map.entry("jsx", javascriptConfig),
                Map.entry("ts", typescriptConfig),
                Map.entry("sql", sqlConfig),
                Map.entry("ddl", sqlConfig),
                Map.entry("html", htmlConfig),
                Map.entry("htm", htmlConfig));
    }
}
