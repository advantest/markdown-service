/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.validation;

/**
 * The identifiers of the issue types this service reports itself.
 * 
 * <p>An issue type identifier names one concrete problem, so that a tool can recognize, filter,
 * suppress or offer a fix for it without matching the message text. Identifiers are stable API:
 * once published, an identifier keeps its meaning.</p>
 * 
 * <p>Components extending this service report their own issue types and are not restricted to the
 * identifiers listed here. To keep identifiers unique across all contributors, an identifier
 * starts with the reverse domain name of the contributing component, the way the identifiers
 * below start with <code>com.advantest.markdown</code>.</p>
 */
public final class MarkdownIssueTypes {

	private static final String PREFIX = "com.advantest.markdown.";

	/** A link or image has no target at all, e.g. <code>[label]()</code>. */
	public static final String LINK_EMPTY_TARGET = PREFIX + "link.emptyTarget";

	/** A link or image points to a file or directory that does not exist. */
	public static final String LINK_TARGET_DOES_NOT_EXIST = PREFIX + "link.targetDoesNotExist";

	/**
	 * A link or image points to a file, but its target path ends with a slash, which announces a
	 * directory.
	 */
	public static final String LINK_FILE_PATH_WITH_TRAILING_SLASH =
			PREFIX + "link.filePathWithTrailingSlash";

	/**
	 * A link or image points to a directory, but its target path does not end with a slash, so it
	 * reads like the path of a file.
	 */
	public static final String LINK_DIRECTORY_PATH_WITHOUT_TRAILING_SLASH =
			PREFIX + "link.directoryPathWithoutTrailingSlash";

	/**
	 * A link or image points to a file or directory by a path that names it on its own, e.g.
	 * <code>/usr/share/doc/guide.md</code>, which leads nowhere on another machine.
	 */
	public static final String LINK_ABSOLUTE_TARGET_PATH = PREFIX + "link.absoluteTargetPath";

	/**
	 * A link or image points to a file relative to the document containing it, but the location of
	 * that document is unknown, so there is nothing the target could be resolved against.
	 */
	public static final String LINK_UNKNOWN_DOCUMENT_LOCATION = PREFIX + "link.unknownDocumentLocation";

	/**
	 * A link or image points to a web address that is none, e.g. because it names another scheme
	 * than <code>http</code> or <code>https</code> or because the address cannot be read at all.
	 */
	public static final String LINK_INVALID_WEB_ADDRESS = PREFIX + "link.invalidWebAddress";

	/**
	 * A link or image points to a web address that did not answer, e.g. because no host of that
	 * name exists or because nothing answered in time.
	 */
	public static final String LINK_WEB_ADDRESS_DOES_NOT_ANSWER = PREFIX + "link.webAddressDoesNotAnswer";

	/**
	 * A link or image points to a web address that answered, but with a status code saying that
	 * it leads nowhere, e.g. <code>404</code>.
	 */
	public static final String LINK_WEB_ADDRESS_NOT_REACHABLE = PREFIX + "link.webAddressNotReachable";

	/**
	 * A link or image points to a target naming a scheme nothing knows, e.g.
	 * <code>htp://example.org</code>, so the target is neither resolved nor checked by anyone.
	 */
	public static final String LINK_UNKNOWN_TARGET_SCHEME = PREFIX + "link.unknownTargetScheme";

	/** A reference link has an empty label, e.g. <code>[label][]</code> used as a full reference link. */	public static final String LINK_EMPTY_REFERENCE_LABEL = PREFIX + "link.emptyReferenceLabel";

	/** A reference link refers to a label that no link reference definition in the document defines. */
	public static final String LINK_MISSING_REFERENCE_DEFINITION = PREFIX + "link.missingReferenceDefinition";

	/** A reference link is broken in a way that leaves open whether its label or its definition is missing. */
	public static final String LINK_AMBIGUOUS_REFERENCE = PREFIX + "link.ambiguousReference";

	/** A link reference definition uses an identifier that Markdown does not allow. */
	public static final String LINK_REFERENCE_DEFINITION_INVALID_IDENTIFIER =
			PREFIX + "linkReferenceDefinition.invalidIdentifier";

	/** Several link reference definitions in the same document use the same identifier. */
	public static final String LINK_REFERENCE_DEFINITION_DUPLICATE_IDENTIFIER =
			PREFIX + "linkReferenceDefinition.duplicateIdentifier";

	/** A section anchor uses an identifier that is not allowed as an HTML anchor identifier. */
	public static final String ANCHOR_INVALID_IDENTIFIER = PREFIX + "anchor.invalidIdentifier";

	/** Several section anchors in the same document use the same identifier. */
	public static final String ANCHOR_DUPLICATE_IDENTIFIER = PREFIX + "anchor.duplicateIdentifier";

	/**
	 * A link or image points to a target that is there but could not be read, so what it contains
	 * could not be looked at.
	 */
	public static final String LINK_TARGET_CANNOT_BE_READ = PREFIX + "link.targetCannotBeRead";

	/**
	 * A link or image points to a place inside a target, e.g. <code>guide.md#section</code>, that
	 * the target does not declare.
	 */
	public static final String ANCHOR_NOT_FOUND = PREFIX + "anchor.notFound";

	/**
	 * A link or image points to a place inside a target no validator answers for, so what the
	 * fragment names could not be looked for.
	 */
	public static final String ANCHOR_NO_VALIDATOR_FOR_TARGET = PREFIX + "anchor.noValidatorForTarget";

	private MarkdownIssueTypes() {
		// utility class, not meant to be instantiated
	}

}
