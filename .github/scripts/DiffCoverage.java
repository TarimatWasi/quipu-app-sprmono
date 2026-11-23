import java.io.IOException;
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
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * QP-SPRMONO-CI-01: every production line a change adds or edits, and every branch on it, is
 * covered by the tests. The package minimums of JaCoCo (pom.xml) keep the whole from falling; this
 * keeps each change whole. Run after {@code mvn verify}, from the root of the repository:
 *
 * <pre>java .github/scripts/DiffCoverage.java target/site/jacoco/jacoco.xml origin/development</pre>
 *
 * <p>A single source file run by the JDK itself: no dependency to install in CI.
 */
public final class DiffCoverage {

  private static final Pattern FILE = Pattern.compile("^\\+\\+\\+ b/(.+)$");
  private static final Pattern HUNK = Pattern.compile("^@@ .* \\+(\\d+)(?:,(\\d+))? @@");

  private record Line(int missedInstructions, int coveredInstructions, int missedBranches) {}

  public static void main(String[] args) throws Exception {
    if (args.length != 2) {
      System.err.println("usage: DiffCoverage <jacoco.xml> <base ref>");
      System.exit(2);
    }
    Map<String, Set<Integer>> changed = changedLines(args[1]);
    Map<String, Map<Integer, Line>> report = report(Path.of(args[0]));
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
    System.out.printf("Changed executable lines: %d, not fully covered: %d%n", lines, uncovered.size());
    uncovered.forEach(System.out::println);
    if (!uncovered.isEmpty()) {
      System.exit(1);
    }
  }

  /** The lines each production source gained or changed since the merge base with the base ref. */
  private static Map<String, Set<Integer>> changedLines(String base)
      throws IOException, InterruptedException {
    Process git =
        new ProcessBuilder(
                "git", "diff", "--unified=0", "--no-color", base + "...HEAD", "--", "src/main/java")
            .redirectErrorStream(true)
            .start();
    String diff = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (git.waitFor() != 0) {
      System.err.println(diff);
      System.exit(2);
    }
    Map<String, Set<Integer>> changed = new TreeMap<>();
    String file = null;
    for (String line : diff.split("\n")) {
      Matcher name = FILE.matcher(line);
      if (name.matches()) {
        file = name.group(1).endsWith(".java") ? name.group(1) : null;
        continue;
      }
      Matcher hunk = HUNK.matcher(line);
      if (file != null && hunk.find()) {
        int start = Integer.parseInt(hunk.group(1));
        int count = hunk.group(2) == null ? 1 : Integer.parseInt(hunk.group(2));
        Set<Integer> numbers = changed.computeIfAbsent(file, key -> new TreeSet<>());
        for (int i = start; i < start + count; i++) {
          numbers.add(i);
        }
      }
    }
    return changed;
  }

  /** The coverage of each line of each source, by its path from the root of the repository. */
  private static Map<String, Map<Integer, Line>> report(Path xml) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    // The report names a DTD that is not shipped: it is neither fetched nor validated.
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
    factory.setExpandEntityReferences(false);
    Document document = factory.newDocumentBuilder().parse(Files.newInputStream(xml));
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
                  Integer.parseInt(line.getAttribute("mi")),
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
