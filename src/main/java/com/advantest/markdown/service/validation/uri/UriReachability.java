/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation.uri;

/**
 * The answer to the question whether an address can be reached.
 * 
 * <p>There are exactly two answers, and they carry different things: an address that was reached
 * answered with a status code, an address that was not reached left a reason why. Neither answer
 * can be given without what belongs to it, so nothing reading an answer has to ask whether a
 * status code means anything.</p>
 * 
 * @see UriReachabilityChecker
 */
public sealed interface UriReachability {

	/**
	 * The address answered.
	 * 
	 * @param statusCode the status code the address answered with, e.g. <code>200</code> or
	 *                   <code>404</code>
	 */
	record Answered(int statusCode) implements UriReachability {
	}

	/**
	 * The address did not answer, e.g. because no host of that name exists, because nothing
	 * answered in time or because the connection was refused.
	 * 
	 * @param failureReason why the address could not be reached, in the words of whatever tried
	 *                      to reach it, must not be <code>null</code> and not blank
	 */
	record NotReached(String failureReason) implements UriReachability {

		/**
		 * @throws IllegalArgumentException if the given reason is <code>null</code> or blank
		 */
		public NotReached {
			if (failureReason == null || failureReason.isBlank()) {
				throw new IllegalArgumentException(
						"A reason must be given for an address that could not be reached.");
			}
		}
	}

}
