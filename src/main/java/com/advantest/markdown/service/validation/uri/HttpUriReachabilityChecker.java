/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation.uri;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.advantest.markdown.service.CachesHolder;

/**
 * Asks an address over HTTP whether it is there, hands back the promise of what it answers, and
 * remembers that answer.
 * 
 * <p>Asking returns at once, so the document naming the address is walked on while the far side
 * takes its time, and whoever wants the answer waits for the promise.</p>
 * 
 * <p>Only the head of a document is asked for, because whether an address leads somewhere is
 * answered by the status code alone and nothing here reads a page. An address answering with a
 * redirect is followed, so that what is reported is what a reader would finally arrive at.</p>
 * 
 * <p>Where the asking fails, the reason is what the failure says about itself, and the name of its
 * class where it says nothing.</p>
 * 
 * <p>Every answer is remembered, the one that came and the one that did not, for as long as this
 * checker lives, except where a server says that it cannot answer now, as told below. An address
 * that costs a timeout costs it once and not once per document naming
 * it, and an address is asked about only once even while several documents are validated at the
 * same time: the answer is put into the cache while it is still being waited for, so a second
 * document waits for the same request instead of starting another one. Nothing expires by itself,
 * so a checker living in a long running program is told to {@link #clearCaches() forget} what it
 * knows when its answers may have gone stale.</p>
 * 
 * <p>The checker is asked only through a view {@link #openForRun(Executor) opened} for a validation
 * run, which asks with a client of its own and shares the answers with this checker and every other
 * view. The run closes the view and with it the client, so that a client lives no longer than the
 * run that asked with it, and no client is created that nobody closes; the answers live as long as
 * this checker. A run that is cancelled stops what its view is still asking, and an answer it did
 * not wait for is not remembered: the address did not fail to answer, nobody waited for it. Another
 * run waiting for the same answer asks again itself.</p>
 * 
 * <p>Two times bound the asking: how long the far side has to accept the connection, and how long
 * it has to answer. Both have a default, so a caller needing neither says nothing, and both can be
 * given instead, because what is long enough depends on a network this library knows nothing
 * about.</p>
 * 
 * <p>No more requests are sent to one server at once than a given number, by default
 * {@link #DEFAULT_MAX_NUMBER_OF_PARALLEL_NETWORK_REQUESTS_PER_SERVER}. A server takes only so many
 * requests at once on one connection, and the client fails every request beyond that number instead
 * of sending it, which would report a working address as one that does not answer. A further
 * request to the same server waits, without holding a thread, until an earlier one is answered.</p>
 * 
 * <p>An address whose server says that it cannot answer now is asked again, twice at most. That is
 * an answer with one of the status codes 429 "Too Many Requests", 503 "Service Unavailable" or 504
 * "Gateway Timeout", which is asked again after about one and then about three seconds, and a
 * request the client refused because the connection carried as many requests as the server takes at
 * once, which is sent again at once and then after about three seconds. Every wait varies by a
 * quarter at random, so that requests refused together do not come back together. A server saying
 * in <code>Retry-After</code> how long to wait is followed if it asks for
 * {@link #LONGEST_WAIT_A_SERVER_MAY_ASK_FOR} at most; a server asking for more is not asked again.
 * A request waiting to be asked again holds no place in the limits below; it waits for its turn
 * anew. Nothing else is asked again: another status code says something about the address, and a
 * timeout has already used up the time an address is given.</p>
 * 
 * <p>An address that still cannot answer after that is {@link UriReachability.NotReached not
 * reached}, with the status code or the refusal as its reason, and not an address answering with
 * an error status code. A server still saying 429 is the exception: it answered, only not about the
 * address, so the address is handed back as {@link UriReachability.Answered answering} 429, which
 * the shipped validator reports nothing for. Such an answer is remembered for the run that asked
 * for it only, and not for as long as the checker lives, so that the next run asks again.</p>
 * 
 * <p>No more requests are sent to all servers together than a second number either, by default
 * {@link #DEFAULT_MAX_NUMBER_OF_PARALLEL_NETWORK_REQUESTS}. Every open request costs the machine
 * asking a connection or a stream, and a proxy in between carries all of them, so documents naming
 * many servers must not open as many requests as all limits per server together allow. A request
 * first waits for its turn at its server and only then for its turn among all servers, so a request
 * held up by one busy server takes no place another server could use.</p>
 * 
 * <p>Both limits belong to the checker, so every view and every run shares them.</p>
 * 
 * <p>Instances of this class can be used from several threads at once.</p>
 */
