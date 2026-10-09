/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.resources.walk;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Walks folder trees and hands every document it finds to a consumer, never handing the same
 * document twice, however many walks it does.
 * 
 * <p>One walker belongs to one validation run. It remembers every document it handed out, so
 * that two links to the same target and roots lying inside each other do not hand a document
 * twice. A walk remembers every folder it entered, so that a symbolic link pointing back up the
 * tree does not make it run in circles; a later walk enters the folders again, since it may be
 * given another filter. A folder or document is recognized by its file key, i.e. device and inode
 * on Linux, or by its real path where the file system has no file key.</p>
 * 
 * <p>A walk reads names and attributes only, never the contents of a file. A file is handed out
 * if it is a document by its name and no filter skips it. A folder that cannot be listed is
 * reported, and the walk goes on with the next one.</p>
 * 
 * <p>Can be used from several threads at once.</p>
 */
public final class ResourceTreeWalker {

	private static final Logger LOG = LoggerFactory.getLogger(ResourceTreeWalker.class);

	private final Predicate<String> isDocumentName;


	private final Set<Object> foundDocuments = ConcurrentHashMap.newKeySet();

	/**
	 * Creates a walker remembering nothing yet.
	 * 
	 * @param isDocumentName tells by a file's name whether it is a document to be handed out, must
	 *                       not be <code>null</code>
	 * @throws IllegalArgumentException if the argument is <code>null</code>
	 */
	public ResourceTreeWalker(Predicate<String> isDocumentName) {
		if (isDocumentName == null) {
			throw new IllegalArgumentException("Argument must not be null.");
		}
		this.isDocumentName = isDocumentName;
	}

	/**
	 * Walks the tree below the given root, depth first, the entries of a folder in the order of
	 * their names, and hands every document found to the given consumer.
	 * 
	 * @param root the folder to start from, must not be <code>null</code>; a symbolic link given as
	 *             root is followed
	 * @param filter the filter deciding which folders are entered and which documents are handed
	 *               out, already {@link ResourceFilter#createForRun(ResourceFilterContext) created}
	 *               for the run, must not be <code>null</code>
	 * @param otherRoots roots of other walks of the same call, which this walk leaves to them, must
	 *                   not be <code>null</code>
	 * @param cancelled tells whether the walk is to stop, asked before every folder, must not be
	 *                  <code>null</code>
	 * @param documents receives every document found, absolute and normalized, must not be
	 *                  <code>null</code>
	 * @throws IOException if the root cannot be read or is no folder
	 */
	public void walk(Path root, ResourceFilter filter, Collection<Path> otherRoots, BooleanSupplier cancelled,
			Consumer<Path> documents) throws IOException {
		Path start = root.toAbsolutePath().normalize();
		BasicFileAttributes rootAttributes = Files.readAttributes(start, BasicFileAttributes.class);
		if (!rootAttributes.isDirectory()) {
			throw new NotDirectoryException(start.toString());
		}
		Folder first = new Folder(start, realPathOf(start));
		Set<Object> enteredFolders = new HashSet<>();
		enteredFolders.add(keyOf(first.realPath(), rootAttributes));

		Deque<Folder> folders = new ArrayDeque<>();
		folders.push(first);
		while (!folders.isEmpty() && !cancelled.getAsBoolean()) {
			Folder folder = folders.pop();
			List<Folder> subfolders = new ArrayList<>();
			for (Path entry : list(folder.path())) {
				visit(start, folder, entry, filter, otherRoots, enteredFolders, subfolders, documents);
			}
			for (int index = subfolders.size() - 1; index >= 0; index--) {
				folders.push(subfolders.get(index));
			}
		}
	}

	/**
	 * A folder to be listed, with its real path, which is found once for the root and for every
	 * symbolic link followed, and derived from the folder above for every other folder, since
	 * finding a real path is slow where every file is asked for it.
	 */
	private record Folder(Path path, Path realPath) {
	}

