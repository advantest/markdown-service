/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation.uri;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Checks that no more requests are on their way at once than the limit allows, and that every
 * request waiting for its turn is sent once a place is free.
 */
class RequestLimitTest {

	@ParameterizedTest
	@ValueSource(ints = { 0, -1 })
	void refusesALimitAllowingNothing(int maxRequestsAtOnce) {
		assertThrows(IllegalArgumentException.class, () -> new RequestLimit(maxRequestsAtOnce));
	}

	@Test
	void sendsNoMoreRequestsAtOnceThanAllowedAndTheOthersInTheirOrderOnceAPlaceIsFree() {
		RequestLimit limit = new RequestLimit(2);
		List<Integer> sent = new ArrayList<>();
		List<CompletableFuture<Integer>> onTheirWay = new ArrayList<>();
		List<CompletableFuture<Integer>> answers = new ArrayList<>();

		for (int i = 0; i < 5; i++) {
			int request = i;
			answers.add(limit.submit(() -> {
				sent.add(request);
				CompletableFuture<Integer> answer = new CompletableFuture<>();
				onTheirWay.add(answer);
				return answer;
			}));
		}

		assertEquals(List.of(0, 1), sent);
		assertEquals(2, limit.requestsOnTheirWay());
		assertEquals(3, limit.requestsWaiting());

		onTheirWay.get(1).complete(1);

		assertEquals(List.of(0, 1, 2), sent);
		assertEquals(Integer.valueOf(1), answers.get(1).join());
		assertFalse(answers.get(0).isDone());

		onTheirWay.get(0).complete(0);
		onTheirWay.get(2).complete(2);

		assertEquals(List.of(0, 1, 2, 3, 4), sent);
		assertEquals(2, limit.requestsOnTheirWay());
		assertEquals(0, limit.requestsWaiting());

		onTheirWay.get(3).complete(3);
		onTheirWay.get(4).complete(4);

		assertEquals(0, limit.requestsOnTheirWay());
		for (int i = 0; i < 5; i++) {
			assertEquals(Integer.valueOf(i), answers.get(i).join());
		}
	}

	@Test
	void letsGoOfThePlaceOfARequestThatFailed() {
		RequestLimit limit = new RequestLimit(1);
		IOException failure = new IOException("connection reset");
		CompletableFuture<String> first = new CompletableFuture<>();

		CompletableFuture<String> failed = limit.submit(() -> first);
		CompletableFuture<String> next = limit.submit(() -> CompletableFuture.completedFuture("answered"));

		assertFalse(next.isDone());

		first.completeExceptionally(failure);

		ExecutionException thrown = assertThrows(ExecutionException.class, failed::get);
		assertSame(failure, thrown.getCause());
		assertEquals("answered", next.join());
		assertEquals(0, limit.requestsOnTheirWay());
	}

	@Test
	void failsTheAnswerOfARequestThatCouldNotBeSentAndLetsGoOfItsPlace() {
		RequestLimit limit = new RequestLimit(1);
		IllegalStateException failure = new IllegalStateException("client closed");

		CompletableFuture<String> failed = limit.submit(() -> {
			throw failure;
		});
		CompletableFuture<String> handedBackNothing = limit.submit(() -> null);

		ExecutionException thrown = assertThrows(ExecutionException.class, failed::get);
		assertSame(failure, thrown.getCause());
		assertInstanceOf(IllegalStateException.class,
				assertThrows(ExecutionException.class, handedBackNothing::get).getCause());
		assertEquals("sent", limit.submit(() -> CompletableFuture.completedFuture("sent")).join());
		assertEquals(0, limit.requestsOnTheirWay());
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	void sendsALongQueueOfRequestsAnsweredAtOnceWithoutRunningOutOfStack() {
		RequestLimit limit = new RequestLimit(1);
		CompletableFuture<Integer> first = new CompletableFuture<>();
		int requests = 100_000;
		AtomicInteger sent = new AtomicInteger();

		limit.submit(() -> first);
		List<CompletableFuture<Integer>> answers = new ArrayList<>();
		for (int i = 0; i < requests; i++) {
			answers.add(limit.submit(() -> CompletableFuture.completedFuture(sent.incrementAndGet())));
		}

		first.complete(0);

		assertEquals(requests, sent.get());
		assertEquals(Integer.valueOf(requests), answers.get(requests - 1).join());
		assertEquals(0, limit.requestsOnTheirWay());
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	void neverHasMoreRequestsOnTheirWayThanAllowedWhileManyThreadsSubmit() throws Exception {
		int maxRequestsAtOnce = 4;
		int requests = 2_000;
		RequestLimit limit = new RequestLimit(maxRequestsAtOnce);
		AtomicInteger onTheirWay = new AtomicInteger();
		AtomicInteger mostAtOnce = new AtomicInteger();
		ExecutorService submitters = Executors.newFixedThreadPool(8);
		ExecutorService answerers = Executors.newFixedThreadPool(8);
		CountDownLatch go = new CountDownLatch(1);

		try {
			List<CompletableFuture<Integer>> answers = new ArrayList<>();
			for (int i = 0; i < requests; i++) {
				int request = i;
				answers.add(CompletableFuture.supplyAsync(() -> {
					await(go);
					return limit.submit(() -> {
						mostAtOnce.accumulateAndGet(onTheirWay.incrementAndGet(), Math::max);
						return CompletableFuture.supplyAsync(() -> {
							onTheirWay.decrementAndGet();
							return request;
						}, answerers);
					});
				}, submitters).thenCompose(answer -> answer));
			}

			go.countDown();

			for (int i = 0; i < requests; i++) {
				assertEquals(Integer.valueOf(i), answers.get(i).get(10, TimeUnit.SECONDS));
			}
			assertTrue(mostAtOnce.get() <= maxRequestsAtOnce, "at most " + maxRequestsAtOnce
					+ " requests were allowed at once, but " + mostAtOnce.get() + " were on their way");
			assertEquals(0, limit.requestsOnTheirWay());
			assertEquals(0, limit.requestsWaiting());
		} finally {
			submitters.shutdownNow();
			answerers.shutdownNow();
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(exception);
		}
	}

}
