/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation.anchor;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

import com.advantest.markdown.MarkdownParserAndHtmlRenderer;
import com.advantest.resources.Resource;
import com.advantest.markdown.service.resources.ResourceContentsCache;
import com.advantest.resources.UnresolvedResource;
import com.vladsch.flexmark.util.ast.Document;

/**
 * Remembers the section anchors the Markdown resources declare, so that a resource is read and
 * parsed at most once for as long as this cache lives.
 * 
 * <p>A cache is meant to live as long as one run, e.g. one validation of a document or of a
 * directory tree: whoever runs creates it when the run starts and drops it when the run ends. That
 * is why it has neither a bound nor a way to clear it.</p>
 * 
 * <p>What is remembered is the set of anchors, never the parsed document. A parsed document can be
 * changed by whoever holds it, and even reading some of its parts fills in what it computes lazily,
 * so it is not to be read by two threads at the same time. A run checks several documents at once,
 * and several of them may point into the same one; the document is therefore parsed by the thread
 * asking first, its anchors are taken out of it on that thread, and the document is dropped. Every
 * thread gets the same set, which cannot be changed.</p>
 * 
 * <p>The text is read through the {@link ResourceContentsCache} this cache is given, so a resource
 * whose contents a run read first and parses later is read once. A document is parsed with the
 * resource it was read from, so that what it refers to resolves against its own location.</p>
 * 
 * <p>Only a resource named like a Markdown file is parsed, i.e. one carrying an extension the parser
 * accepts as Markdown. Reading anything else and parsing it as Markdown would answer about a
 * document that never existed, so asking for it is a programming error.</p>
 * 
 * <p>A document is remembered under the resource's {@link Resource#getResolvedPath() resolved
 * path}. An {@link UnresolvedResource}, whose path is only the target as it was written, is read
 * and parsed every time it is asked for. A failure is remembered just as long as the anchors would
 * have been, and several threads may ask at the same time; a resource they ask for at once is parsed
 * by the first of them, while the others wait for the anchors it found.</p>
 * 
 * @see MarkdownSectionAnchors#validAnchorsIn(Document)
 */
public final class MarkdownSectionAnchorsCache {

	private final MarkdownParserAndHtmlRenderer parserAndRenderer;

	private final ResourceContentsCache contentsCache;

	private final Map<String, CompletableFuture<Set<String>>> anchorsByResolvedPath = new ConcurrentHashMap<>();

	/**
	 * Creates an empty cache.
	 * 
	 * @param parserAndRenderer the parser reading a document, must not be <code>null</code>
	 * @param contentsCache     the cache the text of a document is read through, must not be
	 *                          <code>null</code>
	 * @throws IllegalArgumentException if one of the arguments is <code>null</code>
	 */
	public MarkdownSectionAnchorsCache(MarkdownParserAndHtmlRenderer parserAndRenderer,
			ResourceContentsCache contentsCache) {
		if (parserAndRenderer == null || contentsCache == null) {
			throw new IllegalArgumentException("Arguments must not be null.");
		}
		this.parserAndRenderer = parserAndRenderer;
		this.contentsCache = contentsCache;
	}

	/**
	 * Answers the section anchors the given Markdown resource declares, reading and parsing it only
	 * if nobody asked for it before.
	 * 
	 * @param markdownResource the resource to be parsed, must not be <code>null</code> and must be
	 *                         named like a Markdown file
	 * @return the anchors, never <code>null</code>, possibly empty, not to be changed
	 * @throws IOException if the resource cannot be read, now or when it was read before
	 * @throws IllegalArgumentException if the given resource is <code>null</code> or is not named like
	 *                                  a Markdown file, a resource without a path among them
	 */
	public Set<String> sectionAnchorsOf(Resource markdownResource) throws IOException {
		if (markdownResource == null) {
			throw new IllegalArgumentException("Argument must not be null.");
		}

		String resolvedPath = markdownResource.getResolvedPath();
		if (!this.parserAndRenderer.getMarkdownFileExtensions().isMarkdownFile(resolvedPath)) {
			throw new IllegalArgumentException(
					"Only a Markdown resource is parsed here, and this one is none: " + resolvedPath);
		}

		if (markdownResource instanceof UnresolvedResource) {
			return readAndFindAnchors(markdownResource);
		}

		CompletableFuture<Set<String>> ownParse = new CompletableFuture<>();
		CompletableFuture<Set<String>> rememberedParse =
				this.anchorsByResolvedPath.putIfAbsent(resolvedPath, ownParse);
		if (rememberedParse == null) {
			// parse outside the map, so that a large document does not block asking for other ones
			try {
				ownParse.complete(readAndFindAnchors(markdownResource));
			} catch (IOException | RuntimeException | Error failure) {
				ownParse.completeExceptionally(failure);
			}
			rememberedParse = ownParse;
		}

		return anchorsOf(rememberedParse);
	}

	private Set<String> readAndFindAnchors(Resource markdownResource) throws IOException {
		String contents = this.contentsCache.readAllContents(markdownResource);
		Document document = this.parserAndRenderer.parseMarkdown(contents, markdownResource);
		return Set.copyOf(MarkdownSectionAnchors.validAnchorsIn(document));
	}

	private static Set<String> anchorsOf(CompletableFuture<Set<String>> parse) throws IOException {
		try {
			return parse.join();
		} catch (CompletionException completion) {
			Throwable failure = completion.getCause();
			if (failure instanceof IOException ioFailure) {
				throw ioFailure;
			}
			if (failure instanceof RuntimeException runtimeFailure) {
				throw runtimeFailure;
			}
			// reading and parsing throw nothing else, see above
			throw (Error) failure;
		}
	}

}
