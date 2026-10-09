/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.advantest.markdown.MarkdownParserAndHtmlRenderer;
import com.advantest.markdown.service.resources.walk.DefaultMarkdownValidationResourcesFilter;
import com.advantest.markdown.service.resources.walk.ResourceFilter;
import com.advantest.resources.LocalFileSystemResource;
import com.advantest.resources.Resource;
import com.advantest.resources.ResourceContentsReader;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;

/**
 * Checks how a {@link MarkdownValidationRun} validates a named resource and walks folder trees:
 * which files it checks, how it treats what it cannot read, and how walks are cancelled and
 * awaited.
 */
public class MarkdownValidationRunWalkTest {

	/**
	 * A validator triggered by the document, remembering the source code it checked, and answering
	 * at once or with a promise the test keeps.
	 */
	private static class DocumentValidator implements MarkdownValidator {

		private final List<String> checked = new CopyOnWriteArrayList<>();

		private final List<CompletableFuture<List<ValidationIssue>>> promises = new CopyOnWriteArrayList<>();

		private final boolean answeringAtOnce;

		DocumentValidator(boolean answeringAtOnce) {
			this.answeringAtOnce = answeringAtOnce;
		}

		@Override
		public Set<Class<? extends Node>> getTriggeringNodeTypes() {
			return Set.of(Document.class);
		}


		@Override
		public CompletableFuture<List<ValidationIssue>> validate(Node node, MarkdownValidationContext context) {
			this.checked.add(node.getChars().toString().strip());
			CompletableFuture<List<ValidationIssue>> promise = this.answeringAtOnce
					? CompletableFuture.completedFuture(List.of())
					: new CompletableFuture<>();
			this.promises.add(promise);
			return promise;
		}
	}

	@TempDir
	Path root;

	private final MarkdownParserAndHtmlRenderer parserAndRenderer = new MarkdownParserAndHtmlRenderer();

	private final DocumentValidator validator = new DocumentValidator(true);

	private final MarkdownValidationRules rules = new MarkdownValidationRules(this.parserAndRenderer,
			List.of(this.validator));

	private void create(String... paths) throws IOException {
		for (String path : paths) {
			Path file = this.root.resolve(path);
			Files.createDirectories(file.getParent());
			Files.writeString(file, "# " + path + "\n", StandardCharsets.UTF_8);
		}
	}

	private Resource resource(String path) {
		return LocalFileSystemResource.of(this.root.resolve(path).toAbsolutePath().normalize());
	}

	private List<Resource> resources(String... paths) {
		return Arrays.stream(paths).map(this::resource).toList();
	}

	private static <T> T join(CompletableFuture<T> promise) {
		return promise.orTimeout(30, TimeUnit.SECONDS).join();
	}

	/** A reader failing with the given failure for the given file and reading every other one. */
	private ResourceContentsReader failingFor(String path, Exception failure) {
		Resource failing = resource(path);
		return resource -> {
			if (resource.equals(failing)) {
				if (failure instanceof IOException ioFailure) {
					throw ioFailure;
				}
				throw (RuntimeException) failure;
			}
			return ResourceContentsReader.FROM_THE_RESOURCE.readAllContents(resource);
		};
	}

	private MarkdownValidationRun runWith(ResourceFilter filter) {
		return this.rules.createRun(null, ResourceContentsReader.FROM_THE_RESOURCE, filter);
	}

	@Test
	void aWalkChecksEveryMarkdownFileInTheOrderFound() throws IOException {
		create("b.md", "a/c.md", "a/d.md", "e.txt");

		Map<Resource, List<ValidationIssue>> findings;
		try (MarkdownValidationRun run = this.rules.createRun()) {
			findings = join(run.validateTree(this.root));
		}

		assertEquals(resources("b.md", "a/c.md", "a/d.md"), List.copyOf(findings.keySet()),
				"Every Markdown file is expected to be checked, in the order the walk found it.");
		assertEquals(List.of(), findings.get(resource("b.md")), "A file without problems is expected to have none.");
		assertThrows(UnsupportedOperationException.class, () -> findings.remove(resource("b.md")),
				"The findings are expected to not be modifiable.");
		assertEquals(3, this.validator.checked.size(), "Every file found is expected to be checked once.");
	}

