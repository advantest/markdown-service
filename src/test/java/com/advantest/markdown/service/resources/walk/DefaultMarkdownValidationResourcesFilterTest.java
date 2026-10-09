/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.resources.walk;

import static com.advantest.markdown.service.resources.walk.TestTrees.create;
import static com.advantest.markdown.service.resources.walk.TestTrees.walk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.advantest.markdown.service.resources.walk.DefaultMarkdownValidationResourcesFilter.Builder;

/**
 * Checks a {@link DefaultMarkdownValidationResourcesFilter}: every rule it offers, how rules are
 * combined, and how a filter is adapted and created for a run.
 */
public class DefaultMarkdownValidationResourcesFilterTest {

	@TempDir
	Path root;

	private static BasicFileAttributes attributesOf(Path path) throws IOException {
		return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
	}

	private static BasicFileAttributes link() {
		BasicFileAttributes attributes = mock(BasicFileAttributes.class);
		when(attributes.isSymbolicLink()).thenReturn(true);
		return attributes;
	}

	private List<String> walkWith(Builder builder) throws IOException {
		return walk(this.root, builder.build().createForRun(new ResourceFilterContext()));
	}

	private Path at(String path) {
		return this.root.resolve(path).toAbsolutePath().normalize();
	}

	@Test
	void theDefaultsSkipSymbolicLinksAndNothingElse() throws IOException {
		create(this.root, ".hidden/a.md", "b.txt.md", "c/d.md");
		DefaultMarkdownValidationResourcesFilter filter = DefaultMarkdownValidationResourcesFilter
				.builderWithDefaults().build();

		assertTrue(filter.skipsFolder(this.root, at("link"), link()), "A link to a folder is expected to be skipped.");
		assertTrue(filter.skipsFile(this.root, at("link.md"), link()), "A link to a file is expected to be skipped.");
		assertEquals(List.of("b.txt.md", ".hidden/a.md", "c/d.md"), walk(this.root, filter),
				"Nothing but links is expected to be skipped by default.");
	}

	@Test
	void anEmptyBuilderFollowsSymbolicLinksAndSkipsNothing() throws IOException {
		DefaultMarkdownValidationResourcesFilter filter = DefaultMarkdownValidationResourcesFilter.emptyBuilder()
				.build();

		assertFalse(filter.skipsFolder(this.root, at("link"), link()), "A link to a folder is expected to be followed.");
		assertFalse(filter.skipsFile(this.root, at("link.md"), link()), "A link to a file is expected to be followed.");
		assertSame(filter, filter.createForRun(new ResourceFilterContext()),
				"A filter with nothing to bind to a run is expected to be used as it is.");
	}

	@Test
	void dotPrefixedFoldersCanBeSkipped() throws IOException {
		create(this.root, ".settings/a.md", ".b.md", "c/.d/e.md", "f/g.md");

		assertEquals(List.of(".b.md", "f/g.md"),
				walkWith(DefaultMarkdownValidationResourcesFilter.emptyBuilder().skippingDotPrefixedFolders(true)),
				"Folders starting with a dot are expected to be skipped, files starting with a dot not.");
		assertEquals(4, walkWith(DefaultMarkdownValidationResourcesFilter.emptyBuilder()
				.skippingDotPrefixedFolders(true).skippingDotPrefixedFolders(false)).size(),
				"Switching the rule off again is expected to skip nothing.");
	}

	@Test
	void hiddenFilesAndFoldersCanBeSkipped() throws IOException {
		create(this.root, "hiddenFolder/a.md", "hidden.md", "visible.md", ".dot/b.md", ".dot.md");
		if (Files.getFileAttributeView(this.root, DosFileAttributeView.class) != null) {
			for (String hidden : List.of("hiddenFolder", "hidden.md", ".dot", ".dot.md")) {
				Files.setAttribute(this.root.resolve(hidden), "dos:hidden", true);
			}
		}
		// a file system may store the DOS attribute without taking it for hiddenness, as on Linux
		boolean dos = Files.isHidden(this.root.resolve("hidden.md"));

		List<String> found = walkWith(DefaultMarkdownValidationResourcesFilter.emptyBuilder().skippingHidden(true));

		assertEquals(dos ? List.of("visible.md") : List.of("hidden.md", "visible.md", "hiddenFolder/a.md"), found,
				"What the file system marks hidden is expected to be skipped.");
	}

