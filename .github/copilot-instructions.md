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
