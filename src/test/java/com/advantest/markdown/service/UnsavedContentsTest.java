/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.advantest.markdown.service.validation.MarkdownValidationRun;
import com.advantest.resources.LocalFileSystemResource;
import com.advantest.resources.Resource;
import com.advantest.resources.UnresolvedResource;

/**
 * Tests for the contents an editor puts into a {@link MarkdownService} because its user did not
 * save them yet, which renderings and validation runs read instead of what is stored.
 */
class UnsavedContentsTest {

	private static final String LINK_TO_INSTALLATION = "See the [installation](guide.md#installation) for details.\n";

	private static final String GUIDE_WITH_INSTALLATION = "# Guide\n\n## Installation {#installation}\n";

	@TempDir
	private Path documentDirectory;

	private final MarkdownService service = new MarkdownService();

	private Resource document;

	private Resource guide;

	@BeforeEach
	void writeTheGuide() throws IOException {
		Path guideFile = this.documentDirectory.resolve("guide.md");
		Files.writeString(guideFile, "# Guide\n");
		this.guide = LocalFileSystemResource.of(guideFile);
		this.document = LocalFileSystemResource.of(this.documentDirectory.resolve("document.md"));
	}

	@Test
	void validationReadsTheUnsavedContentsOfADocumentLinkedTo() {
		assertFalse(this.service.validateMarkdown(LINK_TO_INSTALLATION, this.document).isEmpty(),
				"The stored guide has no section 'installation'.");

		this.service.putUnsavedContents(this.guide, GUIDE_WITH_INSTALLATION);

		assertTrue(this.service.validateMarkdown(LINK_TO_INSTALLATION, this.document).isEmpty(),
				"The unsaved guide has the section 'installation'.");
	}

	@Test
	void validationReadsTheStoredContentsAgainOnceTheUnsavedOnesAreDropped() {
		this.service.putUnsavedContents(this.guide, GUIDE_WITH_INSTALLATION);
		this.service.dropUnsavedContents(this.guide);

		assertFalse(this.service.validateMarkdown(LINK_TO_INSTALLATION, this.document).isEmpty(),
				"The stored guide has no section 'installation'.");
	}

	@Test
	void runReadsTheUnsavedContentsAsTheyWereWhenTheRunWasCreated() {
		try (MarkdownValidationRun runBefore = this.service.createValidationRun()) {
			this.service.putUnsavedContents(this.guide, GUIDE_WITH_INSTALLATION);

			try (MarkdownValidationRun runAfter = this.service.createValidationRun()) {
				assertFalse(runBefore.validate(LINK_TO_INSTALLATION, this.document).join().isEmpty(),
						"A run reads the contents it started with.");
				assertTrue(runAfter.validate(LINK_TO_INSTALLATION, this.document).join().isEmpty(),
						"A run created after the put reads the unsaved guide.");
			}
		}
	}

	@Test
	void unsavedContentsOfAFileThatIsNotStoredDoNotMakeTheFileExist() throws IOException {
		Path draftFile = this.documentDirectory.resolve("draft.md");
		this.service.putUnsavedContents(LocalFileSystemResource.of(draftFile), "# Draft\n");

		assertFalse(this.service.validateMarkdown("See the [draft](draft.md).\n", this.document).isEmpty(),
				"A link to a file that is not stored is broken, whatever an editor holds.");
	}

	@Test
	void renderingReadsTheUnsavedContentsOfADiagram() throws IOException {
		Files.writeString(this.documentDirectory.resolve("sequence.puml"), "@startuml\nAlice -> Saved\n@enduml\n");
		Resource diagram = LocalFileSystemResource.of(this.documentDirectory.resolve("sequence.puml"));
		String markdown = "![sequence](sequence.puml)\n";

		this.service.putUnsavedContents(diagram, "@startuml\nAlice -> Unsaved\n@enduml\n");
		String html = this.service.parseMarkdownAndRenderHtml(markdown, this.document);

		assertTrue(html.contains("Unsaved"), html);
		assertFalse(html.contains("Saved<"), html);
	}

	@Test
	void renderingOfAServiceBuiltReadsTheUnsavedContentsOfADiagram() throws IOException {
		Files.writeString(this.documentDirectory.resolve("sequence.puml"), "@startuml\nAlice -> Saved\n@enduml\n");
		Resource diagram = LocalFileSystemResource.of(this.documentDirectory.resolve("sequence.puml"));
		MarkdownService builtService = MarkdownService.builderNotCheckingUriReachability().build();

		builtService.putUnsavedContents(diagram, "@startuml\nAlice -> Unsaved\n@enduml\n");
		String html = builtService.parseMarkdownAndRenderHtml("![sequence](sequence.puml)\n", this.document);

		assertTrue(html.contains("Unsaved"), html);
	}

	@Test
	void renderingReadsTheStoredContentsAgainOnceTheUnsavedOnesAreDropped() throws IOException {
		Files.writeString(this.documentDirectory.resolve("sequence.puml"), "@startuml\nAlice -> Saved\n@enduml\n");
		Resource diagram = LocalFileSystemResource.of(this.documentDirectory.resolve("sequence.puml"));

		this.service.putUnsavedContents(diagram, "@startuml\nAlice -> Unsaved\n@enduml\n");
		this.service.dropUnsavedContents(diagram);
		String html = this.service.parseMarkdownAndRenderHtml("![sequence](sequence.puml)\n", this.document);

		assertTrue(html.contains("Saved"), html);
		assertFalse(html.contains("Unsaved"), html);
	}

	@Test
	void refusesUnsavedContentsForAResourceNobodyResolved() {
		Resource unresolved = new UnresolvedResource("guide.md");

		assertThrows(IllegalArgumentException.class, () -> this.service.putUnsavedContents(unresolved, "# Guide\n"));
		assertThrows(IllegalArgumentException.class, () -> this.service.dropUnsavedContents(unresolved));
	}

	@Test
	void refusesNulls() {
		assertThrows(IllegalArgumentException.class, () -> this.service.putUnsavedContents(null, "# Guide\n"));
		assertThrows(IllegalArgumentException.class, () -> this.service.putUnsavedContents(this.guide, null));
		assertThrows(IllegalArgumentException.class, () -> this.service.dropUnsavedContents(null));
	}

}
