"""Verify the version-independent Spring Boot artifact used by both Docker builds."""
from pathlib import Path
import sys
import re
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def check(require_artifact=False):
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    pom = ET.parse(ROOT / "cloudnest-server/pom.xml")
    name = pom.findtext("m:build/m:finalName", namespaces=ns)
    if name != "cloudnest-server":
        raise ValueError("Maven finalName must be cloudnest-server")
    expected = {
        "cloudnest-server/Dockerfile": "COPY --from=build /build/target/cloudnest-server.jar app.jar",
        "deploy/Dockerfile.server-runtime": "COPY cloudnest-server/target/cloudnest-server.jar app.jar",
        ".dockerignore": "!cloudnest-server/target/cloudnest-server.jar",
    }
    for file, line in expected.items():
        if line not in (ROOT / file).read_text(encoding="utf-8").splitlines():
            raise ValueError(f"Deployment artifact mismatch in {file}")
    for file in (ROOT / "scripts").glob("*.py"):
        if re.search(r"cloudnest-server-\d[\w.-]*\.jar", file.read_text(encoding="utf-8")):
            raise ValueError(f"Version-specific artifact reference in {file.name}")
    if require_artifact:
        with zipfile.ZipFile(ROOT / "cloudnest-server/target/cloudnest-server.jar") as jar:
            manifest = jar.read("META-INF/MANIFEST.MF").decode()
            if "Start-Class: com.cloudnest.CloudNestApplication" not in manifest:
                raise ValueError("Artifact is not the executable CloudNest application")
            if not any(name.startswith("BOOT-INF/lib/") for name in jar.namelist()):
                raise ValueError("Artifact has not been repackaged by Spring Boot")
    print("Deployment artifact contract passed" + ("; executable JAR verified." if require_artifact else "."))


if __name__ == "__main__":
    check("--require-artifact" in sys.argv)
