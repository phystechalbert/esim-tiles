"""Creates the release keystore and keystore.properties next to this script. Prints no secrets."""
import os
import secrets
import subprocess
import sys

here = os.path.dirname(os.path.abspath(__file__))
store = os.path.join(here, "release.jks")
props = os.path.join(here, "keystore.properties")
if os.path.exists(store):
    sys.exit("release.jks already exists, not overwriting")

password = secrets.token_urlsafe(24)
keytool = r"C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot\bin\keytool.exe"
subprocess.run([
    keytool, "-genkeypair", "-keystore", store, "-alias", "esimtiles",
    "-keyalg", "RSA", "-keysize", "4096", "-validity", "36500",
    "-storepass", password, "-keypass", password,
    "-dname", "CN=eSIM Tiles, O=phystechalbert",
], check=True, capture_output=True)
with open(props, "w", encoding="utf-8") as f:
    f.write("storeFile=release.jks\nalias=esimtiles\n")
    f.write("storePassword=" + password + "\nkeyPassword=" + password + "\n")
print("keystore created")
