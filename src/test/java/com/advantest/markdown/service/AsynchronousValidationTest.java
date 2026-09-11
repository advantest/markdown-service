/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.vladsch.flexmark.util.ast.Document;

import com.advantest.markdown.service.validation.MarkdownValidationContext;
import com.advantest.markdown.service.validation.ValidationIssue;
import com.advantest.markdown.service.validation.uri.UriReachability;
import com.advantest.markdown.service.validation.uri.UriReachabilityChecker;
import com.advantest.markdown.service.validation.uri.UriTarget;
import com.advantest.markdown.service.validation.uri.UriValidator;
import com.advantest.resources.UnresolvedResource;

/**
 * Tests that a check which has to ask something slow does not hold up the walk over the document,
 * and that waiting for what it promises changes nothing but the moment the findings arrive.
 */
class AsynchronousValidationTest {

	private static final String TWO_ADDRESSES =
			"[first](https://example.org/one) and [second](https://example.org/two)";

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	void severalAddressesAreAskedAboutBeforeTheFirstOneAnswers() {
		CountDownLatch bothAsked = new CountDownLatch(2);
		MarkdownService service = MarkdownService.builder()
				.withUriReachabilityCheck(answeringOnceEverybodyHasAsked(bothAsked))
				.build();

		List<ValidationIssue> issues = service.validateMarkdown(TWO_ADDRESSES);

		assertTrue(issues.isEmpty(),
				"Both addresses answer, and neither of them answers before the other was asked.");
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	void walkOverTheDocumentIsOverBeforeASlowAddressAnswers() {
		CountDownLatch answersMayArrive = new CountDownLatch(1);
		MarkdownService service = MarkdownService.builder()
				.withUriReachabilityCheck(answeringOnceReleasedBy(answersMayArrive))
				.build();

		CompletableFuture<List<ValidationIssue>> promisedIssues =
				service.validateMarkdownAsync(TWO_ADDRESSES);

		assertFalse(promisedIssues.isDone(), "Nothing can be found while no address has answered.");
		answersMayArrive.countDown();
		assertTrue(promisedIssues.join().isEmpty(), "Both addresses are there, so there is nothing to report.");
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	void waitingForThePromiseFindsWhatTheWaitingRunFinds() {
		MarkdownService service = serviceAnswering(new UriReachability.Answered(404));

		assertEquals(service.validateMarkdown(TWO_ADDRESSES),
				service.validateMarkdownAsync(TWO_ADDRESSES).join(),
				"Whether a caller waits or is called back does not change what is found.");
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	void findingsKeepTheirOrderHoweverLateAnAnswerArrives() {
		CountDownLatch answersMayArrive = new CountDownLatch(1);
		MarkdownService service = MarkdownService.builder()
				.withUriReachabilityCheck(answeringOnceReleasedBy(answersMayArrive,
						new UriReachability.Answered(404)))
				.build();

		CompletableFuture<List<ValidationIssue>> promisedIssues = service.validateMarkdownAsync(
				"[slow](https://example.org/gone) and [near](guide.md)");
		answersMayArrive.countDown();
		List<ValidationIssue> issues = promisedIssues.join();

		assertEquals(2, issues.size());
		assertTrue(issues.get(0).startOffset() < issues.get(1).startOffset(),
				"The finding about the address stands before the one that was there at once,"
						+ " because that is where the address stands.");
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	void whatACheckFailedWithIsWhatACallerSees() {
		IllegalStateException failure = new IllegalStateException("This check gave up.");
		MarkdownService service = MarkdownService.builder()
				.withUriValidator(new UriValidator() {

					@Override
					public boolean isResponsibleFor(UriTarget target) {
						return true;
					}

					@Override
					public CompletableFuture<List<ValidationIssue>> validate(UriTarget target,
							MarkdownValidationContext context) {
						throw failure;
					}
				})
				.build();

		IllegalStateException thrown = assertThrows(IllegalStateException.class,
				() -> service.validateMarkdown("[label](https://example.org/one)"));

		assertSame(failure, thrown, "A caller sees what the check threw, not what the waiting wrapped it in.");
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	void promisedRunResolvesAgainstTheResourceItIsToldAbout() {
		MarkdownService service = serviceAnswering(new UriReachability.Answered(200));

		List<ValidationIssue> issues = service
				.validateMarkdownAsync("[label](guide.md)", UnresolvedResource.UNKNOWN_DOCUMENT)
				.join();

		assertEquals(service.validateMarkdown("[label](guide.md)", UnresolvedResource.UNKNOWN_DOCUMENT),
				issues, "Being called back finds what waiting finds, here as well.");
	}

	@Test
	void promisedRunSaysAtOnceThatItWasGivenNothingToCheck() {
		MarkdownService service = new MarkdownService();

		assertThrows(IllegalArgumentException.class, () -> service.validateMarkdownAsync((String) null));
		assertThrows(IllegalArgumentException.class,
				() -> service.validateMarkdownAsync(null, UnresolvedResource.UNKNOWN_DOCUMENT));
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	void failureThatIsNoRuntimeFailureIsHandedOnAsTheWaitingWrappedIt() {
		IOException failure = new IOException("The address could not be asked about.");
		MarkdownService service = MarkdownService.builder()
				.withUriReachabilityCheck(uri -> CompletableFuture.failedFuture(failure))
				.build();

		CompletionException thrown = assertThrows(CompletionException.class,
				() -> service.validateMarkdown("[label](https://example.org/one)"));

		assertSame(failure, thrown.getCause(),
				"What cannot be thrown as it was keeps the wrapper, and names its reason.");
	}
	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	void documentThatIsAlreadyParsedIsCheckedWaitingAndPromising() {
		MarkdownService service = serviceAnswering(new UriReachability.Answered(404));
		Document document = service.parseMarkdown(TWO_ADDRESSES);

		List<ValidationIssue> awaited = service.validateMarkdown(document);

		assertEquals(2, awaited.size(), "Neither of the two addresses is there.");
		assertEquals(awaited, service.validateMarkdownAsync(document).join(),
				"A document that is already parsed is checked the same way, whoever waits.");
	}
	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	void failureNothingIsMeantToCatchIsHandedOnAsItWasThrown() {
		StackOverflowError failure = new StackOverflowError();
		MarkdownService service = MarkdownService.builder()
				.withUriReachabilityCheck(uri -> CompletableFuture.failedFuture(failure))
				.build();

		assertSame(failure, assertThrows(StackOverflowError.class,
				() -> service.validateMarkdown("[label](https://example.org/one)")),
				"What nothing is meant to catch reaches a caller as it was thrown.");
	}
	private static MarkdownService serviceAnswering(UriReachability answer) {
		return MarkdownService.builder()
				.withUriReachabilityCheck(uri -> CompletableFuture.completedFuture(answer))
				.build();
	}

	/** Answers for an address only once every address of the document has been asked about. */
	private static UriReachabilityChecker answeringOnceEverybodyHasAsked(CountDownLatch everybodyAsked) {
		return uri -> {
			everybodyAsked.countDown();
			return CompletableFuture.supplyAsync(() -> {
				awaitQuietly(everybodyAsked);
				return new UriReachability.Answered(200);
			});
		};
	}

	private static UriReachabilityChecker answeringOnceReleasedBy(CountDownLatch release) {
		return answeringOnceReleasedBy(release, new UriReachability.Answered(200));
	}

	private static UriReachabilityChecker answeringOnceReleasedBy(CountDownLatch release,
			UriReachability answer) {
		return uri -> CompletableFuture.supplyAsync(() -> {
			awaitQuietly(release);
			return answer;
		});
	}

	private static void awaitQuietly(CountDownLatch latch) {
		try {
			if (!latch.await(5, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Waited in vain for the other addresses to be asked about.");
			}
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(interrupted);
		}
	}

}