public final class HttpUriReachabilityChecker implements UriReachabilityChecker, CachesHolder {

	/** How long it may take until the far side accepts the connection, where nobody says otherwise. */
	public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(2);

	/**
	 * How long it may take until the far side has answered, where nobody says otherwise.
	 * 
	 * <p>Fifteen seconds is three times the slowest answer measured, which came from an address
	 * running a search before it answers. A shorter limit turns a slow address into one this check
	 * claims not to exist, and the cost of a longer one is bounded rather than multiplied, because
	 * addresses are asked at the same time and each one only once.</p>
	 */
	public static final Duration DEFAULT_ANSWER_TIMEOUT = Duration.ofSeconds(15);

	/**
	 * How many requests may be on their way to one server at once, where nobody says otherwise.
	 * 
	 * <p>Servers commonly announce that they take 100 requests at once on one connection, and the
	 * standard recommends no less. Sixteen stays well below that and was measured to be as fast as
	 * no limit at all over a corpus of documents.</p>
	 */
	public static final int DEFAULT_MAX_NUMBER_OF_PARALLEL_NETWORK_REQUESTS_PER_SERVER = 16;

	/**
	 * How many requests may be on their way to all servers together at once, where nobody says
	 * otherwise.
	 * 
	 * <p>It lets eight servers be asked at their full limit at once, and keeps the open connections
	 * far below what a process is commonly allowed to hold open, even where every request needs a
	 * connection of its own.</p>
	 */
	public static final int DEFAULT_MAX_NUMBER_OF_PARALLEL_NETWORK_REQUESTS = 128;

	/**
	 * The longest wait a server may ask for in <code>Retry-After</code> and still be asked again.
	 * 
	 * <p>Asking again is worth a wait that stays below the time an address is given to answer; a
	 * server asking for more is taken not to answer, rather than to hold up the run asking it.</p>
	 */
	public static final Duration LONGEST_WAIT_A_SERVER_MAY_ASK_FOR = Duration.ofSeconds(5);

	/** What the client says when it refuses a request beyond what a server takes at once. */
	static final String TOO_MANY_STREAMS = "too many concurrent streams";

	/** How often an address saying that it cannot answer now is asked again at most. */
	static final int MAX_NUMBER_OF_RETRIES = 2;

	/** The status codes saying that the server, or a gateway in front of it, cannot answer now. */
	private static final Set<Integer> STATUS_CODES_OF_A_BUSY_SERVER = Set.of(429, 503, 504);

	/** The status code of a server saying that it was asked too often by whoever asks. */
	private static final int STATUS_CODE_TOO_MANY_REQUESTS = 429;

	/** How long to wait before asking a busy server again the first time, before the random part. */
	private static final Duration FIRST_WAIT = Duration.ofSeconds(1);

	/** How long to wait before asking again the second time, before the random part. */
	private static final Duration SECOND_WAIT = Duration.ofSeconds(3);

	// Some servers answer a request only if they recognize who is asking, and refuse everything
	// else, so this checker names itself after a tool such a server is used to.
	private static final Logger LOG = LoggerFactory.getLogger(HttpUriReachabilityChecker.class);

	private static final String USER_AGENT = "curl/8.11.0";

	private static final String ACCEPTED_CONTENT = "*/*";

	/** Creates a client asking as this checker says, executing on the given executor. */
	private final Function<Executor, HttpClient> clientFactory;

	private final Duration answerTimeout;

	private final int maxNumberOfParallelNetworkRequests;

	private final int maxNumberOfParallelNetworkRequestsPerServer;

	private final RequestLimit limitOverAllServers;

	/** Hands out an executor running what it is given once the given time has passed. */
	private final Function<Duration, Executor> delays;

	/** Hands out a number from zero up to, but not including, one, which varies a wait. */
	private final DoubleSupplier randomness;

	private final Map<URI, CompletableFuture<Outcome>> answersByUri = new ConcurrentHashMap<>();

	private final Map<String, RequestLimit> limitsByServer = new ConcurrentHashMap<>();

