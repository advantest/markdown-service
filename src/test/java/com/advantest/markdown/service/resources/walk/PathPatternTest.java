/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.resources.walk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/**
 * Checks a {@link PathPattern}: its syntax, what it matches, and when a walk has to look below a
 * folder for a match.
 */
public class PathPatternTest {

	private static List<String> segments(String path) {
		return path.isEmpty() ? List.of() : Arrays.asList(path.split("/"));
	}

	private static boolean matchesFile(String glob, String path) {
		return PathPattern.compile(glob).matches(segments(path), false);
	}

	private static boolean matchesFolder(String glob, String path) {
		return PathPattern.compile(glob).matches(segments(path), true);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "/", "//", "a//b", "\\", "a\\", "[ab", "[!", "[]", "{a,b", "{a,{b}}" })
	void aMalformedPatternIsRefused(String glob) {
		assertThrows(IllegalArgumentException.class, () -> PathPattern.compile(glob),
				"The pattern '" + glob + "' is expected to be refused.");
	}

	@ParameterizedTest
	@CsvSource({
			"*.md, a.md, true",
			"*.md, doc/a.md, true",
			"*.md, doc/sub/a.md, true",
			"*.md, a.txt, false",
			"*.md, a.md.txt, false",
			"README.md, README.md, true",
			"README.md, doc/README.md, true",
			"README.md, readme.md, false",
			"/README.md, README.md, true",
			"/README.md, doc/README.md, false",
			"doc/a.md, doc/a.md, true",
			"doc/a.md, x/doc/a.md, false",
			"doc/*.md, doc/a.md, true",
			"doc/*.md, doc/sub/a.md, false",
			"doc/**/a.md, doc/a.md, true",
			"doc/**/a.md, doc/x/y/a.md, true",
			"doc/**, doc/a.md, true",
			"doc/**, doc/x/a.md, true",
			"**/doc/a.md, x/y/doc/a.md, true",
			"**/doc/a.md, doc/a.md, true",
			"**/**/a.md, x/a.md, true",
			"**, a.md, true",
			"a?.md, ab.md, true",
			"a?.md, a.md, false",
			"a?.md, abc.md, false",
			"[ab].md, a.md, true",
			"[ab].md, c.md, false",
			"[a-c].md, b.md, true",
			"[a-c].md, d.md, false",
			"[!a].md, b.md, true",
			"[!a].md, a.md, false",
			"[]].md, ].md, true",
			"[a-].md, -.md, true",
			"[\\^&].md, ^.md, true",
			"'{doc,doc_*}/a.md', doc/a.md, true",
			"'{doc,doc_*}/a.md', doc_api/a.md, true",
			"'{doc,doc_*}/a.md', docs/a.md, false",
			"a{b}c.md, abc.md, true",
			"a\\*.md, a*.md, true",
			"a\\*.md, ab.md, false",
			"a.b+c(d)$.md, a.b+c(d)$.md, true",
			"a.b+c(d)$.md, aXb+c(d)$.md, false",
			"a}b, a}b, true",
			"'a,b', 'a,b', true",
			"*(1).md, x(1).md, true",
	})
	void aPatternMatchesFilesAsExpected(String glob, String path, boolean expected) {
		assertEquals(expected, matchesFile(glob, path),
				"The pattern '" + glob + "' is expected to " + (expected ? "" : "not ") + "match " + path);
	}

	@Test
	void aTrailingSlashMatchesFoldersOnly() {
		assertTrue(matchesFolder("target/", "target"), "A folder pattern is expected to match a folder.");
		assertTrue(matchesFolder("target/", "x/target"), "An unanchored folder pattern is expected to match deeper.");
		assertFalse(matchesFile("target/", "target"), "A folder pattern is expected to not match a file.");
		assertTrue(matchesFolder("/target/", "target"), "An anchored folder pattern is expected to match at the base.");
		assertFalse(matchesFolder("/target/", "x/target"),
				"An anchored folder pattern is expected to match at the base only.");
	}

	@Test
	void aTrailingDoubleStarMatchesWhatIsInsideButNotTheFolderItself() {
		assertFalse(matchesFolder("doc/**", "doc"), "doc/** is expected to not match doc itself.");
		assertTrue(matchesFolder("doc/**", "doc/sub"), "doc/** is expected to match a folder inside doc.");
		assertFalse(matchesFolder("doc/**", ""), "doc/** is expected to not match the base.");
	}

	@Test
	void aPatternMatchesItselfOrAFolderAbove() {
		PathPattern doc = PathPattern.compile("doc");
		assertTrue(doc.matchesItselfOrAFolderAbove(segments("doc/sub/a.md"), false),
				"A file below a matching folder is expected to be matched.");
		assertTrue(doc.matchesItselfOrAFolderAbove(segments("doc"), true), "The folder itself is expected to match.");
		assertFalse(doc.matchesItselfOrAFolderAbove(segments("src/a.md"), false),
				"A file below no matching folder is expected to not be matched.");
		assertFalse(doc.matchesItselfOrAFolderAbove(List.of(), true), "The base is expected to match nothing.");

		PathPattern folders = PathPattern.compile("doc/");
		assertTrue(folders.matchesItselfOrAFolderAbove(segments("doc/a.md"), false),
				"A folder above the file is expected to be taken as a folder.");
		assertFalse(folders.matchesItselfOrAFolderAbove(segments("doc"), false),
				"A file named like the folder is expected to not be matched by a folder pattern.");
	}

	@ParameterizedTest
	@CsvSource({
			"'{doc,doc_*}/**', '', true",
			"'{doc,doc_*}/**', doc, true",
			"'{doc,doc_*}/**', doc_x, true",
			"'{doc,doc_*}/**', doc/sub, true",
			"'{doc,doc_*}/**', src, false",
			"/doc/a.md, doc, true",
			"/doc/a.md, doc/a.md, false",
			"/doc/a.md, src, false",
			"*.md, src, true",
			"*.md, src/deep, true",
			"**/api/*.md, x/y, true",
			"x/**/api/*.md, y, false",
			"x/**/api/*.md, x/y/z, true",
	})
	void aPatternTellsWhetherSomethingBelowAFolderMayMatch(String glob, String folder, boolean expected) {
		assertEquals(expected, PathPattern.compile(glob).mayMatchBelow(segments(folder)),
				"The pattern '" + glob + "' is expected to " + (expected ? "" : "not ")
						+ "possibly match below '" + folder + "'.");
	}

	@Test
	void segmentsBelowTheBaseAreAnsweredRelativeToIt() {
		Path base = Path.of("base").toAbsolutePath();
		assertEquals(List.of("a", "b.md"), PathPattern.segmentsBelow(base, base.resolve("a").resolve("b.md")),
				"The segments are expected to be relative to the base.");
		assertEquals(List.of(), PathPattern.segmentsBelow(base, base), "The base itself is expected to have none.");
		assertNull(PathPattern.segmentsBelow(base, base.resolveSibling("other")),
				"A path outside the base is expected to have no segments.");
	}

	@Test
	void thePatternIsShownAsItWasGiven() {
		assertEquals("doc/**/*.md", PathPattern.compile("doc/**/*.md").toString(),
				"The pattern is expected to be shown as given.");
	}

}
