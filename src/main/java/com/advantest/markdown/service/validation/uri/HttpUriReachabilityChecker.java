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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

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
 * so a checker living in a long running program is told to {@link #clearCache() forget} what it
 * knows when its answers may have gone stale.</p>
 * 
 * <p>Instances of this class can be used from several threads at once.</p>
 */
public final class HttpUriReachabilityChecker implements UriReachabilityChecker {

	/** How long it may take until the far side accepts the connection. */
	static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(2);

	/** How long it may take until the far side has answered. */
	static final Duration DEFAULT_ANSWER_TIMEOUT = Duration.ofSeconds(5);

	// Some servers answer a request only if they recognize who is asking, and refuse everything
	// else, so this checker names itself after a tool such a server is used to.
	private static final String USER_AGENT = "curl/8.11.0";

	private static final String ACCEPTED_CONTENT = "*/*";

	private final HttpClient httpClient;

	private final Duration answerTimeout;

	private final Map<URI, CompletableFuture<UriReachability>> answersByUri = new ConcurrentHashMap<>();

	/**
	 * Creates a checker asking over HTTP, giving an address about two seconds to accept the
	 * connection and about five seconds to answer.
	 */
	public HttpUriReachabilityChecker() {
		this(HttpClient.newBuilder()
				.connectTimeout(DEFAULT_CONNECT_TIMEOUT)
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build(),
				DEFAULT_ANSWER_TIMEOUT);
	}

	/**
	 * Creates a checker asking with the given client, so that a test can say what an address
	 * answers.
	 * 
	 * @param httpClient the client to ask with, must not be <code>null</code>
	 * @param answerTimeout how long an address has to answer, must not be <code>null</code>
	 * @throws IllegalArgumentException if one of the arguments is <code>null</code>
	 */
	HttpUriReachabilityChecker(HttpClient httpClient, Duration answerTimeout) {
		if (httpClient == null || answerTimeout == null) {
			throw new IllegalArgumentException("Arguments must not be null.");
		}
		this.httpClient = httpClient;
		this.answerTimeout = answerTimeout;
	}

	@Override
	public CompletableFuture<UriReachability> check(URI targetUri) {
		if (targetUri == null) {
			throw new IllegalArgumentException("Argument must not be null.");
		}

		// a copy, so that a caller giving up on an answer gives up on its own promise and not on
		// the one every other document naming that address waits for
		return this.answersByUri.computeIfAbsent(targetUri, this::ask).copy();
	}

	/**
	 * Forgets every answer, so that every address is asked again.
	 */
	public void clearCache() {
		this.answersByUri.clear();
	}

	private CompletableFuture<UriReachability> ask(URI targetUri) {
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
			return CompletableFuture.completedFuture(new UriReachability.NotReached(reasonOf(exception)));
		}

		return this.httpClient.sendAsync(request, BodyHandlers.discarding())
				.<UriReachability>thenApply(response -> new UriReachability.Answered(response.statusCode()))
				.exceptionally(throwable -> new UriReachability.NotReached(reasonOf(throwable)));
	}

	private static String reasonOf(Throwable throwable) {
		Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
				? throwable.getCause()
				: throwable;

		String message = cause.getMessage();
		return message == null || message.isBlank() ? cause.getClass().getName() : message;
	}

}
