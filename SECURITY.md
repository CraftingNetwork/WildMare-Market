# Security Policy

## Scope

WildMare Market is a Minecraft-only virtual economy system. It must not be modified or presented as a real-money brokerage, wallet, exchange, deposit, withdrawal, cash-out, cryptocurrency transfer, gambling, or investment-advice service.

## Credential Handling

- Store API keys only in `providers.yml` on the server.
- Restrict file permissions to the server account.
- Do not commit production keys.
- Do not expose configuration files through web panels without access control.
- Console status output never intentionally prints API keys.

## Operational Controls

- Grant administrator permissions only to trusted operators.
- Back up the database before migrations or destructive commands.
- Keep Paper/Purpur, Vault, the economy provider, Java, and database software patched.
- Use a database account restricted to the WildMare Market schema.
- Use TLS for remote databases whenever supported.
- Review `wm_audit_logs` and transaction failures after economy or database incidents.

## Reporting

When reporting a vulnerability, include the plugin version, server implementation/version, Java version, storage type, reproduction steps, and sanitized logs. Never include API keys, database passwords, player personal data, or webhook tokens.
