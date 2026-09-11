/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Asks a validator in a way that keeps its failure to itself.
 * 
 * <p>A validator that cannot do its work says so by throwing an unchecked exception, or by breaking
 * the promise it gave. Either way it loses what it would have found about the node or the target it
 * was asked about, and nothing else: every other validator is still asked about that node, and the
 * walk goes on over the rest of the document. A rule talking to a service that is down must not take
 * the findings of a document with it.</p>
 * 
 * <p>What nobody is meant to catch &ndash; an {@link Error} &ndash; is not caught here either. It
 * says that the machine, and not a rule, is in trouble, so it ends the run as it would end anything
 * else.</p>
 * 
 * <p>A failure is silent for now: a document whose validators failed looks like a document with
 * nothing to report. Saying what went wrong is a question of its own.</p>
 */
final class ValidatorGuard {

	private ValidatorGuard() {
		// this class offers nothing but static methods
	}

	/**
	 * Asks a validator whether it answers for something, reading a failure as a no.
	 * 
	 * @param question the question to the validator, must not be <code>null</code>
	 * @return what the validator answered, <code>false</code> if it failed instead of answering
	 */
	static boolean saysItIsResponsible(BooleanSupplier question) {
		try {
			return question.getAsBoolean();
		} catch (RuntimeException failure) {
			return false;
		}
	}

	/**
	 * Asks a validator about what it finds, reading a failure as nothing found.
	 * 
	 * @param validation the question to the validator, must not be <code>null</code>
	 * @return the promise of what the validator found, kept with an empty list if it failed
	 *         instead of finding something, never <code>null</code>
	 */
	static CompletableFuture<List<ValidationIssue>> findingsOf(
			Supplier<CompletableFuture<List<ValidationIssue>>> validation) {
		CompletableFuture<List<ValidationIssue>> promisedIssues;
		try {
			promisedIssues = validation.get();
		} catch (RuntimeException failure) {
			return CompletableFuture.completedFuture(List.of());
		}

		if (promisedIssues == null) {
			return CompletableFuture.completedFuture(List.of());
		}
		return promisedIssues.exceptionally(ValidatorGuard::nothingFound);
	}

	/**
	 * Reads a broken promise as nothing found, unless what broke it is what nobody is meant to
	 * catch, which breaks this promise as well.
	 */
	private static List<ValidationIssue> nothingFound(Throwable failure) {
		Throwable reason = failure instanceof CompletionException ? failure.getCause() : failure;
		if (reason instanceof Error seriousFailure) {
			throw seriousFailure;
		}
		return List.of();
	}

}
