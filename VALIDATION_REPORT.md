# Validation Report

Validation performed in the generation environment:

- Java 21 syntax/type compilation of all 63 production source files passed against local compatibility stubs for Paper, Vault, PlaceholderAPI, Adventure, HikariCP, Gson, and JDBC APIs.
- Java 21 syntax/type compilation of all 4 JUnit test source files passed against local JUnit signatures.
- Pure-Java smoke checks passed for fee calculation, period parsing, sparkline generation, quote normalization, and tradability.
- All 10 YAML resources parsed successfully.
- `pom.xml` parsed successfully as XML.
- The five migration resources listed in `migrations/index.txt` executed successfully against an in-memory SQLite database and created all expected tables.
- No `TODO`, `FIXME`, pseudo-code marker, or omitted-code marker was found.

## Environment Limitation

A dependency-resolving Maven build was not run in this sandbox because the Maven executable and unrestricted artifact-network access were unavailable. Run the following in a normal development or CI environment before deployment:

```bash
mvn clean test package
```

Then install `target/WildMareMarket-1.0.0.jar` on a staging Paper/Purpur server and complete the integration checklist in `docs/TESTING.md`.
