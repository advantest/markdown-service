/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.resources;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.advantest.markdown.service.parsing.LinkTarget;
import com.advantest.resources.FileSchemeUriResolver;
import com.advantest.resources.LocalFileSystemResourceResolver;
import com.advantest.resources.ResourceResolver;
import com.advantest.resources.UriResolver;

/**
 * Knows which resolver answers for a reference written in a document.
 * 
 * <p>Two kinds of reference are told apart. A reference without a scheme, e.g.
 * <code>../images/logo.png</code>, names a resource of the environment this code runs in, and
 * there is one resolver for that environment or there is none. A reference naming a scheme, e.g.
 * <code>https://example.org/guide</code>, is offered to the registered {@link UriResolver}s, the
 * last registered one that says it is responsible answering for it, so that a resolver added later
 * can claim what a more general one would have taken.</p>
 * 
 * <p>A reference whose scheme is a single letter is a drive letter of a file system, e.g.
 * <code>C:/docs/guide.md</code>, and therefore names a resource of this environment. No scheme in
 * use is a single letter, so nothing is taken away from the resolvers of a scheme by reading it
 * that way.</p>
 * 
 * <p>A reference nobody answers for is left alone: it is not this environment's business, and
 * saying anything about it would be guessing.</p>
 */
public final class ResourceResolverRegistry {

	private final ResourceResolver localFileSystemResolver;

	private final List<UriResolver> uriResolvers;

	/**
	 * Creates a registry with the given resolvers.
	 * 
	 * @param localFileSystemResolver the resolver of the environment this code runs in, or
	 *                                <code>null</code> if references to it are not resolved at all
	 * @param uriResolvers the resolvers of references naming a scheme, in the order in which they
	 *                     were registered, must not be <code>null</code>
	 * @throws IllegalArgumentException if the list of URI resolvers is <code>null</code> or
	 *                                  contains <code>null</code>
	 */
	public ResourceResolverRegistry(ResourceResolver localFileSystemResolver, List<UriResolver> uriResolvers) {
		if (uriResolvers == null || uriResolvers.stream().anyMatch(resolver -> resolver == null)) {
			throw new IllegalArgumentException("Argument must be a list without null elements.");
		}

		this.localFileSystemResolver = localFileSystemResolver;

		List<UriResolver> lastRegisteredFirst = new ArrayList<>(uriResolvers);
		Collections.reverse(lastRegisteredFirst);
		this.uriResolvers = List.copyOf(lastRegisteredFirst);
	}

	/**
	 * Creates the registry used when nothing else is asked for: references are resolved in the file
	 * system of the machine this code runs on, whether they name it with the <code>file</code>
	 * scheme or not.
	 * 
	 * @return the newly created registry, never <code>null</code>
	 */
	public static ResourceResolverRegistry ofLocalFileSystem() {
		return ofLocalFileSystem(new LocalFileSystemResourceResolver());
	}

	/**
	 * Creates a registry resolving references with the given resolver of the local file system,
	 * whether they name it with the <code>file</code> scheme or not.
	 * 
	 * @param localFileSystemResolver the resolver of the local file system, must not be
	 *                                <code>null</code>
	 * @return the newly created registry, never <code>null</code>
	 * @throws IllegalArgumentException if the given resolver is <code>null</code>
	 */
	public static ResourceResolverRegistry ofLocalFileSystem(ResourceResolver localFileSystemResolver) {
		if (localFileSystemResolver == null) {
			throw new IllegalArgumentException("Argument must not be null.");
		}
		return new ResourceResolverRegistry(localFileSystemResolver,
				List.of(new FileSchemeUriResolver(localFileSystemResolver)));
	}

	/**
	 * Returns the resolver answering for the given reference.
	 * 
	 * @param targetResourcePathOrUri the reference as it is written in the document, must be
	 *                                neither <code>null</code> nor blank
	 * @return the resolver to ask, or an empty {@link Optional} if nobody answers for the reference
	 * @throws IllegalArgumentException if the given reference is <code>null</code> or blank
	 */
	public Optional<ResourceResolver> resolverFor(String targetResourcePathOrUri) {
		if (targetResourcePathOrUri == null || targetResourcePathOrUri.isBlank()) {
			throw new IllegalArgumentException("Argument must be a non-blank resource path or URI.");
		}

		if (namesAResourceOfThisEnvironment(targetResourcePathOrUri)) {
			return Optional.ofNullable(this.localFileSystemResolver);
		}

		return uriResolverFor(targetResourcePathOrUri).map(ResourceResolver.class::cast);
	}

	/**
	 * Returns the resolver of a scheme answering for the given reference.
	 * 
	 * <p>A reference that names a scheme but that the URI syntax rejects reaches no resolver: a
	 * resolver of a scheme is asked with a URI, and there is none to hand over.</p>
	 * 
	 * @param targetResourcePathOrUri the reference as it is written in the document, must be
	 *                                neither <code>null</code> nor blank
	 * @return the resolver to ask, or an empty {@link Optional} if nobody answers for the reference
	 * @throws IllegalArgumentException if the given reference is <code>null</code> or blank
	 */
	public Optional<UriResolver> uriResolverFor(String targetResourcePathOrUri) {
		if (targetResourcePathOrUri == null || targetResourcePathOrUri.isBlank()) {
			throw new IllegalArgumentException("Argument must be a non-blank resource path or URI.");
		}

		URI targetUri;
		try {
			targetUri = URI.create(targetResourcePathOrUri);
		} catch (IllegalArgumentException exception) {
			return Optional.empty();
		}

		return this.uriResolvers.stream()
				.filter(resolver -> resolver.isResponsibleFor(targetUri))
				.findFirst();
	}

	/**
	 * Tells whether the given reference names a resource of the environment this code runs in,
	 * i.e. whether it names no scheme or a drive letter.
	 * 
	 * @param targetResourcePathOrUri the reference as it is written in the document, must not be
	 *                                <code>null</code>
	 * @return <code>true</code> if and only if the reference names a resource of this environment
	 * @throws IllegalArgumentException if the given reference is <code>null</code>
	 */
	public static boolean namesAResourceOfThisEnvironment(String targetResourcePathOrUri) {
		String scheme = LinkTarget.of(targetResourcePathOrUri).scheme();
		return scheme == null || scheme.length() == 1;
	}

}