	/**
	 * Creates a checker asking over HTTP, giving an address the times this class defaults to:
	 * {@link #DEFAULT_CONNECT_TIMEOUT} to accept the connection and {@link #DEFAULT_ANSWER_TIMEOUT}
	 * to answer, and sending no more than {@link #DEFAULT_MAX_NUMBER_OF_PARALLEL_NETWORK_REQUESTS}
	 * requests at once, no more than {@link #DEFAULT_MAX_NUMBER_OF_PARALLEL_NETWORK_REQUESTS_PER_SERVER}
	 * of them to one server.
	 */
	public HttpUriReachabilityChecker() {
		this(DEFAULT_CONNECT_TIMEOUT, DEFAULT_ANSWER_TIMEOUT);
	}

	/**
	 * Creates a checker asking over HTTP, giving an address the times a caller says.
	 * 
	 * <p>What is long enough depends on the network a caller sits in and on what its addresses lead
	 * to, which is nothing this library can know. Whoever needs other times says them here instead
	 * of writing a check of their own: everything else this one does &ndash; remembering an answer,
	 * joining a request already in flight, following a redirect, naming itself so that a server
	 * refusing unknown callers answers, reading the reason a failure gives &ndash; is kept.</p>
	 * 
	 * <p>{@link #DEFAULT_CONNECT_TIMEOUT} and {@link #DEFAULT_ANSWER_TIMEOUT} are there so that
	 * changing one of the two does not mean restating the other.</p>
	 * 
	 * @param untilConnected how long the far side has to accept the connection, must not be
	 *        <code>null</code> and must be longer than nothing
	 * @param untilAnswered how long the far side has to answer, must not be <code>null</code>, must
	 *        be longer than nothing and must not be shorter than <code>untilConnected</code>,
	 *        because connecting is part of answering
	 * @throws IllegalArgumentException if one of the arguments is <code>null</code>, if one of them
	 *         is zero or negative, or if the time to answer is shorter than the time to connect
	 */
	public HttpUriReachabilityChecker(Duration untilConnected, Duration untilAnswered) {
		this(untilConnected, untilAnswered, DEFAULT_MAX_NUMBER_OF_PARALLEL_NETWORK_REQUESTS,
				DEFAULT_MAX_NUMBER_OF_PARALLEL_NETWORK_REQUESTS_PER_SERVER);
	}

	/**
	 * Creates a checker asking over HTTP, giving an address the times a caller says and sending no
	 * more requests at once, to all servers together and to one server, than the caller says.
	 * 
	 * <p>Everything {@link #HttpUriReachabilityChecker(Duration, Duration)} says holds. How many
	 * requests a server answers well at once depends on the server and the network in between, and
	 * how many requests the machine asking can keep open depends on that machine, so a caller who
	 * measured either says so here. A limit per server above the one over all servers is allowed;
	 * the smaller one then holds.</p>
	 * 
	 * @param untilConnected how long the far side has to accept the connection, must not be
	 *        <code>null</code> and must be longer than nothing
	 * @param untilAnswered how long the far side has to answer, must not be <code>null</code>, must
	 *        be longer than nothing and must not be shorter than <code>untilConnected</code>
	 * @param maxNumberOfParallelNetworkRequests how many requests may be on their way to all servers
	 *        together at once, at least one
	 * @param maxNumberOfParallelNetworkRequestsPerServer how many requests may be on their way to one
	 *        server at once, at least one
	 * @throws IllegalArgumentException if one of the times is <code>null</code>, if one of them is
	 *         zero or negative, if the time to answer is shorter than the time to connect, or if
	 *         one of the numbers of requests is less than one
	 */
	public HttpUriReachabilityChecker(Duration untilConnected, Duration untilAnswered,
			int maxNumberOfParallelNetworkRequests, int maxNumberOfParallelNetworkRequestsPerServer) {
		this(clientsConnectingWithin(untilConnected, untilAnswered), untilAnswered,
				maxNumberOfParallelNetworkRequests, maxNumberOfParallelNetworkRequestsPerServer);
	}

