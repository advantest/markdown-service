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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * <p>A failure is written to a log, naming the validator that failed and carrying what it failed
 * with, because a caught failure that nobody hears of makes a broken rule look like a clean
 * document. Where those lines go is not decided here: this library uses a logging API and ships no
 * binding, so whoever composes it says where they are written, and without a binding they are
 * dropped.</p>
 * 
 * <p>The findings stay silent about it. A failure is nothing an author can act on &ndash; the
 * reason usually lies outside the document, and one unreachable service would mark every link of
 * every document &ndash; so it is reported to whoever runs the program rather than to whoever
 * writes the text.</p>
 */
final class ValidatorGuard {

	private static final Logger LOG = LoggerFactory.getLogger(ValidatorGuard.class);

	private ValidatorGuard() {
		// this class offers nothing but static methods
	}

	/**
	 * Asks a validator whether it answers for something, reading a failure as a no.
	 * 
	 * @param validator the validator being asked, so that a failure can say who failed, must not
	 *        be <code>null</code>
	 * @param question the question to the validator, must not be <code>null</code>
	 * @return what the validator answered, <code>false</code> if it failed instead of answering
	 */
	static boolean saysItIsResponsible(Object validator, BooleanSupplier question) {
		try {
			return question.getAsBoolean();
		} catch (RuntimeException failure) {
			LOG.warn("The validator {} failed when asked whether it answers for a target."
					+ " It is read as answering for nothing this time.", nameOf(validator), failure);
			return false;
		}
	}

	/**
	 * Asks a validator about what it finds, reading a failure as nothing found.
	 * 
	 * @param validator the validator being asked, so that a failure can say who failed, must not
	 *        be <code>null</code>
	 * @param validation the question to the validator, must not be <code>null</code>
	 * @return the promise of what the validator found, kept with an empty list if it failed
	 *         instead of finding something, never <code>null</code>
	 */
	static CompletableFuture<List<ValidationIssue>> findingsOf(Object validator,
			Supplier<CompletableFuture<List<ValidationIssue>>> validation) {

		CompletableFuture<List<ValidationIssue>> promisedIssues;
		try {
			promisedIssues = validation.get();
		} catch (RuntimeException failure) {
			LOG.warn("The validator {} failed before it could look. What it would have found is lost,"
					+ " and the rest of the document is validated on.", nameOf(validator), failure);
			return CompletableFuture.completedFuture(List.of());
		}

		if (promisedIssues == null) {
			LOG.warn("The validator {} promised nothing at all, where a promise of what it found was"
					+ " expected. It is read as having found nothing.", nameOf(validator));
			return CompletableFuture.completedFuture(List.of());
		}
		return promisedIssues.exceptionally(failure -> nothingFound(validator, failure));
	}

	/**
	 * Reads a broken promise as nothing found, unless what broke it is what nobody is meant to
	 * catch, which breaks this promise as well.
	 */
	private static List<ValidationIssue> nothingFound(Object validator, Throwable failure) {
		Throwable reason = failure instanceof CompletionException ? failure.getCause() : failure;
		if (reason instanceof Error seriousFailure) {
			throw seriousFailure;
		}

		LOG.warn("The validator {} broke the promise it gave. What it would have found is lost, and"
				+ " the rest of the document is validated on.", nameOf(validator), reason);
		return List.of();
	}

	private static String nameOf(Object validator) {
		return validator == null ? "that was asked" : validator.getClass().getName();
	}

}
