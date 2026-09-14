# Targets naming a file or a directory

A target written as a path names something next to the document. This document is about what the path says
and about whether it is there.

## Something that is not there

<!-- expect: link.files.targetDoesNotExist | ERROR | 23 | ../files/missing.md | The referenced file or directory '../files/missing.md' does not exist. Resolved target path: {corpus}/files/missing.md -->
[a file nobody wrote](../files/missing.md)

<!-- expect: link.files.targetDoesNotExist | ERROR | 27 | ../files/missing/ | The referenced file or directory '../files/missing/' does not exist. Resolved target path: {corpus}/files/missing -->
[a directory nobody made](../files/missing/)

## A file written as a directory

<!-- expect: link.files.filePathWithTrailingSlash | ERROR | 39 | ../files/guide.md/ | The file path '../files/guide.md/' ends with a '/' which usually indicates a directory, not a file. Please remove the trailing '/' if you mean a file. -->
[a file whose path ends with a slash](../files/guide.md/)

## A directory written as a file

<!-- expect: link.files.directoryPathWithoutTrailingSlash | WARNING | 39 | ../files/notes | The given path '../files/notes' is a directory, not a file. Please add a trailing '/' if you really mean a directory. -->
[a directory whose path has no slash](../files/notes)

## A path that names its place on its own

<!-- expect: link.files.absoluteTargetPath | ERROR | 41 | /files/guide.md | The path '/files/guide.md' names a file or directory of one machine, so it leads nowhere for anybody else reading this document. Please use a path relative to this document instead. -->
[a path starting at the root of a disk](/files/guide.md)

## What is right

[a file that is there](../files/guide.md)

[a directory that is there](../files/notes/)
