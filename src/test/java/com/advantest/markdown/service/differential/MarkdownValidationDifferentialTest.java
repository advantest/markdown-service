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
import com.advantest.markdown.service.differential.ExpectedDeviations.Deviation;
import com.advantest.markdown.service.validation.ValidationIssue;
import com.advantest.resources.LocalFileSystemResource;
import com.advantest.resources.Resource;

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
 * it invents is a difference, whatever it is about. The differences that were decided are declared
 * in {@link ExpectedDeviations}, taken out of the comparison and counted in the report, so that the
 * comparison keeps failing on every difference nobody decided.</p>
 * 
 * <p>The corpus and the recording are not part of this repository and are not published. They
 * default to where they lie on the machine this comparison is developed on, and a machine holding
 * them elsewhere names them as system properties. The build leaves this test out either way &mdash;
 * the surefire plugin excludes the package &mdash; so running it means asking for it:</p>
 * 
 * <pre>
 * mvn test -Dtest=MarkdownValidationDifferentialTest -DfailIfNoSpecifiedTests=false \
 *          [-Dmarkdown.corpus.root=&lt;corpus&gt; -Dmarkdown.recording.dir=&lt;recording&gt;]
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
 * 
 * <p>The optional property <code>markdown.corpus.checkWebAddresses</code> switches the rules about
 * web addresses on. They ask the network, so a run without it stays offline, and its recorded
 * findings then count as not covered rather than as missing. A run with it needs the network, and
 * the corpus names addresses inside the company network, which only answer from inside it:</p>
 * 
 * <pre>
 * mvn test -Dtest=MarkdownValidationDifferentialTest -DfailIfNoSpecifiedTests=false \
 *          [-Dmarkdown.corpus.root=&lt;corpus&gt; -Dmarkdown.recording.dir=&lt;recording&gt;] \
 *          -Dmarkdown.corpus.checkWebAddresses=true
 * </pre>
 * 
 * <p>Such a run reports what the recorded run reported, apart from two kinds of difference that are
 * neither of them a defect of a rule. An address a validator of the FluentMark extensions claims is
 * asked about here and was not asked about there, so it is reported as surplus until those
 * validators exist. And an address is a moving target: one that answers slowly enough is reported
 * by whoever asked it on the slower day.</p>
 */
public class MarkdownValidationDifferentialTest {

	private static final String CORPUS_ROOT_PROPERTY = "markdown.corpus.root";
	private static final String RECORDING_DIRECTORY_PROPERTY = "markdown.recording.dir";
	private static final String INCLUDE_PROPERTY = "markdown.corpus.include";
	private static final String EXCLUDE_PROPERTY = "markdown.corpus.exclude";
	private static final String WEB_ADDRESSES_PROPERTY = "markdown.corpus.checkWebAddresses";

	/**
	 * Where the corpus and the recording lie on the machine this comparison is developed on, so
	 * that running the test means naming the test and nothing else.
	 * 
	 * <p>Both are checkouts of repositories of the FluentMark Eclipse plug-ins, and this package is
	 * the one place allowed to know that. A machine holding them elsewhere overrides the default
	 * with the system property, and the recording taken outside the company network lies next to
	 * the one taken inside it.</p>
	 */
	private static final String DEFAULT_CORPUS_ROOT = "C:\\work\\git-repos\\fluentmark-extensions\\tests";

	private static final String DEFAULT_RECORDING_DIRECTORY = "C:\\work\\git-repos\\fluentmark-extensions"
			+ "\\com.advantest.fluentmark.extensions.validations.tests\\validation-results-recording"
			+ "\\inside-intranet";

	private static final boolean WEB_ADDRESSES_ARE_CHECKED = Boolean.getBoolean(WEB_ADDRESSES_PROPERTY);

	private static final PortedValidationRules PORTED_RULES =
			PortedValidationRules.of(WEB_ADDRESSES_ARE_CHECKED);

	private static final Summary SUMMARY = new Summary();

	/**
	 * The service every case validates with, one for the whole run.
	 * 
	 * <p>Where the run asks about a web address, one checker serves every document, so that an
	 * address named by several of them is asked about once.</p>
	 */
	private static final MarkdownService SERVICE = serviceOfThisRun();

	private final MarkdownService service = SERVICE;

	private static MarkdownService serviceOfThisRun() {
		MarkdownService.Builder builder = MarkdownService.builder().withLocalFileSystemResourceResolver();

		return WEB_ADDRESSES_ARE_CHECKED ? builder.withUriReachabilityCheck().build() : builder.build();
	}

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
		Path corpusRoot = directoryFromProperty(CORPUS_ROOT_PROPERTY, DEFAULT_CORPUS_ROOT);
		Path recordingDirectory =
				directoryFromProperty(RECORDING_DIRECTORY_PROPERTY, DEFAULT_RECORDING_DIRECTORY);

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

