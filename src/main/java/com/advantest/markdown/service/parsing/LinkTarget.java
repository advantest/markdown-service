/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.parsing;

import java.net.URI;

/**
 * The three parts of what a link points to: the scheme, the path and the fragment.
 * 
 * <p>The parts answer different questions, and each of them is answered by somebody else. The
 * scheme says who can resolve the target at all: without one it names a file, with one it names
 * something only the surrounding environment knows, e.g. a web address. The path names what is
 * resolved, and the fragment names a place inside it, which can only be looked for once the target
 * itself has been found.</p>
 * 
 * <p>A part is <code>null</code> where the target does not have it, which is not the same as an
 * empty one: <code>#section</code> has an empty path and a fragment, <code>file.md</code> has a
 * path and no fragment.</p>
 * 
 * @param scheme the scheme of the target, e.g. <code>https</code>, or <code>null</code> if it has
 *               none and therefore names a file
 * @param path the path of the target, or <code>null</code> if it has none
 * @param fragment the fragment of the target, without the leading <code>#</code>, or
 *                 <code>null</code> if it has none
 */
public record LinkTarget(String scheme, String path, String fragment) {

	/**
	 * Splits the given link target into its parts.
	 * 
	 * <p>A target of a Markdown document is not necessarily a valid URI &ndash; a path with a blank
	 * in it is the everyday example &ndash; so a target the URI syntax rejects is split by hand.
	 * Both ways are the ones FluentMark uses, so that the rules built on them keep reporting what
	 * they report today.</p>
	 * 
	 * @param linkTarget the target of a link, image or link reference definition, must not be
	 *                   <code>null</code>
	 * @return the parts of the given target, never <code>null</code>
	 * @throws IllegalArgumentException if the given target is <code>null</code>
	 */
	public static LinkTarget of(String linkTarget) {
		if (linkTarget == null) {
			throw new IllegalArgumentException("Argument must not be null.");
		}

		try {
			URI uri = URI.create(linkTarget);
			return new LinkTarget(uri.getScheme(), uri.getPath(), uri.getFragment());
		} catch (IllegalArgumentException exception) {
			return splitByHand(linkTarget);
		}
	}

	/**
	 * Splits a target the URI syntax rejects. The fragment keeps its leading <code>#</code> in
	 * FluentMark here, which is what makes its anchor rules unreachable for every well formed
	 * target; this splits both ways alike, so that the parts mean the same whichever way a target
	 * took.
	 */
	private static LinkTarget splitByHand(String linkTarget) {
		String scheme = null;
		String fragment = null;
		String path = linkTarget;

		int indexOfHashTag = linkTarget.indexOf('#');
		if (indexOfHashTag > -1) {
			fragment = linkTarget.substring(indexOfHashTag + 1);
			path = linkTarget.substring(0, indexOfHashTag);
		}

		int indexOfColon = linkTarget.indexOf(':');
		if (indexOfColon > -1) {
			scheme = linkTarget.substring(0, indexOfColon);
			if (indexOfColon + 1 < path.length()) {
				path = path.substring(indexOfColon + 1);
			}
		}

		return new LinkTarget(scheme, path, fragment);
	}

	/**
	 * Answers whether the target names a file or directory, i.e. something the file system of the
	 * environment resolves rather than a web address or another scheme of its own.
	 * 
	 * @return <code>true</code> if the target has no scheme and a path to resolve
	 */
	public boolean namesAFile() {
		return this.scheme == null && this.path != null && !this.path.isBlank();
	}

}
