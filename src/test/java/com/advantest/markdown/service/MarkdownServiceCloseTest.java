/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import com.advantest.markdown.service.validation.MarkdownValidationContext;
import com.advantest.markdown.service.validation.MarkdownValidationRun;
import com.advantest.markdown.service.validation.ValidationIssue;
import com.advantest.markdown.service.validation.anchor.AnchorTarget;
import com.advantest.markdown.service.validation.anchor.AnchorValidator;
import com.advantest.markdown.service.validation.uri.UriReachability;
import com.advantest.markdown.service.validation.uri.UriReachabilityChecker;
import com.advantest.markdown.service.validation.uri.UriTarget;
import com.advantest.markdown.service.validation.uri.UriValidator;
import com.advantest.resources.RelativePathResourceResolver;
import com.advantest.resources.Resource;
import com.advantest.resources.UnresolvedResource;
import com.advantest.resources.UriResolver;

/**
 * Tests that closing a service closes everything the service owns: the runs it handed out and
 * every part it is made of that can be closed.
 */
class MarkdownServiceCloseTest {

	/** A document naming an address that the waiting validator below never answers about. */
	private static final String DOCUMENT_NAMING_AN_ADDRESS = "[a page](https://example.org/page)\n";

	/** Counts how often it was closed, and fails doing so if it is told to. */
	private static class CountingPart implements AutoCloseable {

		/** Hands out the moments of closing, so that a test can tell which part was closed first. */
		private static final AtomicInteger CLOCK = new AtomicInteger();

		private final Exception failure;

		int timesClosed;

		int closedAt;

		CountingPart() {
			this(null);
		}

		CountingPart(Exception failure) {
			this.failure = failure;
		}

		@Override
		public void close() throws Exception {
			this.timesClosed++;
			this.closedAt = CLOCK.incrementAndGet();
			if (this.failure != null) {
				throw this.failure;
			}
		}
	}

	private static final class CloseableRelativePathResolver extends CountingPart
			implements RelativePathResourceResolver {

		@Override
		public Resource resolve(String targetResourcePathOrUri, Resource referencingDocument) {
			return new UnresolvedResource(targetResourcePathOrUri);
		}
	}

	private static final class CloseableUriResolver extends CountingPart implements UriResolver {

		@Override
		public boolean isResponsibleFor(URI targetUri) {
			return false;
		}

		@Override
		public Resource resolve(URI targetUri, Resource referencingDocument) {
			return new UnresolvedResource(targetUri.toString());
		}
	}

	private static final class CloseableChecker extends CountingPart implements UriReachabilityChecker {

		@Override
		public OfRun openForRun(Executor executor) {
			return targetUri -> CompletableFuture.completedFuture(new UriReachability.Answered(200));
		}
	}

	private static final class CloseableUriValidator extends CountingPart implements UriValidator {

		CloseableUriValidator() {
		}

		CloseableUriValidator(Exception failure) {
			super(failure);
		}

		@Override
		public boolean isResponsibleFor(UriTarget target) {
			return false;
		}

		@Override
		public CompletableFuture<List<ValidationIssue>> validate(UriTarget target,
				MarkdownValidationContext context) {
			return CompletableFuture.completedFuture(List.of());
		}
	}

	private static final class CloseableAnchorValidator extends CountingPart implements AnchorValidator {

		@Override
		public boolean isResponsibleFor(AnchorTarget target) {
			return false;
		}

		@Override
		public CompletableFuture<List<ValidationIssue>> validate(AnchorTarget target,
				MarkdownValidationContext context) {
			return CompletableFuture.completedFuture(List.of());
		}
	}

	/** Claims every address and never answers about it, so that its findings stay awaited. */
	private static final class WaitingUriValidator implements UriValidator {

		@Override
		public boolean isResponsibleFor(UriTarget target) {
			return true;
		}

		@Override
		public CompletableFuture<List<ValidationIssue>> validate(UriTarget target,
				MarkdownValidationContext context) {
			return new CompletableFuture<>();
		}
	}

