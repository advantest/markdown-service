/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.resources.walk;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Creates folder trees for the tests and walks them. */
final class TestTrees {

	private TestTrees() {
	}

	/**
	 * Creates the given files and folders below the given root; a path ending with
	 * <code>/</code> is a folder, every other one a file with a heading as its contents.
	 */
	static void create(Path root, String... paths) throws IOException {
		for (String path : paths) {
			Path created = root.resolve(path);
			if (path.endsWith("/")) {
				Files.createDirectories(created);
			} else {
				Files.createDirectories(created.getParent());
				Files.writeString(created, "# " + created.getFileName() + "\n", StandardCharsets.UTF_8);
			}
		}
	}

	/** Creates a symbolic link, or skips the test where the file system or the user cannot. */
	static void link(Path link, Path target) {
		try {
			Files.createSymbolicLink(link, target);
		} catch (IOException | UnsupportedOperationException e) {
			assumeTrue(false, "Symbolic links cannot be created here: " + e);
		}
	}

	/** Walks the tree with a fresh walker handing out Markdown files, answering the paths found. */
	static List<String> walk(Path root, ResourceFilter filter) throws IOException {
		return walk(new ResourceTreeWalker(name -> name.endsWith(".md")), root, filter, List.of());
	}

	/** Walks the tree with the given walker, answering the paths found relative to the root. */
	static List<String> walk(ResourceTreeWalker walker, Path root, ResourceFilter filter, Collection<Path> otherRoots)
			throws IOException {
		List<String> found = new ArrayList<>();
		walker.walk(root, filter, otherRoots, () -> false, file -> found.add(relative(root, file)));
		return found;
	}

	/** Answers the given path relative to the root, with <code>/</code> as separator. */
	static String relative(Path root, Path path) {
		return root.toAbsolutePath().normalize().relativize(path).toString().replace('\\', '/');
	}

}
