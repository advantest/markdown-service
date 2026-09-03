# Custom instructions for this repository

Instructions for GitHub Copilot when working in `markdown-service`. They apply to every session in
this repository, in addition to what a task asks for.

## Committing

- **Separate a file rename from a change of its content.** A rename goes into its own commit, with
  the file content untouched, so that git recognises it as a rename and every client shows it as
  one. The change of the content follows in the next commit.
- **One concern per commit.** A commit does one thing, and its message says that thing. If a change
  brings an unrelated fix along, that fix goes into its own commit — a reader looking for it later
  will not find it under a message that never mentions it.
- Write the commit message in whole sentences, saying what the commit achieves rather than which
  files it touches.

## Markdown files

- Limit a line to 120 characters. A table is exempt where the syntax would otherwise break.
- Align the columns of a table with spaces so that it is readable in the source as well.

## This repository knows nothing about where its code came from

Code is ported into this repository from other projects. What it says about itself must not depend
on them:

- **Do not name another project** in a comment, a message, a test name or a documentation file of
  this repository, neither the project the code was ported from nor a project reading this library.
- **Do not describe the behaviour of another project**, and do not justify a behaviour of this
  library by saying what another one does. A comment says what this code does and why that is
  right here; a reader must be able to understand it without knowing any other repository.
- **Do not point to an issue, a decision or a document that lives elsewhere.** An identifier like
  `I-01` or `D-24` means nothing to a reader of this repository.

A relation between this library and another project is documented in the repository that owns that
relation, not here. Where a behaviour looks arbitrary without its history, the comment states the
rule that is implemented, e.g. that a message keeps a double space, not who once produced it.

The differential test package `com.advantest.markdown.service.differential` is the one exception.
Its whole purpose is to compare this service with a recorded run of the implementation it was
extracted from, so it has to name that implementation and may point to the decisions and issues
recorded elsewhere — without them the code cannot be understood. The package is temporary and goes
away once the extraction is finished. Nothing outside it may rely on that exception.
