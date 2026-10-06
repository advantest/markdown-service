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
 * <p>An implementation is asked from several threads at once, so it has to bear that. It is also
 * asked for the same address again and again, by every document naming it, so it is expected to
 * remember an answer rather than to ask again &ndash; the shipped implementation does, see
 * {@link HttpUriReachabilityChecker}.</p>
 * 
 * @see com.advantest.markdown.service.MarkdownService.Builder#withUriReachabilityCheck()
 */
public interface UriReachabilityChecker {

	/**
	 * Asks whether the given address can be reached and hands back the promise of the answer.
	 * 
	 * <p>The address is asked while the document it was found in is walked on, and the promise is
	 * waited for once the walk is over, so this method returns without waiting for anything. An
	 * implementation that knows its answer without asking anybody hands back a
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
	 * Opens this check for one validation run, which asks through what is opened here and closes it
	 * when the run ends.
	 * 
	 * <p>A check asking over the network holds what it asks with, e.g. a connection pool, and a run
	 * is the natural span of that: whatever is opened for a run is let go of when the run is closed,
	 * and stopped when the run is cancelled, so that nothing keeps asking for a run nobody waits for
	 * any longer. What the check remembers, in contrast, may outlive the run, so that the next run
	 * asks only about what is new.</p>
	 * 
	 * <p>This default opens nothing: it hands out a view asking this check itself, whose closing and
	 * cancelling do nothing, which is right for a check holding nothing it would have to let go
	 * of.</p>
	 * 
	 * @param executor what the run executes its work on, which a check may hand to what it opens
	 *                 here, must not be <code>null</code>; it lives until the view is closed
	 * @return the check for the run, never <code>null</code>
	 * @throws IllegalArgumentException if the given executor is <code>null</code>
	 */
	default OfRun openForRun(Executor executor) {
		if (executor == null) {
			throw new IllegalArgumentException("Argument must not be null.");
		}
		return new OfRun() {

			@Override
			public CompletableFuture<UriReachability> check(URI targetUri) {
				return UriReachabilityChecker.this.check(targetUri);
			}

			@Override
			public void cancel() {
				// holds nothing that could be stopped
			}

			@Override
			public void close() {
				// holds nothing that could be let go of
			}
		};
	}

	/**
	 * A check opened for one validation run, closed by the run when it ends.
	 * 
	 * @see UriReachabilityChecker#openForRun(Executor)
	 */
	interface OfRun extends UriReachabilityChecker, AutoCloseable {

		/**
		 * Stops asking: what is still being asked about is not waited for any more, and its promise
		 * fails with a {@link java.util.concurrent.CancellationException}, because the address did
		 * not fail to answer, nobody waited for its answer. Nothing is remembered for such an
		 * address, so the next run asks it again.
		 */
		void cancel();

		/**
		 * Lets go of whatever was opened for the run, after what is still being asked about has
		 * answered.
		 */
		@Override
		void close();

	}

}
