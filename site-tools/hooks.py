"""MkDocs hooks: keep contributor-facing links that leave `docs/` working on the published site."""

import re
import shutil
from pathlib import Path

REPO = "https://github.com/adityamparikh/solr-intellij-plugin/blob/main"

# `](../specs/...)` and `](../src/...)` point outside docs_dir; send them to the repository.
_OUTSIDE = re.compile(r"\]\(\.\./((?:specs|src|demo|build\.gradle\.kts|README\.md)[^)]*)\)")


def on_page_markdown(markdown, **_):
    """Rewrite links that escape `docs/` into GitHub blob URLs."""
    return _OUTSIDE.sub(lambda m: f"]({REPO}/{m.group(1)})", markdown)


def on_post_build(config, **_):
    """Copy the Dokka HTML (`./gradlew dokkaGenerate`) into the site under /api/, if it was generated."""
    dokka = Path("build/dokka/html")
    if dokka.is_dir():
        shutil.copytree(dokka, Path(config["site_dir"]) / "api", dirs_exist_ok=True)
    else:
        print("WARNING - build/dokka/html not found; the site will have no /api/. Run ./gradlew dokkaGenerate first.")
