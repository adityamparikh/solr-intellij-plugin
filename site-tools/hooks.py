"""MkDocs hooks: keep contributor-facing links that leave `docs/` working on the published site."""

import re

REPO = "https://github.com/adityamparikh/solr-intellij-plugin/blob/main"

# `](../specs/...)` and `](../src/...)` point outside docs_dir; send them to the repository.
_OUTSIDE = re.compile(r"\]\(\.\./((?:specs|src|demo|build\.gradle\.kts|README\.md)[^)]*)\)")


def on_page_markdown(markdown, **_):
    """Rewrite links that escape `docs/` into GitHub blob URLs."""
    return _OUTSIDE.sub(lambda m: f"]({REPO}/{m.group(1)})", markdown)