	private static Function<Executor, HttpClient> clientsConnectingWithin(Duration untilConnected,
			Duration untilAnswered) {
		if (untilConnected == null || untilAnswered == null) {
			throw new IllegalArgumentException("Arguments must not be null.");
		}
		if (untilConnected.isZero() || untilConnected.isNegative()
				|| untilAnswered.isZero() || untilAnswered.isNegative()) {
			throw new IllegalArgumentException("An address has to be given a time longer than nothing,"
					+ " but was given " + untilConnected + " to connect and " + untilAnswered + " to answer.");
		}
		if (untilConnected.compareTo(untilAnswered) > 0) {
			throw new IllegalArgumentException("Connecting is part of answering, so an address cannot be"
					+ " given less time to answer than to connect, but was given " + untilConnected
					+ " to connect and " + untilAnswered + " to answer.");
		}

		return executor -> HttpClient.newBuilder()
				.connectTimeout(untilConnected)
				.followRedirects(HttpClient.Redirect.NORMAL)
				.executor(executor)
				.build();
	}

	/**
	 * Creates a checker asking with the given client in every view, so that a test can say what an
	 * address answers.
	 * 
	 * @param httpClient the client to ask with, must not be <code>null</code>
	 * @param answerTimeout how long an address has to answer, must not be <code>null</code>
	 * @throws IllegalArgumentException if one of the arguments is <code>null</code>
	 */
	HttpUriReachabilityChecker(HttpClient httpClient, Duration answerTimeout) {
		this(alwaysHandingOut(httpClient), answerTimeout);
	}

	private static Function<Executor, HttpClient> alwaysHandingOut(HttpClient httpClient) {
		return httpClient == null ? null : executor -> httpClient;
	}

	/**
	 * Creates a checker asking with the clients the given factory creates, so that a test can say
	 * what an address answers in each view.
	 * 
	 * @param clientFactory creates a client executing on the executor it is given, must not be
	 *                      <code>null</code>
	 * @param answerTimeout how long an address has to answer, must not be <code>null</code>
	 * @throws IllegalArgumentException if one of the arguments is <code>null</code>
	 */
	HttpUriReachabilityChecker(Function<Executor, HttpClient> clientFactory, Duration answerTimeout) {
		this(clientFactory, answerTimeout, DEFAULT_MAX_NUMBER_OF_PARALLEL_NETWORK_REQUESTS_PER_SERVER);
	}

	/**
	 * Creates a checker asking with the clients the given factory creates and sending no more
	 * requests to one server at once than given, so that a test can watch the limit per server.
	 * 
	 * @param clientFactory creates a client executing on the executor it is given, must not be
	 *                      <code>null</code>
	 * @param answerTimeout how long an address has to answer, must not be <code>null</code>
	 * @param maxNumberOfParallelNetworkRequestsPerServer how many requests may be on their way to
	 *                      one server at once, at least one
	 * @throws IllegalArgumentException if one of the arguments is <code>null</code> or the number
	 *         of requests is less than one
	 */
	HttpUriReachabilityChecker(Function<Executor, HttpClient> clientFactory, Duration answerTimeout,
			int maxNumberOfParallelNetworkRequestsPerServer) {
		this(clientFactory, answerTimeout, DEFAULT_MAX_NUMBER_OF_PARALLEL_NETWORK_REQUESTS,
				maxNumberOfParallelNetworkRequestsPerServer);
	}

	/**
	 * Creates a checker asking with the clients the given factory creates and sending no more
	 * requests at once, to all servers together and to one server, than given, so that a test can
	 * watch both limits.
	 * 
	 * @param clientFactory creates a client executing on the executor it is given, must not be
	 *                      <code>null</code>
	 * @param answerTimeout how long an address has to answer, must not be <code>null</code>
	 * @param maxNumberOfParallelNetworkRequests how many requests may be on their way to all
	 *                      servers together at once, at least one
	 * @param maxNumberOfParallelNetworkRequestsPerServer how many requests may be on their way to
	 *                      one server at once, at least one
	 * @throws IllegalArgumentException if one of the arguments is <code>null</code> or one of the
	 *         numbers of requests is less than one
	 */
	HttpUriReachabilityChecker(Function<Executor, HttpClient> clientFactory, Duration answerTimeout,
			int maxNumberOfParallelNetworkRequests, int maxNumberOfParallelNetworkRequestsPerServer) {
		this(clientFactory, answerTimeout, maxNumberOfParallelNetworkRequests,
				maxNumberOfParallelNetworkRequestsPerServer,
				wait -> CompletableFuture.delayedExecutor(wait.toMillis(), TimeUnit.MILLISECONDS),
				() -> ThreadLocalRandom.current().nextDouble());
	}

