/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation.uri;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.advantest.markdown.service.validation.IssueSeverity;
import com.advantest.markdown.service.validation.MarkdownIssueTypes;
import com.advantest.markdown.service.validation.MarkdownValidationContext;
import com.advantest.markdown.service.validation.ValidationIssue;

/**
 * Reports a target naming a scheme nothing here knows, e.g. <code>htp://example.org</code>.
 * 
 * <p>Such a target is nobody's business: no resolver looks the resource up, no validator asks
 * whether it is there, and the reader of the document is left with an address that leads nowhere.
 * That is almost always a typing mistake in the scheme, and it is worth saying so — the same
 * address written without a scheme would be looked for and reported.</p>
 * 
 * <p>To keep a legitimate target out of this, a scheme that is in common use in a document is
 * known here even though nothing checks it: a mail address, a telephone number or a file are named
 * that way on purpose, and asking whether such a target is there is either impossible or none of
 * this library's business. Anybody using a scheme beyond those registers a {@link UriValidator}
 * answering for it, which takes the target out of this validator's hands.</p>
 */
public class UnknownSchemeUriValidator implements UriValidator {

	/**
	 * The schemes a document may name without anything here checking the target.
	 * 
	 * <p>They are either not a question of reachability at all, e.g. a mail address or a telephone
	 * number, or they name a resource this library does not go looking for.</p>
	 */
	private static final Set<String> KNOWN_SCHEMES = Set.of(
			"http", "https", "ftp", "ftps", "sftp", "ssh", "file", "data",
			"mailto", "tel", "sms", "callto", "skype", "msteams", "xmpp", "irc", "ircs",
			"news", "nntp", "git", "urn", "magnet", "ws", "wss");

	private static final Pattern SCHEME_OF_TEXT = Pattern.compile("^([A-Za-z][A-Za-z0-9+.\\-]*):");

	private final Set<String> knownSchemes;

	/** Creates the validator knowing the schemes a document commonly names. */
	public UnknownSchemeUriValidator() {
		this(Set.of());
	}

	/**
	 * Creates the validator knowing the schemes a document commonly names and, beyond those, the
	 * given ones, e.g. a scheme a tool of its own answers for.
	 * 
	 * @param additionalKnownSchemes the schemes to be left alone as well, read regardless of upper
	 *                               and lower case, must be neither <code>null</code> nor hold
	 *                               <code>null</code>
	 * @throws IllegalArgumentException if the given schemes are <code>null</code> or hold
	 *         <code>null</code>
	 */
	public UnknownSchemeUriValidator(Set<String> additionalKnownSchemes) {
		if (additionalKnownSchemes == null
				|| additionalKnownSchemes.stream().anyMatch(scheme -> scheme == null)) {
			throw new IllegalArgumentException("Argument must be a set without null elements.");
		}

		Set<String> schemesReadRegardlessOfCase = new HashSet<>();
		for (String scheme : additionalKnownSchemes) {
			schemesReadRegardlessOfCase.add(scheme.toLowerCase(Locale.ROOT));
		}
		this.knownSchemes = Set.copyOf(schemesReadRegardlessOfCase);
	}

	@Override
	public boolean isResponsibleFor(UriTarget target) {
		if (target == null) {
			throw new IllegalArgumentException("Argument must not be null.");
		}

		return schemeOf(target)
				.filter(scheme -> !KNOWN_SCHEMES.contains(scheme))
				.filter(scheme -> !this.knownSchemes.contains(scheme))
				.isPresent();
	}

	@Override
	public CompletableFuture<List<ValidationIssue>> validate(UriTarget target,
			MarkdownValidationContext context) {

		if (target == null) {
			throw new IllegalArgumentException("Argument must not be null.");
		}

		String scheme = schemeOf(target).orElse("");

		return CompletableFuture.completedFuture(List.of(new ValidationIssue(
				MarkdownIssueTypes.LINK_UNKNOWN_TARGET_SCHEME,
				IssueSeverity.WARNING,
				String.format("The referenced target '%s' names the scheme '%s', which nothing knows here,"
						+ " so the target is neither resolved nor checked. Please check the scheme for a"
						+ " typing mistake.", target.uriText(), scheme),
				target.lineNumber(),
				target.startOffset(),
				target.endOffset())));
	}

	/**
	 * Says which scheme the target names, reading it from the text where the URI syntax refuses
	 * the target as a whole, so that a mistyped scheme is recognized even in a target that is no
	 * URI at all.
	 */
	private static Optional<String> schemeOf(UriTarget target) {
		Optional<String> schemeOfUri = target.scheme();
		if (schemeOfUri.isPresent()) {
			return schemeOfUri.map(scheme -> scheme.toLowerCase(Locale.ROOT));
		}

		Matcher schemeInText = SCHEME_OF_TEXT.matcher(target.uriText());
		return schemeInText.find()
				? Optional.of(schemeInText.group(1).toLowerCase(Locale.ROOT))
				: Optional.empty();
	}

}