	@Test
	void everyPartHandedToTheBuilderThatCanBeClosedIsClosed() {
		CloseableRelativePathResolver relativePathResolver = new CloseableRelativePathResolver();
		CloseableUriResolver uriResolver = new CloseableUriResolver();
		CloseableChecker checker = new CloseableChecker();
		CloseableUriValidator uriValidator = new CloseableUriValidator();
		CloseableAnchorValidator anchorValidator = new CloseableAnchorValidator();
		MarkdownService service = MarkdownService.builderNotCheckingUriReachability()
				.withRelativePathResourceResolver(relativePathResolver)
				.withUriResolver(uriResolver)
				.withUriReachabilityCheck(checker)
				.withUriValidator(uriValidator)
				.withAnchorValidator(anchorValidator)
				.build();

		service.close();

		assertEquals(1, relativePathResolver.timesClosed);
		assertEquals(1, uriResolver.timesClosed);
		assertEquals(1, checker.timesClosed);
		assertEquals(1, uriValidator.timesClosed);
		assertEquals(1, anchorValidator.timesClosed);
	}

	@Test
	void thePartsAreClosedBeforeWhatTheyUse() {
		CloseableRelativePathResolver relativePathResolver = new CloseableRelativePathResolver();
		CloseableUriResolver uriResolver = new CloseableUriResolver();
		CloseableChecker checker = new CloseableChecker();
		CloseableUriValidator uriValidator = new CloseableUriValidator();
		CloseableAnchorValidator anchorValidator = new CloseableAnchorValidator();
		MarkdownService service = MarkdownService.builderNotCheckingUriReachability()
				.withRelativePathResourceResolver(relativePathResolver)
				.withUriResolver(uriResolver)
				.withUriReachabilityCheck(checker)
				.withUriValidator(uriValidator)
				.withAnchorValidator(anchorValidator)
				.build();

		service.close();

		assertTrue(uriValidator.closedAt < anchorValidator.closedAt,
				"The validators of addresses are expected to be closed first.");
		assertTrue(anchorValidator.closedAt < checker.closedAt,
				"The check is expected to be closed after the validators asking it.");
		assertTrue(checker.closedAt < relativePathResolver.closedAt,
				"The resolvers are expected to be closed last.");
		assertTrue(relativePathResolver.closedAt < uriResolver.closedAt,
				"The resolver of a path is expected to be closed before the resolvers of a scheme.");
	}

	@Test
	void aPartHandedOverToBeClosedIsClosedAfterThePartsUsingIt() {
		CloseableRelativePathResolver relativePathResolver = new CloseableRelativePathResolver();
		CloseableUriValidator uriValidator = new CloseableUriValidator();
		CountingPart sharedClient = new CountingPart();
		CountingPart otherSharedClient = new CountingPart();
		MarkdownService service = MarkdownService.builderNotCheckingUriReachability()
				.withRelativePathResourceResolver(relativePathResolver)
				.withUriValidator(uriValidator)
				.withPartToClose(sharedClient)
				.withPartToClose(otherSharedClient)
				.build();

		service.close();

		assertEquals(1, sharedClient.timesClosed);
		assertEquals(1, otherSharedClient.timesClosed);
		assertTrue(uriValidator.closedAt < sharedClient.closedAt,
				"A part handed over to be closed is expected to be closed after the validators.");
		assertTrue(relativePathResolver.closedAt < sharedClient.closedAt,
				"A part handed over to be closed is expected to be closed after the resolvers.");
		assertTrue(sharedClient.closedAt < otherSharedClient.closedAt,
				"The parts handed over to be closed are expected to be closed in the order given.");
	}

	@Test
	void noPartToCloseIsTakenThatIsNotThere() {
		assertThrows(IllegalArgumentException.class,
				() -> MarkdownService.builderNotCheckingUriReachability().withPartToClose(null));
	}

	@Test
	void closingAClosedServiceDoesNothing() {
		CloseableUriValidator uriValidator = new CloseableUriValidator();
		MarkdownService service = MarkdownService.builderNotCheckingUriReachability()
				.withUriValidator(uriValidator)
				.build();

		service.close();
		service.close();

		assertEquals(1, uriValidator.timesClosed);
	}

	@Test
	void aServiceMadeOfNothingClosableIsClosedWithoutComplaining() {
		assertDoesNotThrow(new MarkdownService()::close);
		assertDoesNotThrow(MarkdownService.builderCheckingUriReachability().build()::close);
	}

