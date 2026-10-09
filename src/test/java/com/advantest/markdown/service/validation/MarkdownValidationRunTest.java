/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.advantest.markdown.MarkdownParserAndHtmlRenderer;
import com.advantest.markdown.service.validation.uri.UriReachability;
import com.advantest.markdown.service.validation.uri.UriReachabilityChecker;
import com.advantest.resources.UnresolvedResource;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;

/**
 * Checks a {@link MarkdownValidationRun}: what the documents of one run share, and how a run is
 * cancelled and closed.
 */
public class MarkdownValidationRunTest {

	/**
	 * A validator triggered by the document, remembering the context it was handed and answering
	 * with a promise the test keeps.
	 */
	private static class DocumentValidator implements MarkdownValidator {

		private final List<MarkdownValidationContext> contexts = new CopyOnWriteArrayList<>();

		private final List<CompletableFuture<List<ValidationIssue>>> promises = new CopyOnWriteArrayList<>();

		private final boolean answeringAtOnce;

		DocumentValidator(boolean answeringAtOnce) {
			this.answeringAtOnce = answeringAtOnce;
		}

		@Override
		public Set<Class<? extends Node>> getTriggeringNodeTypes() {
			return Set.of(Document.class);
		}

		@Override
		public NodeFilter getIgnoredNodes() {
			return NodeFilters.NOTHING;
		}

		@Override
		public CompletableFuture<List<ValidationIssue>> validate(Node node, MarkdownValidationContext context) {
			this.contexts.add(context);
			CompletableFuture<List<ValidationIssue>> promise = this.answeringAtOnce
					? CompletableFuture.completedFuture(List.of())
					: new CompletableFuture<>();
			this.promises.add(promise);
			return promise;
		}
	}

	private final MarkdownParserAndHtmlRenderer parserAndRenderer = new MarkdownParserAndHtmlRenderer();

	private MarkdownValidationRules rulesOf(MarkdownValidator validator) {
		return new MarkdownValidationRules(this.parserAndRenderer, List.of(validator));
	}

	@Test
	void theDocumentsOfOneRunShareTheContextOfTheRun() {
		DocumentValidator validator = new DocumentValidator(true);
		MarkdownValidationRules rules = rulesOf(validator);

		try (MarkdownValidationRun run = rules.createRun()) {
			run.validate("# One\n", UnresolvedResource.UNKNOWN_DOCUMENT).join();
			run.validate("# Two\n", UnresolvedResource.UNKNOWN_DOCUMENT).join();
		}
		try (MarkdownValidationRun run = rules.createRun()) {
			run.validate("# Three\n", UnresolvedResource.UNKNOWN_DOCUMENT).join();
		}

		assertEquals(3, validator.contexts.size(), "Every document is expected to be checked once.");
		assertSame(validator.contexts.get(0), validator.contexts.get(1),
				"The documents of one run are expected to be checked in the same context.");
		assertNotSame(validator.contexts.get(1), validator.contexts.get(2),
				"A new run is expected to bring a context of its own.");
	}

	@Test
	void cancellingARunCancelsTheFindingsStillAwaited() {
		DocumentValidator validator = new DocumentValidator(false);
		MarkdownValidationRun run = rulesOf(validator).createRun();

		CompletableFuture<List<ValidationIssue>> findings = run.validate("# One\n", UnresolvedResource.UNKNOWN_DOCUMENT);
		assertFalse(findings.isDone(), "The findings are expected to wait for the validator.");

		run.cancel();

		assertTrue(findings.isCancelled(), "Cancelling the run is expected to cancel the awaited findings.");
		assertTrue(run.isCancelled(), "The run is expected to say that it was cancelled.");
		run.close();
	}

	@Test
	void aCancelledRunChecksNothingAnyMore() {
		DocumentValidator validator = new DocumentValidator(true);
		MarkdownValidationRun run = rulesOf(validator).createRun();
		run.cancel();

		CompletableFuture<List<ValidationIssue>> findings = run.validate("# One\n", UnresolvedResource.UNKNOWN_DOCUMENT);

		assertTrue(findings.isCancelled(), "A cancelled run is expected to answer with cancelled findings.");
		assertTrue(validator.contexts.isEmpty(), "A cancelled run is expected not to ask any validator.");
		run.close();
	}

	@Test
	void aRunSaysWhetherItWasClosed() {
		MarkdownValidationRun run = rulesOf(new DocumentValidator(true)).createRun();
		assertFalse(run.isClosed(), "A new run is expected to be open.");

		run.cancel();
		assertFalse(run.isClosed(), "A cancelled run is expected to be open until it is closed.");

		run.close();
		assertTrue(run.isClosed(), "A closed run is expected to say so.");
	}
	@Test
	void aClosedRunRefusesADocument() {
		DocumentValidator validator = new DocumentValidator(true);
		MarkdownValidationRun run = rulesOf(validator).createRun();
		run.close();

		assertThrows(IllegalStateException.class,
				() -> run.validate("# One\n", UnresolvedResource.UNKNOWN_DOCUMENT));
		assertThrows(IllegalStateException.class,
				() -> run.validate(this.parserAndRenderer.parseMarkdown("# One\n")));
		assertTrue(validator.contexts.isEmpty(), "A closed run is expected not to ask any validator.");
	}