	@Test
	void aWalkAppliesTheFilterOfTheRun() throws IOException {
		create("doc/a.md", "src/b.md");
		ResourceFilter filter = DefaultMarkdownValidationResourcesFilter.builderWithDefaults().onlyPaths("/doc/")
				.build();

		try (MarkdownValidationRun run = runWith(filter)) {
			assertEquals(Set.of(resource("doc/a.md")), join(run.validateTree(this.root)).keySet(),
					"The run's filter is expected to apply.");
		}
	}

	@Test
	void aWalkCanBeGivenAFilterOfItsOwn() throws IOException {
		create("doc/a.md", "src/b.md");
		ResourceFilter filter = DefaultMarkdownValidationResourcesFilter.builderWithDefaults().onlyPaths("/src/")
				.build();

		try (MarkdownValidationRun run = this.rules.createRun()) {
			assertEquals(Set.of(resource("src/b.md")), join(run.validateTree(this.root, filter)).keySet(),
					"The walk's own filter is expected to apply.");
			assertEquals(Set.of(resource("doc/a.md")), join(run.validateTree(this.root)).keySet(),
					"A later walk with the run's filter is expected to check what is left.");
		}
	}

	@Test
	void aFilterIsCreatedOnceForARun() throws IOException {
		create("a/b.md", "c/d.md");
		ResourceFilter filter = mock(ResourceFilter.class);
		when(filter.createForRun(any())).thenReturn(filter);

		try (MarkdownValidationRun run = runWith(filter)) {
			join(run.validateTree(this.root.resolve("a")));
			join(run.validateTree(this.root.resolve("c")));
			assertTrue(run.isValidated(this.root.resolve("a/b.md"), this.root));
		}

		verify(filter, times(1)).createForRun(any());
	}

	@Test
	void aFileIsCheckedOncePerRunHoweverOftenItIsWalked() throws IOException {
		create("a/b.md", "c.md");

		try (MarkdownValidationRun run = this.rules.createRun()) {
			assertEquals(resources("a/b.md"), List.copyOf(join(run.validateTree(this.root.resolve("a"))).keySet()),
					"The first walk is expected to check what it finds.");
			assertEquals(resources("c.md"), List.copyOf(join(run.validateTree(this.root)).keySet()),
					"A later walk is expected to leave out what was checked already.");
		}
	}

	@Test
	void severalTreesAreWalkedInOnePromiseEachFileOnce() throws IOException {
		create("a/b.md", "a/c/d.md", "e.md");

		Map<Resource, List<ValidationIssue>> findings;
		try (MarkdownValidationRun run = this.rules.createRun()) {
			findings = join(run.validateTrees(List.of(this.root, this.root.resolve("a/c"), this.root.resolve("."),
					this.root.resolve("a"))));
		}

		assertEquals(resources("e.md", "a/c/d.md", "a/b.md"), List.copyOf(findings.keySet()),
				"Every file is expected to be checked once, by the walk of the root nearest to it, the roots "
						+ "walked in the order given.");
		assertEquals(3, this.validator.checked.size(), "Every file is expected to be checked once.");
	}

	@Test
	void aRootNearerToAFileDecidesOnItWithTheFilter() throws IOException {
		create("a/doc/b.md", "doc/c.md");
		ResourceFilter filter = DefaultMarkdownValidationResourcesFilter.builderWithDefaults().onlyPaths("/doc/")
				.build();

		try (MarkdownValidationRun run = runWith(filter)) {
			assertEquals(Set.of(resource("doc/c.md"), resource("a/doc/b.md")),
					join(run.validateTrees(List.of(this.root, this.root.resolve("a")))).keySet(),
					"Each file is expected to be matched relative to the root nearest to it.");
		}
	}

