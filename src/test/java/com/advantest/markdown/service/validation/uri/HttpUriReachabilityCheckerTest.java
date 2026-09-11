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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tests for {@link HttpUriReachabilityChecker}, the check asking an address over HTTP whether it
 * is there.
 */
class HttpUriReachabilityCheckerTest {

	private static final URI SOME_ADDRESS = URI.create("https://example.org/guide");

	@Test
	void answeringAddressYieldsItsStatusCode() {
		HttpClient httpClient = clientAnswering(200);

		UriReachability reachability = checkerUsing(httpClient).check(SOME_ADDRESS).join();

		assertEquals(new UriReachability.Answered(200), reachability);
	}

	@Test
	void addressAnsweringWithAnErrorStatusCodeIsStillAnAnswer() {
		HttpClient httpClient = clientAnswering(404);

		UriReachability reachability = checkerUsing(httpClient).check(SOME_ADDRESS).join();

		assertEquals(new UriReachability.Answered(404), reachability);
	}

	@Test
	void addressThatDoesNotAnswerYieldsTheReasonItGave() {
		HttpClient httpClient = clientFailingWith(new HttpConnectTimeoutException("HTTP connect timed out"));

		UriReachability reachability = checkerUsing(httpClient).check(SOME_ADDRESS).join();

		assertEquals(new UriReachability.NotReached("HTTP connect timed out"), reachability);
	}

	@Test
	void failureWithoutAReasonIsNamedAfterWhatWentWrong() {
		HttpClient httpClient = clientFailingWith(new IOException());

		UriReachability reachability = checkerUsing(httpClient).check(SOME_ADDRESS).join();

		assertEquals(new UriReachability.NotReached("java.io.IOException"), reachability);
	}

	@Test
	void addressHttpCannotAskAboutIsNotReached() {
		HttpClient httpClient = clientAnswering(200);

		UriReachability reachability = checkerUsing(httpClient)
				.check(URI.create("mailto:someone@example.org")).join();

		assertInstanceOf(UriReachability.NotReached.class, reachability);
	}

	@Test
	void addressIsAskedWithAHeadRequestAndATimeout() {
		HttpClient httpClient = clientAnswering(200);
		ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);

		new HttpUriReachabilityChecker(httpClient, Duration.ofMillis(1500)).check(SOME_ADDRESS);

