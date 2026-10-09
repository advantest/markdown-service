/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.resources.walk;

import static com.advantest.markdown.service.resources.walk.TestTrees.create;
import static com.advantest.markdown.service.resources.walk.TestTrees.link;
import static com.advantest.markdown.service.resources.walk.TestTrees.walk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Checks a {@link ResourceTreeWalker}: the order it walks in, that it never runs in circles nor
 * hands a document out twice, and how it treats what it cannot read.
 */
public class ResourceTreeWalkerTest {

	private static final ResourceFilter NOTHING_SKIPPED = new ResourceFilter() {
	};

	@TempDir
	Path root;

	private final ResourceTreeWalker walker = new ResourceTreeWalker(name -> name.endsWith(".md"));

	@Test
	void aWalkerNeedsToKnowWhatADocumentIs() {
		assertThrows(IllegalArgumentException.class, () -> new ResourceTreeWalker(null),
				"A walker without a test for document names is expected to be refused.");
	}

	@Test
	void theTreeIsWalkedDepthFirstInTheOrderOfTheNames() throws IOException {
		create(this.root, "b.md", "a/z.md", "a/b/c.md", "a/a.md", "c/", "d.txt", "a/x.MD");

		assertEquals(List.of("b.md", "a/a.md", "a/z.md", "a/b/c.md"), walk(this.root, NOTHING_SKIPPED),
				"Every Markdown file is expected to be found, the files of a folder before its subfolders"
						+ " and both in the order of their names.");
	}

	@Test
	void anEmptyTreeHasNoDocuments() throws IOException {
		assertEquals(List.of(), walk(this.root, NOTHING_SKIPPED), "An empty tree is expected to have no document.");
	}

	@Test
	void aRootThatIsNoFolderIsRefused() throws IOException {
		create(this.root, "a.md");
		assertThrows(NotDirectoryException.class, () -> walk(this.root.resolve("a.md"), NOTHING_SKIPPED),
				"A file given as root is expected to be refused.");
		assertThrows(NoSuchFileException.class, () -> walk(this.root.resolve("missing"), NOTHING_SKIPPED),
				"A root that does not exist is expected to be refused.");
	}

	@Test
	void theFilterIsAskedAboutEveryFolderAndDocumentButNotTheRootNorOtherFiles() throws IOException {
		create(this.root, "a/b.md", "a/c.txt", "d.md");
		List<String> asked = new ArrayList<>();
		ResourceFilter filter = new ResourceFilter() {
			@Override
			public boolean skipsFolder(Path walkRoot, Path folder, BasicFileAttributes attributes) {
				asked.add("folder " + TestTrees.relative(walkRoot, folder));
				return false;
			}

			@Override
			public boolean skipsFile(Path walkRoot, Path file, BasicFileAttributes attributes) {
				asked.add("file " + TestTrees.relative(walkRoot, file));
				return false;
			}
		};

		walk(this.root, filter);

		assertEquals(List.of("folder a", "file d.md", "file a/b.md"), asked,
				"The filter is expected to be asked about the folders and the documents only.");
	}

	@Test
	void aSkippedFolderIsNotEnteredAndASkippedFileNotHandedOut() throws IOException {
		create(this.root, "skipped/a.md", "kept/b.md", "kept/skipped.md");
		ResourceFilter filter = new ResourceFilter() {
			@Override
			public boolean skipsFolder(Path walkRoot, Path folder, BasicFileAttributes attributes) {
				return folder.getFileName().toString().equals("skipped");
			}

			@Override
			public boolean skipsFile(Path walkRoot, Path file, BasicFileAttributes attributes) {
				return file.getFileName().toString().equals("skipped.md");
			}
		};

		assertEquals(List.of("kept/b.md"), walk(this.root, filter),
				"Nothing skipped and nothing below a skipped folder is expected to be handed out.");
	}

	@Test
	void aWalkerHandsNoDocumentOutTwice() throws IOException {
		create(this.root, "a/b.md", "c.md");

		assertEquals(List.of("c.md", "a/b.md"), walk(this.walker, this.root, NOTHING_SKIPPED, List.of()),
				"The first walk is expected to find everything.");
		assertEquals(List.of(), walk(this.walker, this.root, NOTHING_SKIPPED, List.of()),
				"A second walk of the same root is expected to find nothing new.");
		assertEquals(List.of(), walk(this.walker, this.root.resolve("a"), NOTHING_SKIPPED, List.of()),
				"A walk of a folder walked before is expected to find nothing new.");
	}

