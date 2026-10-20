/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.advantest.markdown.MarkdownParserAndHtmlRenderer;
import com.advantest.markdown.service.validation.anchor.MarkdownSectionAnchorsCache;
import com.advantest.markdown.service.resources.ResourceContentsCache;
import com.advantest.resources.Resource;
import com.advantest.resources.ResourceKind;

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
		CountingResource resource = new CountingResource("/docs/guide.md", "# Guide {#guide}\n");

		String contents = context.getContents(resource);
		Set<String> firstAnchors = context.getMarkdownSectionAnchors(resource);
		Set<String> secondAnchors = context.getMarkdownSectionAnchors(resource);

		assertEquals("# Guide {#guide}\n", contents);
		assertEquals(Set.of("guide"), firstAnchors);
		assertSame(firstAnchors, secondAnchors, "A document is expected to be parsed once per run.");
		assertEquals(1, resource.readCount, "A resource is expected to be read once per run.");
	}

	@Test
	void aNewRunReadsAndParsesAgain() throws IOException {
		CountingResource resource = new CountingResource("/docs/guide.md", "# Guide\n");

		MarkdownValidationContext.parsingWith(this.parserAndRenderer).getMarkdownSectionAnchors(resource);
		MarkdownValidationContext.parsingWith(this.parserAndRenderer).getMarkdownSectionAnchors(resource);

		assertEquals(2, resource.readCount, "A run is not expected to share what it read.");
	}

	@Test
	void aContextWithoutAParserCannotBeCreated() {
		assertThrows(IllegalArgumentException.class, () -> MarkdownValidationContext.parsingWith(null));
	}

	@Test
	void aContextWithoutItsCachesCannotBeCreated() {
		ResourceContentsCache contents = new ResourceContentsCache();
		MarkdownSectionAnchorsCache anchors = new MarkdownSectionAnchorsCache(this.parserAndRenderer, contents);

		assertThrows(IllegalArgumentException.class, () -> new ValidationRunContext(null, anchors));
		assertThrows(IllegalArgumentException.class, () -> new ValidationRunContext(contents, null));
	}

}