		// the recorded offsets count every character of the file, carriage returns included,
		// so the source code must reach the service exactly as it is stored
		String markdownSourceCode = new String(Files.readAllBytes(markdownFile), StandardCharsets.UTF_8);

		List<RecordedFinding> recordedFindings = recording.findingsOf(file);
		List<ComparableFinding> expectedFindings =
				withoutExpectedDeviations(toComparableFindings(recordedFindings), markdownSourceCode);
		List<ComparableFinding> actualFindings =
				withoutExpectedSurpluses(
						withoutDifferentlyWordedFindings(producedFindings(markdownSourceCode, markdownFile),
								markdownSourceCode),
						markdownSourceCode);

		SUMMARY.count(recordedFindings, expectedFindings, actualFindings);

		assertEquals(render(expectedFindings), render(actualFindings),
				"The service does not report " + file + " the way the recorded run did.");
	}

	/**
	 * Drops the recorded findings that this service is meant not to produce, so that only the
	 * differences nobody decided fail the comparison. Each of them is counted, so that the report
	 * keeps stating how many findings were let go and why.
	 */
	private static List<ComparableFinding> withoutExpectedDeviations(List<ComparableFinding> recordedFindings,
			String markdownSourceCode) {

		List<ComparableFinding> expected = new ArrayList<>(recordedFindings.size());

		for (ComparableFinding finding : recordedFindings) {
			Optional<Deviation> deviation = ExpectedDeviations.explainingAbsenceOf(contextOf(finding, markdownSourceCode));

			if (deviation.isPresent()) {
				SUMMARY.countDeviation(deviation.get());
				continue;
			}

			Optional<Deviation> differentMessage =
					ExpectedDeviations.explainingDifferentMessageOf(contextOf(finding, markdownSourceCode));

			if (differentMessage.isPresent()) {
				SUMMARY.countDeviation(differentMessage.get());
			} else {
				expected.add(finding);
			}
		}

		return expected;
	}

	/**
	 * Drops the findings this service words differently on purpose, so that they are not reported
	 * as surplus. Their recorded counterparts are dropped and counted by
	 * {@link #withoutExpectedDeviations(List, String)}.
	 */
	private static List<ComparableFinding> withoutDifferentlyWordedFindings(List<ComparableFinding> findings,
			String markdownSourceCode) {

		return findings.stream()
				.filter(finding -> ExpectedDeviations
						.explainingDifferentMessageOf(contextOf(finding, markdownSourceCode)).isEmpty())
				.toList();
	}

	/**
	 * Drops the findings this service produces although the recorded run has none of them, where
	 * that was decided. Each of them is counted, so that the report keeps stating how many findings
	 * were let go and why.
	 */
	private static List<ComparableFinding> withoutExpectedSurpluses(List<ComparableFinding> findings,
			String markdownSourceCode) {

		List<ComparableFinding> compared = new ArrayList<>(findings.size());

		for (ComparableFinding finding : findings) {
			Optional<Deviation> deviation =
					ExpectedDeviations.explainingSurplusOf(contextOf(finding, markdownSourceCode));

			if (deviation.isPresent()) {
				SUMMARY.countDeviation(deviation.get());
			} else {
				compared.add(finding);
			}
		}

		return compared;
	}

	private static ExpectedDeviations.RecordedFindingInContext contextOf(ComparableFinding finding,
			String markdownSourceCode) {

		return new ExpectedDeviations.RecordedFindingInContext(finding.issueTypeId(),
				finding.startOffset(), finding.endOffset(), markdownSourceCode);
	}

	private static List<ComparableFinding> toComparableFindings(List<RecordedFinding> recordedFindings) {
		return recordedFindings.stream()
				.flatMap(finding -> {
					String message = RewordedMessages.asThisServiceWordsIt(finding);

					return PORTED_RULES.issueTypeIdOf(message).stream()
							.map(issueTypeId -> new ComparableFinding(finding.lineNumber(), finding.startOffset(),
									finding.endOffset(), finding.severity(), issueTypeId, message));
				})
				.sorted(ComparableFinding.ORDER)
				.toList();
	}

	private List<ComparableFinding> producedFindings(String markdownSourceCode, Path markdownFile) {
		Resource documentResource = LocalFileSystemResource.of(markdownFile);

		return this.service.validateMarkdown(markdownSourceCode, documentResource).stream()
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
	private static Path directoryFromProperty(String propertyName, String defaultLocation) {
		String value = System.getProperty(propertyName, defaultLocation);

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
				+ " into this repository, so both default to where they lie on the machine this"
				+ " comparison is developed on.";
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

		private static final int MESSAGE_SHAPE_LENGTH = 90;

		private int comparedFiles;
		private int matchingFiles;
		private int skippedFiles;
		private final Map<String, int[]> countsPerIssueType = new LinkedHashMap<>();
		private final Map<String, Integer> notCoveredPerMessage = new LinkedHashMap<>();
		private int notCoveredFindings;
		private final Map<Deviation, Integer> deviations = new LinkedHashMap<>();

		synchronized void countSkippedFile() {
			this.skippedFiles++;
		}

		synchronized void countDeviation(Deviation deviation) {
			this.deviations.merge(deviation, 1, Integer::sum);
		}

		synchronized void count(List<RecordedFinding> recorded, List<ComparableFinding> expected,
				List<ComparableFinding> actual) {
			this.comparedFiles++;

			recorded.stream()
					.filter(finding -> PORTED_RULES
							.issueTypeIdOf(RewordedMessages.asThisServiceWordsIt(finding)).isEmpty())
					.forEach(this::countNotCovered);

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

		/**
		 * Counts a recorded finding that no ported rule accounts for, under the shape of its
		 * message. The rule behind it has no name here yet, and the message is all the recording
		 * says about it, so the shapes are the list of what is still to be ported.
		 * 
		 * <p>Whether a rule accounts for a finding is asked of the message as this service words
		 * it, the same way the comparison asks it. Asking it of the recorded wording would count a
		 * finding of a rule that is ported but worded differently as an open one, and the list
		 * would then name work that is done.</p>
		 */
		private void countNotCovered(RecordedFinding finding) {
			this.notCoveredFindings++;
			this.notCoveredPerMessage.merge(
					String.format("%-10s %s", bundleOf(finding), messageShape(finding.message())), 1, Integer::sum);
		}

		private static String bundleOf(RecordedFinding finding) {
			return finding.bundle().contains(".extensions.") ? "extensions" : "fluentmark";
		}

		/**
		 * Reduces a message to what it says about its rule. Everything a message quotes from the
		 * validated document is replaced, both because it varies from finding to finding and
		 * because it must not be printed: neither the corpus nor its content is public.
		 */
		private static String messageShape(String message) {
			String shape = message
					.replaceAll("\\s+", " ")
					.replaceAll("https?://[^\\s'\"<>)\\]]+", "<url>")
					.replaceAll("'[^']*'", "'<text>'")
					.replaceAll("\"[^\"]*\"", "\"<text>\"")
					.replaceAll("\\S*[/\\\\]\\S*", "<path>")
					.replaceAll("\\d+", "#")
					.trim();

			return shape.length() <= MESSAGE_SHAPE_LENGTH
					? shape
					: shape.substring(0, MESSAGE_SHAPE_LENGTH) + "...";
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

			appendNotCovered(report);
			appendDeviations(report);

			System.out.println(report);
		}

		/**
		 * Lists the findings that the two runs differ about on purpose, whichever side has them.
		 * They are left out of the comparison, so without this the report would claim a parity that
		 * was in part decided rather than reached.
		 */
		private void appendDeviations(StringBuilder report) {
			if (this.deviations.isEmpty()) {
				return;
			}

			int total = this.deviations.values().stream().mapToInt(Integer::intValue).sum();
			report.append(String.format("%n  findings the two runs differ about on purpose: %d%n", total));

			this.deviations.entrySet().stream()
					.sorted(Map.Entry.comparingByKey(Comparator.comparing(Deviation::id)))
					.forEach(entry -> report.append(String.format("  %4d %s %s%n",
							entry.getValue(), entry.getKey().id(), entry.getKey().description())));
		}

		/**
		 * Lists the recorded findings that no ported rule accounts for, so that the report states
		 * what is still missing and not only how well the ported rules do. The rules behind them are
		 * unnamed here, so they are grouped by the shape of their message.
		 */
		private void appendNotCovered(StringBuilder report) {
			int covered = this.countsPerIssueType.values().stream().mapToInt(counts -> counts[0]).sum();

			report.append(String.format("%n  findings of the compared files: %d covered by a ported rule,"
					+ " %d not covered yet%n", covered, this.notCoveredFindings));

			this.notCoveredPerMessage.entrySet().stream()
					.sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
							.thenComparing(Map.Entry.comparingByKey()))
					.forEach(entry -> report.append(String.format("  %4d %s%n",
							entry.getValue(), entry.getKey())));
		}

	}

}