		verify(httpClient).sendAsync(request.capture(), any());
		assertEquals("HEAD", request.getValue().method());
		assertEquals(SOME_ADDRESS, request.getValue().uri());
		assertEquals(Duration.ofMillis(1500), request.getValue().timeout().orElseThrow());
	}

	@Test
	void answerOfAnAddressIsRememberedRatherThanAskedForAgain() {
		HttpClient httpClient = clientAnswering(200);
		HttpUriReachabilityChecker checker = checkerUsing(httpClient);

		assertEquals(checker.check(SOME_ADDRESS).join(), checker.check(SOME_ADDRESS).join());

		verify(httpClient, times(1)).sendAsync(any(), any());
	}

	@Test
	void addressThatCouldNotBeReachedIsRememberedAsWell() {
		HttpClient httpClient = clientFailingWith(new HttpConnectTimeoutException("HTTP connect timed out"));
		HttpUriReachabilityChecker checker = checkerUsing(httpClient);

		checker.check(SOME_ADDRESS);
		checker.check(SOME_ADDRESS);

		verify(httpClient, times(1)).sendAsync(any(), any());
	}

	@Test
	void everyAddressIsAskedAboutOnItsOwn() {
		HttpClient httpClient = clientAnswering(200);
		HttpUriReachabilityChecker checker = checkerUsing(httpClient);

		checker.check(SOME_ADDRESS);
		checker.check(URI.create("https://example.org/other"));

		verify(httpClient, times(2)).sendAsync(any(), any());
	}

	@Test
	void forgottenAnswerIsAskedForAgain() {
		HttpClient httpClient = clientAnswering(200);
		HttpUriReachabilityChecker checker = checkerUsing(httpClient);

		checker.check(SOME_ADDRESS);
		checker.clearCache();
		checker.check(SOME_ADDRESS);

		verify(httpClient, times(2)).sendAsync(any(), any());
	}

	@Test
	void addressAlreadyBeingAskedAboutIsWaitedForInsteadOfAskedAgain() throws Exception {
		CompletableFuture<HttpResponse<Void>> answer = new CompletableFuture<>();
		AtomicInteger requests = new AtomicInteger();
		HttpClient httpClient = mock(HttpClient.class);
		when(httpClient.sendAsync(any(), any(BodyHandler.class))).thenAnswer(invocation -> {
			requests.incrementAndGet();
			return answer;
		});
		HttpUriReachabilityChecker checker = checkerUsing(httpClient);

		int askers = 4;
		CountDownLatch allAsking = new CountDownLatch(askers);
		ExecutorService threads = Executors.newFixedThreadPool(askers);
		try {
			List<Future<UriReachability>> answers = new ArrayList<>();
			for (int asker = 0; asker < askers; asker++) {
				answers.add(threads.submit(() -> {
					allAsking.countDown();
					return checker.check(SOME_ADDRESS).join();
				}));
			}
			assertTrue(allAsking.await(5, TimeUnit.SECONDS));
			answer.complete(responseWith(200));

			for (Future<UriReachability> reachability : answers) {
				assertEquals(new UriReachability.Answered(200), reachability.get(5, TimeUnit.SECONDS));
			}
		} finally {
			threads.shutdownNow();
		}

		assertEquals(1, requests.get());
	}

	@Test
	void addressIsAskedWithoutWaitingForWhatItAnswers() {
		CompletableFuture<HttpResponse<Void>> answer = new CompletableFuture<>();
		HttpClient httpClient = clientAnsweringWith(answer);

		CompletableFuture<UriReachability> reachability = checkerUsing(httpClient).check(SOME_ADDRESS);

		assertFalse(reachability.isDone());
		answer.complete(responseWith(200));
		assertEquals(new UriReachability.Answered(200), reachability.join());
	}

	@Test
	void askerGivingUpOnAnAnswerLeavesItForEverybodyElse() {
		CompletableFuture<HttpResponse<Void>> answer = new CompletableFuture<>();
		HttpClient httpClient = clientAnsweringWith(answer);
		HttpUriReachabilityChecker checker = checkerUsing(httpClient);

		checker.check(SOME_ADDRESS).cancel(true);
		answer.complete(responseWith(200));

		assertEquals(new UriReachability.Answered(200), checker.check(SOME_ADDRESS).join());
		verify(httpClient, times(1)).sendAsync(any(), any());
	}

	@Test
	void addressMustBeGiven() {
		assertThrows(IllegalArgumentException.class, () -> checkerUsing(clientAnswering(200)).check(null));
	}

	@Test
	void clientAndTimeoutMustBeGiven() {
		assertThrows(IllegalArgumentException.class,
				() -> new HttpUriReachabilityChecker(null, Duration.ofSeconds(1)));
		assertThrows(IllegalArgumentException.class,
				() -> new HttpUriReachabilityChecker(HttpClient.newHttpClient(), null));
	}

	private static HttpUriReachabilityChecker checkerUsing(HttpClient httpClient) {
		return new HttpUriReachabilityChecker(httpClient, Duration.ofSeconds(1));
	}

	@SuppressWarnings("unchecked")
	private static HttpClient clientAnswering(int statusCode) {
		HttpClient httpClient = mock(HttpClient.class);
		when(httpClient.sendAsync(any(), any(BodyHandler.class)))
				.thenAnswer(invocation -> CompletableFuture.completedFuture(responseWith(statusCode)));
		return httpClient;
	}

	@SuppressWarnings("unchecked")
	private static HttpClient clientAnsweringWith(CompletableFuture<HttpResponse<Void>> answer) {
		HttpClient httpClient = mock(HttpClient.class);
		when(httpClient.sendAsync(any(), any(BodyHandler.class))).thenAnswer(invocation -> answer);
		return httpClient;
	}

	@SuppressWarnings("unchecked")
	private static HttpClient clientFailingWith(Throwable failure) {
		HttpClient httpClient = mock(HttpClient.class);
		when(httpClient.sendAsync(any(), any(BodyHandler.class)))
				.thenAnswer(invocation -> CompletableFuture.failedFuture(failure));
		return httpClient;
	}

	@SuppressWarnings("unchecked")
	private static HttpResponse<Void> responseWith(int statusCode) {
		HttpResponse<Void> response = mock(HttpResponse.class);
		when(response.statusCode()).thenReturn(statusCode);
		return response;
	}

}