	/**
	 * Creates a checker asking with the clients the given factory creates, sending no more requests
	 * at once than given, and waiting before asking an address again as the given delays and
	 * randomness say, so that a test can watch the waits without waiting.
	 * 
	 * @param clientFactory creates a client executing on the executor it is given, must not be
	 *                      <code>null</code>
	 * @param answerTimeout how long an address has to answer, must not be <code>null</code>
	 * @param maxNumberOfParallelNetworkRequests how many requests may be on their way to all
	 *                      servers together at once, at least one
	 * @param maxNumberOfParallelNetworkRequestsPerServer how many requests may be on their way to
	 *                      one server at once, at least one
	 * @param delays hands out an executor running what it is given once the given time has passed,
	 *                      must not be <code>null</code>
	 * @param randomness hands out a number from zero up to, but not including, one, which varies a
	 *                      wait, must not be <code>null</code>
	 * @throws IllegalArgumentException if one of the arguments is <code>null</code> or one of the
	 *         numbers of requests is less than one
	 */
	HttpUriReachabilityChecker(Function<Executor, HttpClient> clientFactory, Duration answerTimeout,
			int maxNumberOfParallelNetworkRequests, int maxNumberOfParallelNetworkRequestsPerServer,
			Function<Duration, Executor> delays, DoubleSupplier randomness) {
		if (clientFactory == null || answerTimeout == null || delays == null || randomness == null) {
			throw new IllegalArgumentException("Arguments must not be null.");
		}
		if (maxNumberOfParallelNetworkRequests < 1 || maxNumberOfParallelNetworkRequestsPerServer < 1) {
			throw new IllegalArgumentException("At least one request has to be allowed at once, but "
					+ maxNumberOfParallelNetworkRequests + " were to all servers and "
					+ maxNumberOfParallelNetworkRequestsPerServer + " to one server.");
		}
		this.clientFactory = clientFactory;
		this.answerTimeout = answerTimeout;
		this.maxNumberOfParallelNetworkRequests = maxNumberOfParallelNetworkRequests;
		this.maxNumberOfParallelNetworkRequestsPerServer = maxNumberOfParallelNetworkRequestsPerServer;
		this.limitOverAllServers = new RequestLimit(maxNumberOfParallelNetworkRequests);
		this.delays = delays;
		this.randomness = randomness;
	}

	/**
	 * Creates a client asking as this checker says, as a view opened for a run does.
	 * 
	 * @param executor what the client executes on, must not be <code>null</code>
	 * @return the client, so that a test can read what it was given; whoever calls this closes it
	 */
	HttpClient createClient(Executor executor) {
		return this.clientFactory.apply(executor);
	}

	/** @return how long an address is given to answer, so that a test can read it */
	Duration getAnswerTimeout() {
		return this.answerTimeout;
	}

	/**
	 * Returns how many requests this checker sends to all servers together at once.
	 * 
	 * @return the number, at least one
	 */
	public int getMaxNumberOfParallelNetworkRequests() {
		return this.maxNumberOfParallelNetworkRequests;
	}

	/**
	 * Returns how many requests this checker sends to one server at once.
	 * 
	 * @return the number, at least one
	 */
	public int getMaxNumberOfParallelNetworkRequestsPerServer() {
		return this.maxNumberOfParallelNetworkRequestsPerServer;
	}

	/**
	 * Opens a view asking with a client of its own, created as this checker says and executing on
	 * the given executor, and sharing the answers with this checker.
	 * 
	 * @param executor what the run executes its work on, which the client of the view executes on as
	 *                 well, must not be <code>null</code>
	 * @return the view, never <code>null</code>
	 * @throws IllegalArgumentException if the given executor is <code>null</code>
	 */
	@Override
	public OfRun openForRun(Executor executor) {
		if (executor == null) {
			throw new IllegalArgumentException("Argument must not be null.");
		}
		return new ViewOfRun(createClient(executor));
	}

