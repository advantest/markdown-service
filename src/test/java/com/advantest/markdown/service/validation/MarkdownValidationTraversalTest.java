/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.advantest.markdown.service.MarkdownService;
import com.vladsch.flexmark.ast.Code;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.Link;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ast.Text;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;

/**
 * Checks the document traversal of {@link MarkdownValidation}, i.e. which nodes a validator is
 * offered and which ones are kept away from it.
 */
public class MarkdownValidationTraversalTest {

	private final MarkdownService service = new MarkdownService();

	/**
	 * A validator remembering the nodes it was offered, so that a test can check them.
	 */
	private static class RecordingValidator implements MarkdownValidator {

		private final Set<Class<? extends Node>> triggeringNodeTypes;

		private final NodeFilter ignoredNodes;

		private final List<Node> visitedNodes = new ArrayList<>();

		RecordingValidator(NodeFilter ignoredNodes, Set<Class<? extends Node>> triggeringNodeTypes) {
			this.ignoredNodes = ignoredNodes;
			this.triggeringNodeTypes = triggeringNodeTypes;
		}

		@Override
		public Set<Class<? extends Node>> getTriggeringNodeTypes() {
			return this.triggeringNodeTypes;
		}

		@Override
		public NodeFilter getIgnoredNodes() {
			return this.ignoredNodes;
		}

		@Override
		public List<ValidationIssue> validate(Node node) {
			this.visitedNodes.add(node);
			return List.of();
		}

		List<String> visitedText() {
			return this.visitedNodes.stream().map(node -> node.getChars().toString()).toList();
		}
	}

	private static RecordingValidator validatorFor(Class<? extends Node> nodeType) {
		return new RecordingValidator(NodeFilters.MARKDOWN_CODE, Set.of(nodeType));
	}

	private List<ValidationIssue> validate(String markdown, MarkdownValidator... validators) {
		Document document = this.service.parseMarkdown(markdown);
		return new MarkdownValidation(List.of(validators)).validate(document);
	}

	@Test
	public void everyNodeOfTheTriggeringTypeIsOfferedExactlyOnce() {
		RecordingValidator linkValidator = validatorFor(Link.class);

		validate("[one](1.md) and [two](2.md)\n\n[three](3.md)\n", linkValidator);

		assertEquals(List.of("[one](1.md)", "[two](2.md)", "[three](3.md)"), linkValidator.visitedText(),
				"Every link of the document is expected exactly once, in document order.");
	}

	@Test
	public void aValidatorIsTriggeredBySubtypesOfItsNodeTypes() {
		RecordingValidator anyNodeValidator = new RecordingValidator(NodeFilters.NOTHING, Set.of(Node.class));

		validate("Some text\n", anyNodeValidator);

		assertEquals(List.of("Some text\n", "Some text\n", "Some text"), anyNodeValidator.visitedText(),
				"A validator triggered by Node is expected to see the document, the paragraph and the text.");
	}

	@Test
	public void theDocumentItselfIsOfferedToItsValidator() {
		RecordingValidator documentValidator = validatorFor(Document.class);

		validate("[one](1.md)\n", documentValidator);

		assertEquals(1, documentValidator.visitedNodes.size(),
				"A validator triggered by the document is expected to be called exactly once.");
		assertTrue(documentValidator.visitedNodes.get(0) instanceof Document,
				"A validator triggered by the document is expected to be given the document.");
	}

	@Test
	public void anIgnoredNodeIsSkippedWithEverythingBelowIt() {
		RecordingValidator textValidator = validatorFor(Text.class);

		validate("Before\n\n```\nInside the fence\n```\n\nAfter\n", textValidator);

		assertEquals(List.of("Before", "After"), textValidator.visitedText(),
				"The content of the fenced code block is expected to be skipped with the block itself.");
	}

