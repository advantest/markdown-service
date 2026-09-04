/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2022-2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.advantest.markdown.MarkdownParserAndHtmlRenderer;
import com.advantest.markdown.service.resources.ResourceResolverRegistry;
import com.advantest.resources.LocalFileSystemResource;
import com.advantest.resources.Resource;
import com.vladsch.flexmark.util.ast.Document;

class MarkdownServiceTest {

	private MarkdownParserAndHtmlRenderer delegate;
	private MarkdownService service;

	@BeforeEach
	void setUp() {
		this.delegate = spy(new MarkdownParserAndHtmlRenderer());
		this.service = new MarkdownService(this.delegate, ResourceResolverRegistry.ofLocalFileSystem());
	}

	@Test
	void parseMarkdownFromStringDelegates() {
		Document document = this.service.parseMarkdown("# Title");

		assertNotNull(document);
		verify(this.delegate).parseMarkdown("# Title");
	}

	@Test
	void parseMarkdownWithDocumentResourceDelegates(@TempDir Path tempDir) throws IOException {
		Path markdownFilePath = tempDir.resolve("example.md");
		Files.writeString(markdownFilePath, "# Title", StandardCharsets.UTF_8);
		Resource documentResource = LocalFileSystemResource.of(markdownFilePath);

		Document document = this.service.parseMarkdown("# Title", documentResource);

		assertNotNull(document);
		assertEquals(documentResource, MarkdownParserAndHtmlRenderer.KEY_DOCUMENT_RESOURCE.get(document));
		verify(this.delegate).parseMarkdown("# Title", documentResource);
	}

	@Test
	void parseMarkdownAndRenderHtmlWithDocumentResourceDelegates(@TempDir Path tempDir) {
		Resource documentResource = LocalFileSystemResource.of(tempDir.resolve("example.md"));

		String html = this.service.parseMarkdownAndRenderHtml("# Title", documentResource);

		assertNotNull(html);
		assertFalse(html.isBlank());
		verify(this.delegate).parseMarkdownAndRenderHtml("# Title", documentResource);
	}

	@Test
	void renderHtmlDelegates() {
		Document document = this.delegate.parseMarkdown("# Title");

		String html = this.service.renderHtml(document);

		assertNotNull(html);
		assertFalse(html.isBlank());
		verify(this.delegate).renderHtml(document);
	}

	@Test
	void parseMarkdownAndRenderHtmlDelegates() {
		String html = this.service.parseMarkdownAndRenderHtml("# Title");

		assertNotNull(html);
		assertFalse(html.isBlank());
		verify(this.delegate).parseMarkdownAndRenderHtml("# Title");
	}

	@Test
	void parseMarkdownRejectsAMissingDocumentResource() {
		assertThrows(IllegalArgumentException.class, () -> this.service.parseMarkdown("# Title", null));
		assertThrows(IllegalArgumentException.class,
				() -> this.service.parseMarkdownAndRenderHtml("# Title", null));
		assertThrows(IllegalArgumentException.class, () -> this.service.validateMarkdown("# Title", null));
	}

	@Test
	void resultOfDelegateIsReturnedUnchanged() {
		AtomicReference<Document> delegateResult = new AtomicReference<>();
		doAnswer(invocation -> {
			Document document = (Document) invocation.callRealMethod();
			delegateResult.set(document);
			return document;
		}).when(this.delegate).parseMarkdown("text");

		Document actualDocument = this.service.parseMarkdown("text");

		assertSame(delegateResult.get(), actualDocument);
		verify(this.delegate).parseMarkdown("text");
	}

	@Test
	void defaultConstructorCreatesWorkingService() {
		MarkdownService serviceWithDefaultDelegate = new MarkdownService();

		String html = serviceWithDefaultDelegate.parseMarkdownAndRenderHtml("Hello *world*");

		assertTrue(html.contains("<em>world</em>"));
	}

	@Test
	void constructorRejectsNullArguments() {
		assertThrows(IllegalArgumentException.class,
				() -> new MarkdownService(null, ResourceResolverRegistry.ofLocalFileSystem()));
		assertThrows(IllegalArgumentException.class,
				() -> new MarkdownService(new MarkdownParserAndHtmlRenderer(), null));
	}

}