	@Test
	void aWalkStartingBelowAnEarlierRootHandsOutWhatTheEarlierLeftOver() throws IOException {
		create(this.root, "a/b.md", "a/c/d.md", "e.md");

		assertEquals(List.of("b.md", "c/d.md"), walk(this.walker, this.root.resolve("a"), NOTHING_SKIPPED, List.of()),
				"The inner walk is expected to find the files below its root.");
		assertEquals(List.of("e.md"), walk(this.walker, this.root, NOTHING_SKIPPED, List.of()),
				"The outer walk is expected to leave out the documents handed out before.");
	}

	@Test
	void aLaterWalkEntersTheFoldersAgainWithItsOwnFilter() throws IOException {
		create(this.root, "doc/a.md", "src/b.md");
		ResourceFilter skippingDoc = new ResourceFilter() {
			@Override
			public boolean skipsFolder(Path walkRoot, Path folder, BasicFileAttributes attributes) {
				return folder.getFileName().toString().equals("doc");
			}
		};

		assertEquals(List.of("src/b.md"), walk(this.walker, this.root, skippingDoc, List.of()),
				"The first walk is expected to skip the folder its filter skips.");
		assertEquals(List.of("doc/a.md"), walk(this.walker, this.root, NOTHING_SKIPPED, List.of()),
				"A later walk with another filter is expected to enter the folder skipped before.");
	}

	@Test
	void aWalkLeavesTheRootsOfOtherWalksToThem() throws IOException {
		create(this.root, "a/b.md", "e.md");
		Path inner = this.root.resolve("a").toAbsolutePath().normalize();

		assertEquals(List.of("e.md"), walk(this.walker, this.root, NOTHING_SKIPPED, List.of(inner)),
				"The outer walk is expected to leave the inner root to its own walk.");
		assertEquals(List.of("b.md"), walk(this.walker, inner, NOTHING_SKIPPED, List.of(this.root)),
				"The inner walk is expected to find what lies below its root.");
	}

	@Test
	void aWalkStopsWhenCancelled() throws IOException {
		create(this.root, "a.md", "b/c.md", "d/e.md");
		AtomicInteger asked = new AtomicInteger();
		List<Path> found = new ArrayList<>();

		this.walker.walk(this.root, NOTHING_SKIPPED, List.of(), () -> asked.incrementAndGet() > 1, found::add);

		assertEquals(1, found.size(), "Only the root's own documents are expected to be found before cancelling.");
	}

	@Test
	void aWalkCancelledBeforeItStartsFindsNothing() throws IOException {
		create(this.root, "a.md");
		List<Path> found = new ArrayList<>();

		this.walker.walk(this.root, NOTHING_SKIPPED, List.of(), () -> true, found::add);

		assertEquals(List.of(), found, "A cancelled walk is expected to find nothing.");
	}

	@Test
	void aFolderThatCannotBeListedIsLeftOut() throws IOException {
		create(this.root, "locked/a.md", "open/b.md");
		Path locked = this.root.resolve("locked");
		assumeTrue(Files.getFileStore(locked).supportsFileAttributeView("posix"), "Needs POSIX permissions.");
		Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
		try {
			assumeTrue(!Files.isReadable(locked), "Needs a user the permissions apply to.");
			assertEquals(List.of("open/b.md"), walk(this.root, NOTHING_SKIPPED),
					"A folder that cannot be listed is expected to be left out, and the walk to go on.");
		} finally {
			Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
		}
	}

	@Test
	void aFolderGoneBeforeItIsListedIsLeftOut() throws IOException {
		create(this.root, "a/gone/b.md", "c/d.md");
		Path gone = this.root.resolve("a").resolve("gone");
		ResourceFilter deleting = new ResourceFilter() {
			@Override
			public boolean skipsFolder(Path walkRoot, Path folder, BasicFileAttributes attributes) {
				if (folder.equals(gone.toAbsolutePath().normalize())) {
					try {
						Files.delete(folder.resolve("b.md"));
						Files.delete(folder);
					} catch (IOException e) {
						throw new IllegalStateException(e);
					}
				}
				return false;
			}
		};

		assertEquals(List.of("c/d.md"), walk(this.root, deleting),
				"A folder gone before it is listed is expected to be left out, and the walk to go on.");
	}

	@Test
	void aSymbolicLinkLeadingBackUpTheTreeDoesNotMakeTheWalkRunInCircles() throws IOException {
		create(this.root, "a/b.md");
		link(this.root.resolve("a").resolve("up"), this.root);

		assertEquals(List.of("a/b.md"), walk(this.root, NOTHING_SKIPPED),
				"A link to the root is expected to be entered never, since the root was entered already.");
	}

