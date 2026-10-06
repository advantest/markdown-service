/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.resources;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.advantest.resources.Resource;
import com.advantest.resources.ResourceContentsReader;
import com.advantest.resources.UnresolvedResource;

/**
 * Holds the contents of resources that differ from what the resources themselves contain, e.g. the
 * text an editor holds and its user did not save yet, so that reading such a resource answers with
 * that text rather than with what was saved.
 * 
 * <p>This is no cache: nothing here is forgotten or cleared but by whoever put it here, and it
 * lives as long as whoever keeps it. What was put here last for a resource wins. Contents are kept
 * under the resource's {@link Resource#getResolvedPath() resolved path}, which is what tells two
 * resources apart for whoever resolved them, so a resource without such a path, and an
 * {@link UnresolvedResource}, cannot be given contents.</p>
 * 
 * <p>The contents are kept in a map that is never changed: every put and every drop replaces it by
 * a new one, which copies the references to the texts, never the texts themselves. Reading through
 * {@link #readerOfContentsAsTheyAreNow()} therefore costs nothing, and whoever reads through it sees
 * one state of all contents, however they change in the meantime.</p>
 * 
 * <p>The contents can be put, dropped and read from several threads at once.</p>
 */
public final class UnsavedResourceContents {

	private final AtomicReference<Map<String, String>> contentsByResolvedPath = new AtomicReference<>(Map.of());

	/**
	 * Says that the given resource contains the given text, until it is dropped or something else
	 * is put for it.
	 * 
	 * @param resource the resource the contents are meant for, must not be <code>null</code>, must
	 *                 not be an {@link UnresolvedResource} and must have a resolved path
	 * @param contents what the resource is to be read as, must not be <code>null</code>
	 * @throws IllegalArgumentException if an argument is <code>null</code>, or if the resource is
	 *                                  unresolved or has no resolved path
	 */
	public void put(Resource resource, String contents) {
		if (contents == null) {
			throw new IllegalArgumentException("Arguments must not be null.");
		}
		String resolvedPath = resolvedPathOf(resource);

		this.contentsByResolvedPath.updateAndGet(contentsByPath -> {
			Map<String, String> changed = new HashMap<>(contentsByPath);
			changed.put(resolvedPath, contents);
			return Map.copyOf(changed);
		});
	}

	/**
	 * Lets go of the contents put for the given resource, so that the resource is read as it is
	 * again. Dropping a resource nothing was put for changes nothing.
	 * 
	 * @param resource the resource whose contents were put, must not be <code>null</code>, must not
	 *                 be an {@link UnresolvedResource} and must have a resolved path
	 * @throws IllegalArgumentException if the given resource is <code>null</code>, unresolved or has
	 *                                  no resolved path
	 */
	public void drop(Resource resource) {
		String resolvedPath = resolvedPathOf(resource);

		this.contentsByResolvedPath.updateAndGet(contentsByPath -> {
			if (!contentsByPath.containsKey(resolvedPath)) {
				return contentsByPath;
			}
			Map<String, String> changed = new HashMap<>(contentsByPath);
			changed.remove(resolvedPath);
			return Map.copyOf(changed);
		});
	}

	/**
	 * Hands out a reader answering with the contents put here at the time it is asked, and asking
	 * the resource itself for any other resource.
	 * 
	 * <p>Where something reads one resource at a time, e.g. a rendering, this reader shows what the
	 * user sees in the moment it reads.</p>
	 * 
	 * @return a reader of the current contents, never <code>null</code>
	 */
	public ResourceContentsReader readerOfCurrentContents() {
		return resource -> readFrom(this.contentsByResolvedPath.get(), resource);
	}

	/**
	 * Hands out a reader answering with the contents put here until now, and asking the resource
	 * itself for any other resource, whatever is put or dropped afterwards.
	 * 
	 * <p>Where something reads many resources and has to see one state of all of them, e.g. a
	 * validation run, this reader keeps contents put in the meantime from it.</p>
	 * 
	 * @return a reader of the contents as they are now, never <code>null</code>
	 */
	public ResourceContentsReader readerOfContentsAsTheyAreNow() {
		Map<String, String> contentsByPath = this.contentsByResolvedPath.get();
		return resource -> readFrom(contentsByPath, resource);
	}

	private static String readFrom(Map<String, String> contentsByPath, Resource resource) throws IOException {
		if (resource == null) {
			throw new IllegalArgumentException("Argument must not be null.");
		}
		String resolvedPath = resource.getResolvedPath();
		String contents = resource instanceof UnresolvedResource || resolvedPath == null
				? null
				: contentsByPath.get(resolvedPath);
		return contents != null ? contents : resource.readAllContents();
	}

	private static String resolvedPathOf(Resource resource) {
		if (resource == null) {
			throw new IllegalArgumentException("Arguments must not be null.");
		}
		if (resource instanceof UnresolvedResource) {
			throw new IllegalArgumentException("An unresolved resource cannot be given contents.");
		}
		String resolvedPath = resource.getResolvedPath();
		if (resolvedPath == null || resolvedPath.isEmpty()) {
			throw new IllegalArgumentException("A resource without a resolved path cannot be given contents.");
		}
		return resolvedPath;
	}

}
