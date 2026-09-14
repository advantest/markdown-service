# Targets that are empty or name a scheme nobody knows

A link needs a target, and a target naming a scheme says who answers for it. This document is about the two
ways that can go wrong before anybody looks at what the target names.

## A link without a target

<!-- expect: link.emptyTarget | ERROR | 31 | () | The target file path or URL is empty. -->
[a link with no target at all]()

An image is a link as well:

<!-- expect: link.emptyTarget | ERROR | 35 | () | The target file path or URL is empty. -->
![a picture with no target at all]()

## A target naming a scheme nobody answers for

<!-- expect: link.unknownTargetScheme | WARNING | 45 | htp://example.org/guide | The referenced target 'htp://example.org/guide' names the scheme 'htp', which nothing knows here, so the target is neither resolved nor checked. Please check the scheme for a typing mistake. -->
[written with a scheme that does not exist](htp://example.org/guide)

<!-- expect: link.unknownTargetScheme | WARNING | 45 | gopher://example.org/guide | The referenced target 'gopher://example.org/guide' names the scheme 'gopher', which nothing knows here, so the target is neither resolved nor checked. Please check the scheme for a typing mistake. -->
[a scheme this service knows nothing about](gopher://example.org/guide)

## A web address that is none

<!-- expect: link.http.invalidWebAddress | ERROR | 41 | https:// | The referenced web address 'https://' seems not to be a valid HTTP web address. Expected authority at index 8: https:// -->
[an address that cannot be read at all](https://)
