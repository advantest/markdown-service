/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Applies the validation rules to Markdown source code.
 * 
 * <p>The rules reproduce those of the FluentMark Eclipse plug-ins, including their messages and
 * the text ranges they mark, so that both report the same problems for the same document.</p>
 * 
 * <p>The rules themselves live in validators this one is composed of, each covering one kind of
 * Markdown construct.</p>
 */
public class MarkdownValidator {

	private final MarkdownLinkValidator linkValidator = new MarkdownLinkValidator();

	/**
	 * Checks the given Markdown source code.
	 * 
	 * @param markdownSourceCode the Markdown source code to be checked, must not be <code>null</code>
	 * @return the problems found, ordered by start offset, never <code>null</code> and not modifiable
	 */
	public List<ValidationIssue> validate(String markdownSourceCode) {
		if (markdownSourceCode == null) {
			throw new IllegalArgumentException("Argument must not be null.");
		}

		List<ValidationIssue> issues = new ArrayList<>(this.linkValidator.validate(markdownSourceCode));

		issues.sort(Comparator.comparingInt(ValidationIssue::startOffset));
		return List.copyOf(issues);
	}

}
