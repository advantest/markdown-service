/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import com.advantest.markdown.MarkdownParserAndHtmlRenderer;
import com.advantest.resources.Resource;
import com.vladsch.flexmark.util.ast.Document;

/**
 * A {@link MarkdownValidationContext} reading every resource at most once.
 * 
 * <p>What was read is remembered under the resource's {@link Resource#getResolvedPath() resolved
 * path}, which is what tells two resources apart for whoever resolved them. A resource nobody
 * resolved has no such name, so nothing is remembered for it; asking for its Markdown document is
 * refused, as asking for any resource not named like a Markdown file is.</p>
 * 
 * <p>A failure of reading is remembered as well: a resource that cannot be read is not read again
 * for the next link pointing into it, and every one of those links is still told what went wrong.
 * A parsed document is remembered next to the text it was parsed from, so a resource read first and
 * parsed later is read once.</p>
 * 
 * <p>One instance belongs to one run and is used by the one thread walking the document, so
 * nothing here is synchronized.</p>
 */
final class CachingMarkdownValidationContext implements MarkdownValidationContext {

	private final MarkdownParserAndHtmlRenderer parserAndRenderer;

	private final Map<String, Object> contentsByResolvedPath = new HashMap<>();

	private final Map<String, Document> documentsByResolvedPath = new HashMap<>();

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

		String resolvedPath = resource.getResolvedPath();
		if (resolvedPath == null || resolvedPath.isEmpty()) {
			// nothing tells this resource from another one, so nothing can be remembered for it
			return resource.readAllContents();
		}

		Object remembered = this.contentsByResolvedPath.get(resolvedPath);
		if (remembered == null) {
			try {
				remembered = resource.readAllContents();
			} catch (IOException failure) {
				remembered = failure;
			}
			this.contentsByResolvedPath.put(resolvedPath, remembered);
		}

		if (remembered instanceof IOException failure) {
			throw failure;
		}
		return (String) remembered;
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
		if (parsedDocument == null) {
			parsedDocument = this.parserAndRenderer.parseMarkdown(getContents(markdownResource), markdownResource);
			this.documentsByResolvedPath.put(resolvedPath, parsedDocument);
		}
		return parsedDocument;
	}

}
