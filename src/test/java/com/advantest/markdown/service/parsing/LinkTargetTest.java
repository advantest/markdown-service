/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.parsing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests the split of a link target into scheme, path and fragment.
 */
public class LinkTargetTest {

	@Test
	public void readsAPlainFilePathAsAPath() {
		LinkTarget target = LinkTarget.of("documents/overview.md");

		assertNull(target.scheme(), "A file path has no scheme.");
		assertEquals("documents/overview.md", target.path());
		assertNull(target.fragment(), "A target without a hash tag has no fragment.");
		assertTrue(target.isLocalResourcePath());
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			https://example.com/page.html    | https
			http://example.com               | http
			ftp://example.com/archive.zip    | ftp
			mailto:someone@example.com       | mailto
			file:///C:/documents/overview.md | file
			file:/documents/overview.md      | file
			urn:isbn:0451450523              | urn
			""")
	public void readsTheSchemeOfATargetThatHasOne(String linkTarget, String expectedScheme) {
		LinkTarget target = LinkTarget.of(linkTarget);

		assertEquals(expectedScheme, target.scheme());
		assertFalse(target.isLocalResourcePath(),
				"Whoever owns the scheme resolves the target, not the resource resolver.");
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"overview.md",
			"documents/overview.md",
			"./overview.md",
			"../overview.md",
			"../../documents/overview.md",
			"path/with/slash/",
			"./../../some/path/to/../../other/dir/file.txt",
			"a%20document%20with%20blanks.md",
			"/absolute/path/overview.md",
			"images/diagram.png" })
	public void readsATargetWithoutASchemeAsALocalResourcePath(String linkTarget) {
		LinkTarget target = LinkTarget.of(linkTarget);

		assertNull(target.scheme());
		assertTrue(target.isLocalResourcePath());
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			overview.md#the-section                          | overview.md                          | the-section
			documents/overview.md#a-heading-with-2-digits    | documents/overview.md                | a-heading-with-2-digits
			../src/com/example/project/SomeClass.java#isCool | ../src/com/example/project/SomeClass.java | isCool
			""")
	public void separatesTheFragmentFromThePath(String linkTarget, String expectedPath, String expectedFragment) {
		LinkTarget target = LinkTarget.of(linkTarget);

		assertEquals(expectedPath, target.path());
		assertEquals(expectedFragment, target.fragment(), "The fragment is read without its hash tag.");
		assertTrue(target.isLocalResourcePath(),
				"A fragment behind a path does not stop the path from naming a resource.");
	}

	@Test
	public void readsAFragmentNamingAMethodWithItsParameters() {
		LinkTarget target = LinkTarget.of(
				"../../../simple-java-project/src/com/example/project/x/SomeClass.java"
						+ "#doSomething(int,boolean,Character[],List<Map<K,V>>)");

		assertEquals("../../../simple-java-project/src/com/example/project/x/SomeClass.java", target.path());
		assertEquals("doSomething(int,boolean,Character[],List<Map<K,V>>)", target.fragment(),
				"A fragment may name a member of the target, and the URI syntax rejects such a target,"
						+ " so it is split by hand.");
		assertTrue(target.isLocalResourcePath());
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"#the-section",
			"#a-heading-with-blanks in it",
			"#doSomething(int,boolean)" })
	public void readsATargetOfNothingButAFragment(String linkTarget) {
		LinkTarget target = LinkTarget.of(linkTarget);

		assertEquals(linkTarget.substring(1), target.fragment());
		assertFalse(target.isLocalResourcePath(),
				"A target pointing into the current document names no resource to look for.");
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			https://example.com/search?test=true&value=4         | /search
			https://example.com/search?test=true&value=4#results | /search
			https://example.com:8443/a/b/c?query=a+b             | /a/b/c
			https://example.com/browse/PROJECT-42?filter=open    | /browse/PROJECT-42
			""")
	public void readsThePathOfAWebAddressWithoutItsQuery(String linkTarget, String expectedPath) {
		LinkTarget target = LinkTarget.of(linkTarget);

		assertEquals("https", target.scheme());
		assertEquals(expectedPath, target.path(), "The query is not part of the path.");
		assertFalse(target.isLocalResourcePath());
	}

	@Test
	public void readsTheFragmentOfAWebAddressCarryingAQuery() {
		LinkTarget target = LinkTarget.of("https://example.com/search?test=true&value=4#results");

		assertEquals("results", target.fragment());
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			my documents/overview.md#the section  | my documents/overview.md | the section
			overview.md#a fragment {with braces}  | overview.md              | a fragment {with braces}
			""")
	public void splitsATargetTheUriSyntaxRejects(String linkTarget, String expectedPath, String expectedFragment) {
		LinkTarget target = LinkTarget.of(linkTarget);

		assertNull(target.scheme());
		assertEquals(expectedPath, target.path());
		assertEquals(expectedFragment, target.fragment());
	}

	@Test
	public void readsATargetWithBlanksAndWithoutAFragment() {
		LinkTarget target = LinkTarget.of("documents/a file.md");

		assertNull(target.scheme());
		assertEquals("documents/a file.md", target.path());
		assertNull(target.fragment());
		assertTrue(target.isLocalResourcePath());
	}

	@Test
	public void readsADriveLetterAsAScheme() {
		LinkTarget target = LinkTarget.of("C:\\documents\\overview.md");

		assertEquals("C", target.scheme(), "A drive letter cannot be told apart from a scheme, so an"
				+ " absolute path of a Windows file system names no resource to look for.");
		assertFalse(target.isLocalResourcePath());
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "   " })
	public void readsABlankTargetAsNamingNoResource(String linkTarget) {
		assertFalse(LinkTarget.of(linkTarget).isLocalResourcePath());
	}

	@Test
	public void rejectsNull() {
		assertThrows(IllegalArgumentException.class, () -> LinkTarget.of(null));
	}

}