	@Test
	void aFileThatCannotBeReadIsLeftOutOfTheWalksFindings() throws IOException {
		create("a.md", "b.md");

		try (MarkdownValidationRun run = this.rules.createRun(null, failingFor("a.md", new IOException("unreadable")),
				DefaultMarkdownValidationResourcesFilter.builderWithDefaults().build())) {
			assertEquals(Set.of(resource("b.md")), join(run.validateTree(this.root)).keySet(),
					"A file that cannot be read is expected to be left out, and the others to be checked.");
		}
	}

	@Test
	void aFileWhoseReadingFailsUnexpectedlyIsLeftOutOfTheWalksFindings() throws IOException {
		create("a.md", "b.md");

		try (MarkdownValidationRun run = this.rules.createRun(null, failingFor("a.md", new IllegalStateException("boom")),
				DefaultMarkdownValidationResourcesFilter.builderWithDefaults().build())) {
			assertEquals(Set.of(resource("b.md")), join(run.validateTree(this.root)).keySet(),
					"A file whose check fails is expected to be left out, and the others to be checked.");
		}
	}

	@Test
	void aWalkFailsIfTheFilterFails() throws IOException {
		create("a/b.md");
		ResourceFilter failing = new ResourceFilter() {
			@Override
			public boolean skipsFolder(Path walkRoot, Path folder, BasicFileAttributes attributes) {
				throw new IllegalStateException("broken filter");
			}
		};

		try (MarkdownValidationRun run = this.rules.createRun()) {
			CompletionException failure = assertThrows(CompletionException.class,
					() -> join(run.validateTree(this.root, failing)));
			assertInstanceOf(IllegalStateException.class, failure.getCause(),
					"The walk is expected to fail with what the filter threw.");
		}
	}

	@Test
	void aWalkMustStartInAnExistingFolder() throws IOException {
		create("a.md");

		try (MarkdownValidationRun run = this.rules.createRun()) {
			assertThrows(IllegalArgumentException.class, () -> run.validateTree(this.root.resolve("a.md")),
					"A file is expected to be refused as root.");
			assertThrows(IllegalArgumentException.class, () -> run.validateTree(this.root.resolve("missing")),
					"A missing folder is expected to be refused as root.");
			assertThrows(IllegalArgumentException.class,
					() -> run.validateTrees(List.of(this.root, this.root.resolve("a.md"))),
					"A file is expected to be refused as one of the roots.");
			assertThrows(IllegalArgumentException.class, () -> run.validateTree(null));
			assertThrows(IllegalArgumentException.class, () -> run.validateTree(null, mock(ResourceFilter.class)));
			assertThrows(IllegalArgumentException.class, () -> run.validateTree(this.root, null));
			assertThrows(IllegalArgumentException.class, () -> run.validateTrees(null));
			assertThrows(IllegalArgumentException.class, () -> run.validateTrees(Arrays.asList(this.root, null)));
			assertThrows(IllegalArgumentException.class, () -> run.isValidated(null, this.root));
			assertThrows(IllegalArgumentException.class, () -> run.isValidated(this.root.resolve("a.md"), null));
			assertThrows(IllegalArgumentException.class, () -> run.validate((Resource) null));
		}
	}

	@Test
	void aClosedRunWalksNothing() {
		MarkdownValidationRun run = this.rules.createRun();
		run.close();

		assertThrows(IllegalStateException.class, () -> run.validateTree(this.root),
				"A closed run is expected to refuse a walk.");
		assertThrows(IllegalStateException.class, () -> run.validate(resource("a.md")),
				"A closed run is expected to refuse a resource.");
	}

	@Test
	void aCancelledRunWalksNothing() throws IOException {
		create("a.md");

		try (MarkdownValidationRun run = this.rules.createRun()) {
			run.cancel();
			assertTrue(run.validateTree(this.root).isCancelled(), "A cancelled run is expected to walk nothing.");
			assertTrue(run.validate(resource("a.md")).isCancelled(), "A cancelled run is expected to check nothing.");
		}
		assertEquals(List.of(), this.validator.checked, "Nothing is expected to be checked.");
	}

