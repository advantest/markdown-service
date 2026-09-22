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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.advantest.markdown.service.parsing.LinkTarget;
import com.advantest.resources.FileSchemeUriResolver;
import com.advantest.resources.LocalFileSystemResourceResolver;
import com.advantest.resources.RelativePathResourceResolver;
import com.advantest.resources.ResourceResolver;
import com.advantest.resources.UriResolver;

/**
 * Knows which resolver answers for a reference written in a document.
 * 
 * <p>Three kinds of reference are told apart. A reference without a scheme and without a leading
 * separator, e.g. <code>../images/logo.png</code>, has a meaning only together with the document
 * carrying it, and the one registered {@link RelativePathResourceResolver} says what that meaning
 * is. A reference that is an absolute path, e.g. <code>/usr/share/doc/guide.md</code> or
 * <code>C:\documents\guide.md</code>, names its resource on one machine and nowhere else, so it is
 * resolved by nobody. Everything else names a scheme and is offered to the registered
 * {@link UriResolver}s, the last registered one that says it is responsible answering for it, so
 * that a resolver added later can claim what a more general one would have taken.</p>
 * 
 * <p>A reference whose scheme is a single letter is a drive letter of a file system, e.g.
 * <code>C:/docs/guide.md</code>, and therefore an absolute path. No scheme in use is a single
 * letter, so nothing is taken away from the resolvers of a scheme by reading it that way.</p>
 * 
 * <p>A reference nobody answers for is left alone: it is not this environment's business, and
 * saying anything about it would be guessing.</p>
 */
public final class ResourceResolverRegistry {

	private static final Logger LOG = LoggerFactory.getLogger(ResourceResolverRegistry.class);

	private final RelativePathResourceResolver relativePathResolver;

	private final List<UriResolver> uriResolvers;

	/**
	 * Creates a registry with the given resolvers.
	 * 
	 * @param relativePathResolver the resolver saying what a path means as seen from the document
	 *                             carrying it, must not be <code>null</code>
	 * @param uriResolvers the resolvers of references naming a scheme, in the order in which they
	 *                     were registered, must not be <code>null</code>
	 * @throws IllegalArgumentException if the resolver of a path is <code>null</code> or if the
	 *                                  list of URI resolvers is <code>null</code> or contains
	 *                                  <code>null</code>
	 */
	public ResourceResolverRegistry(RelativePathResourceResolver relativePathResolver,
			List<UriResolver> uriResolvers) {

		if (relativePathResolver == null) {
			throw new IllegalArgumentException("Argument must not be null.");
		}
		if (uriResolvers == null || uriResolvers.stream().anyMatch(resolver -> resolver == null)) {
			throw new IllegalArgumentException("Argument must be a list without null elements.");
		}

		this.relativePathResolver = relativePathResolver;

		List<UriResolver> lastRegisteredFirst = new ArrayList<>(uriResolvers);
		Collections.reverse(lastRegisteredFirst);
		this.uriResolvers = List.copyOf(lastRegisteredFirst);
	}

	/**
	 * Creates the registry used when nothing else is asked for: references are resolved in the file
	 * system of the machine this code runs on, whether they are written as a path or with the
	 * <code>file</code> scheme.
	 * 
	 * @return the newly created registry, never <code>null</code>
	 */
	public static ResourceResolverRegistry ofLocalFileSystem() {
		return ofLocalFileSystem(new LocalFileSystemResourceResolver());
	}

	/**
	 * Creates a registry resolving references with the given resolver of the local file system,
	 * whether they are written as a path or with the <code>file</code> scheme.
	 * 
	 * @param relativePathResolver the resolver of the local file system, must not be
	 *                             <code>null</code>
	 * @return the newly created registry, never <code>null</code>
	 * @throws IllegalArgumentException if the given resolver is <code>null</code>
	 */
	public static ResourceResolverRegistry ofLocalFileSystem(RelativePathResourceResolver relativePathResolver) {
		if (relativePathResolver == null) {
			throw new IllegalArgumentException("Argument must not be null.");
		}
		return new ResourceResolverRegistry(relativePathResolver,
				List.of(new FileSchemeUriResolver(relativePathResolver)));
	}

	/**
	 * Returns the resolver saying what a path means as seen from the document carrying it.
	 * 
	 * @return the resolver, never <code>null</code>
	 */
	public RelativePathResourceResolver relativePathResolver() {
		return this.relativePathResolver;
	}

	/**
	 * Returns the resolver answering for the given reference.
	 * 
	 * <p>A path naming its resource on its own reaches no resolver: it leads to the resource the
	 * author meant on one machine and nowhere else, so it is reported rather than looked for.</p>
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

		if (isRelativePath(targetResourcePathOrUri)) {
			return Optional.of(this.relativePathResolver);
		}

		if (isAbsolutePathWithoutScheme(targetResourcePathOrUri)) {
			return Optional.empty();
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
			LOG.debug("The reference '{}' is no valid URI, so no resolver is asked about it.",
					targetResourcePathOrUri, exception);
			return Optional.empty();
		}

		return this.uriResolvers.stream()
				.filter(resolver -> resolver.isResponsibleFor(targetUri))
				.findFirst();
	}

	/**
	 * Tells whether the given reference is a path that is meant as seen from the document carrying
	 * it, i.e. a path naming no scheme and starting with no separator.
	 * 
	 * @param targetResourcePathOrUri the reference as it is written in the document, must not be
	 *                                <code>null</code>
	 * @return <code>true</code> if and only if the reference is such a path
	 * @throws IllegalArgumentException if the given reference is <code>null</code>
	 */
	public static boolean isRelativePath(String targetResourcePathOrUri) {
		return isPathWithoutScheme(targetResourcePathOrUri)
				&& !isAbsolutePathWithoutScheme(targetResourcePathOrUri);
	}

	/**
	 * Tells whether the given reference is a path naming its resource on its own, i.e. a path
	 * starting with a separator, e.g. <code>/usr/share/doc/guide.md</code> or
	 * <code>\\server\share\guide.md</code>, or a path led by a drive letter, e.g.
	 * <code>C:\documents\guide.md</code>.
	 * 
	 * @param targetResourcePathOrUri the reference as it is written in the document, must not be
	 *                                <code>null</code>
	 * @return <code>true</code> if and only if the reference is such a path
	 * @throws IllegalArgumentException if the given reference is <code>null</code>
	 */
	public static boolean isAbsolutePathWithoutScheme(String targetResourcePathOrUri) {
		if (targetResourcePathOrUri == null) {
			throw new IllegalArgumentException("Argument must not be null.");
		}

		String scheme = LinkTarget.of(targetResourcePathOrUri).scheme();
		if (scheme != null) {
			// a scheme of a single letter is the drive letter of an absolute path
			return scheme.length() == 1;
		}

		return targetResourcePathOrUri.startsWith("/") || targetResourcePathOrUri.startsWith("\\");
	}

	/**
	 * Tells whether the given reference is a path rather than a URI, i.e. whether it names no
	 * scheme or a drive letter.
	 * 
	 * @param targetResourcePathOrUri the reference as it is written in the document, must not be
	 *                                <code>null</code>
	 * @return <code>true</code> if and only if the reference is a path
	 * @throws IllegalArgumentException if the given reference is <code>null</code>
	 */
	public static boolean isPathWithoutScheme(String targetResourcePathOrUri) {
		if (targetResourcePathOrUri == null) {
			throw new IllegalArgumentException("Argument must not be null.");
		}

		String scheme = LinkTarget.of(targetResourcePathOrUri).scheme();
		return scheme == null || scheme.length() == 1;
	}

}