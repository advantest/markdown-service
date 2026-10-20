/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation.anchor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.advantest.markdown.MarkdownFileExtensions;
import com.advantest.markdown.MarkdownParserAndHtmlRenderer;
import com.advantest.markdown.service.resources.ResourceContentsCache;
import com.advantest.resources.Resource;
import com.advantest.resources.ResourceKind;
import com.advantest.resources.UnresolvedResource;
import com.vladsch.flexmark.util.ast.Document;

/**
 * Checks what a run remembers about the section anchors of the documents it parsed.
 */
class MarkdownSectionAnchorsCacheTest {

	/** A resource answering with a fixed text, or failing, and counting how often it was read. */
	private static final class CountingResource implements Resource {

		private final String resolvedPath;

		private final String contents;

		private final Throwable failure;

		private final AtomicInteger readCount = new AtomicInteger();

		private CountingResource(String resolvedPath, String contents) {
			this(resolvedPath, contents, null);
		}

		private CountingResource(String resolvedPath, String contents, Throwable failure) {
			this.resolvedPath = resolvedPath;
			this.contents = contents;
			this.failure = failure;
		}

		static CountingResource failing(String resolvedPath, Throwable failure) {
			return new CountingResource(resolvedPath, null, failure);
		}

		int reads() {
			return this.readCount.get();
		}

		@Override
		public String getResolvedPath() {
			return this.resolvedPath;
		}

		@Override
		public boolean exists() {
			return this.contents != null;
		}

		@Override
		public Optional<ResourceKind> getKind() {
			return exists() ? Optional.of(ResourceKind.FILE) : Optional.empty();
		}

		@Override
		public BufferedReader readContents() throws IOException {
			this.readCount.incrementAndGet();
			if (this.failure instanceof IOException ioFailure) {
				throw ioFailure;
			}
			if (this.failure instanceof Error error) {
				throw error;
			}
			return new BufferedReader(new StringReader(this.contents));
		}
	}

	private final MarkdownParserAndHtmlRenderer parserAndRenderer = new MarkdownParserAndHtmlRenderer();

	private final ResourceContentsCache contentsCache = new ResourceContentsCache();

	private final MarkdownSectionAnchorsCache cache =
			new MarkdownSectionAnchorsCache(this.parserAndRenderer, this.contentsCache);

	@Test
	void parsesAResourceAskedForTwiceOnce() throws IOException {
		CountingResource resource = new CountingResource("/docs/guide.md", "# Guide\n");

		Set<String> firstAnswer = this.cache.sectionAnchorsOf(resource);
		Set<String> secondAnswer = this.cache.sectionAnchorsOf(resource);

		assertSame(firstAnswer, secondAnswer, "The anchors found first are expected again.");
		assertEquals(1, resource.reads(), "A document is expected to be read once.");
	}

	@Test
	void readsAResourceWhoseContentsWereReadBeforeOnce() throws IOException {
		CountingResource resource = new CountingResource("/docs/guide.md", "# Guide\n");

		this.contentsCache.readAllContents(resource);
		this.cache.sectionAnchorsOf(resource);

		assertEquals(1, resource.reads(), "Reading and parsing are expected to share one read.");
	}

	@Test
	void tellsTwoResourcesApartByTheirResolvedPath() throws IOException {
		CountingResource guide = new CountingResource("/docs/guide.md", "# Guide {#guide}\n");
		CountingResource notes = new CountingResource("/docs/notes.md", "# Notes {#notes}\n");

		assertEquals(Set.of("guide"), this.cache.sectionAnchorsOf(guide));
		assertEquals(Set.of("notes"), this.cache.sectionAnchorsOf(notes));
	}

	@Test
	void doesNotReadAResourceThatCannotBeReadAgain() {
		CountingResource missing = CountingResource.failing("/docs/gone.md", new IOException("Gone."));

		IOException firstFailure = assertThrows(IOException.class, () -> this.cache.sectionAnchorsOf(missing));
		IOException secondFailure = assertThrows(IOException.class, () -> this.cache.sectionAnchorsOf(missing));

		assertSame(firstFailure, secondFailure, "Every caller is expected to be told what went wrong.");
		assertEquals(1, missing.reads(), "A failing document is expected to be read once.");
	}

	@Test
	void remembersAFailureOfParsing() {
		MarkdownParserAndHtmlRenderer failingParser = new MarkdownParserAndHtmlRenderer() {
			@Override
			public Document parseMarkdown(String markdownSourceCode, Resource documentResource) {
				throw new IllegalStateException("The parser broke.");
			}
		};
		MarkdownSectionAnchorsCache cacheParsingWithFailures =
				new MarkdownSectionAnchorsCache(failingParser, this.contentsCache);
		CountingResource resource = new CountingResource("/docs/guide.md", "# Guide\n");

		IllegalStateException firstFailure = assertThrows(IllegalStateException.class,
				() -> cacheParsingWithFailures.sectionAnchorsOf(resource));
		IllegalStateException secondFailure = assertThrows(IllegalStateException.class,
				() -> cacheParsingWithFailures.sectionAnchorsOf(resource));

		assertSame(firstFailure, secondFailure);
	}

