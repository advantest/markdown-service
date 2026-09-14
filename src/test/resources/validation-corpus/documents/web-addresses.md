# Addresses that are asked whether they are there

An address is asked once the caller says so. This document is about an address that stays silent and one
that answers that there is nothing behind it.

## An address that does not answer

<!-- expect: link.http.webAddressDoesNotAnswer | WARNING | 34 | https://silent.example.org/guide | The referenced web address 'https://silent.example.org/guide' seems not to exist. (Error message: no host of that name is known) -->
[an address nothing answers for](https://silent.example.org/guide)

## An address that answers that there is nothing there

<!-- expect: link.http.webAddressNotReachable | ERROR | 27 | https://example.org/gone | The referenced web address 'https://example.org/gone' is not reachable (HTTP status code 404). -->
[an address that is gone](https://example.org/gone)

## An address that answers

[an address that is there](https://example.org/guide)