	@Test
	void aPathWhoseHiddennessCannotBeReadIsTakenAsNotHidden() {
		DefaultMarkdownValidationResourcesFilter filter = DefaultMarkdownValidationResourcesFilter.emptyBuilder()
				.skippingHidden(true).build();

		assertFalse(filter.skipsFile(this.root, at("missing.md"), null),
				"A file that cannot be read is expected to be taken as not hidden.");
	}

	@Test
	void filesCanBeKeptByTheirExtension() throws IOException {
		create(this.root, "a.md", "b.MD", "c.markdown", "d.txt", "noExtension", ".md");

		ResourceTreeWalker walkingAll = new ResourceTreeWalker(name -> true);
		DefaultMarkdownValidationResourcesFilter filter = DefaultMarkdownValidationResourcesFilter.emptyBuilder()
				.onlyFileExtensions("md", ".Markdown").build();

		assertEquals(List.of("a.md", "b.MD", "c.markdown"), TestTrees.walk(walkingAll, this.root, filter, List.of()),
				"Only files with one of the extensions are expected to be kept, compared case-insensitively;"
						+ " a name starting with its only dot is expected to have no extension.");
	}

	@Test
	void filesCanBeSkippedByTheirExtension() throws IOException {
		create(this.root, "a.md", "b.class", "C.CLASS", "d.bak", "noExtension");

		ResourceTreeWalker walkingAll = new ResourceTreeWalker(name -> true);
		DefaultMarkdownValidationResourcesFilter filter = DefaultMarkdownValidationResourcesFilter.emptyBuilder()
				.skippingFileExtensions(" class ").skippingFileExtensions("bak").build();

		assertEquals(List.of("a.md", "noExtension"), TestTrees.walk(walkingAll, this.root, filter, List.of()),
				"Files with a skipped extension are expected to be skipped.");
	}

	@Test
	void twoExtensionRulesApplyBoth() throws IOException {
		create(this.root, "a.md", "b.puml", "c.txt");

		ResourceTreeWalker walkingAll = new ResourceTreeWalker(name -> true);
		DefaultMarkdownValidationResourcesFilter filter = DefaultMarkdownValidationResourcesFilter.emptyBuilder()
				.onlyFileExtensions("md", "puml").skippingFileExtensions("puml").build();

		assertEquals(List.of("a.md"), TestTrees.walk(walkingAll, this.root, filter, List.of()),
				"A file is expected to be kept only if every rule keeps it.");
	}

	@Test
	void extensionsMustBeGiven() {
		Builder builder = DefaultMarkdownValidationResourcesFilter.emptyBuilder();
		assertThrows(IllegalArgumentException.class, () -> builder.onlyFileExtensions(),
				"No extension is expected to be refused.");
		assertThrows(IllegalArgumentException.class, () -> builder.onlyFileExtensions((String[]) null),
				"No extension is expected to be refused.");
		assertThrows(IllegalArgumentException.class, () -> builder.skippingFileExtensions("md", null),
				"A null extension is expected to be refused.");
		assertThrows(IllegalArgumentException.class, () -> builder.skippingFileExtensions(" . "),
				"An empty extension is expected to be refused.");
	}

