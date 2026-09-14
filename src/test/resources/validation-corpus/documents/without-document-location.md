<!-- validated without a document location -->
# Text that is nowhere

A caller may hand over Markdown without saying where it came from, e.g. an editor validating what is typed
before it is saved. A target written as a path has nothing to be resolved against then.

<!-- expect: link.files.unknownDocumentLocation | ERROR | 45 | files/guide.md | The referenced file or directory 'files/guide.md' cannot be resolved, because the location of the document containing this link is unknown. -->
[a file next to a document that is nowhere](files/guide.md)
