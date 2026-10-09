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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Checks {@link GitIgnoredPaths} against real git repositories, and what happens where git cannot
 * be asked.
 */
public class GitIgnoredPathsTest {

	private static final String GIT = "git";

	@TempDir
	Path root;

	@BeforeAll
	static void gitIsInstalled() {
		boolean installed;
		try {
			installed = new ProcessBuilder(GIT, "--version").redirectErrorStream(true)
					.redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor() == 0;
		} catch (IOException e) {
			installed = false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			installed = false;
		}
		assumeTrue(installed, "These tests need git installed.");
	}

	private static void git(Path folder, String... arguments) throws IOException {
		List<String> command = new ArrayList<>();
		command.add(GIT);
		command.add("-C");
		command.add(folder.toString());
		command.addAll(List.of(arguments));
		try {
			int exitCode = new ProcessBuilder(command).redirectErrorStream(true)
					.redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor();
			assertEquals(0, exitCode, "git " + String.join(" ", arguments) + " is expected to succeed.");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException(e);
		}
	}

	private static void ignore(Path repository, String... patterns) throws IOException {
		Files.writeString(repository.resolve(".gitignore"), String.join("\n", patterns) + "\n", StandardCharsets.UTF_8);
	}

	private Path repository(String... paths) throws IOException {
		Path repository = this.root.resolve("repo").toAbsolutePath().normalize();
		Files.createDirectories(repository);
		git(repository, "init", "-q");
		create(repository, paths);
		return repository;
	}

	private static boolean ignored(GitIgnoredPaths paths, Path repository, String path, boolean folder) {
		return paths.isIgnored(repository.resolve(path).normalize(), folder);
	}

	@Test
	void whatGitIgnoresIsIgnored() throws IOException {
		Path repository = repository("kept.md", "ignored.md", "sub/ignored.md", "build/a.md", "build/deep/b.md",
				"sub/build/c.md", "with space ä.md");
		ignore(repository, "ignored.md", "/build/", "*space*");
		GitIgnoredPaths paths = new GitIgnoredPaths(GIT);

		assertFalse(ignored(paths, repository, "kept.md", false), "An untracked file not ignored is expected to be kept.");
		assertTrue(ignored(paths, repository, "ignored.md", false), "An ignored file is expected to be ignored.");
		assertTrue(ignored(paths, repository, "sub/ignored.md", false),
				"An ignored file deeper down is expected to be ignored.");
		assertTrue(ignored(paths, repository, "build", true), "An ignored folder is expected to be ignored.");
		assertTrue(ignored(paths, repository, "build/a.md", false),
				"A file in an ignored folder is expected to be ignored.");
		assertTrue(ignored(paths, repository, "build/deep", true),
				"A folder in an ignored folder is expected to be ignored.");
		assertTrue(ignored(paths, repository, "build/deep/b.md", false),
				"A file deep in an ignored folder is expected to be ignored.");
		assertFalse(ignored(paths, repository, "sub/build/c.md", false),
				"A file in a folder matching an anchored pattern elsewhere is expected to be kept.");
		assertFalse(ignored(paths, repository, "sub", true), "A folder not ignored is expected to be kept.");
		assertTrue(ignored(paths, repository, "with space ä.md", false),
				"A file with spaces and umlauts in its name is expected to be ignored.");
		assertFalse(ignored(paths, repository, ".gitignore", false), "The ignore file itself is expected to be kept.");
	}

	@Test
	void aRepositoryWithoutIgnoredFilesIgnoresNothing() throws IOException {
		Path repository = repository("a.md", "sub/b.md");
		GitIgnoredPaths paths = new GitIgnoredPaths(GIT);

		assertFalse(ignored(paths, repository, "a.md", false), "Nothing is expected to be ignored.");
		assertFalse(ignored(paths, repository, "sub/b.md", false), "Nothing is expected to be ignored.");
		assertFalse(ignored(paths, repository, "sub", true), "Nothing is expected to be ignored.");
	}

	@Test
	void aTrackedFileIsNotIgnoredEvenIfAPatternMatchesIt() throws IOException {
		Path repository = repository("tracked.md");
		ignore(repository, "*.md");
		git(repository, "add", "-f", "tracked.md");
		create(repository, "untracked.md");
		GitIgnoredPaths paths = new GitIgnoredPaths(GIT);

		assertFalse(ignored(paths, repository, "tracked.md", false), "A tracked file is expected to be kept.");
		assertTrue(ignored(paths, repository, "untracked.md", false), "An untracked file is expected to be ignored.");
	}