	@Test
	void pathsCanBeKeptByPatternsRelativeToTheRoot() throws IOException {
		create(this.root, "README.md", "doc/a.md", "doc/sub/b.md", "doc_api/c.md", "docs/d.md", "src/doc/e.md");

		assertEquals(List.of("doc/a.md", "doc/sub/b.md", "doc_api/c.md"),
				walkWith(DefaultMarkdownValidationResourcesFilter.emptyBuilder().onlyPaths("/{doc,doc_*}/")),
				"Only what lies in a matching folder is expected to be kept.");
		assertEquals(List.of("doc/a.md", "doc/sub/b.md", "doc_api/c.md", "src/doc/e.md"),
				walkWith(DefaultMarkdownValidationResourcesFilter.emptyBuilder().onlyPaths("{doc,doc_*}/")),
				"An unanchored folder pattern is expected to keep matching folders at any depth.");
		assertEquals(List.of("doc/a.md", "src/doc/e.md"),
				walkWith(DefaultMarkdownValidationResourcesFilter.emptyBuilder().onlyPaths("doc/*.md", "**/doc/e.md")),
				"A file is expected to be kept if it matches one of the patterns.");
	}

	@Test
	void twoPathRulesApplyBoth() throws IOException {
		create(this.root, "doc/a.md", "doc/b.md", "src/c.md");

		assertEquals(List.of("doc/a.md"), walkWith(DefaultMarkdownValidationResourcesFilter.emptyBuilder()
				.onlyPaths("doc/").onlyPaths("a.md")), "A file is expected to be kept only if both rules keep it.");
	}

	@Test
	void pathsCanBeSkippedByPatternsRelativeToTheRoot() throws IOException {
		create(this.root, "target/a.md", "sub/target/b.md", "sub/c.md", "d.md", "doc/e.md");

		assertEquals(List.of("d.md", "doc/e.md", "sub/c.md", "sub/target/b.md"),
				walkWith(DefaultMarkdownValidationResourcesFilter.emptyBuilder().skippingPaths("/target/")),
				"An anchored pattern is expected to skip at the root only.");
		assertEquals(List.of("d.md", "doc/e.md", "sub/c.md"),
				walkWith(DefaultMarkdownValidationResourcesFilter.emptyBuilder().skippingPaths("target/")),
				"An unanchored pattern is expected to skip at any depth.");
		assertEquals(List.of("doc/e.md", "sub/target/b.md"),
				walkWith(DefaultMarkdownValidationResourcesFilter.emptyBuilder().skippingPaths("/target", "c.md",
						"/d.md")),
				"A path is expected to be skipped if it matches one of the patterns.");
	}

	@Test
	void pathsCanBeKeptRelativeToABase() throws IOException {
		create(this.root, "project/doc/a.md", "project/src/b.md", "other/doc/c.md", "d.md");
		Path project = this.root.resolve("project");

		assertEquals(List.of("project/doc/a.md"), walkWith(DefaultMarkdownValidationResourcesFilter.emptyBuilder()
				.onlyPathsBelow(project, "doc/")),
				"Only what matches below the base is expected to be kept, and the folders above the base"
						+ " are expected to be entered.");
	}

	@Test
	void aWalkBelowTheBaseOfARuleKeepingPathsMatchesRelativeToTheBase() throws IOException {
		create(this.root, "project/doc/a.md", "project/src/b.md");
		ResourceFilter filter = DefaultMarkdownValidationResourcesFilter.emptyBuilder()
				.onlyPathsBelow(this.root, "project/doc/").build();
		Path project = this.root.resolve("project");

		assertEquals(List.of("doc/a.md"), walk(project, filter),
				"A walk below the base is expected to match relative to the base.");
	}

	@Test
	void pathsCanBeSkippedRelativeToABase() throws IOException {
		create(this.root, "project/target/a.md", "project/b.md", "target/c.md");
		Path project = this.root.resolve("project");

		assertEquals(List.of("project/b.md", "target/c.md"), walkWith(DefaultMarkdownValidationResourcesFilter
				.emptyBuilder().skippingPathsBelow(project, "/target/")),
				"Only what matches below the base is expected to be skipped.");
	}

