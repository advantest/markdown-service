/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.advantest.markdown.MarkdownParserAndHtmlRenderer;
import com.advantest.markdown.service.MarkdownService;
import com.advantest.markdown.service.validation.anchor.AnchorTarget;
import com.advantest.markdown.service.validation.anchor.AnchorValidator;
import com.advantest.markdown.service.validation.uri.UriTarget;
import com.advantest.markdown.service.validation.uri.UriValidator;
import com.vladsch.flexmark.ast.Link;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;

/**
 * Checks that a validator which cannot do its work loses its own findings and nothing else.
 */
class FailingValidatorTest {

	private final MarkdownService service = new MarkdownService();

	/** Answers about every link, either with a finding or with whatever the test wants instead. */
	private static class LinkValidator implements MarkdownValidator {

		private final Function<Node, CompletableFuture<List<ValidationIssue>>> answer;

		LinkValidator(Function<Node, CompletableFuture<List<ValidationIssue>>> answer) {
			this.answer = answer;
		}

		@Override
		public Set<Class<? extends Node>> getTriggeringNodeTypes() {
			return Set.of(Link.class);
		}

		@Override
		public CompletableFuture<List<ValidationIssue>> validate(Node node,
				MarkdownValidationContext context) {
			return this.answer.apply(node);
		}
	}

	@Test
	void validatorThrowingWhereItIsAskedLosesNothingButItsOwnFindings() {
		LinkValidator failing = new LinkValidator(node -> {
			throw new IllegalStateException("This rule gave up.");
		});
		LinkValidator working = findingOneThingPerLink();

		List<ValidationIssue> issues = validate("[one](1.md) and [two](2.md)\n", failing, working);

		assertEquals(List.of(0, 16), issues.stream().map(ValidationIssue::startOffset).toList(),
				"Both links are still checked by the rule that works, and both findings are reported.");
	}

	@Test
	void validatorBreakingItsPromiseLosesNothingButItsOwnFindings() {
		LinkValidator failing = new LinkValidator(node -> CompletableFuture
				.failedFuture(new IllegalStateException("This rule gave up after it had promised.")));
		LinkValidator working = findingOneThingPerLink();

		List<ValidationIssue> issues = validate("[one](1.md) and [two](2.md)\n", failing, working);

		assertEquals(List.of(0, 16), issues.stream().map(ValidationIssue::startOffset).toList(),
				"A promise nobody can keep is read as nothing found, not as the end of the run.");
	}

	@Test
	void validatorFailingAboutOneNodeIsStillAskedAboutTheNext() {
		LinkValidator failingOnTheFirstLink = new LinkValidator(node -> {
			if (node.getChars().toString().startsWith("[one]")) {
				throw new IllegalStateException("This rule gave up on this link.");
			}
			return CompletableFuture.completedFuture(List.of(issueAt(node.getStartOffset())));
		});

		List<ValidationIssue> issues = validate("[one](1.md) and [two](2.md)\n", failingOnTheFirstLink);

		assertEquals(List.of(16), issues.stream().map(ValidationIssue::startOffset).toList(),
				"What the rule found about the second link survives what it did about the first.");
	}

	@Test
	void validatorFailingWhereItIsAskedWhetherItAnswersIsReadAsNotAnswering() {
		MarkdownValidator refusingToDecide = new LinkValidator(node -> {
			throw new AssertionError("A validator that does not answer the question is not asked.");
		}) {
			@Override
			public boolean isValidatorFor(Node node) {
				throw new IllegalStateException("This rule cannot tell.");
			}
		};

		assertTrue(validate("[one](1.md)\n", refusingToDecide).isEmpty(),
				"A rule that cannot say whether it answers for a node is not asked about it.");
	}

	@Test
	void validatorHandingBackNothingAtAllIsReadAsHavingFoundNothing() {
		LinkValidator promisingNothing = new LinkValidator(node -> null);

		assertTrue(validate("[one](1.md)\n", promisingNothing).isEmpty(),
				"A rule that is broken enough to hand back nothing ends no run.");
	}

	@Test
	void failureNothingIsMeantToCatchEndsTheRun() {
		StackOverflowError failure = new StackOverflowError();
		LinkValidator dying = new LinkValidator(node -> {
			throw failure;
		});

		assertSame(failure, assertThrows(StackOverflowError.class, () -> validate("[one](1.md)\n", dying)),
				"What says that the machine is in trouble is not read as a rule having found nothing.");
	}

	@Test
	void brokenPromiseCarryingSuchAFailureEndsTheRunAsWell() {
		StackOverflowError failure = new StackOverflowError();
		LinkValidator dying = new LinkValidator(node -> CompletableFuture.failedFuture(failure));

		CompletionException thrown =
				assertThrows(CompletionException.class, () -> validate("[one](1.md)\n", dying));

		assertSame(failure, thrown.getCause(),
				"A promise broken by such a failure is not read as a rule having found nothing either.");
	}

	@Test
	void uriValidatorThatFailsLeavesTheOtherFindingsOfTheDocument() {
		MarkdownService serviceWithAFailingRule = MarkdownService.builderNotCheckingUriReachability()
				.withUriValidator(new UriValidator() {

					@Override
					public boolean isResponsibleFor(UriTarget target) {
						return true;
					}

					@Override
					public CompletableFuture<List<ValidationIssue>> validate(UriTarget target,
							MarkdownValidationContext context) {
						throw new IllegalStateException("This rule gave up.");
					}
				})
				.build();

		String document = "[web](https://example.org/one) and [file](/absolute.md)\n";

		List<ValidationIssue> issues = serviceWithAFailingRule.validateMarkdown(document);

		assertEquals(new MarkdownService().validateMarkdown(document), issues,
				"The document is reported the way it is where nothing answers for its address.");
		assertFalse(issues.isEmpty(), "What is wrong with the other target is still said.");
	}

