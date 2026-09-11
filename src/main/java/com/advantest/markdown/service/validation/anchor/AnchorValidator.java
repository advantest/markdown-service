/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation.anchor;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.advantest.markdown.service.validation.MarkdownValidationContext;
import com.advantest.markdown.service.validation.ValidationIssue;

/**
 * Checks what a link points to inside another document, e.g. the <code>section</code> of
 * <code>guide.md#section</code>.
 * 
 * <p>What a fragment means depends on the kind of the target: a section of a Markdown document, a
 * method of a Java file, a page of something else. This library ships the Markdown answer and lets
 * the surrounding environment register what it knows better; the validators are asked in the order
 * they were registered, and the first one saying that it is responsible answers alone.</p>
 * 
 * <p>A validator answers with findings rather than with a yes or a no, so that a rule beyond "is
 * the anchor there" &ndash; an anchor spelled invalidly, one the target declares twice &ndash;
 * needs no other interface. Reading the target is not its business: whoever asks has read it, so
 * that a target which cannot be read is one finding in one place, whatever kind of target it
 * is.</p>
 */
public interface AnchorValidator {

	/**
	 * Tells whether this validator answers for the given target.
	 * 
	 * <p>The question is asked before the target is read, so a fragment nobody answers for costs no
	 * reading. It is therefore decided by the {@link AnchorTarget#targetPath() path as it is
	 * written}, not by what the target contains.</p>
	 * 
	 * @param target the target of a link, must not be <code>null</code>
	 * @return <code>true</code> if and only if this validator answers for that target
	 * @throws IllegalArgumentException if the given target is <code>null</code>
	 */
	boolean isResponsibleFor(AnchorTarget target);

	/**
	 * Checks the given target, which this validator said it is
	 * {@link #isResponsibleFor(AnchorTarget) responsible} for.
	 * 
	 * <p>A validator that cannot answer at once hands back the promise of its findings and returns;
	 * the promise is waited for once the document has been walked.</p>
	 * 
	 * @param target the target of a link, must not be <code>null</code>
	 * @param context what the current validation run knows, among it what another document
	 *                contains, must not be <code>null</code>
	 * @return the promise of the problems found, never <code>null</code>, kept with an empty list
	 *         where there are none
	 * @throws IllegalArgumentException if an argument is <code>null</code>
	 */
	CompletableFuture<List<ValidationIssue>> validate(AnchorTarget target, MarkdownValidationContext context);

}