	@Test
	void closingARunWaitsForTheFindingsStillAwaited() throws Exception {
		DocumentValidator validator = new DocumentValidator(false);
		MarkdownValidationRun run = rulesOf(validator).createRun();
		CompletableFuture<List<ValidationIssue>> findings = run.validate("# One\n", UnresolvedResource.UNKNOWN_DOCUMENT);

		CompletableFuture<Void> closed = CompletableFuture.runAsync(run::close);
		Thread.sleep(100);
		assertFalse(closed.isDone(), "Closing is expected to wait for the awaited findings.");

		validator.promises.get(0).complete(List.of());

		closed.get(5, TimeUnit.SECONDS);
		assertTrue(findings.isDone(), "The findings are expected to be there once the run is closed.");
	}

	@Test
	void closingARunIgnoresFindingsThatFailed() {
		DocumentValidator validator = new DocumentValidator(false);
		MarkdownValidationRun run = rulesOf(validator).createRun();
		CompletableFuture<List<ValidationIssue>> cancelled = run.validate("# One\n", UnresolvedResource.UNKNOWN_DOCUMENT);
		run.validate("# Two\n", UnresolvedResource.UNKNOWN_DOCUMENT);

		cancelled.cancel(false);
		validator.promises.get(1).complete(List.of());

		run.close();
	}

	/** A check counting how often a run opened, cancelled and closed it. */
	private static final class CountingChecker implements UriReachabilityChecker {

		private final AtomicInteger opened = new AtomicInteger();

		private final AtomicInteger cancelled = new AtomicInteger();

		private final AtomicInteger closed = new AtomicInteger();

		private final List<UriReachabilityChecker.OfRun> views = new CopyOnWriteArrayList<>();

		@Override
		public OfRun openForRun(Executor executor) {
			this.opened.incrementAndGet();
			OfRun view = new OfRun() {

				@Override
				public CompletableFuture<UriReachability> check(URI targetUri) {
					return CompletableFuture.completedFuture(new UriReachability.Answered(200));
				}

				@Override
				public void cancel() {
					CountingChecker.this.cancelled.incrementAndGet();
				}

				@Override
				public void close() {
					CountingChecker.this.closed.incrementAndGet();
				}
			};
			this.views.add(view);
			return view;
		}
	}

	@Test
	void theContextOfARunHandsOutTheCheckOpenedForTheRun() {
		DocumentValidator validator = new DocumentValidator(true);
		CountingChecker checker = new CountingChecker();

		try (MarkdownValidationRun run = rulesOf(validator).createRun(checker)) {
			run.validate("# One\n", UnresolvedResource.UNKNOWN_DOCUMENT).join();
			assertEquals(0, checker.closed.get(), "The check is expected to stay open while the run lasts.");
		}

		assertEquals(1, checker.opened.get(), "The run is expected to open the check once.");
		assertSame(checker.views.get(0), validator.contexts.get(0).getUriReachabilityChecker().orElseThrow(),
				"A validator is expected to be handed the check opened for its run.");
		assertEquals(1, checker.closed.get(), "Closing the run is expected to close the check opened for it.");
	}

	@Test
	void cancellingARunCancelsTheCheckOpenedForIt() {
		CountingChecker checker = new CountingChecker();
		MarkdownValidationRun run = rulesOf(new DocumentValidator(true)).createRun(checker);

		run.cancel();
		run.close();
		run.close();

		assertEquals(1, checker.cancelled.get(), "Cancelling the run is expected to cancel its check.");
		assertEquals(1, checker.closed.get(), "A run is expected to close its check once, however often it is closed.");
	}

	@Test
	void aRunWithoutACheckOffersNone() {
		DocumentValidator validator = new DocumentValidator(true);

		try (MarkdownValidationRun run = rulesOf(validator).createRun()) {
			run.validate("# One\n", UnresolvedResource.UNKNOWN_DOCUMENT).join();
		}

		assertTrue(validator.contexts.get(0).getUriReachabilityChecker().isEmpty(),
				"A run that may ask no address is expected to offer no check.");
	}

	@Test
	void findingsNobodyWaitedForAreNotReadAsNothingFound() {
		DocumentValidator validator = new DocumentValidator(false);

		try (MarkdownValidationRun run = rulesOf(validator).createRun()) {
			CompletableFuture<List<ValidationIssue>> findings =
					run.validate("# One\n", UnresolvedResource.UNKNOWN_DOCUMENT);
			validator.promises.get(0).cancel(false);

			assertThrows(CancellationException.class, findings::join,
					"A check that was not waited for is expected to leave the findings unfinished, not empty.");
		}
	}

	@Test
	void nullArgumentsAreRefused() {
		try (MarkdownValidationRun run = rulesOf(new DocumentValidator(true)).createRun()) {
			assertThrows(IllegalArgumentException.class, () -> run.validate((Document) null));
			assertThrows(IllegalArgumentException.class, () -> run.validate(null, UnresolvedResource.UNKNOWN_DOCUMENT));
			assertThrows(IllegalArgumentException.class, () -> run.validate("# One\n", null));
		}
	}

}
