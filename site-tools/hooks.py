"""MkDocs hooks: keep contributor-facing links that leave `docs/` working on the published site."""

import re
import posixpath
import shutil
from pathlib import Path

REPO = "https://github.com/adityamparikh/solr-intellij-plugin/blob/main"

# Pages excluded from the site (see `exclude_docs`) that other pages still link to.
_EXCLUDED = ("Module.md", "demo/README.md")

_LINK = re.compile(r"\]\(([^)\s#]+)(#[^)\s]*)?\)")


def on_page_markdown(markdown, page, **_):
    """Send links that leave the built site, or reach a page excluded from it, to GitHub.

    A link is resolved against the page's own directory, so `../../specs/x.md` from `how-to/` and
    `../specs/x.md` from `docs/` both reach the repository's `specs/x.md`.
    """
    base = posixpath.dirname(page.file.src_uri)

    def rewrite(m):
        target, anchor = m.group(1), m.group(2) or ""
        if "://" in target or target.startswith(("mailto:", "#")):
            return m.group(0)
        resolved = posixpath.normpath(posixpath.join(base, target))
        if resolved.startswith("../api/"):
            return m.group(0)  # the Dokka tree is generated into the site, not into the repository
        if resolved.startswith(".."):
            repo_path = resolved[3:]  # one level up from docs/ is the repository root
        elif resolved in _EXCLUDED or resolved.startswith(("presentation/", "design/", "demo/")):
            repo_path = "docs/" + resolved
        else:
            return m.group(0)
        return f"]({REPO}/{repo_path}{anchor})"

    return _LINK.sub(rewrite, markdown)


def on_post_build(config, **_):
    """Copy the Dokka HTML (`./gradlew dokkaGenerate`) into the site under /api/, if it was generated."""
    dokka = Path("build/dokka/html")
    if dokka.is_dir():
        shutil.copytree(dokka, Path(config["site_dir"]) / "api", dirs_exist_ok=True)
    else:
        print("WARNING - build/dokka/html not found; the site will have no /api/. Run ./gradlew dokkaGenerate first.")