	@Test
	void twoLinksToTheSameTargetHandItOutOnce() throws IOException {
		create(this.root, "target/a.md", "z.md");
		link(this.root.resolve("zlink"), this.root.resolve("target"));
		link(this.root.resolve("zz.md"), this.root.resolve("z.md"));

		assertEquals(List.of("z.md", "target/a.md"), walk(this.root, NOTHING_SKIPPED),
				"A document reachable on several ways is expected to be handed out once, on the first way found.");
	}

	@Test
	void aLinkIsShownToTheFilterAsALink() throws IOException {
		create(this.root, "target/a.md", "z.md");
		link(this.root.resolve("folderLink"), this.root.resolve("target"));
		link(this.root.resolve("fileLink.md"), this.root.resolve("z.md"));
		ResourceFilter skippingLinks = DefaultMarkdownValidationResourcesFilter.builderWithDefaults().build();

		ResourceTreeWalker fresh = new ResourceTreeWalker(name -> name.endsWith(".md"));
		assertEquals(List.of("z.md", "target/a.md"), walk(fresh, this.root, skippingLinks, List.of()),
				"Links are expected to be skipped by a filter skipping links.");
	}

	@Test
	void aBrokenLinkIsLeftOut() throws IOException {
		create(this.root, "a.md");
		link(this.root.resolve("broken.md"), this.root.resolve("missing.md"));

		assertEquals(List.of("a.md"), walk(this.root, NOTHING_SKIPPED),
				"A link pointing nowhere is expected to be left out.");
	}

	@Test
	void aRootGivenAsLinkIsFollowed() throws IOException {
		create(this.root, "target/a.md");
		Path rootLink = this.root.resolve("rootLink");
		link(rootLink, this.root.resolve("target"));

		assertEquals(List.of("a.md"), walk(rootLink, DefaultMarkdownValidationResourcesFilter.builderWithDefaults()
				.build()), "A link given as root is expected to be followed.");
	}

	@Test
	void aFileFoundIsValidatedIfAWalkWouldHandItOut() throws IOException {
		create(this.root, "skipped/a.md", "kept/b.md", "kept/c.txt", "d/");
		ResourceFilter filter = DefaultMarkdownValidationResourcesFilter.emptyBuilder().skippingPaths("skipped/")
				.build();

		assertTrue(this.walker.isValidated(this.root.resolve("kept/b.md"), this.root, filter),
				"A document a walk would hand out is expected to be validated.");
		assertFalse(this.walker.isValidated(this.root.resolve("skipped/a.md"), this.root, filter),
				"A document in a skipped folder is expected to not be validated.");
		assertFalse(this.walker.isValidated(this.root.resolve("kept/c.txt"), this.root, filter),
				"A file that is no document is expected to not be validated.");
		assertFalse(this.walker.isValidated(this.root.resolve("kept/missing.md"), this.root, filter),
				"A file that does not exist is expected to not be validated.");
		assertFalse(this.walker.isValidated(this.root.resolve("missing/x.md"), this.root, filter),
				"A file in a folder that does not exist is expected to not be validated.");
		assertFalse(this.walker.isValidated(this.root.resolveSibling("outside.md"), this.root, filter),
				"A file outside the root is expected to not be validated.");
		assertFalse(this.walker.isValidated(this.root, this.root, filter),
				"The root itself is expected to not be validated.");
	}

	@Test
	void askingAboutAFileRemembersNothing() throws IOException {
		create(this.root, "a/b.md");
		assertTrue(this.walker.isValidated(this.root.resolve("a/b.md"), this.root, NOTHING_SKIPPED),
				"The document is expected to be validated.");

		assertEquals(List.of("a/b.md"), walk(this.walker, this.root, NOTHING_SKIPPED, List.of()),
				"A walk after asking is expected to still find the document.");
	}

	@Test
	void aFileWhoseFolderOnTheWayIsAFileIsNotValidated() throws IOException {
		create(this.root, "a.md");
		assertFalse(this.walker.isValidated(this.root.resolve("a.md").resolve("b.md"), this.root, NOTHING_SKIPPED),
				"A path below a file is expected to not be validated.");
	}

	@Test
	void aFolderNamedLikeADocumentIsNotValidated() throws IOException {
		create(this.root, "folder.md/");
		assertFalse(this.walker.isValidated(this.root.resolve("folder.md"), this.root, NOTHING_SKIPPED),
				"A folder is expected to not be validated, whatever its name.");
		assertEquals(List.of(), walk(this.root, NOTHING_SKIPPED), "A folder is expected to never be handed out.");
	}

}
