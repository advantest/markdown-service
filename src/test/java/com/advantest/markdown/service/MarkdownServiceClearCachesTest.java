/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.Test;

import com.advantest.markdown.service.validation.MarkdownValidationContext;
import com.advantest.markdown.service.validation.ValidationIssue;
import com.advantest.markdown.service.validation.anchor.AnchorTarget;
import com.advantest.markdown.service.validation.anchor.AnchorValidator;
import com.advantest.markdown.service.validation.uri.UriReachability;
import com.advantest.markdown.service.validation.uri.UriReachabilityChecker;
import com.advantest.markdown.service.validation.uri.UriTarget;
import com.advantest.markdown.service.validation.uri.UriValidator;

/**
 * Tests that a service passes the word on to everything it is made of that remembers answers, so
 * that a caller holding nothing but the service can make it forget.
 */
class MarkdownServiceClearCachesTest {

	/** A check remembering nothing and only counting how often it was told to forget. */
	private static final class CountingChecker implements UriReachabilityChecker, CachesHolder {

		private int timesTold;

		@Override
		public OfRun openForRun(Executor executor) {
			return targetUri -> CompletableFuture.completedFuture(new UriReachability.Answered(200));
		}

		@Override
		public void clearCaches() {
			this.timesTold++;
		}
	}

	/** A validator of addresses saying that it remembers something, counting the same way. */
	private static final class CountingUriValidator implements UriValidator, CachesHolder {

		private int timesTold;

		@Override
		public boolean isResponsibleFor(UriTarget target) {
			return false;
		}

		@Override
		public CompletableFuture<List<ValidationIssue>> validate(UriTarget target,
				MarkdownValidationContext context) {
			return CompletableFuture.completedFuture(List.of());
		}

		@Override
		public void clearCaches() {
			this.timesTold++;
		}
	}

	/** A validator of what a link names inside its target, remembering something as well. */
	private static final class CountingAnchorValidator implements AnchorValidator, CachesHolder {

		private int timesTold;

		@Override
		public boolean isResponsibleFor(AnchorTarget target) {
			return false;
		}

		@Override
		public CompletableFuture<List<ValidationIssue>> validate(AnchorTarget target,
				MarkdownValidationContext context) {
			return CompletableFuture.completedFuture(List.of());
		}

		@Override
		public void clearCaches() {
			this.timesTold++;
		}
	}

	@Test
	void theCheckAskingAddressesIsToldToForget() {
		CountingChecker checker = new CountingChecker();
		MarkdownService service = MarkdownService.builderNotCheckingUriReachability()
				.withUriReachabilityCheck(checker)
				.build();

		service.clearCaches();

		assertEquals(1, checker.timesTold);
	}

	@Test
	void everyRegisteredValidatorRememberingSomethingIsToldToForget() {
		CountingUriValidator uriValidator = new CountingUriValidator();
		CountingAnchorValidator anchorValidator = new CountingAnchorValidator();
		MarkdownService service = MarkdownService.builderNotCheckingUriReachability()
				.withUriValidator(uriValidator)
				.withAnchorValidator(anchorValidator)
				.build();

		service.clearCaches();

		assertEquals(1, uriValidator.timesTold);
		assertEquals(1, anchorValidator.timesTold);
	}

	@Test
	void everythingIsToldEveryTimeItIsSaid() {
		CountingChecker checker = new CountingChecker();
		MarkdownService service = MarkdownService.builderNotCheckingUriReachability()
				.withUriReachabilityCheck(checker)
				.build();

		service.clearCaches();
		service.clearCaches();
		service.clearCaches();

		assertEquals(3, checker.timesTold);
	}

	@Test
	void aServiceRememberingNothingIsToldWithoutComplaining() {
		MarkdownService service = MarkdownService.builderNotCheckingUriReachability()
				.withUriValidator(new UriValidator() {

					@Override
					public boolean isResponsibleFor(UriTarget target) {
						return false;
					}

					@Override
					public CompletableFuture<List<ValidationIssue>> validate(UriTarget target,
							MarkdownValidationContext context) {
						return CompletableFuture.completedFuture(List.of());
					}
				})
				.build();

		assertDoesNotThrow(service::clearCaches);
	}

	@Test
	void theDefaultServiceIsToldWithoutComplaining() {
		assertDoesNotThrow(new MarkdownService()::clearCaches);
	}

}
