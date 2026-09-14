/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation.corpus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.advantest.markdown.service.MarkdownService;
import com.advantest.markdown.service.validation.MarkdownIssueTypes;
import com.advantest.markdown.service.validation.ValidationIssue;
import com.advantest.markdown.service.validation.uri.UriReachability;
import com.advantest.markdown.service.validation.uri.UriReachabilityChecker;
import com.advantest.resources.LocalFileSystemResource;
import com.advantest.resources.Resource;

/**
 * Holds every rule of this service against a set of documents written for it.
 * 
 * <p>A unit test asks one rule about the input it was written for, which says that the rule works
 * but not that it still says what it said: a message losing a word, a rule losing its identifier or
 * a finding moving by one character passes every one of them. This test reads documents that
 * declare what they provoke, in full, and fails on any difference &ndash; a finding nobody declared
 * as well as a declaration nothing produces.</p>
 * 
 * <p>The documents lie in <code>src/test/resources/validation-corpus</code> and belong to this
 * repository: they name nothing that is not in them, they need no network, and how they declare
 * what they expect is written down in the README lying next to them.</p>
 * 
 * <p>The addresses of a document are answered by {@link #reachabilityOfTheCorpus()}, which knows
 * what the few addresses of this corpus answer and says so without asking anybody. The rules about
 * an address that stays silent and about one that answers that there is nothing there are therefore
 * checked here as well, and the run stays as fast and as repeatable as one that never leaves the
 * machine.</p>
 */
class ValidationCorpusTest {

	private static final String CORPUS_ON_THE_CLASS_PATH = "validation-corpus";

	/**
	 * An address that nothing answers for, and one that answers that there is nothing there.
	 * 
	 * <p>Every other address of the corpus is there, so that a document is about the rule it was
	 * written for and about nothing else.</p>
	 */
	private static final String ADDRESS_THAT_STAYS_SILENT = "https://silent.example.org/guide";
	private static final String ADDRESS_THAT_IS_GONE = "https://example.org/gone";

	private final MarkdownService service = MarkdownService.builder()
			.withLocalFileSystemResourceResolver()
			.withUriReachabilityCheck(reachabilityOfTheCorpus())
			.build();

	@TestFactory
	Stream<DynamicTest> reportsWhatTheDocumentsDeclare() throws IOException {
		Path documents = documentsDirectory();

		return Files.list(documents)
				.filter(ValidationCorpusTest::isMarkdownDocument)
				.sorted()
				.map(document -> DynamicTest.dynamicTest(document.getFileName().toString(),
						() -> compare(document)));
	}

	private static boolean isMarkdownDocument(Path file) {
		return file.getFileName().toString().endsWith(".md");
	}

	/**
	 * Says which rule of this service no document of the corpus provokes, so that a rule added
	 * later is not left without a document saying what it reports.
	 */
	@Test
	void isProvokedByEveryRuleThisServiceHas() throws IOException, IllegalAccessException {
		Set<String> declaredIssueTypeIds = declaredFindingsOfTheCorpus().stream()
				.map(ExpectedFinding::issueTypeId)
				.collect(Collectors.toSet());

		List<String> rulesNoDocumentProvokes = new ArrayList<>();
		for (Field constant : MarkdownIssueTypes.class.getDeclaredFields()) {
			if (!Modifier.isStatic(constant.getModifiers()) || !Modifier.isPublic(constant.getModifiers())
					|| constant.getType() != String.class) {
				continue;
			}

			String issueTypeId = (String) constant.get(null);
			if (!declaredIssueTypeIds.contains(issueTypeId)) {
				rulesNoDocumentProvokes.add(issueTypeId);
			}
		}

		assertEquals(List.of(), rulesNoDocumentProvokes,
				"No document of the corpus declares these rules. Please write a document provoking each of"
						+ " them, or extend one that is about the same thing.");
	}

	private static List<ExpectedFinding> declaredFindingsOfTheCorpus() throws IOException {
		try (Stream<Path> documents = Files.list(documentsDirectory())) {
			List<ExpectedFinding> declaredFindings = new ArrayList<>();

			for (Path document : documents.filter(ValidationCorpusTest::isMarkdownDocument).toList()) {
				declaredFindings.addAll(ExpectedFinding.declaredIn(
						new String(Files.readAllBytes(document), StandardCharsets.UTF_8)));
			}

			return declaredFindings;
		}
	}

