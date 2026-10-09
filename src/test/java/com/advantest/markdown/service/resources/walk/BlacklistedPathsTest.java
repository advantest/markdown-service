/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.resources.walk;

import static com.advantest.markdown.service.resources.walk.TestTrees.create;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Checks {@link BlacklistedPaths}: the format of a blacklist, and how {@link ResourceFilterContext}
 * reads one once per run.
 */
public class BlacklistedPathsTest {

	@TempDir
	Path root;

	private Path at(String path) {
		return this.root.resolve(path).toAbsolutePath().normalize();
	}

	@Test
	void entriesAreFilePathsRelativeToTheBase() {
		BlacklistedPaths listed = BlacklistedPaths.of(List.of(
				"doc/a.md",
				"  doc/b.md  ",
				"/doc/c.md",
				"//doc/d.md",
				"doc\\e.md",
				"\\doc\\f.md",
				"doc/../g.md",
				"./h.md"), this.root, "test");

		for (String file : List.of("doc/a.md", "doc/b.md", "doc/c.md", "doc/d.md", "doc/e.md", "doc/f.md", "g.md",
				"h.md")) {
			assertTrue(listed.contains(at(file)), file + " is expected to be listed.");
		}
		assertFalse(listed.contains(at("doc")), "A folder above an entry is expected to not be listed.");
		assertFalse(listed.contains(at("a.md")), "A file named like an entry elsewhere is expected to not be listed.");
	}

	@Test
	void commentsAndBlankLinesAreDropped() {
		BlacklistedPaths listed = BlacklistedPaths.of(List.of("# a.md", "   # b.md", "", "   ", "c.md # d.md"),
				this.root, "test");

		assertFalse(listed.contains(at("a.md")), "A comment is expected to list nothing.");
		assertFalse(listed.contains(at("b.md")), "An indented comment is expected to list nothing.");
		assertTrue(listed.contains(at("c.md # d.md")), "A # after the start is expected to belong to the path.");
	}

	@Test
	void anEntryNamingAFolderIsDropped() throws IOException {
		create(this.root, "doc/a.md");

		BlacklistedPaths listed = BlacklistedPaths.of(List.of("doc", "doc/a.md"), this.root, "test");

		assertFalse(listed.contains(at("doc")), "A folder is expected to be dropped from the list.");
		assertTrue(listed.contains(at("doc/a.md")), "The other entries are expected to be kept.");
	}

	@Test
	void entriesNamingNothingThatExistsAreReported() throws IOException {
		create(this.root, "doc/a.md");

		BlacklistedPaths listed = BlacklistedPaths.of(List.of("doc/a.md", "doc\\gone.md", " /old/b.md "), this.root,
				"test");

		assertEquals(List.of("doc/gone.md", "old/b.md"), listed.entriesNamingNothing(),
				"Every entry naming nothing that exists is expected to be reported, as written once cleaned up.");
		assertTrue(listed.contains(at("doc/a.md")), "An entry naming an existing file is expected to be listed.");
	}

	@Test
	void aListWhoseEntriesAllExistReportsNothing() throws IOException {
		create(this.root, "doc/a.md");

		BlacklistedPaths listed = BlacklistedPaths.of(List.of("# comment", "doc", "doc/a.md"), this.root, "test");

		assertEquals(List.of(), listed.entriesNamingNothing(),
				"Neither a comment nor a folder nor an existing file is expected to be reported as naming nothing.");
	}

	@Test
	void anEntryThatIsNoPathIsDropped() {
		BlacklistedPaths listed = BlacklistedPaths.of(List.of("a\0b.md", "c.md"), this.root, "test");

		assertTrue(listed.contains(at("c.md")), "The other entries are expected to be kept.");
	}

	@Test
	void aBlacklistIsReadFromItsFile() throws IOException {
		Path list = this.root.resolve("list.txt");
		Files.writeString(list, "# comment\r\na.md\r\nsub/b.md\n", StandardCharsets.UTF_8);

		BlacklistedPaths listed = BlacklistedPaths.read(list, this.root);

		assertTrue(listed.contains(at("a.md")), "Lines ending with CR LF are expected to be read.");
		assertTrue(listed.contains(at("sub/b.md")), "Lines ending with LF are expected to be read.");
	}

	@Test
	void aBlacklistThatCannotBeReadListsNothing() {
		BlacklistedPaths listed = BlacklistedPaths.read(this.root.resolve("missing.txt"), this.root);

		assertFalse(listed.contains(at("missing.txt")), "A missing list is expected to list nothing.");
	}

	@Test
	void aContextReadsABlacklistOnce() throws IOException {
		Path list = this.root.resolve("list.txt");
		Files.writeString(list, "a.md\n", StandardCharsets.UTF_8);
		ResourceFilterContext context = new ResourceFilterContext();

		BlacklistedPaths first = context.blacklistedPaths(list, this.root);
		BlacklistedPaths again = context.blacklistedPaths(this.root.resolve("sub/../list.txt"), this.root.resolve("."));
		BlacklistedPaths otherBase = context.blacklistedPaths(list, this.root.resolve("sub"));

		assertSame(first, again, "The same list with the same base is expected to be read once.");
		assertNotSame(first, otherBase, "The same list with another base is expected to be read for that base.");
		assertTrue(otherBase.contains(at("sub/a.md")), "The entries are expected to be relative to the base.");
		assertNotSame(first, new ResourceFilterContext().blacklistedPaths(list, this.root),
				"Another context is expected to read the list again.");
	}

	@Test
	void aContextNeedsAListAndABase() {
		ResourceFilterContext context = new ResourceFilterContext();
		assertThrows(IllegalArgumentException.class, () -> context.blacklistedPaths(null, this.root),
				"A missing list is expected to be refused.");
		assertThrows(IllegalArgumentException.class, () -> context.blacklistedPaths(this.root.resolve("l"), null),
				"A missing base is expected to be refused.");
	}

}
