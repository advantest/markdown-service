/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.Test;

import com.advantest.markdown.service.validation.MarkdownIssueTypes;
import com.advantest.markdown.service.validation.MarkdownValidationContext;
import com.advantest.markdown.service.validation.ValidationIssue;
import com.advantest.markdown.service.validation.uri.UriReachability;
import com.advantest.markdown.service.validation.uri.UriReachabilityChecker;
import com.advantest.markdown.service.validation.uri.UriTarget;
import com.advantest.markdown.service.validation.uri.UriValidator;

/**
 * Tests that the web addresses of a document are checked once a caller says that addresses may be
 * asked about, and that they are left alone until then.
 */
class WebAddressValidationTest {

	/** Answers for the addresses a test knows and remembers which ones it was asked about. */
	private static final class KnownAddresses implements UriReachabilityChecker {

		private final List<URI> askedAddresses = new ArrayList<>();

		private final Map<String, UriReachability> answers;

		private KnownAddresses(Map<String, UriReachability> answers) {
			this.answers = answers;
		}

		@Override
		public OfRun openForRun(Executor executor) {
			return this::check;
		}

		private CompletableFuture<UriReachability> check(URI uri) {
			this.askedAddresses.add(uri);
			return CompletableFuture.completedFuture(
					this.answers.getOrDefault(uri.toString(), new UriReachability.Answered(200)));
		}
	}

	@Test
	void addressIsLeftAloneUntilACallerSaysThatAddressesMayBeAskedAbout() {
		List<ValidationIssue> issues =
				new MarkdownService().validateMarkdown("[label](https://example.org/gone)");

		assertTrue(issues.isEmpty());
	}

	@Test
	void addressNobodyCanAskAboutIsStillReportedAsNoAddress() {
		MarkdownService serviceAskingNobody = new MarkdownService();

		List<ValidationIssue> issues =
				serviceAskingNobody.validateMarkdown("[label](https:/example.org/guide)");

		assertEquals(1, issues.size());
		assertEquals(MarkdownIssueTypes.LINK_HTTP_INVALID_WEB_ADDRESS, issues.get(0).issueTypeId());
		assertTrue(issues.get(0).message().contains("It has to start with https:// or http://"),
				issues.get(0).message());
	}

	@Test
	void addressNobodyCanReadIsStillReportedWithoutAnybodyBeingAsked() {
		MarkdownService serviceAskingNobody = new MarkdownService();

		List<ValidationIssue> issues =
				serviceAskingNobody.validateMarkdown("[label](https://example.org/^guide)");

		assertEquals(1, issues.size());
		assertEquals(MarkdownIssueTypes.LINK_HTTP_INVALID_WEB_ADDRESS, issues.get(0).issueTypeId());
	}

	@Test
	void addressOfADocumentIsAskedAboutOnceACallerSaysSo() {
		KnownAddresses addresses = new KnownAddresses(Map.of());
		MarkdownService service = MarkdownService.builderNotCheckingUriReachability()
				.withUriReachabilityCheck(addresses)
				.build();

		service.validateMarkdown("[label](https://example.org/guide)");

		assertEquals(List.of(URI.create("https://example.org/guide")), addresses.askedAddresses);
	}

	@Test
	void addressThatIsNotThereIsReported() {
		MarkdownService service = serviceKnowing(Map.of(
				"https://example.org/gone", new UriReachability.Answered(404)));

		List<ValidationIssue> issues = service.validateMarkdown("[label](https://example.org/gone)");

		assertEquals(1, issues.size());
		assertEquals(MarkdownIssueTypes.LINK_HTTP_WEB_ADDRESS_NOT_REACHABLE, issues.get(0).issueTypeId());
		assertEquals(8, issues.get(0).startOffset());
		assertEquals(32, issues.get(0).endOffset());
	}

	@Test
	void addressThatDoesNotAnswerIsReported() {
		MarkdownService service = serviceKnowing(Map.of(
				"https://example.org/silent", new UriReachability.NotReached("request timed out")));

		List<ValidationIssue> issues = service.validateMarkdown("[label](https://example.org/silent)");

		assertEquals(1, issues.size());
		assertEquals(MarkdownIssueTypes.LINK_HTTP_WEB_ADDRESS_DOES_NOT_ANSWER, issues.get(0).issueTypeId());
	}

	@Test
	void addressThatIsThereIsNotReported() {
		MarkdownService service = serviceKnowing(Map.of());

		assertTrue(service.validateMarkdown("[label](https://example.org/guide)").isEmpty());
	}

	@Test
	void addressOfALinkReferenceDefinitionIsCheckedAsWell() {
		MarkdownService service = serviceKnowing(Map.of(
				"https://example.org/gone", new UriReachability.Answered(404)));

		List<ValidationIssue> issues =
				service.validateMarkdown("[key]: https://example.org/gone\n\nSee [key].");

		assertEquals(1, issues.size());
		assertEquals(MarkdownIssueTypes.LINK_HTTP_WEB_ADDRESS_NOT_REACHABLE, issues.get(0).issueTypeId());
	}

	@Test
	void targetNamingAnotherSchemeIsLeftAlone() {
		KnownAddresses addresses = new KnownAddresses(Map.of());
		MarkdownService service = MarkdownService.builderNotCheckingUriReachability()
				.withUriReachabilityCheck(addresses)
				.build();

		List<ValidationIssue> issues = service.validateMarkdown("[label](mailto:someone@example.org)");

		assertTrue(issues.isEmpty());
		assertTrue(addresses.askedAddresses.isEmpty());
	}

	@Test
	void validatorOfACallerAnswersBeforeTheShippedOne() {
		KnownAddresses addresses = new KnownAddresses(Map.of(
				"https://example.org/gone", new UriReachability.Answered(404)));
		MarkdownService service = MarkdownService.builderNotCheckingUriReachability()
				.withUriReachabilityCheck(addresses)
				.withUriValidator(new UriValidator() {

					@Override
					public boolean isResponsibleFor(UriTarget target) {
						return target.uriText().startsWith("https://example.org/");
					}

					@Override
					public CompletableFuture<List<ValidationIssue>> validate(UriTarget target,
							MarkdownValidationContext context) {
						return CompletableFuture.completedFuture(List.of());
					}
				})
				.build();

		List<ValidationIssue> issues = service.validateMarkdown("[label](https://example.org/gone)");

		assertTrue(issues.isEmpty());
		assertTrue(addresses.askedAddresses.isEmpty());
	}

	private static MarkdownService serviceKnowing(Map<String, UriReachability> answers) {
		return MarkdownService.builderNotCheckingUriReachability()
				.withUriReachabilityCheck(new KnownAddresses(answers))
				.build();
	}

}