	/**
	 * Hands out the remembered answer for the given address, asking it with the given client where
	 * nobody asked yet.
	 * 
	 * <p>The answer is remembered while it is still being waited for, so that every other document
	 * naming the address waits for the same request. What is handed out is a promise of its own, so
	 * that a caller giving up on the answer does not give up on the one every other caller waits
	 * for.</p>
	 * 
	 * <p>An answer that was not waited for, because the view asking for it was cancelled, is
	 * forgotten. A caller whose own view was not cancelled asks again with its own client rather
	 * than going without the answer.</p>
	 * 
	 * <p>An answer saying that the server could not answer now is forgotten as well, but kept among
	 * the answers of the run the caller belongs to, so that the run asks once and the next run asks
	 * again.</p>
	 */
	private CompletableFuture<UriReachability> answerOf(URI targetUri, HttpClient client, BooleanSupplier aborted,
			Map<URI, UriReachability> answersOfTheRunOnly) {
		UriReachability answerOfTheRun = answersOfTheRunOnly.get(targetUri);
		if (answerOfTheRun != null) {
			return CompletableFuture.completedFuture(answerOfTheRun);
		}

		CompletableFuture<Outcome> answer =
				this.answersByUri.computeIfAbsent(targetUri, uri -> ask(uri, client, aborted));
		answer.whenComplete((outcome, failure) -> {
			if (failure != null) {
				this.answersByUri.remove(targetUri, answer);
			} else if (!outcome.lasting()) {
				answersOfTheRunOnly.putIfAbsent(targetUri, outcome.reachability());
				this.answersByUri.remove(targetUri, answer);
			}
		});

		return answer.thenApply(Outcome::reachability).exceptionallyCompose(failure -> {
			if (aborted.getAsBoolean()) {
				return CompletableFuture.failedFuture(failure);
			}
			this.answersByUri.remove(targetUri, answer);
			return answerOf(targetUri, client, aborted, answersOfTheRunOnly);
		});
	}

	/**
	 * Forgets every answer, so that every address is asked again.
	 */
	@Override
	public void clearCaches() {
		this.answersByUri.clear();
	}

	private CompletableFuture<Outcome> ask(URI targetUri, HttpClient client, BooleanSupplier aborted) {
		HttpRequest request;
		try {
			request = HttpRequest.newBuilder(targetUri)
					.method("HEAD", BodyPublishers.noBody())
					.timeout(this.answerTimeout)
					.header("User-Agent", USER_AGENT)
					.header("Accept", ACCEPTED_CONTENT)
					.build();
		} catch (IllegalArgumentException exception) {
			// an address HTTP cannot ask about, e.g. one naming another scheme
			return CompletableFuture.completedFuture(new Outcome(notReached(targetUri, exception), true));
		}

		return askAgain(targetUri, request, client, aborted, 0);
	}

	/**
	 * Sends the request, and sends it again after a wait where the server said that it cannot answer
	 * now and was not asked again as often as allowed yet.
	 * 
	 * @param retriesSoFar how often the request was sent again already
	 */
	private CompletableFuture<Outcome> askAgain(URI targetUri, HttpRequest request, HttpClient client,
			BooleanSupplier aborted, int retriesSoFar) {
		return send(targetUri, request, client, aborted)
				.handle((response, failure) -> {
					if (failure != null) {
						if (aborted.getAsBoolean()) {
							return CompletableFuture.<Outcome>failedFuture(new CancellationException(
									"Nobody waited for the answer of the web address " + targetUri + " any more."));
						}
						if (!isRefusedForTooManyStreams(failure)) {
							return CompletableFuture.completedFuture(new Outcome(notReached(targetUri, failure), true));
						}
						// a refused request never reached the server, so it is sent again at once the first time
						return retriesSoFar < MAX_NUMBER_OF_RETRIES
								? askAgainAfter(retriesSoFar == 0 ? Duration.ZERO : varied(SECOND_WAIT),
										targetUri, request, client, aborted, retriesSoFar)
								: CompletableFuture.completedFuture(
										busy(targetUri, reasonOf(failure), retriesSoFar));
					}

					int statusCode = response.statusCode();
					if (!STATUS_CODES_OF_A_BUSY_SERVER.contains(statusCode)) {
						return CompletableFuture.completedFuture(
								new Outcome(new UriReachability.Answered(statusCode), true));
					}
					Optional<Duration> wait = retriesSoFar < MAX_NUMBER_OF_RETRIES
							? waitBeforeAskingAgain(response, retriesSoFar)
							: Optional.empty();
					if (wait.isPresent()) {
						return askAgainAfter(wait.get(), targetUri, request, client, aborted, retriesSoFar);
					}
					return CompletableFuture.completedFuture(statusCode == STATUS_CODE_TOO_MANY_REQUESTS
							? askedTooOften(targetUri, retriesSoFar)
							: busy(targetUri, "HTTP status code " + statusCode, retriesSoFar));
				})
				.thenCompose(Function.identity());
	}