	@Test
	public void whatOneValidatorIgnoresIsStillOfferedToAnother() {
		RecordingValidator markdownValidator = validatorFor(Code.class);
		RecordingValidator codeValidator = new RecordingValidator(NodeFilters.NOTHING, Set.of(Code.class));

		validate("A code span `[link](x.md)` in a text.\n", markdownValidator, codeValidator);

		assertEquals(List.of(), markdownValidator.visitedText(),
				"A validator ignoring verbatim text is not expected to see the code span.");
		assertEquals(List.of("`[link](x.md)`"), codeValidator.visitedText(),
				"A validator ignoring nothing is expected to see the code span.");
	}

	@Test
	public void markdownCodeBetweenInlineHtmlTagsIsStillOffered() {
		RecordingValidator linkValidator = validatorFor(Link.class);

		validate("<em>[label](some/file.md)</em>\n", linkValidator);

		assertEquals(List.of("[label](some/file.md)"), linkValidator.visitedText(),
				"A link between two inline HTML tags is Markdown code and is expected to be checked.");
	}

	@Test
	public void markdownCodeInsideAnHtmlBlockIsNotOffered() {
		RecordingValidator linkValidator = validatorFor(Link.class);

		validate("<div>\n[label](some/file.md)\n</div>\n", linkValidator);

		assertEquals(List.of(), linkValidator.visitedText(),
				"A link inside an HTML block is part of the HTML and is expected to be skipped.");
	}

	@Test
	public void nodesInEmbeddedLanguagesAreSkipped() {
		RecordingValidator linkValidator = validatorFor(Link.class);

		validate("""
				[visible](0.md)

				`[span](1.md)`

				```
				[fence](2.md)
				```

				<!-- [comment](3.md) -->

				@startuml
				[uml](4.md)
				@enduml

				$[math](5.md)$
				""", linkValidator);

		assertEquals(List.of("[visible](0.md)"), linkValidator.visitedText(),
				"Only the link outside the embedded languages is expected to be checked.");
	}

	@Test
	public void aValidatorCanSortOutNodesTheNodeTypeCannotDistinguish() {
		RecordingValidator shortParagraphValidator = new RecordingValidator(
				NodeFilters.MARKDOWN_CODE, Set.of(Paragraph.class)) {
			@Override
			public boolean isValidatorFor(Node node) {
				return node.getChars().length() < 10;
			}
		};

		validate("Short\n\nA much longer paragraph\n", shortParagraphValidator);

		assertEquals(List.of("Short\n"), shortParagraphValidator.visitedText(),
				"Only the paragraph accepted by isValidatorFor is expected to be checked.");
	}

	@Test
	public void theIssuesOfAllValidatorsAreCollectedAndOrderedByStartOffset() {
		MarkdownValidator secondLinkFirst = new MarkdownValidator() {
			@Override
			public Set<Class<? extends Node>> getTriggeringNodeTypes() {
				return Set.of(Document.class);
			}

			@Override
			public List<ValidationIssue> validate(Node node) {
				return List.of(issueAt(20), issueAt(5));
			}
		};

		List<ValidationIssue> issues = validate("[one](1.md) and [two](2.md)\n", secondLinkFirst);

		assertEquals(List.of(5, 20), issues.stream().map(ValidationIssue::startOffset).toList(),
				"The issues of a validator are expected to be ordered by their start offset.");
	}

	private static ValidationIssue issueAt(int startOffset) {
		return new ValidationIssue(MarkdownIssueTypes.LINK_EMPTY_TARGET, IssueSeverity.ERROR,
				"An issue for testing.", 1, startOffset, startOffset + 1);
	}

	@Test
	public void aFencedCodeBlockIsNotOfferedToAMarkdownValidatorButItsSiblingsAre() {
		RecordingValidator fenceValidator = new RecordingValidator(NodeFilters.NOTHING,
				Set.of(FencedCodeBlock.class));
		RecordingValidator paragraphValidator = validatorFor(Paragraph.class);

		validate("Before\n\n```\ncode\n```\n\nAfter\n", fenceValidator, paragraphValidator);

		assertEquals(1, fenceValidator.visitedNodes.size(), "The fenced code block is expected once.");
		assertEquals(List.of("Before\n", "After\n"), paragraphValidator.visitedText(),
				"The paragraphs around the fenced code block are expected to be checked.");
	}

}