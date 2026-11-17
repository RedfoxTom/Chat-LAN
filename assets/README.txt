This is where the CA goes that the app trusts on top of the system certificates.

  cp /path/to/your-lan-ca.crt assets/ca.crt

The build also works without this file: then the app only uses system CAs (real
certificates) or "Accept any certificate" in the settings - fine for trying things
out, not for production.

The file is in .gitignore because every LAN installation has its own CA.