	private CompletableFuture<Outcome> askAgainAfter(Duration wait, URI targetUri, HttpRequest request,
			HttpClient client, BooleanSupplier aborted, int retriesSoFar) {
		LOG.debug("The web address {} cannot answer now and is asked again in {} ms.", targetUri, wait.toMillis());
		// the request waits outside the limits, so that it holds no place another request could use
		CompletableFuture<Void> waited = wait.isZero()
				? CompletableFuture.completedFuture(null)
				: CompletableFuture.runAsync(() -> { }, this.delays.apply(wait));
		return waited.thenCompose(ignored -> askAgain(targetUri, request, client, aborted, retriesSoFar + 1));
	}

	/**
	 * Sends the request once its turn has come at its server and among all servers.
	 */
	private CompletableFuture<HttpResponse<Void>> send(URI targetUri, HttpRequest request, HttpClient client,
			BooleanSupplier aborted) {
		// the turn at the server comes first, so that a request held up by a busy server takes no
		// place among all servers that a request to another server could use
		return limitOf(targetUri)
				.submit(() -> this.limitOverAllServers.submit(() -> aborted.getAsBoolean()
						? CompletableFuture.failedFuture(new CancellationException("Nobody waits for the answer"
								+ " of the web address " + targetUri + " any more, so it is not asked."))
						: client.sendAsync(request, BodyHandlers.discarding())));
	}

	/**
	 * Hands out the limit of the server the given address leads to. A server is told apart by the
	 * scheme and the authority, since the client opens a connection for each of the two.
	 */
	private RequestLimit limitOf(URI targetUri) {
		String server = (targetUri.getScheme() + "://" + targetUri.getRawAuthority()).toLowerCase(Locale.ROOT);
		return this.limitsByServer.computeIfAbsent(server,
				ignored -> new RequestLimit(this.maxNumberOfParallelNetworkRequestsPerServer));
	}

	/**
	 * Says how long to wait before asking a busy server again: as long as the server asks for in
	 * <code>Retry-After</code>, if it says so in a way that can be read, otherwise about one second
	 * the first time and about three seconds the second time.
	 * 
	 * @return the wait, or nothing if the server asks for a longer one than
	 *         {@link #LONGEST_WAIT_A_SERVER_MAY_ASK_FOR}
	 */
	private Optional<Duration> waitBeforeAskingAgain(HttpResponse<?> response, int retriesSoFar) {
		Optional<Duration> askedFor = response.headers().firstValue("Retry-After")
				.flatMap(HttpUriReachabilityChecker::waitOf);
		if (askedFor.isPresent()) {
			return askedFor.get().compareTo(LONGEST_WAIT_A_SERVER_MAY_ASK_FOR) <= 0 ? askedFor : Optional.empty();
		}
		return Optional.of(varied(retriesSoFar == 0 ? FIRST_WAIT : SECOND_WAIT));
	}

