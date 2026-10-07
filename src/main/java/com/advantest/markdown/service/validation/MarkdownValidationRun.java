/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.advantest.markdown.MarkdownParserAndHtmlRenderer;
import com.advantest.markdown.service.parsing.ParsedMarkdownDocumentsCache;
import com.advantest.markdown.service.resources.ResourceContentsCache;
import com.advantest.markdown.service.validation.uri.UriReachabilityChecker;
import com.advantest.resources.Resource;
import com.advantest.resources.ResourceContentsReader;
import com.advantest.resources.UnresolvedResource;
import com.vladsch.flexmark.util.ast.Document;

/**
 * One validation of one document or of several, reading and parsing every resource the documents
 * refer to at most once, and keeping nothing of that for the next run.
 * 
 * <p>A run is created by the service and starts nothing when it is created: it checks what it is
 * handed, one document per call, and each call answers with the promise of what was found in that
 * document. The documents of one run share what the run read and parsed, so fifty documents
 * pointing into the same one have it read and parsed once. A file that changes while a run reads
 * it is seen by that run as it was first read; a run checks one state of every file.</p>
 * 
 * <p>A run owns what it works with: the executor its work runs on, what it read and parsed, and the
 * check asking an address whether it is there, opened for this run. What that check learnt about an
 * address outlives the run, so the next run asks only about what is new.</p>
 * 
 * <p>Whoever creates a run closes it. {@link #close()} waits for the findings that are still being
 * waited for and then lets go of everything the run holds. An editor whose text changed while a
 * run checked it {@link #cancel() cancels} that run, which stops what is still being asked over the
 * network, and creates the next one.</p>
 * 
 * <pre>
 * try (MarkdownValidationRun run = service.createValidationRun()) {
 *     CompletableFuture&lt;List&lt;ValidationIssue&gt;&gt; readme = run.validate(readmeText, readmeResource);
 *     CompletableFuture&lt;List&lt;ValidationIssue&gt;&gt; guide = run.validate(guideText, guideResource);
 *     ...
 * }
 * </pre>
 * 
 * <p>A run can be used from several threads at once.</p>
 */
public final class MarkdownValidationRun implements AutoCloseable {

	private final MarkdownValidationRules rules;

	private final MarkdownParserAndHtmlRenderer parserAndRenderer;

	private final MarkdownValidationContext context;

	private final ExecutorService executor;

	private final UriReachabilityChecker.OfRun uriReachabilityChecker;

	private final Set<CompletableFuture<List<ValidationIssue>>> awaitedFindings = ConcurrentHashMap.newKeySet();

	private volatile boolean cancelled;

	private volatile boolean closed;

	/**
	 * Creates a run applying the given rules, opening the given check for the run.
	 * 
	 * @param rules the rules every document of the run is checked by, must not be <code>null</code>
	 * @param parserAndRenderer the parser reading the source code handed to the run and every
	 *                          document the run looks into, must not be <code>null</code>
	 * @param uriReachabilityChecker the check asking an address whether it is there, may be
	 *                               <code>null</code>, in which case no address is asked about
	 * @param contentsReader what answers with the contents of a resource the run reads, at most
	 *                       once per resource, must not be <code>null</code>
	 * @throws IllegalArgumentException if the rules, the parser or the reader are <code>null</code>
	 */
	MarkdownValidationRun(MarkdownValidationRules rules, MarkdownParserAndHtmlRenderer parserAndRenderer,
			UriReachabilityChecker uriReachabilityChecker, ResourceContentsReader contentsReader) {
		if (rules == null || parserAndRenderer == null || contentsReader == null) {
			throw new IllegalArgumentException("Arguments must not be null.");
		}
		this.rules = rules;
		this.parserAndRenderer = parserAndRenderer;
		this.executor = Executors.newVirtualThreadPerTaskExecutor();
		this.uriReachabilityChecker = uriReachabilityChecker == null
				? null
				: uriReachabilityChecker.openForRun(this.executor);

		ResourceContentsCache contents = new ResourceContentsCache(contentsReader);
		this.context = new ValidationRunContext(contents,
				new ParsedMarkdownDocumentsCache(parserAndRenderer, contents), this.uriReachabilityChecker);
	}

