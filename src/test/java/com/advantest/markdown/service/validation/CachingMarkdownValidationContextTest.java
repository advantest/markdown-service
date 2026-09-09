/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.advantest.markdown.MarkdownFileExtensions;
import com.advantest.markdown.MarkdownParserAndHtmlRenderer;
import com.advantest.resources.Resource;
import com.advantest.resources.ResourceKind;
import com.vladsch.flexmark.util.ast.Document;

/**
 * Checks what a validation run remembers about the documents its validators looked into.
 */
class CachingMarkdownValidationContextTest {

	/** A resource answering with a fixed text, or failing, and counting how often it was read. */
	private static final class CountingResource implements Resource {

		private final String resolvedPath;

		private final String contents;

		private int readCount;

		private CountingResource(String resolvedPath, String contents) {
			this.resolvedPath = resolvedPath;
			this.contents = contents;
		}

		@Override
		public String getResolvedPath() {
			return this.resolvedPath;
		}

		@Override
		public boolean exists() {
			return this.contents != null;
		}

		@Override
		public Optional<ResourceKind> getKind() {
			return exists() ? Optional.of(ResourceKind.FILE) : Optional.empty();
		}

		@Override
		public BufferedReader readContents() throws IOException {
			this.readCount++;
			if (this.contents == null) {
				throw new IOException("Nothing to read at " + this.resolvedPath + ".");
			}
			return new BufferedReader(new StringReader(this.contents));
		}
	}

	private final MarkdownValidationContext context =
			MarkdownValidationContext.parsingWith(new MarkdownParserAndHtmlRenderer());

	@Test
	void aResourceIsReadOnlyOnceInOneRun() throws IOException {
		CountingResource resource = new CountingResource("/docs/guide.md", "# Guide\n");

		Document firstAnswer = this.context.getParsedMarkdownDocument(resource);
		Document secondAnswer = this.context.getParsedMarkdownDocument(resource);

		assertSame(firstAnswer, secondAnswer, "The document parsed first is expected again.");
		assertEquals(1, resource.readCount, "A document is expected to be read once per run.");
	}

	@Test
	void twoResourcesAreToldApartByTheirResolvedPath() throws IOException {
		CountingResource guide = new CountingResource("/docs/guide.md", "# Guide\n");
		CountingResource notes = new CountingResource("/docs/notes.md", "# Notes\n");

		Document parsedGuide = this.context.getParsedMarkdownDocument(guide);
		Document parsedNotes = this.context.getParsedMarkdownDocument(notes);

		assertEquals("# Guide\n", parsedGuide.getChars().toString());
		assertEquals("# Notes\n", parsedNotes.getChars().toString());
	}

	@Test
	void aDocumentThatCannotBeReadIsNotReadAgain() {
		CountingResource missing = new CountingResource("/docs/gone.md", null);

		IOException firstFailure =
				assertThrows(IOException.class, () -> this.context.getParsedMarkdownDocument(missing));
		IOException secondFailure =
				assertThrows(IOException.class, () -> this.context.getParsedMarkdownDocument(missing));

		assertSame(firstFailure, secondFailure, "Every caller is expected to be told what went wrong.");
		assertEquals(1, missing.readCount, "A failing document is expected to be read once per run.");
	}

	@Test
	void aParsedDocumentKnowsWhereItCameFrom() throws IOException {
		CountingResource resource = new CountingResource("/docs/guide.md", "# Guide\n");

		Document parsedDocument = this.context.getParsedMarkdownDocument(resource);

		assertSame(resource, MarkdownParserAndHtmlRenderer.KEY_DOCUMENT_RESOURCE.get(parsedDocument),
				"The parsed document is expected to carry the resource it was read from.");
	}

	@Test
	void aResourceNobodyResolvedIsNotRead() {
		CountingResource unnamed = new CountingResource("", "# Guide\n");

		assertThrows(IllegalArgumentException.class, () -> this.context.getParsedMarkdownDocument(unnamed));
		assertEquals(0, unnamed.readCount, "Without a name nothing says that this is Markdown.");
	}

	@Test
	void aResourceThatIsNoMarkdownFileIsNotRead() {
		CountingResource plainText = new CountingResource("/docs/guide.txt", "# Guide\n");

		assertThrows(IllegalArgumentException.class, () -> this.context.getParsedMarkdownDocument(plainText));
		assertEquals(0, plainText.readCount,
				"Reading a document that is no Markdown and parsing it as Markdown answers about nothing.");
	}

	@Test
	void howTheMarkdownExtensionIsWrittenDoesNotMatter() throws IOException {
		CountingResource shouted = new CountingResource("/docs/GUIDE.MD", "# Guide\n");

		assertEquals("# Guide\n", this.context.getParsedMarkdownDocument(shouted).getChars().toString());
	}

	@Test
	void whatCountsAsMarkdownIsWhatTheParserAccepts() throws IOException {
		MarkdownValidationContext contextAcceptingMore =
				MarkdownValidationContext.parsingWith(MarkdownParserAndHtmlRenderer.builder()
						.withMarkdownFileExtensions(MarkdownFileExtensions.accepting("md", "mkd"))
						.build());
		CountingResource resource = new CountingResource("/docs/guide.mkd", "# Guide\n");

		assertEquals("# Guide\n", contextAcceptingMore.getParsedMarkdownDocument(resource).getChars().toString());
		assertThrows(IllegalArgumentException.class, () -> this.context.getParsedMarkdownDocument(resource),
				"A context whose parser was not told about that extension is expected to refuse it.");
	}

	@Test
	void nothingIsReadForNoResource() {
		assertThrows(IllegalArgumentException.class, () -> this.context.getParsedMarkdownDocument(null));
	}

	@Test
	void aContextWithoutAParserCannotBeCreated() {
		assertThrows(IllegalArgumentException.class, () -> MarkdownValidationContext.parsingWith(null));
	}

}
