# Retained synthetic cases

These JSON files are data-only regression examples for a later implementation. They contain no tokens, secrets, customer data, live responses or executable TypeScript. No production endpoints were called.

- [ozon-cases.json](ozon-cases.json): the old allowlist plus representative local request/response and expected-decoding cases. `rawBody` intentionally stays a string in the lossless-number case so an ordinary JSON parser cannot round its values before the new decoder sees them. Money/identifier expectations use strings.
- [import-integrity.json](import-integrity.json): six synthetic invoice lines and acceptance/rejection expectations; no source image is retained.

Each case names its baseline source. Ozon cases were reduced or renamed to remove even fake buyer/contact/address fields. Other than those reductions and fixed synthetic IDs/dates, shapes come from the old local tests. They are not a complete external API test suite and are not proof that Ozon still serves these contracts. See [dated caveat](../ozon-fetching.md).
