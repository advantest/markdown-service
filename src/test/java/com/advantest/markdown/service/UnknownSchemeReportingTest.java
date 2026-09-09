/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.advantest.markdown.service.validation.MarkdownIssueTypes;
import com.advantest.markdown.service.validation.MarkdownValidationContext;
import com.advantest.markdown.service.validation.ValidationIssue;
import com.advantest.markdown.service.validation.uri.UriTarget;
import com.advantest.markdown.service.validation.uri.UriValidator;

/**
 * Tests that a document naming a scheme nothing knows is told so, and that a caller answering for
 * such a scheme takes the target out of that rule's hands.
 */
class UnknownSchemeReportingTest {

	@Test
	void targetNamingASchemeNothingKnowsIsReported() {
		List<ValidationIssue> issues =
				new MarkdownService().validateMarkdown("[label](htp://example.org/guide)");

		assertEquals(1, issues.size());
		assertEquals(MarkdownIssueTypes.LINK_UNKNOWN_TARGET_SCHEME, issues.get(0).issueTypeId());
		assertEquals(8, issues.get(0).startOffset());
		assertEquals(31, issues.get(0).endOffset());
	}

	@Test
	void targetNamingAWebAddressIsLeftAloneAsLongAsNobodyAsksAddresses() {
		assertTrue(new MarkdownService()
				.validateMarkdown("[label](https://example.org/guide)")
				.isEmpty());
	}

	@Test
	void targetNamingAMailAddressIsLeftAlone() {
		assertTrue(new MarkdownService()
				.validateMarkdown("[label](mailto:someone@example.org)")
				.isEmpty());
	}

	@Test
	void targetOfASchemeACallerAnswersForIsNotReported() {
		MarkdownService service = MarkdownService.builder()
				.withUriValidator(new UriValidator() {

					@Override
					public boolean isResponsibleFor(UriTarget target) {
						return target.uriText().startsWith("jira:");
					}

					@Override
					public List<ValidationIssue> validate(UriTarget target, MarkdownValidationContext context) {
						return List.of();
					}
				})
				.build();

		assertTrue(service.validateMarkdown("[label](jira:HMR-257)").isEmpty());
	}

}
