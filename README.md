# Markdown Language Service (markdown-service)

Markdown language service is a Java library providing all Markdown language features like
parsing and checking Markdown source code as well as translating Markdown to HTML (rednering HTML).

## Scope

- **Language features** — parsing, validation, completion, formatting, following links, and refactoring,
  code mining for Markdown, built on `markdown-core`.
- **Extension points** — a `ServiceLoader` SPI so extensions can add validators,
  completions and other optional features without living in this repository.

