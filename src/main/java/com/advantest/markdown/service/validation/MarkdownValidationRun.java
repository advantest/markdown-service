/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.advantest.markdown.MarkdownParserAndHtmlRenderer;
import com.advantest.markdown.service.parsing.ParsedMarkdownDocumentsCache;
import com.advantest.markdown.service.resources.ResourceContentsCache;
import com.advantest.markdown.service.resources.walk.DefaultMarkdownValidationResourcesFilter;
import com.advantest.markdown.service.resources.walk.ResourceFilter;
import com.advantest.markdown.service.resources.walk.ResourceFilterContext;
import com.advantest.markdown.service.resources.walk.ResourceTreeWalker;
import com.advantest.markdown.service.validation.uri.UriReachabilityChecker;
import com.advantest.resources.LocalFileSystemResource;
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
 * <p>A run also walks folder trees and validates every Markdown file it finds there, see
 * {@link #validateTree(Path)}. Which folders it enters and which files it validates is decided by
 * a {@link ResourceFilter}: the one the run was created with, or one handed to a single walk. All
 * walks of a run share what the run read and parsed, and none of them validates a file another
 * walk of the run validated already.</p>
 * 
 * <p>A run can be used from several threads at once.</p>
 */
public final class MarkdownValidationRun implements AutoCloseable {

	private static final Logger LOG = LoggerFactory.getLogger(MarkdownValidationRun.class);

	private final MarkdownValidationRules rules;

	private final MarkdownParserAndHtmlRenderer parserAndRenderer;

	private final MarkdownValidationContext context;

	private final ResourceContentsCache contents;

	private final ExecutorService executor;

	private final UriReachabilityChecker.OfRun uriReachabilityChecker;

	private final ResourceFilter resourceFilter;

	private final ResourceFilterContext filterContext = new ResourceFilterContext();

	/** The filters met by this run, each mapped to the instance it created for this run. */
	private final Map<ResourceFilter, ResourceFilter> filtersForRun =
			Collections.synchronizedMap(new IdentityHashMap<>());

	private final ResourceTreeWalker walker;

	private final Set<CompletableFuture<?>> awaitedFindings = ConcurrentHashMap.newKeySet();

	/** The walks still going on, which are not cancelled but end on their own once cancelled. */
	private final Set<CompletableFuture<Void>> runningWalks = ConcurrentHashMap.newKeySet();

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
		this(rules, parserAndRenderer, uriReachabilityChecker, contentsReader,
				DefaultMarkdownValidationResourcesFilter.builderWithDefaults().build());
	}

	/**
	 * Creates a run applying the given rules, opening the given check for the run, and walking
	 * folder trees with the given filter.
	 * 
	 * @param rules the rules every document of the run is checked by, must not be <code>null</code>
	 * @param parserAndRenderer the parser reading the source code handed to the run and every
	 *                          document the run looks into, must not be <code>null</code>
	 * @param uriReachabilityChecker the check asking an address whether it is there, may be
	 *                               <code>null</code>, in which case no address is asked about
	 * @param contentsReader what answers with the contents of a resource the run reads, at most
	 *                       once per resource, must not be <code>null</code>
	 * @param resourceFilter the filter deciding which folders a walk of the run enters and which
	 *                       files it validates, must not be <code>null</code>
	 * @throws IllegalArgumentException if the rules, the parser, the reader or the filter are
	 *                                  <code>null</code>
	 */
	MarkdownValidationRun(MarkdownValidationRules rules, MarkdownParserAndHtmlRenderer parserAndRenderer,
			UriReachabilityChecker uriReachabilityChecker, ResourceContentsReader contentsReader,
			ResourceFilter resourceFilter) {
		if (rules == null || parserAndRenderer == null || contentsReader == null || resourceFilter == null) {
			throw new IllegalArgumentException("Arguments must not be null.");
		}
		this.rules = rules;
		this.parserAndRenderer = parserAndRenderer;
		this.resourceFilter = resourceFilter;
		this.walker = new ResourceTreeWalker(parserAndRenderer.getMarkdownFileExtensions()::isMarkdownFile);
		this.executor = Executors.newVirtualThreadPerTaskExecutor();
		this.uriReachabilityChecker = uriReachabilityChecker == null
				? null
				: uriReachabilityChecker.openForRun(this.executor);

		this.contents = new ResourceContentsCache(contentsReader);
		this.context = new ValidationRunContext(this.contents,
				new ParsedMarkdownDocumentsCache(parserAndRenderer, this.contents), this.uriReachabilityChecker);
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

		CompletableFuture<List<ValidationIssue>> findings = awaitUnlessClosed(new CompletableFuture<>());
		return validate(document, findings);
	}

	/**
	 * Remembers the given promise as awaited, before the run is asked whether it was closed, so
	 * that closing either sees the promise and waits for it, or this sees that the run was closed.
	 */
	private <T> CompletableFuture<T> awaitUnlessClosed(CompletableFuture<T> promise) {
		await(promise);
		if (this.closed) {
			promise.cancel(false);
			throw new IllegalStateException("The validation run was closed and checks nothing any more.");
		}
		return promise;
	}

	private <T> CompletableFuture<T> await(CompletableFuture<T> promise) {
		this.awaitedFindings.add(promise);
		promise.whenComplete((result, failure) -> this.awaitedFindings.remove(promise));
		return promise;
	}

	/** Checks the document, completing the given promise, which is awaited already. */
	private CompletableFuture<List<ValidationIssue>> validate(Document document,
			CompletableFuture<List<ValidationIssue>> findings) {
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
	 * Reads the given Markdown document and checks it without waiting for the slow checks.
	 * 
	 * <p>The document is read through the run, so a document read before by this run, e.g.
	 * because another document links into it, is not read again. No {@link ResourceFilter}
	 * applies: whoever names a document wants it checked.</p>
	 * 
	 * @param document the Markdown document to be read and checked, must not be <code>null</code>
	 * @return the promise of the problems found, ordered by start offset, empty if there are none,
	 *         never <code>null</code> and kept with a list that is not modifiable; it completes
	 *         exceptionally with the {@link IOException} if the document cannot be read
	 * @throws IllegalArgumentException if the given document is <code>null</code>
	 * @throws IllegalStateException if the run was closed
	 * @see #validate(Document)
	 */
	public CompletableFuture<List<ValidationIssue>> validate(Resource document) {
		if (document == null) {
			throw new IllegalArgumentException("Argument must not be null.");
		}

		CompletableFuture<List<ValidationIssue>> findings = awaitUnlessClosed(new CompletableFuture<>());
		return readAndValidate(document, findings);
	}

	/** Reads and checks the document, completing the given promise, which is awaited already. */
	private CompletableFuture<List<ValidationIssue>> readAndValidate(Resource document,
			CompletableFuture<List<ValidationIssue>> findings) {
		if (this.cancelled) {
			findings.cancel(false);
			return findings;
		}

		Document parsed;
		try {
			parsed = this.parserAndRenderer.parseMarkdown(this.contents.readAllContents(document), document);
		} catch (IOException failure) {
			findings.completeExceptionally(failure);
			return findings;
		} catch (RuntimeException | Error failure) {
			findings.completeExceptionally(failure);
			throw failure;
		}
		return validate(parsed, findings);
	}

	/**
	 * Walks the folder tree below the given root and checks every Markdown file found there that
	 * the run's {@link ResourceFilter} does not skip, without waiting for the slow checks.
	 * 
	 * <p>The walk runs on the run's own threads, so this returns at once. A file another walk of
	 * this run checked already is not checked again, and a symbolic link leading back up the tree
	 * does not make the walk run in circles. A folder that cannot be listed is reported and left
	 * out; a file found that cannot be read or checked is reported as an error and left out of the
	 * findings; the walk goes on in both cases.</p>
	 * 
	 * <p>Cancelling the run stops the walk and cancels the promise.</p>
	 * 
	 * @param root the folder to start from, must not be <code>null</code>
	 * @return the promise of the problems found, mapping every file checked, in the order the walk
	 *         found them, to its problems ordered by start offset, which are empty if there are
	 *         none; never <code>null</code>, kept with maps and lists that are not modifiable
	 * @throws IllegalArgumentException if the root is <code>null</code> or no folder
	 * @throws IllegalStateException if the run was closed
	 */
	public CompletableFuture<Map<Resource, List<ValidationIssue>>> validateTree(Path root) {
		if (root == null) {
			throw new IllegalArgumentException("Argument must not be null.");
		}
		return walkAndValidate(List.of(root), this.resourceFilter);
	}

	/**
	 * Does what {@link #validateTree(Path)} does, with the given filter instead of the run's, e.g.
	 * with other filters for a project of one kind than for one of another kind.
	 * 
	 * @param root the folder to start from, must not be <code>null</code>
	 * @param filter the filter deciding which folders the walk enters and which files it checks,
	 *               {@link ResourceFilter#createForRun(ResourceFilterContext) created} for this run
	 *               the first time the run meets it, must not be <code>null</code>
	 * @return the promise of the problems found, see {@link #validateTree(Path)}
	 * @throws IllegalArgumentException if an argument is <code>null</code> or the root is no folder
	 * @throws IllegalStateException if the run was closed
	 */
	public CompletableFuture<Map<Resource, List<ValidationIssue>>> validateTree(Path root, ResourceFilter filter) {
		if (root == null || filter == null) {
			throw new IllegalArgumentException("Arguments must not be null.");
		}
		return walkAndValidate(List.of(root), filter);
	}

	/**
	 * Does what {@link #validateTree(Path)} does for every given root, one after the other, in one
	 * promise. A walk reaching the root of another walk of this call leaves it to that walk, so
	 * that every file is matched against the filter relative to the root nearest to it.
	 * 
	 * @param roots the folders to start from, must neither be <code>null</code> nor hold
	 *              <code>null</code>
	 * @return the promise of the problems found in all trees, see {@link #validateTree(Path)}
	 * @throws IllegalArgumentException if the roots are <code>null</code>, hold <code>null</code>,
	 *                                  or one of them is no folder
	 * @throws IllegalStateException if the run was closed
	 */
	public CompletableFuture<Map<Resource, List<ValidationIssue>>> validateTrees(Collection<Path> roots) {
		if (roots == null || roots.stream().anyMatch(root -> root == null)) {
			throw new IllegalArgumentException("Arguments must not be null.");
		}
		return walkAndValidate(roots, this.resourceFilter);
	}

	/**
	 * Tells whether a walk of this run from the given root would check the given file, applying
	 * the run's filter to the folders on the way and to the file, without walking.
	 * 
	 * <p>This is meant for a file learnt about elsewhere, e.g. from a notification that it
	 * changed, which is to be treated like a walk would treat it.</p>
	 * 
	 * @param file the file, must not be <code>null</code>
	 * @param root the folder a walk would start from, must not be <code>null</code>
	 * @return <code>true</code> if the file exists, lies below the root, is a Markdown file, and
	 *         neither it nor a folder on the way is skipped by the run's filter
	 * @throws IllegalArgumentException if an argument is <code>null</code>
	 */
	public boolean isValidated(Path file, Path root) {
		if (file == null || root == null) {
			throw new IllegalArgumentException("Arguments must not be null.");
		}
		return this.walker.isValidated(file, root, filterForRun(this.resourceFilter));
	}

	private ResourceFilter filterForRun(ResourceFilter filter) {
		return this.filtersForRun.computeIfAbsent(filter, known -> known.createForRun(this.filterContext));
	}

	private CompletableFuture<Map<Resource, List<ValidationIssue>>> walkAndValidate(Collection<Path> roots,
			ResourceFilter filter) {
		Set<Path> distinctRoots = new LinkedHashSet<>();
		for (Path root : roots) {
			Path folder = root.toAbsolutePath().normalize();
			if (!Files.isDirectory(folder)) {
				throw new IllegalArgumentException("A folder tree to be validated must start in a folder: " + root);
			}
			distinctRoots.add(folder);
		}

		CompletableFuture<Map<Resource, List<ValidationIssue>>> findings = awaitUnlessClosed(new CompletableFuture<>());
		if (this.cancelled) {
			findings.cancel(false);
			return findings;
		}

		CompletableFuture<Void> walked = new CompletableFuture<>();
		this.runningWalks.add(walked);
		this.executor.execute(() -> {
			try {
				walkAndValidate(List.copyOf(distinctRoots), filter, findings);
			} finally {
				walked.complete(null);
				this.runningWalks.remove(walked);
			}
		});
		return findings;
	}

	private void walkAndValidate(List<Path> roots, ResourceFilter filter,
			CompletableFuture<Map<Resource, List<ValidationIssue>>> findings) {
		Map<Resource, CompletableFuture<List<ValidationIssue>>> found = new LinkedHashMap<>();
		try {
			ResourceFilter forRun = filterForRun(filter);
			for (Path root : roots) {
				List<Path> otherRoots = new ArrayList<>(roots);
				otherRoots.remove(root);
				this.walker.walk(root, forRun, otherRoots, this::isCancelled, file -> {
					Resource document = LocalFileSystemResource.of(file);
					found.put(document, validateFound(document));
				});
			}
		} catch (IOException | RuntimeException | Error failure) {
			findings.completeExceptionally(failure);
			return;
		}

		CompletableFuture.allOf(found.values().stream()
						.map(promise -> promise.handle((issues, failure) -> null))
						.toArray(CompletableFuture[]::new))
				.thenRun(() -> {
					if (this.cancelled) {
						findings.cancel(false);
						return;
					}
					Map<Resource, List<ValidationIssue>> issuesPerDocument = new LinkedHashMap<>();
					found.forEach((document, promise) -> {
						try {
							issuesPerDocument.put(document, promise.join());
						} catch (CompletionException failure) {
							LOG.error("Could not validate {}", document, failure.getCause());
						}
					});
					findings.complete(Collections.unmodifiableMap(issuesPerDocument));
				});
	}

	/** Checks a document a walk found, which fails its own promise only, not the walk. */
	private CompletableFuture<List<ValidationIssue>> validateFound(Resource document) {
		CompletableFuture<List<ValidationIssue>> findings = await(new CompletableFuture<>());
		try {
			return readAndValidate(document, findings);
		} catch (RuntimeException | Error failure) {
			// the promise was failed with it already
			return findings;
		}
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
	 * Tells whether the run was {@link #close() closed}, which it is from the moment closing began.
	 * 
	 * @return <code>true</code> if the run was closed
	 */
	public boolean isClosed() {
		return this.closed;
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

		awaitEverything();

		try {
			if (this.uriReachabilityChecker != null) {
				this.uriReachabilityChecker.close();
			}
		} finally {
			this.executor.close();
		}
	}

	/**
	 * Waits until every promise of findings has completed and every walk has ended, including the
	 * promises a walk creates while it is waited for.
	 */
	private void awaitEverything() {
		while (true) {
			List<CompletableFuture<?>> pending = new ArrayList<>(this.awaitedFindings);
			pending.addAll(this.runningWalks);
			pending.removeIf(CompletableFuture::isDone);
			if (pending.isEmpty()) {
				return;
			}
			for (CompletableFuture<?> promise : pending) {
				try {
					promise.join();
				} catch (CancellationException | CompletionException failure) {
					// whoever waits for these findings is told what happened; closing only waits
				}
			}
		}
	}

}