	/**
	 * Checks the given parsed Markdown document without waiting for the answers of the checks that
	 * have to ask something slow, e.g. a web address.
	 * 
	 * <p>The document is walked before this method returns, so it must not be changed until the
	 * promised findings have arrived. Everything resolved relative to the document is resolved
	 * relative to the resource it was parsed with. The promised findings are ordered by their start
	 * offset, so that two runs over equal source code promise equal lists.</p>
	 * 
	 * <p>A run that was cancelled checks nothing any more: the promise it answers with is cancelled
	 * already.</p>
	 * 
	 * @param document the parsed Markdown document to be checked, must not be <code>null</code>
	 * @return the promise of the problems found, ordered by start offset, empty if there are none,
	 *         never <code>null</code> and kept with a list that is not modifiable
	 * @throws IllegalArgumentException if the given document is <code>null</code>
	 * @throws IllegalStateException if the run was closed
	 */
	public CompletableFuture<List<ValidationIssue>> validate(Document document) {
		if (document == null) {
			throw new IllegalArgumentException("Argument must not be null.");
		}

		CompletableFuture<List<ValidationIssue>> findings = new CompletableFuture<>();
		// remembered before the run is asked whether it was closed, so that closing either sees
		// these findings and waits for them, or this check sees that the run was closed
		this.awaitedFindings.add(findings);
		findings.whenComplete((issues, failure) -> this.awaitedFindings.remove(findings));

		if (this.closed) {
			findings.cancel(false);
			throw new IllegalStateException("The validation run was closed and checks nothing any more.");
		}
		if (this.cancelled) {
			findings.cancel(false);
			return findings;
		}

		CompletableFuture<List<ValidationIssue>> walked;
		try {
			walked = this.rules.validate(document, this.context);
		} catch (RuntimeException | Error failure) {
			findings.completeExceptionally(failure);
			throw failure;
		}
		walked.whenComplete((issues, failure) -> {
			if (failure == null) {
				findings.complete(issues);
			} else {
				findings.completeExceptionally(failure instanceof CompletionException && failure.getCause() != null
						? failure.getCause()
						: failure);
			}
		});

		return findings;
	}

	/**
	 * Parses the given Markdown source code that came from the given resource and checks it without
	 * waiting for the slow checks.
	 * 
	 * @param markdownSourceCode the Markdown source code to be checked, must not be <code>null</code>
	 * @param documentResource the resource the source code came from, must not be <code>null</code>,
	 *                         pass {@link UnresolvedResource#UNKNOWN_DOCUMENT} if it is unknown
	 * @return the promise of the problems found, ordered by start offset, empty if there are none,
	 *         never <code>null</code> and kept with a list that is not modifiable
	 * @throws IllegalArgumentException if one of the arguments is <code>null</code>
	 * @throws IllegalStateException if the run was closed
	 * @see #validate(Document)
	 */
	public CompletableFuture<List<ValidationIssue>> validate(String markdownSourceCode, Resource documentResource) {
		if (markdownSourceCode == null || documentResource == null) {
			throw new IllegalArgumentException("Arguments must not be null.");
		}
		if (this.closed) {
			throw new IllegalStateException("The validation run was closed and checks nothing any more.");
		}

		return validate(this.parserAndRenderer.parseMarkdown(markdownSourceCode, documentResource));
	}

	/**
	 * Stops the run: every promise of findings still being waited for is cancelled, and nothing is
	 * checked any more.
	 * 
	 * <p>Cancelling is meant for a run whose answers nobody wants any longer, e.g. because the text
	 * it checks has changed in the meantime. A document is cancelled as a whole, so findings already
	 * promised for a document are not handed out partly. Cancelling a cancelled run does
	 * nothing.</p>
	 */
	public void cancel() {
		this.cancelled = true;
		this.awaitedFindings.forEach(findings -> findings.cancel(false));
		if (this.uriReachabilityChecker != null) {
			this.uriReachabilityChecker.cancel();
		}
	}

	/**
	 * Tells whether the run was {@link #cancel() cancelled}.
	 * 
	 * @return <code>true</code> if the run was cancelled
	 */
	public boolean isCancelled() {
		return this.cancelled;
	}

	/**
	 * Waits for every promise of findings still being waited for, and then lets go of everything
	 * the run holds: the check opened for it first, then the executor its work ran on. A document
	 * handed to a closed run is refused. Closing a closed run does nothing.
	 * 
	 * <p>Close a run from the thread that created it, or from any other thread that is not waiting
	 * for the findings of this run itself: closing waits for them, so a callback completing one of
	 * them that closes the run would wait for itself.</p>
	 */
	@Override
	public synchronized void close() {
		if (this.closed) {
			return;
		}
		this.closed = true;

		for (CompletableFuture<List<ValidationIssue>> findings : List.copyOf(this.awaitedFindings)) {
			try {
				findings.join();
			} catch (CancellationException | CompletionException failure) {
				// whoever waits for these findings is told what happened; closing only waits
			}
		}

		try {
			if (this.uriReachabilityChecker != null) {
				this.uriReachabilityChecker.close();
			}
		} finally {
			this.executor.close();
		}
	}

}
