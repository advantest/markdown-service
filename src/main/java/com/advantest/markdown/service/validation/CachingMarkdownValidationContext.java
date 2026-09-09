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
 * A {@link MarkdownValidationContext} reading every document at most once.
 * 
 * <p>What was read is remembered under the resource's {@link Resource#getResolvedPath() resolved
 * path}, which is what tells two resources apart for whoever resolved them. That name is also what
 * says whether the resource is Markdown at all: a resource named otherwise, and a resource nobody
 * resolved and which therefore has no name, is refused rather than read.</p>
 * 
 * <p>A failure is remembered as well: a document that cannot be read is not read again for the
 * next link pointing into it, and every one of those links is still told what went wrong.</p>
 * 
 * <p>One instance belongs to one run and is used by the one thread walking the document, so
 * nothing here is synchronized.</p>
 */
final class CachingMarkdownValidationContext implements MarkdownValidationContext {

	private final MarkdownParserAndHtmlRenderer parserAndRenderer;

	private final Map<String, Object> documentsByResolvedPath = new HashMap<>();

	CachingMarkdownValidationContext(MarkdownParserAndHtmlRenderer parserAndRenderer) {
		if (parserAndRenderer == null) {
			throw new IllegalArgumentException("Argument must not be null.");
		}
		this.parserAndRenderer = parserAndRenderer;
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

		Object remembered = this.documentsByResolvedPath.get(resolvedPath);
		if (remembered == null) {
			try {
				remembered = readAndParse(markdownResource);
			} catch (IOException failure) {
				remembered = failure;
			}
			this.documentsByResolvedPath.put(resolvedPath, remembered);
		}

		if (remembered instanceof IOException failure) {
			throw failure;
		}
		return (Document) remembered;
	}

	private Document readAndParse(Resource markdownResource) throws IOException {
		return this.parserAndRenderer.parseMarkdown(markdownResource.readAllContents(), markdownResource);
	}

}
