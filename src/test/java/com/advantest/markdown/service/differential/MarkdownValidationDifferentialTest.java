/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.differential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import com.advantest.markdown.service.MarkdownService;
import com.advantest.markdown.service.validation.ValidationIssue;

/**
 * Compares this service with a recorded validation run of the FluentMark Eclipse plug-ins.
 * 
 * <p>The extraction has to reproduce what FluentMark reports today. The unit tests state that rule
 * by rule on small documents; this test states it for a whole corpus of real documents at once, and
 * so it is also the measure of how far the extraction has come.</p>
 * 
 * <p>One test case per validated file, named after the file. A case compares all findings of that
 * file as one text, so that the failure shows the missing and the surplus findings side by side
 * instead of stopping at the first one. Files the recorded run validated without a finding are test
 * cases as well — they are where a rule that reports too much shows up.</p>
 * 
 * <p>Only the findings of the rules that have been ported are compared, see
 * {@link PortedValidationRules}. Everything the service does report is compared, though: a finding
 * it invents is a difference, whatever it is about.</p>
 * 
 * <p>The corpus and the recording are not part of this repository and are not published, so their
 * locations have no default and have to be given as system properties. The build therefore leaves
 * this test out — the surefire plugin excludes the package — and running it means asking for it:</p>
 * 
 * <pre>
 * mvn test -Dtest=MarkdownValidationDifferentialTest -DfailIfNoSpecifiedTests=false \
 *          -Dmarkdown.corpus.root=&lt;corpus&gt; -Dmarkdown.recording.dir=&lt;recording&gt;
 * </pre>
 * 
 * <p>In the IDE the two belong into the VM arguments of the launch configuration, not into the
 * program arguments. A missing or wrong location fails the test instead of skipping it, because a
 * skipped comparison is indistinguishable from one that found no difference.</p>
 * 
 * <p>The optional properties <code>markdown.corpus.include</code> and
 * <code>markdown.corpus.exclude</code> take a regular expression each and are matched against the
 * path of a file relative to the corpus root. A file outside the selection is skipped rather than
 * compared, so that a run over a part of the corpus keeps stating what it left out.</p>
 */
public class MarkdownValidationDifferentialTest {

	private static final String CORPUS_ROOT_PROPERTY = "markdown.corpus.root";
	private static final String RECORDING_DIRECTORY_PROPERTY = "markdown.recording.dir";
	private static final String INCLUDE_PROPERTY = "markdown.corpus.include";
	private static final String EXCLUDE_PROPERTY = "markdown.corpus.exclude";

	private static final Summary SUMMARY = new Summary();

	private final MarkdownService service = new MarkdownService();

	/**
	 * A finding of either side, reduced to what both sides can state about it.
	 */
	private record ComparableFinding(
			int lineNumber,
			int startOffset,
			int endOffset,
			String severity,
			String issueTypeId,
			String message) {

		private static final Comparator<ComparableFinding> ORDER = Comparator
				.comparingInt(ComparableFinding::startOffset)
				.thenComparingInt(ComparableFinding::endOffset)
				.thenComparing(ComparableFinding::issueTypeId)
				.thenComparing(ComparableFinding::message);

		String render() {
			return String.format("line %d [%d,%d] %s %s %s",
					this.lineNumber, this.startOffset, this.endOffset, this.severity, this.issueTypeId,
					escapeWhitespace(this.message));
		}

		/**
		 * Keeps a finding on a line of its own, so that a difference stays readable. Messages quote
		 * the validated document and may therefore contain tabs and line breaks.
		 */
		private static String escapeWhitespace(String message) {
			return message.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n");
		}
	}

	@TestFactory
	public Stream<DynamicTest> reportsWhatTheRecordedRunReported() {
		Path corpusRoot = directoryFromProperty(CORPUS_ROOT_PROPERTY);
		Path recordingDirectory = directoryFromProperty(RECORDING_DIRECTORY_PROPERTY);

		ValidationRecording recording = ValidationRecording.readFrom(recordingDirectory);
		Predicate<String> inScope = scopeFromProperties();

		return recording.validatedFiles().stream()
				.map(file -> DynamicTest.dynamicTest(file, () -> {
					if (!inScope.test(file)) {
						SUMMARY.countSkippedFile();
						Assumptions.abort("Excluded from this run by " + INCLUDE_PROPERTY
								+ " or " + EXCLUDE_PROPERTY + ".");
					}

					compare(file, corpusRoot, recording);
				}));
	}

	private void compare(String file, Path corpusRoot, ValidationRecording recording) throws IOException {
		Path markdownFile = corpusRoot.resolve(file);
		assertTrue(Files.isRegularFile(markdownFile),
				"The recording names " + file + ", but there is no such file under the corpus root "
						+ corpusRoot + ".");

		List<ComparableFinding> expectedFindings = recordedFindings(file, recording);
		List<ComparableFinding> actualFindings = producedFindings(markdownFile);

		SUMMARY.count(expectedFindings, actualFindings);

		assertEquals(render(expectedFindings), render(actualFindings),
				"The service does not report " + file + " the way the recorded run did.");
	}