	private void compare(Path document) throws IOException {
		String markdownSourceCode = new String(Files.readAllBytes(document), StandardCharsets.UTF_8);

		List<ExpectedFinding> declaredFindings = ExpectedFinding.declaredIn(markdownSourceCode);
		List<ExpectedFinding> reportedFindings = reportedFindings(markdownSourceCode, document);

		assertEquals(render(declaredFindings), render(reportedFindings),
				"The service does not report " + document.getFileName() + " the way the document declares it."
						+ " A line that is reported but not declared can be copied into the document, without"
						+ " the line number in front of it, above the line it belongs to.");
	}

	private List<ExpectedFinding> reportedFindings(String markdownSourceCode, Path document) {
		List<ValidationIssue> issues = withoutDocumentLocation(markdownSourceCode)
				? this.service.validateMarkdown(markdownSourceCode)
				: this.service.validateMarkdown(markdownSourceCode, resourceOf(document));

		return issues.stream()
				.map(issue -> asExpectedFinding(issue, markdownSourceCode))
				.sorted(Comparator.comparingInt(ExpectedFinding::lineNumber)
						.thenComparingInt(ExpectedFinding::column)
						.thenComparing(ExpectedFinding::issueTypeId))
				.toList();
	}

	private static boolean withoutDocumentLocation(String markdownSourceCode) {
		return markdownSourceCode.lines()
				.anyMatch(line -> line.strip().equals(ExpectedFinding.WITHOUT_DOCUMENT_LOCATION));
	}

	private static Resource resourceOf(Path document) {
		return LocalFileSystemResource.of(document);
	}

	private static ExpectedFinding asExpectedFinding(ValidationIssue issue, String markdownSourceCode) {
		int lineStart = markdownSourceCode.lastIndexOf('\n', issue.startOffset() - 1) + 1;

		return new ExpectedFinding(issue.lineNumber(), issue.startOffset() - lineStart + 1,
				markdownSourceCode.substring(issue.startOffset(), issue.endOffset()), issue.severity().name(),
				issue.issueTypeId(), withoutTheLocationOfThisMachine(issue.message()));
	}

	/**
	 * Writes the place the corpus lies in as <code>{corpus}</code> and its separators as forward
	 * slashes, so that a message naming a resolved path says the same thing on every machine.
	 * 
	 * <p>A message of this corpus carries no backslash of its own, so turning every one of them
	 * into a forward slash changes nothing but a path.</p>
	 */
	private static String withoutTheLocationOfThisMachine(String message) {
		return message.replace(corpusDirectory().toString(), "{corpus}").replace('\\', '/');
	}

	private static String render(List<ExpectedFinding> findings) {
		return findings.stream()
				.map(ExpectedFinding::render)
				.collect(Collectors.joining("\n"));
	}

	/**
	 * Answers for the addresses of this corpus without asking anybody, so that the rules about a
	 * web address are checked without a network and answer the same thing every day.
	 */
	private static UriReachabilityChecker reachabilityOfTheCorpus() {
		return targetUri -> {
			String address = targetUri.toString();

			if (ADDRESS_THAT_STAYS_SILENT.equals(address)) {
				return CompletableFuture.completedFuture(
						new UriReachability.NotReached("no host of that name is known"));
			}
			if (ADDRESS_THAT_IS_GONE.equals(address)) {
				return CompletableFuture.completedFuture(new UriReachability.Answered(404));
			}

			return CompletableFuture.completedFuture(new UriReachability.Answered(200));
		};
	}

	private static Path documentsDirectory() {
		return corpusDirectory().resolve("documents");
	}

	private static Path corpusDirectory() {
		URL corpus = ValidationCorpusTest.class.getClassLoader().getResource(CORPUS_ON_THE_CLASS_PATH);
		assertTrue(corpus != null, "The corpus is not on the class path: " + CORPUS_ON_THE_CLASS_PATH);

		try {
			Path directory = Path.of(URI.create(corpus.toString()));
			assertTrue(Files.isDirectory(directory), "The corpus is no directory: " + directory);

			return directory;
		} catch (IllegalArgumentException notAFile) {
			throw new UncheckedIOException(
					new IOException("The corpus has to lie in a directory, not in an archive: " + corpus,
							notAFile));
		}
	}

}
