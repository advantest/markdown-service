/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation;

import java.io.IOException;
import java.util.Optional;

import com.advantest.markdown.MarkdownParserAndHtmlRenderer;
import com.advantest.markdown.service.parsing.ParsedMarkdownDocumentsCache;
import com.advantest.markdown.service.resources.ResourceContentsCache;
import com.advantest.markdown.service.validation.uri.UriReachabilityChecker;
import com.advantest.resources.Resource;
import com.vladsch.flexmark.util.ast.Document;

/**
 * What one validation run knows besides the document it walks through.
 * 
 * <p>Every validator is handed the same context for the whole run and none of them keeps anything
 * of it: a validator is used for more than one document, so what it learns about one document must
 * not travel to the next. The context is created with a {@link MarkdownValidationRun run} and is
 * dropped when that run is closed. It is asked from more than one thread, because a check that cannot answer at
 * once is waited for after the walk and asks the context while it works, so an implementation has
 * to bear that.</p>
 * 
 * <p>What it is for today is looking into another document. A link points into a document that has
 * to be read and parsed to answer whether the anchor it names is there, and fifty links into the
 * same document are one question, not fifty. The context answers it once and remembers the answer,
 * the failure included, for as long as the run lasts. It does not outlive the run, because a
 * document changes while its author types and an answer remembered longer would have to be
 * forgotten again at the right moment &ndash; a question this way never has to be asked.</p>
 * 
 * @see MarkdownValidator#validate(com.vladsch.flexmark.util.ast.Node, MarkdownValidationContext)
 */
public interface MarkdownValidationContext {

	/**
	 * Creates a context parsing another document with the given parser.
	 * 
	 * @param parserAndRenderer the parser reading another document, must not be <code>null</code>
	 * @return a context of one run, never <code>null</code>
	 * @throws IllegalArgumentException if the given parser is <code>null</code>
	 */
	static MarkdownValidationContext parsingWith(MarkdownParserAndHtmlRenderer parserAndRenderer) {
		ResourceContentsCache contents = new ResourceContentsCache();
		return new ValidationRunContext(contents, new ParsedMarkdownDocumentsCache(parserAndRenderer, contents));
	}

	/**
	 * Reads what the given resource contains, or answers with what was read for it earlier in this
	 * run.
	 * 
	 * <p>A resource of any kind is read here, not only a Markdown one, so that whoever wants to
	 * know whether a target can be read at all asks in one place. Reading twice is what is avoided,
	 * not reading at all: a resource read here and parsed later is read once.</p>
	 * 
	 * @param resource the resource to be read, must not be <code>null</code>
	 * @return what the resource contains, never <code>null</code>, possibly empty
	 * @throws IOException if the resource cannot be read, raised again for every further caller
	 *                     asking for the same resource in this run
	 * @throws IllegalArgumentException if the given resource is <code>null</code>
	 */
	String getContents(Resource resource) throws IOException;

	/**
	 * Reads and parses the given Markdown resource, or answers with what was read for it earlier in
	 * this run.
	 * 
	 * <p>The parsed document carries the resource it came from, so that what it refers to resolves
	 * against its own location and not against the location of the document being checked.</p>
	 * 
	 * <p>Only a resource named like a Markdown file is read, i.e. one carrying an extension the
	 * parser accepts as Markdown. Reading anything else and parsing it as Markdown would answer
	 * about a document that never existed, so asking for it is a programming error and not a
	 * finding. Whether a failure of reading is worth reporting, in contrast, is the caller's
	 * decision, which is why it is raised and not turned into a finding.</p>
	 * 
	 * @param markdownResource the resource to be read, must not be <code>null</code> and must be
	 *                         named like a Markdown file
	 * @return the parsed document, never <code>null</code>
	 * @throws IOException if the resource cannot be read, raised again for every further caller
	 *                     asking for the same resource in this run
	 * @throws IllegalArgumentException if the given resource is <code>null</code> or is not named
	 *                                  like a Markdown file, the resource nobody resolved among
	 *                                  them
	 */
	Document getParsedMarkdownDocument(Resource markdownResource) throws IOException;

	/**
	 * Hands out the check of this run asking an address whether it is there.
	 * 
	 * <p>The check belongs to the run: whatever it opened to ask with is let go of when the run
	 * ends, and stopped when the run is cancelled. What it learnt about an address is remembered
	 * beyond the run, so a validator asks it rather than remembering an answer itself.</p>
	 * 
	 * @return the check, or {@link Optional#empty()} if no address is asked about in this run, which
	 *         this default answers
	 */
	default Optional<UriReachabilityChecker> getUriReachabilityChecker() {
		return Optional.empty();
	}

}
