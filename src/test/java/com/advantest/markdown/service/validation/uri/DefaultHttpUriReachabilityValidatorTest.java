/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation.uri;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.advantest.markdown.MarkdownParserAndHtmlRenderer;
import com.advantest.markdown.service.validation.IssueSeverity;
import com.advantest.markdown.service.validation.MarkdownIssueTypes;
import com.advantest.markdown.service.validation.MarkdownValidationContext;
import com.advantest.markdown.service.validation.ValidationIssue;
import com.advantest.resources.Resource;
import com.vladsch.flexmark.util.ast.Document;

/**
 * Tests what the shipped validator of web addresses answers for a target, and which target it
 * answers for at all.
 */
class DefaultHttpUriReachabilityValidatorTest {

	/** No validator of this test looks into another document, so what the context parses is never asked. */
	private static final MarkdownValidationContext CONTEXT_WITHOUT_CHECK =
			MarkdownValidationContext.parsingWith(new MarkdownParserAndHtmlRenderer());

	private static final DefaultHttpUriReachabilityValidator VALIDATOR = new DefaultHttpUriReachabilityValidator();

	/** A context handing out the given check, and nothing else a validator of this test asks for. */
	private static MarkdownValidationContext contextAsking(UriReachabilityChecker checker) {
		return new MarkdownValidationContext() {

			@Override
			public String getContents(Resource resource) throws IOException {
				return CONTEXT_WITHOUT_CHECK.getContents(resource);
			}

			@Override
			public Document getParsedMarkdownDocument(Resource markdownResource) throws IOException {
				return CONTEXT_WITHOUT_CHECK.getParsedMarkdownDocument(markdownResource);
			}

			@Override
			public Optional<UriReachabilityChecker> getUriReachabilityChecker() {
				return Optional.of(checker);
			}
		};
	}

	private static MarkdownValidationContext contextAnswering(UriReachability reachability) {
		return contextAsking(new StubbedReachabilityChecker(reachability));
	}

	/** Answers what a test tells it to and remembers which addresses it was asked about. */
	private static final class StubbedReachabilityChecker implements UriReachabilityChecker {

		private final List<URI> askedAddresses = new ArrayList<>();

		private final UriReachability answer;

		private StubbedReachabilityChecker(UriReachability answer) {
			this.answer = answer;
		}

		@Override
		public CompletableFuture<UriReachability> check(URI uri) {
			this.askedAddresses.add(uri);
			return CompletableFuture.completedFuture(this.answer);
		}
	}

	private static final UriReachability REACHED = new UriReachability.Answered(200);

	@Test
	void runWithoutACheckFindsNothingAboutAnAddress() {
		assertTrue(VALIDATOR.validate(targetOf("https://example.org/gone"), CONTEXT_WITHOUT_CHECK).join().isEmpty(),
				"Whether an address is there is only said in a run that may ask it.");
	}

	@Test
	void validatorAsksTheCheckOfTheRunItIsHandedTheContextOf() {
		StubbedReachabilityChecker first = new StubbedReachabilityChecker(REACHED);
		StubbedReachabilityChecker second = new StubbedReachabilityChecker(REACHED);

		VALIDATOR.validate(targetOf("https://example.org/one"), contextAsking(first)).join();
		VALIDATOR.validate(targetOf("https://example.org/two"), contextAsking(second)).join();

		assertEquals(List.of(URI.create("https://example.org/one")), first.askedAddresses);
		assertEquals(List.of(URI.create("https://example.org/two")), second.askedAddresses);
	}

	@Test
	void validatorAnswersForAWebAddress() {
		DefaultHttpUriReachabilityValidator validator = VALIDATOR;

		assertTrue(validator.isResponsibleFor(targetOf("https://example.org/guide")));
		assertTrue(validator.isResponsibleFor(targetOf("http://example.org/guide")));
	}

	@Test
	void validatorAnswersForAWebAddressWhateverItIsWrittenLike() {
		DefaultHttpUriReachabilityValidator validator = VALIDATOR;

		assertTrue(validator.isResponsibleFor(targetOf("HTTPS://Example.org/guide")));
	}

	@Test
	void validatorLeavesATextThatOnlyLooksLikeAWebAddressAlone() {
		DefaultHttpUriReachabilityValidator validator = VALIDATOR;

		assertFalse(validator.isResponsibleFor(targetOf("https:/example.org/guide")));
		assertFalse(validator.isResponsibleFor(targetOf("https://example.org/a guide")));
	}

	@Test
	void validatorLeavesEveryOtherSchemeAlone() {
		DefaultHttpUriReachabilityValidator validator = VALIDATOR;

		assertFalse(validator.isResponsibleFor(targetOf("mailto:someone@example.org")));
		assertFalse(validator.isResponsibleFor(targetOf("ftp://example.org/guide")));
		assertFalse(validator.isResponsibleFor(targetOf("guide/introduction.md")));
	}