	@Test
	void cancellingARunCancelsItsWalk() throws IOException {
		create("a.md", "b/c.md");
		DocumentValidator waiting = new DocumentValidator(false);
		MarkdownValidationRules waitingRules = new MarkdownValidationRules(this.parserAndRenderer, List.of(waiting));

		try (MarkdownValidationRun run = waitingRules.createRun()) {
			CompletableFuture<Map<Resource, List<ValidationIssue>>> findings = run.validateTree(this.root);
			while (waiting.promises.size() < 2) {
				Thread.onSpinWait();
			}
			assertFalse(findings.isDone(), "The walk is expected to wait for the checks.");

			run.cancel();

			assertTrue(findings.isCancelled(), "Cancelling the run is expected to cancel the walk's findings.");
		}
	}

	@Test
	void closingARunWaitsForItsWalks() throws IOException {
		create("a.md", "b/c.md");
		DocumentValidator waiting = new DocumentValidator(false);
		MarkdownValidationRules waitingRules = new MarkdownValidationRules(this.parserAndRenderer, List.of(waiting));
		CompletableFuture<Map<Resource, List<ValidationIssue>>> findings;

		MarkdownValidationRun run = waitingRules.createRun();
		findings = run.validateTree(this.root);
		Thread answering = Thread.ofVirtual().start(() -> {
			while (waiting.promises.size() < 2) {
				Thread.onSpinWait();
			}
			waiting.promises.forEach(promise -> promise.complete(List.of()));
		});
		run.close();

		assertTrue(findings.isDone(), "Closing is expected to wait for the walk's findings.");
		assertEquals(2, findings.join().size(), "Both files are expected to be checked.");
		assertFalse(answering.isAlive() && waiting.promises.size() < 2, "Both files are expected to be found.");
	}

	@Test
	void aNamedResourceIsReadAndCheckedWhateverTheFilterSays() throws IOException {
		create("src/a.md");
		ResourceFilter filter = DefaultMarkdownValidationResourcesFilter.builderWithDefaults().onlyPaths("/doc/")
				.build();

		try (MarkdownValidationRun run = runWith(filter)) {
			assertEquals(List.of(), join(run.validate(resource("src/a.md"))), "The resource is expected to be checked.");
			assertFalse(run.isValidated(this.root.resolve("src/a.md"), this.root),
					"A walk is expected to skip the file.");
		}
		assertEquals(List.of("# src/a.md"), this.validator.checked, "The resource's contents are expected to be read.");
	}

	@Test
	void aNamedResourceThatCannotBeReadFailsItsPromise() {
		try (MarkdownValidationRun run = this.rules.createRun()) {
			CompletionException failure = assertThrows(CompletionException.class,
					() -> join(run.validate(resource("missing.md"))));
			assertInstanceOf(IOException.class, failure.getCause(),
					"The promise is expected to fail with the reason the resource could not be read.");
		}
	}

	@Test
	void aNamedResourceWhoseReadingFailsUnexpectedlyThrows() throws IOException {
		create("a.md");

		try (MarkdownValidationRun run = this.rules.createRun(null, failingFor("a.md", new IllegalStateException("boom")),
				DefaultMarkdownValidationResourcesFilter.builderWithDefaults().build())) {
			assertThrows(IllegalStateException.class, () -> run.validate(resource("a.md")),
					"What the reader threw is expected to be thrown.");
		}
	}

	@Test
	void aRunTellsWhetherAWalkWouldCheckAFile() throws IOException {
		create("doc/a.md", "src/b.md");
		ResourceFilter filter = DefaultMarkdownValidationResourcesFilter.builderWithDefaults().onlyPaths("/doc/")
				.build();

		try (MarkdownValidationRun run = runWith(filter)) {
			assertTrue(run.isValidated(this.root.resolve("doc/a.md"), this.root), "doc/a.md is expected to be checked.");
			assertFalse(run.isValidated(this.root.resolve("src/b.md"), this.root), "src/b.md is expected to be skipped.");
		}
	}

}
