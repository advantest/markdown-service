# The documents the rules of this service are held against

Every document under `documents/` is validated by `ValidationCorpusTest`, and every finding the service
reports has to be declared in the document that provokes it. A finding nobody declared fails the run, and so
does a declaration nothing produces.

## How a document declares what it expects

A declaration is an HTML comment standing directly above the line it is about:

```markdown
<!-- expect: link.emptyTarget | ERROR | 1 | [a link]() | The link has no target. -->
[a link]()
```

The five parts, separated by a vertical bar:

| part         | meaning                                                                |
|:-------------|:-----------------------------------------------------------------------|
| rule         | the issue type identifier without its `com.advantest.markdown.` prefix |
| severity     | `ERROR`, `WARNING` or `INFO`                                           |
| column       | the column the finding starts in, counted from 1                       |
| covered text | the text the finding marks, from its start to its end                  |
| message      | the reported message, in full                                          |

A `\n` in the covered text or in the message stands for a line break, `\t` for a tab and `\\` for a
backslash. Several declarations above the same line are read in the order they stand in, and a declaration
belongs to the next line that is not itself a declaration.

A message naming the place a target was resolved to would say something different on every machine, so the
directory this corpus lies in is written as `{corpus}` and every path is written with forward slashes.

Neither the offset of a finding nor the line ending of a file is written down. A finding is placed by its
line number, by its column within that line and by the text it covers, so that a document keeps saying the
same thing whether it is stored with `LF` or with `CRLF`.

## How a declaration is written down

A failing run prints every finding as the declaration that would state it, with the line it belongs to in
front of it:

```text
line 8: <!-- expect: link.emptyTarget | ERROR | 31 | () | The target file path or URL is empty. -->
```

Such a line is copied into the document above line 8, without the `line 8: ` in front of it. Where a message
names line numbers of the document itself, they move as soon as a declaration is inserted above them, so the
run after the insertion says what they have become.

A document may say one thing about itself, on a line of its own:

```markdown
<!-- validated without a document location -->
```

It is then validated as text that is nowhere, which is what the rule about an unknown document location
needs.

## What belongs here

A document per subject, small enough to read at once, and written for this repository: it names nothing that
is not in it. Where a rule is about a file that exists, that file lies under `files/`.

Every rule this service has is provoked by at least one document, which `ValidationCorpusTest` checks: a
rule no document declares fails the run and has to be given a document, or a place in one that is about the
same thing.

The addresses of the corpus are answered by the test itself, so that a run needs no network and says the
same thing every day. `https://silent.example.org/guide` stays silent, `https://example.org/gone` answers
that there is nothing there, and every other address answers that it is there.

A line holding a declaration may be longer than the 120 characters this repository asks of a Markdown file.
A message is quoted in full and never wrapped, because a wrapped message could no longer be held against
what the service says character by character.
