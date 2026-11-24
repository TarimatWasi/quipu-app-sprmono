package com.tarimatwasi.quipu.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * QP-SPRMONO-CI-01: every production line a change adds or edits, and every branch on it, is
 * covered by the tests. The minimums of JaCoCo (pom.xml) keep the packages and the whole from
 * falling; this keeps each change whole. CI runs it after {@code mvn verify}, as a single source
 * file of the JDK, from the root of the repository:
 *
 * <pre>
 * java src/test/java/com/tarimatwasi/quipu/support/DiffCoverage.java \
 *     target/site/jacoco/jacoco.xml origin/development
 * </pre>
 *
 * <p>It uses only the JDK, so CI installs nothing; the build compiles and checks it as test code,
 * and {@code DiffCoverageTest} covers what it reads and decides.
 */
public final class DiffCoverage {

  private static final Pattern FILE = Pattern.compile("^\\+\\+\\+ b/(.+)$");
  private static final Pattern HUNK = Pattern.compile("^@@ .* \\+(\\d+)(?:,(\\d+))? @@");

  /** The coverage of one line, as JaCoCo reports it. */
  record Line(int coveredInstructions, int missedBranches) {}

  private DiffCoverage() {}

  public static void main(String[] args)
      throws IOException, InterruptedException, ParserConfigurationException, SAXException {
    if (args.length != 2) {
      System.err.println("usage: DiffCoverage <jacoco.xml> <base ref>");
      System.exit(2);
    }
    Process git =
        new ProcessBuilder(
                "git",
                "diff",
                "--unified=0",
                "--no-color",
                args[1] + "...HEAD",
                "--",
                "src/main/java")
            .redirectErrorStream(true)
            .start();
    String diff = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (git.waitFor() != 0) {
      System.err.println(diff);
      System.exit(2);
    }
    Map<String, Map<Integer, Line>> report;
    try (InputStream xml = Files.newInputStream(Path.of(args[0]))) {
      report = report(xml);
    }
    System.exit(check(changedLines(diff), report, System.out) ? 0 : 1);
  }

  /** Prints what is not fully covered; true if nothing is. */
  static boolean check(
      Map<String, Set<Integer>> changed, Map<String, Map<Integer, Line>> report, PrintStream out) {
    List<String> uncovered = new ArrayList<>();
    int lines = 0;
    for (var file : changed.entrySet()) {
      Map<Integer, Line> covered = report.getOrDefault(file.getKey(), Map.of());
      for (int number : file.getValue()) {
        Line line = covered.get(number);
        if (line == null) {
          continue; // not executable: a comment, a declaration, a blank line
        }
        lines++;
        if (line.coveredInstructions() == 0) {
          uncovered.add(file.getKey() + ":" + number + " is not run by any test");
        } else if (line.missedBranches() > 0) {
          uncovered.add(
              file.getKey() + ":" + number + " misses " + line.missedBranches() + " branch(es)");
        }
      }
    }
    out.printf("Changed executable lines: %d, not fully covered: %d%n", lines, uncovered.size());
    uncovered.forEach(out::println);
    return uncovered.isEmpty();
  }

  /** The lines each Java source gained or changed, from a diff with no context lines. */
  static Map<String, Set<Integer>> changedLines(String diff) {
    Map<String, Set<Integer>> changed = new TreeMap<>();
    String file = null;
    for (String line : diff.lines().toList()) {
      Matcher name = FILE.matcher(line);
      if (name.matches()) {
        file = name.group(1).endsWith(".java") ? name.group(1) : null;
        continue;
      }
      Matcher hunk = HUNK.matcher(line);
      if (file != null && hunk.find()) {
        int start = Integer.parseInt(hunk.group(1));
        String length = hunk.group(2);
        int count = length == null ? 1 : Integer.parseInt(length);
        Set<Integer> numbers = changed.computeIfAbsent(file, key -> new TreeSet<>());
        for (int i = start; i < start + count; i++) {
          numbers.add(i);
        }
      }
    }
    return changed;
  }

  /** The coverage of each line of each source, by its path from the root of the repository. */
  static Map<String, Map<Integer, Line>> report(InputStream xml)
      throws ParserConfigurationException, SAXException, IOException {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    // The report names a DTD that is not shipped: it is neither fetched nor validated.
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    factory.setExpandEntityReferences(false);
    Document document = factory.newDocumentBuilder().parse(xml);
    Map<String, Map<Integer, Line>> report = new TreeMap<>();
    NodeList packages = document.getElementsByTagName("package");
    for (int p = 0; p < packages.getLength(); p++) {
      Element pkg = (Element) packages.item(p);
      NodeList sources = pkg.getElementsByTagName("sourcefile");
      for (int s = 0; s < sources.getLength(); s++) {
        Element source = (Element) sources.item(s);
        Map<Integer, Line> lines = new TreeMap<>();
        NodeList entries = source.getElementsByTagName("line");
        for (int l = 0; l < entries.getLength(); l++) {
          Element line = (Element) entries.item(l);
          lines.put(
              Integer.parseInt(line.getAttribute("nr")),
              new Line(
                  Integer.parseInt(line.getAttribute("ci")),
                  Integer.parseInt(line.getAttribute("mb"))));
        }
        report.put(
            "src/main/java/" + pkg.getAttribute("name") + "/" + source.getAttribute("name"), lines);
      }
    }
    return report;
  }
}
