# Markdown Language Service (markdown-service)

Markdown language service is a Java library providing all Markdown language features like
parsing and checking Markdown source code as well as translating Markdown to HTML (rendering HTML).

## Scope

- **Language features** — parsing, validation, completion, formatting, following links, and refactoring,
  code mining for Markdown, built on `markdown-core`.
- **Extension points** — a caller adds validators, extensions of the parser and the renderer and other
  customisations through the builder of the service, so that they need not live in this repository.

## Building

```shell
./mvnw verify
```

The build needs Java 21 and Maven 3.9, which the Maven wrapper fetches. It takes `markdown-core` from the
GitHub Packages registry of its repository, which needs a GitHub token in the Maven settings, or from the
local Maven repository once `./mvnw install` was run in a checkout of `markdown-core`.
