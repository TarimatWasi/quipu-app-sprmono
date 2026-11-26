package com.tarimatwasi.quipu.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;

/** Structure and coding rules of the guide over production code (BE-SPR-ARQ, DI, CFG, COD...). */
@AnalyzeClasses(
    packages = ArchitectureRules.ROOT,
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest static final ArchTests RULES = ArchTests.in(ArchitectureRules.class);

  @ArchTest static final ArchTests EVENTS = ArchTests.in(EventRules.class);
}
