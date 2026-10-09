/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.resources.walk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The files named in a blacklist, which a walk does not validate.
 * 
 * <p>A blacklist is a text file with one entry per line:</p>
 * <ul>
 * <li>Every line is trimmed. Blank lines and lines starting with <code>#</code> are dropped.</li>
 * <li>Every other line is the path of a file relative to a base folder, with <code>/</code> or
 * <code>\</code> as separator. A leading separator is ignored, so <code>/doc/a.md</code> and
 * <code>doc/a.md</code> name the same file.</li>
 * <li>An entry names a file. An entry naming an existing folder has no effect, which is reported
 * once when the list is read.</li>
 * <li>An entry naming nothing that exists has no effect either. All such entries are reported
 * together, once when the list is read, so that whoever keeps the list can remove them.</li>
 * </ul>
 */
public final class BlacklistedPaths {

	private static final Logger LOG = LoggerFactory.getLogger(BlacklistedPaths.class);

	private static final BlacklistedPaths NONE = new BlacklistedPaths(Set.of(), List.of());

	private final Set<Path> files;

	private final List<String> entriesWithoutTarget;

	private BlacklistedPaths(Set<Path> files, List<String> entriesWithoutTarget) {
		this.files = files;
		this.entriesWithoutTarget = entriesWithoutTarget;
	}

	/**
	 * Reads the given blacklist file.
	 * 
	 * @param listFile the blacklist file, must not be <code>null</code>
	 * @param base the folder the entries are relative to, must not be <code>null</code>
	 * @return the files listed, none if the list could not be read, which is reported
	 */
	static BlacklistedPaths read(Path listFile, Path base) {
		List<String> lines;
		try {
			lines = Files.readAllLines(listFile, StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOG.warn("Could not read the blacklist {}, so no file listed there is skipped: {}", listFile, e.toString());
			return NONE;
		}
		return of(lines, base, listFile.toString());
	}

	/**
	 * Takes the given lines of a blacklist.
	 * 
	 * @param lines the blacklist's lines, must not be <code>null</code>
	 * @param base the folder the entries are relative to, must not be <code>null</code>
	 * @param source where the lines came from, named when an entry is reported
	 * @return the files listed, never <code>null</code>
	 */
	static BlacklistedPaths of(List<String> lines, Path base, String source) {
		Path absoluteBase = base.toAbsolutePath().normalize();
		Set<Path> files = new HashSet<>();
		List<String> entriesWithoutTarget = new ArrayList<>();
		for (String line : lines) {
			String entry = line.strip();
			if (entry.isEmpty() || entry.startsWith("#")) {
				continue;
			}
			entry = entry.replace('\\', '/');
			while (entry.startsWith("/")) {
				entry = entry.substring(1);
			}
			Path file;
			try {
				file = absoluteBase.resolve(entry).normalize();
			} catch (InvalidPathException e) {
				LOG.warn("The blacklist {} has an entry that is no path, which is ignored: {}", source, line);
				continue;
			}
			if (Files.isDirectory(file)) {
				LOG.warn("The blacklist {} names a folder, which is ignored, since it is to name files only: {}",
						source, line);
				continue;
			}
			if (!Files.exists(file)) {
				entriesWithoutTarget.add(entry);
			}
			files.add(file);
		}
		if (!entriesWithoutTarget.isEmpty()) {
			LOG.warn("Entries of the blacklist {} name no existing file and may be removed from it: {}", source,
					String.join(", ", entriesWithoutTarget));
		}
		return new BlacklistedPaths(files, List.copyOf(entriesWithoutTarget));
	}

	/**
	 * Tells which entries name nothing that exists, as they are written in the list once trimmed
	 * and with <code>/</code> as separator.
	 */
	List<String> entriesWithoutTarget() {
		return this.entriesWithoutTarget;
	}

	/**
	 * Tells whether the given file is listed.
	 * 
	 * @param file the file, absolute and normalized
	 * @return <code>true</code> if the file is listed
	 */
	public boolean contains(Path file) {
		return this.files.contains(file);
	}

}