	/**
	 * Reads a <code>Retry-After</code>, which is either a number of seconds or the time from which
	 * on to ask again.
	 * 
	 * @return the wait, never negative, or nothing if the value can be read neither way
	 */
	static Optional<Duration> waitOf(String retryAfter) {
		String value = retryAfter.trim();
		if (!value.isEmpty() && value.chars().allMatch(Character::isDigit)) {
			// a number too large for a long is a wait too long anyway
			return Optional.of(value.length() > 12 ? ChronoUnit.FOREVER.getDuration()
					: Duration.ofSeconds(Long.parseLong(value)));
		}
		try {
			Duration untilThen = Duration.between(ZonedDateTime.now(),
					ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME));
			return Optional.of(untilThen.isNegative() ? Duration.ZERO : untilThen);
		} catch (DateTimeParseException exception) {
			return Optional.empty();
		}
	}

	/** Varies the given wait by up to a quarter, up or down, at random. */
	private Duration varied(Duration wait) {
		return Duration.ofMillis(Math.round(wait.toMillis() * (0.75 + 0.5 * this.randomness.getAsDouble())));
	}
	private static boolean isRefusedForTooManyStreams(Throwable failure) {
		Throwable cause = failure instanceof CompletionException && failure.getCause() != null
				? failure.getCause()
				: failure;

		// the client says so only in the message of an IOException
		return cause instanceof IOException
				&& cause.getMessage() != null
				&& cause.getMessage().contains(TOO_MANY_STREAMS);
	}

	/**
	 * The check of one validation run, asking with a client of its own and sharing the answers with
	 * the checker.
	 */
	private final class ViewOfRun implements OfRun {

		private final HttpClient client;

		private volatile boolean aborted;

		/** What the servers said they could not answer now, remembered for this run only. */
		private final Map<URI, UriReachability> answersOfThisRunOnly = new ConcurrentHashMap<>();

		private ViewOfRun(HttpClient client) {
			this.client = client;
		}

		@Override
		public CompletableFuture<UriReachability> check(URI targetUri) {
			if (targetUri == null) {
				throw new IllegalArgumentException("Argument must not be null.");
			}
			return answerOf(targetUri, this.client, () -> this.aborted, this.answersOfThisRunOnly);
		}

		@Override
		public void cancel() {
			this.aborted = true;
			this.client.shutdownNow();
		}

		@Override
		public void close() {
			this.client.close();
		}

	}

	/**
	 * Says that an address was asked and gave no answer.
	 * 
	 * <p>The caller is handed the reason so that it can decide what the author of the document
	 * should hear, and the author does hear it: an address that answers nothing becomes a finding
	 * against the link that carries it. The log therefore keeps the exception rather than the
	 * news, and keeps it at debug level, because an address that cannot be asked and an address
	 * that is genuinely gone look alike from the outside and only the exception tells the two
	 * apart. Saying that at warning level would put one line into the log for every address of a
	 * document, which is loudest exactly when the network is down and every one of them is
	 * wrong.</p>
	 */
	private static UriReachability notReached(URI targetUri, Throwable failure) {
		String reason = reasonOf(failure);
		LOG.debug("The web address {} was asked and gave no answer: {}", targetUri, reason, failure);
		return new UriReachability.NotReached(reason);
	}

	/**
	 * Says that an address was asked, and asked again, and still said that it cannot answer now.
	 * 
	 * <p>Unlike an address that gave no answer, this one is logged at warning level: it says
	 * nothing about the address, so the finding made of it says only that the address was not
	 * checked, and whoever reads the log learns why.</p>
	 */
	private static Outcome busy(URI targetUri, String reason, int retriesSoFar) {
		LOG.warn("The web address {} was asked {} times and could not answer: {}", targetUri, retriesSoFar + 1,
				reason);
		return new Outcome(new UriReachability.NotReached(reason), false);
	}

	/**
	 * Says that an address was asked, and asked again, and its server still said that it was asked
	 * too often.
	 * 
	 * <p>Unlike any other busy server, this one did answer, and the address is handed back as
	 * answering with the status code 429. A server counting the requests of whoever asks is there,
	 * and its limit says nothing about the address asked. It is logged at warning level, because
	 * the address was not checked, and it is remembered for the run that asked only.</p>
	 */
	private static Outcome askedTooOften(URI targetUri, int retriesSoFar) {
		LOG.warn("The web address {} was asked {} times and its server still said that it was asked too often"
				+ " (HTTP status code 429), so the address is not checked.", targetUri, retriesSoFar + 1);
		return new Outcome(new UriReachability.Answered(STATUS_CODE_TOO_MANY_REQUESTS), false);
	}

	private static String reasonOf(Throwable throwable) {
		Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
				? throwable.getCause()
				: throwable;

		String message = cause.getMessage();
		return message == null || message.isBlank() ? cause.getClass().getName() : message;
	}

	/**
	 * What asking an address came to, and whether that may be remembered for as long as the checker
	 * lives.
	 * 
	 * @param reachability what the address answered, or why it did not
	 * @param lasting <code>false</code> if the server said that it cannot answer now, which the next
	 *                run is to ask again
	 */
	private record Outcome(UriReachability reachability, boolean lasting) {
	}

}