	@Test
	void validatorRejectsATargetItIsNotGiven() {
		DefaultHttpUriReachabilityValidator validator = VALIDATOR;

		assertThrows(IllegalArgumentException.class, () -> validator.isResponsibleFor(null));
		assertThrows(IllegalArgumentException.class, () -> validator.validate(null, CONTEXT_WITHOUT_CHECK));
		assertThrows(IllegalArgumentException.class,
				() -> validator.validate(targetOf("https://example.org/guide"), null));
	}

	@Test
	void addressThatDoesNotAnswerIsReportedAsAWarning() {
		List<ValidationIssue> issues = VALIDATOR.validate(targetOf("https://plantxyzuml.com"),
				contextAnswering(new UriReachability.NotReached("HTTP connect timed out"))).join();

		assertEquals(1, issues.size());
		ValidationIssue issue = issues.get(0);
		assertEquals(MarkdownIssueTypes.LINK_HTTP_WEB_ADDRESS_DOES_NOT_ANSWER, issue.issueTypeId());
		assertEquals(IssueSeverity.WARNING, issue.severity());
		assertEquals("The referenced web address 'https://plantxyzuml.com' seems not to exist."
				+ " (Error message: HTTP connect timed out)", issue.message());
	}

	@Test
	void addressAnsweringThatThereIsNothingThereIsReported() {
		List<ValidationIssue> issues = VALIDATOR.validate(targetOf("https://example.org/gone"),
				contextAnswering(new UriReachability.Answered(404))).join();

		assertEquals(1, issues.size());
		ValidationIssue issue = issues.get(0);
		assertEquals(MarkdownIssueTypes.LINK_HTTP_WEB_ADDRESS_NOT_REACHABLE, issue.issueTypeId());
		assertEquals(IssueSeverity.ERROR, issue.severity());
		assertEquals("The referenced web address 'https://example.org/gone' is not reachable"
				+ " (HTTP status code 404).", issue.message());
	}

	@Test
	void addressAnsweringThatItIsBrokenItselfIsReportedAsWell() {
		List<ValidationIssue> issues = VALIDATOR.validate(targetOf("https://example.org/broken"),
				contextAnswering(new UriReachability.Answered(500))).join();

		assertEquals(1, issues.size());
		assertEquals("The referenced web address 'https://example.org/broken' is not reachable"
				+ " (HTTP status code 500).", issues.get(0).message());
	}

	@Test
	void addressThatIsThereIsNotReported() {
		assertTrue(VALIDATOR.validate(targetOf("https://example.org/guide"),
				contextAnswering(new UriReachability.Answered(200))).join().isEmpty());
	}

	@Test
	void addressAnsweringWithARedirectOrAnythingBelowFourHundredIsNotReported() {
		assertTrue(VALIDATOR.validate(targetOf("https://example.org/moved"),
				contextAnswering(new UriReachability.Answered(301))).join().isEmpty());
		assertTrue(VALIDATOR.validate(targetOf("https://example.org/odd"),
				contextAnswering(new UriReachability.Answered(399))).join().isEmpty());
	}

	@Test
	void addressIsAskedAboutAsItIsWritten() {
		StubbedReachabilityChecker checker = new StubbedReachabilityChecker(REACHED);

		VALIDATOR.validate(targetOf("https://example.org/guide?q=1#top"), contextAsking(checker));

		assertEquals(List.of(URI.create("https://example.org/guide?q=1#top")), checker.askedAddresses);
	}

	@Test
	void reportedProblemMarksTheWholeAddressWhereItStands() {
		UriTarget target = UriTarget.of("https://example.org/gone", 7, 120, 144);

		ValidationIssue issue = VALIDATOR.validate(target, contextAnswering(new UriReachability.Answered(404)))
				.join().get(0);

		assertEquals(7, issue.lineNumber());
		assertEquals(120, issue.startOffset());
		assertEquals(144, issue.endOffset());
	}

	@Test
	void addressWrittenWithoutTheSecureSchemeIsCheckedTheSameWay() {
		List<ValidationIssue> issues = VALIDATOR.validate(targetOf("http://example.org/gone"),
				contextAnswering(new UriReachability.Answered(404))).join();

		assertEquals(1, issues.size());
		assertEquals("The referenced web address 'http://example.org/gone' is not reachable"
				+ " (HTTP status code 404).", issues.get(0).message());
	}

	private static UriTarget targetOf(String text) {
		return UriTarget.of(text, 1, 0, text.length());
	}

}
