/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation;

/**
 * How severe a {@link ValidationIssue} is.
 * 
 * <p>The constants correspond to the diagnostic severities of the Language Server Protocol,
 * so that a language server can map them without having to interpret them.</p>
 */
public enum IssueSeverity {

	/** Something is wrong and needs to be fixed, e.g. a link that cannot be resolved. */
	ERROR,

	/** Something is suspicious, but may well be intended. */
	WARNING,

	/** Something worth knowing that does not ask for a change. */
	INFO;

}