	@Test
	void aWalkStartingInsideASkippedFolderOfTheBaseSkipsEverything() throws IOException {
		create(this.root, "project/target/sub/a.md", "project/b.md");
		ResourceFilter filter = DefaultMarkdownValidationResourcesFilter.emptyBuilder()
				.skippingPathsBelow(this.root, "/project/target/").build();

		assertEquals(List.of(), walk(this.root.resolve("project/target"), filter),
				"A file whose folder between base and root is skipped is expected to be skipped.");
		assertEquals(List.of("b.md"), walk(this.root.resolve("project"), filter),
				"A walk above the skipped folder is expected to skip that folder only.");
	}

	@Test
	void pathPatternsMustBeGivenAndWellFormed() {
		Builder builder = DefaultMarkdownValidationResourcesFilter.emptyBuilder();
		assertThrows(IllegalArgumentException.class, () -> builder.onlyPaths(), "No pattern is expected to be refused.");
		assertThrows(IllegalArgumentException.class, () -> builder.skippingPaths((String[]) null),
				"No pattern is expected to be refused.");
		assertThrows(IllegalArgumentException.class, () -> builder.skippingPaths("a//b"),
				"A malformed pattern is expected to be refused.");
		assertThrows(IllegalArgumentException.class, () -> builder.onlyPathsBelow(null, "doc/"),
				"A missing base is expected to be refused.");
		assertThrows(IllegalArgumentException.class, () -> builder.skippingPathsBelow(null, "doc/"),
				"A missing base is expected to be refused.");
	}

	@Test
	void blacklistedFilesAreSkipped() throws IOException {
		create(this.root, "doc/a.md", "doc/b.md", "c.md");
		Path list = this.root.resolve("blacklist.txt");
		Files.writeString(list, "# skipped\n/doc/a.md\n", StandardCharsets.UTF_8);

		assertEquals(List.of("c.md", "doc/b.md"), walkWith(DefaultMarkdownValidationResourcesFilter.emptyBuilder()
				.skippingBlacklistedPaths(list, this.root)), "The files listed are expected to be skipped.");
		assertEquals(List.of("doc/a.md", "doc/b.md"), walkWith(DefaultMarkdownValidationResourcesFilter.emptyBuilder()
				.skippingBlacklistedPaths(List.of("c.md"), this.root)),
				"The files listed in lines are expected to be skipped.");
	}

	@Test
	void aBlacklistIsReadOncePerRunAndAgainForTheNextRun() throws IOException {
		create(this.root, "a.md", "b.md");
		Path list = this.root.resolve("blacklist.txt");
		Files.writeString(list, "a.md\n", StandardCharsets.UTF_8);
		DefaultMarkdownValidationResourcesFilter filter = DefaultMarkdownValidationResourcesFilter.emptyBuilder()
				.skippingBlacklistedPaths(list, this.root).build();

		ResourceFilterContext run = new ResourceFilterContext();
		ResourceFilter first = filter.createForRun(run);
		Files.writeString(list, "b.md\n", StandardCharsets.UTF_8);
		ResourceFilter sameRun = filter.createForRun(run);
		ResourceFilter nextRun = filter.createForRun(new ResourceFilterContext());

		assertNotSame(filter, first, "A filter with a blacklist is expected to be bound to the run.");
		assertTrue(first.skipsFile(this.root, at("a.md"), null), "The first run is expected to skip what it read.");
		assertTrue(sameRun.skipsFile(this.root, at("a.md"), null),
				"The same run is expected to not read the list again.");
		assertTrue(nextRun.skipsFile(this.root, at("b.md"), null), "The next run is expected to read the list again.");
		assertFalse(nextRun.skipsFile(this.root, at("a.md"), null),
				"The next run is expected to not skip what is no longer listed.");
	}

	@Test
	void aBlacklistMustBeBoundToARunBeforeItIsUsed() {
		DefaultMarkdownValidationResourcesFilter filter = DefaultMarkdownValidationResourcesFilter.emptyBuilder()
				.skippingBlacklistedPaths(List.of("a.md"), this.root).build();

		assertThrows(IllegalStateException.class, () -> filter.skipsFile(this.root, at("a.md"), null),
				"A blacklist not bound to a run is expected to refuse to answer.");
		assertFalse(filter.skipsFolder(this.root, at("a"), null), "A blacklist is expected to skip no folder.");
	}

