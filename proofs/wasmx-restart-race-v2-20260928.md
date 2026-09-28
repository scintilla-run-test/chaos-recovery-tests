# wasm-xprs restart/race proof v2

Retry with the bearer token exported into concurrent `sh -c` workers. The first harness failed before sending requests because of shell quoting, not product behavior.
