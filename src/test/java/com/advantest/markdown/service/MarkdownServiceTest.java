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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.advantest.markdown.MarkdownParserAndHtmlRenderer;
import com.advantest.markdown.service.resources.ResourceResolverRegistry;
import com.advantest.markdown.service.resources.walk.DefaultMarkdownValidationResourcesFilter;
import com.advantest.markdown.service.resources.walk.ResourceFilter;
import com.advantest.markdown.service.validation.MarkdownValidationRun;
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

		assertTrue(Pattern.compile("<em[^>]*>world</em>").matcher(html).find());
	}

	@Test
	void constructorRejectsNullArguments() {
		assertThrows(IllegalArgumentException.class,
				() -> new MarkdownService(null, ResourceResolverRegistry.ofLocalFileSystem()));
		assertThrows(IllegalArgumentException.class,
				() -> new MarkdownService(new MarkdownParserAndHtmlRenderer(), null));
	}

	@Test
	void theResourceFilterSkipsSymbolicLinksByDefault() {
		try (MarkdownService built = MarkdownService.builderNotCheckingUriReachability().build()) {
			assertTrue(built.resourceFilter() instanceof DefaultMarkdownValidationResourcesFilter,
					"The default filter is expected to be the library's.");
			assertTrue(built.resourceFilter().skipsFolder(Path.of("a"), Path.of("a/link"), linkAttributes()),
					"The default filter is expected to skip a symbolic link.");
		}
	}

	@Test
	void theBuilderSetsTheResourceFilterOfTheRuns(@TempDir Path tempDir) throws IOException {
		Files.createDirectories(tempDir.resolve("doc"));
		Files.writeString(tempDir.resolve("doc/a.md"), "# A\n", StandardCharsets.UTF_8);
		Files.writeString(tempDir.resolve("b.md"), "# B\n", StandardCharsets.UTF_8);
		ResourceFilter onlyDoc = DefaultMarkdownValidationResourcesFilter.emptyBuilder().onlyPaths("doc/").build();

		try (MarkdownService built = MarkdownService.builderNotCheckingUriReachability().withResourceFilter(onlyDoc)
				.build()) {
			assertSame(onlyDoc, built.resourceFilter(), "The service is expected to keep the filter it was given.");
			try (MarkdownValidationRun run = built.createValidationRun()) {
				assertEquals(Set.of(LocalFileSystemResource.of(tempDir.resolve("doc/a.md").toAbsolutePath().normalize())),
						run.validateTree(tempDir).join().keySet(), "A run is expected to walk with the service's filter.");
			}
			try (MarkdownValidationRun run = built.createValidationRun(
					DefaultMarkdownValidationResourcesFilter.emptyBuilder().build())) {
				assertEquals(2, run.validateTree(tempDir).join().size(),
						"A run created with a filter of its own is expected to walk with that one.");
			}
		}
	}

	@Test
	void aResourceFilterMustBeGiven() {
		MarkdownService.Builder builder = MarkdownService.builderNotCheckingUriReachability();
		assertThrows(IllegalArgumentException.class, () -> builder.withResourceFilter(null));

		try (MarkdownService built = builder.build()) {
			assertThrows(IllegalArgumentException.class, () -> built.createValidationRun(null));
		}
	}

	@Test
	void aClosedServiceNeitherAnswersItsResourceFilterNorCreatesARunWithAFilter() {
		MarkdownService built = MarkdownService.builderNotCheckingUriReachability().build();
		ResourceFilter filter = built.resourceFilter();
		built.close();

		assertThrows(IllegalStateException.class, built::resourceFilter);
		assertThrows(IllegalStateException.class, () -> built.createValidationRun(filter));
	}

	private static BasicFileAttributes linkAttributes() {
		BasicFileAttributes attributes = mock(BasicFileAttributes.class);
		when(attributes.isSymbolicLink()).thenReturn(true);
		return attributes;
	}
}