	@Test
	void uriValidatorFailingWhereItIsAskedWhetherItAnswersLetsTheNextOneAnswer() {
		MarkdownService serviceWithAFailingRule = MarkdownService.builderNotCheckingUriReachability()
				.withUriValidator(new UriValidator() {

					@Override
					public boolean isResponsibleFor(UriTarget target) {
						throw new IllegalStateException("This rule cannot tell.");
					}

					@Override
					public CompletableFuture<List<ValidationIssue>> validate(UriTarget target,
							MarkdownValidationContext context) {
						throw new AssertionError("A validator that did not claim the target is not asked.");
					}
				})
				.withUriValidator(new UriValidator() {

					@Override
					public boolean isResponsibleFor(UriTarget target) {
						return true;
					}

					@Override
					public CompletableFuture<List<ValidationIssue>> validate(UriTarget target,
							MarkdownValidationContext context) {
						return CompletableFuture.completedFuture(List.of(issueAt(target.startOffset())));
					}
				})
				.build();

		List<ValidationIssue> issues =
				serviceWithAFailingRule.validateMarkdown("[web](https://example.org/one)\n");

		assertEquals(List.of(6), issues.stream().map(ValidationIssue::startOffset).toList(),
				"The rule that could not tell is passed over, and the next one answers for the target.");
	}

	@Test
	void anchorValidatorThatFailsLeavesTheOtherFindingsOfTheDocument() {
		MarkdownService serviceWithAFailingRule = MarkdownService.builderNotCheckingUriReachability()
				.withAnchorValidator(new AnchorValidator() {

					@Override
					public boolean isResponsibleFor(AnchorTarget target) {
						return true;
					}

					@Override
					public CompletableFuture<List<ValidationIssue>> validate(AnchorTarget target,
							MarkdownValidationContext context) {
						throw new IllegalStateException("This rule gave up.");
					}
				})
				.build();

		String document = "[here](#nowhere) and [file](/absolute.md)\n";

		List<ValidationIssue> issues = serviceWithAFailingRule.validateMarkdown(document);

		assertFalse(issues.isEmpty(), "What is wrong with the other target is still said.");
		assertTrue(issues.stream().noneMatch(issue -> issue.startOffset() == 0),
				"Nothing is said about the anchor the failing rule was asked about.");
	}

	@Test
	void failureOfAValidatorIsSaidToWhoeverRunsTheProgram() {
		LinkValidator failing = new LinkValidator(node -> {
			throw new IllegalStateException("This rule gave up.");
		});

		String reported = whatIsReportedWhile(() -> validate("[one](1.md)\n", failing));

		assertTrue(reported.contains(LinkValidator.class.getName()),
				"What is reported says which validator failed, so that the rule can be found again.");
		assertTrue(reported.contains("This rule gave up."),
				"What is reported carries what the validator failed with.");
	}

	@Test
	void failureOfAValidatorAskedWhetherItAnswersIsSaidAsWell() {
		MarkdownValidator refusingToDecide = new LinkValidator(node -> null) {
			@Override
			public boolean isValidatorFor(Node node) {
				throw new IllegalStateException("This rule cannot tell.");
			}
		};

		String reported = whatIsReportedWhile(() -> validate("[one](1.md)\n", refusingToDecide));

		assertTrue(reported.contains("This rule cannot tell."),
				"A validator that cannot even say whether it answers does not fail unheard either.");
	}

	@Test
	void nothingIsSaidToTheAuthorAboutAValidatorThatFailed() {
		LinkValidator failing = new LinkValidator(node -> {
			throw new IllegalStateException("This rule gave up.");
		});

		assertTrue(validate("[one](1.md)\n", failing).isEmpty(),
				"A failure is a matter for whoever runs the program, not for whoever writes the text.");
	}

	/**
	 * Runs something and hands back what the composed log sink wrote while it ran.
	 * 
	 * <p>Which sink that is, and what a line in it looks like, is nobody's contract. This library
	 * ships a logging API and no binding; the one reading along here is the one this test run
	 * composes for itself.</p>
	 */
	private static String whatIsReportedWhile(Runnable something) {
		ByteArrayOutputStream reported = new ByteArrayOutputStream();
		PrintStream sinkOfTheTestRun = System.err;
		System.setErr(new PrintStream(reported, true, StandardCharsets.UTF_8));
		try {
			something.run();
		} finally {
			System.err.flush();
			System.setErr(sinkOfTheTestRun);
		}
		return reported.toString(StandardCharsets.UTF_8);
	}

	private static LinkValidator findingOneThingPerLink() {
		return new LinkValidator(
				node -> CompletableFuture.completedFuture(List.of(issueAt(node.getStartOffset()))));
	}

	private static ValidationIssue issueAt(int startOffset) {
		return new ValidationIssue(MarkdownIssueTypes.LINK_EMPTY_TARGET, IssueSeverity.ERROR,
				"An issue for testing.", 1, startOffset, startOffset + 1);
	}

	private List<ValidationIssue> validate(String markdown, MarkdownValidator... validators) {
		Document document = this.service.parseMarkdown(markdown);
		MarkdownParserAndHtmlRenderer parserAndRenderer = new MarkdownParserAndHtmlRenderer();
		return new MarkdownValidation(parserAndRenderer, List.of(validators))
				.validate(document, MarkdownValidationContext.parsingWith(parserAndRenderer)).join();
	}

}
