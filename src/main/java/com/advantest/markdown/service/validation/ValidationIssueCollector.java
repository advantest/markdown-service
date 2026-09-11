/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Collects what a run found, in the order in which it was found, whatever of it is there already
 * and whatever of it is still promised.
 * 
 * <p>Keeping the order is what this class is for. A check that has to ask somebody answers later
 * than a check reading the document, so appending what came back would put two findings of the same
 * place into an order that depends on how fast a server answered. Every finding therefore keeps the
 * place it was found in, and the promises are put into it when they are kept.</p>
 * 
 * <p>An instance belongs to one collection and is filled by the one thread doing it.</p>
 */
final class ValidationIssueCollector {

	private final List<CompletableFuture<List<ValidationIssue>>> promisedIssuesInOrder = new ArrayList<>();

	/**
	 * Adds one finding that is there already.
	 * 
	 * @param issue the finding, must not be <code>null</code>
	 */
	void add(ValidationIssue issue) {
		this.promisedIssuesInOrder.add(CompletableFuture.completedFuture(List.of(issue)));
	}

	/**
	 * Adds findings that are there already.
	 * 
	 * @param issues the findings, must not be <code>null</code>
	 */
	void addAll(List<ValidationIssue> issues) {
		if (!issues.isEmpty()) {
			this.promisedIssuesInOrder.add(CompletableFuture.completedFuture(List.copyOf(issues)));
		}
	}

	/**
	 * Adds findings that are promised, keeping the place they are found in for them.
	 * 
	 * @param promisedIssues the promise of the findings, must not be <code>null</code>
	 */
	void addPromised(CompletableFuture<List<ValidationIssue>> promisedIssues) {
		if (promisedIssues == null) {
			throw new IllegalArgumentException("Argument must not be null.");
		}
		this.promisedIssuesInOrder.add(promisedIssues);
	}

	/**
	 * Promises everything collected, in the order it was collected in.
	 * 
	 * @return the promise of the findings, kept once every promise given to this collector is,
	 *         never <code>null</code>
	 */
	CompletableFuture<List<ValidationIssue>> promised() {
		List<CompletableFuture<List<ValidationIssue>>> promises = List.copyOf(this.promisedIssuesInOrder);

		return CompletableFuture.allOf(promises.toArray(CompletableFuture[]::new))
				.thenApply(nothing -> flatten(promises));
	}

	private static List<ValidationIssue> flatten(List<CompletableFuture<List<ValidationIssue>>> promises) {
		List<ValidationIssue> issues = new ArrayList<>(promises.size());
		for (CompletableFuture<List<ValidationIssue>> promise : promises) {
			issues.addAll(promise.join());
		}
		return issues;
	}

}
