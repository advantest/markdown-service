/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.resources.walk;

import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Decides which folders a walk through a folder tree enters and which files it validates.
 * 
 * <p>A file or folder is skipped as soon as one filter skips it. A skipped folder is not listed,
 * so nothing below it is seen. The root a walk starts from is never asked about.</p>
 * 
 * <p>Every path handed to a filter is absolute and normalized, and lies in the walk's root. The
 * attributes are read without following links, so that
 * {@link BasicFileAttributes#isSymbolicLink()} tells a link from what it points to.</p>
 * 
 * <p>A filter applies to walks only. A document named explicitly is validated whatever a filter
 * would say about it.</p>
 */
public interface ResourceFilter {

	/**
	 * Tells whether a walk skips the given folder and everything below it.
	 * 
	 * @param root the folder the walk started from, absolute and normalized
	 * @param folder the folder, absolute and normalized, below the root
	 * @param attributes the folder's attributes, read without following links
	 * @return <code>true</code> if the walk does not enter the folder
	 */
	default boolean skipsFolder(Path root, Path folder, BasicFileAttributes attributes) {
		return false;
	}

	/**
	 * Tells whether a walk skips the given file.
	 * 
	 * @param root the folder the walk started from, absolute and normalized
	 * @param file the file, absolute and normalized, below the root
	 * @param attributes the file's attributes, read without following links
	 * @return <code>true</code> if the walk does not validate the file
	 */
	default boolean skipsFile(Path root, Path file, BasicFileAttributes attributes) {
		return false;
	}

	/**
	 * Answers the filter to be used by one validation run, called once per run for every filter
	 * the run meets.
	 * 
	 * <p>A filter that needs what the run holds for its filters, e.g. what git ignores, answers an
	 * instance asking the given context. Every other filter answers itself, which is the
	 * default.</p>
	 * 
	 * @param context what the run holds for its filters, shared by all filters and walks of the
	 *                run, never <code>null</code>
	 * @return the filter to be used by the run, never <code>null</code>
	 */
	default ResourceFilter createForRun(ResourceFilterContext context) {
		return this;
	}

}