	@Test
	void aBlacklistMustBeGiven() {
		Builder builder = DefaultMarkdownValidationResourcesFilter.emptyBuilder();
		assertThrows(IllegalArgumentException.class, () -> builder.skippingBlacklistedPaths((Path) null, this.root),
				"A missing list file is expected to be refused.");
		assertThrows(IllegalArgumentException.class,
				() -> builder.skippingBlacklistedPaths(this.root.resolve("list.txt"), null),
				"A missing base is expected to be refused.");
		assertThrows(IllegalArgumentException.class,
				() -> builder.skippingBlacklistedPaths((List<String>) null, this.root),
				"Missing lines are expected to be refused.");
		assertThrows(IllegalArgumentException.class,
				() -> builder.skippingBlacklistedPaths(Arrays.asList("a.md", null), this.root),
				"A null line is expected to be refused.");
		assertThrows(IllegalArgumentException.class, () -> builder.skippingBlacklistedPaths(List.of("a.md"), null),
				"A missing base is expected to be refused.");
	}

	@Test
	void whatGitIgnoresMustBeBoundToARunBeforeItIsUsed() {
		DefaultMarkdownValidationResourcesFilter filter = DefaultMarkdownValidationResourcesFilter.emptyBuilder()
				.skippingWhatGitIgnores(true).build();

		assertThrows(IllegalStateException.class, () -> filter.skipsFile(this.root, at("a.md"), null),
				"A git rule not bound to a run is expected to refuse to answer.");
		assertThrows(IllegalStateException.class, () -> filter.skipsFolder(this.root, at("a"), null),
				"A git rule not bound to a run is expected to refuse to answer.");
	}

	@Test
	void whatGitIgnoresIsSkippedInAWalk() throws IOException, InterruptedException {
		create(this.root, "build/a.md", "doc/b.md", "doc/c.draft.md", "d.md");
		Files.writeString(this.root.resolve(".gitignore"), "build/\n*.draft.md\n", StandardCharsets.UTF_8);
		Process init = new ProcessBuilder("git", "init", "-q", this.root.toString()).redirectErrorStream(true).start();
		init.getInputStream().readAllBytes();
		assumeTrue(init.waitFor() == 0, "git is needed to tell what it ignores.");

		assertEquals(List.of("d.md", "doc/b.md"), walkWith(DefaultMarkdownValidationResourcesFilter.emptyBuilder()
				.skippingWhatGitIgnores(true)), "Ignored folders and files are expected to be skipped.");
	}

	@Test
	void gitFoldersAreSkippedWhenSkippingWhatGitIgnores() throws IOException {
		create(this.root, ".git/a.md", "b.md");
		ResourceFilter filter = DefaultMarkdownValidationResourcesFilter.emptyBuilder().skippingWhatGitIgnores(true)
				.build().createForRun(new ResourceFilterContext("git-that-does-not-exist-" + System.nanoTime()));

		assertEquals(List.of("b.md"), walk(this.root, filter),
				"A .git folder is expected to be skipped even where git cannot be asked.");
	}

	@Test
	void ownFiltersAreAskedLastAndCreatedForEveryRun() throws IOException {
		ResourceFilter own = mock(ResourceFilter.class);
		ResourceFilter ownForRun = mock(ResourceFilter.class);
		when(own.createForRun(any())).thenReturn(ownForRun);
		when(ownForRun.skipsFile(any(), any(), any())).thenReturn(true);
		DefaultMarkdownValidationResourcesFilter filter = DefaultMarkdownValidationResourcesFilter.emptyBuilder()
				.skippingFileExtensions("txt").skipping(own).build();
		ResourceFilterContext context = new ResourceFilterContext();

		ResourceFilter forRun = filter.createForRun(context);

		verify(own).createForRun(context);
		assertTrue(forRun.skipsFile(this.root, at("a.md"), null), "The own filter is expected to be asked.");
		assertFalse(forRun.skipsFolder(this.root, at("a"), null), "The own filter is expected to be asked.");
		assertTrue(forRun.skipsFile(this.root, at("a.txt"), null), "The library's rules are expected to skip.");
		verify(ownForRun, never()).skipsFile(this.root, at("a.txt"), null);
		assertThrows(IllegalArgumentException.class,
				() -> DefaultMarkdownValidationResourcesFilter.emptyBuilder().skipping(null),
				"A missing filter is expected to be refused.");
	}

