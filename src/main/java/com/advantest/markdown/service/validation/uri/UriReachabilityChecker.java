/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation.uri;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Asks whether an address can be reached.
 * 
 * <p>This is the one place where validating a document leaves the machine it runs on, and it is
 * handed to the service from outside for two reasons. A caller decides whether addresses are asked
 * about at all, because asking costs time and needs a network; and a test says what an address
 * answers instead of depending on what the network happens to answer that day.</p>
 * 
 * <p>A checker asks nothing itself. It lives as long as the service it was handed to, and every
 * validation run {@link #openForRun(Executor) opens} a check of its own and asks through that one
 * alone. Whatever a check opens to ask with, e.g. a connection pool, therefore lives no longer than
 * the run that asks with it, and is let go of when the run ends; nothing outside a run can ask and
 * leave something open that nobody closes. What the checker remembers, in contrast, outlives every
 * run, so that the next run asks only about what is new.</p>
 * 
 * <p>A checker is opened from several threads at once, and so is asked a check opened by it. An
 * address is asked about again and again, by every document naming it, so a checker is expected to
 * remember an answer rather than to ask again &ndash; the shipped implementation does, see
 * {@link HttpUriReachabilityChecker}.</p>
 * 
 * <p>A checker holding nothing it would have to let go of opens a check that does nothing when it
 * is cancelled or closed, so it can be written as a function handing out a function:</p>
 * 
 * <pre>
 * UriReachabilityChecker everythingAnswers = executor -&gt; targetUri -&gt;
 *         CompletableFuture.completedFuture(new UriReachability.Answered(200));
 * </pre>
 * 
 * @see com.advantest.markdown.service.MarkdownService.Builder#withUriReachabilityCheck()
 */
@FunctionalInterface
public interface UriReachabilityChecker {

	/**
	 * Opens the check of one validation run, which the run asks through and closes when it ends.
	 * 
	 * <p>Whatever is opened here is let go of when the check is closed, and stopped when it is
	 * cancelled, so that nothing keeps asking for a run nobody waits for any longer.</p>
	 * 
	 * @param executor what the run executes its work on, which a checker may hand to what it opens
	 *                 here, must not be <code>null</code>; it lives until the check is closed
	 * @return the check of the run, never <code>null</code>
	 * @throws IllegalArgumentException if the given executor is <code>null</code>
	 */
	OfRun openForRun(Executor executor);

	/**
	 * The check of one validation run, opened by a {@link UriReachabilityChecker} and closed by the
	 * run when it ends.
	 * 
	 * @see UriReachabilityChecker#openForRun(Executor)
	 */
	@FunctionalInterface
	interface OfRun extends AutoCloseable {

		/**
		 * Asks whether the given address can be reached and hands back the promise of the answer.
		 * 
		 * <p>The address is asked while the document it was found in is walked on, and the promise
		 * is waited for once the walk is over, so this method returns without waiting for anything.
		 * A check that knows its answer without asking anybody hands back a
		 * {@link java.util.concurrent.CompletableFuture#completedFuture(Object) promise that is kept
		 * already}.</p>
		 * 
		 * <p>The promise answers rather than fails: an address that cannot be reached, for whatever
		 * reason, is a {@link UriReachability.NotReached} answer carrying that reason, not a promise
		 * broken with an exception.</p>
		 * 
		 * @param targetUri the address to be asked about, must not be <code>null</code>
		 * @return the promise of what the address answered, never <code>null</code>
		 * @throws IllegalArgumentException if the given address is <code>null</code>
		 */
		CompletableFuture<UriReachability> check(URI targetUri);

		/**
		 * Stops asking: what is still being asked about is not waited for any more, and its promise
		 * fails with a {@link java.util.concurrent.CancellationException}, because the address did
		 * not fail to answer, nobody waited for its answer. Nothing is remembered for such an
		 * address, so the next run asks it again.
		 * 
		 * <p>This default stops nothing, which is right for a check holding nothing that could be
		 * stopped.</p>
		 */
		default void cancel() {
			// holds nothing that could be stopped
		}

		/**
		 * Lets go of whatever was opened for the run, after what is still being asked about has
		 * answered.
		 * 
		 * <p>This default lets go of nothing, which is right for a check holding nothing.</p>
		 */
		@Override
		default void close() {
			// holds nothing that could be let go of
		}

	}

}