# Extra trusted certificates (optional)

Antivirus HTTPS scanning (e.g. Avast/AVG "Web Shield") or a company proxy re-signs HTTPS traffic with its own
root certificate. Windows trusts it, but Docker containers don't, so image builds fail with
`certificate verify failed` / `PKIX path building failed`.

Fix: export that root certificate as a PEM `.crt` file into this folder, then rebuild
(`docker compose build`). Every `*.crt` here is trusted by the build and runtime containers.
The files are git-ignored - they belong to your machine only. Leave the folder empty if you don't need it.

Find the certificate: open `certmgr.msc` -> Trusted Root Certification Authorities -> Certificates,
look for your antivirus/proxy name, right-click -> All Tasks -> Export -> "Base-64 encoded X.509 (.CER)",
and save it here with a `.crt` extension.
