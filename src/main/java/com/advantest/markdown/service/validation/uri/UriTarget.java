/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation.uri;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The target of a link, an image or a link reference definition that names a scheme, handed to the
 * {@link UriValidator}s together with the place it was found.
 * 
 * <p>The target is passed as it is written in the document, because a rule may have something to
 * say about a text that is no address at all, and it is passed as a {@link URI} where the URI
 * syntax accepts it, so that it is read once instead of once per rule.</p>
 * 
 * @param uriText the target as it is written in the document, must neither be <code>null</code> nor
 *        blank
 * @param uri the target read as a URI, or {@link Optional#empty()} where the URI syntax rejects
 *        the text, must not be <code>null</code>
 * @param lineNumber the line the target is written in, starting at 1
 * @param startOffset the first character of the target in the document, starting at 0, inclusive
 * @param endOffset the character following the target, exclusive, must not be smaller than the
 *        start offset
 */
public record UriTarget(String uriText, Optional<URI> uri, int lineNumber, int startOffset, int endOffset) {

	private static final Logger LOG = LoggerFactory.getLogger(UriTarget.class);

	/**
	 * Creates a target, rejecting incomplete or contradictory data.
	 * 
	 * @throws IllegalArgumentException if an argument is <code>null</code>, blank or outside its
	 *         allowed range
	 */
	public UriTarget {
		if (uriText == null || uriText.isBlank()) {
			throw new IllegalArgumentException("A target text is required.");
		}
		if (uri == null) {
			throw new IllegalArgumentException("A target either is a URI or is none, but never null.");
		}
		if (lineNumber < 1) {
			throw new IllegalArgumentException("Line numbers start at 1, but was: " + lineNumber);
		}
		if (startOffset < 0) {
			throw new IllegalArgumentException("Offsets start at 0, but the start offset was: " + startOffset);
		}
		if (endOffset < startOffset) {
			throw new IllegalArgumentException(
					"The end offset " + endOffset + " is smaller than the start offset " + startOffset + ".");
		}
	}

	/**
	 * Creates a target from the text it is written with, reading it as a URI where that is
	 * possible.
	 * 
	 * @param uriText the target as it is written in the document, must neither be <code>null</code>
	 *        nor blank
	 * @param lineNumber the line the target is written in, starting at 1
	 * @param startOffset the first character of the target in the document, starting at 0
	 * @param endOffset the character following the target, exclusive
	 * @return the target, never <code>null</code>
	 * @throws IllegalArgumentException if an argument is <code>null</code>, blank or outside its
	 *         allowed range
	 */
	public static UriTarget of(String uriText, int lineNumber, int startOffset, int endOffset) {
		return new UriTarget(uriText, readAsUri(uriText), lineNumber, startOffset, endOffset);
	}

	/**
	 * Tells the scheme this target names, e.g. <code>https</code>.
	 * 
	 * @return the scheme, or {@link Optional#empty()} where the target names none the URI syntax
	 *         recognizes, never <code>null</code>
	 */
	public Optional<String> scheme() {
		return this.uri.map(URI::getScheme).filter(scheme -> !scheme.isBlank());
	}

	private static Optional<URI> readAsUri(String uriText) {
		if (uriText == null || uriText.isBlank()) {
			return Optional.empty();
		}

		try {
			return Optional.of(new URI(uriText));
		} catch (URISyntaxException exception) {
			LOG.debug("The link target '{}' is no valid URI, so nothing is known about its scheme.",
					uriText, exception);
			return Optional.empty();
		}
	}

}
