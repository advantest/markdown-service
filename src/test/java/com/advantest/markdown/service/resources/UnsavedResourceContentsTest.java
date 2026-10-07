/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.advantest.resources.LocalFileSystemResource;
import com.advantest.resources.Resource;
import com.advantest.resources.ResourceContentsReader;
import com.advantest.resources.ResourceKind;
import com.advantest.resources.UnresolvedResource;

/**
 * Tests for {@link UnsavedResourceContents}, which answers with the contents put for a resource
 * instead of what the resource contains.
 */
class UnsavedResourceContentsTest {

	@TempDir
	private Path tempDir;

	private final UnsavedResourceContents unsavedContents = new UnsavedResourceContents();

	private Resource guide;

	@BeforeEach
	void writeTheGuide() throws IOException {
		Path file = this.tempDir.resolve("guide.md");
		Files.writeString(file, "# Saved\n");
		this.guide = LocalFileSystemResource.of(file);
	}

	@Test
	void readsWhatTheResourceContainsWhereNothingWasPut() throws IOException {
		assertEquals("# Saved\n", this.unsavedContents.readerOfCurrentContents().readAllContents(this.guide));
		assertEquals("# Saved\n", this.unsavedContents.readerOfContentsSnapshot().readAllContents(this.guide));
	}

	@Test
	void readsWhatWasPutInsteadOfWhatTheResourceContains() throws IOException {
		this.unsavedContents.put(this.guide, "# Unsaved\n");

		assertEquals("# Unsaved\n", this.unsavedContents.readerOfCurrentContents().readAllContents(this.guide));
		assertEquals("# Unsaved\n", this.unsavedContents.readerOfContentsSnapshot().readAllContents(this.guide));
	}

	@Test
	void readsWhatWasPutLast() throws IOException {
		this.unsavedContents.put(this.guide, "# First\n");
		this.unsavedContents.put(this.guide, "# Second\n");

		assertEquals("# Second\n", this.unsavedContents.readerOfCurrentContents().readAllContents(this.guide));
	}

	@Test
	void readsTheResourceAgainOnceWhatWasPutIsDropped() throws IOException {
		this.unsavedContents.put(this.guide, "# Unsaved\n");
		this.unsavedContents.drop(this.guide);

		assertEquals("# Saved\n", this.unsavedContents.readerOfCurrentContents().readAllContents(this.guide));
	}

	@Test
	void dropsNothingWhereNothingWasPut() throws IOException {
		this.unsavedContents.drop(this.guide);

		assertEquals("# Saved\n", this.unsavedContents.readerOfCurrentContents().readAllContents(this.guide));
	}

	@Test
	void readsWhatWasPutForAnotherResourceOfTheSamePath() throws IOException {
		this.unsavedContents.put(this.guide, "# Unsaved\n");

		Resource sameFile = LocalFileSystemResource.of(this.tempDir.resolve("guide.md"));

		assertEquals("# Unsaved\n", this.unsavedContents.readerOfCurrentContents().readAllContents(sameFile));
	}

	@Test
	void readsEveryOtherResourceItself() throws IOException {
		this.unsavedContents.put(this.guide, "# Unsaved\n");
		Path notes = this.tempDir.resolve("notes.md");
		Files.writeString(notes, "# Notes\n");

		assertEquals("# Notes\n",
				this.unsavedContents.readerOfCurrentContents().readAllContents(LocalFileSystemResource.of(notes)));
	}

	@Test
	void readerOfCurrentContentsSeesWhatIsPutAndDroppedAfterItWasHandedOut() throws IOException {
		ResourceContentsReader reader = this.unsavedContents.readerOfCurrentContents();

		this.unsavedContents.put(this.guide, "# Unsaved\n");
		assertEquals("# Unsaved\n", reader.readAllContents(this.guide));

		this.unsavedContents.drop(this.guide);
		assertEquals("# Saved\n", reader.readAllContents(this.guide));
	}

	@Test
	void readerOfContentsSnapshotSeesNothingPutOrDroppedAfterItWasHandedOut() throws IOException {
		this.unsavedContents.put(this.guide, "# Before\n");
		ResourceContentsReader reader = this.unsavedContents.readerOfContentsSnapshot();

		this.unsavedContents.put(this.guide, "# After\n");
		assertEquals("# Before\n", reader.readAllContents(this.guide));

		this.unsavedContents.drop(this.guide);
		assertEquals("# Before\n", reader.readAllContents(this.guide));
	}

	@Test
	void readsAnUnresolvedResourceItself() {
		ResourceContentsReader reader = this.unsavedContents.readerOfCurrentContents();

		assertThrows(IOException.class, () -> reader.readAllContents(new UnresolvedResource("guide.md")));
	}

	@Test
	void readsAResourceWithoutAPathItself() throws IOException {
		assertEquals("# Untitled\n",
				this.unsavedContents.readerOfCurrentContents().readAllContents(new ResourceWithoutPath()));
	}

	@Test
	void refusesContentsForAResourceNobodyResolved() {
		Resource unresolved = new UnresolvedResource("guide.md");

		assertThrows(IllegalArgumentException.class, () -> this.unsavedContents.put(unresolved, "# Unsaved\n"));
		assertThrows(IllegalArgumentException.class, () -> this.unsavedContents.drop(unresolved));
	}

	@Test
	void refusesContentsForAResourceWithoutAPath() {
		Resource withoutPath = new ResourceWithoutPath();

		assertThrows(IllegalArgumentException.class, () -> this.unsavedContents.put(withoutPath, "# Unsaved\n"));
		assertThrows(IllegalArgumentException.class, () -> this.unsavedContents.drop(withoutPath));
	}

	@Test
	void refusesNulls() {
		assertThrows(IllegalArgumentException.class, () -> this.unsavedContents.put(null, "# Unsaved\n"));
		assertThrows(IllegalArgumentException.class, () -> this.unsavedContents.put(this.guide, null));
		assertThrows(IllegalArgumentException.class, () -> this.unsavedContents.drop(null));
		assertThrows(IllegalArgumentException.class,
				() -> this.unsavedContents.readerOfCurrentContents().readAllContents(null));
	}

	/**
	 * A resource whose resolved path is empty, as one created for text of unknown origin.
	 */
	private static final class ResourceWithoutPath implements Resource {

		@Override
		public String getResolvedPath() {
			return "";
		}

		@Override
		public boolean exists() {
			return true;
		}

		@Override
		public Optional<ResourceKind> getKind() {
			return Optional.of(ResourceKind.FILE);
		}

		@Override
		public BufferedReader readContents() {
			return new BufferedReader(new StringReader("# Untitled\n"));
		}

	}

}
