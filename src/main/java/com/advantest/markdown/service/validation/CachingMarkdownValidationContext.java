/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.advantest.markdown.MarkdownParserAndHtmlRenderer;
import com.advantest.resources.Resource;
import com.advantest.resources.ResourceContentsCache;
import com.vladsch.flexmark.util.ast.Document;

/**
 * A {@link MarkdownValidationContext} reading every resource at most once.
 * 
 * <p>What was read is remembered by a {@link ResourceContentsCache} of its own, which lives as long
 * as this context and tells two resources apart by their {@link Resource#getResolvedPath() resolved
 * path}. A resource nobody resolved has no such name, so it is read every time it is asked for;
 * asking for its Markdown document is refused, as asking for any resource not named like a Markdown
 * file is.</p>
 * 
 * <p>A failure of reading is remembered as well: a resource that cannot be read is not read again
 * for the next link pointing into it, and every one of those links is still told what went wrong.
 * A parsed document is remembered next to the text it was parsed from, so a resource read first and
 * parsed later is read once.</p>
 * 
 * <p>One instance belongs to one run, and a run is not one thread: a check that cannot answer at
 * once is waited for after the document has been walked, and what it does in between reads
 * resources through this context. The cache and the map of documents therefore bear being read
 * and written at the same time, and a resource two threads ask for at once is read once.</p>
 */
final class CachingMarkdownValidationContext implements MarkdownValidationContext {

	private final MarkdownParserAndHtmlRenderer parserAndRenderer;

	private final ResourceContentsCache contents = new ResourceContentsCache();

	private final Map<String, Document> documentsByResolvedPath = new ConcurrentHashMap<>();

	CachingMarkdownValidationContext(MarkdownParserAndHtmlRenderer parserAndRenderer) {
		if (parserAndRenderer == null) {
			throw new IllegalArgumentException("Argument must not be null.");
		}
		this.parserAndRenderer = parserAndRenderer;
	}

	@Override
	public String getContents(Resource resource) throws IOException {
		if (resource == null) {
			throw new IllegalArgumentException("Argument must not be null.");
		}
		return this.contents.readAllContents(resource);
	}

	@Override
	public Document getParsedMarkdownDocument(Resource markdownResource) throws IOException {
		if (markdownResource == null) {
			throw new IllegalArgumentException("Argument must not be null.");
		}

		String resolvedPath = markdownResource.getResolvedPath();
		if (!this.parserAndRenderer.getMarkdownFileExtensions().isMarkdownFile(resolvedPath)) {
			throw new IllegalArgumentException(
					"Only a Markdown resource is parsed here, and this one is none: " + resolvedPath);
		}

		Document parsedDocument = this.documentsByResolvedPath.get(resolvedPath);
		if (parsedDocument != null) {
			return parsedDocument;
		}

		// read before the document is remembered: reading says what went wrong with a checked
		// exception, which the mapping function of a concurrent map cannot pass on
		String contents = getContents(markdownResource);

		return this.documentsByResolvedPath.computeIfAbsent(resolvedPath,
				path -> this.parserAndRenderer.parseMarkdown(contents, markdownResource));
	}

}
