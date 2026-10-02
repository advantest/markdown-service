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
import java.util.concurrent.CompletableFuture;

import com.advantest.markdown.service.validation.IssueSeverity;
import com.advantest.markdown.service.validation.MarkdownIssueTypes;
import com.advantest.markdown.service.validation.MarkdownValidationContext;
import com.advantest.markdown.service.validation.ValidationIssue;

/**
 * Reports a target that was meant to be a web address and is none, e.g.
 * <code>https:/example.org</code>.
 * 
 * <p>Two things can be wrong with such a target without anybody being asked: it begins with the
 * scheme but not with the separator that follows it, or it carries both and still cannot be read
 * as an address. Neither question needs the network, which is why they are asked here and not by
 * the validator that asks an address whether it is there: a library that cannot reach anything
 * still knows what an address has to look like, and an author mistyping one is told so.</p>
 * 
 * <p>This validator claims a broken target and nothing else. A target beginning with
 * <code>http</code> or <code>https</code> that can be read as an address is left to whoever asks
 * it, so claiming here never keeps an address from being asked, and a run without a
 * {@link UriReachabilityChecker} loses the asking alone rather than the reading as well.</p>
 */
public class HttpUriSyntaxValidator implements UriValidator {

	private static final String SCHEME_HTTP = "http";

	private static final String SCHEME_HTTPS = "https";

	@Override
	public boolean isResponsibleFor(UriTarget target) {
		if (target == null) {
			throw new IllegalArgumentException("Argument must not be null.");
		}

		return beginsWithTheHttpScheme(target.uriText()) && !isReadableWebAddress(target);
	}

	@Override
	public CompletableFuture<List<ValidationIssue>> validate(UriTarget target,
			MarkdownValidationContext context) {

		if (target == null || context == null) {
			throw new IllegalArgumentException("Argument must not be null.");
		}

		if (!startsWithHttpSchemeAndSeparator(target.uriText())) {
			return CompletableFuture.completedFuture(List.of(
					issue(target, noHttpAddressMessage(target.uriText()))));
		}

		if (target.uri().isEmpty()) {
			return CompletableFuture.completedFuture(List.of(
					issue(target, unreadableAddressMessage(target.uriText()))));
		}

		return CompletableFuture.completedFuture(List.of());
	}

	/**
	 * Tells whether the target is a web address this validator has nothing to say about.
	 * 
	 * <p>Such a target carries the scheme together with its separator and was read as a URI, which
	 * is everything the two reports here are about. It costs a field read and a comparison of
	 * characters: the text is read as a URI once, when the target is created, so asking this of
	 * every target of a document reads nothing twice.</p>
	 *
	 * @param target the target to ask, never <code>null</code>
	 * @return whether the target is a web address that can be read
	 */
	private static boolean isReadableWebAddress(UriTarget target) {
		return startsWithHttpSchemeAndSeparator(target.uriText()) && target.uri().isPresent();
	}

	/**
	 * Tells whether the text begins with {@code http:} or {@code https:}, whatever is written
	 * after it.
	 * 
	 * <p>This is what claims an address the URI syntax refused, {@code https:/example.org} or
	 * {@code https://example.org/a guide}: such a text has no scheme to read, so nothing asking
	 * the URI for one claims it, and without this question the address that an author most likely
	 * mistyped would be claimed by no validator and pass silently.</p>
	 * 
	 * <p>A scheme is written in either case, and the comparison is case-insensitive without
	 * folding the text first, so that asking costs no string.</p>
	 *
	 * @param targetText the text the target was written with, never {@code null}
	 * @return whether the text was meant as a web address, whether or not it can be read as one
	 */
	static boolean beginsWithTheHttpScheme(String targetText) {
		return startsWithIgnoringCase(targetText, SCHEME_HTTP + ":")
				|| startsWithIgnoringCase(targetText, SCHEME_HTTPS + ":");
	}

	/**
	 * Tells whether the text begins with {@code http://} or {@code https://}, the scheme and its
	 * separator.
	 * 
	 * <p>This separates the two reports this validator writes: a text missing the separator is no
	 * web address at all and is reported as one that has to be written with {@code https://},
	 * while a text carrying it is a web address whose remainder is then read.</p>
	 *
	 * @param targetText the text the target was written with, never {@code null}
	 * @return whether the text begins with a complete web address scheme
	 */
	static boolean startsWithHttpSchemeAndSeparator(String targetText) {
		return startsWithIgnoringCase(targetText, SCHEME_HTTP + "://")
				|| startsWithIgnoringCase(targetText, SCHEME_HTTPS + "://");
	}

	private static boolean startsWithIgnoringCase(String text, String beginning) {
		return text.regionMatches(true, 0, beginning, 0, beginning.length());
	}

	private static ValidationIssue issue(UriTarget target, String message) {
		return new ValidationIssue(MarkdownIssueTypes.LINK_HTTP_INVALID_WEB_ADDRESS, IssueSeverity.ERROR,
				message, target.lineNumber(), target.startOffset(), target.endOffset());
	}

	private static String noHttpAddressMessage(String targetText) {
		return String.format("The referenced web address '%s' seems not to be a valid HTTP web address."
				+ " It has to start with https:// or http://", targetText);
	}

	private static String unreadableAddressMessage(String targetText) {
		return String.format("The referenced web address '%s' seems not to be a valid HTTP web address. ",
				targetText) + whyItCannotBeRead(targetText);
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
