# Anchors, the places inside a document

A heading may carry an identifier, and a link may point at one. This document is about an identifier that is
no identifier, an identifier two headings share, and a link pointing at something that is not there.

<!-- expect: anchor.invalidIdentifier | ERROR | 47 | #1st-section | The anchor identifier "1st-section" is invalid. It has to contain at least one character, must start with a letter, and is allowed to contain any number of the following characters in the remainder: letters ([A-Za-z]), digits ([0-9]), hyphens ("-"), underscores ("_"), colons (":"), and periods ("."). -->
## A heading with an identifier that is none {#1st-section}

<!-- expect: anchor.duplicateIdentifier | ERROR | 38 | #shared | The anchor identifier "shared" is not unique. The same identifier is used in the following lines: 10, 13 -->
## A heading with a good identifier {#shared}

<!-- expect: anchor.duplicateIdentifier | ERROR | 51 | #shared | The anchor identifier "shared" is not unique. The same identifier is used in the following lines: 10, 13 -->
## Another heading with the very same identifier {#shared}

## Links into a document

[a place that the guide declares](../files/guide.md#a-section)

<!-- expect: anchor.notFound | ERROR | 55 | #no-such-section | There is no section with the anchor 'no-such-section' in the Markdown document '{corpus}/files/guide.md', or the anchor is invalid. -->
[a place the guide does not declare](../files/guide.md#no-such-section)

## A place inside something nobody looks into

<!-- expect: anchor.noValidatorForTarget | WARNING | 48 | #detail | The anchor 'detail' cannot be checked, because nothing answers for a target like '../files/diagram.png'. -->
[a place inside a picture](../files/diagram.png#detail)

## A place inside something that cannot be read

<!-- expect: anchor.noValidatorForTarget | WARNING | 45 | #detail | The anchor 'detail' cannot be checked, because nothing answers for a target like '../files/notes/'. -->
[a place inside a directory](../files/notes/#detail)

## A place inside something that looks like a document and is none

<!-- expect: link.files.directoryPathWithoutTrailingSlash | WARNING | 52 | ../files/chapters.md | The given path '../files/chapters.md' is a directory, not a file. Please add a trailing '/' if you really mean a directory. -->
<!-- expect: link.targetCannotBeRead | ERROR | 52 | ../files/chapters.md | The referenced file '../files/chapters.md' cannot be read, so the anchor 'detail' cannot be looked for. Resolved target path: {corpus}/files/chapters.md -->
[a place inside a directory named like a document](../files/chapters.md#detail)