	@Test
	void aClosedServiceRefusesEveryCall() {
		MarkdownService service = new MarkdownService();
		var document = service.parseMarkdown("# One\n");
		Resource resource = UnresolvedResource.UNKNOWN_DOCUMENT;

		service.close();

		List<Executable> calls = List.of(
				service::clearCaches,
				() -> service.parseMarkdown("# One\n"),
				() -> service.parseMarkdown("# One\n", resource),
				() -> service.renderHtml(document),
				() -> service.parseMarkdownAndRenderHtml("# One\n"),
				() -> service.parseMarkdownAndRenderHtml("# One\n", resource),
				() -> service.validateMarkdown(document),
				() -> service.validateMarkdown("# One\n"),
				() -> service.validateMarkdown("# One\n", resource),
				() -> service.validateMarkdownAsync(document),
				() -> service.validateMarkdownAsync("# One\n"),
				() -> service.validateMarkdownAsync("# One\n", resource),
				service::createValidationRun,
				() -> service.putUnsavedContents(resource, "# One\n"),
				() -> service.dropUnsavedContents(resource));
		for (Executable call : calls) {
			assertThrows(IllegalStateException.class, call);
		}
	}

	@Test
	void anOpenRunIsCancelledAndClosed() {
		MarkdownService service = MarkdownService.builderNotCheckingUriReachability()
				.withUriValidator(new WaitingUriValidator())
				.build();
		MarkdownValidationRun run = service.createValidationRun();
		CompletableFuture<List<ValidationIssue>> findings =
				run.validate(DOCUMENT_NAMING_AN_ADDRESS, UnresolvedResource.UNKNOWN_DOCUMENT);

		service.close();

		assertTrue(findings.isCancelled(), "The findings still awaited are expected to be cancelled.");
		assertTrue(run.isCancelled(), "The open run is expected to be cancelled.");
		assertTrue(run.isClosed(), "The open run is expected to be closed.");
	}

	@Test
	void findingsPromisedWithoutARunOfTheCallerAreCancelled() {
		MarkdownService service = MarkdownService.builderNotCheckingUriReachability()
				.withUriValidator(new WaitingUriValidator())
				.build();
		CompletableFuture<List<ValidationIssue>> findings =
				service.validateMarkdownAsync(DOCUMENT_NAMING_AN_ADDRESS);

		service.close();

		assertTrue(findings.isCancelled(), "The findings still awaited are expected to be cancelled.");
	}

	@Test
	void aRunTheCallerClosedIsLeftAlone() {
		MarkdownService service = new MarkdownService();
		MarkdownValidationRun run = service.createValidationRun();
		run.close();

		service.close();

		assertFalse(run.isCancelled(), "A run closed before is expected not to be cancelled.");
	}

	@Test
	void everyPartIsClosedEvenIfClosingAnotherOneFailed() {
		IllegalStateException oneFailure = new IllegalStateException("one");
		IllegalArgumentException otherFailure = new IllegalArgumentException("other");
		CloseableUriValidator failingOne = new CloseableUriValidator(oneFailure);
		CloseableUriValidator failingOther = new CloseableUriValidator(otherFailure);
		CloseableUriValidator closingFine = new CloseableUriValidator();
		MarkdownService service = MarkdownService.builderNotCheckingUriReachability()
				.withUriValidator(failingOne)
				.withUriValidator(closingFine)
				.withUriValidator(failingOther)
				.build();

		RuntimeException raised = assertThrows(RuntimeException.class, service::close);

		assertEquals(1, failingOne.timesClosed);
		assertEquals(1, failingOther.timesClosed);
		assertEquals(1, closingFine.timesClosed);
		assertEquals(1, raised.getSuppressed().length, "The later failure is expected to be suppressed.");
		assertEquals(Set.of(oneFailure, otherFailure), Set.of(raised, raised.getSuppressed()[0]));
	}

	@Test
	void aFailureThatIsNoRuntimeExceptionIsWrapped() {
		IOException failure = new IOException("failed");
		MarkdownService service = MarkdownService.builderNotCheckingUriReachability()
				.withUriValidator(new CloseableUriValidator(failure))
				.build();

		IllegalStateException raised = assertThrows(IllegalStateException.class, service::close);

		assertSame(failure, raised.getCause());
	}
}