	@Test
	void gitIsAskedOncePerRepository() throws IOException {
		Path repository = repository("a.md", "b.md");
		ignore(repository, "a.md");
		GitIgnoredPaths paths = new GitIgnoredPaths(GIT);

		assertTrue(ignored(paths, repository, "a.md", false), "a.md is expected to be ignored.");
		ignore(repository, "b.md");

		assertTrue(ignored(paths, repository, "a.md", false), "The answer of git is expected to be kept.");
		assertFalse(ignored(paths, repository, "b.md", false), "git is expected to not be asked again.");
		GitIgnoredPaths nextRun = new GitIgnoredPaths(GIT);
		assertTrue(ignored(nextRun, repository, "b.md", false), "A new instance is expected to ask git again.");
	}

	@Test
	void aNestedRepositoryIsAskedOnItsOwn() throws IOException {
		Path outer = repository("a.md");
		ignore(outer, "a.md");
		Path inner = outer.resolve("inner");
		Files.createDirectories(inner);
		git(inner, "init", "-q");
		create(inner, "a.md", "b.md");
		ignore(inner, "b.md");
		GitIgnoredPaths paths = new GitIgnoredPaths(GIT);

		assertTrue(ignored(paths, outer, "a.md", false), "The outer repository's rules are expected to apply there.");
		assertFalse(ignored(paths, inner, "a.md", false),
				"The outer repository's rules are expected to not apply in the inner one.");
		assertTrue(ignored(paths, inner, "b.md", false), "The inner repository's rules are expected to apply there.");
		assertFalse(ignored(paths, outer, "inner", true), "The inner repository is expected to be kept.");
	}

	@Test
	void aPathOutsideEveryRepositoryIsNotIgnored() throws IOException {
		create(this.root, "a.md", "sub/b.md");
		GitIgnoredPaths paths = new GitIgnoredPaths(GIT);
		Path absoluteRoot = this.root.toAbsolutePath().normalize();
		assumeFalseInsideARepository(absoluteRoot);

		assertFalse(paths.isIgnored(absoluteRoot.resolve("a.md"), false), "Nothing is expected to be ignored.");
		assertFalse(paths.isIgnored(absoluteRoot.resolve("sub").resolve("b.md"), false),
				"Nothing is expected to be ignored, also when the folders above are known already.");
		assertFalse(paths.isIgnored(absoluteRoot.getRoot(), true), "A path without parent is expected to be kept.");
	}

	private static void assumeFalseInsideARepository(Path folder) {
		for (Path current = folder; current != null; current = current.getParent()) {
			assumeTrue(!Files.exists(current.resolve(".git")), "Needs a temporary folder outside every repository.");
		}
	}

	@Test
	void whereGitFailsNothingIsIgnored() throws IOException {
		create(this.root, "a.md");
		Files.writeString(this.root.resolve(".git"), "no repository\n", StandardCharsets.UTF_8);
		Files.writeString(this.root.resolve(".gitignore"), "a.md\n", StandardCharsets.UTF_8);
		GitIgnoredPaths paths = new GitIgnoredPaths(GIT);

		assertFalse(paths.isIgnored(this.root.resolve("a.md").toAbsolutePath().normalize(), false),
				"Nothing is expected to be ignored where git fails.");
	}

	@Test
	void whereGitIsMissingNothingIsIgnored() throws IOException {
		Path repository = repository("a.md");
		ignore(repository, "a.md");
		Path other = this.root.resolve("other").toAbsolutePath().normalize();
		Files.createDirectories(other);
		git(other, "init", "-q");
		create(other, "b.md");
		ignore(other, "b.md");
		GitIgnoredPaths paths = new GitIgnoredPaths("git-that-does-not-exist-" + System.nanoTime());

		assertFalse(ignored(paths, repository, "a.md", false), "Nothing is expected to be ignored without git.");
		assertFalse(ignored(paths, other, "b.md", false),
				"Nothing is expected to be ignored in another repository either, without trying git again.");
	}

}