	@Test
	void remembersAnErrorOfReading() {
		CountingResource resource = CountingResource.failing("/docs/broken.md", new LinkageError("Missing."));

		assertThrows(LinkageError.class, () -> this.cache.sectionAnchorsOf(resource));
		assertThrows(LinkageError.class, () -> this.cache.sectionAnchorsOf(resource));

		assertEquals(1, resource.reads());
	}

	@Test
	void parsesADocumentWithTheResourceItCameFrom() throws IOException {
		AtomicReference<Resource> parsedWith = new AtomicReference<>();
		MarkdownParserAndHtmlRenderer recordingParser = new MarkdownParserAndHtmlRenderer() {
			@Override
			public Document parseMarkdown(String markdownSourceCode, Resource documentResource) {
				parsedWith.set(documentResource);
				return super.parseMarkdown(markdownSourceCode, documentResource);
			}
		};
		CountingResource resource = new CountingResource("/docs/guide.md", "# Guide\n");

		new MarkdownSectionAnchorsCache(recordingParser, this.contentsCache).sectionAnchorsOf(resource);

		assertSame(resource, parsedWith.get(),
				"The document is expected to be parsed with the resource it was read from.");
	}

	@Test
	void answersAnchorsThatCannotBeChanged() throws IOException {
		CountingResource resource = new CountingResource("/docs/guide.md", "# Guide\n");

		Set<String> anchors = this.cache.sectionAnchorsOf(resource);

		assertThrows(UnsupportedOperationException.class, () -> anchors.add("notes"),
				"Anchors shared by several threads are expected to stay as they were found.");
	}

	@Test
	void doesNotRememberAnUnresolvedResource() {
		UnresolvedResource unresolved = new UnresolvedResource("guide.md");

		IOException firstFailure = assertThrows(IOException.class, () -> this.cache.sectionAnchorsOf(unresolved));
		IOException secondFailure = assertThrows(IOException.class, () -> this.cache.sectionAnchorsOf(unresolved));

		assertNotSame(firstFailure, secondFailure, "An unresolved resource is expected to be read every time.");
	}

	@Test
	void refusesAResourceWithoutAPath() {
		CountingResource unnamed = new CountingResource("", "# Guide\n");
		CountingResource nameless = new CountingResource(null, "# Guide\n");

		assertThrows(IllegalArgumentException.class, () -> this.cache.sectionAnchorsOf(unnamed));
		assertThrows(IllegalArgumentException.class, () -> this.cache.sectionAnchorsOf(nameless));
		assertEquals(0, unnamed.reads() + nameless.reads(), "Without a name nothing says that this is Markdown.");
	}

	@Test
	void refusesAResourceThatIsNoMarkdownFile() {
		CountingResource plainText = new CountingResource("/docs/guide.txt", "# Guide\n");

		assertThrows(IllegalArgumentException.class, () -> this.cache.sectionAnchorsOf(plainText));
		assertEquals(0, plainText.reads(),
				"Reading a document that is no Markdown and parsing it as Markdown answers about nothing.");
	}

	@Test
	void ignoresHowTheMarkdownExtensionIsWritten() throws IOException {
		CountingResource shouted = new CountingResource("/docs/GUIDE.MD", "# Guide {#guide}\n");

		assertEquals(Set.of("guide"), this.cache.sectionAnchorsOf(shouted));
	}

	@Test
	void takesAsMarkdownWhatTheParserAccepts() throws IOException {
		MarkdownSectionAnchorsCache cacheAcceptingMore = new MarkdownSectionAnchorsCache(
				MarkdownParserAndHtmlRenderer.builder()
						.withMarkdownFileExtensions(MarkdownFileExtensions.accepting("md", "mkd"))
						.build(),
				this.contentsCache);
		CountingResource resource = new CountingResource("/docs/guide.mkd", "# Guide {#guide}\n");

		assertEquals(Set.of("guide"), cacheAcceptingMore.sectionAnchorsOf(resource));
		assertThrows(IllegalArgumentException.class, () -> this.cache.sectionAnchorsOf(resource),
				"A cache whose parser was not told about that extension is expected to refuse it.");
	}

	@Test
	void parsesAResourceManyThreadsAskForAtOnceOnce() throws Exception {
		CountingResource resource = new CountingResource("/docs/guide.md", "# Guide\n");
		int threadCount = 8;
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Set<String>>> answers = new ArrayList<>();

		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		try {
			for (int i = 0; i < threadCount; i++) {
				answers.add(executor.submit(() -> {
					start.await();
					return this.cache.sectionAnchorsOf(resource);
				}));
			}
			start.countDown();

			Set<String> firstAnswer = answers.get(0).get();
			for (Future<Set<String>> answer : answers) {
				assertSame(firstAnswer, answer.get());
			}
		} finally {
			executor.shutdownNow();
		}
		assertEquals(1, resource.reads());
	}

	@Test
	void refusesNoResource() {
		assertThrows(IllegalArgumentException.class, () -> this.cache.sectionAnchorsOf(null));
	}

	@Test
	void cannotBeCreatedWithoutAParserOrAContentsCache() {
		assertThrows(IllegalArgumentException.class,
				() -> new MarkdownSectionAnchorsCache(null, this.contentsCache));
		assertThrows(IllegalArgumentException.class,
				() -> new MarkdownSectionAnchorsCache(this.parserAndRenderer, null));
	}

}
