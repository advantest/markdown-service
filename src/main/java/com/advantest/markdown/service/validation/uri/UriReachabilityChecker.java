/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation.uri;

import java.net.URI;
import java.util.concurrent.CompletableFuture;

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

}
