/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation.uri;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.advantest.markdown.service.validation.IssueSeverity;
import com.advantest.markdown.service.validation.MarkdownIssueTypes;
import com.advantest.markdown.service.validation.MarkdownValidationContext;
import com.advantest.markdown.service.validation.ValidationIssue;

/**
 * Checks a target that is meant to be a web address, e.g.
 * <code>https://example.org/guide</code>.
 * 
 * <p>Two things can be wrong with an address that can be read: it does not answer at all, or it
 * answers by saying that there is nothing there. Both are decided by asking it, which is what the
 * {@link UriReachabilityChecker} of the run does, and both are therefore reported only in a run that
 * was given such a check. In a run without one, this validator finds nothing.</p>
 * 
 * <p>This validator answers for a target naming the scheme <code>http</code> or <code>https</code>
 * that was read as an address. A text that was meant as a web address and is none belongs to
 * {@link HttpUriSyntaxValidator}, which is asked before this one and needs nobody: asking an
 * address that cannot be read is impossible, and saying what is wrong with it does not need the
 * network. This validator answers for nothing else &mdash; what a target naming another scheme has
 * to look like is known by whoever owns that scheme.</p>
 */
public class DefaultHttpUriReachabilityValidator implements UriValidator {

	private static final String SCHEME_HTTP = "http";

	private static final String SCHEME_HTTPS = "https";


	@Override
	public boolean isResponsibleFor(UriTarget target) {
		if (target == null) {
			throw new IllegalArgumentException("Argument must not be null.");
		}

		return namesTheHttpScheme(target)
				&& HttpUriSyntaxValidator.startsWithHttpSchemeAndSeparator(target.uriText());
	}

	@Override
	public CompletableFuture<List<ValidationIssue>> validate(UriTarget target,
			MarkdownValidationContext context) {

		if (target == null || context == null) {
			throw new IllegalArgumentException("Arguments must not be null.");
		}

		Optional<UriReachabilityChecker.OfRun> reachabilityChecker = context.getUriReachabilityChecker();
		if (target.uri().isEmpty() || reachabilityChecker.isEmpty()) {
			return CompletableFuture.completedFuture(List.of());
		}

		return reachabilityChecker.orElseThrow().check(target.uri().orElseThrow())
				.thenApply(reachability -> whatIsWrongWith(target, reachability));
	}

	private static List<ValidationIssue> whatIsWrongWith(UriTarget target, UriReachability reachability) {
		return switch (reachability) {
			case UriReachability.NotReached notReached -> List.of(
					issue(target, MarkdownIssueTypes.LINK_HTTP_WEB_ADDRESS_DOES_NOT_ANSWER, IssueSeverity.WARNING,
							addressDoesNotAnswerMessage(target.uriText(), notReached.failureReason())));
			case UriReachability.Answered answered when answered.statusCode() >= 400 -> List.of(
					issue(target, MarkdownIssueTypes.LINK_HTTP_WEB_ADDRESS_NOT_REACHABLE, IssueSeverity.ERROR,
							addressNotReachableMessage(target.uriText(), answered.statusCode())));
			case UriReachability.Answered answered -> List.of();
		};
	}

	/**
	 * Tells whether the target names the scheme {@code http} or {@code https} in the URI it was read as.
	 * <p>
	 * This is asked together with the question whether the text carries the separator after the scheme,
	 * because {@code https:/example.org} is read as a URI naming {@code https} as well and is none of this
	 * validator's business: it is an address nobody can ask, and {@link HttpUriSyntaxValidator} answers
	 * for it instead. A scheme is written in either case, so it is compared ignoring the case and without
	 * folding the text first, so that asking this of every target of a document builds no string.
	 *
	 * @param target the target to ask, never {@code null}
	 * @return whether the target was read as a URI naming one of the two schemes
	 */
	private static boolean namesTheHttpScheme(UriTarget target) {
		return target.scheme()
				.filter(scheme -> SCHEME_HTTP.equalsIgnoreCase(scheme) || SCHEME_HTTPS.equalsIgnoreCase(scheme))
				.isPresent();
	}

	private static ValidationIssue issue(UriTarget target, String issueTypeId, IssueSeverity severity,
			String message) {

		return new ValidationIssue(issueTypeId, severity, message,
				target.lineNumber(), target.startOffset(), target.endOffset());
	}


	private static String addressDoesNotAnswerMessage(String targetText, String failureReason) {
		return String.format("The referenced web address '%s' seems not to exist. (Error message: %s)",
				targetText, failureReason);
	}

	private static String addressNotReachableMessage(String targetText, int statusCode) {
		return String.format("The referenced web address '%s' is not reachable (HTTP status code %s).",
				targetText, statusCode);
	}

}
