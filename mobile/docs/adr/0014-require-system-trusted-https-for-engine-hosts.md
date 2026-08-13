# Require system-trusted HTTPS for engine hosts

Mobile Clients will connect only to Engine Hosts over HTTPS whose certificate chain and host name are trusted by the operating system, using either a public CA or a private CA installed by the user. Pairing conveys the Engine URL and one-time authorization material but never weakens transport trust; cleartext HTTP, trust-all modes, and certificate-error bypasses are not supported, even on a private network or VPN.
