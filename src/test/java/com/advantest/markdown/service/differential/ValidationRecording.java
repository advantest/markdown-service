/*
 * This work is made available under the terms of the BSD 2-Clause "Simplified" License.
 * The BSD accompanies this distribution (LICENSE.txt).
 * 
 * Copyright © 2026 Advantest Europe GmbH. All rights reserved.
 */
package com.advantest.markdown.service.differential;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A recorded validation run of the FluentMark Eclipse plug-ins over a corpus of Markdown files.
 * 
 * <p>A recording consists of an index naming every validated file together with the number of its
 * findings, and of the findings themselves. The index is what makes the recording usable as a
 * reference: it also names the files that were validated <em>without</em> a finding, and those are
 * the files in which this service must not report anything either.</p>
 */
final class ValidationRecording {

	private static final String INDEX_FILE_NAME = "index.txt";
	private static final String FINDINGS_FILE_NAME = "validation-findings.tsv";

	private static final Pattern INDEX_ENTRY = Pattern.compile("^(\\d+)\\s+(.+)$");

	private final List<String> validatedFiles;
	private final Map<String, List<RecordedFinding>> findingsPerFile;

	private ValidationRecording(List<String> validatedFiles, Map<String, List<RecordedFinding>> findingsPerFile) {
		this.validatedFiles = validatedFiles;
		this.findingsPerFile = findingsPerFile;
	}

	/**
	 * Reads the recording written into the given directory.
	 * 
	 * @param directory the directory holding the index and the findings, must not be
	 *        <code>null</code>
	 * @return the recording, never <code>null</code>
	 * @throws IllegalArgumentException if the recording is incomplete or inconsistent in itself
	 */
	static ValidationRecording readFrom(Path directory) {
		Map<String, List<RecordedFinding>> findingsPerFile = new LinkedHashMap<>();
		for (String row : readRows(directory.resolve(FINDINGS_FILE_NAME))) {
			RecordedFinding finding = RecordedFinding.parse(row);
			findingsPerFile.computeIfAbsent(finding.file(), file -> new ArrayList<>()).add(finding);
		}

		List<String> validatedFiles = new ArrayList<>();
		for (String indexEntry : readLines(directory.resolve(INDEX_FILE_NAME))) {
			Matcher matcher = INDEX_ENTRY.matcher(indexEntry);
			if (!matcher.matches()) {
				throw new IllegalArgumentException("Cannot read the index entry: " + indexEntry);
			}

			String file = matcher.group(2);
			int expectedCount = Integer.parseInt(matcher.group(1));
			int actualCount = findingsPerFile.getOrDefault(file, List.of()).size();
			if (expectedCount != actualCount) {
				throw new IllegalArgumentException("The index announces " + expectedCount + " findings for " + file
						+ ", but the recording holds " + actualCount + ".");
			}

			validatedFiles.add(file);
		}

		return new ValidationRecording(validatedFiles, findingsPerFile);
	}

	/**
	 * Answers every file the recorded run validated, findings or not, in the order of the index.
	 */
	List<String> validatedFiles() {
		return Collections.unmodifiableList(this.validatedFiles);
	}

	/**
	 * Answers what the recorded run reported for the given file, in the order of the recording.
	 */
	List<RecordedFinding> findingsOf(String file) {
		return Collections.unmodifiableList(this.findingsPerFile.getOrDefault(file, List.of()));
	}

	private static List<String> readRows(Path findingsFile) {
		List<String> lines = readLines(findingsFile);
		if (lines.isEmpty()) {
			throw new IllegalArgumentException("The recording " + findingsFile + " has no header row.");
		}

		// the first row names the columns
		return lines.subList(1, lines.size());
	}

	private static List<String> readLines(Path file) {
		try {
			return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
					.filter(line -> !line.isBlank())
					.toList();
		} catch (IOException exception) {
			throw new UncheckedIOException("Cannot read the recording file " + file + ".", exception);
		}
	}

}
