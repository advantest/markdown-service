/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation.uri;

import java.util.List;

import com.advantest.markdown.service.validation.ValidationIssue;

/**
 * Checks a link target that names a scheme, e.g. <code>https://example.org/guide</code>.
 * 
 * <p>A target written as a path is looked for where the documents live, and a target naming its
 * resource on its own is reported; everything else names a scheme, and what such a target has to
 * look like, and whether it leads anywhere, is known by whoever owns that scheme. A validator
 * says which targets it knows and is asked about those alone.</p>
 * 
 * <p>Several validators may know the same scheme and tell each other apart by the host or by the
 * beginning of the address, e.g. one answering for the issue tracker of a team and one answering
 * for every other web address. The one registered last that says it is
 * {@link #isResponsibleFor(UriTarget) responsible} answers for a target, and it answers alone. A
 * target no validator claims is left alone rather than reported.</p>
 * 
 * @see com.advantest.markdown.service.MarkdownService.Builder#withUriValidator(UriValidator)
 */
public interface UriValidator {

	/**
	 * Tells whether this validator answers for the given target.
	 * 
	 * <p>The target is passed as it is written in the document, so a validator sees a text that is
	 * no address at all as well, and can claim it where it recognizes what the author meant.</p>
	 * 
	 * @param target the target as it was found in the document, must not be <code>null</code>
	 * @return <code>true</code> if and only if this validator checks the given target
	 * @throws IllegalArgumentException if the given target is <code>null</code>
	 */
	boolean isResponsibleFor(UriTarget target);

	/**
	 * Checks the given target and says what is wrong with it.
	 * 
	 * @param target the target as it was found in the document, must not be <code>null</code>
	 * @return the problems found, never <code>null</code>, empty if there are none
	 * @throws IllegalArgumentException if the given target is <code>null</code>
	 */
	List<ValidationIssue> validate(UriTarget target);

}
