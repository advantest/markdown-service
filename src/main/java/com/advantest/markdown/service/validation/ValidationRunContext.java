/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

import com.advantest.markdown.service.validation.anchor.MarkdownSectionAnchorsCache;
import com.advantest.markdown.service.resources.ResourceContentsCache;
import com.advantest.markdown.service.validation.uri.UriReachabilityChecker;
import com.advantest.resources.Resource;

/**
 * The {@link MarkdownValidationContext} of one validation run, reading and parsing through the caches
 * the run owns.
 * 
 * <p>Both caches live as long as this context, so a resource is read at most once and a Markdown
 * resource parsed at most once in a run, and nothing is kept for the next run. The documents are
 * parsed from the contents the run read, so a resource read first and parsed later is read once.
 * Of a parsed document, only its section anchors are kept.</p>
 * 
 * @param contents  the cache every resource of the run is read through, must not be
 *                  <code>null</code>
 * @param anchors   the cache every Markdown resource of the run is parsed through to learn its
 *                  section anchors, reading through <code>contents</code>, must not be
 *                  <code>null</code>
 * @param uriReachabilityChecker the check of the run asking an address whether it is there, may be
 *                               <code>null</code>, in which case no address is asked about
 */
record ValidationRunContext(ResourceContentsCache contents, MarkdownSectionAnchorsCache anchors,
		UriReachabilityChecker.OfRun uriReachabilityChecker) implements MarkdownValidationContext {

	ValidationRunContext {
		if (contents == null || anchors == null) {
			throw new IllegalArgumentException("Arguments must not be null.");
		}
	}

	/**
	 * Creates the context of a run asking no address whether it is there.
	 */
	ValidationRunContext(ResourceContentsCache contents, MarkdownSectionAnchorsCache anchors) {
		this(contents, anchors, null);
	}

	@Override
	public String getContents(Resource resource) throws IOException {
		return this.contents.readAllContents(resource);
	}

	@Override
	public Set<String> getMarkdownSectionAnchors(Resource markdownResource) throws IOException {
		return this.anchors.sectionAnchorsOf(markdownResource);
	}

	@Override
	public Optional<UriReachabilityChecker.OfRun> getUriReachabilityChecker() {
		return Optional.ofNullable(this.uriReachabilityChecker);
	}

}