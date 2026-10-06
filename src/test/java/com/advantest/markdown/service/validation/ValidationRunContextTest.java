/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.advantest.markdown.MarkdownParserAndHtmlRenderer;
import com.advantest.markdown.service.parsing.ParsedMarkdownDocumentsCache;
import com.advantest.markdown.service.resources.ResourceContentsCache;
import com.advantest.resources.Resource;
import com.advantest.resources.ResourceKind;
import com.vladsch.flexmark.util.ast.Document;

/**
 * Checks that a validation run reads and parses through caches of its own.
 */
class ValidationRunContextTest {

	/** A resource answering with a fixed text and counting how often it was read. */
	private static final class CountingResource implements Resource {

		private final String resolvedPath;

		private final String contents;

		private int readCount;

		private CountingResource(String resolvedPath, String contents) {
			this.resolvedPath = resolvedPath;
			this.contents = contents;
		}

		@Override
		public String getResolvedPath() {
			return this.resolvedPath;
		}

		@Override
		public boolean exists() {
			return true;
		}

		@Override
		public Optional<ResourceKind> getKind() {
			return Optional.of(ResourceKind.FILE);
		}

		@Override
		public BufferedReader readContents() throws IOException {
			this.readCount++;
			return new BufferedReader(new StringReader(this.contents));
		}
	}

	private final MarkdownParserAndHtmlRenderer parserAndRenderer = new MarkdownParserAndHtmlRenderer();

	@Test
	void aResourceReadAndParsedInOneRunIsReadOnce() throws IOException {
		MarkdownValidationContext context = MarkdownValidationContext.parsingWith(this.parserAndRenderer);
		CountingResource resource = new CountingResource("/docs/guide.md", "# Guide\n");

		String contents = context.getContents(resource);
		Document firstDocument = context.getParsedMarkdownDocument(resource);
		Document secondDocument = context.getParsedMarkdownDocument(resource);

		assertEquals("# Guide\n", contents);
		assertEquals("# Guide\n", firstDocument.getChars().toString());
		assertSame(firstDocument, secondDocument, "A document is expected to be parsed once per run.");
		assertEquals(1, resource.readCount, "A resource is expected to be read once per run.");
	}

	@Test
	void aNewRunReadsAndParsesAgain() throws IOException {
		CountingResource resource = new CountingResource("/docs/guide.md", "# Guide\n");

		Document firstRunsDocument =
				MarkdownValidationContext.parsingWith(this.parserAndRenderer).getParsedMarkdownDocument(resource);
		Document secondRunsDocument =
				MarkdownValidationContext.parsingWith(this.parserAndRenderer).getParsedMarkdownDocument(resource);

		assertNotSame(firstRunsDocument, secondRunsDocument, "A run is not expected to share its documents.");
		assertEquals(2, resource.readCount, "A run is not expected to share what it read.");
	}

	@Test
	void aContextWithoutAParserCannotBeCreated() {
		assertThrows(IllegalArgumentException.class, () -> MarkdownValidationContext.parsingWith(null));
	}

	@Test
	void aContextWithoutItsCachesCannotBeCreated() {
		ResourceContentsCache contents = new ResourceContentsCache();
		ParsedMarkdownDocumentsCache documents = new ParsedMarkdownDocumentsCache(this.parserAndRenderer, contents);

		assertThrows(IllegalArgumentException.class, () -> new ValidationRunContext(null, documents));
		assertThrows(IllegalArgumentException.class, () -> new ValidationRunContext(contents, null));
	}

}