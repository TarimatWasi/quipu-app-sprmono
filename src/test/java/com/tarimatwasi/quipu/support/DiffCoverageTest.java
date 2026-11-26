package com.tarimatwasi.quipu.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.support.DiffCoverage.Line;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The gate of CI on what a change covers: what it reads, and what it lets through. */
class DiffCoverageTest {

  private static final String FILE = "src/main/java/com/example/Foo.java";

  @Test
  void theChangedLinesOfTheJavaSourcesAreReadFromTheDiff() {
    String diff =
        """
        diff --git a/src/main/java/com/example/Foo.java b/src/main/java/com/example/Foo.java
        --- a/src/main/java/com/example/Foo.java
        +++ b/src/main/java/com/example/Foo.java
        @@ -10,0 +11,3 @@ class Foo {
        +  int a;
        @@ -20 +23 @@ class Foo {
        @@ -30,2 +33,0 @@ class Foo {
        --- a/src/main/java/com/example/notes.txt
        +++ b/src/main/java/com/example/notes.txt
        @@ -1 +1,4 @@
        """;

    assertThat(DiffCoverage.changedLines(diff)).containsOnlyKeys(FILE);
    // A hunk of zero lines (only removals) adds nothing; one without a count is one line.
    assertThat(DiffCoverage.changedLines(diff).get(FILE)).containsExactly(11, 12, 13, 23);
  }

  @Test
  void theReportGivesTheCoverageOfEachLineByThePathOfItsSource() throws Exception {
    String xml =
        """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
        <report name="app">
          <package name="com/example">
            <sourcefile name="Foo.java">
              <line nr="11" mi="0" ci="3" mb="1" cb="1"/>
              <line nr="12" mi="2" ci="0" mb="0" cb="0"/>
            </sourcefile>
          </package>
        </report>
        """;

    var report =
        DiffCoverage.report(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

    assertThat(report.get(FILE))
        .containsEntry(11, new Line(3, 1))
        .containsEntry(12, new Line(0, 0));
  }

  /** A line no test runs, or a branch of it no test takes, fails; a comment does not count. */
  @Test
  void aLineNotRunOrABranchNotTakenFailsTheChange() {
    var changed = Map.of(FILE, Set.of(11, 12, 13, 14));
    var report = Map.of(FILE, Map.of(11, new Line(3, 0), 12, new Line(0, 0), 13, new Line(2, 1)));
    var out = new ByteArrayOutputStream();

    boolean passed =
        DiffCoverage.check(changed, report, new PrintStream(out, true, StandardCharsets.UTF_8));

    assertThat(passed).isFalse();
    assertThat(out.toString(StandardCharsets.UTF_8))
        .contains("Changed executable lines: 3, not fully covered: 2")
        .contains(FILE + ":12 is not run by any test")
        .contains(FILE + ":13 misses 1 branch(es)")
        .doesNotContain(":11 ")
        .doesNotContain(":14");
  }

  @Test
  void aChangeFullyCoveredPasses() {
    var out = new ByteArrayOutputStream();

    assertThat(
            DiffCoverage.check(
                Map.of(FILE, Set.of(11), "src/main/java/Gone.java", Set.of(1)),
                Map.of(FILE, Map.of(11, new Line(1, 0))),
                new PrintStream(out, true, StandardCharsets.UTF_8)))
        .isTrue();
    assertThat(out.toString(StandardCharsets.UTF_8)).contains("not fully covered: 0");
  }
}
