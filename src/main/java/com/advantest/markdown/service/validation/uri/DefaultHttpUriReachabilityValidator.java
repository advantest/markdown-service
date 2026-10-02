/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation.uri;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import com.advantest.markdown.service.validation.IssueSeverity;
import com.advantest.markdown.service.validation.MarkdownIssueTypes;
import com.advantest.markdown.service.validation.MarkdownValidationContext;
import com.advantest.markdown.service.validation.ValidationIssue;

/**
 * Checks a target that is meant to be a web address, e.g.
 * <code>https://example.org/guide</code>.
 * 
 * <p>Four things can be wrong with such a target: it does not begin with a scheme this validator
 * knows, it cannot be read as an address at all, it names an address that does not answer, or it
 * names an address that answers by saying that there is nothing there. The first two are decided
 * by reading the target, the other two by asking the address, which is what the
 * {@link UriReachabilityChecker} does.</p>
 * 
 * <p>This validator answers for every target beginning with <code>http</code> or
 * <code>https</code>, including one that only looks as if it did, because a mistyped address is
 * exactly what a reader of the document would run into. It answers for nothing else: what a target
 * naming another scheme has to look like is known by whoever owns that scheme.</p>
 */
public class DefaultHttpUriReachabilityValidator implements UriValidator {

	private static final String SCHEME_HTTP = "http";

	private static final String SCHEME_HTTPS = "https";

	private final UriReachabilityChecker reachabilityChecker;

	/**
	 * Creates the validator, asking the given check whether an address is there.
	 * 
	 * @param reachabilityChecker the check asking an address whether it is there, must not be
	 *                            <code>null</code>
	 * @throws IllegalArgumentException if the given check is <code>null</code>
	 */
	public DefaultHttpUriReachabilityValidator(UriReachabilityChecker reachabilityChecker) {
		if (reachabilityChecker == null) {
			throw new IllegalArgumentException("Argument must not be null.");
		}
		this.reachabilityChecker = reachabilityChecker;
	}

	@Override
	public boolean isResponsibleFor(UriTarget target) {
		if (target == null) {
			throw new IllegalArgumentException("Argument must not be null.");
		}

		return namesTheHttpScheme(target) || beginsWithTheHttpScheme(target.uriText());
	}

	@Override
	public CompletableFuture<List<ValidationIssue>> validate(UriTarget target,
			MarkdownValidationContext context) {

		if (target == null) {
			throw new IllegalArgumentException("Argument must not be null.");
		}

		if (!startsWithHttpSchemeAndSeparator(target.uriText())) {
			return CompletableFuture.completedFuture(List.of(
					issue(target, MarkdownIssueTypes.LINK_HTTP_INVALID_WEB_ADDRESS, IssueSeverity.ERROR,
							noHttpAddressMessage(target.uriText()))));
		}

		if (target.uri().isEmpty()) {
			return CompletableFuture.completedFuture(List.of(
					issue(target, MarkdownIssueTypes.LINK_HTTP_INVALID_WEB_ADDRESS, IssueSeverity.ERROR,
							unreadableAddressMessage(target.uriText()))));
		}

		return this.reachabilityChecker.check(target.uri().orElseThrow())
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
	 * A target only has such a scheme where its text could be read as a URI at all, so this question alone
	 * would leave every mistyped address to nobody. A scheme is written in either case, so it is compared
	 * in lower case, folded with {@link Locale#ROOT} so that the answer does not depend on the language of
	 * the machine the library runs on.
	 *
	 * @param target the target to ask, never {@code null}
	 * @return whether the target was read as a URI naming one of the two schemes
	 */
	private static boolean namesTheHttpScheme(UriTarget target) {
		return target.scheme()
				.map(scheme -> scheme.toLowerCase(Locale.ROOT))
				.filter(scheme -> SCHEME_HTTP.equals(scheme) || SCHEME_HTTPS.equals(scheme))
				.isPresent();
	}

	/**
	 * Tells whether the text begins with {@code http:} or {@code https:}, whatever it is written after it.
	 * <p>
	 * This is what claims an address the URI syntax refused, {@code https:/example.org} or
	 * {@code https://example.org/a guide}: such a text has no scheme to read, so
	 * {@link #namesTheHttpScheme(UriTarget)} says no about it, and without this question the address that
	 * an author most likely mistyped would be claimed by no validator and pass silently.
	 *
	 * @param targetText the text the target was written with, never {@code null}
	 * @return whether the text was meant as a web address, whether or not it can be read as one
	 */
	private static boolean beginsWithTheHttpScheme(String targetText) {
		String address = targetText.toLowerCase(Locale.ROOT);
		return address.startsWith(SCHEME_HTTP + ":") || address.startsWith(SCHEME_HTTPS + ":");
	}

	/**
	 * Tells whether the text begins with {@code http://} or {@code https://}, the scheme and its separator.
	 * <p>
	 * This is the question {@link #validate(UriTarget, MarkdownValidationContext)} asks first, and it separates the two reports it can
	 * write: a text missing the separator is not a web address at all and is reported as one that has to be
	 * written with {@code https://}, while a text carrying it is a web address whose remainder is then read
	 * and asked about.
	 *
	 * @param targetText the text the target was written with, never {@code null}
	 * @return whether the text begins with a complete web address scheme
	 */
	private static boolean startsWithHttpSchemeAndSeparator(String targetText) {
		String address = targetText.toLowerCase(Locale.ROOT);
		return address.startsWith(SCHEME_HTTP + "://") || address.startsWith(SCHEME_HTTPS + "://");
	}

	private static ValidationIssue issue(UriTarget target, String issueTypeId, IssueSeverity severity,
			String message) {

		return new ValidationIssue(issueTypeId, severity, message,
				target.lineNumber(), target.startOffset(), target.endOffset());
	}

	private static String noHttpAddressMessage(String targetText) {
		return String.format("The referenced web address '%s' seems not to be a valid HTTP web address."
				+ " It has to start with https:// or http://", targetText);
	}

	private static String unreadableAddressMessage(String targetText) {
		return String.format("The referenced web address '%s' seems not to be a valid HTTP web address. ",
				targetText) + whyItCannotBeRead(targetText);
	}

	private static String addressDoesNotAnswerMessage(String targetText, String failureReason) {
		return String.format("The referenced web address '%s' seems not to exist. (Error message: %s)",
				targetText, failureReason);
	}

	private static String addressNotReachableMessage(String targetText, int statusCode) {
		return String.format("The referenced web address '%s' is not reachable (HTTP status code %s).",
				targetText, statusCode);
	}

	/**
	 * Says what the URI syntax has against the given text, in its own words, so that the author
	 * learns where the address goes wrong instead of only that it does.
	 */
	private static String whyItCannotBeRead(String targetText) {
		try {
			new URI(targetText);
			return "";
		} catch (URISyntaxException | IllegalArgumentException exception) {
			String reason = exception.getMessage();
			return reason == null || reason.isBlank() ? exception.getClass().getName() : reason;
		}
	}

}