	private static List<ComparableFinding> recordedFindings(String file, ValidationRecording recording) {
		return recording.findingsOf(file).stream()
				.flatMap(finding -> PortedValidationRules.issueTypeIdOf(finding.message()).stream()
						.map(issueTypeId -> new ComparableFinding(finding.lineNumber(), finding.startOffset(),
								finding.endOffset(), finding.severity(), issueTypeId, finding.message())))
				.sorted(ComparableFinding.ORDER)
				.toList();
	}

	private List<ComparableFinding> producedFindings(Path markdownFile) throws IOException {
		// the recorded offsets count every character of the file, carriage returns included,
		// so the source code must reach the service exactly as it is stored
		String markdownSourceCode = new String(Files.readAllBytes(markdownFile), StandardCharsets.UTF_8);

		return this.service.validateMarkdown(markdownSourceCode).stream()
				.map(MarkdownValidationDifferentialTest::toComparableFinding)
				.sorted(ComparableFinding.ORDER)
				.toList();
	}

	private static ComparableFinding toComparableFinding(ValidationIssue issue) {
		return new ComparableFinding(issue.lineNumber(), issue.startOffset(), issue.endOffset(),
				issue.severity().name(), issue.issueTypeId(), issue.message());
	}

	private static String render(List<ComparableFinding> findings) {
		return findings.stream()
				.map(ComparableFinding::render)
				.collect(Collectors.joining("\n"));
	}

	/**
	 * Reads a directory location from a system property, and fails if it is not there.
	 * 
	 * <p>An unset or wrong location is a configuration mistake, not a reason to pass: a silently
	 * skipped comparison looks exactly like one that found no difference. The default build does not
	 * run this test at all, see the class comment, so failing here costs nobody anything.</p>
	 */
	private static Path directoryFromProperty(String propertyName) {
		String value = System.getProperty(propertyName);

		assertNotNull(value, () -> missingConfiguration(propertyName, "is not set"));
		assertFalse(value.isBlank(), () -> missingConfiguration(propertyName, "is empty"));

		Path directory = Path.of(value);
		assertTrue(Files.isDirectory(directory),
				() -> missingConfiguration(propertyName, "is not a directory: " + value));

		return directory;
	}

	private static String missingConfiguration(String propertyName, String problem) {
		return "The system property " + propertyName + " " + problem + ", so this service cannot be"
				+ " compared with a recorded validation run of the FluentMark Eclipse plug-ins."
				+ " Pass -D" + CORPUS_ROOT_PROPERTY + "=<corpus> and -D" + RECORDING_DIRECTORY_PROPERTY
				+ "=<recording> as VM arguments, not as program arguments. Neither location belongs"
				+ " into this repository, so neither has a default.";
	}

	private static Predicate<String> scopeFromProperties() {
		Predicate<String> included = patternFromProperty(INCLUDE_PROPERTY)
				.<Predicate<String>>map(pattern -> file -> pattern.matcher(file).find())
				.orElse(file -> true);
		Predicate<String> excluded = patternFromProperty(EXCLUDE_PROPERTY)
				.<Predicate<String>>map(pattern -> file -> pattern.matcher(file).find())
				.orElse(file -> false);

		return included.and(excluded.negate());
	}

	private static Optional<Pattern> patternFromProperty(String propertyName) {
		String value = System.getProperty(propertyName);

		return value == null || value.isBlank() ? Optional.empty() : Optional.of(Pattern.compile(value));
	}

	@AfterAll
	public static void reportHowFarTheExtractionHasCome() {
		SUMMARY.print();
	}

	/**
	 * Counts what the run found, so that the outcome is a measure of the progress and not only a
	 * list of failed files.
	 */
	private static final class Summary {

		private int comparedFiles;
		private int matchingFiles;
		private int skippedFiles;
		private final Map<String, int[]> countsPerIssueType = new LinkedHashMap<>();

		synchronized void countSkippedFile() {
			this.skippedFiles++;
		}

		synchronized void count(List<ComparableFinding> expected, List<ComparableFinding> actual) {
			this.comparedFiles++;

			List<ComparableFinding> missing = new ArrayList<>(expected);
			List<ComparableFinding> surplus = new ArrayList<>();
			for (ComparableFinding finding : actual) {
				if (!missing.remove(finding)) {
					surplus.add(finding);
				}
			}

			if (missing.isEmpty() && surplus.isEmpty()) {
				this.matchingFiles++;
			}

			expected.forEach(finding -> count(finding, 0));
			missing.forEach(finding -> count(finding, 1));
			surplus.forEach(finding -> count(finding, 2));
		}

		private void count(ComparableFinding finding, int index) {
			this.countsPerIssueType.computeIfAbsent(finding.issueTypeId(), issueType -> new int[3])[index]++;
		}

		synchronized void print() {
			if (this.comparedFiles == 0 && this.skippedFiles == 0) {
				return;
			}

			StringBuilder report = new StringBuilder();
			report.append("\nDifferential validation over the recorded corpus\n");
			report.append(String.format("  files: %d compared, %d identical, %d skipped%n",
					this.comparedFiles, this.matchingFiles, this.skippedFiles));

			this.countsPerIssueType.entrySet().stream()
					.sorted(Map.Entry.comparingByKey())
					.forEach(entry -> report.append(String.format("  %-60s recorded %4d, missing %4d, surplus %4d%n",
							entry.getKey(), entry.getValue()[0], entry.getValue()[1], entry.getValue()[2])));

			System.out.println(report);
		}

	}

}
