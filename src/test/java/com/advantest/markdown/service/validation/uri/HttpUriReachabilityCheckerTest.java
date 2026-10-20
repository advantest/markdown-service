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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

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

		UriReachability reachability = viewAsking(httpClient).check(SOME_ADDRESS).join();

		assertEquals(new UriReachability.Answered(200), reachability);
	}

	@Test
	void addressAnsweringWithAnErrorStatusCodeIsStillAnAnswer() {
		HttpClient httpClient = clientAnswering(404);

		UriReachability reachability = viewAsking(httpClient).check(SOME_ADDRESS).join();

		assertEquals(new UriReachability.Answered(404), reachability);
	}

	@Test
	void addressThatDoesNotAnswerYieldsTheReasonItGave() {
		HttpClient httpClient = clientFailingWith(new HttpConnectTimeoutException("HTTP connect timed out"));

		UriReachability reachability = viewWaiting(httpClient, new ArrayList<>()).check(SOME_ADDRESS).join();

		assertEquals(new UriReachability.NotReached("HTTP connect timed out"), reachability);
	}

	@Test
	void failureWithoutAReasonIsNamedAfterWhatWentWrong() {
		HttpClient httpClient = clientFailingWith(new IOException());

		UriReachability reachability = viewAsking(httpClient).check(SOME_ADDRESS).join();

		assertEquals(new UriReachability.NotReached("java.io.IOException"), reachability);
	}

	@Test
	void addressHttpCannotAskAboutIsNotReached() {
		HttpClient httpClient = clientAnswering(200);

		UriReachability reachability = viewAsking(httpClient)
				.check(URI.create("mailto:someone@example.org")).join();

		assertInstanceOf(UriReachability.NotReached.class, reachability);
	}

	@Test
	void addressIsAskedWithAHeadRequestAndATimeout() {
		HttpClient httpClient = clientAnswering(200);
		ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);

		new HttpUriReachabilityChecker(httpClient, Duration.ofMillis(1500)).openForRun(Runnable::run)
				.check(SOME_ADDRESS);

		verify(httpClient).sendAsync(request.capture(), any());
		assertEquals("HEAD", request.getValue().method());
		assertEquals(SOME_ADDRESS, request.getValue().uri());
		assertEquals(Duration.ofMillis(1500), request.getValue().timeout().orElseThrow());
	}

	@Test
	void answerOfAnAddressIsRememberedRatherThanAskedForAgain() {
		HttpClient httpClient = clientAnswering(200);
		UriReachabilityChecker.OfRun view = viewAsking(httpClient);

		assertEquals(view.check(SOME_ADDRESS).join(), view.check(SOME_ADDRESS).join());

		verify(httpClient, times(1)).sendAsync(any(), any());
	}

	@Test
	void addressThatCouldNotBeReachedIsRememberedAsWell() {
		HttpClient httpClient = clientFailingWith(new HttpConnectTimeoutException("HTTP connect timed out"));
		UriReachabilityChecker.OfRun view = viewWaiting(httpClient, new ArrayList<>());

		view.check(SOME_ADDRESS);
		view.check(SOME_ADDRESS);

		// the first check asked once more, because the server did not accept the connection in time
		verify(httpClient, times(2)).sendAsync(any(), any());
	}

	@Test
	void everyAddressIsAskedAboutOnItsOwn() {
		HttpClient httpClient = clientAnswering(200);
		UriReachabilityChecker.OfRun view = viewAsking(httpClient);

		view.check(SOME_ADDRESS);
		view.check(URI.create("https://example.org/other"));

		verify(httpClient, times(2)).sendAsync(any(), any());
	}

	@Test
	void forgottenAnswerIsAskedForAgain() {
		HttpClient httpClient = clientAnswering(200);
		HttpUriReachabilityChecker checker = checkerUsing(httpClient);
		UriReachabilityChecker.OfRun view = checker.openForRun(Runnable::run);

		view.check(SOME_ADDRESS);
		checker.clearCaches();
		view.check(SOME_ADDRESS);

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
		UriReachabilityChecker.OfRun view = viewAsking(httpClient);

		int askers = 4;
		CountDownLatch allAsking = new CountDownLatch(askers);
		ExecutorService threads = Executors.newFixedThreadPool(askers);
		try {
			List<Future<UriReachability>> answers = new ArrayList<>();
			for (int asker = 0; asker < askers; asker++) {
				answers.add(threads.submit(() -> {
					allAsking.countDown();
					return view.check(SOME_ADDRESS).join();
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

		CompletableFuture<UriReachability> reachability = viewAsking(httpClient).check(SOME_ADDRESS);

		assertFalse(reachability.isDone());
		answer.complete(responseWith(200));
		assertEquals(new UriReachability.Answered(200), reachability.join());
	}

	@Test
	void askerGivingUpOnAnAnswerLeavesItForEverybodyElse() {
		CompletableFuture<HttpResponse<Void>> answer = new CompletableFuture<>();
		HttpClient httpClient = clientAnsweringWith(answer);
		UriReachabilityChecker.OfRun view = viewAsking(httpClient);

		view.check(SOME_ADDRESS).cancel(true);
		answer.complete(responseWith(200));

		assertEquals(new UriReachability.Answered(200), view.check(SOME_ADDRESS).join());
		verify(httpClient, times(1)).sendAsync(any(), any());
	}

	@Test
	void addressMustBeGiven() {
		assertThrows(IllegalArgumentException.class, () -> viewAsking(clientAnswering(200)).check(null));
	}

	@Test
	void clientAndTimeoutMustBeGiven() {
		assertThrows(IllegalArgumentException.class,
				() -> new HttpUriReachabilityChecker((HttpClient) null, Duration.ofSeconds(1)));
		assertThrows(IllegalArgumentException.class,
				() -> new HttpUriReachabilityChecker(HttpClient.newHttpClient(), null));
	}

	@Test
	void checkerNobodyGaveTimesToUsesTheOnesThisClassDefaultsTo() {
		HttpUriReachabilityChecker checker = new HttpUriReachabilityChecker();

		assertEquals(HttpUriReachabilityChecker.DEFAULT_ANSWER_TIMEOUT, checker.getAnswerTimeout());
		try (HttpClient client = checker.createClient(Runnable::run)) {
			assertEquals(HttpUriReachabilityChecker.DEFAULT_CONNECT_TIMEOUT, client.connectTimeout().orElseThrow());
		}
	}

	@Test
	void theDefaultTimesAreTheOnesTheMeasurementAskedFor() {
		// the values themselves, so that changing one of them is a decision rather than an edit
		assertEquals(Duration.ofSeconds(5), HttpUriReachabilityChecker.DEFAULT_CONNECT_TIMEOUT);
		assertEquals(Duration.ofSeconds(15), HttpUriReachabilityChecker.DEFAULT_ANSWER_TIMEOUT);
	}

	@Test
	void checkerGivenTimesUsesExactlyThose() {
		HttpUriReachabilityChecker checker =
				new HttpUriReachabilityChecker(Duration.ofSeconds(3), Duration.ofSeconds(30));

		assertEquals(Duration.ofSeconds(30), checker.getAnswerTimeout());
		try (HttpClient client = checker.createClient(Runnable::run)) {
			assertEquals(Duration.ofSeconds(3), client.connectTimeout().orElseThrow());
		}
	}

	@Test
	void checkerGivenTimesStillFollowsARedirect() {
		HttpUriReachabilityChecker checker =
				new HttpUriReachabilityChecker(Duration.ofSeconds(3), Duration.ofSeconds(30));

		try (HttpClient client = checker.createClient(Runnable::run)) {
			assertEquals(HttpClient.Redirect.NORMAL, client.followRedirects());
		}
	}

	@Test
	void bothTimesMustBeGiven() {
		assertThrows(IllegalArgumentException.class,
				() -> new HttpUriReachabilityChecker((Duration) null, Duration.ofSeconds(5)));
		assertThrows(IllegalArgumentException.class,
				() -> new HttpUriReachabilityChecker(Duration.ofSeconds(2), null));
	}

	@Test
	void aTimeOfNothingOrLessIsNoTime() {
		assertThrows(IllegalArgumentException.class,
				() -> new HttpUriReachabilityChecker(Duration.ZERO, Duration.ofSeconds(5)));
		assertThrows(IllegalArgumentException.class,
				() -> new HttpUriReachabilityChecker(Duration.ofSeconds(2), Duration.ZERO));
		assertThrows(IllegalArgumentException.class,
				() -> new HttpUriReachabilityChecker(Duration.ofSeconds(-2), Duration.ofSeconds(5)));
		assertThrows(IllegalArgumentException.class,
				() -> new HttpUriReachabilityChecker(Duration.ofSeconds(2), Duration.ofSeconds(-5)));
	}

	@Test
	void answeringCannotBeGivenLessTimeThanConnecting() {
		// the two are of one type and stand next to each other, so exchanging them is easy and
		// would otherwise be silently wrong
		assertThrows(IllegalArgumentException.class,
				() -> new HttpUriReachabilityChecker(Duration.ofSeconds(15), Duration.ofSeconds(2)));
	}

	@Test
	void connectingAndAnsweringMayBeGivenTheSameTime() {
		HttpUriReachabilityChecker checker =
				new HttpUriReachabilityChecker(Duration.ofSeconds(4), Duration.ofSeconds(4));

		assertEquals(Duration.ofSeconds(4), checker.getAnswerTimeout());
	}

	@Test
	void viewOfARunAsksWithAClientOfItsOwnExecutingOnTheExecutorOfTheRun() {
		HttpClient clientOfTheRun = clientAnswering(200);
		List<Executor> executorsGiven = new ArrayList<>();
		HttpUriReachabilityChecker checker = new HttpUriReachabilityChecker(executor -> {
			executorsGiven.add(executor);
			return clientOfTheRun;
		}, Duration.ofSeconds(1));
		Executor executorOfTheRun = Runnable::run;

		try (UriReachabilityChecker.OfRun view = checker.openForRun(executorOfTheRun)) {
			assertEquals(new UriReachability.Answered(200), view.check(SOME_ADDRESS).join());
		}

		assertEquals(List.of(executorOfTheRun), executorsGiven,
				"Only the view is expected to create a client, executing on the executor of the run.");
		verify(clientOfTheRun).sendAsync(any(), any());
	}

	@Test
	void closingAViewClosesItsClient() {
		HttpClient clientOfTheRun = clientAnswering(200);
		HttpUriReachabilityChecker checker = checkerUsing(clientOfTheRun);

		checker.openForRun(Runnable::run).close();

		verify(clientOfTheRun).close();
	}

	@Test
	void viewsShareTheAnswersOfTheChecker() {
		HttpClient firstClient = clientAnswering(200);
		HttpClient secondClient = clientAnswering(200);
		List<HttpClient> clients = new ArrayList<>(List.of(firstClient, secondClient));
		HttpUriReachabilityChecker checker =
				new HttpUriReachabilityChecker(executor -> clients.remove(0), Duration.ofSeconds(1));

		try (UriReachabilityChecker.OfRun first = checker.openForRun(Runnable::run)) {
			first.check(SOME_ADDRESS).join();
		}
		try (UriReachabilityChecker.OfRun second = checker.openForRun(Runnable::run)) {
			assertEquals(new UriReachability.Answered(200), second.check(SOME_ADDRESS).join());
		}

		verify(firstClient, times(1)).sendAsync(any(), any());
		verify(secondClient, times(0)).sendAsync(any(), any());
	}

	@Test
	void cancellingAViewStopsItsClientAndRemembersNothingItDidNotWaitFor() {
		CompletableFuture<HttpResponse<Void>> pendingAnswer = new CompletableFuture<>();
		HttpClient clientOfTheRun = clientAnsweringWith(pendingAnswer);
		doAnswer(invocation -> pendingAnswer.completeExceptionally(new IOException("shut down")))
				.when(clientOfTheRun).shutdownNow();
		HttpUriReachabilityChecker checker = checkerUsing(clientOfTheRun);

		UriReachabilityChecker.OfRun view = checker.openForRun(Runnable::run);
		CompletableFuture<UriReachability> answer = view.check(SOME_ADDRESS);
		view.cancel();

		verify(clientOfTheRun).shutdownNow();
		CompletionException failure = assertThrows(CompletionException.class, answer::join);
		assertInstanceOf(CancellationException.class, failure.getCause(),
				"An answer nobody waited for is expected to be cancelled rather than to say the address did not answer.");

		pendingAnswer.obtrudeValue(responseWith(200));
		assertEquals(new UriReachability.Answered(200), checker.openForRun(Runnable::run).check(SOME_ADDRESS).join(),
				"The address is expected to be asked again, since its earlier answer was not waited for.");
		verify(clientOfTheRun, times(2)).sendAsync(any(), any());
	}

	@Test
	void viewWaitingForAnAnswerAnotherViewDidNotWaitForAsksItself() {
		CompletableFuture<HttpResponse<Void>> pendingAnswer = new CompletableFuture<>();
		HttpClient cancelledClient = clientAnsweringWith(pendingAnswer);
		doAnswer(invocation -> pendingAnswer.completeExceptionally(new IOException("shut down")))
				.when(cancelledClient).shutdownNow();
		HttpClient goingOnClient = clientAnswering(404);
		List<HttpClient> clients = new ArrayList<>(List.of(cancelledClient, goingOnClient));
		HttpUriReachabilityChecker checker =
				new HttpUriReachabilityChecker(executor -> clients.remove(0), Duration.ofSeconds(1));

		UriReachabilityChecker.OfRun cancelled = checker.openForRun(Runnable::run);
		UriReachabilityChecker.OfRun goingOn = checker.openForRun(Runnable::run);
		cancelled.check(SOME_ADDRESS);
		CompletableFuture<UriReachability> answer = goingOn.check(SOME_ADDRESS);
		assertFalse(answer.isDone(), "The second view is expected to wait for the request of the first one.");

		cancelled.cancel();

		assertEquals(new UriReachability.Answered(404), answer.join(),
				"The view still waiting is expected to ask the address itself.");
		verify(goingOnClient).sendAsync(any(), any());
		goingOn.close();
	}

	@Test
	void sendsNoMoreRequestsToOneServerAtOnceThanAllowed() {
		List<CompletableFuture<HttpResponse<Void>>> onTheirWay = new ArrayList<>();
		HttpClient httpClient = clientAnsweringLater(onTheirWay);
		UriReachabilityChecker.OfRun view =
				new HttpUriReachabilityChecker(executor -> httpClient, Duration.ofSeconds(1), 2).openForRun(Runnable::run);

		view.check(URI.create("https://example.org/a"));
		view.check(URI.create("https://example.org/b"));
		CompletableFuture<UriReachability> third = view.check(URI.create("https://EXAMPLE.org/c"));

		verify(httpClient, times(2)).sendAsync(any(), any());

		onTheirWay.get(0).complete(responseWith(200));

		verify(httpClient, times(3)).sendAsync(any(), any());
		onTheirWay.get(2).complete(responseWith(404));
		assertEquals(new UriReachability.Answered(404), third.join());
	}

	@Test
	void requestToAnotherServerDoesNotWaitForTheRequestsToTheFirstOne() {
		List<CompletableFuture<HttpResponse<Void>>> onTheirWay = new ArrayList<>();
		HttpClient httpClient = clientAnsweringLater(onTheirWay);
		UriReachabilityChecker.OfRun view =
				new HttpUriReachabilityChecker(executor -> httpClient, Duration.ofSeconds(1), 1).openForRun(Runnable::run);

		view.check(URI.create("https://example.org/a"));
		view.check(URI.create("https://example.com/a"));
		view.check(URI.create("http://example.org/a"));
		view.check(URI.create("https://example.org:8443/a"));

		verify(httpClient, times(4)).sendAsync(any(), any());
	}

	@Test
	void sendsNoMoreRequestsToAllServersAtOnceThanAllowed() {
		List<CompletableFuture<HttpResponse<Void>>> onTheirWay = new ArrayList<>();
		HttpClient httpClient = clientAnsweringLater(onTheirWay);
		UriReachabilityChecker.OfRun view = new HttpUriReachabilityChecker(executor -> httpClient,
				Duration.ofSeconds(1), 2, 16).openForRun(Runnable::run);

		view.check(URI.create("https://example.org/a"));
		view.check(URI.create("https://example.com/a"));
		CompletableFuture<UriReachability> third = view.check(URI.create("https://example.net/a"));

		verify(httpClient, times(2)).sendAsync(any(), any());

		onTheirWay.get(1).complete(responseWith(200));

		verify(httpClient, times(3)).sendAsync(any(), any());
		onTheirWay.get(2).complete(responseWith(404));
		assertEquals(new UriReachability.Answered(404), third.join());
	}

	@Test
	void requestWaitingForItsServerTakesNoPlaceAmongAllServers() {
		List<CompletableFuture<HttpResponse<Void>>> onTheirWay = new ArrayList<>();
		HttpClient httpClient = clientAnsweringLater(onTheirWay);
		UriReachabilityChecker.OfRun view = new HttpUriReachabilityChecker(executor -> httpClient,
				Duration.ofSeconds(1), 2, 1).openForRun(Runnable::run);

		view.check(URI.create("https://example.org/a"));
		view.check(URI.create("https://example.org/b"));
		view.check(URI.create("https://example.org/c"));
		view.check(URI.create("https://example.com/a"));

		verify(httpClient, times(2)).sendAsync(any(), any());
	}

	@Test
	void everyViewSharesTheLimitOverAllServersOfTheChecker() {
		List<CompletableFuture<HttpResponse<Void>>> onTheirWay = new ArrayList<>();
		HttpClient firstClient = clientAnsweringLater(onTheirWay);
		HttpClient secondClient = clientAnswering(200);
		List<HttpClient> clients = new ArrayList<>(List.of(firstClient, secondClient));
		HttpUriReachabilityChecker checker =
				new HttpUriReachabilityChecker(executor -> clients.remove(0), Duration.ofSeconds(1), 1, 16);
		UriReachabilityChecker.OfRun first = checker.openForRun(Runnable::run);
		UriReachabilityChecker.OfRun second = checker.openForRun(Runnable::run);

		first.check(URI.create("https://example.org/a"));
		CompletableFuture<UriReachability> waiting = second.check(URI.create("https://example.com/a"));

		verify(secondClient, times(0)).sendAsync(any(), any());

		onTheirWay.get(0).complete(responseWith(200));

		assertEquals(new UriReachability.Answered(200), waiting.join());
		verify(secondClient).sendAsync(any(), any());
	}

	@Test
	void everyViewSharesTheLimitOfTheChecker() {
		List<CompletableFuture<HttpResponse<Void>>> onTheirWay = new ArrayList<>();
		HttpClient firstClient = clientAnsweringLater(onTheirWay);
		HttpClient secondClient = clientAnswering(200);
		List<HttpClient> clients = new ArrayList<>(List.of(firstClient, secondClient));
		HttpUriReachabilityChecker checker =
				new HttpUriReachabilityChecker(executor -> clients.remove(0), Duration.ofSeconds(1), 1);
		UriReachabilityChecker.OfRun first = checker.openForRun(Runnable::run);
		UriReachabilityChecker.OfRun second = checker.openForRun(Runnable::run);

		first.check(URI.create("https://example.org/a"));
		CompletableFuture<UriReachability> waiting = second.check(URI.create("https://example.org/b"));

		verify(secondClient, times(0)).sendAsync(any(), any());

		onTheirWay.get(0).complete(responseWith(200));

		assertEquals(new UriReachability.Answered(200), waiting.join());
		verify(secondClient).sendAsync(any(), any());
	}

	@Test
	void requestOfACancelledViewIsNotSentWhenItsTurnComes() {
		List<CompletableFuture<HttpResponse<Void>>> onTheirWay = new ArrayList<>();
		HttpClient firstClient = clientAnsweringLater(onTheirWay);
		HttpClient cancelledClient = clientAnswering(200);
		List<HttpClient> clients = new ArrayList<>(List.of(firstClient, cancelledClient));
		HttpUriReachabilityChecker checker =
				new HttpUriReachabilityChecker(executor -> clients.remove(0), Duration.ofSeconds(1), 1);
		UriReachabilityChecker.OfRun first = checker.openForRun(Runnable::run);
		UriReachabilityChecker.OfRun cancelled = checker.openForRun(Runnable::run);

		first.check(URI.create("https://example.org/a"));
		CompletableFuture<UriReachability> waiting = cancelled.check(URI.create("https://example.org/b"));
		cancelled.cancel();
		onTheirWay.get(0).complete(responseWith(200));

		verify(cancelledClient, times(0)).sendAsync(any(), any());
		CompletionException failure = assertThrows(CompletionException.class, waiting::join);
		assertInstanceOf(CancellationException.class, failure.getCause());
	}

	@Test
	void requestRefusedForTooManyRequestsAtOnceIsSentAgainAtOnce() {
		List<Duration> waits = new ArrayList<>();
		HttpClient httpClient = clientAnsweringInTurn(refused(), answered(200));

		UriReachability reachability = viewWaiting(httpClient, waits).check(SOME_ADDRESS).join();

		assertEquals(new UriReachability.Answered(200), reachability);
		verify(httpClient, times(2)).sendAsync(any(), any());
		assertEquals(List.of(), waits);
	}

	@Test
	void requestRefusedTwiceIsSentAgainAfterAboutThreeSeconds() {
		List<Duration> waits = new ArrayList<>();
		HttpClient httpClient = clientAnsweringInTurn(refused(), refused(), answered(200));

		UriReachability reachability = viewWaiting(httpClient, waits).check(SOME_ADDRESS).join();

		assertEquals(new UriReachability.Answered(200), reachability);
		verify(httpClient, times(3)).sendAsync(any(), any());
		assertEquals(List.of(Duration.ofSeconds(3)), waits);
	}

	@Test
	void requestRefusedThreeTimesIsNotReached() {
		HttpClient httpClient = clientAnsweringInTurn(refused(), refused(), refused());

		UriReachability reachability = viewWaiting(httpClient, new ArrayList<>()).check(SOME_ADDRESS).join();

		assertEquals(new UriReachability.NotReached(HttpUriReachabilityChecker.TOO_MANY_STREAMS), reachability);
		verify(httpClient, times(3)).sendAsync(any(), any());
	}

	@Test
	void requestFailingForAnotherReasonIsNotSentAgain() {
		HttpClient httpClient = clientFailingWith(new IOException("Connection reset"));

		viewAsking(httpClient).check(SOME_ADDRESS).join();

		verify(httpClient, times(1)).sendAsync(any(), any());
	}

	@Test
	void requestTimingOutIsNotSentAgain() {
		HttpClient httpClient = clientFailingWith(new HttpTimeoutException("request timed out"));

		UriReachability reachability = viewWaiting(httpClient, new ArrayList<>()).check(SOME_ADDRESS).join();

		assertEquals(new UriReachability.NotReached("request timed out"), reachability);
		verify(httpClient, times(1)).sendAsync(any(), any());
	}

	@Test
	void serverNotAcceptingTheConnectionInTimeIsAskedAgainAfterAboutOneSecond() {
		List<Duration> waits = new ArrayList<>();
		HttpClient httpClient = clientAnsweringInTurn(connectTimedOut(), answered(200));

		UriReachability reachability = viewWaiting(httpClient, waits).check(SOME_ADDRESS).join();

		assertEquals(new UriReachability.Answered(200), reachability);
		verify(httpClient, times(2)).sendAsync(any(), any());
		assertEquals(List.of(Duration.ofSeconds(1)), waits);
	}

	@Test
	void serverNotAcceptingTheConnectionInTimeTwiceIsNotReachedWithoutBeingAskedAThirdTime() {
		HttpClient httpClient = clientAnsweringInTurn(connectTimedOut(), connectTimedOut(), answered(200));

		UriReachability reachability = viewWaiting(httpClient, new ArrayList<>()).check(SOME_ADDRESS).join();

		assertEquals(new UriReachability.NotReached("HTTP connect timed out"), reachability);
		verify(httpClient, times(2)).sendAsync(any(), any());
	}

	@Test
	void connectTimeoutWrappedByTheFutureItFailedIsRecognized() {
		HttpClient httpClient = clientAnsweringInTurn(
				CompletableFuture.failedFuture(
						new CompletionException(new HttpConnectTimeoutException("HTTP connect timed out"))),
				answered(200));

		UriReachability reachability = viewWaiting(httpClient, new ArrayList<>()).check(SOME_ADDRESS).join();

		assertEquals(new UriReachability.Answered(200), reachability);
	}

	@Test
	void refusalWrappedByTheFutureItFailedIsRecognized() {
		HttpClient httpClient = clientAnsweringInTurn(
				CompletableFuture.failedFuture(
						new CompletionException(new IOException(HttpUriReachabilityChecker.TOO_MANY_STREAMS))),
				answered(200));

		UriReachability reachability = viewWaiting(httpClient, new ArrayList<>()).check(SOME_ADDRESS).join();

		assertEquals(new UriReachability.Answered(200), reachability);
		verify(httpClient, times(2)).sendAsync(any(), any());
	}

	@Test
	void serverSayingItCannotAnswerNowIsAskedAgainAfterAboutOneSecond() {
		for (int statusCode : new int[] { 429, 503, 504 }) {
			List<Duration> waits = new ArrayList<>();
			HttpClient httpClient = clientAnsweringInTurn(answered(statusCode), answered(200));

			UriReachability reachability = viewWaiting(httpClient, waits).check(SOME_ADDRESS).join();

			assertEquals(new UriReachability.Answered(200), reachability, "status code " + statusCode);
			verify(httpClient, times(2)).sendAsync(any(), any());
			assertEquals(List.of(Duration.ofSeconds(1)), waits, "status code " + statusCode);
		}
	}

	@Test
	void serverStillBusyIsAskedAgainAfterAboutThreeSeconds() {
		List<Duration> waits = new ArrayList<>();
		HttpClient httpClient = clientAnsweringInTurn(answered(503), answered(503), answered(404));

		UriReachability reachability = viewWaiting(httpClient, waits).check(SOME_ADDRESS).join();

		assertEquals(new UriReachability.Answered(404), reachability);
		assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(3)), waits);
	}

	@Test
	void serverStillBusyAfterBeingAskedAgainTwiceIsNotReachedWithItsStatusCodeAsTheReason() {
		for (int statusCode : new int[] { 503, 504 }) {
			HttpClient httpClient =
					clientAnsweringInTurn(answered(statusCode), answered(statusCode), answered(statusCode));

			UriReachability reachability = viewWaiting(httpClient, new ArrayList<>()).check(SOME_ADDRESS).join();

			assertEquals(new UriReachability.NotReached("HTTP status code " + statusCode), reachability);
			verify(httpClient, times(3)).sendAsync(any(), any());
		}
	}

	@Test
	void serverStillSayingItWasAskedTooOftenAfterBeingAskedAgainTwiceAnswers429() {
		HttpClient httpClient = clientAnsweringInTurn(answered(429), answered(429), answered(429));

		UriReachability reachability = viewWaiting(httpClient, new ArrayList<>()).check(SOME_ADDRESS).join();

		assertEquals(new UriReachability.Answered(429), reachability);
		verify(httpClient, times(3)).sendAsync(any(), any());
	}

	@Test
	void serverSayingItWasAskedTooOftenAndAskingForALongerWaitThanAllowedAnswers429() {
		HttpClient httpClient = clientAnsweringInTurn(answered(429, "60"), answered(200));

		UriReachability reachability = viewWaiting(httpClient, new ArrayList<>()).check(SOME_ADDRESS).join();

		assertEquals(new UriReachability.Answered(429), reachability);
		verify(httpClient, times(1)).sendAsync(any(), any());
	}

	@Test
	void serverThatWasAskedTooOftenIsAskedOnlyOncePerRunButAgainInTheNextRun() {
		HttpClient httpClient = clientAnswering(429);
		HttpUriReachabilityChecker checker = new HttpUriReachabilityChecker(executor -> httpClient,
				Duration.ofSeconds(1), 128, 16, recordingIn(new ArrayList<>()), () -> 0.5);
		UriReachabilityChecker.OfRun firstRun = checker.openForRun(Runnable::run);

		assertEquals(new UriReachability.Answered(429), firstRun.check(SOME_ADDRESS).join());
		assertEquals(new UriReachability.Answered(429), firstRun.check(SOME_ADDRESS).join());
		verify(httpClient, times(3)).sendAsync(any(), any());

		checker.openForRun(Runnable::run).check(SOME_ADDRESS).join();

		verify(httpClient, times(6)).sendAsync(any(), any());
	}

	@Test
	void anyOtherErrorStatusCodeIsNotAskedAgain() {
		for (int statusCode : new int[] { 400, 404, 500, 502 }) {
			List<Duration> waits = new ArrayList<>();
			HttpClient httpClient = clientAnswering(statusCode);

			UriReachability reachability = viewWaiting(httpClient, waits).check(SOME_ADDRESS).join();

			assertEquals(new UriReachability.Answered(statusCode), reachability);
			verify(httpClient, times(1)).sendAsync(any(), any());
			assertEquals(List.of(), waits);
		}
	}

	@Test
	void waitVariesByUpToAQuarterAtRandom() {
		List<Duration> shortestWaits = new ArrayList<>();
		HttpClient httpClient = clientAnsweringInTurn(answered(503), answered(503), answered(503));
		new HttpUriReachabilityChecker(executor -> httpClient, Duration.ofSeconds(1), 128, 16,
				recordingIn(shortestWaits), () -> 0.0).openForRun(Runnable::run).check(SOME_ADDRESS).join();

		List<Duration> longestWaits = new ArrayList<>();
		HttpClient otherClient = clientAnsweringInTurn(refused(), refused(), answered(200),
				answered(503), answered(200));
		UriReachabilityChecker.OfRun view = new HttpUriReachabilityChecker(executor -> otherClient,
				Duration.ofSeconds(1), 128, 16, recordingIn(longestWaits), () -> 0.9999).openForRun(Runnable::run);
		view.check(SOME_ADDRESS).join();
		view.check(URI.create("https://example.org/other")).join();

		assertEquals(List.of(Duration.ofMillis(750), Duration.ofMillis(2250)), shortestWaits);
		assertEquals(List.of(Duration.ofMillis(3750), Duration.ofMillis(1250)), longestWaits);
	}

	@Test
	void serverSayingHowLongToWaitIsWaitedForThatLong() {
		List<Duration> waits = new ArrayList<>();
		HttpClient httpClient =
				clientAnsweringInTurn(answered(429, "2"), answered(503, " 5 "), answered(200));

		UriReachability reachability = viewWaiting(httpClient, waits).check(SOME_ADDRESS).join();

		assertEquals(new UriReachability.Answered(200), reachability);
		assertEquals(List.of(Duration.ofSeconds(2), Duration.ofSeconds(5)), waits);
	}

	@Test
	void serverAskingForALongerWaitThanAllowedIsNotAskedAgain() {
		List<Duration> waits = new ArrayList<>();
		HttpClient httpClient = clientAnsweringInTurn(answered(503, "6"), answered(200));

		UriReachability reachability = viewWaiting(httpClient, waits).check(SOME_ADDRESS).join();

		assertEquals(new UriReachability.NotReached("HTTP status code 503"), reachability);
		verify(httpClient, times(1)).sendAsync(any(), any());
		assertEquals(List.of(), waits);
		assertEquals(Duration.ofSeconds(5), HttpUriReachabilityChecker.LONGEST_WAIT_A_SERVER_MAY_ASK_FOR);
	}

	@Test
	void serverSayingHowLongToWaitInAWayThatCannotBeReadIsWaitedForAsUsual() {
		List<Duration> waits = new ArrayList<>();
		HttpClient httpClient = clientAnsweringInTurn(answered(503, "soon"), answered(200));

		viewWaiting(httpClient, waits).check(SOME_ADDRESS).join();

		assertEquals(List.of(Duration.ofSeconds(1)), waits);
	}

	@Test
	void waitIsReadAsSecondsOrAsTheTimeFromWhichOnToAskAgain() {
		assertEquals(Optional.of(Duration.ofSeconds(120)), HttpUriReachabilityChecker.waitOf("120"));
		assertEquals(Optional.of(Duration.ZERO), HttpUriReachabilityChecker.waitOf("0"));
		assertEquals(Optional.of(Duration.ZERO),
				HttpUriReachabilityChecker.waitOf("Wed, 21 Oct 2015 07:28:00 GMT"));
		Duration untilTomorrow = HttpUriReachabilityChecker.waitOf(DateTimeFormatter.RFC_1123_DATE_TIME
				.format(ZonedDateTime.now(ZoneOffset.UTC).plusDays(1))).orElseThrow();
		assertTrue(untilTomorrow.compareTo(Duration.ofHours(23)) > 0, untilTomorrow.toString());
		assertTrue(HttpUriReachabilityChecker.waitOf("99999999999999999999").orElseThrow()
				.compareTo(HttpUriReachabilityChecker.LONGEST_WAIT_A_SERVER_MAY_ASK_FOR) > 0);
		assertEquals(Optional.empty(), HttpUriReachabilityChecker.waitOf("-1"));
		assertEquals(Optional.empty(), HttpUriReachabilityChecker.waitOf(""));
	}

	@Test
	void serverThatCouldNotAnswerNowIsAskedOnlyOncePerRunButAgainInTheNextRun() {
		HttpClient httpClient = clientAnswering(503);
		HttpUriReachabilityChecker checker = new HttpUriReachabilityChecker(executor -> httpClient,
				Duration.ofSeconds(1), 128, 16, recordingIn(new ArrayList<>()), () -> 0.5);
		UriReachabilityChecker.OfRun firstRun = checker.openForRun(Runnable::run);

		UriReachability first = firstRun.check(SOME_ADDRESS).join();
		UriReachability again = firstRun.check(SOME_ADDRESS).join();

		verify(httpClient, times(3)).sendAsync(any(), any());
		assertEquals(first, again);

		checker.openForRun(Runnable::run).check(SOME_ADDRESS).join();

		verify(httpClient, times(6)).sendAsync(any(), any());
	}

	@Test
	void requestRefusedThreeTimesIsAskedAgainInTheNextRun() {
		HttpClient httpClient = clientFailingWith(new IOException(HttpUriReachabilityChecker.TOO_MANY_STREAMS));
		HttpUriReachabilityChecker checker = new HttpUriReachabilityChecker(executor -> httpClient,
				Duration.ofSeconds(1), 128, 16, recordingIn(new ArrayList<>()), () -> 0.5);

		checker.openForRun(Runnable::run).check(SOME_ADDRESS).join();
		checker.openForRun(Runnable::run).check(SOME_ADDRESS).join();

		verify(httpClient, times(6)).sendAsync(any(), any());
	}

	@Test
	void requestWaitingToBeAskedAgainTakesNoPlaceAtItsServer() {
		List<Runnable> waiting = new ArrayList<>();
		HttpClient httpClient = clientAnsweringInTurn(answered(503), answered(200), answered(404));
		UriReachabilityChecker.OfRun view = new HttpUriReachabilityChecker(executor -> httpClient,
				Duration.ofSeconds(1), 1, 1, wait -> waiting::add, () -> 0.5).openForRun(Runnable::run);

		CompletableFuture<UriReachability> busy = view.check(SOME_ADDRESS);
		UriReachability other = view.check(URI.create("https://example.org/other")).join();

		assertEquals(new UriReachability.Answered(200), other);
		verify(httpClient, times(2)).sendAsync(any(), any());
		assertFalse(busy.isDone());

		waiting.remove(0).run();

		assertEquals(new UriReachability.Answered(404), busy.join());
	}

	@Test
	void requestOfAViewCancelledWhileWaitingToBeAskedAgainIsNotSentAgain() {
		List<Runnable> waiting = new ArrayList<>();
		HttpClient httpClient = clientAnsweringInTurn(answered(503), answered(200));
		UriReachabilityChecker.OfRun view = new HttpUriReachabilityChecker(executor -> httpClient,
				Duration.ofSeconds(1), 128, 16, wait -> waiting::add, () -> 0.5).openForRun(Runnable::run);

		CompletableFuture<UriReachability> busy = view.check(SOME_ADDRESS);
		view.cancel();
		waiting.remove(0).run();

		verify(httpClient, times(1)).sendAsync(any(), any());
		CompletionException failure = assertThrows(CompletionException.class, busy::join);
		assertInstanceOf(CancellationException.class, failure.getCause());
	}
	@Test
	void checkerNobodyGaveALimitSendsAsManyRequestsAtOnceAsThisClassDefaultsTo() {
		assertEquals(16, HttpUriReachabilityChecker.DEFAULT_MAX_NUMBER_OF_PARALLEL_NETWORK_REQUESTS_PER_SERVER);
		assertEquals(128, HttpUriReachabilityChecker.DEFAULT_MAX_NUMBER_OF_PARALLEL_NETWORK_REQUESTS);
		for (HttpUriReachabilityChecker checker : List.of(new HttpUriReachabilityChecker(),
				new HttpUriReachabilityChecker(Duration.ofSeconds(3), Duration.ofSeconds(30)))) {
			assertEquals(HttpUriReachabilityChecker.DEFAULT_MAX_NUMBER_OF_PARALLEL_NETWORK_REQUESTS_PER_SERVER,
					checker.getMaxNumberOfParallelNetworkRequestsPerServer());
			assertEquals(HttpUriReachabilityChecker.DEFAULT_MAX_NUMBER_OF_PARALLEL_NETWORK_REQUESTS,
					checker.getMaxNumberOfParallelNetworkRequests());
		}
	}

	@Test
	void checkerGivenLimitsUsesExactlyThoseOnes() {
		HttpUriReachabilityChecker checker =
				new HttpUriReachabilityChecker(Duration.ofSeconds(3), Duration.ofSeconds(30), 7, 5);

		assertEquals(7, checker.getMaxNumberOfParallelNetworkRequests());
		assertEquals(5, checker.getMaxNumberOfParallelNetworkRequestsPerServer());
	}

	@Test
	void limitPerServerAboveTheLimitOverAllServersIsAllowed() {
		HttpUriReachabilityChecker checker =
				new HttpUriReachabilityChecker(Duration.ofSeconds(3), Duration.ofSeconds(30), 4, 32);

		assertEquals(4, checker.getMaxNumberOfParallelNetworkRequests());
		assertEquals(32, checker.getMaxNumberOfParallelNetworkRequestsPerServer());
	}

	@Test
	void limitAllowingNoRequestAtAllIsRefused() {
		for (int none : new int[] { 0, -1 }) {
			assertThrows(IllegalArgumentException.class,
					() -> new HttpUriReachabilityChecker(Duration.ofSeconds(3), Duration.ofSeconds(30), none, 16));
			assertThrows(IllegalArgumentException.class,
					() -> new HttpUriReachabilityChecker(Duration.ofSeconds(3), Duration.ofSeconds(30), 128, none));
		}
	}

	private static UriReachabilityChecker.OfRun viewAsking(HttpClient httpClient) {
		return checkerUsing(httpClient).openForRun(Runnable::run);
	}

	/** Opens a view whose waits before asking again are recorded instead of waited for. */
	private static UriReachabilityChecker.OfRun viewWaiting(HttpClient httpClient, List<Duration> waits) {
		return new HttpUriReachabilityChecker(executor -> httpClient, Duration.ofSeconds(1), 128, 16,
				recordingIn(waits), () -> 0.5).openForRun(Runnable::run);
	}

	/** Records every wait in the given list and runs what is to follow at once. */
	private static Function<Duration, Executor> recordingIn(List<Duration> waits) {
		return wait -> {
			waits.add(wait);
			return Runnable::run;
		};
	}

	private static CompletableFuture<HttpResponse<Void>> answered(int statusCode) {
		return CompletableFuture.completedFuture(responseWith(statusCode));
	}

	private static CompletableFuture<HttpResponse<Void>> answered(int statusCode, String retryAfter) {
		HttpResponse<Void> response = responseWith(statusCode);
		when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Retry-After", List.of(retryAfter)), (n, v) -> true));
		return CompletableFuture.completedFuture(response);
	}

	private static CompletableFuture<HttpResponse<Void>> refused() {
		return CompletableFuture.failedFuture(new IOException(HttpUriReachabilityChecker.TOO_MANY_STREAMS));
	}

	private static CompletableFuture<HttpResponse<Void>> connectTimedOut() {
		return CompletableFuture.failedFuture(new HttpConnectTimeoutException("HTTP connect timed out"));
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

	/** Answers every request with a promise of its own, which the test completes when it wants. */
	@SuppressWarnings("unchecked")
	private static HttpClient clientAnsweringLater(List<CompletableFuture<HttpResponse<Void>>> onTheirWay) {
		HttpClient httpClient = mock(HttpClient.class);
		when(httpClient.sendAsync(any(), any(BodyHandler.class))).thenAnswer(invocation -> {
			CompletableFuture<HttpResponse<Void>> answer = new CompletableFuture<>();
			onTheirWay.add(answer);
			return answer;
		});
		return httpClient;
	}

	/** Answers the first request with the first of the given answers, the second with the second, and so on. */
	@SafeVarargs
	@SuppressWarnings("unchecked")
	private static HttpClient clientAnsweringInTurn(CompletableFuture<HttpResponse<Void>>... answers) {
		HttpClient httpClient = mock(HttpClient.class);
		AtomicInteger timesAsked = new AtomicInteger();
		when(httpClient.sendAsync(any(), any(BodyHandler.class)))
				.thenAnswer(invocation -> answers[timesAsked.getAndIncrement()]);
		return httpClient;
	}

	@SuppressWarnings("unchecked")
	private static HttpResponse<Void> responseWith(int statusCode) {
		HttpResponse<Void> response = mock(HttpResponse.class);
		when(response.statusCode()).thenReturn(statusCode);
		when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (name, value) -> true));
		return response;
	}

}
