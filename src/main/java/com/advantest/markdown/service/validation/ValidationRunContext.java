/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation;

import java.io.IOException;

import com.advantest.markdown.ParsedMarkdownDocumentsCache;
import com.advantest.resources.Resource;
import com.advantest.resources.ResourceContentsCache;
import com.vladsch.flexmark.util.ast.Document;

/**
 * The {@link MarkdownValidationContext} of one validation run, reading and parsing through the caches
 * the run owns.
 * 
 * <p>Both caches live as long as this context, so a resource is read at most once and a Markdown
 * resource parsed at most once in a run, and nothing is kept for the next run. The documents are
 * parsed from the contents the run read, so a resource read first and parsed later is read once.</p>
 * 
 * @param contents  the cache every resource of the run is read through, must not be
 *                  <code>null</code>
 * @param documents the cache every Markdown resource of the run is parsed through, reading through
 *                  <code>contents</code>, must not be <code>null</code>
 */
record ValidationRunContext(ResourceContentsCache contents, ParsedMarkdownDocumentsCache documents)
		implements MarkdownValidationContext {

	ValidationRunContext {
		if (contents == null || documents == null) {
			throw new IllegalArgumentException("Arguments must not be null.");
		}
	}

	@Override
	public String getContents(Resource resource) throws IOException {
		return this.contents.readAllContents(resource);
	}

	@Override
	public Document getParsedMarkdownDocument(Resource markdownResource) throws IOException {
		return this.documents.parseMarkdown(markdownResource);
	}

}