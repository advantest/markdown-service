/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation.uri;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Lets no more than a given number of requests be on their way at once, and makes the others wait
 * for their turn.
 * 
 * <p>A server takes only so many requests at once on one connection, and one that is sent beyond
 * that number fails instead of waiting. This limit keeps the requests below it. A request over the
 * limit is queued, and it is sent as soon as one on its way has been answered, in the order the
 * requests came. Nothing waits on a thread: a request is sent by whoever lets go of a place, i.e.
 * by the thread completing the answer before it.</p>
 * 
 * <p>A place is let go of when the answer has come, whether it is a good one or a failure, so a
 * request that fails does not keep a place taken.</p>
 * 
 * <p>Instances of this class can be used from several threads at once.</p>
 */
final class RequestLimit {

	private static final ThreadLocal<Deque<Runnable>> STARTS_ON_THIS_THREAD = new ThreadLocal<>();

	private final int maxRequestsAtOnce;

	private final Deque<Waiting<?>> waiting = new ArrayDeque<>();

	private int requestsOnTheirWay;

	/**
	 * Creates a limit.
	 * 
	 * @param maxRequestsAtOnce how many requests may be on their way at once, at least one
	 * @throws IllegalArgumentException if the number is less than one
	 */
	RequestLimit(int maxRequestsAtOnce) {
		if (maxRequestsAtOnce < 1) {
			throw new IllegalArgumentException(
					"At least one request has to be allowed at once, but " + maxRequestsAtOnce + " were.");
		}
		this.maxRequestsAtOnce = maxRequestsAtOnce;
	}

	/**
	 * Sends a request now if a place is free, or as soon as one is.
	 * 
	 * @param <T> what the request answers
	 * @param request sends the request and returns the promise of its answer, must not be
	 *        <code>null</code>; an exception it throws fails the answer
	 * @return the promise of the answer, never <code>null</code>
	 * @throws IllegalArgumentException if the given request is <code>null</code>
	 */
	<T> CompletableFuture<T> submit(Supplier<CompletableFuture<T>> request) {
		if (request == null) {
			throw new IllegalArgumentException("Argument must not be null.");
		}

		Waiting<T> next = new Waiting<>(request, new CompletableFuture<>());
		boolean sendNow;

		synchronized (this) {
			sendNow = this.requestsOnTheirWay < this.maxRequestsAtOnce;
			if (sendNow) {
				this.requestsOnTheirWay++;
			} else {
				this.waiting.add(next);
			}
		}

		if (sendNow) {
			runWithoutGrowingTheStack(() -> send(next));
		}
		return next.answer();
	}

	/**
	 * Returns how many requests are on their way right now.
	 */
	synchronized int requestsOnTheirWay() {
		return this.requestsOnTheirWay;
	}

	/**
	 * Returns how many requests wait for their turn right now.
	 */
	synchronized int requestsWaiting() {
		return this.waiting.size();
	}

	private <T> void send(Waiting<T> request) {
		CompletableFuture<T> sent;
		try {
			sent = request.request().get();
			if (sent == null) {
				sent = CompletableFuture.failedFuture(
						new IllegalStateException("A request handed back no promise of an answer."));
			}
		} catch (RuntimeException | Error exception) {
			sent = CompletableFuture.failedFuture(exception);
		}

		sent.whenComplete((value, failure) -> {
			// the place is let go of before the answer is handed on, so that whatever the caller
			// does with the answer does not hold up the next request
			letGoOfPlace();
			if (failure != null) {
				request.answer().completeExceptionally(failure);
			} else {
				request.answer().complete(value);
			}
		});
	}

	private void letGoOfPlace() {
		Waiting<?> next;
		synchronized (this) {
			next = this.waiting.poll();
			if (next == null) {
				this.requestsOnTheirWay--;
			}
			// otherwise the place passes straight on to the next request
		}
		if (next != null) {
			Waiting<?> passedOn = next;
			runWithoutGrowingTheStack(() -> send(passedOn));
		}
	}

	/**
	 * Runs the given start of a request, or, if a start is already running on this thread, runs it
	 * right after that one. An answer arriving at once, as a failure does, lets go of its place while
	 * the request is still being sent, which sends the next one; without this, a long queue of such
	 * requests would nest one call per request on the stack.
	 */
	private static void runWithoutGrowingTheStack(Runnable start) {
		Deque<Runnable> starts = STARTS_ON_THIS_THREAD.get();
		if (starts != null) {
			starts.add(start);
			return;
		}

		starts = new ArrayDeque<>();
		STARTS_ON_THIS_THREAD.set(starts);
		try {
			for (Runnable next = start; next != null; next = starts.poll()) {
				next.run();
			}
		} finally {
			STARTS_ON_THIS_THREAD.remove();
		}
	}

	private record Waiting<T>(Supplier<CompletableFuture<T>> request, CompletableFuture<T> answer) {
	}

}