	private void visit(Path root, Folder parent, Path entry, ResourceFilter filter, Collection<Path> otherRoots,
			Set<Object> enteredFolders, List<Folder> subfolders, Consumer<Path> documents) {
		BasicFileAttributes attributes;
		BasicFileAttributes target;
		try {
			attributes = Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			target = attributes.isSymbolicLink() ? Files.readAttributes(entry, BasicFileAttributes.class) : attributes;
		} catch (IOException e) {
			// gone since it was listed, or a link pointing nowhere: nothing to walk or validate
			LOG.debug("Skipped {}, since its attributes could not be read: {}", entry, e.toString());
			return;
		}
		if (target.isDirectory()) {
			if (otherRoots.contains(entry) || filter.skipsFolder(root, entry, attributes)) {
				return;
			}
			Path realPath = realPathOf(parent, entry, attributes);
			if (enteredFolders.add(keyOf(realPath, target))) {
				subfolders.add(new Folder(entry, realPath));
			}
		} else if (target.isRegularFile()) {
			if (this.isDocumentName.test(entry.getFileName().toString()) && !filter.skipsFile(root, entry, attributes)
					&& this.foundDocuments.add(keyOf(realPathOf(parent, entry, attributes), target))) {
				documents.accept(entry);
			}
		}
	}

	private static List<Path> list(Path folder) {
		List<Path> entries = new ArrayList<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
			for (Path entry : stream) {
				entries.add(entry);
			}
		} catch (IOException | RuntimeException e) {
			LOG.warn("Could not list the folder {}, so nothing below it is validated: {}", folder, e.toString());
		}
		entries.sort(null);
		return entries;
	}

	/**
	 * Answers what tells the given folder or file from every other one: its file key if the file
	 * system has one, as on Linux, otherwise its real path.
	 */
	private static Object keyOf(Path realPath, BasicFileAttributes attributes) {
		Object fileKey = attributes.fileKey();
		return fileKey != null ? fileKey : realPath;
	}

	/** Answers the real path of an entry of the given folder, asking the file system for a link only. */
	private static Path realPathOf(Folder parent, Path entry, BasicFileAttributes attributes) {
		return attributes.isSymbolicLink() ? realPathOf(entry) : parent.realPath().resolve(entry.getFileName());
	}

	private static Path realPathOf(Path path) {
		try {
			return path.toRealPath();
		} catch (IOException e) {
			return path;
		}
	}

	/**
	 * Tells whether a walk from the given root would validate the given file, applying the filter
	 * to the folders on the way and to the file, without walking and without remembering
	 * anything.
	 * 
	 * @param file the file, must not be <code>null</code>
	 * @param root the root a walk would start from, must not be <code>null</code>
	 * @param filter the filter, already {@link ResourceFilter#createForRun(ResourceFilterContext)
	 *               created} for the run, must not be <code>null</code>
	 * @return <code>true</code> if the file exists, lies below the root, is a document by its name,
	 *         and is skipped by no filter, neither itself nor a folder on the way
	 */
	public boolean isValidated(Path file, Path root, ResourceFilter filter) {
		Path start = root.toAbsolutePath().normalize();
		Path path = file.toAbsolutePath().normalize();
		if (!path.startsWith(start) || path.equals(start) || !this.isDocumentName.test(path.getFileName().toString())) {
			return false;
		}
		try {
			Path folder = start;
			for (Path name : start.relativize(path.getParent())) {
				if (name.toString().isEmpty()) {
					continue;
				}
				folder = folder.resolve(name);
				BasicFileAttributes attributes = Files.readAttributes(folder, BasicFileAttributes.class,
						LinkOption.NOFOLLOW_LINKS);
				if (!Files.isDirectory(folder) || filter.skipsFolder(start, folder, attributes)) {
					return false;
				}
			}
			BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
					LinkOption.NOFOLLOW_LINKS);
			return Files.isRegularFile(path) && !filter.skipsFile(start, path, attributes);
		} catch (IOException e) {
			return false;
		}
	}


}