	@Test
	void aFilterIsAdaptedThroughABuilderWithoutBeingChanged() throws IOException {
		create(this.root, "target/a.md", "bin/b.md", "c.md");
		DefaultMarkdownValidationResourcesFilter base = DefaultMarkdownValidationResourcesFilter.builderWithDefaults()
				.skippingPaths("/target/").build();

		DefaultMarkdownValidationResourcesFilter adapted = base.toBuilder().skippingPaths("/bin/").build();

		assertEquals(List.of("c.md"), walk(this.root, adapted), "The adapted filter is expected to have both rules.");
		assertEquals(List.of("c.md", "bin/b.md"), walk(this.root, base),
				"The filter adapted is expected to stay as it was.");
		assertTrue(adapted.skipsFolder(this.root, at("link"), link()),
				"The adapted filter is expected to keep the settings.");
	}

	@Test
	void aBuilderBuildsFiltersIndependentOfItsLaterChanges() throws IOException {
		create(this.root, "a.md", "b.md");
		Builder builder = DefaultMarkdownValidationResourcesFilter.emptyBuilder().skippingPaths("a.md");
		DefaultMarkdownValidationResourcesFilter first = builder.build();
		builder.skippingPaths("b.md").skippingDotPrefixedFolders(true).skippingHidden(true);

		assertEquals(List.of("b.md"), walk(this.root, first), "A filter built is expected to not change with the builder.");
		assertEquals(List.of(), walk(this.root, builder.build()),
				"A filter built later is expected to have the later rules.");
		assertEquals(List.of("b.md"), walk(this.root, first.toBuilder().build()),
				"The builder of a filter is expected to hold its settings as they were when it was built.");
	}

	@Test
	void theLibrarysRulesAreBoundOnceAndKeptWhereNothingIsToBeBound() {
		ResourceFilter own = mock(ResourceFilter.class);
		when(own.createForRun(any())).thenReturn(own);
		DefaultMarkdownValidationResourcesFilter filter = DefaultMarkdownValidationResourcesFilter.builderWithDefaults()
				.skippingDotPrefixedFolders(true).skippingHidden(true).onlyFileExtensions("md").onlyPaths("doc/")
				.skipping(own).build();

		assertSame(filter, filter.createForRun(new ResourceFilterContext()),
				"A filter whose rules need nothing of the run is expected to be used as it is.");
	}

	@Test
	void everyRuleIsAskedUntilOneSkips() throws IOException {
		List<String> asked = new ArrayList<>();
		ResourceFilter first = recording("first", asked, false);
		ResourceFilter second = recording("second", asked, true);
		ResourceFilter third = recording("third", asked, true);
		DefaultMarkdownValidationResourcesFilter filter = DefaultMarkdownValidationResourcesFilter.emptyBuilder()
				.skipping(first).skipping(second).skipping(third).build();

		assertTrue(filter.skipsFolder(this.root, at("a"), null), "The second rule is expected to skip the folder.");
		assertEquals(List.of("first", "second"), asked, "The rules are expected to be asked in order until one skips.");
	}

	private static ResourceFilter recording(String name, List<String> asked, boolean skips) {
		return new ResourceFilter() {
			@Override
			public boolean skipsFolder(Path walkRoot, Path folder, BasicFileAttributes attributes) {
				asked.add(name);
				return skips;
			}
		};
	}

}
