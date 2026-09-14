# Links that point at a definition

A reference link names a label and the document defines what the label stands for. This document is about a
label nothing defines, a label that is not written down at all, and a definition that cannot be found twice.

## A label nothing defines

<!-- expect: link.missingReferenceDefinition | ERROR | 34 | missing-definition | There is no link reference definition for the reference link label "missing-definition". Expected a link reference definition like "[ReferenceLinkLabel]: https://plantuml.com" -->
[a link to a label nobody wrote][missing-definition]

## A label that is not written down

<!-- expect: link.ambiguousReference | ERROR | 1 | [a link with an empty label][] | There is either no link reference definition for the reference link label "a link with an empty label" (assuming this is a collapsed reference link like "[ReferenceLinkLabel][]") or the reference link label is empty (assuming this is a full reference link like "[Some text][ReferenceLinkLabel]"). Expected a link reference definition like "[a link with an empty label]: https://plantuml.com" or a reference link "[a link with an empty label][ReferenceLinkLabel]" to an existing link reference definition. -->
[a link with an empty label][]

<!-- expect: link.ambiguousReference | ERROR | 1 | [some text][] | There is either no link reference definition for the reference link label "some text" (assuming this is a collapsed reference link like "[ReferenceLinkLabel][]") or the reference link label is empty (assuming this is a full reference link like "[Some text][ReferenceLinkLabel]"). Expected a link reference definition like "[some text]: https://plantuml.com" or a reference link "[some text][ReferenceLinkLabel]" to an existing link reference definition. -->
[some text][]

<!-- expect: link.emptyReferenceLabel | ERROR | 3 | [] | The reference link label is empty. Please create a link reference definition like "[ReferenceLinkLabel]: https://plantuml.com" and use that reference link label in your link, e.g. "[your link text][ReferenceLinkLabel]" or "[ReferenceLinkLabel]". -->
[][]

<!-- expect: link.emptyReferenceLabel | ERROR | 4 |   | The reference link label is empty. Please create a link reference definition like "[ReferenceLinkLabel]: https://plantuml.com" and use that reference link label in your link, e.g. "[your link text][ReferenceLinkLabel]" or "[ReferenceLinkLabel]". -->
[][ ]

## A definition whose identifier is none

[a link to a label written with a character Markdown does not allow][a+label]

## An identifier two definitions share

[a link to a label defined twice][defined-twice]

## What is defined

[a link to something defined below][the-guide]

<!-- expect: linkReferenceDefinition.invalidIdentifier | ERROR | 2 | a+label | The link reference definition identifier "a+label" is invalid. It has to contain at least one non-space character and is allowed to contain any number of the following characters: letters ([A-Za-z]), digits ([0-9]), hyphens ("-"), underscores ("_"), colons (":"), periods ("."), slashes ("/"), spaces (" "). -->
[a+label]: ../files/guide.md
<!-- expect: linkReferenceDefinition.duplicateIdentifier | ERROR | 2 | defined-twice | The link reference definition identifier "defined-twice" is not unique. The same identifier is used in the following lines: 40, 42 -->
[defined-twice]: ../files/guide.md
<!-- expect: linkReferenceDefinition.duplicateIdentifier | ERROR | 2 | defined-twice | The link reference definition identifier "defined-twice" is not unique. The same identifier is used in the following lines: 40, 42 -->
[defined-twice]: ../files/guide.md
[the-guide]: ../files/guide.md
[a label with a space in it]: ../files/guide.md
