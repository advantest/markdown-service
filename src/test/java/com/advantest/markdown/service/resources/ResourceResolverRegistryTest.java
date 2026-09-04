/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.advantest.resources.AbsoluteLocalPathResolver;
import com.advantest.resources.LocalFileSystemResourceResolver;
import com.advantest.resources.RelativePathResourceResolver;
import com.advantest.resources.Resource;
import com.advantest.resources.ResourceResolver;
import com.advantest.resources.UnresolvedResource;
import com.advantest.resources.UriResolver;

/**
 * Checks which resolver is asked for which reference: the one saying what a path means as seen
 * from the document carrying it, the one of a path naming its resource on its own, and the one
 * claiming a scheme for the rest.
 */
class ResourceResolverRegistryTest {

	/**
	 * A resolver answering for whatever it was told to answer for, so that a test can see which one
	 * was picked.
	 */
	private static final class RecordingUriResolver implements UriResolver {

		private final String claimedPrefix;

		private RecordingUriResolver(String claimedPrefix) {
			this.claimedPrefix = claimedPrefix;
		}

		@Override
		public boolean isResponsibleFor(URI targetUri) {
			return targetUri.toString().startsWith(this.claimedPrefix);
		}

		@Override
		public Resource resolve(URI targetUri, Resource referencingDocument) {
			return new UnresolvedResource(targetUri.toString());
		}

	}

	private final RelativePathResourceResolver localResolver = new LocalFileSystemResourceResolver();

	@ParameterizedTest
	@ValueSource(strings = {
			"guide.md",
			"../images/logo.png",
			"path/with/slash/",
			"guide.md#a-section",
			"a file with blanks.md"
	})
	void readsAReferenceMeantAsSeenFromTheDocument(String reference) {
		assertTrue(ResourceResolverRegistry.isRelativePath(reference), reference);
		assertFalse(ResourceResolverRegistry.isAbsolutePathWithoutScheme(reference), reference);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"/absolute/path/guide.md",
			"C:/docs/guide.md",
			"c:\\docs\\guide.md",
			"\\\\server\\share\\guide.md",
			"/docs/a file with blanks.md"
	})
	void readsAReferenceNamingItsResourceOnItsOwn(String reference) {
		assertTrue(ResourceResolverRegistry.isAbsolutePathWithoutScheme(reference), reference);
		assertFalse(ResourceResolverRegistry.isRelativePath(reference), reference);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"https://example.org/guide",
			"http://example.org/guide?test=true&value=4",
			"mailto:someone@example.org",
			"file:///home/user/guide.md",
			"ftp://example.org/guide.md"
	})
	void readsAReferenceNamingAScheme(String reference) {
		assertFalse(ResourceResolverRegistry.isPathWithoutScheme(reference), reference);
		assertFalse(ResourceResolverRegistry.isRelativePath(reference), reference);
		assertFalse(ResourceResolverRegistry.isAbsolutePathWithoutScheme(reference), reference);
	}

	@Test
	void asksTheResolverOfAPathForAReferenceWithoutAScheme() {
		ResourceResolverRegistry registry = ResourceResolverRegistry.ofLocalFileSystem(this.localResolver);

		assertSame(this.localResolver, registry.resolverFor("../images/logo.png").orElseThrow());
	}

	@Test
	void asksTheResolverOfAnAbsolutePathForAPathNamingItsResourceOnItsOwn() {
		ResourceResolverRegistry registry = ResourceResolverRegistry.ofLocalFileSystem(this.localResolver);

		ResourceResolver resolver = registry.resolverFor("/absolute/path/guide.md").orElseThrow();

		assertTrue(resolver instanceof AbsoluteLocalPathResolver);
	}

	@Test
	void asksTheResolverOfTheFileSchemeForAFileNamedWithIt() {
		ResourceResolverRegistry registry = ResourceResolverRegistry.ofLocalFileSystem(this.localResolver);

		ResourceResolver resolver = registry.resolverFor("file:///home/user/guide.md").orElseThrow();

		assertTrue(resolver instanceof UriResolver);
	}

	@Test
	void asksNobodyForAReferenceNoResolverClaims() {
		ResourceResolverRegistry registry = ResourceResolverRegistry.ofLocalFileSystem(this.localResolver);

		assertEquals(Optional.empty(), registry.resolverFor("https://example.org/guide"));
	}

	@Test
	void asksNobodyForAnAbsolutePathWhereSuchAPathMeansNothing() {
		ResourceResolverRegistry registry = new ResourceResolverRegistry(this.localResolver, null, List.of());

		assertEquals(Optional.empty(), registry.resolverFor("/absolute/path/guide.md"));
		assertEquals(Optional.empty(), registry.absolutePathResolver());
		assertSame(this.localResolver, registry.relativePathResolver());
	}

	@Test
	void asksTheResolverRegisteredLastOfThoseClaimingTheSameReference() {
		UriResolver general = new RecordingUriResolver("https://");
		UriResolver specific = new RecordingUriResolver("https://tickets.example.org/");
		ResourceResolverRegistry registry =
				new ResourceResolverRegistry(this.localResolver, null, List.of(general, specific));

		assertSame(specific, registry.resolverFor("https://tickets.example.org/ABC-1").orElseThrow());
		assertSame(general, registry.resolverFor("https://example.org/guide").orElseThrow());
	}

	@Test
	void asksNobodyForAReferenceTheUriSyntaxRejects() {
		UriResolver everything = new RecordingUriResolver("");
		ResourceResolverRegistry registry =
				new ResourceResolverRegistry(this.localResolver, null, List.of(everything));

		assertEquals(Optional.empty(), registry.resolverFor("https://example.org/a guide|.md"));
	}

	@Test
	void rejectsMissingArguments() {
		ResourceResolverRegistry registry = ResourceResolverRegistry.ofLocalFileSystem();

		assertThrows(IllegalArgumentException.class, () -> registry.resolverFor(null));
		assertThrows(IllegalArgumentException.class, () -> registry.resolverFor("  "));
		assertThrows(IllegalArgumentException.class, () -> registry.uriResolverFor(null));
		assertThrows(IllegalArgumentException.class, () -> registry.uriResolverFor("  "));
		assertThrows(IllegalArgumentException.class,
				() -> ResourceResolverRegistry.isRelativePath(null));
		assertThrows(IllegalArgumentException.class,
				() -> ResourceResolverRegistry.isAbsolutePathWithoutScheme(null));
		assertThrows(IllegalArgumentException.class,
				() -> ResourceResolverRegistry.isPathWithoutScheme(null));
		assertThrows(IllegalArgumentException.class, () -> ResourceResolverRegistry.ofLocalFileSystem(null));
		assertThrows(IllegalArgumentException.class,
				() -> new ResourceResolverRegistry(this.localResolver, null, null));
		assertThrows(IllegalArgumentException.class,
				() -> new ResourceResolverRegistry(null, null, List.of()));
	}

}
