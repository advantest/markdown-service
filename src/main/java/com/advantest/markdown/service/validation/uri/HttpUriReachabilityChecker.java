/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation.uri;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
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
 * checker lives. An address that costs a timeout costs it once and not once per document naming
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

	// Some servers answer a request only if they recognize who is asking, and refuse everything
	// else, so this checker names itself after a tool such a server is used to.
	private static final Logger LOG = LoggerFactory.getLogger(HttpUriReachabilityChecker.class);

	private static final String USER_AGENT = "curl/8.11.0";

	private static final String ACCEPTED_CONTENT = "*/*";

	/** Creates a client asking as this checker says, executing on the given executor. */
	private final Function<Executor, HttpClient> clientFactory;

	private final Duration answerTimeout;

	private final Map<URI, CompletableFuture<UriReachability>> answersByUri = new ConcurrentHashMap<>();

	/**
	 * Creates a checker asking over HTTP, giving an address the times this class defaults to:
	 * {@link #DEFAULT_CONNECT_TIMEOUT} to accept the connection and {@link #DEFAULT_ANSWER_TIMEOUT}
	 * to answer.
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
		this(clientsConnectingWithin(untilConnected, untilAnswered), untilAnswered);
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
		if (clientFactory == null || answerTimeout == null) {
			throw new IllegalArgumentException("Arguments must not be null.");
		}
		this.clientFactory = clientFactory;
		this.answerTimeout = answerTimeout;
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
	 */
	private CompletableFuture<UriReachability> answerOf(URI targetUri, HttpClient client, BooleanSupplier aborted) {
		CompletableFuture<UriReachability> answer =
				this.answersByUri.computeIfAbsent(targetUri, uri -> ask(uri, client, aborted));
		answer.whenComplete((reachability, failure) -> {
			if (failure != null) {
				this.answersByUri.remove(targetUri, answer);
			}
		});

		return answer.exceptionallyCompose(failure -> {
			if (aborted.getAsBoolean()) {
				return CompletableFuture.failedFuture(failure);
			}
			this.answersByUri.remove(targetUri, answer);
			return answerOf(targetUri, client, aborted);
		});
	}

	/**
	 * Forgets every answer, so that every address is asked again.
	 */
	@Override
	public void clearCaches() {
		this.answersByUri.clear();
	}

	private CompletableFuture<UriReachability> ask(URI targetUri, HttpClient client, BooleanSupplier aborted) {
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
			return CompletableFuture.completedFuture(notReached(targetUri, exception));
		}

		return client.sendAsync(request, BodyHandlers.discarding())
				.<UriReachability>thenApply(response -> new UriReachability.Answered(response.statusCode()))
				.exceptionally(throwable -> {
					if (aborted.getAsBoolean()) {
						throw new CancellationException("Nobody waited for the answer of the web address "
								+ targetUri + " any more.");
					}
					return notReached(targetUri, throwable);
				});
	}

	/**
	 * The check of one validation run, asking with a client of its own and sharing the answers with
	 * the checker.
	 */
	private final class ViewOfRun implements OfRun {

		private final HttpClient client;

		private volatile boolean aborted;

		private ViewOfRun(HttpClient client) {
			this.client = client;
		}

		@Override
		public CompletableFuture<UriReachability> check(URI targetUri) {
			if (targetUri == null) {
				throw new IllegalArgumentException("Argument must not be null.");
			}
			return answerOf(targetUri, this.client, () -> this.aborted);
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

	private static String reasonOf(Throwable throwable) {
		Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
				? throwable.getCause()
				: throwable;

		String message = cause.getMessage();
		return message == null || message.isBlank() ? cause.getClass().getName() : message;
	}

}
