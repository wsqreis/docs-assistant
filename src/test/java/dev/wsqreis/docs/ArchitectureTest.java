package dev.wsqreis.docs;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

@AnalyzeClasses(packages = "dev.wsqreis.docs", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /** Ingestion and search know nothing about answering, HTTP or MCP; web and mcp are thin adapters. */
    @ArchTest
    static final ArchRule layers = layeredArchitecture().consideringOnlyDependenciesInLayers()
            .layer("Web").definedBy("..docs.web..")
            .layer("Mcp").definedBy("..docs.mcp..")
            .layer("Eval").definedBy("..docs.eval..")
            .layer("Answer").definedBy("..docs.answer..")
            .layer("Search").definedBy("..docs.search..")
            .layer("Ingest").definedBy("..docs.ingest..")
            .whereLayer("Web").mayNotBeAccessedByAnyLayer()
            .whereLayer("Mcp").mayNotBeAccessedByAnyLayer()
            .whereLayer("Eval").mayNotBeAccessedByAnyLayer()
            .whereLayer("Answer").mayOnlyBeAccessedByLayers("Web")
            .whereLayer("Search").mayOnlyBeAccessedByLayers("Answer", "Web", "Mcp", "Eval")
            .whereLayer("Ingest").mayOnlyBeAccessedByLayers("Search", "Answer");
}
