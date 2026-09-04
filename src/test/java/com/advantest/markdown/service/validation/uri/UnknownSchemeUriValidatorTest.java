/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation.uri;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.advantest.markdown.service.validation.IssueSeverity;
import com.advantest.markdown.service.validation.MarkdownIssueTypes;
import com.advantest.markdown.service.validation.ValidationIssue;

/**
 * Tests which scheme is left alone and which one is reported as one nothing here knows.
 */
class UnknownSchemeUriValidatorTest {

	private final UnknownSchemeUriValidator validator = new UnknownSchemeUriValidator();

	@Test
	void schemeOfAWebAddressIsKnown() {
		assertFalse(this.validator.isResponsibleFor(targetOf("https://example.org/guide")));
		assertFalse(this.validator.isResponsibleFor(targetOf("http://example.org/guide")));
	}

	@Test
	void schemeThatIsNoQuestionOfReachabilityIsKnownAsWell() {
		assertFalse(this.validator.isResponsibleFor(targetOf("mailto:someone@example.org")));
		assertFalse(this.validator.isResponsibleFor(targetOf("tel:+49-123-456")));
		assertFalse(this.validator.isResponsibleFor(targetOf("file:///c:/documents/guide.md")));
	}

	@Test
	void schemeIsReadRegardlessOfUpperAndLowerCase() {
		assertFalse(this.validator.isResponsibleFor(targetOf("MailTo:someone@example.org")));
	}

	@Test
	void schemeNothingKnowsIsThisValidatorsBusiness() {
		assertTrue(this.validator.isResponsibleFor(targetOf("htp://example.org/guide")));
		assertTrue(this.validator.isResponsibleFor(targetOf("htps://example.org/guide")));
	}

	@Test
	void schemeOfATargetTheUriSyntaxRefusesIsReadFromTheTextItself() {
		assertTrue(this.validator.isResponsibleFor(targetOf("htp://example.org/a guide")));
		assertFalse(this.validator.isResponsibleFor(targetOf("https://example.org/a guide")));
	}

	@Test
	void targetNamingNoSchemeAtAllIsNotThisValidatorsBusiness() {
		assertFalse(this.validator.isResponsibleFor(targetOf("guide/introduction.md")));
	}

	@Test
	void schemeACallerNamesIsKnownAsWell() {
		UnknownSchemeUriValidator validatorKnowingMore =
				new UnknownSchemeUriValidator(Set.of("Jira", "wiki"));

		assertFalse(validatorKnowingMore.isResponsibleFor(targetOf("jira:HMR-257")));
		assertFalse(validatorKnowingMore.isResponsibleFor(targetOf("wiki:Some_Page")));
		assertTrue(validatorKnowingMore.isResponsibleFor(targetOf("htp://example.org")));
	}

	@Test
	void validatorNeedsARealSetOfSchemes() {
		assertThrows(IllegalArgumentException.class, () -> new UnknownSchemeUriValidator(null));

		Set<String> schemesHoldingNothing = new HashSet<>();
		schemesHoldingNothing.add(null);
		assertThrows(IllegalArgumentException.class,
				() -> new UnknownSchemeUriValidator(Collections.unmodifiableSet(schemesHoldingNothing)));
	}

	@Test
	void validatorRejectsATargetItIsNotGiven() {
		assertThrows(IllegalArgumentException.class, () -> this.validator.isResponsibleFor(null));
		assertThrows(IllegalArgumentException.class, () -> this.validator.validate(null));
	}

	@Test
	void targetNamingASchemeNothingKnowsIsReportedWhereItStands() {
		UriTarget target = UriTarget.of("htp://example.org/guide", 4, 40, 63);

		List<ValidationIssue> issues = this.validator.validate(target);

		assertEquals(1, issues.size());
		ValidationIssue issue = issues.get(0);
		assertEquals(MarkdownIssueTypes.LINK_UNKNOWN_TARGET_SCHEME, issue.issueTypeId());
		assertEquals(IssueSeverity.WARNING, issue.severity());
		assertEquals("The referenced target 'htp://example.org/guide' names the scheme 'htp', which"
				+ " nothing knows here, so the target is neither resolved nor checked. Please check"
				+ " the scheme for a typing mistake.", issue.message());
		assertEquals(4, issue.lineNumber());
		assertEquals(40, issue.startOffset());
		assertEquals(63, issue.endOffset());
	}

	private static UriTarget targetOf(String text) {
		return UriTarget.of(text, 1, 0, text.length());
	}

}
